package app.cloudbase.core.flight

import app.cloudbase.core.Atmosphere
import app.cloudbase.core.Geo
import app.cloudbase.core.Wind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

class WindEstimatorTest {
    private fun windVector(w: Wind): DoubleArray { // the direction it blows TOWARD: east, north
        val to = Math.toRadians(w.fromDeg + 180.0)
        return doubleArrayOf(w.speed * sin(to), w.speed * cos(to))
    }

    private fun vectorError(a: Wind, b: Wind): Double {
        val va = windVector(a)
        val vb = windVector(b)
        return hypot(va[0] - vb[0], va[1] - vb[1])
    }

    /** Fly the sim, feed 1 Hz fixes, and return the estimate when the first thermal climb is complete. */
    private fun afterFirstClimb(wind: Wind): Pair<FlightSim, WindEstimate?> {
        val sim = FlightSim(seed = 3, wind = wind)
        val est = WindEstimator(sim.trimSpeed)
        var k = 0
        while (!sim.finished) {
            sim.step(0.02)
            if (++k % 50 == 0 && sim.flying) {
                val f = sim.fix(0)
                est.add(f.timeMs, f.speed, f.track, f.altMsl)
                if (sim.trueAlt >= sim.groundElevM + 995.0) return sim to est.estimate(f.timeMs)
            }
        }
        error("never completed the climb")
    }

    private fun checkCircle(wind: Wind, dirTolDeg: Double) {
        val (sim, e) = afterFirstClimb(wind)
        assertNotNull(e)
        e!!
        val dir = Math.abs(Geo.angleDiff(e.wind.fromDeg, wind.fromDeg))
        println("wind %s: est from=%.1f speed=%.2f TAS=%.2f (true %.2f) q=%.2f %s, vector error %.2f"
            .format(wind, e.wind.fromDeg, e.wind.speed, e.airspeed, sim.trueTas, e.quality, e.method, vectorError(e.wind, wind)))
        assertEquals(WindMethod.CIRCLE, e.method)
        assertEquals("speed", wind.speed, e.wind.speed, 1.0)
        assertTrue("direction off by $dir", dir <= dirTolDeg)
        assertEquals("airspeed", sim.trueTas, e.airspeed, 1.0)
        assertTrue("quality ${e.quality}", e.quality > 0.5)
    }

    @Test fun circleWind_250_4() = checkCircle(Wind(250.0, 4.0), 15.0)
    @Test fun circleWind_40_7() = checkCircle(Wind(40.0, 7.0), 15.0)
    @Test fun circleWind_180_1p5() = checkCircle(Wind(180.0, 1.5), 40.0)

    @Test fun zeroWindReadsNearZero() {
        val (_, e) = afterFirstClimb(Wind(0.0, 0.0))
        assertNotNull(e)
        println("zero wind: est speed %.2f".format(e!!.wind.speed))
        assertTrue("speed ${e.wind.speed}", e.wind.speed < 1.0)
    }

    /** Wind estimate quality during a whole climb: how soon is it usable, and does it stay accurate? */
    @Test fun estimateConvergesDuringFirstThermal() {
        val wind = Wind(250.0, 4.0)
        val sim = FlightSim(seed = 3, wind = wind)
        val est = WindEstimator(sim.trimSpeed)
        var k = 0
        var firstGood = Double.NaN
        var worstAfter60 = 0.0
        var lastClimbStart = Double.NaN
        while (!sim.finished && sim.trueAlt < sim.groundElevM + 995.0) {
            sim.step(0.02)
            if (++k % 50 == 0 && sim.flying) {
                val f = sim.fix(0)
                est.add(f.timeMs, f.speed, f.track, f.altMsl)
                val e = est.estimate(f.timeMs)
                if (e != null) {
                    val err = vectorError(e.wind, wind)
                    if (firstGood.isNaN() && err < 1.5) firstGood = sim.timeS
                    if (firstGood.isNaN().not() && sim.timeS > firstGood + 60.0) worstAfter60 = maxOf(worstAfter60, err)
                }
                if (lastClimbStart.isNaN() && sim.trueVario > 0.5) lastClimbStart = sim.timeS
            }
        }
        println("first estimate within 1.5 m/s at t=%.0f s (climb began %.0f s); worst vector error afterwards %.2f"
            .format(firstGood, lastClimbStart, worstAfter60))
        assertTrue("usable within 40 s of starting the climb", firstGood - lastClimbStart < 40.0)
        assertTrue("worst error after convergence $worstAfter60", worstAfter60 < 1.0)
    }

    // --- TRIM method on synthetic tracks ---

    /** S-turn: heading swings sinusoidally by +/- [halfSpanDeg] around [meanHeading]; ground velocity = air + wind. */
    private fun sTurn(wind: Wind, halfSpanDeg: Double, meanHeading: Double, seconds: Int, alt: Double = 1500.0,
                      periodS: Double = 40.0, tasScale: Double = 1.0, noise: Double = 0.15): Pair<WindEstimator, Long> {
        val est = WindEstimator(10.5)
        val tas = tasScale * 10.5 / sqrt(Atmosphere.densityRatio(alt))
        val w = windVector(wind)
        val rng = Random(2)
        var now = 0L
        for (i in 0..seconds) {
            val h = Math.toRadians(meanHeading + halfSpanDeg * sin(2 * Math.PI * i / periodS))
            val gx = tas * sin(h) + w[0] + noise * rng.nextGaussian()
            val gy = tas * cos(h) + w[1] + noise * rng.nextGaussian()
            now = i * 1000L
            est.add(now, hypot(gx, gy), (Math.toDegrees(Math.atan2(gx, gy)) + 360.0) % 360.0, alt)
        }
        return est to now
    }

    @Test fun trimMethodOnSTurn() {
        var worstSpeed = 0.0
        var worstDir = 0.0
        for (wind in listOf(Wind(250.0, 4.0), Wind(40.0, 7.0), Wind(180.0, 3.0), Wind(90.0, 5.0))) {
            for (mean in doubleArrayOf(0.0, 120.0, 250.0)) {
                for (sign in doubleArrayOf(1.0, -1.0)) {
                    val (est, now) = sTurn(wind, 45.0 * sign, mean, 60)
                    val e = est.estimate(now)
                    assertNotNull("wind $wind heading $mean", e)
                    e!!
                    assertEquals(WindMethod.TRIM, e.method)
                    val dSpeed = abs(e.wind.speed - wind.speed)
                    val dDir = abs(Geo.angleDiff(e.wind.fromDeg, wind.fromDeg))
                    worstSpeed = maxOf(worstSpeed, dSpeed)
                    worstDir = maxOf(worstDir, dDir)
                    assertTrue("wind $wind heading $mean: speed error $dSpeed", dSpeed <= 2.0)
                    assertTrue("wind $wind heading $mean: direction error $dDir", dDir <= 30.0)
                    assertTrue("TRIM quality stays below CIRCLE's: ${e.quality}", e.quality < 0.5)
                }
            }
        }
        println("TRIM S-turn (90 deg): worst speed error %.2f m/s, worst direction error %.1f deg".format(worstSpeed, worstDir))
    }

    /** The honest weakness of TRIM: the pilot is not at the nominal trim speed. A 10 % error costs about 1 m/s of wind. */
    @Test fun trimIsRoughlyRobustToAirspeedAndNoiseErrors() {
        var worst = 0.0
        for (scale in doubleArrayOf(0.9, 1.1)) {
            for (wind in listOf(Wind(250.0, 4.0), Wind(40.0, 7.0))) {
                val (est, now) = sTurn(wind, 45.0, 0.0, 60, tasScale = scale, noise = 0.3)
                val e = est.estimate(now)!!
                worst = maxOf(worst, vectorError(e.wind, wind))
            }
        }
        println("TRIM with +/-10 %% airspeed error and 0.3 m/s noise: worst vector error %.2f m/s".format(worst))
        assertTrue("worst $worst", worst < 2.5)
    }

    @Test fun trimAirspeedFollowsTrimSpeedSettingAndAltitude() {
        val rho = sqrt(Atmosphere.densityRatio(3000.0))
        val (est, now) = sTurn(Wind(250.0, 4.0), 45.0, 0.0, 60, alt = 3000.0)
        assertEquals(10.5 / rho, est.estimate(now)!!.airspeed, 1e-6)
        // Replay the same fixes into an estimator whose trim speed is set after construction.
        val est2 = WindEstimator()
        est2.trimSpeed = 12.5
        val tas = 10.5 / rho
        val w = windVector(Wind(250.0, 4.0))
        for (i in 0..60) {
            val h = Math.toRadians(45.0 * sin(2 * Math.PI * i / 40.0))
            val gx = tas * sin(h) + w[0]
            val gy = tas * cos(h) + w[1]
            est2.add(i * 1000L, hypot(gx, gy), (Math.toDegrees(Math.atan2(gx, gy)) + 360.0) % 360.0, 3000.0)
        }
        assertEquals(12.5 / rho, est2.estimate(60_000L)!!.airspeed, 1e-6)
    }

    // --- Negative cases, ageing, hygiene ---

    @Test fun straightFlightGivesNothing() {
        val est = WindEstimator()
        for (i in 0..120) est.add(i * 1000L, 12.0 + 0.1 * (i % 3), 70.0 + 2.0 * Math.sin(i * 0.3), 1500.0)
        assertNull(est.estimate(120_000L))
    }

    @Test fun lessThan40DegreesIsNotUsedButPreviousEstimateAges() {
        val (est, now) = sTurn(Wind(250.0, 4.0), 45.0, 0.0, 60)
        val before = est.estimate(now)!!
        // Straight flight afterwards: the window slides until it holds < 40 deg of spread. Estimate must not change.
        var t = now
        val gx = before.airspeed * sin(0.0) + windVector(Wind(250.0, 4.0))[0]
        val gy = before.airspeed + windVector(Wind(250.0, 4.0))[1]
        for (i in 1..200) {
            t = now + i * 1000L
            est.add(t, hypot(gx, gy), Math.toDegrees(Math.atan2(gx, gy)), 1500.0)
        }
        val after = est.estimate(t)!!
        assertTrue("no new fit once the turn left the window: ${after.timeMs - now} ms", after.timeMs - now <= 90_000L)
        assertEquals(before.wind.speed, after.wind.speed, 1.0)
    }

    @Test fun qualityHalvesEveryFiveMinutes() {
        val (est, now) = sTurn(Wind(250.0, 4.0), 45.0, 0.0, 60)
        val q0 = est.estimate(now)!!.quality
        assertEquals(q0 / 2, est.estimate(now + 300_000L)!!.quality, 1e-9)
        assertEquals(q0 / 4, est.estimate(now + 600_000L)!!.quality, 1e-9)
        assertNull("stale estimates are dropped", est.estimate(now + 3_600_000L))
        // Same wind value while it ages.
        assertEquals(est.estimate(now)!!.wind.speed, est.estimate(now + 300_000L)!!.wind.speed, 0.0)
    }

    @Test fun badSamplesAreIgnored() {
        val est = WindEstimator()
        est.add(0, 0.5, 10.0, 0.0)       // too slow
        est.add(1000, 10.0, Double.NaN, 0.0) // no track
        est.add(2000, Double.NaN, 10.0, 0.0)
        assertNull(est.estimate(3000))
        val (e2, now) = sTurn(Wind(0.0, 3.0), 45.0, 0.0, 60)
        assertNotNull(e2.estimate(now))
        e2.reset()
        assertNull(e2.estimate(now))
    }

    @Test fun smoothingDoesNotJump() {
        // Feed a long full circle in steady wind, then a short burst of garbage-ish speed: estimate moves slowly.
        val wind = Wind(250.0, 4.0)
        val sim = FlightSim(seed = 3, wind = wind)
        val est = WindEstimator()
        var k = 0
        var prev: WindEstimate? = null
        var maxJump = 0.0
        while (!sim.finished && sim.trueAlt < sim.groundElevM + 500.0) {
            sim.step(0.02)
            if (++k % 50 == 0 && sim.flying) {
                val f = sim.fix(0)
                est.add(f.timeMs, f.speed, f.track, f.altMsl)
                val e = est.estimate(f.timeMs)
                if (e != null && prev != null) maxJump = maxOf(maxJump, vectorError(e.wind, prev.wind))
                prev = e
            }
        }
        println("max per-fix change of displayed wind: %.2f m/s".format(maxJump))
        assertTrue("jump $maxJump", maxJump < 1.5)
    }
}
