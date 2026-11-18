package app.cloudbase.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import app.cloudbase.Hub
import app.cloudbase.core.Geo
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

/** The lift/sink heat map: red where the air was rising, blue where it was sinking. */
class ThermalOverlay(context: Context) : MapView.Overlay, ThermalMap.Visitor {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val minR = context.dp(3f)
    private var canvas: Canvas? = null
    private var proj: MapView.Projection? = null
    private var radius = 0f

    override fun draw(canvas: Canvas, p: MapView.Projection) {
        if (!Hub.settings.showThermals) return
        val t = Hub.computer.thermals
        val now = Hub.computer.fix?.timeMs ?: return
        if (t.size == 0) return
        this.canvas = canvas
        proj = p
        // Slightly larger than half a cell so neighbouring cells merge into a smooth patch.
        radius = max(minR, (t.cellM * 0.75 / p.metresPerPixel).toFloat())
        t.forEach(p.minLat, p.minLon, p.maxLat, p.maxLon, now, this)
        this.canvas = null
    }

    override fun cell(lat: Double, lon: Double, climb: Double, ageMs: Long) {
        val strength = min(1.0, abs(climb) / FULL_SCALE_MS)
        if (strength < 0.1) return
        // Fade with age: an old thermal has probably moved on or died.
        val fresh = 1.0 - 0.7 * min(1.0, ageMs / FADE_MS)
        paint.color = Theme.climbColor(climb, (190 * strength * fresh).toInt())
        val p = proj ?: return
        canvas?.drawCircle(p.x(lon), p.y(lat), radius, paint)
    }

    companion object {
        /** Lift or sink at which the colour is fully saturated. */
        const val FULL_SCALE_MS = 3.0
        const val FADE_MS = 15 * 60_000.0
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
        if (!f.track.isNaN() && f.speed > 2) {
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
