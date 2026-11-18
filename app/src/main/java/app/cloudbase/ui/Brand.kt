package app.cloudbase.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import app.cloudbase.Hub
import app.cloudbase.R
import app.cloudbase.ui.Theme.dp

/**
 * The parts that make the app look like itself rather than like stock Android: the logo's sky
 * as a header, its cloud as a mark, and controls drawn to match. All of it sits around the
 * flying display, never on it.
 */
object Brand {
    val SKY_TOP = Color.parseColor("#58B4F5")
    val SKY_BOTTOM = Color.parseColor("#1E7FE0")

    /** The logo's sky gradient. */
    fun sky(radius: Float = 0f, topOnly: Boolean = false) = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(SKY_TOP, SKY_BOTTOM)).apply {
        if (topOnly) cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f) else cornerRadius = radius
    }

    fun cloud(ctx: Context) = ImageView(ctx).apply { setImageResource(R.drawable.ic_cloud) }

    fun wordmark(ctx: Context, size: Float = 20f) = TextView(ctx).apply {
        text = "CLOUDBASE"
        setTextColor(Color.WHITE)
        textSize = size
        typeface = Theme.CONDENSED_BOLD
        letterSpacing = 0.18f
    }
}

/** A map button with its icon drawn as strokes, so it is crisp at any size and matches the rest. */
class GlyphButton(context: Context, private val glyph: Glyph, onClick: () -> Unit) : View(context) {
    enum class Glyph { MENU, PLUS, MINUS, RECENTRE }

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Theme.TEXT; strokeWidth = context.dp(2.2f); strokeCap = Paint.Cap.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.ACCENT }

    init {
        background = Theme.ripple(GradientDrawable().apply { cornerRadius = context.dp(12f); setColor(Color.WHITE) })
        elevation = context.dp(3f)
        contentDescription = when (glyph) { Glyph.MENU -> "Menu"; Glyph.PLUS -> "Zoom in"; Glyph.MINUS -> "Zoom out"; Glyph.RECENTRE -> "Follow my position" }
        setOnClickListener { onClick() }
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = width * 0.2f
        when (glyph) {
            Glyph.MENU -> for (i in -1..1) canvas.drawLine(cx - r, cy + i * r * 0.75f, cx + r, cy + i * r * 0.75f, stroke)
            Glyph.PLUS -> { canvas.drawLine(cx - r, cy, cx + r, cy, stroke); canvas.drawLine(cx, cy - r, cx, cy + r, stroke) }
            Glyph.MINUS -> canvas.drawLine(cx - r, cy, cx + r, cy, stroke)
            Glyph.RECENTRE -> {
                stroke.color = Theme.ACCENT
                canvas.drawCircle(cx, cy, r, stroke)
                canvas.drawCircle(cx, cy, r * 0.35f, fill)
                for (a in 0..3) {
                    val dx = if (a == 0) 1f else if (a == 1) -1f else 0f
                    val dy = if (a == 2) 1f else if (a == 3) -1f else 0f
                    canvas.drawLine(cx + dx * r, cy + dy * r, cx + dx * r * 1.45f, cy + dy * r * 1.45f, stroke)
                }
                stroke.color = Theme.TEXT
            }
        }
    }
}

/** An on/off switch drawn in the brand colours. Display only: the row it sits in takes the tap. */
class SwitchView(context: Context, private val on: Boolean) : View(context) {
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (on) Theme.ACCENT else Color.parseColor("#C9D1DA") }
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val rect = RectF()

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(context.dp(46), context.dp(26))

    override fun onDraw(canvas: Canvas) {
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(rect, height / 2f, height / 2f, track)
        val r = height / 2f - context.dp(3f)
        canvas.drawCircle(if (on) width - height / 2f else height / 2f, height / 2f, r, knob)
    }
}

/**
 * The main menu: a sheet that slides over the bottom of the map under a sky header, in place
 * of a stock dialog list.
 */
class MenuSheet(context: Context, items: List<Pair<String, () -> Unit>>, private val dismiss: () -> Unit) : FrameLayout(context) {
    init {
        setBackgroundColor(Color.argb(90, 16, 42, 67)) // scrim
        setOnClickListener { dismiss() }
        val radius = context.dp(20f)
        val sheet = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f); setColor(Color.WHITE) }
            elevation = context.dp(12f)
            isClickable = true
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                background = Brand.sky(radius, topOnly = true)
                setPadding(context.dp(20), 0, context.dp(20), 0)
                addView(Brand.cloud(context), LinearLayout.LayoutParams(context.dp(42), context.dp(28)))
                addView(Brand.wordmark(context).apply { setPadding(context.dp(12), 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(Theme.label(context, "v${Hub.version()}", Color.argb(210, 255, 255, 255), 11f))
            }, LinearLayout.LayoutParams(-1, context.dp(64)))
            for ((i, item) in items.withIndex()) {
                val last = i == items.size - 1
                addView(Theme.body(context, item.first, if (last) Theme.DANGER else Theme.TEXT, 17f).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(context.dp(24), 0, context.dp(24), 0)
                    background = Theme.ripple(null)
                    setOnClickListener { dismiss(); item.second() }
                }, LinearLayout.LayoutParams(-1, context.dp(56)))
                if (!last) addView(Theme.rule(context))
            }
            addView(View(context), LinearLayout.LayoutParams(1, context.dp(10)))
        }
        addView(sheet, LayoutParams(-1, -2, Gravity.BOTTOM))
    }
}
