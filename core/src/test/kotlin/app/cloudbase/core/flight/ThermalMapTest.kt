package app.cloudbase.core.flight

import app.cloudbase.core.LocalProjection
import app.cloudbase.core.Wind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.roundToInt

class ThermalMapTest {
    private val lat0 = 46.0
    private val lon0 = 7.0
    private val proj = LocalProjection(lat0, lon0)

    private class Cell(val x: Double, val y: Double, val climb: Double, val ageMs: Long)

    /** Cells of [map] inside generous bounds, positions in metres from the anchor. */
    private fun cells(map: ThermalMap, nowMs: Long): List<Cell> {
        val out = ArrayList<Cell>()
        map.forEach(lat0 - 1.0, lon0 - 1.0, lat0 + 1.0, lon0 + 1.0, nowMs) { lat, lon, climb, age ->
            out.add(Cell(proj.x(lon), proj.y(lat), climb, age))
        }
        return out
    }

    private fun add(map: ThermalMap, x: Double, y: Double, climb: Double, t: Long) =
        map.add(proj.lat(y), proj.lon(x), climb, t)

    // --- drift ---

    @Test fun driftMovesEverythingTogetherWhateverTheRecordingTime() {
        // West wind (from 270) blows toward east at 5 m/s.
        val map = ThermalMap()
        val wind = Wind(270.0, 5.0)
        add(map, 0.0, 0.0, 2.0, 0L)             // A: recorded at t = 0 at the anchor (a cell centre)
        map.drift(wind, 60.0)                    // air has moved 300 m east
        add(map, 510.0, 90.0, 3.0, 60_000L)      // B: recorded at t = 60 s at a ground position of (510, 90)
        map.drift(wind, 60.0)                    // another 300 m
        val c = cells(map, 120_000L).sortedBy { it.climb }
        assertEquals(2, c.size)
        // Physics: A is 600 m east of where it started; B is 300 m east of where it was recorded.
        assertEquals(600.0, c[0].x, 0.01); assertEquals(0.0, c[0].y, 0.01)
        assertEquals(810.0, c[1].x, 0.01); assertEquals(90.0, c[1].y, 0.01)
        assertEquals(120_000L, c[0].ageMs)
        assertEquals(60_000L, c[1].ageMs)
    }

    @Test fun driftDirectionConventionIsFromNotTo() {
        // South-west wind (from 225) blows toward north-east; north wind (from 0) toward south.
        val ne = ThermalMap()
        add(ne, 0.0, 0.0, 1.0, 0L)
        ne.drift(Wind(225.0, 10.0), 100.0)
        val c = cells(ne, 0L).single()
        assertEquals(1000.0 / Math.sqrt(2.0), c.x, 0.01)
        assertEquals(1000.0 / Math.sqrt(2.0), c.y, 0.01)

        val south = ThermalMap()
        add(south, 0.0, 0.0, 1.0, 0L)
        south.drift(Wind(0.0, 4.0), 50.0)
        val s = cells(south, 0L).single()
        assertEquals(0.0, s.x, 0.01)
        assertEquals(-200.0, s.y, 0.01)
    }

    @Test fun samplesOfTheSameAirMergeAcrossDrift() {
        val map = ThermalMap()
        add(map, 0.0, 0.0, 2.0, 0L)
        map.drift(Wind(270.0, 5.0), 60.0)
        add(map, 300.0, 0.0, 4.0, 60_000L) // the glider followed the same air 300 m downwind
        assertEquals(1, map.size)
        // New samples weigh 0.4: 2.0 + 0.4 * (4.0 - 2.0)
        assertEquals(2.8, cells(map, 60_000L).single().climb, 1e-12)
    }

    @Test fun driftWithManySmallStepsEqualsOneBigStep() {
        val a = ThermalMap(); val b = ThermalMap()
        add(a, 0.0, 0.0, 1.0, 0L); add(b, 0.0, 0.0, 1.0, 0L)
        a.drift(Wind(100.0, 6.0), 600.0)
        repeat(600) { b.drift(Wind(100.0, 6.0), 1.0) }
        val ca = cells(a, 0L).single(); val cb = cells(b, 0L).single()
        assertEquals(ca.x, cb.x, 1e-6); assertEquals(ca.y, cb.y, 1e-6)
    }

    // --- visiting ---

    @Test fun forEachRespectsBoundsUsingDriftedPositions() {
        val map = ThermalMap()
        add(map, 0.0, 0.0, 1.0, 0L)
        add(map, 300.0, 0.0, 2.0, 0L)
        add(map, 0.0, 300.0, 3.0, 0L)
        add(map, -300.0, -300.0, 4.0, 0L)
        var seen = ArrayList<Double>()
        fun query(minX: Double, minY: Double, maxX: Double, maxY: Double): List<Double> {
            seen = ArrayList()
            map.forEach(proj.lat(minY), proj.lon(minX), proj.lat(maxY), proj.lon(maxX), 0L) { _, _, c, _ -> seen.add(c) }
            return seen.sorted()
        }
        assertEquals(listOf(1.0, 2.0, 3.0, 4.0), query(-1000.0, -1000.0, 1000.0, 1000.0))
        assertEquals(listOf(1.0, 3.0), query(-50.0, -50.0, 50.0, 350.0))
        assertEquals(listOf(4.0), query(-400.0, -400.0, -250.0, -250.0))
        assertEquals(emptyList<Double>(), query(500.0, 500.0, 900.0, 900.0))
        // After 100 s of 3 m/s westerly wind everything has moved 300 m east: the old box is empty of x=0 cells.
        map.drift(Wind(270.0, 3.0), 100.0)
        assertEquals(listOf(1.0, 3.0), query(250.0, -50.0, 350.0, 350.0))
        assertEquals(listOf(2.0), query(550.0, -50.0, 650.0, 50.0))
        assertEquals(emptyList<Double>(), query(-50.0, -50.0, 50.0, 350.0))
    }

    @Test fun forEachReportsLatLonAndAge() {
        val map = ThermalMap()
        map.add(lat0, lon0, 1.5, 1000L)
        var n = 0
        map.forEach(45.9, 6.9, 46.1, 7.1, 5000L) { lat, lon, climb, age ->
            n++
            assertEquals(lat0, lat, 1e-9); assertEquals(lon0, lon, 1e-9)
            assertEquals(1.5, climb, 0.0); assertEquals(4000L, age)
        }
        assertEquals(1, n)
        // Empty map: nothing, no crash.
        ThermalMap().forEach(0.0, 0.0, 1.0, 1.0, 0L) { _, _, _, _ -> error("no cells expected") }
    }

    // --- expiry and capacity ---

    @Test fun oldCellsAreInvisibleAndEventuallyPurged() {
        val map = ThermalMap(maxAgeMs = 20 * 60_000L)
        add(map, 0.0, 0.0, 2.0, 0L)
        add(map, 300.0, 0.0, 2.0, 10 * 60_000L)
        assertEquals(2, cells(map, 20 * 60_000L).size)          // exactly maxAge: still shown
        assertEquals(1, cells(map, 20 * 60_000L + 1).size)      // first one has just expired
        assertEquals(0, cells(map, 31 * 60_000L).size)
        assertEquals(2, map.size)                                // lazily: not yet removed
        add(map, 600.0, 0.0, 1.0, 31 * 60_000L)                  // next add purges
        assertEquals(1, map.size)
    }

    @Test fun expiredCellIsReplacedNotBlended() {
        val map = ThermalMap(maxAgeMs = 60_000L)
        add(map, 0.0, 0.0, 3.0, 0L)
        add(map, 0.0, 0.0, -2.0, 5_000L) // blended: 3 + 0.4 * (-5) = 1.0
        assertEquals(1.0, cells(map, 5_000L).single().climb, 1e-12)
        add(map, 0.0, 0.0, -2.0, 5_000L + 60_001L) // old reading is stale: forget it
        assertEquals(-2.0, cells(map, 65_001L).single().climb, 1e-12)
    }

    @Test fun capacityIsCappedAndOldestEvicted() {
        val map = ThermalMap(maxCells = 100)
        val rng = Random(4)
        for (i in 0 until 1000) {
            add(map, i * 30.0, 0.0, 1.0 + i, i * 1000L)
            assertTrue("size ${map.size} at $i", map.size <= 100)
        }
        assertEquals(100, map.size)
        val c = cells(map, 1_000_000L)
        assertEquals(100, c.size)
        // Exactly the 100 newest survive.
        assertEquals(901.0, c.minOf { it.climb }, 0.0)
        assertEquals(1000.0, c.maxOf { it.climb }, 0.0)
        rng.nextInt() // keep the Random referenced
    }

    @Test fun defaultCapIsNeverExceededOverAFullFlight() {
        val map = ThermalMap()
        val sim = FlightSim(seed = 2, sensorNoise = false)
        var k = 0
        var peak = 0
        while (!sim.finished) {
            sim.step(0.02)
            if (++k % 50 == 0 && sim.flying) {
                map.add(sim.trueLat, sim.trueLon, sim.trueAirMassClimb, (sim.timeS * 1000).toLong())
                map.drift(sim.wind, 1.0)
                peak = maxOf(peak, map.size)
            }
        }
        println("default map: peak size over the whole sim flight = $peak (cap 3000)")
        assertTrue(peak <= 3000)
    }

    /** Model-based test of the hash table: random adds with eviction and expiry against a plain LinkedHashMap. */
    @Test fun hashTableMatchesReferenceModel() {
        val maxCells = 64
        val maxAge = 5_000L
        val map = ThermalMap(maxAgeMs = maxAge, maxCells = maxCells)
        class Ref(var climb: Double, var time: Long)
        val ref = LinkedHashMap<Long, Ref>()
        val rng = Random(99)
        add(map, 0.0, 0.0, 0.0, 0L) // anchor
        ref[0L] = Ref(0.0, 0L)
        var t = 0L
        fun key(i: Int, j: Int) = i.toLong() * 100_000L + j
        for (step in 1..20_000) {
            t += 1 + rng.nextInt(400) // strictly increasing, so "oldest" is unambiguous
            val i = rng.nextInt(14) - 7
            val j = rng.nextInt(14) - 7
            val climb = rng.nextGaussian()
            add(map, i * 30.0, j * 30.0, climb, t)
            val k = key(i, j)
            val existing = ref[k]
            if (existing != null) {
                if (t - existing.time > maxAge) existing.climb = climb else existing.climb += 0.4 * (climb - existing.climb)
                existing.time = t
            } else {
                if (ref.size >= maxCells) {
                    ref.entries.removeIf { t - it.value.time > maxAge }
                    if (ref.size >= maxCells) ref.remove(ref.entries.minByOrNull { it.value.time }!!.key)
                }
                ref[k] = Ref(climb, t)
            }
            if (step % 97 == 0) {
                val got = cells(map, t).associate { key((it.x / 30.0).roundToInt(), (it.y / 30.0).roundToInt()) to it }
                val want = ref.filter { t - it.value.time <= maxAge }
                assertEquals("step $step", want.keys, got.keys)
                for ((kk, r) in want) {
                    assertEquals(r.climb, got[kk]!!.climb, 1e-9)
                    assertEquals(t - r.time, got[kk]!!.ageMs)
                }
                assertTrue(map.size <= maxCells)
            }
        }
    }

    @Test fun clearForgetsEverythingIncludingAnchorAndDrift() {
        val map = ThermalMap()
        add(map, 0.0, 0.0, 1.0, 0L)
        map.drift(Wind(270.0, 5.0), 100.0)
        map.clear()
        assertEquals(0, map.size)
        // New anchor elsewhere, and no leftover drift.
        map.add(47.0, 8.0, 1.0, 0L)
        var n = 0
        map.forEach(46.9, 7.9, 47.1, 8.1, 0L) { lat, lon, _, _ -> n++; assertEquals(47.0, lat, 1e-9); assertEquals(8.0, lon, 1e-9) }
        assertEquals(1, n)
    }

    // --- bestLift ---

    /** A Gaussian thermal of strength [peak] and 1/e radius [radius] sampled on every cell around (cx, cy). */
    private fun paint(map: ThermalMap, cx: Double, cy: Double, peak: Double, radius: Double, t: Long) {
        var x = cx - 3 * radius
        while (x <= cx + 3 * radius) {
            var y = cy - 3 * radius
            while (y <= cy + 3 * radius) {
                val d2 = (x - cx) * (x - cx) + (y - cy) * (y - cy)
                add(map, x, y, peak * exp(-d2 / (radius * radius)), t)
                y += 30.0
            }
            x += 30.0
        }
    }

    @Test fun bestLiftCentroidOfStrongCells() {
        val map = ThermalMap()
        add(map, 0.0, 0.0, 0.0, 0L)
        paint(map, 300.0, 150.0, 3.0, 80.0, 1000L)
        val core = map.bestLift(proj.lat(0.0), proj.lon(0.0), 1000.0, 2000L)
        assertNotNull(core)
        core!!
        assertEquals(300.0, proj.x(core.lon), 8.0)
        assertEquals(150.0, proj.y(core.lat), 8.0)
        assertEquals(3.0, core.climb, 0.3)
    }

    @Test fun bestLiftRespectsRadiusAgeAndThresholds() {
        val map = ThermalMap()
        add(map, 0.0, 0.0, 0.0, 0L)
        paint(map, 600.0, 0.0, 4.0, 60.0, 0L)   // strong but far
        paint(map, -120.0, 0.0, 2.0, 60.0, 0L)  // weaker, near
        val here = proj.lat(0.0) to proj.lon(0.0)
        // Radius 300 m only sees the near one.
        val near = map.bestLift(here.first, here.second, 300.0, 1000L)!!
        assertEquals(-120.0, proj.x(near.lon), 10.0)
        assertEquals(2.0, near.climb, 0.2)
        // Radius 1 km prefers the strongest.
        val far = map.bestLift(here.first, here.second, 1000.0, 1000L)!!
        assertEquals(600.0, proj.x(far.lon), 10.0)
        // Too old for a core (default 4 min) ...
        assertNull(map.bestLift(here.first, here.second, 1000.0, 5 * 60_000L))
        // ... unless the caller allows it.
        assertNotNull(map.bestLift(here.first, here.second, 1000.0, 5 * 60_000L, maxAgeForCoreMs = 10 * 60_000L))
        // Nothing above 0.5 m/s: no core.
        val weak = ThermalMap()
        paint(weak, 0.0, 0.0, 0.5, 80.0, 0L)
        assertNull(weak.bestLift(lat0, lon0, 500.0, 0L))
        assertNull(ThermalMap().bestLift(lat0, lon0, 500.0, 0L))
    }

    @Test fun bestLiftIgnoresFringeBelowSixtyPercent() {
        val map = ThermalMap()
        add(map, 0.0, -300.0, 0.0, 0L) // anchor, in a cell of its own and too weak to count
        add(map, 0.0, 0.0, 3.0, 0L)
        add(map, 30.0, 0.0, 3.0, 0L)
        add(map, 300.0, 0.0, 1.7, 0L) // 57 % of the max: excluded although > 0.5 m/s
        add(map, 600.0, 0.0, 1.9, 0L) // 63 %: included
        val core = map.bestLift(lat0, lon0, 2000.0, 0L)!!
        val expectedX = (0.0 * 3.0 + 30.0 * 3.0 + 600.0 * 1.9) / (3.0 + 3.0 + 1.9)
        assertEquals(expectedX, proj.x(core.lon), 0.05) // (map anchored 300 m south of this test's projection)
    }

    @Test fun bestLiftFollowsTheDrift() {
        val map = ThermalMap()
        add(map, 0.0, 0.0, 3.0, 0L)
        val wind = Wind(270.0, 5.0)
        repeat(60) { map.drift(wind, 1.0) }
        val core = map.bestLift(lat0, lon0, 1000.0, 60_000L)!!
        assertEquals(300.0, proj.x(core.lon), 0.01)
    }

    /** The real acceptance test: find the simulated thermal's drifted centre from the glider's own track. */
    private fun thermalCentreError(map: ThermalMap, sim: FlightSim, noisy: Boolean, turns: Double): Double {
        val th = sim.thermals[0]
        val thX0 = proj.x(th.lon)
        val thY0 = proj.y(th.lat)
        val to = Math.toRadians(sim.wind.fromDeg + 180.0)
        val wE = sim.wind.speed * Math.sin(to)
        val wN = sim.wind.speed * Math.cos(to)
        var k = 0
        var circlingSince = Double.NaN
        var lastHeading = sim.headingDeg
        while (!sim.finished) {
            sim.step(0.02)
            val turn = Math.toRadians((((sim.headingDeg - lastHeading + 540.0) % 360.0) - 180.0)) / 0.02
            lastHeading = sim.headingDeg
            if (++k % 50 != 0 || !sim.flying) continue
            val nowMs = (sim.timeS * 1000).toLong()
            if (noisy) {
                val f = sim.fix(0)
                map.add(f.lat, f.lon, sim.trueAirMassClimb, nowMs)
            } else {
                map.add(sim.trueLat, sim.trueLon, sim.trueAirMassClimb, nowMs)
            }
            map.drift(sim.wind, 1.0)
            if (turn > 0.3 && circlingSince.isNaN()) circlingSince = sim.timeS
            if (!circlingSince.isNaN() && sim.timeS - circlingSince >= turns * 18.0) {
                val core = map.bestLift(sim.trueLat, sim.trueLon, 250.0, nowMs)
                assertNotNull("no core after $turns turns", core)
                val thX = thX0 + wE * sim.timeS
                val thY = thY0 + wN * sim.timeS
                return hypot(proj.x(core!!.lon) - thX, proj.y(core.lat) - thY)
            }
        }
        error("never circled")
    }

    @Test fun bestLiftFindsSimThermalAfterTwoAndThreeTurns() {
        for (turns in doubleArrayOf(2.0, 3.0)) {
            for (wind in listOf(Wind(250.0, 4.0), Wind(40.0, 7.0))) {
                val e = thermalCentreError(ThermalMap(), FlightSim(seed = 1, wind = wind, sensorNoise = false), false, turns)
                println("bestLift after %.0f turns, wind %s: centre error %.1f m".format(turns, wind, e))
                assertTrue("error $e m after $turns turns", e < 40.0)
            }
        }
    }

    @Test fun bestLiftWithNoisyGpsPositions() {
        val e = thermalCentreError(ThermalMap(), FlightSim(seed = 5), true, 3.0)
        println("bestLift with GPS noise after 3 turns: centre error %.1f m".format(e))
        assertTrue("error $e m", e < 40.0)
    }
}
