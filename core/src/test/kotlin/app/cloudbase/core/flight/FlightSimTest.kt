package app.cloudbase.core.flight

import app.cloudbase.core.Atmosphere
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class FlightSimTest {
    private class Summary {
        var maxAlt = 0.0
        var takeoffs = 0
        var landings = 0
        var timeS = 0.0
        var lastFlying = false
        var lastAlt = 0.0
    }

    private fun runToEnd(sim: FlightSim, dt: Double = 0.02): Summary {
        val s = Summary()
        while (!sim.finished && sim.timeS < 3600.0) {
            sim.step(dt)
            if (sim.flying && !s.lastFlying) s.takeoffs++
            if (!sim.flying && s.lastFlying) s.landings++
            s.lastFlying = sim.flying
            s.maxAlt = maxOf(s.maxAlt, sim.trueAlt)
            s.lastAlt = sim.trueAlt
        }
        s.timeS = sim.timeS
        return s
    }

    @Test fun takesOffClimbsLandsAndFinishes() {
        val sim = FlightSim(seed = 1)
        val s = runToEnd(sim)
        println("sim: time=%.0f s, peak %.0f m above launch".format(s.timeS, s.maxAlt - sim.groundElevM))
        assertTrue("finished", sim.finished)
        assertTrue("under 40 min, was ${s.timeS}", s.timeS < 40 * 60)
        assertTrue("climbs >= 800 m, was ${s.maxAlt - sim.groundElevM}", s.maxAlt - sim.groundElevM >= 800.0)
        assertEquals("landed 600 m below launch", sim.groundElevM - 600.0, s.lastAlt, 0.01)
        assertEquals(1, s.takeoffs)
        assertEquals(1, s.landings)
        assertFalse(sim.flying)
    }

    @Test fun deterministicForSeed() {
        fun trace(seed: Long): DoubleArray {
            val sim = FlightSim(seed)
            val out = DoubleArray(8)
            repeat(40_000) { // 800 s at 20 ms
                sim.step(0.02)
                if (it % 5000 == 0) {
                    val f = sim.fix(0)
                    out[it / 5000] = f.lat * 1e3 + f.lon + f.altMsl + sim.pressureHpa + sim.accel[0] + sim.accel[2]
                }
            }
            return out
        }
        assertTrue(trace(7).contentEquals(trace(7)))
        assertFalse(trace(7).contentEquals(trace(8)))
    }

    @Test fun noiseFreeSensorsAreExact() {
        val sim = FlightSim(sensorNoise = false)
        repeat(3000) { sim.step(0.02) } // 60 s: flying
        val f = sim.fix(1_000_000L)
        assertEquals(sim.trueLat, f.lat, 0.0)
        assertEquals(sim.trueLon, f.lon, 0.0)
        assertEquals(sim.trueAlt, f.altMsl, 0.0)
        assertEquals(1_060_000L, f.timeMs)
    }

    @Test fun sensorsAreConsistentWithTruth() {
        val sim = FlightSim(sensorNoise = false)
        // At rest the accelerometer reads exactly 1 g.
        val a = sim.accel
        assertEquals(9.80665, sqrt((a[0] * a[0] + a[1] * a[1] + a[2] * a[2]).toDouble()), 1e-4)
        // Pressure round-trips to altitude.
        assertEquals(sim.groundElevM, Atmosphere.altitude(sim.pressureHpa), 0.01)
        // Standing still: no course.
        assertTrue(sim.fix(0).track.isNaN())
    }
}
