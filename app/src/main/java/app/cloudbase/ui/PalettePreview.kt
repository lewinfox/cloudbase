package app.cloudbase.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import app.cloudbase.FlightService
import app.cloudbase.core.Atmosphere
import app.cloudbase.core.FlightComputer
import app.cloudbase.core.LocalProjection
import app.cloudbase.core.igc.IgcTrack
import app.cloudbase.core.pressureAt
import app.cloudbase.ui.Theme.dp
import app.cloudbase.ui.Theme.sp

/**
 * What the thermal map looked like a third of the way into the demo flight (a real one: a climb,
 * with sink on the way in), kept as plain numbers so it can be drawn in any colour scale. The
 * settings page shows it once per scale so the choice is made on real air, not on a legend.
 */
object PaletteSample {
    const val SIZE = 48
    /** Seconds into the recorded flight: circling in the climb at Coronet Peak. */
    private const val AT_S = 1060

    class Data(val ix: IntArray, val iy: IntArray, val climb: FloatArray, val weight: FloatArray, val trackX: FloatArray, val trackY: FloatArray)

    @Volatile var data: Data? = null
        private set
    private var loading = false

    /** Replays the flight on a background thread (once), then calls [ready] on the caller's thread via [post]. */
    fun load(context: Context, post: (Runnable) -> Unit, ready: () -> Unit) {
        if (data != null || loading) return
        loading = true
        val app = context.applicationContext
        Thread {
            val d = try { compute(app) } catch (e: Exception) { null }
            post(Runnable { data = d; loading = false; if (d != null) ready() })
        }.start()
    }

    private fun compute(context: Context): Data? {
        val t = context.assets.open(FlightService.DEMO_ASSET).bufferedReader().use { IgcTrack.read(it) } ?: return null
        // Its own flight computer: nothing here touches the live one.
        val c = FlightComputer { _, _ -> Double.NaN }
        var i = 0
        while (i < t.size && (t.timeMs(i) - t.timeMs(0)) / 1000 <= AT_S) {
            if (t.hasPressure) c.onPressure(t.timeMs(i) * 1_000_000L, Atmosphere.pressureAt(t.pressureAlt(i)))
            c.onFix(t.fix(i))
            i++
        }
        val f = c.fix ?: return null
        val proj = LocalProjection(f.lat, f.lon)
        val cell = c.thermals.cellM
        val ix = ArrayList<Int>(); val iy = ArrayList<Int>(); val climb = ArrayList<Float>(); val weight = ArrayList<Float>()
        val half = SIZE / 2 * cell
        val s = proj.lat(-half); val w = proj.lon(-half); val n = proj.lat(half); val e = proj.lon(half)
        // The same two layers, and the same hand-over between them, as the map draws.
        val live = FlightComputer.LIVE_THERMAL_MS
        c.thermals.forEach(s, w, n, e, f.timeMs) { lat, lon, v, age ->
            ix.add(Math.round(proj.x(lon) / cell).toInt() + SIZE / 2); iy.add(SIZE / 2 - Math.round(proj.y(lat) / cell).toInt())
            climb.add(v.toFloat()); weight.add(1f - (1f - ThermalOverlay.HISTORY_STRENGTH) * minOf(1.0, age.toDouble() / live).toFloat())
        }
        c.thermalHistory.forEach(s, w, n, e, f.timeMs) { lat, lon, v, age ->
            if (age < live) return@forEach
            ix.add(Math.round(proj.x(lon) / cell).toInt() + SIZE / 2); iy.add(SIZE / 2 - Math.round(proj.y(lat) / cell).toInt())
            climb.add(v.toFloat()); weight.add(ThermalOverlay.HISTORY_STRENGTH)
        }
        val trail = c.trail
        val tx = FloatArray(trail.size) { k -> (proj.x(trail.lon(k)) / cell).toFloat() + SIZE / 2 }
        val ty = FloatArray(trail.size) { k -> SIZE / 2 - (proj.y(trail.lat(k)) / cell).toFloat() }
        return Data(ix.toIntArray(), iy.toIntArray(), climb.toFloatArray(), weight.toFloatArray(), tx, ty)
    }
}

/** One colour scale shown on the sample flight, with its legend underneath. Tap to choose it. */
class PalettePreview(context: Context, private val palette: HeatGrid.Palette, private val selected: Boolean) : View(context) {
    private val bitmap = Bitmap.createBitmap(PaletteSample.SIZE, PaletteSample.SIZE, Bitmap.Config.ARGB_8888)
    private var rendered = false
    private val filter = Paint(Paint.FILTER_BITMAP_FLAG)
    private val ground = Paint().apply { color = Color.parseColor("#E4E7E0") } // about the tone of the map's hill shading
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.argb(170, 16, 42, 67); strokeWidth = context.dp(1.3f) }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = if (selected) Theme.ACCENT else Theme.LINE; strokeWidth = context.dp(if (selected) 3f else 1f) }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.TEXT; textSize = context.sp(15f); typeface = Theme.CONDENSED_BOLD }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.MUTED; textSize = context.sp(10f); typeface = Theme.CONDENSED; textAlign = Paint.Align.CENTER }
    private val swatch = Paint()
    private val clip = Path()
    private val rect = RectF()
    private val dst = RectF()
    private val track = Path()

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), context.dp(176))

    /** Colours come from the shared [HeatGrid.palette], so switch it just for this drawing. */
    private inline fun withPalette(block: () -> Unit) {
        val keep = HeatGrid.palette
        HeatGrid.palette = palette
        try { block() } finally { HeatGrid.palette = keep }
    }

    private fun render(d: PaletteSample.Data) {
        val grid = HeatGrid(PaletteSample.SIZE)
        grid.clear()
        for (i in d.ix.indices) grid.splat(d.ix[i], d.iy[i], d.climb[i], d.weight[i])
        withPalette { grid.render() }
        bitmap.setPixels(grid.pixels, 0, PaletteSample.SIZE, 0, 0, PaletteSample.SIZE, PaletteSample.SIZE)
        rendered = true
    }

    override fun onDraw(canvas: Canvas) {
        val r = context.dp(12f)
        val inset = border.strokeWidth / 2
        rect.set(inset, inset, width - inset, height - inset)
        clip.rewind(); clip.addRoundRect(rect, r, r, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clip)

        // The sample: a square window of the flight, as wide as the tile, cropped to the picture area.
        val pictureH = height - context.dp(52f)
        canvas.drawRect(0f, 0f, width.toFloat(), pictureH, ground)
        val d = PaletteSample.data
        if (d != null) {
            if (!rendered) render(d)
            val side = width.toFloat()
            val top = (pictureH - side) / 2
            dst.set(0f, top, side, top + side)
            canvas.save()
            canvas.clipRect(0f, 0f, width.toFloat(), pictureH)
            canvas.drawBitmap(bitmap, null, dst, filter)
            val k = side / PaletteSample.SIZE
            track.rewind()
            for (i in d.trackX.indices) {
                val x = (d.trackX[i] + 0.5f) * k; val y = top + (d.trackY[i] + 0.5f) * k
                if (i == 0) track.moveTo(x, y) else track.lineTo(x, y)
            }
            canvas.drawPath(track, trackPaint)
            canvas.restore()
        }

        // Legend: the scale itself, sink on the left, lift on the right.
        val pad = context.dp(12f)
        val barTop = pictureH + context.dp(26f)
        val barBottom = barTop + context.dp(10f)
        canvas.drawText(palette.label, pad, pictureH + context.dp(19f), text)
        val w = width - 2 * pad
        withPalette {
            var x = 0f
            while (x < w) {
                val v = MIN_MS + (MAX_MS - MIN_MS) * x / w
                val c = HeatGrid.color(v, 1f)
                if (c != 0) { swatch.color = c or (0xFF shl 24); canvas.drawRect(pad + x, barTop, pad + x + 2f, barBottom, swatch) }
                x += 2f
            }
        }
        for (v in MIN_MS.toInt()..MAX_MS.toInt() step 2) {
            val x = pad + w * (v - MIN_MS) / (MAX_MS - MIN_MS)
            canvas.drawText(if (v > 0) "+$v" else if (v < 0) "−${-v}" else "0", x, barBottom + context.dp(12f), small)
        }
        canvas.restore()
        canvas.drawRoundRect(rect, r, r, border)
    }

    companion object {
        const val MIN_MS = -4f
        const val MAX_MS = 6f
    }
}
