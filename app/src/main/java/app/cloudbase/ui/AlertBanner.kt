package app.cloudbase.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.LinearLayout
import android.widget.TextView
import app.cloudbase.ui.Theme.dp
import kotlin.math.abs

/**
 * The airspace alert: a full-width band between the instruments and the map, so it takes room
 * from the map rather than covering the instruments. A large ✕ dismisses it, as does a swipe to
 * the left (made for gloves: the whole band takes the swipe).
 */
@SuppressLint("ViewConstructor")
class AlertBanner(context: Context, private val onDismiss: () -> Unit) : LinearLayout(context) {
    private val text: TextView = Theme.body(context, "", Color.WHITE, 18f).apply {
        typeface = Theme.CONDENSED_BOLD
        setPadding(context.dp(14), context.dp(10), context.dp(6), context.dp(10))
        gravity = Gravity.CENTER_VERTICAL
    }
    private val close = CloseGlyph(context).apply {
        contentDescription = "Dismiss alert"
        background = Theme.ripple(null, Color.argb(60, 255, 255, 255))
        setOnClickListener { dismiss() }
    }
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var dragging = false

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(text, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(close, LayoutParams(context.dp(64), context.dp(64)))
        visibility = GONE
    }

    /** Red: inside, or at a ceiling. Amber: approaching. Blue: for information, nothing to do. */
    enum class Kind { DANGER, WARN, INFO }

    fun show(message: String, kind: Kind) {
        val fg = if (kind == Kind.WARN) Theme.TEXT else Color.WHITE
        setBackgroundColor(when (kind) { Kind.DANGER -> Theme.DANGER; Kind.WARN -> Theme.WARN; Kind.INFO -> Theme.ACCENT })
        text.setTextColor(fg)
        close.color = fg
        text.text = message
        if (visibility != VISIBLE) { translationX = 0f; alpha = 1f; visibility = VISIBLE }
    }

    fun hide() {
        animate().cancel()
        visibility = GONE
    }

    private fun dismiss() {
        animate().translationX(-width.toFloat()).alpha(0f).setDuration(150).withEndAction {
            visibility = GONE
            translationX = 0f
            alpha = 1f
            onDismiss()
        }.start()
    }

    // A horizontal drag anywhere on the band, taken from the children (the ✕) once it is clearly a swipe.
    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.rawX; dragging = false }
            MotionEvent.ACTION_MOVE -> if (!dragging && downX - e.rawX > slop) dragging = true
        }
        return dragging
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.rawX; return true }
            MotionEvent.ACTION_MOVE -> {
                val dx = (e.rawX - downX).coerceAtMost(0f)
                if (abs(dx) > slop) dragging = true
                translationX = dx
                alpha = 1f - 0.6f * (abs(dx) / width.coerceAtLeast(1)).coerceAtMost(1f)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging && -translationX > width / 4f) dismiss()
                else animate().translationX(0f).alpha(1f).setDuration(120).start()
                dragging = false
            }
        }
        return true
    }

    /** A bold ✕ drawn in code, sized for a gloved thumb. */
    private class CloseGlyph(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = context.dp(3.5f); strokeCap = Paint.Cap.ROUND
        }
        var color: Int = Color.WHITE
            set(v) { field = v; paint.color = v; invalidate() }

        override fun onDraw(canvas: Canvas) {
            val r = context.dp(11f)
            val cx = width / 2f
            val cy = height / 2f
            canvas.drawLine(cx - r, cy - r, cx + r, cy + r, paint)
            canvas.drawLine(cx - r, cy + r, cx + r, cy - r, paint)
        }
    }
}
