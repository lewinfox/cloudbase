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
    ) = evaluate(lat(y), lon(x), a, track, speed, vario)

    private fun List<AirspaceStatus>.single(level: AlertLevel): AirspaceStatus {
        assertEquals(toString(), 1, size)
        assertEquals(level, this[0].level)
        return this[0]
    }

    // ---- flying level toward a wall --------------------------------------------------------

    @Test
    fun levelFlightTowardCtrWallBecomesImminentThenInside() {
        val m = monitor(ctr)
        val a = alt(1000.0, terrain = 500.0)

        // 610 m from the wall at 15 m/s: crosses after 40.7 s; samples every 2 s, so reported as 42 s.
        val s = m.at(-610.0, 0.0, a, track = 90.0, speed = 15.0).single(AlertLevel.IMMINENT)
        assertEquals(40.67, s.timeToEntryS, 2.0)
        assertTrue(s.timeToEntryS >= 40.6)
        assertEquals(610.0, s.horizontalM, 2.0)
        assertEquals(VerticalRelation.WITHIN, s.relation)
        assertEquals(0.0, s.verticalM, 0.0)
        assertSame(ctr, s.airspace)

        // 305 m away: 20.3 s.
        val s2 = m.at(-305.0, 0.0, a, track = 90.0, speed = 15.0).single(AlertLevel.IMMINENT)
        assertTrue(s2.timeToEntryS in 20.3..22.3)

        // 50 m inside.
        val inside = m.at(50.0, 0.0, a, track = 90.0, speed = 15.0).single(AlertLevel.INSIDE)
        assertEquals(0.0, inside.horizontalM, 0.0)
        assertEquals(VerticalRelation.WITHIN, inside.relation)
        assertTrue(inside.timeToEntryS.isNaN())
    }

    @Test
    fun fasterAircraftSamplesFinerThanThirtyMetres() {
        // 20 m/s: step is 1.5 s (30 m). 1010 m away -> 50.5 s -> first sample at 51.0.
        val s = monitor(ctr).at(-1010.0, 0.0, alt(1000.0), track = 90.0, speed = 20.0).single(AlertLevel.IMMINENT)
        assertEquals(51.0, s.timeToEntryS, 1e-9)
    }

    @Test
    fun tooFarToReachWithinLookaheadIsNone() {
        val m = monitor(ctr)
        assertTrue(m.at(-1500.0, 0.0, alt(1000.0), track = 90.0, speed = 15.0).isEmpty()) // 100 s away
        assertTrue(m.at(-1000.0, 0.0, alt(1000.0), track = 90.0, speed = 15.0).isEmpty()) // 67 s away
    }

    @Test
    fun nearWhenCloseButNotConverging() {
        val m = monitor(ctr)
        // Stationary 290 m from the wall: NEAR.
        val s = m.at(-290.0, 0.0, alt(1000.0)).single(AlertLevel.NEAR)
        assertEquals(290.0, s.horizontalM, 1.0)
        assertTrue(s.timeToEntryS.isNaN())
        // 310 m: nothing.
        assertTrue(m.at(-310.0, 0.0, alt(1000.0)).isEmpty())
        // Flying away from the wall at 100 m: NEAR, not IMMINENT.
        val away = m.at(-100.0, 0.0, alt(1000.0), track = 270.0, speed = 15.0).single(AlertLevel.NEAR)
        assertEquals(100.0, away.horizontalM, 1.0)
        // Flying parallel to the wall 200 m off: NEAR.
        m.at(-200.0, 0.0, alt(1000.0), track = 0.0, speed = 15.0).single(AlertLevel.NEAR)
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
        val s = m.at(1000.0, 2800.0, alt(1000.0), track = 180.0, speed = 15.0).single(AlertLevel.IMMINENT)
        assertEquals(53.3, s.timeToEntryS, 2.5)
    }

    // ---- vertical behaviour ----------------------------------------------------------------

    @Test
    fun climbingUnderATmaFloor() {
        val m = monitor(tma)
        // 200 m below the floor, not climbing: nothing.
        assertTrue(m.at(0.0, 0.0, alt(1000.0)).isEmpty())
        // Climbing at 2 m/s from 200 m below: 100 s away, beyond the lookahead.
        assertTrue(m.at(0.0, 0.0, alt(1000.0), vario = 2.0).isEmpty())

        // 50 m below, level: NEAR (laterally inside counts as zero distance).
        val near = m.at(0.0, 0.0, alt(1150.0)).single(AlertLevel.NEAR)
        assertEquals(VerticalRelation.BELOW, near.relation)
        assertEquals(50.0, near.verticalM, 1e-9)
        assertEquals(0.0, near.horizontalM, 0.0)

        // 50 m below, climbing 2 m/s: 25 s to the floor, first 2 s sample inside is 26 s.
        val imminent = m.at(0.0, 0.0, alt(1150.0), vario = 2.0).single(AlertLevel.IMMINENT)
        assertEquals(26.0, imminent.timeToEntryS, 1e-9)
        assertEquals(VerticalRelation.BELOW, imminent.relation)
        assertEquals(50.0, imminent.verticalM, 1e-9)

        // 200 m below at 5 m/s: 40 s, imminent even though the vertical margin is not met.
        val fast = m.at(0.0, 0.0, alt(1000.0), vario = 5.0).single(AlertLevel.IMMINENT)
        assertEquals(40.0, fast.timeToEntryS, 2.0)

        // Above the floor: inside.
        val inside = m.at(0.0, 0.0, alt(1250.0)).single(AlertLevel.INSIDE)
        assertEquals(VerticalRelation.WITHIN, inside.relation)
    }

    @Test
    fun climbingThroughTheWholeSequence() {
        // Simulated climb at 2 m/s towards the 1200 m floor: NONE -> IMMINENT -> INSIDE (IMMINENT
        // supersedes NEAR while climbing; a level approach to the same heights goes through NEAR).
        val m = monitor(tma)
        val levels = ArrayList<AlertLevel>()
        var h = 1000.0
        while (h < 1260.0) {
            val st = m.at(0.0, 0.0, alt(h), vario = 2.0)
            levels.add(if (st.isEmpty()) AlertLevel.NONE else st[0].level)
            h += 2.0
        }
        val order = levels.distinct()
        assertEquals(listOf(AlertLevel.NONE, AlertLevel.IMMINENT, AlertLevel.INSIDE), order)
        // Level flight to the same heights does pass through NEAR.
        val levelFlight = (1000..1260 step 2).map { hh ->
            m.at(0.0, 0.0, alt(hh.toDouble())).let { if (it.isEmpty()) AlertLevel.NONE else it[0].level }
        }.distinct()
        assertEquals(listOf(AlertLevel.NONE, AlertLevel.NEAR, AlertLevel.INSIDE), levelFlight)
    }

    @Test
    fun aboveTheCeilingAndDescendingIn() {
        val m = monitor(tma)
        // FL100 = 3048 m. 52 m above: NEAR, relation ABOVE.
        val near = m.at(0.0, 0.0, alt(3100.0)).single(AlertLevel.NEAR)
        assertEquals(VerticalRelation.ABOVE, near.relation)
        assertEquals(52.0, near.verticalM, 1e-9)
        // Descending at 2 m/s from 100 m above the ceiling: 50 s.
        val imminent = m.at(0.0, 0.0, alt(3148.0), vario = -2.0).single(AlertLevel.IMMINENT)
        assertEquals(50.0, imminent.timeToEntryS, 2.0)
        assertEquals(VerticalRelation.ABOVE, imminent.relation)
        // 152 m above and level: nothing.
        assertTrue(m.at(0.0, 0.0, alt(3200.0)).isEmpty())
    }

    @Test
    fun passingSafelyOverOrUnderIsNone() {
        val m = monitor(ctr, tma)
        // Heading into the CTR wall at 2100 m MSL: 600 m above its 1500 m ceiling (and above nothing else).
        val over = m.at(-610.0, 0.0, alt(2100.0), track = 90.0, speed = 15.0)
        assertTrue(over.toString(), over.none { it.airspace === ctr })
        // Beneath the TMA at 600 m MSL (floor 1200), flying laterally into it.
        val under = m.at(-5500.0, 0.0, alt(600.0), track = 90.0, speed = 15.0)
        assertTrue(under.isEmpty())
        // And inside it laterally, still 600 m below.
        assertTrue(m.at(-4000.0, 0.0, alt(600.0), track = 90.0, speed = 15.0).none { it.airspace === tma })
    }

    @Test
    fun flightLevelFloorUsesPressureAltitudeNotMsl() {
        // Floor FL50 = 1524 m on the standard datum; ceiling FL100.
        val fl50 = box("FL50", "D", "TMA", fl(50), fl(100), -5000.0, -5000.0, 5000.0, 5000.0)
        val m = monitor(fl50)
        // QNH-high day: pressure altitude is 150 m below MSL. MSL says 1650 (above the floor) but the
        // aircraft is at 1500 m STD, 24 m under FL50.
        val s = m.at(0.0, 0.0, AircraftAlt(msl = 1650.0, pressureAlt = 1500.0, terrain = Double.NaN))
            .single(AlertLevel.NEAR)
        assertEquals(VerticalRelation.BELOW, s.relation)
        assertEquals(24.0, s.verticalM, 1e-9)
        // Same MSL, pressure altitude 1600: above the floor, so inside.
        m.at(0.0, 0.0, AircraftAlt(msl = 1650.0, pressureAlt = 1600.0, terrain = Double.NaN)).single(AlertLevel.INSIDE)
        // The other way round: MSL 1400 is below 1524 but pressure altitude 1550 is above it.
        m.at(0.0, 0.0, AircraftAlt(msl = 1400.0, pressureAlt = 1550.0, terrain = Double.NaN)).single(AlertLevel.INSIDE)
        // 150 m below on STD: nothing (altitude vs a naive MSL comparison would say 124 m above -> inside).
        assertTrue(m.at(0.0, 0.0, AircraftAlt(msl = 1650.0, pressureAlt = 1374.0, terrain = Double.NaN)).isEmpty())
    }

    @Test
    fun climbPredictionUsesTheFloorsOwnDatum() {
        val fl50 = box("FL50", "D", "TMA", fl(50), fl(100), -5000.0, -5000.0, 5000.0, 5000.0)
        val m = monitor(fl50)
        // Pressure altitude 1474 m (50 m under the floor), climbing 2 m/s -> 25 s -> sample at 26 s.
        val s = m.at(0.0, 0.0, AircraftAlt(msl = 1700.0, pressureAlt = 1474.0, terrain = Double.NaN), vario = 2.0)
            .single(AlertLevel.IMMINENT)
        assertEquals(26.0, s.timeToEntryS, 1e-9)
    }

    @Test
    fun groundReferencedCeilingWithHighTerrain() {
        // Ceiling 304.8 m (1000 ft) AGL; terrain 800 m, so the ceiling is at 1104.8 m MSL here.
        val low = box("LOW", "D", "CTR", gnd, agl(304.8), -5000.0, -5000.0, 5000.0, 5000.0)
        val m = monitor(low)
        // 200 m AGL: inside.
        m.at(0.0, 0.0, alt(1000.0, terrain = 800.0)).single(AlertLevel.INSIDE)
        // 400 m AGL: 95.2 m above the ceiling -> NEAR, ABOVE.
        val near = m.at(0.0, 0.0, alt(1200.0, terrain = 800.0)).single(AlertLevel.NEAR)
        assertEquals(VerticalRelation.ABOVE, near.relation)
        assertEquals(95.2, near.verticalM, 1e-9)
        // 500 m AGL: nothing; the MSL height of 1300 m alone would be nowhere near the ceiling.
        assertTrue(m.at(0.0, 0.0, alt(1300.0, terrain = 800.0)).isEmpty())
        // Unknown terrain is treated as sea level: 1000 m "AGL" is far above the ceiling.
        assertTrue(m.at(0.0, 0.0, alt(1000.0, terrain = Double.NaN)).isEmpty())
        // Descending 4 m/s from 500 m AGL: crosses 304.8 after 48.8 s, first 2 s sample is 50 s.
        val s = m.at(0.0, 0.0, alt(1300.0, terrain = 800.0), vario = -4.0).single(AlertLevel.IMMINENT)
        assertEquals(50.0, s.timeToEntryS, 1e-9)
        assertEquals(VerticalRelation.ABOVE, s.relation)
    }

    @Test
    fun surfaceFloorNeverCountsAsBelowGround() {
        // GPS or terrain error puts the aircraft 5 m "below" the ground: still inside a GND-floored zone.
        val m = monitor(ctr)
        m.at(1000.0, 0.0, alt(795.0, terrain = 800.0)).single(AlertLevel.INSIDE)
    }

    @Test
    fun unknownAltitudeIsTreatedAsInside() {
        val m = monitor(ctr)
        m.at(1000.0, 0.0, AircraftAlt(Double.NaN, Double.NaN, Double.NaN)).single(AlertLevel.INSIDE)
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
        // Now add one that does alert.
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
        val near150 = box("near150", "D", "CTR", gnd, msl(1500.0), -500.0, -1000.0, 500.0, -150.0)
        val near250 = box("near250", "D", "CTR", gnd, msl(1500.0), -500.0, -1000.0, 500.0, -250.0)
        // Deliberately shuffled input order.
        val m = monitor(near250, later, inside, near150, soon)
        val r = m.at(0.0, 0.0, alt(1000.0), track = 90.0, speed = 10.0)
        assertEquals(listOf("inside", "soon", "later", "near150", "near250"), r.map { it.airspace.name })
        assertEquals(
            listOf(AlertLevel.INSIDE, AlertLevel.IMMINENT, AlertLevel.IMMINENT, AlertLevel.NEAR, AlertLevel.NEAR),
            r.map { it.level },
        )
        assertTrue(r[1].timeToEntryS < r[2].timeToEntryS)
        assertEquals(150.0, r[3].horizontalM, 1.0)
        assertEquals(250.0, r[4].horizontalM, 1.0)
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
