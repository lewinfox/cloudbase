package app.cloudbase.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import app.cloudbase.Hub
import app.cloudbase.core.Geo
import app.cloudbase.core.airspace.Airspace
import app.cloudbase.core.airspace.AlertLevel
import app.cloudbase.core.airspace.AltLimit
import app.cloudbase.core.airspace.AltRef
import app.cloudbase.core.airspace.Relevance
import app.cloudbase.core.airspace.Slab
import app.cloudbase.core.airspace.crossSection
import app.cloudbase.ui.Theme.dp
import app.cloudbase.ui.Theme.sp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The view from the side: a vertical slice through the air along the direction of travel, with
 * the terrain below, airspace as blocks, and the glider's projected path. Answers "how much
 * room do I have above me, and what am I about to fly into?".
 *
 * The slice is recomputed once per GPS fix in [refresh]; onDraw only paints what was computed.
 */
class SideView(context: Context) : View(context) {
    private val terrainPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.TERRAIN }
    private val slabFill = Paint().apply { style = Paint.Style.FILL }
    private val slabEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = context.dp(1.5f) }
    private val gridPaint = Paint().apply { color = Theme.LINE; strokeWidth = 1f }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.MUTED; textSize = context.sp(10.5f); typeface = Theme.CONDENSED }
    private val namePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = context.sp(11f); typeface = Theme.CONDENSED_BOLD }
    private val headPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.TEXT; textSize = context.sp(13f); typeface = Theme.CONDENSED_BOLD }
    private val gliderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.ACCENT }
    private val pathPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Theme.TEXT; strokeWidth = context.dp(1.5f)
        pathEffect = DashPathEffect(floatArrayOf(context.dp(6f), context.dp(5f)), 0f)
    }
    private val path = Path()

    /** Distance shown ahead of the glider, metres. Tap cycles through [RANGES]. */
    private var rangeIndex = 0
    private val rangeM get() = RANGES[rangeIndex]
    private var bearing = 0.0
    private val terrain = DoubleArray(SAMPLES) { Double.NaN }
    private var slabs: List<Slab> = emptyList()
    private var relevance: List<Relevance> = emptyList()
    private var altMsl = Double.NaN
    private var stdToMsl = 0.0
    private var speed = 0.0
    private var climb = 0.0
    private var bottom = 0.0
    private var top = 1000.0
    private var step = 250.0
    private var headline = ""
    private var headlineColor = Theme.TEXT
    private var hasData = false

    init {
        setBackgroundColor(Theme.BG)
        setOnClickListener {
            rangeIndex = (rangeIndex + 1) % RANGES.size
            refresh()
        }
    }

    private val backM get() = rangeM * BEHIND
    private val spanM get() = rangeM * (1 + BEHIND)

    fun refresh() {
        val c = Hub.computer
        val f = c.fix
        if (f == null || c.altMsl.isNaN()) { hasData = false; invalidate(); return }
        hasData = true
        // Keep looking the way we were last going when the track becomes unknown (stopped, or parked into wind).
        if (!f.track.isNaN() && f.speed > 1.5) bearing = f.track
        altMsl = c.altMsl
        stdToMsl = if (c.pressureAlt.isNaN()) 0.0 else c.altMsl - c.pressureAlt
        speed = f.speed
        climb = c.climb.let { if (it.isNaN()) 0.0 else it }

        val start = Geo.destination(f.lat, f.lon, bearing + 180.0, backM)
        var lo = altMsl
        for (i in 0 until SAMPLES) {
            val p = Geo.destination(start.lat, start.lon, bearing, spanM * i / (SAMPLES - 1))
            val e = Hub.terrainAt(p.lat, p.lon)
            terrain[i] = e
            if (!e.isNaN()) lo = min(lo, e)
        }

        val policy = Hub.policy()
        val candidates = ArrayList<Airspace>()
        for (a in c.nearby) if (policy.relevance(a) != Relevance.HIDE) candidates.add(a)
        slabs = crossSection(candidates, start.lat, start.lon, bearing, spanM)
        relevance = slabs.map { policy.relevance(it.airspace) }

        // Vertical window: from the ground to just past the top of the layer we are in. Below all
        // airspace that is the floor overhead; inside an airspace it is that airspace's ceiling,
        // so the next boundary up is always in view and nothing beyond it takes up room. The
        // window grows by itself as each boundary is crossed.
        val boundary = nextBoundaryAbove()
        val hi = if (boundary.isNaN()) altMsl + OPEN_SKY_M else boundary
        bottom = lo - 60
        // "Just past": enough above the boundary to see what starts there.
        top = max(hi + max(MIN_PAST_M, (hi - bottom) * PAST_FRACTION), altMsl + MIN_PAST_M)
        step = when { top - bottom > 3000 -> 1000.0; top - bottom > 1300 -> 500.0; else -> 250.0 }
        bottom = floor(bottom / step) * step

        val u = Units(Hub.settings)
        val h = c.headroom
        val inside = c.statuses.firstOrNull { it.level == AlertLevel.INSIDE }
        if (inside != null) {
            headline = "Inside ${AirspaceStyle.title(inside.airspace)}"
            headlineColor = Theme.DANGER
        } else if (h == null) {
            headline = "No airspace above"
            headlineColor = Theme.MUTED
        } else {
            headline = "${u.altWithUnit(h.second)} below ${AirspaceStyle.title(h.first)} (${h.first.lower.text})"
            headlineColor = when {
                h.second < Hub.settings.verticalMarginM -> Theme.DANGER
                h.second < 3 * Hub.settings.verticalMarginM -> Theme.WARN
                else -> Theme.TEXT
            }
        }
        invalidate()
    }

    /**
     * The first airspace boundary above the glider, AMSL: the lowest floor overhead, or the
     * ceiling of an airspace we are inside, whichever comes first. NaN if there is none. Only
     * airspace that alerts counts; informational sectors do not define the layers.
     */
    private fun nextBoundaryAbove(): Double {
        val ground = groundAt(backM)
        var best = Double.NaN
        for (i in slabs.indices) {
            val s = slabs[i]
            if (relevance[i] != Relevance.ALERT || s.fromM > backM || s.toM < backM) continue
            val floor = limitMsl(s.airspace.lower, ground)
            val ceiling = if (s.airspace.upper.isUnlimited) Double.NaN else limitMsl(s.airspace.upper, ground)
            val edge = if (floor > altMsl) floor else if (!ceiling.isNaN() && ceiling > altMsl) ceiling else Double.NaN
            if (!edge.isNaN() && (best.isNaN() || edge < best)) best = edge
        }
        return best
    }

    /** Altitude AMSL of a limit at this moment, at a place where the ground is at [ground]. */
    private fun limitMsl(l: AltLimit, ground: Double): Double = when (l.ref) {
        AltRef.MSL -> l.metres
        AltRef.STD -> l.metres + stdToMsl
        AltRef.GND -> l.metres + if (ground.isNaN()) 0.0 else ground
    }

    private fun xOf(distM: Double): Float = (padL + (width - padL) * (distM / spanM)).toFloat()
    private fun yOf(alt: Double): Float = (height - padB - (height - padB - padT) * ((alt - bottom) / (top - bottom))).toFloat()
    private fun groundAt(distM: Double): Double = terrain[(distM / spanM * (SAMPLES - 1)).toInt().coerceIn(0, SAMPLES - 1)]

    private val padL = context.dp(34f)
    private val padB = context.dp(4f)
    private val padT = context.dp(22f)

    override fun onDraw(canvas: Canvas) {
        canvas.drawLine(0f, 0f, width.toFloat(), 0f, gridPaint)
        if (!hasData) {
            canvas.drawText("Side view appears with a GPS fix", context.dp(12f), height / 2f, textPaint)
            return
        }
        val u = Units(Hub.settings)

        // Altitude grid.
        var a = bottom + step
        while (a < top) {
            val y = yOf(a)
            canvas.drawLine(padL, y, width.toFloat(), y, gridPaint)
            canvas.drawText(u.alt(a), context.dp(4f), y + textPaint.textSize * 0.35f, textPaint)
            a += step
        }

        // Airspace blocks. A limit above ground follows the terrain; the others are level.
        for (i in slabs.indices) {
            val s = slabs[i]
            val sp = s.airspace
            val col = AirspaceStyle.color(sp, relevance[i])
            val x0 = xOf(s.fromM); val x1 = xOf(s.toM)
            val topInf = sp.upper.isUnlimited
            path.rewind()
            val steps = max(1, ((s.toM - s.fromM) / spanM * SAMPLES).toInt())
            for (k in 0..steps) {
                val d = s.fromM + (s.toM - s.fromM) * k / steps
                val y = yOf(limitMsl(sp.lower, groundAt(d)))
                if (k == 0) path.moveTo(xOf(d), y) else path.lineTo(xOf(d), y)
            }
            for (k in steps downTo 0) {
                val d = s.fromM + (s.toM - s.fromM) * k / steps
                path.lineTo(xOf(d), if (topInf) -10f else yOf(limitMsl(sp.upper, groundAt(d))))
            }
            path.close()
            canvas.save()
            canvas.clipRect(padL, padT * 0.2f, width.toFloat(), height.toFloat())
            slabFill.color = Color.argb(if (relevance[i] == Relevance.ALERT) 46 else 18, Color.red(col), Color.green(col), Color.blue(col))
            canvas.drawPath(path, slabFill)
            slabEdge.color = col
            canvas.drawPath(path, slabEdge)
            val floorY = yOf(limitMsl(sp.lower, groundAt(s.fromM)))
            // Label a block at its floor, and only when that floor is in view: a block that starts
            // above the window has no place to put a label that would not sit on another one.
            if (x1 - x0 > context.dp(40f) && relevance[i] == Relevance.ALERT && floorY >= padT) {
                namePaint.color = col
                val ty = (floorY - context.dp(4f)).coerceIn(padT + namePaint.textSize, height - padB - context.dp(2f))
                canvas.drawText("${AirspaceStyle.short(sp)} ${sp.lower.text}", max(x0, padL) + context.dp(4f), ty, namePaint)
            }
            canvas.restore()
        }

        // Terrain.
        path.rewind()
        var any = false
        path.moveTo(xOf(0.0), height.toFloat())
        for (i in 0 until SAMPLES) {
            val e = terrain[i]
            if (e.isNaN()) continue
            any = true
            path.lineTo(xOf(spanM * i / (SAMPLES - 1)), yOf(e))
        }
        path.lineTo(width.toFloat(), height.toFloat())
        path.close()
        if (any) canvas.drawPath(path, terrainPaint)

        // Glider and where it is heading.
        val gx = xOf(backM); val gy = yOf(altMsl)
        if (speed > 1.5) {
            val t = Hub.settings.lookaheadS
            canvas.drawLine(gx, gy, xOf(backM + speed * t), yOf(altMsl + climb * t), pathPaint)
        }
        canvas.drawCircle(gx, gy, context.dp(5f), gliderPaint)

        val range = if (rangeM >= 1000) "${(rangeM / 1000).toInt()} km ahead" else "${rangeM.toInt()} m ahead"
        val rangeX = width - textPaint.measureText(range) - context.dp(8f)
        canvas.drawText(range, rangeX, context.dp(15f), textPaint)
        headPaint.color = headlineColor
        // Cut the headline rather than run it into the range label.
        val fits = headPaint.breakText(headline, true, rangeX - padL - context.dp(8f), null)
        val shown = if (fits < headline.length) headline.substring(0, max(0, fits - 1)) + "…" else headline
        canvas.drawText(shown, padL, context.dp(15f), headPaint)
    }

    companion object {
        val RANGES = doubleArrayOf(5_000.0, 10_000.0, 2_000.0)
        /** Fraction of the range also shown behind the glider. */
        const val BEHIND = 0.12
        const val SAMPLES = 64
        /** Sky shown above the glider when there is no airspace overhead. */
        const val OPEN_SKY_M = 400.0
        /** How far past the next boundary the view extends: this fraction of the height shown, at least MIN_PAST_M. */
        const val PAST_FRACTION = 0.12
        const val MIN_PAST_M = 80.0
    }
}
