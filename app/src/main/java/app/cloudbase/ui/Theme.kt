package app.cloudbase.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import app.cloudbase.Settings
import kotlin.math.roundToInt

/**
 * The whole design system: white, near-black text, one blue accent. Light because it is read in
 * full sun; colour is reserved for things that mean something (lift, sink, airspace, alerts).
 */
object Theme {
    val BG = Color.WHITE
    val SURFACE = Color.parseColor("#F4F5F7")
    val LINE = Color.parseColor("#E1E4E8")
    val MUTED = Color.parseColor("#6B7280")
    val TEXT = Color.parseColor("#111418")
    val ACCENT = Color.parseColor("#1565C0")
    val LIFT = Color.parseColor("#D32F2F")
    val SINK = Color.parseColor("#1976D2")
    val WARN = Color.parseColor("#E08A00")
    val DANGER = Color.parseColor("#C62828")
    val OK = Color.parseColor("#2E7D32")
    val TERRAIN = Color.parseColor("#B9AE9C")

    val CONDENSED_BOLD: Typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    val CONDENSED: Typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
    val REGULAR: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)

    fun Context.dp(v: Float): Float = v * resources.displayMetrics.density
    fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
    fun Context.sp(v: Float): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    fun label(ctx: Context, text: String, color: Int = MUTED, size: Float = 11f) = TextView(ctx).apply {
        this.text = text.uppercase()
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        typeface = CONDENSED_BOLD
        letterSpacing = 0.12f
    }

    fun body(ctx: Context, text: String = "", color: Int = TEXT, size: Float = 15f) = TextView(ctx).apply {
        this.text = text
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        typeface = REGULAR
        setLineSpacing(0f, 1.15f)
    }

    fun button(ctx: Context, text: String, primary: Boolean = false, danger: Boolean = false, onClick: () -> Unit) =
        TextView(ctx).apply {
            this.text = text.uppercase()
            gravity = Gravity.CENTER
            typeface = CONDENSED_BOLD
            letterSpacing = 0.1f
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            val edge = if (danger) DANGER else ACCENT
            setTextColor(if (primary) Color.WHITE else edge)
            val shape = GradientDrawable().apply {
                cornerRadius = ctx.dp(8f)
                if (primary) setColor(edge) else { setColor(Color.WHITE); setStroke(ctx.dp(1), edge) }
            }
            background = ripple(shape)
            minHeight = ctx.dp(48)
            maxLines = 1
            setPadding(ctx.dp(16), 0, ctx.dp(16), 0)
            isClickable = true
            setOnClickListener { onClick() }
        }

    fun ripple(content: Drawable?, color: Int = Color.argb(40, 21, 101, 192)): Drawable =
        RippleDrawable(ColorStateList.valueOf(color), content, content ?: ColorDrawable(Color.WHITE))

    fun rule(ctx: Context) = View(ctx).apply {
        setBackgroundColor(LINE)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
    }

    fun column(ctx: Context, padH: Int = 20, padV: Int = 0) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(ctx.dp(padH), ctx.dp(padV), ctx.dp(padH), ctx.dp(padV))
    }

    fun row(ctx: Context) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    /** Lift is red, sink is blue, everywhere in the app; [strength] 0..1 sets how saturated. */
    fun climbColor(climb: Double, alpha: Int): Int {
        val base = if (climb >= 0) LIFT else SINK
        return Color.argb(alpha, Color.red(base), Color.green(base), Color.blue(base))
    }
}

/** Display units. Everything inside the app is SI; conversion happens only here. */
class Units(s: Settings) {
    private val feet = s.altFeet
    private val speed = s.speedUnit

    val altUnit = if (feet) "ft" else "m"
    val speedUnit = when (speed) { "kt" -> "kt"; "mph" -> "mph"; else -> "km/h" }

    fun alt(m: Double): String = if (m.isNaN()) "—" else num(if (feet) m / 0.3048 else m)
    fun speed(ms: Double): String = if (ms.isNaN()) "—" else num(
        when (speed) { "kt" -> ms * 1.943844; "mph" -> ms * 2.236936; else -> ms * 3.6 }
    )
    fun altWithUnit(m: Double) = "${alt(m)} $altUnit"

    companion object {
        fun num(v: Double): String = v.roundToInt().toString().replace('-', '−')
        fun signed(v: Double, decimals: Int = 1): String =
            if (v.isNaN()) "—" else ("%+.${decimals}f".format(v)).replace('-', '−')
    }
}
