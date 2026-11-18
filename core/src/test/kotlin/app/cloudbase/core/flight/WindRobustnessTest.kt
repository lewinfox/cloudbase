package app.cloudbase.core.flight

import app.cloudbase.core.Geo
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * What sloppy flying does to the circle-fit wind. Two different things get called "uneven
 * steering" and they behave differently: uneven TURNING (bank and turn rate wandering) only
 * changes how the same circle of ground velocities is sampled, while uneven AIRSPEED once per
 * turn (always braking on the same side) moves the circle itself.
 */
class WindRobustnessTest {
    private val windToEast = 3.0   // wind vector, the way it blows toward
    private val windToNorth = 2.0

    /** Flies [seconds] of turning and returns the wind-vector error in m/s. */
    private fun fly(seconds: Int, turnRate: (t: Int, heading: Double) -> Double, airspeed: (heading: Double) -> Double): Double {
        val est = WindEstimator()
        var heading = 0.0
        for (t in 0 until seconds) {
            val h = Math.toRadians(heading)
            val v = airspeed(heading)
            val ge = v * sin(h) + windToEast
            val gn = v * cos(h) + windToNorth
            est.add(t * 1000L, hypot(ge, gn), Geo.norm360(Math.toDegrees(atan2(ge, gn))), 1000.0)
            heading = Geo.norm360(heading + turnRate(t, heading))
        }
        val w = est.estimate(seconds * 1000L)!!
        // Convert "from" back to a "toward" vector.
        val to = Math.toRadians(w.wind.fromDeg + 180.0)
        return hypot(w.wind.speed * sin(to) - windToEast, w.wind.speed * cos(to) - windToNorth)
    }

    @Test fun unevenTurnRateDoesNotBiasTheWind() {
        // Tight on one side of the circle (25 deg/s), wide on the other (8 deg/s), constant airspeed.
        val lopsided = fly(120, { _, h -> if (h < 180) 25.0 else 8.0 }, { 11.0 })
        // Turn rate wandering in time, and a reversal of direction half way.
        val wandering = fly(120, { t, _ -> (if (t < 60) 1 else -1) * (14.0 + 9.0 * sin(t / 7.0)) }, { 11.0 })
        println("Wind error: lopsided turn %.3f m/s, wandering/reversing turn %.3f m/s".format(lopsided, wandering))
        assertTrue("lopsided $lopsided", lopsided < 0.1)
        assertTrue("wandering $wandering", wandering < 0.1)
    }

    @Test fun airspeedThatVariesOncePerTurnReadsAsWind() {
        // 1 m/s faster heading north, 1 m/s slower heading south, every turn. From the ground this
        // is indistinguishable from 1 m/s of wind, and that is exactly the error it causes.
        val bias = fly(120, { _, _ -> 18.0 }, { h -> 11.0 + 1.0 * cos(Math.toRadians(h)) })
        // Twice per turn (e.g. pumping the brakes) averages out instead.
        val twice = fly(120, { _, _ -> 18.0 }, { h -> 11.0 + 1.0 * cos(Math.toRadians(2 * h)) })
        println("Wind error: airspeed ±1 m/s once per turn %.3f m/s, twice per turn %.3f m/s".format(bias, twice))
        assertTrue("once per turn $bias", bias in 0.8..1.2)
        assertTrue("twice per turn $twice", twice < 0.2)
    }
}
