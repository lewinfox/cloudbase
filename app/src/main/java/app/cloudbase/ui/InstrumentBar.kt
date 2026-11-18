package app.cloudbase.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import app.cloudbase.Hub
import app.cloudbase.ui.Theme.dp
import app.cloudbase.ui.Theme.sp

/** Speed, altitude, climb and wind in one row, drawn in a single onDraw. */
class InstrumentBar(context: Context) : View(context) {
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Theme.MUTED; textSize = context.sp(10.5f); typeface = Theme.CONDENSED_BOLD; letterSpacing = 0.12f
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Theme.TEXT; textSize = context.sp(34f); typeface = Theme.CONDENSED_BOLD
    }
    private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Theme.MUTED; textSize = context.sp(12f); typeface = Theme.CONDENSED
    }
    private val linePaint = Paint().apply { color = Theme.LINE; strokeWidth = 1f }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.ACCENT; style = Paint.Style.FILL }
    private val arrow = Path()

    private var speed = "—"
    private var alt = "—"
    private var altSub = ""
    private var climb = "—"
    private var climbColor = Theme.TEXT
    private var climbSub = ""
    private var wind = "—"
    private var windSub = ""
    private var windFrom = Float.NaN
    private var speedLabel = "GROUND km/h"
    private var altLabel = "ALT m"
    private var windLabel = "WIND km/h"

    init { setBackgroundColor(Theme.BG) }

    fun refresh() {
        val c = Hub.computer
        val u = Units(Hub.settings)
        val f = c.fix
        speedLabel = "GROUND ${u.speedUnit}"
        altLabel = "ALT ${u.altUnit}"
        windLabel = "WIND ${u.speedUnit}"
        speed = if (f == null) "—" else u.speed(f.speed)
        alt = u.alt(c.altMsl)
        altSub = if (c.agl.isNaN()) "" else "AGL ${u.alt(c.agl.coerceAtLeast(0.0))}"
        val v = c.climb
        climb = Units.signed(v)
        climbColor = when { v.isNaN() -> Theme.TEXT; v >= 0.3 -> Theme.LIFT; v <= -2.0 -> Theme.SINK; else -> Theme.TEXT }
        climbSub = if (c.airMass.isNaN()) "" else "air ${Units.signed(c.airMass)}"
        val w = c.windEstimate
        if (w == null) {
            wind = "—"; windSub = "turn to measure"; windFrom = Float.NaN
        } else {
            wind = u.speed(w.wind.speed)
            windSub = "${compass(w.wind.fromDeg)} ${"%03d".format(Math.round(w.wind.fromDeg).toInt() % 360)}°" + if (w.quality < 0.4) " ?" else ""
            windFrom = w.wind.fromDeg.toFloat()
        }
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), context.dp(86))
    }

    override fun onDraw(canvas: Canvas) {
        val w = width / 4f
        cell(canvas, 0f, w, speedLabel, speed, "", Theme.TEXT)
        cell(canvas, w, w, altLabel, alt, altSub, Theme.TEXT)
        cell(canvas, 2 * w, w, "CLIMB m/s", climb, climbSub, climbColor)
        cell(canvas, 3 * w, w, windLabel, wind, windSub, Theme.TEXT)
        for (i in 1..3) canvas.drawLine(i * w, height * 0.18f, i * w, height * 0.82f, linePaint)
        canvas.drawLine(0f, height - 1f, width.toFloat(), height - 1f, linePaint)
        if (!windFrom.isNaN()) drawWindArrow(canvas, 4 * w - context.dp(20f), context.dp(40f))
    }

    private fun cell(canvas: Canvas, x: Float, w: Float, label: String, value: String, sub: String, color: Int) {
        val pad = context.dp(10f)
        canvas.drawText(label, x + pad, context.dp(19f), labelPaint)
        valuePaint.color = color
        // Shrink long values (e.g. 12,500 ft) rather than clip them.
        val full = context.sp(34f)
        valuePaint.textSize = full
        val max = w - 2 * pad
        val tw = valuePaint.measureText(value)
        if (tw > max) valuePaint.textSize = full * max / tw
        canvas.drawText(value, x + pad, context.dp(54f), valuePaint)
        if (sub.isNotEmpty()) canvas.drawText(sub, x + pad, context.dp(74f), subPaint)
    }

    /** Points the way the wind is blowing TO, north-up like the map, so it lines up with drift on screen. */
    private fun drawWindArrow(canvas: Canvas, cx: Float, cy: Float) {
        val r = context.dp(11f)
        arrow.rewind()
        arrow.moveTo(0f, -r); arrow.lineTo(r * 0.6f, r * 0.8f); arrow.lineTo(0f, r * 0.4f); arrow.lineTo(-r * 0.6f, r * 0.8f); arrow.close()
        canvas.save()
        canvas.translate(cx, cy)
        canvas.rotate(windFrom + 180f)
        canvas.drawPath(arrow, arrowPaint)
        canvas.restore()
    }

    companion object {
        private val POINTS = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        fun compass(deg: Double): String = POINTS[(Math.round(deg / 45.0).toInt() % 8 + 8) % 8]
    }
}
