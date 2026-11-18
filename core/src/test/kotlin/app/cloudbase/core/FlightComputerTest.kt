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

    private fun dms(deg: Double): String {
        val d = deg.toInt()
        val m = ((deg - d) * 60).toInt()
        val s = ((deg - d) * 60 - m) * 60
        return "%d:%02d:%05.2f".format(d, m, s)
    }
}
