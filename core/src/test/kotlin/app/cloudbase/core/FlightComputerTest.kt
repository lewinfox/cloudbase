package app.cloudbase.core

import app.cloudbase.core.airspace.AirspaceIndex
import app.cloudbase.core.airspace.AlertLevel
import app.cloudbase.core.airspace.OpenAir
import app.cloudbase.core.flight.FlightEvent
import app.cloudbase.core.flight.FlightSim
import app.cloudbase.core.flight.FlightState
import app.cloudbase.core.igc.IgcSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader
import kotlin.math.abs

/** The whole pipeline, sensor samples in → instruments, alerts and an IGC file out, on a simulated flight. */
class FlightComputerTest {
    private class MemorySink : FlightLogSink {
        val text = StringBuilder()
        var opened = 0
        var closed = 0
        override fun open(firstFixMs: Long): Appendable { opened++; return text }
        override fun flush() {}
        override fun close() { closed++ }
    }

    private class Run(val computer: FlightComputer, val sink: MemorySink, val events: List<FlightEvent>, val sim: FlightSim)

    /** Feeds a FlightSim through the computer exactly as the Android service does. */
    private fun fly(
        wind: Wind = Wind(250.0, 4.0), baro: Boolean = true, accel: Boolean = true, terrainKnown: Boolean = true,
        setup: (FlightComputer, FlightSim) -> Unit = { _, _ -> }, eachFix: (FlightComputer, FlightSim) -> Unit = { _, _ -> },
    ): Run {
        val sim = FlightSim(seed = 3, wind = wind)
        // Flat ground at launch height until the sim descends below it, then flat at landing height:
        // crude, but enough to give the detector a height above ground.
        val computer = FlightComputer { _, _ -> if (!terrainKnown) Double.NaN else if (sim.trueAlt < sim.groundElevM - 30) sim.groundElevM - 600 else sim.groundElevM }
        val sink = MemorySink()
        val events = ArrayList<FlightEvent>()
        computer.logSink = sink
        computer.onFlightEvent = { events.add(it) }
        setup(computer, sim)
        var nextBaro = 0.0
        var nextFix = 0.0
        val epoch = 1_800_000_000_000L
        while (!sim.finished) {
            sim.step(0.04)
            val tNs = (sim.timeS * 1e9).toLong()
            if (accel) sim.accel.let { computer.onAccel(tNs, it[0], it[1], it[2]) }
            if (baro && sim.timeS >= nextBaro) { nextBaro += 0.05; computer.onPressure(tNs, sim.pressureHpa) }
            if (sim.timeS >= nextFix) { nextFix += 1.0; computer.onFix(sim.fix(epoch)); eachFix(computer, sim) }
        }
        return Run(computer, sink, events, sim)
    }

    @Test fun detectsTakeoffAndLandingAndWritesOneLog() {
        val r = fly()
        assertEquals(listOf(FlightEvent.TAKEOFF, FlightEvent.LANDING), r.events)
        assertEquals(FlightState.GROUND, r.computer.flightState)
        assertEquals(1, r.sink.opened)
        assertEquals(1, r.sink.closed)
        val s = IgcSummary.read(StringReader(r.sink.text.toString()))
        assertNotNull(s)
        // The log covers the flight, not the waiting before and after it.
        assertTrue("duration ${s!!.durationS}", s.durationS > 600)
        assertTrue("max alt ${s.maxAltM}", s.maxAltM > r.sim.groundElevM + 700)
        assertTrue("fix count ${s.fixCount} vs duration ${s.durationS}", abs(s.fixCount - s.durationS) < 5)
    }

    @Test fun altitudeTracksTruthWithBarometer() {
        var worst = 0.0
        var worstClimb = 0.0
        fly(eachFix = { c, sim ->
            if (sim.flying && sim.timeS > 120) {
                worst = maxOf(worst, abs(c.altMsl - sim.trueAlt))
                worstClimb = maxOf(worstClimb, abs(c.climb - sim.trueVario))
            }
        })
        // GPS altitude noise is 4 m; the fused altitude should be much better than a single fix.
        assertTrue("worst altitude error $worst m", worst < 6.0)
        assertTrue("worst climb error $worstClimb m/s", worstClimb < 1.0)
    }

    @Test fun worksWithoutBarometerOrAccelerometer() {
        var worst = 0.0
        val r = fly(baro = false, accel = false, eachFix = { c, sim ->
            if (sim.flying && sim.timeS > 120) worst = maxOf(worst, abs(c.altMsl - sim.trueAlt))
        })
        assertEquals(listOf(FlightEvent.TAKEOFF, FlightEvent.LANDING), r.events)
        assertTrue("worst altitude error $worst m", worst < 15.0)
        assertTrue(r.computer.pressureAlt.isNaN())
    }

    @Test fun glideLineOnlyWhileDescendingAndAtASaneRatio() {
        var onGlide = 0
        var whileClimbing = 0
        var worstRatio = 0.0
        fly(wind = Wind(250.0, 0.5), eachFix = { c, sim ->
            if (!sim.flying) return@fly
            if (c.glideImpact != null) {
                onGlide++
                if (sim.trueVario > 0.8) whileClimbing++
                // Trim is about 10.5 m/s at 1.1 m/s sink: glide 9.5 in still air.
                if (c.glideRatio > worstRatio) worstRatio = c.glideRatio
            }
        })
        assertTrue("glide line shown on $onGlide fixes", onGlide > 100)
        assertTrue("shown on $whileClimbing fixes while climbing hard", whileClimbing < 10)
        assertTrue("largest glide ratio $worstRatio", worstRatio < 40)
    }

    @Test fun knowsWhenItIsCircling() {
        var agree = 0
        var total = 0
        var flips = 0
        var last = false
        fly(eachFix = { c, sim ->
            if (c.circling != last) { flips++; last = c.circling }
            if (!sim.flying) return@fly
            total++
            // The sim circles while it is in lift and glides straight otherwise.
            if (c.circling == (sim.trueVario > 0.3)) agree++
        })
        assertTrue("agreed on $agree of $total fixes", agree > total * 0.85)
        // Two thermals: into and out of each, with no flickering.
        assertEquals(4, flips)
    }

    @Test fun resetAllowsAnEarlierClockAndStartsANewLog() {
        // First flight leaves the clock far ahead; after reset a second one from time zero must work.
        val sim = FlightSim(seed = 3)
        val c = FlightComputer { _, _ -> if (sim.trueAlt < sim.groundElevM - 30) sim.groundElevM - 600 else sim.groundElevM }
        val sink = MemorySink()
        c.logSink = sink
        c.onFix(Fix(9_000_000_000_000L, sim.startLat, sim.startLon, sim.groundElevM, 0.0, Double.NaN))
        assertTrue(c.startRecording())
        assertEquals(1, sink.opened)
        c.reset()
        assertEquals(1, sink.closed)
        assertEquals(FlightState.GROUND, c.flightState)
        var nb = 0.0; var nf = 0.0
        while (!sim.finished) {
            sim.step(0.04)
            val t = (sim.timeS * 1e9).toLong()
            sim.accel.let { c.onAccel(t, it[0], it[1], it[2]) }
            if (sim.timeS >= nb) { nb += 0.05; c.onPressure(t, sim.pressureHpa) }
            if (sim.timeS >= nf) { nf += 1.0; c.onFix(sim.fix(1_000L)) }
        }
        assertEquals(2, sink.opened)
        assertEquals(2, sink.closed)
        // The header is written at takeoff, by which time the barometer is known.
        assertTrue(sink.text.contains("HFALPALTPRESSURE:ISA"))
    }

    @Test fun recordPressedOnLaunchKeepsOneLogThroughTakeoffUntilLanding() {
        // Pressed two seconds in; the sim then stands still on launch for half a minute, longer
        // than the detector needs to call a landing. That must not end the log.
        val starts = ArrayList<Long>()
        val r = fly(eachFix = { c, sim ->
            if (sim.timeS in 2.0..3.0 && !c.recording) { assertTrue(c.startRecording()); starts.add(c.recordStartMs) }
            if (!sim.flying && sim.timeS in 3.0..25.0) assertTrue("recording on launch at ${sim.timeS}", c.recording)
        })
        assertEquals(1, starts.size)
        assertEquals(listOf(FlightEvent.TAKEOFF, FlightEvent.LANDING), r.events)
        assertEquals(1, r.sink.opened)
        assertEquals(1, r.sink.closed)
        assertTrue(!r.computer.recording)
        // The file covers the wait on launch and the whole flight.
        assertTrue(IgcSummary.read(StringReader(r.sink.text.toString()))!!.durationS > 600)
    }

    @Test fun stopPressedInTheAirStaysStoppedUntilLanding() {
        var stopped = false
        val r = fly(eachFix = { c, sim ->
            if (!stopped && sim.flying && c.recording && sim.timeS > 120) { c.stopRecording(); stopped = true }
            if (stopped && sim.flying) assertTrue("restarted at ${sim.timeS}", !c.recording)
        })
        assertTrue(stopped)
        assertEquals(listOf(FlightEvent.TAKEOFF, FlightEvent.LANDING), r.events)
        assertEquals(1, r.sink.opened)
        assertEquals(1, r.sink.closed)
    }

    @Test fun withAutoRecordOffTheButtonsStartAndEndTheFlight() {
        var pressed = false
        val r = fly(setup = { c, _ -> c.autoRecord = false }, eachFix = { c, sim ->
            if (!pressed && sim.timeS >= 2.0) { c.startRecording(); pressed = true }
            if (pressed && c.recording && sim.finished) c.stopRecording()
        })
        assertEquals(FlightEvent.TAKEOFF, r.events.first())
        assertEquals(1, r.sink.opened)
        r.computer.stopRecording()
        assertEquals(listOf(FlightEvent.TAKEOFF, FlightEvent.LANDING), r.events)
        assertEquals(1, r.sink.closed)
        assertEquals(FlightState.GROUND, r.computer.flightState)
    }

    @Test fun retriesOpeningTheLogWhileAirborne() {
        var attempts = 0
        val text = StringBuilder()
        val flaky = object : FlightLogSink {
            override fun open(firstFixMs: Long): Appendable? = if (++attempts < 3) null else text
            override fun flush() {}
            override fun close() {}
        }
        val r = fly(setup = { c, _ -> c.logSink = flaky })
        assertTrue("attempts $attempts", attempts == 3)
        assertTrue(IgcSummary.read(StringReader(text.toString()))!!.durationS > 600)
        assertEquals(FlightState.GROUND, r.computer.flightState)
    }

    @Test fun liftIsRememberedWhereItWasMetForTheWholeFlight() {
        // Where the sim was, over the ground, a minute into its first climb.
        var spotLat = Double.NaN
        var spotLon = Double.NaN
        var spotTime = 0.0
        var liveAtSpot = 0.0
        var historyAtSpot = 0.0
        var lastFixMs = 0L
        val r = fly(wind = Wind(250.0, 5.0), eachFix = { c, sim ->
            if (spotLat.isNaN() && c.circling && sim.trueVario > 1.5) { spotLat = sim.trueLat; spotLon = sim.trueLon; spotTime = sim.timeS }
            // Look again 25 minutes later, just before landing.
            if (!spotLat.isNaN() && sim.flying && sim.timeS > spotTime + 25 * 60) {
                liveAtSpot = strongestNear(c.thermals, spotLat, spotLon, c.fix!!.timeMs)
                historyAtSpot = strongestNear(c.thermalHistory, spotLat, spotLon, c.fix!!.timeMs)
                lastFixMs = c.fix!!.timeMs
            }
        })
        assertTrue("never looked", lastFixMs != 0L)
        // The drifting map has long since carried that lift downwind (or aged it out)...
        assertTrue("live map still shows $liveAtSpot there", liveAtSpot < 0.5)
        // ...but the history still says there was a climb at that place.
        assertTrue("history shows $historyAtSpot there", historyAtSpot > 1.0)
        assertTrue(r.computer.thermalHistory.size > r.computer.thermals.size)
    }

    private fun strongestNear(map: app.cloudbase.core.flight.ThermalMap, lat: Double, lon: Double, nowMs: Long): Double {
        var best = 0.0
        val d = 60.0 / 111_000.0
        map.forEach(lat - d, lon - d * 1.5, lat + d, lon + d * 1.5, nowMs) { _, _, climb, _ -> if (climb > best) best = climb }
        return best
    }

    @Test fun worksWithoutTerrain() {
        val r = fly(terrainKnown = false)
        assertEquals(listOf(FlightEvent.TAKEOFF, FlightEvent.LANDING), r.events)
    }

    @Test fun measuresWindAndMapsTheThermal() {
        val wind = Wind(40.0, 6.0)
        var best: Double = Double.NaN
        var sawCore = false
        val r = fly(wind = wind, eachFix = { c, sim ->
            if (c.thermalCore != null) sawCore = true
            if (sim.flying) c.windEstimate?.let { best = it.wind.speed }
        })
        val w = r.computer.windEstimate
        assertNotNull("no wind estimate", w)
        assertEquals(wind.speed, w!!.wind.speed, 1.5)
        assertTrue("direction ${w.wind.fromDeg}", abs(Geo.angleDiff(w.wind.fromDeg, wind.fromDeg)) < 20)
        assertTrue(sawCore)
        assertTrue(r.computer.thermals.size > 50)
        assertTrue(!best.isNaN())
    }

    @Test fun warnsBeforeClimbingIntoAirspaceOverhead() {
        // A floor 500 m above launch, right over the first thermal: the sim climbs straight into it.
        val levels = ArrayList<AlertLevel>()
        var firstWarnHeadroom = Double.NaN
        fly(setup = { c, sim ->
            val floorFt = ((sim.groundElevM + 500) / Atmosphere.FT).toInt()
            val lat = sim.startLat; val lon = sim.startLon
            val text = """
                AC D
                AN TEST TMA
                AL ${floorFt}ft AMSL
                AH FL195
                V X=${dms(lat)} N ${dms(lon)} E
                DC 20
            """.trimIndent()
            val parsed = OpenAir.parse(StringReader(text))
            assertEquals(parsed.warnings.toString(), 1, parsed.airspaces.size)
            c.setAirspace(AirspaceIndex(parsed.airspaces))
            c.onAlert = { e ->
                if (levels.lastOrNull() != e.status.level) levels.add(e.status.level)
                if (firstWarnHeadroom.isNaN()) firstWarnHeadroom = e.status.verticalM
            }
        })
        assertTrue("alert sequence $levels", levels.contains(AlertLevel.INSIDE))
        // The first warning must come while still below the floor, not once already inside.
        assertTrue("first alert was ${levels.first()}", levels.first() != AlertLevel.INSIDE)
        assertTrue("first warning at $firstWarnHeadroom m below the floor", firstWarnHeadroom > 20)
    }

    @Test fun headingHoldsStillWhileCirclingAndPointsDownwind() {
        // Wind from 250 at 4 m/s: circling, the glider drifts towards 070.
        var circlingFixes = 0
        var sinceStart = 0
        var biggestStep = 0.0
        var offDownwind = 0
        var last = Double.NaN
        fly(wind = Wind(250.0, 4.0), eachFix = { c, _ ->
            if (!c.circling) { sinceStart = 0; last = Double.NaN; return@fly }
            // The first turn of each thermal is where the heading changes over from the glide
            // to the drift: a real change, made once.
            if (++sinceStart <= FIRST_TURN_S) return@fly
            val h = c.heading.trackDeg
            circlingFixes++
            if (!last.isNaN()) biggestStep = maxOf(biggestStep, abs(Geo.angleDiff(h, last)))
            last = h
            if (abs(Geo.angleDiff(h, 70.0)) > 30) offDownwind++
        })
        assertTrue("circled for $circlingFixes s", circlingFixes > 200)
        // The raw track turns 15-25 degrees every second; the heading barely moves.
        assertTrue("heading moved up to $biggestStep degrees in a second", biggestStep < 10)
        assertTrue("$offDownwind of $circlingFixes fixes not downwind", offDownwind < circlingFixes / 20)
    }

    private val FIRST_TURN_S = 25

    /** Circles of [radiusNm] around launch, from OpenAir text; returns them as parsed. */
    private fun zones(sim: FlightSim, vararg z: Triple<String, String, String>, radiusNm: Int = 20): List<app.cloudbase.core.airspace.Airspace> {
        val text = z.joinToString("\n") { (name, lo, hi) ->
            "AC D\nAN $name\nAL $lo\nAH $hi\nV X=${dms(sim.startLat)} N ${dms(sim.startLon)} E\nDC $radiusNm"
        }
        val parsed = OpenAir.parse(StringReader(text))
        assertEquals(parsed.warnings.toString(), z.size, parsed.airspaces.size)
        return parsed.airspaces
    }

    private fun ft(m: Double) = "${(m / Atmosphere.FT).toInt()}ft AMSL"

    @Test fun inAZoneUnderAnAreaWarnsOnceForEachRealChange() {
        // Launch inside a zone (A) whose top is 700 m above launch, under an area (B) starting
        // there: Queenstown in miniature. The sim climbs 1000 m, through A's top into B.
        val heard = ArrayList<Pair<String, AlertLevel>>()
        var warnedBelowFloorM = Double.NaN
        val ceilingLevels = ArrayList<CeilingLevel>()
        var enteredBAtS = Double.NaN
        val notes = ArrayList<String>()
        fly(setup = { c, sim ->
            val top = ft(sim.groundElevM + 700)
            c.setAirspace(AirspaceIndex(zones(sim, Triple("ZONE A", "GND", top), Triple("AREA B", top, "FL195"))))
            c.onAlert = { e ->
                heard.add(e.status.airspace.name to e.status.level)
                if (e.status.airspace.name == "AREA B" && e.status.level == AlertLevel.APPROACHING) warnedBelowFloorM = e.status.verticalM
            }
        }, eachFix = { c, sim ->
            if (ceilingLevels.lastOrNull() != c.ceilingLevel) ceilingLevels.add(c.ceilingLevel)
            if (enteredBAtS.isNaN() && c.statuses.any { it.airspace.name == "AREA B" && it.level == AlertLevel.INSIDE }) enteredBAtS = sim.timeS
            c.info?.let { n -> "in ${n.entered.map { it.name }} left ${n.left.map { it.name }}".let { if (notes.lastOrNull() != it) notes.add(it) } }
        })
        // Inside A from the start: that is the first thing said.
        assertEquals(heard.toString(), "ZONE A" to AlertLevel.INSIDE, heard.first())
        // Climbing to A's top is announced as approaching B (one boundary, one warning), before
        // reaching it, and then B itself.
        val i = heard.indexOf("AREA B" to AlertLevel.APPROACHING)
        assertTrue(heard.toString(), i > 0 && heard.indexOf("AREA B" to AlertLevel.INSIDE) > i)
        assertTrue("warned $warnedBelowFloorM m below the floor", warnedBelowFloorM > 20)
        assertTrue(heard.toString(), heard.none { it.first == "ZONE A" && it.second == AlertLevel.CEILING })
        // Leaving A upwards never sounds like approaching A.
        assertTrue(heard.toString(), heard.none { it == "ZONE A" to AlertLevel.APPROACHING })
        // Nothing is said twice in a row about the same thing, and not much is said at all.
        assertTrue(heard.toString(), heard.zipWithNext().none { (x, y) -> x == y })
        assertTrue(heard.toString(), heard.size <= 6)
        // The altitude display went amber, then red, on the way up.
        assertTrue(ceilingLevels.toString(), ceilingLevels.indexOf(CeilingLevel.AMBER) in 0 until ceilingLevels.indexOf(CeilingLevel.RED))
        assertTrue(!enteredBAtS.isNaN())
        // Gliding back down out of B into A is not an alert, but it is shown.
        assertTrue(notes.toString(), notes.contains("in [ZONE A] left [AREA B]"))
    }

    @Test fun ceilingOfTheZoneWarnsEarlierWithoutABarometer() {
        // Only a zone, top 700 m above launch: its top is the limit.
        fun firstCeilingWarningBelowTop(baro: Boolean): Double {
            var below = Double.NaN
            val r = fly(baro = baro, setup = { c, sim ->
                c.setAirspace(AirspaceIndex(zones(sim, Triple("ZONE A", "GND", ft(sim.groundElevM + 700)))))
                c.onAlert = { e -> if (below.isNaN() && e.status.level == AlertLevel.CEILING) below = e.status.verticalM }
            })
            assertTrue(r.computer.positionError.mslM > 0)
            return below
        }
        val withBaro = firstCeilingWarningBelowTop(true)
        val gpsOnly = firstCeilingWarningBelowTop(false)
        assertTrue("with barometer: $withBaro m below the top", withBaro > 20)
        assertTrue("GPS only $gpsOnly m vs barometer $withBaro m", gpsOnly > withBaro)
    }

    private fun dms(deg: Double): String {
        val d = deg.toInt()
        val m = ((deg - d) * 60).toInt()
        val s = ((deg - d) * 60 - m) * 60
        return "%d:%02d:%05.2f".format(d, m, s)
    }
}
