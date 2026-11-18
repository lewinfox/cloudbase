package app.cloudbase.core

import app.cloudbase.core.airspace.AirspaceIndex
import app.cloudbase.core.airspace.OpenAir
import app.cloudbase.core.flight.FlightEvent
import app.cloudbase.core.igc.IgcSummary
import app.cloudbase.core.igc.IgcTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.StringReader
import kotlin.math.abs

/**
 * A real flight (Coronet Peak, 42 minutes, 1 Hz, GPS + barometric vario, no accelerometer)
 * replayed through the whole pipeline. This is the only test against real flying; everything
 * else uses the simulator.
 */
class RealFlightTest {
    private val file = File("../app/src/main/assets/demo_flight.igc")
    private fun track() = file.bufferedReader().use { IgcTrack.read(it) }!!

    private class Out(val events: List<Pair<FlightEvent, Int>>, val log: String, val computer: FlightComputer)

    /** Seconds are counted from the first fix. */
    private fun replay(t: IgcTrack, setup: (FlightComputer) -> Unit = {}, each: (FlightComputer, Int) -> Unit = { _, _ -> }): Out {
        val c = FlightComputer { _, _ -> Double.NaN }
        val log = StringBuilder()
        c.logSink = object : FlightLogSink {
            override fun open(firstFixMs: Long): Appendable = log
            override fun flush() {}
            override fun close() {}
        }
        val events = ArrayList<Pair<FlightEvent, Int>>()
        var now = 0
        c.onFlightEvent = { events.add(it to now) }
        setup(c)
        for (i in 0 until t.size) {
            now = ((t.timeMs(i) - t.timeMs(0)) / 1000).toInt()
            if (t.hasPressure) c.onPressure(t.timeMs(i) * 1_000_000, Atmosphere.pressureAt(t.pressureAlt(i)))
            c.onFix(t.fix(i))
            each(c, now)
        }
        return Out(events, log.toString(), c)
    }

    @Test fun readsTheFile() {
        val t = track()
        assertEquals(2539, t.size)
        assertTrue(t.hasPressure)
        val f = t.fix(100)
        assertEquals(-44.93, f.lat, 0.02)
        assertEquals(168.76, f.lon, 0.03)
        // Speed and track come from the file's GSP and CCO fields.
        assertTrue(f.speed in 3.0..15.0)
    }

    @Test fun detectsTheRealTakeoffAndLanding() {
        // By eye from the file: moving from about 20 s, down at about 2470 s and then on foot at up
        // to 2.5 m/s. No terrain data here, so landing needs 40 s at walking pace or less.
        val r = replay(track())
        println("Real flight events: " + r.events)
        assertEquals(listOf(FlightEvent.TAKEOFF, FlightEvent.LANDING), r.events.map { it.first })
        assertTrue("takeoff detected at ${r.events[0].second} s", r.events[0].second in 15..40)
        assertTrue("landing detected at ${r.events[1].second} s", r.events[1].second in 2495..2520)
        val s = IgcSummary.read(StringReader(r.log))!!
        println("Re-recorded log: ${s.durationS} s, ${s.fixCount} fixes, max ${s.maxAltM} m, min ${s.minAltM} m")
        assertTrue(abs(s.durationS - 2470) < 60)
        assertEquals(1404.0, s.maxAltM.toDouble(), 5.0)
    }

    @Test fun instrumentsStaySane() {
        var maxClimb = -99.0; var minClimb = 99.0
        var worstAlt = 0.0
        val t = track()
        var circlingS = 0
        val wind = StringBuilder()
        var cores = 0
        replay(t, each = { c, now ->
            if (c.flightState.name != "FLYING") return@replay
            if (now > 60) { maxClimb = maxOf(maxClimb, c.climb); minClimb = minOf(minClimb, c.climb) }
            if (now > 60) worstAlt = maxOf(worstAlt, abs(c.altMsl - c.fix!!.altMsl))
            if (c.circling) circlingS++
            if (c.thermalCore != null) cores++
            if (now % 240 == 0) c.windEstimate.let { w ->
                wind.append(if (w == null) "\n  %4d s  no estimate".format(now) else "\n  %4d s  from %3.0f° at %4.1f m/s, airspeed %4.1f, quality %.2f, %s".format(now, w.wind.fromDeg, w.wind.speed, w.airspeed, w.quality, w.method))
            }
        })
        println("Real flight: climb range %.1f..%.1f m/s, fused vs GPS altitude worst %.1f m, circling %d s, core shown %d s".format(minClimb, maxClimb, worstAlt, circlingS, cores))
        println("Wind over the flight:$wind")
        // A paraglider on a 500-1400 m flight: these would catch a filter that rings or runs away.
        assertTrue("max climb $maxClimb", maxClimb in 1.0..8.0)
        assertTrue("max sink $minClimb", minClimb in -8.0..-1.0)
        assertTrue("altitude drifted $worstAlt m from GPS", worstAlt < 25.0)
    }

    @Test fun airspaceAlongTheRealTrack() {
        val samples = File(System.getProperty("airspaceSamples").orEmpty(), "nz_asp_v2.txt")
        assumeTrue(samples.exists())
        val all = samples.bufferedReader().use { OpenAir.parse(it) }.airspaces
        val seen = LinkedHashMap<String, String>()
        var alerts = 0
        val r = replay(track(), setup = { c -> c.setAirspace(AirspaceIndex(all)); c.onAlert = { alerts++ } }, each = { c, now ->
            for (s in c.statuses) seen.putIfAbsent("${s.level} ${s.airspace.name} (${s.airspace.lower.text}–${s.airspace.upper.text})", "first at $now s, alt ${c.altMsl.toInt()} m")
        })
        println("Airspace on the real flight ($alerts alert sounds):")
        // Launched inside the Queenstown control zone and stayed in it for 42 minutes: announced,
        // repeated three times, then quiet.
        assertTrue("$alerts alert sounds", alerts <= 6)
        seen.forEach { (k, v) -> println("  $k  $v") }
        assertNotNull(r.computer.fix)
    }
}
