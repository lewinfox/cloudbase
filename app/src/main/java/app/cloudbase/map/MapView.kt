package app.cloudbase.map

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import app.cloudbase.core.Geo
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * North-up slippy map. Redraws only on input, [setCenter] or tile arrival: no timers, no animation.
 * All methods must be called on the main thread.
 */
class MapView(context: Context, val layer: TileLayer, val source: TileSource) : View(context) {

    /** Drawn above the tiles, in list order. Overlays that change must call [invalidate] themselves. */
    interface Overlay {
        fun draw(canvas: Canvas, p: Projection)
    }

    /**
     * Screen <-> geographic mapping for the current view state; one instance, updated in place.
     * Across the antimeridian [minLon] > [maxLon], and [x] picks the copy of the world nearest the centre.
     */
    class Projection internal constructor() {
        private var cx = 0.0 // world pixels of the view centre
        private var cy = 0.0
        private var world = Mercator.worldSize(0.0)

        var width = 0; private set
        var height = 0; private set
        var zoom = 0.0; private set
        /** Ground resolution at the view centre's latitude. */
        var metresPerPixel = 0.0; private set

        val minLat: Double get() = lat(height.toFloat())
        val maxLat: Double get() = lat(0f)
        val minLon: Double get() = lon(0f)
        val maxLon: Double get() = lon(width.toFloat())

        internal fun update(lat: Double, lon: Double, zoom: Double, width: Int, height: Int) {
            this.zoom = zoom
            this.width = width
            this.height = height
            world = Mercator.worldSize(zoom)
            cx = Mercator.x(lon, zoom)
            cy = Mercator.y(lat, zoom)
            metresPerPixel = Mercator.metresPerPixel(lat, zoom)
        }

        // x and y are called per vertex by overlays (thousands of times a frame), so they use the
        // cached world size rather than Mercator's general functions, which recompute 2^zoom.
        fun x(lon: Double): Float {
            var dx = (lon + 180.0) / 360.0 * world - cx
            if (dx > world / 2) dx -= world else if (dx < -world / 2) dx += world
            return (width / 2.0 + dx).toFloat()
        }

        fun y(lat: Double): Float {
            val s = sin(lat.coerceIn(-Mercator.MAX_LAT, Mercator.MAX_LAT) * (PI / 180.0))
            return (height / 2.0 + (0.5 - ln((1 + s) / (1 - s)) / (4 * PI)) * world - cy).toFloat()
        }

        fun lat(y: Float): Double = Mercator.lat(cy + y - height / 2.0, zoom)

        fun lon(x: Float): Double = Geo.norm180(Mercator.lon(cx + x - width / 2.0, zoom))
    }

    val projection = Projection()

    /** Setting any of these redraws. Zoom is clamped to the source's range plus 2 levels of overzoom. */
    var centerLat: Double = 0.0
        set(v) { field = v.coerceIn(-Mercator.MAX_LAT, Mercator.MAX_LAT); changed() }
    var centerLon: Double = 0.0
        set(v) { field = Geo.norm180(v); changed() }
    var zoom: Double = source.minZoom.toDouble()
        set(v) { field = v.coerceIn(source.minZoom.toDouble(), source.maxZoom + OVERZOOM); changed() }

    /** True while the map follows the aircraft; a user pan (or a zoom that moves the centre) clears it. */
    var follow: Boolean = true
    var onFollowChanged: ((Boolean) -> Unit)? = null
    var onTap: ((lat: Double, lon: Double) -> Unit)? = null
    val overlays: MutableList<Overlay> = ArrayList()

    private val density = resources.displayMetrics.density
    private val dst = RectF()
    private val src = Rect()
    private val tilePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val attributionBg = Paint().apply { color = Color.argb(160, 255, 255, 255) }
    private val attributionText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.DKGRAY
        textSize = 9 * density
    }
    private val attributionWidth = attributionText.measureText(source.attribution)

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            panBy(dx, dy)
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            onTap?.invoke(projection.lat(e.y), projection.lon(e.x))
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            zoomAbout(zoom + 1, e.x, e.y)
            return true
        }
    })

    private val scaleGestures = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            zoomAbout(zoom + ln(d.scaleFactor.toDouble()) / LN2, d.focusX, d.focusY)
            return true
        }
    }).apply { isQuickScaleEnabled = false } // would fight with double-tap zoom

    init {
        projection.update(centerLat, centerLon, zoom, 0, 0)
    }

    /**
     * Moves the centre (also when [follow] is false: the caller decides whether to follow).
     * Ignored, with no redraw, if the move is under one pixel at the current zoom.
     */
    fun setCenter(lat: Double, lon: Double) {
        val world = Mercator.worldSize(zoom)
        val dx = abs(Mercator.x(lon, zoom) - Mercator.x(centerLon, zoom)).let { min(it, world - it) }
        val dy = abs(Mercator.y(lat, zoom) - Mercator.y(centerLat, zoom))
        if (max(dx, dy) < 1.0) return
        centerLat = lat
        centerLon = lon
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        layer.onTileLoaded = ::invalidate
    }

    override fun onDetachedFromWindow() {
        layer.onTileLoaded = null
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = changed()

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleGestures.onTouchEvent(event)
        gestures.onTouchEvent(event)
        return true
    }

    override fun onDraw(canvas: Canvas) {
        layer.beginFrame()
        canvas.drawColor(PLACEHOLDER)
        drawTiles(canvas)
        for (i in overlays.indices) overlays[i].draw(canvas, projection)
        drawAttribution(canvas)
    }

    private fun drawTiles(canvas: Canvas) {
        val z = floor(zoom + 0.3).toInt().coerceIn(source.minZoom, source.maxZoom)
        val tileSize = Mercator.TILE * 2.0.pow(zoom - z) // on-screen size of one tile
        val n = 1 shl z
        // Screen position of tile (0, 0): the view centre minus the centre's world position.
        val ox = width / 2.0 - Mercator.x(centerLon, zoom)
        val oy = height / 2.0 - Mercator.y(centerLat, zoom)
        val x0 = floor(-ox / tileSize).toInt()
        val x1 = floor((width - ox) / tileSize).toInt()
        val y0 = max(0, floor(-oy / tileSize).toInt())
        val y1 = min(n - 1, floor((height - oy) / tileSize).toInt())
        for (ty in y0..y1) {
            // Edges are rounded from the shared grid line, so neighbours abut without seams.
            val top = Math.round(oy + ty * tileSize).toFloat()
            val bottom = Math.round(oy + (ty + 1) * tileSize).toFloat()
            for (tx in x0..x1) {
                dst.set(
                    Math.round(ox + tx * tileSize).toFloat(), top,
                    Math.round(ox + (tx + 1) * tileSize).toFloat(), bottom,
                )
                drawTile(canvas, z, Math.floorMod(tx, n), ty)
            }
        }
    }

    /** Draws the tile, or failing that the best cached ancestor scaled up; else leaves the placeholder. */
    private fun drawTile(canvas: Canvas, z: Int, x: Int, y: Int) {
        val bmp = layer.get(z, x, y)
        if (bmp != null) {
            canvas.drawBitmap(bmp, null, dst, tilePaint)
            return
        }
        for (dz in 1..MAX_ANCESTOR_LEVELS) {
            if (z - dz < source.minZoom) return
            val ancestor = layer.peek(z - dz, x shr dz, y shr dz) ?: continue
            val size = ancestor.width shr dz // our tile's footprint inside the ancestor
            val sx = (x and ((1 shl dz) - 1)) * size
            val sy = (y and ((1 shl dz) - 1)) * size
            src.set(sx, sy, sx + size, sy + size)
            canvas.drawBitmap(ancestor, src, dst, tilePaint)
            return
        }
    }

    private fun drawAttribution(canvas: Canvas) {
        val pad = 3 * density
        val baseline = height - pad
        canvas.drawRect(0f, baseline - attributionText.textSize - pad, attributionWidth + 2 * pad, height.toFloat(), attributionBg)
        canvas.drawText(source.attribution, pad, baseline - attributionText.descent(), attributionText)
    }

    private fun changed() {
        projection.update(centerLat, centerLon, zoom, width, height)
        invalidate()
    }

    /** Drags the map by a finger movement of ([dx], [dy]) pixels (scroll distance convention). */
    private fun panBy(dx: Float, dy: Float) {
        val cx = Mercator.x(centerLon, zoom) + dx
        val cy = Mercator.y(centerLat, zoom) + dy
        centerLon = Mercator.lon(cx, zoom)
        centerLat = Mercator.lat(cy, zoom)
        userMoved()
    }

    /** Sets the zoom keeping the geographic point under ([fx], [fy]) fixed on screen. */
    private fun zoomAbout(newZoom: Double, fx: Float, fy: Float) {
        val z = newZoom.coerceIn(source.minZoom.toDouble(), source.maxZoom + OVERZOOM)
        if (z == zoom) return
        val lat = projection.lat(fy)
        val lon = projection.lon(fx)
        val cx = Mercator.x(lon, z) - (fx - width / 2.0)
        val cy = Mercator.y(lat, z) - (fy - height / 2.0)
        zoom = z
        centerLon = Mercator.lon(cx, z)
        centerLat = Mercator.lat(cy, z)
        if (abs(fx - width / 2f) >= 1f || abs(fy - height / 2f) >= 1f) userMoved()
    }

    private fun userMoved() {
        if (!follow) return
        follow = false
        onFollowChanged?.invoke(false)
    }

    private companion object {
        const val OVERZOOM = 2.0
        const val MAX_ANCESTOR_LEVELS = 4
        const val PLACEHOLDER = 0xFFE4E4E4.toInt()
        val LN2 = ln(2.0)
    }
}
