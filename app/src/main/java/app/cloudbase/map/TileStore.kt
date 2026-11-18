package app.cloudbase.map

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/** Disk cache of tile PNGs plus the network fetch that fills it. Knows nothing about bitmaps. */
class TileStore(val root: File, val userAgent: String) {
    /** When true the network is never touched; only cached tiles are returned. */
    @Volatile var offline: Boolean = false

    /** Tile file path -> time of last failed download, to stop a dead connection spinning the radio. */
    private val failedAt = ConcurrentHashMap<String, Long>()

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
        if (f.length() > 0) return f
        if (!allowNetwork || offline) return null

        val now = clockMs()
        val failed = failedAt[f.path]
        if (failed != null && now - failed < RETRY_AFTER_MS) return null

        if (download(source.url(z, wx, y), f)) {
            failedAt.remove(f.path)
            return f
        }
        failedAt.values.removeIf { now - it >= RETRY_AFTER_MS }
        failedAt[f.path] = now
        return null
    }

    fun sizeBytes(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

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
        val PNG_MAGIC = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
    }
}
