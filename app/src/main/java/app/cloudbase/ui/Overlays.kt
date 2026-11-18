package app.cloudbase.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import app.cloudbase.Hub
import app.cloudbase.core.FlightComputer
import app.cloudbase.core.Geo
import app.cloudbase.core.LocalProjection
import app.cloudbase.core.airspace.Airspace
import app.cloudbase.core.airspace.AlertLevel
import app.cloudbase.core.airspace.Relevance
import app.cloudbase.core.flight.ThermalMap
import app.cloudbase.map.MapView
import app.cloudbase.ui.Theme.dp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** How an airspace is coloured, on the map and in the side view alike. */
object AirspaceStyle {
    private val PROHIBITED = setOf("P", "R", "Q", "GP", "TSA", "TRA", "TFR")
    val RED = Color.parseColor("#C62828")
    val BLUE = Color.parseColor("#1E5AA8")
    val GREY = Color.parseColor("#7A8089")

    /**
     * Name to show. Many names already say what the airspace is ("NZA635, WELLINGTON, CTA C");
     * the type is only added when the name does not. The exported class is left out on purpose:
     * for New Zealand it is unreliable, and where it matters it is in the name.
     */
    fun title(a: Airspace): String =
        if (a.type.isEmpty() || a.name.contains(a.type, ignoreCase = true)) a.name else "${a.type} ${a.name}"

    /** A few characters for labelling a block in the side view. */
    fun short(a: Airspace): String = a.name.substringBefore(',').take(10)

    fun color(a: Airspace, relevance: Relevance): Int = when {
        relevance != Relevance.ALERT -> GREY
        a.cls in PROHIBITED || a.type in PROHIBITED -> RED
        else -> BLUE
    }
}

/**
 * Airspace outlines. The list of nearby airspaces only changes every few km, so relevance and
 * colour are worked out once per list rather than per frame.
 */
class AirspaceOverlay(context: Context) : MapView.Overlay {
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fill = Paint().apply { style = Paint.Style.FILL }
    private val path = Path()
    private val thin = context.dp(1.2f)
    private val thick = context.dp(2.2f)
    private val alertWidth = context.dp(4f)

    private var source: List<Airspace>? = null
    private var shown: List<Airspace> = emptyList()
    private var colors = IntArray(0)
    private var alertable = BooleanArray(0)

    /** Airspaces drawn at the moment, for tap lookup. */
    val visible: List<Airspace> get() = shown

    private fun sync() {
        val nearby = Hub.computer.nearby
        if (nearby === source) return
        source = nearby
        val policy = Hub.policy()
        val keep = ArrayList<Airspace>()
        val cols = ArrayList<Int>()
        val alert = ArrayList<Boolean>()
        for (a in nearby) {
            val r = policy.relevance(a)
            if (r == Relevance.HIDE) continue
            keep.add(a); cols.add(AirspaceStyle.color(a, r)); alert.add(r == Relevance.ALERT)
        }
        shown = keep
        colors = cols.toIntArray()
        alertable = alert.toBooleanArray()
    }

    /** Force a rebuild after the policy changed (e.g. an airspace was ignored). */
    fun invalidate() { source = null }

    override fun draw(canvas: Canvas, p: MapView.Projection) {
        sync()
        val statuses = Hub.computer.statuses
        for (i in shown.indices) {
            val a = shown[i]
            if (a.maxLat < p.minLat || a.minLat > p.maxLat || a.maxLon < p.minLon || a.minLon > p.maxLon) continue
            path.rewind()
            path.moveTo(p.x(a.lons[0]), p.y(a.lats[0]))
            for (k in 1 until a.lats.size) path.lineTo(p.x(a.lons[k]), p.y(a.lats[k]))
            path.close()
            var level = AlertLevel.NONE
            for (s in statuses) if (s.airspace === a) level = s.level
            val c = colors[i]
            if (alertable[i]) {
                fill.color = Color.argb(if (level == AlertLevel.NONE) 22 else 60, Color.red(c), Color.green(c), Color.blue(c))
                canvas.drawPath(path, fill)
            }
            stroke.color = c
            stroke.strokeWidth = when { level != AlertLevel.NONE -> alertWidth; alertable[i] -> thick; else -> thin }
            canvas.drawPath(path, stroke)
        }
    }
}

/**
 * The lift/sink heat map: a smooth cloud over the air that has been flown through, violet to
 * near-black where it was sinking, and pale yellow through orange and red to crimson where it
 * was rising.
 *
 * Every thermal-map cell sits on one regular lattice (they all drift together), so the cells map
 * straight onto the pixels of a small bitmap, which [HeatGrid] smooths and the canvas scales up.
 */
class ThermalOverlay(context: Context) : MapView.Overlay, ThermalMap.Visitor {
    private val grid = HeatGrid(SIZE)
    private val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dst = RectF()

    // Lattice bookkeeping for the pass in progress.
    private var proj = LocalProjection(0.0, 0.0)
    private var cell = 30.0
    private var hasPhase = false
    private var x0 = 0.0   // metres (east, north) of one lattice point: the first cell seen
    private var y0 = 0.0
    private var cx = 0     // lattice index of the view centre relative to that point
    private var cy = 0
    private var pass = LIVE

    override fun draw(canvas: Canvas, p: MapView.Projection) {
        if (!Hub.settings.showThermals) return
        val live = Hub.computer.thermals
        val history = Hub.computer.thermalHistory
        val now = Hub.computer.fix?.timeMs ?: return
        if (live.size == 0 && history.size == 0) return

        // The bitmap covers SIZE cells around the middle of the view (about 2.9 km at 30 m cells).
        val midLat = (p.minLat + p.maxLat) / 2
        val midLon = p.lon(p.width / 2f)
        proj = LocalProjection(midLat, midLon)
        cell = live.cellM
        hasPhase = false
        grid.clear()
        val half = SIZE / 2 * cell
        val s = proj.lat(-half); val w = proj.lon(-half); val n = proj.lat(half); val e = proj.lon(half)
        // Recent air first, drifting with the wind; then older samples where they were met. A
        // sample is in exactly one of the two, so nothing is drawn twice.
        pass = LIVE
        live.forEach(s, w, n, e, now, this)
        pass = HISTORY
        history.forEach(s, w, n, e, now, this)
        if (!hasPhase) return
        grid.render()
        bitmap.setPixels(grid.pixels, 0, SIZE, 0, 0, SIZE, SIZE)

        // Pixel (i, j) is the lattice point (cx - SIZE/2 + i, cy + SIZE/2 - j): north is up.
        val west = x0 + (cx - SIZE / 2 - 0.5) * cell
        val east = west + SIZE * cell
        val north = y0 + (cy + SIZE / 2 + 0.5) * cell
        val south = north - SIZE * cell
        dst.set(p.x(proj.lon(west)), p.y(proj.lat(north)), p.x(proj.lon(east)), p.y(proj.lat(south)))
        canvas.drawBitmap(bitmap, null, dst, paint)
    }

    override fun cell(lat: Double, lon: Double, climb: Double, ageMs: Long) {
        // Live samples fade from full strength to the history level over the live window, so the
        // hand-over between the two layers does not show as a jump in brightness.
        val liveWindow = FlightComputer.LIVE_THERMAL_MS
        val strength = if (pass == LIVE) {
            1f - (1f - HISTORY_STRENGTH) * min(1.0, ageMs.toDouble() / liveWindow).toFloat()
        } else {
            if (ageMs < liveWindow) return // still shown by the live layer
            HISTORY_STRENGTH
        }
        val x = proj.x(lon)
        val y = proj.y(lat)
        if (!hasPhase) {
            hasPhase = true
            x0 = x; y0 = y
            cx = Math.round(-x0 / cell).toInt()
            cy = Math.round(-y0 / cell).toInt()
        }
        // The two layers' lattices are offset by the drift; history is snapped to the nearest
        // pixel of the live one (at most half a cell out, which is nothing at that age).
        val ix = Math.round((x - x0) / cell).toInt() - cx + SIZE / 2
        val iy = cy + SIZE / 2 - Math.round((y - y0) / cell).toInt()
        grid.splat(ix, iy, climb.toFloat(), strength)
    }

    companion object {
        /** How strongly old, ground-fixed samples are drawn next to fresh ones: faded, still legible. */
        const val HISTORY_STRENGTH = 0.6f
        const val SIZE = 96
        private const val LIVE = 0
        private const val HISTORY = 1
    }
}

/** Track flown, the glider itself, where it will be in a minute, and the best nearby lift. */
class AircraftOverlay(context: Context) : MapView.Overlay {
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.argb(170, 17, 20, 24); strokeWidth = context.dp(1.6f)
        strokeJoin = Paint.Join.ROUND
    }
    private val aheadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Theme.TEXT; strokeWidth = context.dp(1.5f)
        pathEffect = DashPathEffect(floatArrayOf(context.dp(6f), context.dp(5f)), 0f)
    }
    private val gliderFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.ACCENT }
    private val gliderEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE; strokeWidth = context.dp(2f) }
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Theme.LIFT; strokeWidth = context.dp(2.5f) }
    private val path = Path()
    private val glider = Path()
    private val size = context.dp(13f)
    private val coreR = context.dp(14f)
    private val cross = context.dp(6f)
    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Theme.TEXT; strokeWidth = context.dp(2.5f); strokeCap = Paint.Cap.ROUND }
    private var pts = FloatArray(0)

    override fun draw(canvas: Canvas, p: MapView.Projection) {
        val c = Hub.computer
        val f = c.fix ?: return

        val trail = c.trail
        if (trail.size > 1) {
            path.rewind()
            path.moveTo(p.x(trail.lon(0)), p.y(trail.lat(0)))
            for (i in 1 until trail.size) path.lineTo(p.x(trail.lon(i)), p.y(trail.lat(i)))
            canvas.drawPath(path, trailPaint)
        }

        c.thermalCore?.let { core ->
            val x = p.x(core.lon); val y = p.y(core.lat)
            canvas.drawCircle(x, y, coreR, corePaint)
            canvas.drawLine(x - coreR * 0.5f, y, x + coreR * 0.5f, y, corePaint)
            canvas.drawLine(x, y - coreR * 0.5f, x, y + coreR * 0.5f, corePaint)
        }

        val x = p.x(f.lon); val y = p.y(f.lat)
        val impact = if (Hub.settings.showGlide) c.glideImpact else null
        if (impact != null) {
            // Glide line: dashed to an × where the present glide meets the ground.
            val ix = p.x(impact.lon); val iy = p.y(impact.lat)
            canvas.drawLine(x, y, ix, iy, aheadPaint)
            canvas.drawLine(ix - cross, iy - cross, ix + cross, iy + cross, crossPaint)
            canvas.drawLine(ix - cross, iy + cross, ix + cross, iy - cross, crossPaint)
        } else if (!f.track.isNaN() && f.speed > 2 && !c.circling) {
            // No glide line (climbing, or switched off): show where we will be in a minute. Not
            // while circling, when a straight line ahead means nothing.
            val ahead = Geo.destination(f.lat, f.lon, f.track, f.speed * Hub.settings.lookaheadS)
            canvas.drawLine(x, y, p.x(ahead.lon), p.y(ahead.lat), aheadPaint)
        }
        glider.rewind()
        if (f.track.isNaN()) {
            canvas.drawCircle(x, y, size * 0.6f, gliderFill)
            canvas.drawCircle(x, y, size * 0.6f, gliderEdge)
        } else {
            glider.moveTo(0f, -size); glider.lineTo(size * 0.7f, size * 0.8f); glider.lineTo(0f, size * 0.35f); glider.lineTo(-size * 0.7f, size * 0.8f); glider.close()
            canvas.save()
            canvas.translate(x, y)
            canvas.rotate(f.track.toFloat())
            canvas.drawPath(glider, gliderFill)
            canvas.drawPath(glider, gliderEdge)
            canvas.restore()
        }
    }
}
