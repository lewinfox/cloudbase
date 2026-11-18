package app.cloudbase.map

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Disk cache of tile PNGs plus the network fetch that fills it. Knows nothing about bitmaps.
 *
 * The cache is least-recently-used with a size limit ([maxBytes]): a file's modification time is
 * its "last used" stamp, refreshed when the tile is read, and [trim] deletes the stalest tiles
 * first. A pilot's usual flying areas therefore stay on the phone and places looked at once fade
 * away. Sources in [keepForever] (terrain heights, which airspace limits depend on and which are
 * few and small) are never evicted and do not count towards the limit.
 */
class TileStore(val root: File, val userAgent: String) {
    /** When true the network is never touched; only cached tiles are returned. */
    @Volatile var offline: Boolean = false

    /** Tile file path -> time of last failed download, to stop a dead connection spinning the radio. */
    private val failedAt = ConcurrentHashMap<String, Long>()

    /** Size limit for evictable tiles, bytes. */
    @Volatile var maxBytes: Long = Long.MAX_VALUE

    /** Source ids that are never evicted. */
    @Volatile var keepForever: Set<String> = emptySet()

    /** Bytes downloaded since the last [trim]; a trim is started when this passes [TRIM_AFTER_BYTES]. */
    private val sinceTrim = AtomicLong(0)
    private val trimming = AtomicBoolean(false)

    /** Wall-clock milliseconds for last-used stamps; replaceable in tests. */
    internal var wallClockMs: () -> Long = { System.currentTimeMillis() }

    /** Monotonic milliseconds; replaceable in tests. */
    internal var clockMs: () -> Long = { System.nanoTime() / 1_000_000 }

    /** Where a tile lives (or would live). [x] is used as given, so pass it already wrapped. */
    fun file(source: TileSource, z: Int, x: Int, y: Int): File = File(root, "${source.id}/$z/$x/$y.png")

    fun has(source: TileSource, z: Int, x: Int, y: Int): Boolean = file(source, z, x, y).length() > 0

    /**
     * Blocking. Returns the cached file, downloading it first if needed and allowed; null on failure.
     * [x] wraps modulo 2^z; [y] outside the world gives null.
     */
    fun fetch(source: TileSource, z: Int, x: Int, y: Int, allowNetwork: Boolean): File? {
        if (z !in 0..MAX_Z) return null
        val n = 1 shl z
        if (y !in 0 until n) return null
        val wx = Math.floorMod(x, n)
        val f = file(source, z, wx, y)
        if (f.length() > 0) {
            // Mark as used, but at most once a day per tile: this is a metadata write on a path
            // that runs for every tile shown.
            val now = wallClockMs()
            if (now - f.lastModified() > TOUCH_AFTER_MS) f.setLastModified(now)
            return f
        }
        if (!allowNetwork || offline) return null

        val now = clockMs()
        val failed = failedAt[f.path]
        if (failed != null && now - failed < RETRY_AFTER_MS) return null

        if (download(source.url(z, wx, y), f)) {
            failedAt.remove(f.path)
            if (sinceTrim.addAndGet(f.length()) > TRIM_AFTER_BYTES) trimInBackground()
            return f
        }
        failedAt.values.removeIf { now - it >= RETRY_AFTER_MS }
        failedAt[f.path] = now
        return null
    }

    fun sizeBytes(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /** Size of the tiles the limit applies to. */
    fun evictableBytes(): Long = evictable().sumOf { it.length() }

    /**
     * Blocking: if the evictable tiles exceed [maxBytes], deletes the least recently used until
     * they are down to [TRIM_TO] of it (so the next few downloads do not trigger another pass).
     * Returns the number of bytes freed. Safe to call from any thread; concurrent calls are skipped.
     */
    fun trim(): Long {
        if (!trimming.compareAndSet(false, true)) return 0L
        try {
            sinceTrim.set(0)
            val limit = maxBytes
            // Size and time are read once per file: both can change under us while we sort.
            val files = evictable().map { Entry(it, it.length(), it.lastModified()) }.toMutableList()
            var total = files.sumOf { it.size }
            if (total <= limit) return 0L
            val target = (limit * TRIM_TO).toLong()
            files.sortBy { it.usedAt }
            var freed = 0L
            for (e in files) {
                if (total <= target) break
                if (e.file.delete()) { total -= e.size; freed += e.size }
            }
            return freed
        } finally {
            trimming.set(false)
        }
    }

    private class Entry(val file: File, val size: Long, val usedAt: Long)

    private fun evictable(): Sequence<File> {
        val keep = keepForever
        val dirs = root.listFiles { f -> f.isDirectory && f.name !in keep } ?: return emptySequence()
        return dirs.asSequence().flatMap { it.walkTopDown() }.filter { it.isFile && it.name.endsWith(".png") }
    }

    private fun trimInBackground() {
        if (trimming.get()) return
        sinceTrim.set(0)
        Thread({ trim() }, "tile-trim").apply { isDaemon = true; priority = Thread.MIN_PRIORITY; start() }
    }

    fun clear() {
        root.deleteRecursively()
        failedAt.clear()
    }

    /** Writes to a per-thread temp file and renames, so [dest] is never a partial tile. */
    private fun download(url: String, dest: File): Boolean {
        val tmp = File(dest.parentFile, "${dest.name}.${Thread.currentThread().id}.tmp")
        var conn: HttpURLConnection? = null
        try {
            dest.parentFile?.mkdirs()
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("User-Agent", userAgent)
            }
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return false
            val expected = conn.contentLengthLong
            var total = 0L
            conn.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(8192)
                    while (true) {
                        val r = input.read(buf)
                        if (r < 0) break
                        out.write(buf, 0, r)
                        total += r
                    }
                }
            }
            // A truncated body or a captive-portal HTML page must not become a "tile".
            if (total == 0L || (expected >= 0 && total != expected) || !isPng(tmp)) return false
            return tmp.renameTo(dest)
        } catch (e: IOException) {
            return false
        } finally {
            conn?.disconnect()
            tmp.delete()
        }
    }

    private fun isPng(f: File): Boolean {
        val head = ByteArray(PNG_MAGIC.size)
        val n = f.inputStream().use { it.read(head) }
        return n == head.size && head.contentEquals(PNG_MAGIC)
    }

    private companion object {
        const val MAX_Z = 24
        const val CONNECT_TIMEOUT_MS = 8_000
        const val READ_TIMEOUT_MS = 15_000
        const val RETRY_AFTER_MS = 60_000L
        const val TOUCH_AFTER_MS = 24 * 3_600_000L
        const val TRIM_AFTER_BYTES = 16L * 1024 * 1024
        const val TRIM_TO = 0.9
        val PNG_MAGIC = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
    }
}
