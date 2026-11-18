package app.cloudbase.core.flight

import app.cloudbase.core.Atmosphere
import app.cloudbase.core.Geo
import app.cloudbase.core.Wind
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

enum class WindMethod { CIRCLE, TRIM }

/** [airspeed] is true airspeed, m/s. [quality] is 0..1 and already decayed for age at the time of the query. */
class WindEstimate(val wind: Wind, val airspeed: Double, val quality: Double, val method: WindMethod, val timeMs: Long)

/**
 * Wind from GPS ground velocity alone.
 *
 * ground velocity g = air velocity a + wind w. At roughly constant airspeed |a| = V, the points g
 * (east, north) therefore lie on a circle of radius V whose centre is w, the direction the wind
 * blows TOWARD (we convert to the conventional FROM direction at the end).
 *
 *  - CIRCLE: track covers >= 270 degrees: fit the circle freely (Kasa algebraic least squares).
 *    Gives wind and airspeed, needs no airspeed assumption.
 *  - TRIM: track covers 40..270 degrees: fix V to the indicated trim speed converted to true
 *    airspeed and fit only the centre (Gauss-Newton on sum (|g_i - c| - V)^2). The two mirror-image
 *    solutions (centre on either side of the arc) are both tried.
 *
 * Everything runs on a fixed ring buffer, with scratch arrays allocated once.
 */
class WindEstimator(trimSpeedMs: Double = 10.5) {
    /** Indicated trim airspeed, m/s. */
    var trimSpeed: Double = trimSpeedMs

    // --- Ring buffer of accepted fixes (ground velocity components, track, altitude). ---
    private val rTime = LongArray(CAP)
    private val rVx = DoubleArray(CAP)
    private val rVy = DoubleArray(CAP)
    private val rTrk = DoubleArray(CAP)
    private val rAlt = DoubleArray(CAP)
    private var head = 0 // next write slot
    private var count = 0

    // --- Scratch: the current analysis window in chronological order. ---
    private val wx = DoubleArray(CAP)
    private val wy = DoubleArray(CAP)
    private var wn = 0

    // --- Smoothed estimate. ---
    private var hasEstimate = false
    private var sCx = 0.0 // wind vector (toward), east
    private var sCy = 0.0 // north
    private var sAirspeed = 0.0
    private var sQuality = 0.0
    private var sCircleShare = 0.0 // smoothed fraction of the estimate that came from CIRCLE fits
    private var sTimeMs = 0L

    // --- Results of the last fit (avoids returning objects). ---
    private var fitCx = 0.0
    private var fitCy = 0.0
    private var fitR = 0.0
    private var fitSse = 0.0

    /** Feed every in-flight GPS fix (about 1 Hz). Samples under 1 m/s or with NaN track are ignored. */
    fun add(timeMs: Long, groundSpeed: Double, trackDeg: Double, altMsl: Double) {
        if (!(groundSpeed >= MIN_SPEED) || trackDeg.isNaN() || trackDeg.isInfinite()) return
        if (count > 0) {
            val last = rTime[(head - 1) and MASK]
            // Out-of-order or too-dense fixes carry no new geometry; decimating also guarantees
            // that CAP samples span more than WINDOW_MS even for a 10 Hz receiver.
            if (timeMs - last < MIN_SPACING_MS) return
        }
        val t = Math.toRadians(trackDeg)
        rTime[head] = timeMs
        rVx[head] = groundSpeed * sin(t)
        rVy[head] = groundSpeed * cos(t)
        rTrk[head] = trackDeg
        rAlt[head] = altMsl
        head = (head + 1) and MASK
        if (count < CAP) count++
        analyse(timeMs)
    }

    /** Latest smoothed estimate, or null if nothing trustworthy yet. Quality halves every 5 minutes. */
    fun estimate(nowMs: Long): WindEstimate? {
        if (!hasEstimate) return null
        val q = sQuality * decay(nowMs - sTimeMs)
        if (q < MIN_QUALITY) return null
        val toDeg = Math.toDegrees(atan2(sCx, sCy))
        val wind = Wind(Geo.norm360(toDeg + 180.0), hypot(sCx, sCy))
        return WindEstimate(wind, sAirspeed, q, if (sCircleShare >= 0.5) WindMethod.CIRCLE else WindMethod.TRIM, sTimeMs)
    }

    fun reset() {
        head = 0
        count = 0
        wn = 0
        hasEstimate = false
        sQuality = 0.0
    }

    private fun decay(ageMs: Long): Double =
        if (ageMs <= 0L) 1.0 else 0.5.pow(ageMs / QUALITY_HALF_LIFE_MS)

    /** Physical slot of the i-th sample in chronological order (0 = oldest kept). */
    private fun slot(i: Int) = (head - count + i + CAP) and MASK

    private fun analyse(nowMs: Long) {
        // Walk backwards from the newest sample to find the window start: at most WINDOW_MS long
        // and not spanning a GPS dropout (the velocity circle assumes continuous flight).
        var first = count - 1
        while (first > 0) {
            val t = rTime[slot(first - 1)]
            if (nowMs - t > WINDOW_MS || rTime[slot(first)] - t > MAX_GAP_MS) break
            first--
        }
        wn = count - first
        if (wn < MIN_SAMPLES) return

        // Copy the window and unwrap the track so that "coverage" is the total heading spread.
        var unwrapped = rTrk[slot(first)]
        var minU = unwrapped
        var maxU = unwrapped
        var iMin = 0
        var iMax = 0
        var altSum = 0.0
        var altN = 0
        for (i in 0 until wn) {
            val s = slot(first + i)
            wx[i] = rVx[s]
            wy[i] = rVy[s]
            if (i > 0) {
                unwrapped += Geo.angleDiff(rTrk[s], rTrk[slot(first + i - 1)])
                if (unwrapped < minU) { minU = unwrapped; iMin = i }
                if (unwrapped > maxU) { maxU = unwrapped; iMax = i }
            }
            val a = rAlt[s]
            if (!a.isNaN()) { altSum += a; altN++ }
        }
        val coverage = maxU - minU
        if (coverage < MIN_COVERAGE_DEG) return // too straight: only the ageing previous estimate

        if (coverage >= CIRCLE_COVERAGE_DEG && fitCircle()) {
            val rms = sqrt(fitSse / wn)
            val q = minOf(1.0, coverage / 360.0) * (1.0 - rms / MAX_CIRCLE_RMS)
            merge(fitCx, fitCy, fitR, q.coerceIn(MIN_QUALITY, 1.0), WindMethod.CIRCLE, nowMs)
            return
        }
        val alt = if (altN > 0) altSum / altN else 0.0
        val tas = trimSpeed / sqrt(Atmosphere.densityRatio(alt))
        if (fitTrim(tas, iMin, iMax)) {
            val rms = sqrt(fitSse / wn)
            val q = 0.5 * minOf(1.0, coverage / CIRCLE_COVERAGE_DEG) * (1.0 - rms / MAX_TRIM_RMS)
            merge(fitCx, fitCy, tas, q.coerceIn(MIN_QUALITY, 1.0), WindMethod.TRIM, nowMs)
        }
    }

    /**
     * Kasa fit: minimise sum (x² + y² + D x + E y + F)². Centre = (-D/2, -E/2), r² = cx² + cy² - F.
     * Data are centred on their mean first, which leaves a 2x2 linear system. Returns false if the
     * result is implausible (radius outside 5..25 m/s or RMS residual too large).
     */
    private fun fitCircle(): Boolean {
        val n = wn
        var mx = 0.0
        var my = 0.0
        for (i in 0 until n) { mx += wx[i]; my += wy[i] }
        mx /= n; my /= n
        var suu = 0.0; var svv = 0.0; var suv = 0.0
        var suuu = 0.0; var svvv = 0.0; var suvv = 0.0; var svuu = 0.0
        for (i in 0 until n) {
            val u = wx[i] - mx
            val v = wy[i] - my
            val uu = u * u
            val vv = v * v
            suu += uu; svv += vv; suv += u * v
            suuu += uu * u; svvv += vv * v; suvv += u * vv; svuu += v * uu
        }
        val det = suu * svv - suv * suv
        if (det < 1e-9 * (suu + svv) * (suu + svv)) return false // points (nearly) collinear
        val b1 = 0.5 * (suuu + suvv)
        val b2 = 0.5 * (svvv + svuu)
        val uc = (b1 * svv - b2 * suv) / det
        val vc = (b2 * suu - b1 * suv) / det
        val r2 = uc * uc + vc * vc + (suu + svv) / n
        if (!(r2 > 0.0)) return false
        val r = sqrt(r2)
        fitCx = mx + uc
        fitCy = my + vc
        fitR = r
        fitSse = sse(fitCx, fitCy, r)
        return r in MIN_RADIUS..MAX_RADIUS && sqrt(fitSse / n) <= MAX_CIRCLE_RMS
    }

    private fun sse(cx: Double, cy: Double, r: Double): Double {
        var s = 0.0
        for (i in 0 until wn) {
            val ex = wx[i] - cx
            val ey = wy[i] - cy
            val e = sqrt(ex * ex + ey * ey) - r // (Math.hypot is ~20x slower and we need no overflow safety)
            s += e * e
        }
        return s
    }

    /**
     * Fixed-radius fit. Starting points: the two circles of radius [r] through the extreme-heading
     * samples [iA] and [iB] (their centres lie on the chord's perpendicular bisector, one on each
     * side). Both are refined by Gauss-Newton; the lower residual wins unless they are nearly equal,
     * in which case the one closer to the previous estimate does (so the answer does not flip sides).
     */
    private fun fitTrim(r: Double, iA: Int, iB: Int): Boolean {
        val ax = wx[iA]; val ay = wy[iA]
        val bx = wx[iB]; val by = wy[iB]
        val mx = 0.5 * (ax + bx)
        val my = 0.5 * (ay + by)
        val dx = bx - ax
        val dy = by - ay
        val chord = hypot(dx, dy)
        val nx: Double
        val ny: Double
        val h: Double
        if (chord < 1e-6) {
            nx = 1.0; ny = 0.0; h = r
        } else {
            nx = -dy / chord; ny = dx / chord
            h = sqrt(maxOf(r * r - 0.25 * chord * chord, 0.0))
        }
        gaussNewton(mx + h * nx, my + h * ny, r)
        val c1x = fitCx; val c1y = fitCy; val s1 = fitSse
        gaussNewton(mx - h * nx, my - h * ny, r)
        val c2x = fitCx; val c2y = fitCy; val s2 = fitSse

        val nearlyEqual = kotlin.math.abs(s1 - s2) < 0.1 * maxOf(s1, s2) + wn * NEAR_EQUAL_FLOOR
        val pick1 = if (nearlyEqual && hasEstimate && sQuality * decay(rTime[(head - 1) and MASK] - sTimeMs) > MIN_QUALITY) {
            hypot(c1x - sCx, c1y - sCy) <= hypot(c2x - sCx, c2y - sCy)
        } else {
            s1 <= s2
        }
        if (pick1) { fitCx = c1x; fitCy = c1y; fitSse = s1 } else { fitCx = c2x; fitCy = c2y; fitSse = s2 }
        fitR = r
        return sqrt(fitSse / wn) <= MAX_TRIM_RMS && hypot(fitCx, fitCy) <= MAX_WIND
    }

    /** Minimise sum (|g_i - c| - r)² from (cx0, cy0); result in fitCx/fitCy/fitSse. Damped, step-halving. */
    private fun gaussNewton(cx0: Double, cy0: Double, r: Double) {
        var cx = cx0
        var cy = cy0
        var cost = sse(cx, cy, r)
        for (iter in 0 until MAX_GN_ITER) {
            var a = 0.0; var b = 0.0; var c = 0.0 // JᵀJ = [[a b][b c]]
            var gx = 0.0; var gy = 0.0            // Jᵀf
            for (i in 0 until wn) {
                val ex = wx[i] - cx
                val ey = wy[i] - cy
                val d = sqrt(ex * ex + ey * ey)
                if (d < 1e-9) continue
                val jx = -ex / d // d|g-c| / dc
                val jy = -ey / d
                val f = d - r
                a += jx * jx; b += jx * jy; c += jy * jy
                gx += jx * f; gy += jy * f
            }
            // Levenberg damping keeps near-singular arcs (small coverage) from taking wild steps.
            val lambda = 1e-3 * (a + c) + 1e-9
            a += lambda; c += lambda
            val det = a * c - b * b
            if (det <= 0.0) break
            var stepX = -(c * gx - b * gy) / det
            var stepY = -(a * gy - b * gx) / det
            val len = hypot(stepX, stepY)
            if (len > MAX_GN_STEP) { stepX *= MAX_GN_STEP / len; stepY *= MAX_GN_STEP / len }
            var scale = 1.0
            var improved = false
            for (half in 0 until 5) {
                val nc = sse(cx + scale * stepX, cy + scale * stepY, r)
                if (nc < cost) {
                    cx += scale * stepX; cy += scale * stepY
                    cost = nc
                    improved = true
                    break
                }
                scale *= 0.5
            }
            if (!improved || len * scale < 1e-4) break
        }
        fitCx = cx
        fitCy = cy
        fitSse = cost
    }

    /**
     * Blend a new fit into the smoothed estimate. Weights: previous quality (decayed for age, counted
     * as PRIOR_WEIGHT fixes' worth) against the new quality, so a poor TRIM fit barely moves a good
     * CIRCLE estimate, while a first or very stale estimate is simply replaced.
     */
    private fun merge(cx: Double, cy: Double, airspeed: Double, q: Double, method: WindMethod, timeMs: Long) {
        val circle = if (method == WindMethod.CIRCLE) 1.0 else 0.0
        val prior = if (hasEstimate) sQuality * decay(timeMs - sTimeMs) else 0.0
        if (prior < MIN_QUALITY) {
            sCx = cx; sCy = cy; sAirspeed = airspeed; sQuality = q; sCircleShare = circle
        } else {
            val k = q / (q + PRIOR_WEIGHT * prior)
            sCx += k * (cx - sCx)
            sCy += k * (cy - sCy)
            sAirspeed += k * (airspeed - sAirspeed)
            sQuality = k * q + (1.0 - k) * prior
            sCircleShare += k * (circle - sCircleShare)
        }
        sTimeMs = timeMs
        hasEstimate = true
    }

    private companion object {
        const val CAP = 128 // power of two; 128 samples >= 0.8 s spacing > 90 s window
        const val MASK = CAP - 1
        const val MIN_SPACING_MS = 800L
        const val WINDOW_MS = 90_000L
        const val MAX_GAP_MS = 10_000L
        const val MIN_SAMPLES = 5
        const val MIN_SPEED = 1.0
        const val MIN_COVERAGE_DEG = 40.0
        const val CIRCLE_COVERAGE_DEG = 270.0
        const val MIN_RADIUS = 5.0
        const val MAX_RADIUS = 25.0
        const val MAX_CIRCLE_RMS = 1.5
        const val MAX_TRIM_RMS = 2.0
        const val MAX_WIND = 25.0
        const val NEAR_EQUAL_FLOOR = 0.1 * 0.1 // (m/s)² per sample
        const val MAX_GN_ITER = 25
        const val MAX_GN_STEP = 8.0
        const val QUALITY_HALF_LIFE_MS = 300_000.0
        const val MIN_QUALITY = 0.02
        const val PRIOR_WEIGHT = 3.0
    }
}
