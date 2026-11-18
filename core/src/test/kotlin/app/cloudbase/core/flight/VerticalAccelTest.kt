package app.cloudbase.core.flight

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class VerticalAccelTest {
    private val g = 9.80665

    /** Unit vector "up" in the device frame for a device pitched/rolled by the given angles. */
    private fun up(pitchDeg: Double, rollDeg: Double): DoubleArray {
        val p = Math.toRadians(pitchDeg)
        val r = Math.toRadians(rollDeg)
        return doubleArrayOf(sin(p) * cos(r), sin(r), cos(p) * cos(r)).let { v ->
            val n = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
            doubleArrayOf(v[0] / n, v[1] / n, v[2] / n)
        }
    }

    private fun feed(va: VerticalAccel, u: DoubleArray, magnitude: Double, seconds: Double, dt: Double = 0.02): Double {
        var out = Double.NaN
        var t = 0.0
        while (t < seconds) {
            out = va.update((u[0] * magnitude).toFloat(), (u[1] * magnitude).toFloat(), (u[2] * magnitude).toFloat(), dt)
            t += dt
        }
        return out
    }

    @Test fun gravityOnlyReadsZeroInAnyOrientation() {
        for ((pitch, roll) in listOf(0.0 to 0.0, 35.0 to 0.0, -50.0 to 20.0, 80.0 to -60.0)) {
            val va = VerticalAccel()
            assertEquals("pitch $pitch roll $roll", 0.0, feed(va, up(pitch, roll), g, 5.0), 1e-4)
        }
    }

    @Test fun suddenVerticalAccelerationShowsImmediately() {
        val va = VerticalAccel()
        val u = up(30.0, 10.0)
        feed(va, u, g, 5.0)
        // +1.2 m/s² along the same axis: the direction (low-passed) has not moved, so the output is the full 1.2.
        assertEquals(1.2, va.update((u[0] * (g + 1.2)).toFloat(), (u[1] * (g + 1.2)).toFloat(), (u[2] * (g + 1.2)).toFloat(), 0.02), 1e-4)
    }

    @Test fun magnitudeOffsetIsNotRemoved() {
        // Documented behaviour: only the DIRECTION is estimated; a steady 1.1 g (as in a bank) reads +0.98 m/s²
        // forever, and it is the Kalman bias state's job to absorb it.
        val va = VerticalAccel()
        assertEquals(0.1 * g, feed(va, up(20.0, 5.0), 1.1 * g, 30.0), 1e-4)
    }

    @Test fun bankedTurnFindsApparentVerticalNotTrueVertical() {
        // Coordinated turn at 25 degrees of bank: specific force is n*g along the glider's own up axis, which
        // is what the filter takes for vertical. Output = (n - 1) g, whatever the bank angle.
        val bank = Math.toRadians(25.0)
        val n = 1.0 / cos(bank)
        val u = up(0.0, 25.0) // tilted by the bank in device axes (device aligned with the glider)
        val va = VerticalAccel()
        feed(va, u, n * g, 20.0)
        assertEquals((n - 1.0) * g, feed(va, u, n * g, 1.0), 1e-3)
    }

    @Test fun followsSlowReorientationWithTimeConstant() {
        val va = VerticalAccel(tauS = 1.5)
        feed(va, up(0.0, 0.0), g, 5.0)
        // Tilt the device by 30 degrees: the sample length is unchanged but the low-passed direction lags,
        // so there is a transient (< g(1 - cos 30)) that dies away with time constant 1.5 s.
        val u2 = up(30.0, 0.0)
        val early = feed(va, u2, g, 0.1)
        val late = feed(va, u2, g, 10.0)
        println("reorientation transient %.2f -> %.4f m/s²".format(early, late))
        assertTrue("early transient ${early}", Math.abs(early) > 0.5)
        assertEquals(0.0, late, 0.01)
    }

    @Test fun degenerateInputGivesNaNAndResetForgets() {
        val va = VerticalAccel()
        assertTrue(va.update(0f, 0f, 0f, 0.02).isNaN())
        feed(va, up(0.0, 0.0), g, 2.0)
        va.reset()
        // After reset the first sample defines vertical again.
        val u = up(70.0, -30.0)
        assertEquals(0.0, va.update((u[0] * g).toFloat(), (u[1] * g).toFloat(), (u[2] * g).toFloat(), 0.02), 1e-4)
    }
}
