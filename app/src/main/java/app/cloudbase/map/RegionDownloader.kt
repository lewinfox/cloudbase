package app.cloudbase.map

import android.os.Handler
import android.os.Looper
import kotlin.math.floor

/**
 * Pre-downloads every tile covering a lat/lon box so the map works with no signal. One download at a
 * time. Boxes must not cross the antimeridian (minLon <= maxLon). Use from the main thread.
 */
class RegionDownloader(val store: TileStore) {
    /** [alreadyCached] is only counted for plans up to [CACHE_COUNT_LIMIT] tiles, else 0. */
    class Plan(val tiles: Int, val alreadyCached: Int)

    @Volatile var running: Boolean = false
        private set

    // Lazy so that plan() works without Android (plain JVM tests).
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private var worker: Thread? = null
    @Volatile private var cancelled = false

    fun plan(source: TileSource, minLat: Double, minLon: Double, maxLat: Double, maxLon: Double, zooms: IntRange): Plan {
        val tiles = count(source, minLat, minLon, maxLat, maxLon, zooms)
        var cached = 0
        if (tiles <= CACHE_COUNT_LIMIT) {
            forEachTile(source, minLat, minLon, maxLat, maxLon, zooms) { z, x, y -> if (store.has(source, z, x, y)) cached++ }
        }
        return Plan(tiles, cached)
    }

    /**
     * Runs on its own thread at ~4 downloaded tiles/s; cached tiles are skipped without delay.
     * [progress] (done, total) and [done] (ok) are called on the main thread, [done] exactly once.
     * [done] gets false if refused (already running, offline, empty or over [maxTiles]), cancelled,
     * or after 3 tiles in a row fail (no signal: stop rather than keep the radio busy).
     */
    fun start(
        source: TileSource, minLat: Double, minLon: Double, maxLat: Double, maxLon: Double, zooms: IntRange,
        maxTiles: Int = 3000, progress: (Int, Int) -> Unit, done: (Boolean) -> Unit,
    ) {
        val total = count(source, minLat, minLon, maxLat, maxLon, zooms)
        if (running || store.offline || total == 0 || total > maxTiles) {
            main.post { done(false) }
            return
        }
        running = true
        cancelled = false
        worker = Thread {
            val ok = download(source, minLat, minLon, maxLat, maxLon, zooms, total, progress)
            main.post {
                running = false
                done(ok)
            }
        }.apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
            start()
        }
    }

    /** Stops after the tile in progress; [start]'s done callback then receives false. */
    fun cancel() {
        cancelled = true
        worker?.interrupt()
    }

    private fun download(
        source: TileSource, minLat: Double, minLon: Double, maxLat: Double, maxLon: Double, zooms: IntRange,
        total: Int, progress: (Int, Int) -> Unit,
    ): Boolean {
        var count = 0
        var failures = 0
        var lastPost = 0L
        var ok = true
        try {
            forEachTile(source, minLat, minLon, maxLat, maxLon, zooms) { z, x, y ->
                if (cancelled || !ok) return@forEachTile
                val needsNetwork = !store.has(source, z, x, y)
                if (store.fetch(source, z, x, y, true) != null) failures = 0 else if (++failures >= MAX_FAILURES) ok = false
                count++
                val now = System.nanoTime() / 1_000_000
                if (now - lastPost >= PROGRESS_INTERVAL_MS) {
                    lastPost = now
                    val done = count
                    main.post { progress(done, total) }
                }
                if (needsNetwork) Thread.sleep(TILE_INTERVAL_MS)
            }
        } catch (e: InterruptedException) {
            return false
        }
        if (ok && !cancelled) main.post { progress(total, total) }
        return ok && !cancelled
    }

    private fun count(source: TileSource, minLat: Double, minLon: Double, maxLat: Double, maxLon: Double, zooms: IntRange): Int {
        var total = 0L
        for (z in clampZooms(source, zooms)) total += Range(z, minLat, minLon, maxLat, maxLon).count()
        return total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    private inline fun forEachTile(
        source: TileSource, minLat: Double, minLon: Double, maxLat: Double, maxLon: Double, zooms: IntRange,
        action: (z: Int, x: Int, y: Int) -> Unit,
    ) {
        for (z in clampZooms(source, zooms)) {
            val r = Range(z, minLat, minLon, maxLat, maxLon)
            for (y in r.y0..r.y1) for (x in r.x0..r.x1) action(z, x, y)
        }
    }

    private fun clampZooms(source: TileSource, zooms: IntRange) =
        maxOf(zooms.first, source.minZoom)..minOf(zooms.last, source.maxZoom)

    /** Inclusive tile index range of the box at zoom [z]. */
    private class Range(z: Int, minLat: Double, minLon: Double, maxLat: Double, maxLon: Double) {
        private val last = (1 shl z) - 1
        val x0 = tile(Mercator.x(minLon, z.toDouble()))
        val x1 = tile(Mercator.x(maxLon, z.toDouble()))
        val y0 = tile(Mercator.y(maxLat, z.toDouble())) // north edge has the smaller y
        val y1 = tile(Mercator.y(minLat, z.toDouble()))

        private fun tile(px: Double) = floor(px / Mercator.TILE).toInt().coerceIn(0, last)

        fun count(): Long = maxOf(0, x1 - x0 + 1).toLong() * maxOf(0, y1 - y0 + 1)
    }

    private companion object {
        const val CACHE_COUNT_LIMIT = 50_000
        const val TILE_INTERVAL_MS = 250L
        const val PROGRESS_INTERVAL_MS = 250L
        const val MAX_FAILURES = 3
    }
}
