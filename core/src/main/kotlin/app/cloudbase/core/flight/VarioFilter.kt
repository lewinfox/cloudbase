package app.cloudbase.core.flight

/**
 * Altitude / climb-rate Kalman filter.
 *
 * State x = [h, v, b]: altitude (m), vertical speed (m/s, + up) and accelerometer bias (m/s²).
 * The accelerometer is a *control input*, not a measurement. With a measured vertical acceleration
 * `u` (true acceleration + bias b) the kinematics are
 *
 *     h' = h + v·dt + ½(u − b)·dt²
 *     v' = v + (u − b)·dt
 *     b' = b                      (random walk)
 *
 * so F = [[1, dt, −½dt²], [0, 1, −dt], [0, 0, 1]]. The barometer / GPS altitude is the only
 * measurement (H = [1 0 0]); it makes the bias observable, because a wrong bias makes the
 * integrated velocity drift against the altitude. The bias state therefore absorbs sensor offset,
 * local g, and the sustained "(n−1)·g" that the accelerometer reads in a banked turn (see
 * [VerticalAccel]).
 *
 * Without an accelerometer (NaN) the filter is a constant-velocity filter: F loses its third
 * column, the bias is frozen, and the unknown acceleration is modelled as white noise of
 * spectral density [S_CV].
 *
 * The 3×3 covariance is stored as its six upper-triangle scalars so nothing is allocated per call.
 */
class VarioFilter {
    var altitude: Double = Double.NaN
        private set
    var vario: Double = 0.0
        private set
    var initialised: Boolean = false
        private set

    private var bias = 0.0

    // Covariance, upper triangle: pHV = cov(h, v), pHB = cov(h, b), ...
    private var pHH = 0.0
    private var pHV = 0.0
    private var pHB = 0.0
    private var pVV = 0.0
    private var pVB = 0.0
    private var pBB = 0.0

    init {
        reset()
    }

    fun reset() {
        altitude = Double.NaN
        vario = 0.0
        bias = 0.0
        initialised = false
        pHH = 0.0; pHV = 0.0; pHB = 0.0; pVV = 0.0; pVB = 0.0; pBB = 0.0
    }

    /** Advance by [dtS]. [verticalAccel]: m/s² up-positive with gravity removed, or NaN when there is no accelerometer. */
    fun predict(dtS: Double, verticalAccel: Double) {
        if (!initialised) return
        if (!(dtS > 0.0)) return
        if (dtS > MAX_DT_S) {
            reinitialise()
            return
        }
        val dt = dtS
        val hasAccel = !verticalAccel.isNaN()

        // State propagation.
        val net = if (hasAccel) verticalAccel - bias else 0.0
        altitude += vario * dt + 0.5 * net * dt * dt
        vario += net * dt

        // Covariance propagation P ← F P Fᵀ + Q. F = [[1,dt,c],[0,1,d],[0,0,1]].
        val c = if (hasAccel) -0.5 * dt * dt else 0.0
        val d = if (hasAccel) -dt else 0.0
        // Rows of F·P.
        val r00 = pHH + dt * pHV + c * pHB
        val r01 = pHV + dt * pVV + c * pVB
        val r02 = pHB + dt * pVB + c * pBB
        val r11 = pVV + d * pVB
        val r12 = pVB + d * pBB
        // (F·P)·Fᵀ, upper triangle only.
        val n00 = r00 + dt * r01 + c * r02
        val n01 = r01 + d * r02
        val n02 = r02
        val n11 = r11 + d * r12
        val n12 = r12
        // pBB is unchanged by F.

        // Process noise. White acceleration noise of density S integrates to
        // [[S dt³/3, S dt²/2], [S dt²/2, S dt]] on (h, v).
        val s = if (hasAccel) S_ACCEL else S_CV
        pHH = n00 + s * dt * dt * dt / 3.0
        pHV = n01 + s * dt * dt / 2.0
        pHB = n02
        pVV = n11 + s * dt
        pVB = n12
        if (hasAccel) pBB += S_BIAS * dt
        if (pBB > MAX_P_BIAS) pBB = MAX_P_BIAS
    }

    /** Altitude measurement (barometric or GPS) with its 1-sigma noise in metres. */
    fun update(altM: Double, sigmaM: Double) {
        if (altM.isNaN() || altM.isInfinite()) return
        val r = maxOf(sigmaM, MIN_SIGMA_M).let { it * it }
        if (!initialised) {
            altitude = altM
            vario = 0.0
            bias = 0.0
            pHH = r; pHV = 0.0; pHB = 0.0
            pVV = P0_VARIO; pVB = 0.0
            pBB = P0_BIAS
            initialised = true
            return
        }
        val innovation = altM - altitude
        val sInv = 1.0 / (pHH + r)
        val kH = pHH * sInv
        val kV = pHV * sInv
        val kB = pHB * sInv
        altitude += kH * innovation
        vario += kV * innovation
        bias += kB * innovation
        // P ← P − K·(H P), written so each diagonal stays non-negative by construction.
        val shrink = r * sInv // 1 − kH
        val newHH = pHH * shrink
        val newHV = pHV * shrink
        val newHB = pHB * shrink
        val newVV = pVV - kV * pHV
        val newVB = pVB - kV * pHB
        val newBB = pBB - kB * pHB
        pHH = newHH; pHV = newHV; pHB = newHB
        pVV = maxOf(newVV, MIN_P); pVB = newVB; pBB = maxOf(newBB, MIN_P)
    }

    /** Long gap: keep altitude and bias (a sensor property) but forget the velocity and trust the next measurement. */
    private fun reinitialise() {
        vario = 0.0
        pHH = 1e4; pHV = 0.0; pHB = 0.0
        pVV = P0_VARIO; pVB = 0.0
        pBB = maxOf(pBB, P0_BIAS)
    }

    private companion object {
        const val MAX_DT_S = 2.0
        const val MIN_SIGMA_M = 0.01
        const val MIN_P = 1e-9
        const val MAX_P_BIAS = 4.0
        const val P0_VARIO = 4.0   // (2 m/s)²
        const val P0_BIAS = 1.0    // (1 m/s²)²

        /** Spectral density of accelerometer error, (m/s²)²·s. */
        const val S_ACCEL = 0.02
        /** Bias random walk, (m/s²)²/s. */
        const val S_BIAS = 0.1
        /** Unknown vertical acceleration when there is no accelerometer, (m/s²)²·s. */
        const val S_CV = 0.25
    }
}
