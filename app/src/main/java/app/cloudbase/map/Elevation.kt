package app.cloudbase.map

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Terrain height from Terrarium tiles (elevation = R*256 + G + B/256 - 32768). Each tile is decoded
 * once into 256x256 whole metres; a few decoded tiles are kept in memory.
 */
class Elevation(val store: TileStore, val zoom: Int = 11) {
    /** Invoked on the main thread when a tile has become available; coalesced. */
    @Volatile var onTileLoaded: (() -> Unit)? = null

    private val source = TileSource.TERRARIUM
    private val z = zoom.coerceIn(source.minZoom, source.maxZoom)

    // Access-ordered LRU of decoded tiles; [lock] guards it and [pending].
    private val lock = Any()
    private val tiles = object : LinkedHashMap<Long, ShortArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ShortArray>) = size > MAX_TILES
    }
    private val pending = HashSet<Long>()

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "elevation-loader").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }
    private val main = Handler(Looper.getMainLooper())
    private val callbackPending = AtomicBoolean(false)
    private val deliver = Runnable {
        callbackPending.set(false)
        onTileLoaded?.invoke()
    }

    /**
     * Ground elevation AMSL in metres, bilinearly interpolated within one tile (values clamp at the
     * tile edge). NaN if the tile is not in memory yet; a background load is then started, unless the
     * store is offline and the tile is not cached.
     */
    fun at(lat: Double, lon: Double): Double {
        val n = 1 shl z
        val px = Mercator.x(lon, z.toDouble()) // world pixels
        val py = Mercator.y(lat, z.toDouble())
        val tx = Math.floorMod(floor(px / TILE).toInt(), n)
        val ty = floor(py / TILE).toInt().coerceIn(0, n - 1)
        val key = key(tx, ty)

        val data = synchronized(lock) { tiles[key] }
        if (data == null) {
            request(key, tx, ty)
            return Double.NaN
        }
        // Pixel i has its sample at its centre, i + 0.5.
        val u = (px - floor(px / TILE) * TILE - 0.5).coerceIn(0.0, TILE - 1.0)
        val v = (py - ty * TILE - 0.5).coerceIn(0.0, TILE - 1.0)
        val i0 = u.toInt()
        val j0 = v.toInt()
        val i1 = minOf(i0 + 1, TILE - 1)
        val j1 = minOf(j0 + 1, TILE - 1)
        val fu = u - i0
        val fv = v - j0
        val top = data[j0 * TILE + i0] * (1 - fu) + data[j0 * TILE + i1] * fu
        val bottom = data[j1 * TILE + i0] * (1 - fu) + data[j1 * TILE + i1] * fu
        return top * (1 - fv) + bottom * fv
    }

    fun shutdown() {
        executor.shutdownNow()
        main.removeCallbacks(deliver)
        onTileLoaded = null
        synchronized(lock) { tiles.clear() }
    }

    private fun request(key: Long, tx: Int, ty: Int) {
        if (store.offline && !store.has(source, z, tx, ty)) return
        synchronized(lock) { if (!pending.add(key)) return }
        try {
            executor.execute { load(key, tx, ty) }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            synchronized(lock) { pending.remove(key) } // shut down
        }
    }

    private fun load(key: Long, tx: Int, ty: Int) {
        try {
            val file = store.fetch(source, z, tx, ty, true) ?: return
            val decoded = decode(file.path)
            if (decoded == null) {
                file.delete() // corrupt on disk; let a later request re-download it
                return
            }
            synchronized(lock) { tiles[key] = decoded }
            if (callbackPending.compareAndSet(false, true)) main.post(deliver)
        } finally {
            synchronized(lock) { pending.remove(key) }
        }
    }

    private fun decode(path: String): ShortArray? {
        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val bmp = BitmapFactory.decodeFile(path, opts) ?: return null
        try {
            if (bmp.width != TILE || bmp.height != TILE) return null
            val px = IntArray(TILE * TILE)
            bmp.getPixels(px, 0, TILE, 0, 0, TILE, TILE)
            return ShortArray(px.size) { i ->
                val p = px[i]
                val metres = ((p shr 16) and 0xFF) * 256.0 + ((p shr 8) and 0xFF) + (p and 0xFF) / 256.0 - 32768.0
                metres.roundToInt().toShort()
            }
        } finally {
            bmp.recycle()
        }
    }

    private fun key(tx: Int, ty: Int): Long = (tx.toLong() shl 32) or ty.toLong()

    private companion object {
        const val TILE = Mercator.TILE
        const val MAX_TILES = 8
    }
}
