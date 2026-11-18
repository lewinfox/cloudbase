package app.cloudbase.core.flight

import app.cloudbase.core.Atmosphere
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.sqrt

class VarioFilterTest {
    private enum class Mode { BARO_ACCEL, BARO_ONLY, GPS_ONLY }

    private class Result {
        var n = 0
        var sumV2 = 0.0
        var sumH = 0.0
        var sumH2 = 0.0
        var maxAbsV = 0.0
        var circlingN = 0
        var circlingSumV = 0.0
        val rmsVario get() = sqrt(sumV2 / n)
        /** RMS altitude error after removing the constant (mean) offset. */
        val rmsAlt get() = sqrt(sumH2 / n - (sumH / n) * (sumH / n))
        val circlingMeanVarioError get() = circlingSumV / circlingN
    }

    /** Drive the filter from the simulator exactly as the app would, and score it against truth while airborne. */
    private fun run(mode: Mode, accelBias: Double = 0.0, seed: Long = 1): Result {
        val sim = FlightSim(seed)
        val filter = VarioFilter()
        val va = VerticalAccel()
        val res = Result()
        val dt = 0.01
        var k = 0
        var lastT = 0.0
        var accel = Double.NaN
        var lastHeading = sim.headingDeg
        var circlingSince = Double.NaN
        while (!sim.finished) {
            sim.step(dt)
            k++
            val t = k * dt
            val a = sim.accel
            var scored = false
            when (mode) {
                Mode.BARO_ACCEL -> {
                    if (k % 2 == 0) { // 50 Hz accelerometer
                        accel = va.update(a[0], a[1], a[2], 0.02) + accelBias
                        filter.predict(t - lastT, accel); lastT = t
                    }
                    if (k % 5 == 0) { // 20 Hz barometer
                        if (t > lastT) { filter.predict(t - lastT, accel); lastT = t }
                        filter.update(Atmosphere.altitude(sim.pressureHpa), 0.4)
                        scored = true
                    }
                }
                Mode.BARO_ONLY -> if (k % 5 == 0) {
                    filter.predict(0.05, Double.NaN)
                    filter.update(Atmosphere.altitude(sim.pressureHpa), 0.4)
                    scored = true
                }
                Mode.GPS_ONLY -> if (k % 100 == 0) {
                    filter.predict(1.0, Double.NaN)
                    filter.update(sim.fix(0).altMsl, 4.0)
                    scored = true
                }
            }
            val turnRate = Math.toRadians(((sim.headingDeg - lastHeading + 540.0) % 360.0) - 180.0) / dt
            lastHeading = sim.headingDeg
            if (!scored || !sim.flying) continue
            val ev = filter.vario - sim.trueVario
            val eh = filter.altitude - sim.trueAlt
            res.n++
            res.sumV2 += ev * ev
            res.sumH += eh
            res.sumH2 += eh * eh
            res.maxAbsV = maxOf(res.maxAbsV, abs(ev))
            if (turnRate > 0.33) {
                if (circlingSince.isNaN()) circlingSince = t
                if (t - circlingSince > 8.0) { res.circlingN++; res.circlingSumV += ev }
            } else {
                circlingSince = Double.NaN
            }
        }
        return res
    }

    @Test fun modeA_baroAndAccel() {
        val r = run(Mode.BARO_ACCEL)
        println("vario A: rmsV=%.5f rmsAlt=%.5f maxV=%.2f circlingMean=%.5f (n=%d, circ n=%d)"
            .format(r.rmsVario, r.rmsAlt, r.maxAbsV, r.circlingMeanVarioError, r.n, r.circlingN))
        assertTrue("vario RMS ${r.rmsVario}", r.rmsVario <= 0.20)
        assertTrue("alt RMS ${r.rmsAlt}", r.rmsAlt <= 0.5)
        assertTrue("circling n=${r.circlingN}", r.circlingN > 1000)
        assertTrue("circling mean error ${r.circlingMeanVarioError}", abs(r.circlingMeanVarioError) <= 0.1)
    }

    @Test fun modeA_withAccelBias() {
        val r = run(Mode.BARO_ACCEL, accelBias = 0.3)
        println("vario A+bias: rmsV=%.5f rmsAlt=%.5f".format(r.rmsVario, r.rmsAlt))
        assertTrue("vario RMS with bias ${r.rmsVario}", r.rmsVario <= 0.20)
    }

    @Test fun modeB_baroOnly() {
        val r = run(Mode.BARO_ONLY)
        println("vario B: rmsV=%.3f rmsAlt=%.3f maxV=%.2f".format(r.rmsVario, r.rmsAlt, r.maxAbsV))
        assertTrue("vario RMS ${r.rmsVario}", r.rmsVario <= 0.35)
    }

    @Test fun modeC_gpsAltitudeOnly() {
        val r = run(Mode.GPS_ONLY)
        println("vario C: rmsV=%.3f rmsAlt=%.3f maxV=%.2f".format(r.rmsVario, r.rmsAlt, r.maxAbsV))
        assertTrue("vario RMS ${r.rmsVario}", r.rmsVario <= 0.8)
        // No divergence: the error stays bounded, both in the worst case and in altitude.
        assertTrue("max vario error ${r.maxAbsV}", r.maxAbsV < 4.0)
        assertTrue("alt RMS ${r.rmsAlt}", r.rmsAlt < 4.0)
    }

    // --- Synthetic scenarios with known truth ---

    /** Straight, level-ish flight: constant climb, accel carrying a constant bias. */
    @Test fun constantAccelBiasDoesNotOffsetVario() {
        for (bias in doubleArrayOf(0.3, -0.5, 1.0)) {
            val rng = Random(5)
            val f = VarioFilter()
            var t = 0.0
            var sumErr = 0.0
            var n = 0
            val vTrue = 1.5
            var lastT = 0.0
            for (k in 1..(60 * 100)) { // 60 s at 100 ticks/s
                t = k * 0.01
                if (k % 2 == 0) { f.predict(t - lastT, bias + 0.15 * rng.nextGaussian()); lastT = t }
                if (k % 5 == 0) {
                    f.update(1000.0 + vTrue * t + 0.4 * rng.nextGaussian(), 0.4)
                    if (t > 20.0) { sumErr += f.vario - vTrue; n++ }
                }
            }
            assertEquals("bias $bias", 0.0, sumErr / n, 0.05)
        }
    }

    /**
     * Rolling into a 1.0 m/s² "(n-1)·g" offset (a steep banked turn) while the true climb is a constant
     * 1.0 m/s. The bias state must take it up within a few seconds, and vario must not be thrown far
     * off in the meantime.
     */
    @Test fun biasStepIsAbsorbedWithinSeconds() {
        val rng = Random(21)
        val f = VarioFilter()
        val tStep = 30.0
        var lastT = 0.0
        var maxBlockMean = 0.0
        var blockSum = 0.0
        var blockN = 0
        var lateSum = 0.0
        var lateN = 0
        for (k in 1..(60 * 100)) {
            val t = k * 0.01
            // Offset ramps in over 1.5 s (roll-in).
            val offset = 1.0 * ((t - tStep) / 1.5).coerceIn(0.0, 1.0)
            if (k % 2 == 0) { f.predict(t - lastT, offset + 0.1 + 0.15 * rng.nextGaussian()); lastT = t }
            if (k % 5 == 0) {
                f.update(1000.0 + 1.0 * t + 0.4 * rng.nextGaussian(), 0.4)
                if (t > tStep) {
                    val e = f.vario - 1.0
                    // 1 s block means, so measurement noise does not dominate the peak.
                    blockSum += e
                    if (++blockN == 20) { maxBlockMean = maxOf(maxBlockMean, abs(blockSum / blockN)); blockSum = 0.0; blockN = 0 }
                    if (t > tStep + 5.0 && t < tStep + 20.0) { lateSum += e; lateN++ }
                }
            }
        }
        println("bias step: peak 1 s-mean vario error %.2f m/s, mean 5..20 s after = %.3f".format(maxBlockMean, lateSum / lateN))
        assertTrue("peak error $maxBlockMean", maxBlockMean < 1.0)
        assertEquals("offset after 5 s", 0.0, lateSum / lateN, 0.05)
    }

    /** Velocity changes from -1.1 to +2.0 m/s over 0.5 s (flying into a thermal edge). Time to 80 % of the step. */
    @Test fun stepResponse() {
        val rng = Random(11)
        val f = VarioFilter()
        val v0 = -1.1
        val v1 = 2.0
        val tStep = 20.0
        val ramp = 0.5
        var alt = 1000.0
        var v = v0
        var lastT = 0.0
        var reached = Double.NaN
        for (k in 1..(30 * 100)) {
            val t = k * 0.01
            val vNew = when {
                t < tStep -> v0
                t < tStep + ramp -> v0 + (v1 - v0) * (t - tStep) / ramp
                else -> v1
            }
            val a = (vNew - v) / 0.01
            alt += 0.5 * (v + vNew) * 0.01
            v = vNew
            if (k % 2 == 0) { f.predict(t - lastT, a + 0.1 + 0.15 * rng.nextGaussian()); lastT = t }
            if (k % 5 == 0) {
                if (t > lastT) { f.predict(t - lastT, a + 0.1); lastT = t }
                f.update(alt + 0.4 * rng.nextGaussian(), 0.4)
                if (reached.isNaN() && t >= tStep && f.vario >= v0 + 0.8 * (v1 - v0)) reached = t - tStep
            }
        }
        println("vario step: 80%% at %.2f s".format(reached))
        assertTrue("80 % reached within 1.5 s, was $reached", reached <= 1.5)
    }

    // --- Edge cases ---

    @Test fun initialisation() {
        val f = VarioFilter()
        assertFalse(f.initialised)
        assertTrue(f.altitude.isNaN())
        f.predict(0.1, 0.0) // ignored before the first measurement
        assertTrue(f.altitude.isNaN())
        f.update(Double.NaN, 1.0) // NaN measurements are ignored
        assertFalse(f.initialised)
        f.update(500.0, 0.5)
        assertTrue(f.initialised)
        assertEquals(500.0, f.altitude, 0.0)
        assertEquals(0.0, f.vario, 0.0)
        f.reset()
        assertFalse(f.initialised)
        assertTrue(f.altitude.isNaN())
    }

    @Test fun badTimeStepsAreHandled() {
        val f = VarioFilter()
        f.update(100.0, 0.5)
        f.predict(0.0, 0.0)
        f.predict(-1.0, 5.0)
        assertEquals(100.0, f.altitude, 0.0) // ignored
        // Build up some velocity, then a long gap: velocity is forgotten, the next fix is trusted.
        for (i in 1..50) { f.predict(0.1, Double.NaN); f.update(100.0 + 2.0 * i * 0.1, 0.5) }
        assertTrue(f.vario > 1.0)
        f.predict(3.0, Double.NaN)
        assertEquals(0.0, f.vario, 0.0)
        f.update(160.0, 0.5)
        assertEquals(160.0, f.altitude, 1.0) // snapped onto the new measurement, not dragged over minutes
    }

    @Test fun noAllocationFreeOfNaNPoison() {
        val f = VarioFilter()
        f.update(100.0, 0.5)
        repeat(100_000) {
            f.predict(0.02, if (it % 3 == 0) Double.NaN else 0.2)
            if (it % 5 == 0) f.update(100.0, 0.4)
        }
        assertFalse(f.altitude.isNaN() || f.vario.isNaN())
        assertEquals(100.0, f.altitude, 0.5)
    }
}
