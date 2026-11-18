package app.cloudbase.ui

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.tanh

/**
 * Turns scattered lift/sink cells into a smooth picture: a small grid, one pixel per thermal-map
 * cell, where every cell is spread over its neighbours with a Gaussian kernel. The pixel's value
 * is the weighted average climb of everything spread onto it, and its opacity is how much was
 * (so the edge of what has been flown through fades out rather than ending in a hard line)
 * and how fresh.
 * Drawn scaled up with bilinear filtering, that reads as a cloud, not as dots.
 *
 * Plain Kotlin on arrays so it can be tested without Android, and nothing is allocated per frame.
 */
class HeatGrid(val n: Int) {
    private val sum = FloatArray(n * n)
    private val weight = FloatArray(n * n)
    private val cover = FloatArray(n * n)
    /** Non-premultiplied ARGB, row 0 at the top, ready for Bitmap.setPixels. */
    val pixels = IntArray(n * n)

    fun clear() {
        sum.fill(0f)
        weight.fill(0f)
        cover.fill(0f)
    }

    /** Spread one cell at grid position ([ix], [iy]) with value [climb] m/s; [w] 0..1 scales its say (age). */
    fun splat(ix: Int, iy: Int, climb: Float, w: Float) {
        if (ix < -R || iy < -R || ix >= n + R || iy >= n + R || w <= 0f) return
        for (dy in -R..R) {
            val y = iy + dy
            if (y < 0 || y >= n) continue
            val ky = KERNEL[dy + R]
            for (dx in -R..R) {
                val x = ix + dx
                if (x < 0 || x >= n) continue
                val k = ky * KERNEL[dx + R]
                val i = y * n + x
                sum[i] += k * w * climb
                weight[i] += k * w
                cover[i] += k
            }
        }
    }

    /** Fill [pixels]. */
    fun render() {
        for (i in pixels.indices) {
            val w = weight[i]
            if (w < 1e-5f) { pixels[i] = 0; continue }
            val v = sum[i] / w
            // One cell alone gives CENTRE_WEIGHT at its own pixel: that counts as fully covered.
            // Opacity is coverage times the average say of what landed here, so a whole patch of
            // old samples is as faded as a single old sample, however densely it was flown.
            val coverage = min(1f, cover[i] / CENTRE_WEIGHT)
            pixels[i] = color(v, coverage * (w / cover[i]))
        }
    }

    /**
     * Lift is the side a pilot actually reads, and which colours read best is a matter of
     * eyes, screen and sunlight, so there is a choice. Every ramp starts well away from
     * violet, so up and down are never confused.
     */
    enum class Palette(val label: String, internal val ramp: IntArray) {
        /**
         * The upper part of the "turbo" rainbow: turquoise, green, lime, yellow, orange, red,
         * dark red. The most distinct colours of the three, and the strongest lift is the
         * hottest and deepest.
         */
        TURBO("Rainbow", intArrayOf(0x27D7C4, 0x4DF884, 0x95FB51, 0xDEDD32, 0xFFA423, 0xF65F18, 0xBA2208, 0x900C00)),

        /** The upper part of viridis: teal, green, lime, bright yellow. Steadily brighter as lift strengthens. */
        VIRIDIS("Viridis", intArrayOf(0x21918C, 0x28AE80, 0x5EC962, 0xADDC30, 0xFDE725)),

        /** Pale yellow, yellow, orange, red, crimson, deep crimson. No greens, so nothing in common with the map. */
        HEAT("Heat", intArrayOf(0xFFF3A0, 0xFFD600, 0xFF9800, 0xFF3D00, 0xD50032, 0x8E0045)),
    }

    companion object {
        private const val R = 2
        /** Binomial approximation of a Gaussian, sigma about one cell. */
        private val KERNEL = floatArrayOf(1 / 16f, 4 / 16f, 6 / 16f, 4 / 16f, 1 / 16f)
        private const val CENTRE_WEIGHT = (6 / 16f) * (6 / 16f)
        /** Air moving less than this is shown as nothing: it is within the noise of the estimate. */
        const val DEAD_BAND = 0.2f
        const val ALPHA = 225

        /**
         * Sink is always the violet family: violet through indigo to near-black. Listed weakest
         * to strongest, as are the lift ramps.
         */
        private val SINK = intArrayOf(0x9A35E8, 0x6A1FD0, 0x3F14A8, 0x1E0B6B, 0x0B0433)

        /** The lift ramp in use. Set from Settings; main thread only, like all drawing. */
        var palette = Palette.TURBO

        /** Sigmoid scales, m/s: 76 % of a ramp is used by this much. Sink saturates early; lift is spread wide. */
        const val SINK_SCALE = 2.0f
        const val LIFT_SCALE = 3.5f

        /**
         * How far along its ramp a climb or sink value sits, 0..1. A sigmoid (tanh): the colour
         * range is spent on ordinary values where telling them apart matters, and extreme values
         * flatten out towards the end instead of all looking the same shade past a hard limit.
         * With [LIFT_SCALE], half of a lift ramp is used by about 1.9 m/s and three quarters by 3.5.
         */
        fun strength(climb: Float): Float = tanh(abs(climb) / if (climb > 0) LIFT_SCALE else SINK_SCALE)

        /** ARGB for a climb value. [coverage] 0..1 fades the edges of the mapped area and old samples. */
        fun color(climb: Float, coverage: Float): Int {
            if (abs(climb) <= DEAD_BAND || coverage <= 0f) return 0
            val ramp = if (climb > 0) palette.ramp else SINK
            val pos = strength(climb) * (ramp.size - 1)
            val k = min(pos.toInt(), ramp.size - 2)
            val f = pos - k
            val a = ramp[k]
            val b = ramp[k + 1]
            val r = mix(a shr 16 and 0xFF, b shr 16 and 0xFF, f)
            val g = mix(a shr 8 and 0xFF, b shr 8 and 0xFF, f)
            val bl = mix(a and 0xFF, b and 0xFF, f)
            // The colour says how strong; opacity only says how well covered and how fresh.
            val alpha = (ALPHA * min(1f, coverage)).toInt()
            return (alpha shl 24) or (r shl 16) or (g shl 8) or bl
        }

        private fun mix(a: Int, b: Int, f: Float): Int = (a + (b - a) * f + 0.5f).toInt()
    }
}
