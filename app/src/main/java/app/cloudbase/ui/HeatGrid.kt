package app.cloudbase.ui

import kotlin.math.abs
import kotlin.math.min

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

    /** Fill [pixels]. [fullScale] is the climb or sink (m/s) at which the colour is at its strongest. */
    fun render(fullScale: Float) {
        for (i in pixels.indices) {
            val w = weight[i]
            if (w < 1e-5f) { pixels[i] = 0; continue }
            val v = sum[i] / w
            // One cell alone gives CENTRE_WEIGHT at its own pixel: that counts as fully covered.
            // Opacity is coverage times the average say of what landed here, so a whole patch of
            // old samples is as faded as a single old sample, however densely it was flown.
            val coverage = min(1f, cover[i] / CENTRE_WEIGHT)
            pixels[i] = color(v, fullScale, coverage * (w / cover[i]))
        }
    }

    companion object {
        private const val R = 2
        /** Binomial approximation of a Gaussian, sigma about one cell. */
        private val KERNEL = floatArrayOf(1 / 16f, 4 / 16f, 6 / 16f, 4 / 16f, 1 / 16f)
        private const val CENTRE_WEIGHT = (6 / 16f) * (6 / 16f)
        /** Air moving less than this is shown as nothing: it is within the noise of the estimate. */
        const val DEAD_BAND = 0.2f
        const val MAX_ALPHA = 235

        // Graduated ramps, weakest to strongest, so strength reads from the hue as well as the
        // opacity. Every stop is fully saturated, and chosen to be a colour the topographic map
        // underneath does not use: the map is muted greens, greys, browns and a pale grey-blue,
        // which is exactly what a soft blue or a dull red disappears into in sunlight. Lift runs
        // yellow through orange and red to hot pink; sink runs cyan through electric blue to violet.
        private val LIFT = intArrayOf(0xFFD600, 0xFF9100, 0xFF3D00, 0xFF1744, 0xD5006D)
        private val SINK = intArrayOf(0x00E5FF, 0x00B0FF, 0x2979FF, 0x3D5AFE, 0x651FFF)

        /** ARGB for a climb value. [coverage] 0..1 fades the edges of the mapped area. */
        fun color(climb: Float, fullScale: Float, coverage: Float): Int {
            val mag = abs(climb)
            if (mag <= DEAD_BAND || coverage <= 0f) return 0
            val t = min(1f, (mag - DEAD_BAND) / (fullScale - DEAD_BAND))
            val ramp = if (climb > 0) LIFT else SINK
            val pos = t * (ramp.size - 1)
            val k = min(pos.toInt(), ramp.size - 2)
            val f = pos - k
            val a = ramp[k]
            val b = ramp[k + 1]
            val r = mix(a shr 16 and 0xFF, b shr 16 and 0xFF, f)
            val g = mix(a shr 8 and 0xFF, b shr 8 and 0xFF, f)
            val bl = mix(a and 0xFF, b and 0xFF, f)
            // Weak air is lighter, strong air nearly solid; even the weakest is opaque enough to
            // read in sunlight, and nothing is fully opaque so the map still shows through.
            val alpha = (MAX_ALPHA * coverage * (0.55f + 0.45f * t)).toInt()
            return (alpha shl 24) or (r shl 16) or (g shl 8) or bl
        }

        private fun mix(a: Int, b: Int, f: Float): Int = (a + (b - a) * f + 0.5f).toInt()
    }
}
