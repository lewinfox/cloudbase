package app.cloudbase.core.airspace

import app.cloudbase.core.airspace.TestAirspaces.agl
import app.cloudbase.core.airspace.TestAirspaces.alt
import app.cloudbase.core.airspace.TestAirspaces.box
import app.cloudbase.core.airspace.TestAirspaces.fl
import app.cloudbase.core.airspace.TestAirspaces.gnd
import app.cloudbase.core.airspace.TestAirspaces.lat
import app.cloudbase.core.airspace.TestAirspaces.lon
import app.cloudbase.core.airspace.TestAirspaces.msl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AirspaceMonitorTest {
    /** Class D CTR, surface to 1500 m MSL, east of the origin: x 0..4000, y -2000..2000. */
    private val ctr = box("CTR", "D", "CTR", gnd, msl(1500.0), 0.0, -2000.0, 4000.0, 2000.0)

    /** Class D TMA, 1200 m MSL to FL100 (3048 m STD), x and y within +-5000. */
    private val tma = box("TMA", "D", "TMA", msl(1200.0), fl(100), -5000.0, -5000.0, 5000.0, 5000.0)

    private fun monitor(vararg a: Airspace) = AirspaceMonitor().also { it.setCandidates(a.toList()) }

    private fun AirspaceMonitor.at(
        x: Double, y: Double, a: AircraftAlt, track: Double = Double.NaN, speed: Double = 0.0, vario: Double = 0.0,
        err: PositionError = PositionError.NONE,
    ) = evaluate(lat(y), lon(x), a, track, speed, vario, err)

    /** Same error in every datum. */
    private fun err(h: Double = 0.0, v: Double = 0.0) = PositionError(h, v, v, v)

    private fun List<AirspaceStatus>.single(level: AlertLevel): AirspaceStatus {
        assertEquals(toString(), 1, size)
        assertEquals(level, this[0].level)
        return this[0]
    }

    // ---- flying level toward a wall --------------------------------------------------------

    @Test
    fun levelFlightTowardCtrWallIsApproachingThenInside() {
        val m = monitor(ctr)
        val a = alt(1000.0, terrain = 500.0)

        // 610 m from the wall at 15 m/s: crosses after 40.7 s; samples every 2 s, so reported as 42 s.
        val s = m.at(-610.0, 0.0, a, track = 90.0, speed = 15.0).single(AlertLevel.APPROACHING)
        assertEquals(40.67, s.timeToEntryS, 2.0)
        assertTrue(s.timeToEntryS >= 40.6)
        assertEquals(610.0, s.horizontalM, 2.0)
        assertEquals(VerticalRelation.WITHIN, s.relation)
        assertEquals(0.0, s.verticalM, 0.0)
        assertSame(ctr, s.airspace)

        // 50 m inside.
        val inside = m.at(50.0, 0.0, a, track = 90.0, speed = 15.0).single(AlertLevel.INSIDE)
        assertEquals(0.0, inside.horizontalM, 0.0)
        assertTrue(inside.timeToEntryS.isNaN())
    }

    @Test
    fun positionErrorBringsTheWarningForward() {
        val m = monitor(ctr)
        // 940 m at 15 m/s is 62.7 s: just out of the 60 s window with a perfect fix...
        assertTrue(m.at(-940.0, 0.0, alt(1000.0), track = 90.0, speed = 15.0).isEmpty())
        // ...but with 60 m of position error the wall could be 880 m away: 58.7 s.
        val s = m.at(-940.0, 0.0, alt(1000.0), track = 90.0, speed = 15.0, err = err(h = 60.0)).single(AlertLevel.APPROACHING)
        assertTrue(s.timeToEntryS.toString(), s.timeToEntryS in 58.0..60.0)
    }

    @Test
    fun fasterAircraftSamplesFinerThanThirtyMetres() {
        // 20 m/s: step is 1.5 s (30 m). 1010 m away -> 50.5 s -> first sample at 51.0.
        val s = monitor(ctr).at(-1010.0, 0.0, alt(1000.0), track = 90.0, speed = 20.0).single(AlertLevel.APPROACHING)
        assertEquals(51.0, s.timeToEntryS, 1e-9)
    }

    @Test
    fun tooFarToReachWithinLookaheadIsNone() {
        val m = monitor(ctr)
        assertTrue(m.at(-1500.0, 0.0, alt(1000.0), track = 90.0, speed = 15.0).isEmpty()) // 100 s away
        assertTrue(m.at(-1000.0, 0.0, alt(1000.0), track = 90.0, speed = 15.0).isEmpty()) // 67 s away
    }

    @Test
    fun withinThePositionErrorCountsAsApproachingNow() {
        val m = monitor(ctr)
        // Stationary 20 m from the wall with 30 m of error: could already be in it.
        val s = m.at(-20.0, 0.0, alt(1000.0), err = err(h = 30.0)).single(AlertLevel.APPROACHING)
        assertEquals(0.0, s.timeToEntryS, 0.0)
        assertEquals(20.0, s.horizontalM, 1.0)
        // 40 m: clear.
        assertTrue(m.at(-40.0, 0.0, alt(1000.0), err = err(h = 30.0)).isEmpty())
        // Close but flying away, or along it: nothing to warn about any more (it was "near" before).
        assertTrue(m.at(-100.0, 0.0, alt(1000.0), track = 270.0, speed = 15.0, err = err(h = 30.0)).isEmpty())
        assertTrue(m.at(-200.0, 0.0, alt(1000.0), track = 0.0, speed = 15.0, err = err(h = 30.0)).isEmpty())
    }

    @Test
    fun noHorizontalPredictionWhenSlowOrWithoutTrack() {
        val m = monitor(ctr)
        assertTrue(m.at(-610.0, 0.0, alt(1000.0), track = 90.0, speed = 0.5).isEmpty())
        assertTrue(m.at(-610.0, 0.0, alt(1000.0), track = Double.NaN, speed = 15.0).isEmpty())
        assertTrue(m.at(-610.0, 0.0, alt(1000.0), track = 90.0, speed = Double.NaN).isEmpty())
    }

    @Test
    fun trackIsTrueBearing() {
        val m = monitor(ctr)
        // CTR lies to the east: heading north or south from 600 m west never enters it.
        assertTrue(m.at(-600.0, 0.0, alt(1000.0), track = 0.0, speed = 15.0).isEmpty())
        assertTrue(m.at(-600.0, 0.0, alt(1000.0), track = 180.0, speed = 15.0).isEmpty())
        // From the north, heading south into the CTR's north wall (y = 2000): 800 m, 53 s.
        val s = m.at(1000.0, 2800.0, alt(1000.0), track = 180.0, speed = 15.0).single(AlertLevel.APPROACHING)
        assertEquals(53.3, s.timeToEntryS, 2.5)
    }

    // ---- vertical behaviour ----------------------------------------------------------------

    @Test
    fun climbingUnderATmaFloor() {
        val m = monitor(tma)
        // 200 m below the floor, not climbing, or climbing 2 m/s (100 s away): nothing.
        assertTrue(m.at(0.0, 0.0, alt(1000.0)).isEmpty())
        assertTrue(m.at(0.0, 0.0, alt(1000.0), vario = 2.0).isEmpty())
        // 50 m below and level: nothing with a good altitude, approaching if it could be 60 m out.
        assertTrue(m.at(0.0, 0.0, alt(1150.0), err = err(v = 30.0)).isEmpty())
        val now = m.at(0.0, 0.0, alt(1150.0), err = err(v = 60.0)).single(AlertLevel.APPROACHING)
        assertEquals(0.0, now.timeToEntryS, 0.0)
        assertEquals(VerticalRelation.BELOW, now.relation)
        assertEquals(50.0, now.verticalM, 1e-9)
        assertEquals(0.0, now.horizontalM, 0.0)

        // 50 m below, climbing 2 m/s: 25 s to the floor, first 2 s sample inside is 26 s.
        assertEquals(26.0, m.at(0.0, 0.0, alt(1150.0), vario = 2.0).single(AlertLevel.APPROACHING).timeToEntryS, 1e-9)
        // With 20 m of altitude error the floor could be 30 m up: 15 s, sampled at 16.
        assertEquals(16.0, m.at(0.0, 0.0, alt(1150.0), vario = 2.0, err = err(v = 20.0)).single(AlertLevel.APPROACHING).timeToEntryS, 1e-9)

        // Above the floor: inside.
        m.at(0.0, 0.0, alt(1250.0)).single(AlertLevel.INSIDE)
    }

    @Test
    fun climbingThroughTheWholeSequence() {
        val m = monitor(tma)
        val levels = (1000..1260 step 2).map { h ->
            m.at(0.0, 0.0, alt(h.toDouble()), vario = 2.0, err = err(v = 20.0)).let { if (it.isEmpty()) AlertLevel.NONE else it[0].level }
        }.distinct()
        assertEquals(listOf(AlertLevel.NONE, AlertLevel.APPROACHING, AlertLevel.INSIDE), levels)
    }

    @Test
    fun aboveTheTopAndDescendingIn() {
        val m = monitor(tma)
        // FL100 = 3048 m. 52 m above with 60 m of error: could be in it.
        val near = m.at(0.0, 0.0, alt(3100.0), err = err(v = 60.0)).single(AlertLevel.APPROACHING)
        assertEquals(VerticalRelation.ABOVE, near.relation)
        assertEquals(52.0, near.verticalM, 1e-9)
        // Descending at 2 m/s from 100 m above: 50 s.
        val down = m.at(0.0, 0.0, alt(3148.0), vario = -2.0).single(AlertLevel.APPROACHING)
        assertEquals(50.0, down.timeToEntryS, 2.0)
        assertEquals(VerticalRelation.ABOVE, down.relation)
        assertTrue(m.at(0.0, 0.0, alt(3200.0), err = err(v = 60.0)).isEmpty())
    }

    @Test
    fun passingSafelyOverOrUnderIsNone() {
        val m = monitor(ctr, tma)
        val over = m.at(-610.0, 0.0, alt(2100.0), track = 90.0, speed = 15.0, err = err(30.0, 30.0))
        assertTrue(over.toString(), over.none { it.airspace === ctr })
        assertTrue(m.at(-5500.0, 0.0, alt(600.0), track = 90.0, speed = 15.0, err = err(30.0, 30.0)).isEmpty())
        assertTrue(m.at(-4000.0, 0.0, alt(600.0), track = 90.0, speed = 15.0, err = err(30.0, 30.0)).none { it.airspace === tma })
    }

    @Test
    fun flightLevelFloorUsesPressureAltitudeNotMsl() {
        // Floor FL50 = 1524 m on the standard datum; ceiling FL100.
        val fl50 = box("FL50", "D", "TMA", fl(50), fl(100), -5000.0, -5000.0, 5000.0, 5000.0)
        val m = monitor(fl50)
        // QNH-high day: MSL says 1650 (above the floor) but the aircraft is at 1500 m STD, 24 m under.
        val s = m.at(0.0, 0.0, AircraftAlt(msl = 1650.0, pressureAlt = 1500.0, terrain = Double.NaN), err = PositionError(0.0, 0.0, 30.0, 0.0))
            .single(AlertLevel.APPROACHING)
        assertEquals(VerticalRelation.BELOW, s.relation)
        assertEquals(24.0, s.verticalM, 1e-9)
        // The flight-level error is the one that counts: a large MSL error alone changes nothing.
        assertTrue(m.at(0.0, 0.0, AircraftAlt(msl = 1650.0, pressureAlt = 1500.0, terrain = Double.NaN), err = PositionError(0.0, 100.0, 10.0, 100.0)).isEmpty())
        m.at(0.0, 0.0, AircraftAlt(msl = 1650.0, pressureAlt = 1600.0, terrain = Double.NaN)).single(AlertLevel.INSIDE)
        m.at(0.0, 0.0, AircraftAlt(msl = 1400.0, pressureAlt = 1550.0, terrain = Double.NaN)).single(AlertLevel.INSIDE)
    }

    @Test
    fun climbPredictionUsesTheFloorsOwnDatum() {
        val fl50 = box("FL50", "D", "TMA", fl(50), fl(100), -5000.0, -5000.0, 5000.0, 5000.0)
        val m = monitor(fl50)
        val s = m.at(0.0, 0.0, AircraftAlt(msl = 1700.0, pressureAlt = 1474.0, terrain = Double.NaN), vario = 2.0)
            .single(AlertLevel.APPROACHING)
        assertEquals(26.0, s.timeToEntryS, 1e-9)
    }

    @Test
    fun groundReferencedTopWithHighTerrain() {
        // Top 304.8 m (1000 ft) AGL; terrain 800 m.
        val low = box("LOW", "D", "CTR", gnd, agl(304.8), -5000.0, -5000.0, 5000.0, 5000.0)
        val m = monitor(low)
        m.at(0.0, 0.0, alt(1000.0, terrain = 800.0)).single(AlertLevel.INSIDE)
        // 400 m AGL: 95.2 m above the top; within 100 m of error.
        val near = m.at(0.0, 0.0, alt(1200.0, terrain = 800.0), err = err(v = 100.0)).single(AlertLevel.APPROACHING)
        assertEquals(VerticalRelation.ABOVE, near.relation)
        assertEquals(95.2, near.verticalM, 1e-9)
        // Unknown terrain is treated as sea level: 1000 m "AGL" is far above the top.
        assertTrue(m.at(0.0, 0.0, alt(1000.0, terrain = Double.NaN), err = err(v = 100.0)).isEmpty())
        // Descending 4 m/s from 500 m AGL: crosses 304.8 after 48.8 s, first 2 s sample is 50 s.
        assertEquals(50.0, m.at(0.0, 0.0, alt(1300.0, terrain = 800.0), vario = -4.0).single(AlertLevel.APPROACHING).timeToEntryS, 1e-9)
    }

    @Test
    fun surfaceFloorNeverCountsAsBelowGround() {
        val m = monitor(ctr)
        m.at(1000.0, 0.0, alt(795.0, terrain = 800.0)).single(AlertLevel.INSIDE)
    }

    @Test
    fun unknownAltitudeIsTreatedAsInside() {
        val m = monitor(ctr)
        m.at(1000.0, 0.0, AircraftAlt(Double.NaN, Double.NaN, Double.NaN)).single(AlertLevel.INSIDE)
    }

    // ---- the top of the airspace you are in ------------------------------------------------

    @Test
    fun climbingToTheTopOfTheZoneYouAreIn() {
        val m = monitor(ctr) // top 1500 m MSL
        // 1400 m, level: plain inside.
        m.at(1000.0, 0.0, alt(1400.0), err = err(v = 20.0)).single(AlertLevel.INSIDE)
        // Climbing 2 m/s with 20 m of error: 80 m to go, 40 s.
        val c = m.at(1000.0, 0.0, alt(1400.0), vario = 2.0, err = err(v = 20.0)).single(AlertLevel.CEILING)
        assertEquals(40.0, c.timeToEntryS, 1e-9)
        assertEquals(100.0, c.verticalM, 1e-9)
        // Climbing 1 m/s: 80 s, beyond the warning time.
        m.at(1000.0, 0.0, alt(1400.0), vario = 1.0, err = err(v = 20.0)).single(AlertLevel.INSIDE)
        // Level but within the error of the top: could be at it already.
        assertEquals(0.0, m.at(1000.0, 0.0, alt(1490.0), err = err(v = 20.0)).single(AlertLevel.CEILING).timeToEntryS, 0.0)
    }

    @Test
    fun zoneTopUnderTheFloorAboveIsOneWarningNotTwo() {
        // A control area starting where the zone stops.
        val above = box("CTA", "C", "CTA", msl(1500.0), fl(150), -5000.0, -5000.0, 5000.0, 5000.0)
        val m = monitor(ctr, above)
        val r = m.at(1000.0, 0.0, alt(1400.0), vario = 2.0, err = err(v = 20.0))
        assertEquals(r.toString(), listOf(AlertLevel.INSIDE, AlertLevel.APPROACHING), r.map { it.level })
        assertSame(ctr, r[0].airspace)
        assertSame(above, r[1].airspace)
        // A floor well above the zone's top is a separate boundary: the top still warns.
        val high = box("HIGH", "C", "CTA", msl(1800.0), fl(150), -5000.0, -5000.0, 5000.0, 5000.0)
        m.setCandidates(listOf(ctr, high))
        m.at(1000.0, 0.0, alt(1400.0), vario = 2.0, err = err(v = 20.0)).single(AlertLevel.CEILING)
    }

    @Test
    fun ceilingIsTheLowerOfTheZoneTopAndTheFloorAbove() {
        val above = box("CTA", "C", "CTA", msl(1300.0), fl(150), -5000.0, -5000.0, 5000.0, 5000.0)
        val m = monitor(ctr, above)
        // Inside the CTR (top 1500) at 1000, under the CTA floor at 1300: the floor is lower.
        val c = m.ceiling(lat(0.0), lon(1000.0), alt(1000.0), err(v = 25.0))!!
        assertSame(above, c.airspace)
        assertEquals(300.0, c.metres, 1e-9)
        assertEquals(25.0, c.errorM, 0.0)
        // Inside both at 1400: the CTR's top, 100 m up.
        val c2 = m.ceiling(lat(0.0), lon(1000.0), alt(1400.0))!!
        assertSame(ctr, c2.airspace)
        assertEquals(100.0, c2.metres, 1e-9)
        // Outside the CTR laterally at 1400: inside the CTA, whose top is FL150.
        assertSame(above, m.ceiling(lat(0.0), lon(-1000.0), alt(1400.0))!!.airspace)
        assertNull(monitor(ctr).ceiling(lat(0.0), lon(-1000.0), alt(1000.0)))
    }

    // ---- candidates, ordering, headroom ----------------------------------------------------

    @Test
    fun onlyAlertRelevanceAirspacesProduceStatuses() {
        val classE = box("E", "E", "", gnd, msl(1500.0), -5000.0, -5000.0, 5000.0, 5000.0)
        val gsec = box("GSEC", "UNC", "GSEC", gnd, msl(1500.0), -5000.0, -5000.0, 5000.0, 5000.0)
        val fir = box("FIR", "UNC", "FIR", gnd, msl(1500.0), -5000.0, -5000.0, 5000.0, 5000.0)
        val high = box("HIGH", "A", "", msl(7000.0), msl(9000.0), -5000.0, -5000.0, 5000.0, 5000.0)
        val m = monitor(classE, gsec, fir, high)
        assertTrue(m.at(0.0, 0.0, alt(1000.0)).isEmpty())
        assertNull(m.headroom(lat(0.0), lon(0.0), alt(1000.0)))
        m.setCandidates(listOf(classE, ctr))
        m.at(1000.0, 0.0, alt(1000.0)).single(AlertLevel.INSIDE)
    }

    @Test
    fun emptyCandidatesIsEmpty() {
        val m = AirspaceMonitor()
        assertTrue(m.at(0.0, 0.0, alt(1000.0), track = 0.0, speed = 10.0).isEmpty())
        assertNull(m.headroom(lat(0.0), lon(0.0), alt(1000.0)))
    }

    @Test
    fun worstFirstOrdering() {
        val inside = box("inside", "D", "CTR", gnd, msl(1500.0), -500.0, -500.0, 500.0, 500.0)
        val soon = box("soon", "D", "CTR", gnd, msl(1500.0), 205.0, -500.0, 900.0, 500.0) // 20.5 s east
        val later = box("later", "D", "CTR", gnd, msl(1500.0), 405.0, -500.0, 900.0, 500.0) // 40.5 s east
        val now = box("now", "D", "CTR", gnd, msl(1500.0), -500.0, -1000.0, 500.0, -20.0) // 20 m south
        val m = monitor(later, inside, now, soon)
        val r = m.at(0.0, 0.0, alt(1000.0), track = 90.0, speed = 10.0, err = err(h = 30.0))
        assertEquals(listOf("inside", "now", "soon", "later"), r.map { it.airspace.name })
        assertEquals(AlertLevel.INSIDE, r[0].level)
        assertTrue(r.drop(1).all { it.level == AlertLevel.APPROACHING })
        assertEquals(0.0, r[1].timeToEntryS, 0.0)
        assertTrue(r[2].timeToEntryS < r[3].timeToEntryS)
    }

    @Test
    fun headroomIsTheLowestFloorAboveAtThisPosition() {
        val upper = box("T2", "C", "TMA", msl(2000.0), fl(150), -5000.0, -5000.0, 5000.0, 5000.0)
        val elsewhere = box("T3", "D", "TMA", msl(1100.0), fl(100), 20_000.0, -5000.0, 30_000.0, 5000.0)
        val classE = box("E", "E", "", msl(1050.0), fl(100), -5000.0, -5000.0, 5000.0, 5000.0) // SHOW only
        val m = monitor(upper, tma, elsewhere, classE, ctr)

        val h1 = m.headroom(lat(0.0), lon(0.0), alt(1000.0))
        assertNotNull(h1)
        assertSame(tma, h1!!.first)
        assertEquals(200.0, h1.second, 1e-9)

        // Above the first floor: the next one up counts, the surface-floored CTR is never "above".
        val h2 = m.headroom(lat(0.0), lon(0.0), alt(1300.0))!!
        assertSame(upper, h2.first)
        assertEquals(700.0, h2.second, 1e-9)

        assertNull(m.headroom(lat(0.0), lon(0.0), alt(2500.0)))

        val h3 = m.headroom(lat(0.0), lon(25_000.0), alt(1000.0))!!
        assertSame(elsewhere, h3.first)
        assertEquals(100.0, h3.second, 1e-9)

        // Outside every airspace laterally.
        assertNull(m.headroom(lat(0.0), lon(50_000.0), alt(1000.0)))
    }

    @Test
    fun headroomUsesTheFloorsOwnDatum() {
        val fl50 = box("FL50", "D", "TMA", fl(50), fl(100), -5000.0, -5000.0, 5000.0, 5000.0)
        val m = monitor(fl50)
        val h = m.headroom(lat(0.0), lon(0.0), AircraftAlt(msl = 1650.0, pressureAlt = 1500.0, terrain = Double.NaN))!!
        assertEquals(24.0, h.second, 1e-9)
        assertNull(m.headroom(lat(0.0), lon(0.0), AircraftAlt(msl = 1400.0, pressureAlt = 1550.0, terrain = Double.NaN)))
    }

    @Test
    fun headroomOverGroundReferencedFloor() {
        val floorAgl = box("AGLFLOOR", "D", "TMA", agl(300.0), fl(100), -5000.0, -5000.0, 5000.0, 5000.0)
        val m = monitor(floorAgl)
        // Terrain 800: aircraft at 1000 is 200 m AGL, 100 m below the 300 m AGL floor.
        assertEquals(100.0, m.headroom(lat(0.0), lon(0.0), alt(1000.0, terrain = 800.0))!!.second, 1e-9)
    }
}
