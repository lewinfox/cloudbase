package app.cloudbase.map

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Async bitmap provider for [MapView]: [get] answers from memory and queues a background
 * load (disk, then network via [store]) on a miss. Bitmaps are RGB_565 to halve memory.
 */
class TileLayer(
    private val store: TileStore,
    private val source: TileSource,
    cacheBytes: Int = 24 * 1024 * 1024,
) {
    /** Invoked on the main thread; a burst of loads causes a single call. */
    @Volatile var onTileLoaded: (() -> Unit)? = null

    private val cache = object : LruCache<Long, Bitmap>(cacheBytes) {
        override fun sizeOf(key: Long, value: Bitmap) = value.byteCount
    }

    // queue, inFlight and the lock-guarded writes of [stopped] are protected by [lock].
    private val lock = ReentrantLock()
    private val notEmpty = lock.newCondition()
    private val queue = LinkedHashSet<Long>() // wanted and needing the network; oldest first
    // Wanted and already on disk. Served first: with no signal, every network attempt can sit in
    // a timeout, and the tiles we do have must not wait behind them.
    private val diskQueue = LinkedHashSet<Long>()
    private val inFlight = HashSet<Long>()    // being loaded right now
    @Volatile private var stopped = false

    private val main = Handler(Looper.getMainLooper())
    private val callbackPending = AtomicBoolean(false)
    private val deliver = Runnable {
        callbackPending.set(false)
        onTileLoaded?.invoke()
    }

    private val threads = List(THREADS) { i ->
        Thread(::workLoop, "tile-loader-$i").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
            start()
        }
    }

    /**
     * Marks the start of a draw pass: tiles still queued from the previous pass are dropped, because
     * the pass about to run re-requests every tile that is still visible.
     */
    fun beginFrame() = lock.withLock { queue.clear(); diskQueue.clear() }

    /** Returns the tile if in memory, else null and schedules a load. [x] must already be wrapped. */
    fun get(z: Int, x: Int, y: Int): Bitmap? {
        val key = pack(z, x, y)
        cache.get(key)?.let { return it }
        val onDisk = store.has(source, z, x, y)
        lock.withLock {
            if (!stopped && key !in inFlight && (if (onDisk) diskQueue else queue).add(key)) notEmpty.signal()
        }
        return null
    }

    /** Memory-only lookup that never schedules a load (used for drawing ancestors). */
    fun peek(z: Int, x: Int, y: Int): Bitmap? = cache.get(pack(z, x, y))

    fun shutdown() {
        lock.withLock {
            stopped = true
            queue.clear()
            diskQueue.clear()
            notEmpty.signalAll()
        }
        main.removeCallbacks(deliver)
        onTileLoaded = null
        cache.evictAll()
    }

    private fun workLoop() {
        while (true) {
            val key = lock.withLock {
                while (queue.isEmpty() && diskQueue.isEmpty() && !stopped) notEmpty.awaitUninterruptibly()
                if (stopped) return
                val from = if (diskQueue.isEmpty()) queue else diskQueue
                val k = from.first()
                from.remove(k)
                inFlight.add(k)
                k
            }
            try {
                load(key)
            } finally {
                lock.withLock { inFlight.remove(key) }
            }
        }
    }

    private fun load(key: Long) {
        val z = (key shr Z_SHIFT).toInt()
        val x = (key shr X_SHIFT and MASK).toInt()
        val y = (key and MASK).toInt()
        val file = store.fetch(source, z, x, y, true) ?: return
        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
        val bmp = BitmapFactory.decodeFile(file.path, opts)
        if (bmp == null) {
            file.delete() // corrupt on disk; let a later request re-download it
            return
        }
        if (stopped) return
        cache.put(key, bmp)
        if (callbackPending.compareAndSet(false, true)) main.post(deliver)
    }

    private fun pack(z: Int, x: Int, y: Int): Long =
        (z.toLong() shl Z_SHIFT) or (x.toLong() shl X_SHIFT) or y.toLong()

    private companion object {
        const val THREADS = 2
        const val Z_SHIFT = 58
        const val X_SHIFT = 29
        const val MASK = (1L shl 29) - 1
    }
}
