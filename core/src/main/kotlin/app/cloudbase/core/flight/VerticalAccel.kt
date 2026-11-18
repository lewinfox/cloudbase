package app.cloudbase.core.flight

import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Vertical acceleration from a raw 3-axis accelerometer, without a gyroscope.
 *
 * The accelerometer measures specific force: at rest it reads +g along "up". We low-pass the whole
 * vector (time constant [tauS]); its direction is our estimate of vertical, and the output is the
 * *instantaneous* sample projected on that direction, minus standard g. Only the direction of the
 * low-passed vector is used, never its length; any constant error in magnitude (sensor gain/offset,
 * local g, load factor) is left for the Kalman bias state in [VarioFilter] to absorb.
 *
 * Known limitation (physical, not a bug): the "vertical" found this way is the *apparent*
 * vertical, the direction of the sustained specific force. In a coordinated turn that is the
 * glider's own up axis, tilted by the bank angle φ, not the true vertical. The output then reads
 * (n − 1)·g with n = 1/cos φ (about +0.5..1 m/s² in a thermalling turn) instead of 0, and true
 * vertical acceleration is scaled by cos φ. The constant part is absorbed by the bias state after
 * a few seconds; the roll-in and roll-out transients are not, and show up as short vario errors
 * that the barometer then corrects. A gyroscope would be needed to do better.
 */
class VerticalAccel(private val tauS: Double = 1.5) {
    private var gx = 0.0
    private var gy = 0.0
    private var gz = 0.0
    private var primed = false

    /**
     * Raw accelerometer sample (m/s², device frame, includes gravity). Returns acceleration along the
     * estimated vertical, up-positive, minus standard g (9.80665). NaN until a usable direction exists
     * (e.g. free fall, where the sample has no length).
     */
    fun update(ax: Float, ay: Float, az: Float, dtS: Double): Double {
        val x = ax.toDouble()
        val y = ay.toDouble()
        val z = az.toDouble()
        if (!primed) {
            gx = x; gy = y; gz = z
            primed = true
        } else if (dtS > 0.0) {
            // Exact discretisation of a first-order low-pass, valid for any dt.
            val a = 1.0 - exp(-dtS / tauS)
            gx += a * (x - gx)
            gy += a * (y - gy)
            gz += a * (z - gz)
        }
        val norm = sqrt(gx * gx + gy * gy + gz * gz)
        if (norm < MIN_NORM) return Double.NaN
        return (x * gx + y * gy + z * gz) / norm - STANDARD_G
    }

    fun reset() {
        primed = false
        gx = 0.0; gy = 0.0; gz = 0.0
    }

    companion object {
        const val STANDARD_G = 9.80665
        private const val MIN_NORM = 1.0 // m/s²: below this the direction is meaningless
    }
}
