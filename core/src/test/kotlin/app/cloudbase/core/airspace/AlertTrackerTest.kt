package app.cloudbase.core.airspace

import app.cloudbase.core.airspace.TestAirspaces.box
import app.cloudbase.core.airspace.TestAirspaces.gnd
import app.cloudbase.core.airspace.TestAirspaces.msl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertTrackerTest {
    private fun asp(name: String) = box(name, "D", "CTR", gnd, msl(1000.0), 0.0, 0.0, 1000.0, 1000.0)
    private val a = asp("a")
    private val b = asp("b")
    private val c = asp("c")

    private fun st(x: Airspace, level: AlertLevel, timeS: Double = Double.NaN, horizontalM: Double = 0.0) =
        AirspaceStatus(x, level, VerticalRelation.WITHIN, horizontalM, 0.0, timeS)

    private fun AlertTracker.up(nowMs: Long, vararg s: AirspaceStatus) = update(s.toList(), nowMs)

    @Test
    fun nothingToReportGivesNull() {
        val t = AlertTracker()
        assertNull(t.update(emptyList(), 0))
        assertNull(t.up(1000, st(a, AlertLevel.NONE)))
    }

    @Test
    fun nearAnnouncesOnceAndNeverRepeats() {
        val t = AlertTracker()
        val e = t.up(0, st(a, AlertLevel.NEAR))
        assertNotNull(e)
        assertSame(a, e!!.status.airspace)
        assertEquals(AlertLevel.NEAR, e.status.level)
        assertFalse(e.repeat)
        for (ms in listOf(1000L, 15_000L, 30_000L, 60_000L, 600_000L)) assertNull("t=$ms", t.up(ms, st(a, AlertLevel.NEAR)))
    }

    @Test
    fun escalationFiresAtEachHigherLevel() {
        val t = AlertTracker()
        assertEquals(AlertLevel.NEAR, t.up(0, st(a, AlertLevel.NEAR))!!.status.level)
        val imminent = t.up(1000, st(a, AlertLevel.IMMINENT, 30.0))!!
        assertEquals(AlertLevel.IMMINENT, imminent.status.level)
        assertFalse(imminent.repeat)
        val inside = t.up(2000, st(a, AlertLevel.INSIDE))!!
        assertEquals(AlertLevel.INSIDE, inside.status.level)
        assertFalse(inside.repeat)
        // Skipping straight to INSIDE from nothing also fires.
        val t2 = AlertTracker()
        assertEquals(AlertLevel.INSIDE, t2.up(0, st(b, AlertLevel.INSIDE))!!.status.level)
    }

    @Test
    fun insideRepeatsEveryThirtySeconds() {
        val t = AlertTracker()
        assertNotNull(t.up(0, st(a, AlertLevel.INSIDE)))
        assertNull(t.up(1_000, st(a, AlertLevel.INSIDE)))
        assertNull(t.up(29_999, st(a, AlertLevel.INSIDE)))
        val r = t.up(30_000, st(a, AlertLevel.INSIDE))
        assertNotNull(r)
        assertTrue(r!!.repeat)
        assertNull(t.up(59_999, st(a, AlertLevel.INSIDE)))
        assertTrue(t.up(60_000, st(a, AlertLevel.INSIDE))!!.repeat)
    }

    @Test
    fun imminentRepeatsEveryFifteenSeconds() {
        val t = AlertTracker()
        assertNotNull(t.up(0, st(a, AlertLevel.IMMINENT, 20.0)))
        assertNull(t.up(14_999, st(a, AlertLevel.IMMINENT, 20.0)))
        assertTrue(t.up(15_000, st(a, AlertLevel.IMMINENT, 20.0))!!.repeat)
        assertNull(t.up(29_999, st(a, AlertLevel.IMMINENT, 20.0)))
        assertTrue(t.up(30_000, st(a, AlertLevel.IMMINENT, 20.0))!!.repeat)
    }

    @Test
    fun customIntervals() {
        val t = AlertTracker(repeatInsideMs = 1000, repeatImminentMs = 500, clearAfterMs = 200)
        assertNotNull(t.up(0, st(a, AlertLevel.INSIDE)))
        assertNull(t.up(999, st(a, AlertLevel.INSIDE)))
        assertNotNull(t.up(1000, st(a, AlertLevel.INSIDE)))
        // Absent for 200 ms is enough to forget.
        assertNull(t.update(emptyList(), 1100))
        assertNull(t.update(emptyList(), 1200))
        assertNotNull(t.up(1300, st(a, AlertLevel.INSIDE)))
    }

    @Test
    fun briefDropoutsDoNotReannounce() {
        val t = AlertTracker()
        assertNotNull(t.up(0, st(a, AlertLevel.INSIDE)))
        for (s in 1..8) assertNull(t.update(emptyList(), s * 1000L))
        // Back at 9.5 s, under the 10 s clear time: silent (not due a repeat either).
        assertNull(t.up(9_500, st(a, AlertLevel.INSIDE)))
        // Gone long enough (last seen 9.5 s, forgotten at 19.5 s).
        for (s in 10..19) assertNull(t.update(emptyList(), 9_500 + (s - 9) * 1000L))
        val e = t.up(20_500, st(a, AlertLevel.INSIDE))
        assertNotNull(e)
        assertFalse(e!!.repeat)
    }

    @Test
    fun flyingAlongABoundaryDoesNotChatter() {
        // NEAR <-> INSIDE flicker every second for two minutes: exactly one announcement (INSIDE at the start).
        val t = AlertTracker()
        var events = 0
        for (s in 0 until 120) {
            val level = if (s % 2 == 0) AlertLevel.INSIDE else AlertLevel.NEAR
            val e = t.up(s * 1000L, st(a, level))
            if (e != null) {
                events++
                if (s != 0) assertTrue("only repeats after the start, at t=$s", e.repeat)
            }
        }
        // INSIDE keeps being refreshed, so the only extra events are the 30 s repeats: at 30, 60, 90 s.
        assertEquals(4, events)
        // NEAR <-> NONE flicker: once.
        val t2 = AlertTracker()
        var nearEvents = 0
        for (s in 0 until 120) {
            val e = if (s % 3 == 0) t2.up(s * 1000L, st(a, AlertLevel.NEAR)) else t2.update(emptyList(), s * 1000L)
            if (e != null) nearEvents++
        }
        assertEquals(1, nearEvents)
    }

    @Test
    fun levelLoweredOnlyAfterClearTimeThenHigherFiresAgain() {
        val t = AlertTracker()
        assertEquals(AlertLevel.INSIDE, t.up(0, st(a, AlertLevel.INSIDE))!!.status.level)
        // Drops to NEAR for 4 s then back to INSIDE: silent.
        for (s in 1..4) assertNull(t.up(s * 1000L, st(a, AlertLevel.NEAR)))
        assertNull(t.up(5_000, st(a, AlertLevel.INSIDE)))
        // Stays NEAR for 12 s: remembered level drops to NEAR silently.
        for (s in 6..17) assertNull("t=$s", t.up(s * 1000L, st(a, AlertLevel.NEAR)))
        // Now IMMINENT is new information.
        val e = t.up(18_000, st(a, AlertLevel.IMMINENT, 40.0))
        assertNotNull(e)
        assertEquals(AlertLevel.IMMINENT, e!!.status.level)
        assertFalse(e.repeat)
    }

    @Test
    fun lowerLevelAfterClearTimeDoesNotRepeatImmediately() {
        val t = AlertTracker()
        assertNotNull(t.up(0, st(a, AlertLevel.INSIDE)))
        // Hover at IMMINENT: below the announced INSIDE, so after 10 s the memory drops to IMMINENT...
        var events = 0
        for (s in 1..14) if (t.up(s * 1000L, st(a, AlertLevel.IMMINENT, 20.0)) != null) events++
        assertEquals(0, events)
        // ... and the IMMINENT repeat clock restarted when that happened (10 s), so 15 s later.
        assertNull(t.up(24_000, st(a, AlertLevel.IMMINENT, 20.0)))
        assertTrue(t.up(25_000, st(a, AlertLevel.IMMINENT, 20.0))!!.repeat)
    }

    @Test
    fun nearAnnouncedAgainAfterBeingAbsentLongEnough() {
        val t = AlertTracker()
        assertNotNull(t.up(0, st(a, AlertLevel.NEAR)))
        assertNull(t.update(emptyList(), 9_000))
        assertNull(t.up(9_500, st(a, AlertLevel.NEAR))) // not yet forgotten
        assertNull(t.update(emptyList(), 15_000))
        assertNull(t.update(emptyList(), 19_500)) // forgotten here (9.5 s + 10 s)
        assertNotNull(t.up(20_000, st(a, AlertLevel.NEAR)))
    }

    @Test
    fun severalAirspacesReturnWorstFirstThenTheRest() {
        val t = AlertTracker()
        val list = listOf(st(a, AlertLevel.NEAR, horizontalM = 100.0), st(b, AlertLevel.INSIDE), st(c, AlertLevel.IMMINENT, 30.0))
        val first = t.update(list, 0)!!
        assertSame(b, first.status.airspace)
        // Announcements the pilot has not heard yet stay pending and come out one per call.
        val second = t.update(list, 1000)!!
        assertSame(c, second.status.airspace)
        val third = t.update(list, 2000)!!
        assertSame(a, third.status.airspace)
        assertNull(t.update(list, 3000))
    }

    @Test
    fun imminentTiesGoToTheSoonestAndNearTiesToTheClosest() {
        val t = AlertTracker()
        val e = t.update(listOf(st(a, AlertLevel.IMMINENT, 40.0), st(b, AlertLevel.IMMINENT, 12.0)), 0)!!
        assertSame(b, e.status.airspace)
        val t2 = AlertTracker()
        val n = t2.update(listOf(st(a, AlertLevel.NEAR, horizontalM = 250.0), st(b, AlertLevel.NEAR, horizontalM = 90.0)), 0)!!
        assertSame(b, n.status.airspace)
    }

    @Test
    fun repeatOfAHigherLevelBeatsANewLowerOne() {
        val t = AlertTracker()
        assertNotNull(t.up(0, st(a, AlertLevel.INSIDE)))
        val e = t.up(30_000, st(a, AlertLevel.INSIDE), st(b, AlertLevel.NEAR))!!
        assertSame(a, e.status.airspace)
        assertTrue(e.repeat)
        assertSame(b, t.up(31_000, st(a, AlertLevel.INSIDE), st(b, AlertLevel.NEAR))!!.status.airspace)
    }

    @Test
    fun newRiseBeatsRepeatAtTheSameLevel() {
        val t = AlertTracker()
        assertNotNull(t.up(0, st(a, AlertLevel.INSIDE)))
        val e = t.up(30_000, st(a, AlertLevel.INSIDE), st(b, AlertLevel.INSIDE))!!
        assertSame(b, e.status.airspace)
        assertFalse(e.repeat)
    }

    @Test
    fun airspacesAreTrackedIndependently() {
        val t = AlertTracker()
        assertSame(a, t.up(0, st(a, AlertLevel.INSIDE))!!.status.airspace)
        assertNull(t.up(1000, st(a, AlertLevel.INSIDE)))
        // b is new: announced although a is already known.
        assertSame(b, t.up(2000, st(a, AlertLevel.INSIDE), st(b, AlertLevel.NEAR))!!.status.airspace)
        assertNull(t.up(3000, st(a, AlertLevel.INSIDE), st(b, AlertLevel.NEAR)))
    }

    @Test
    fun resetForgetsEverything() {
        val t = AlertTracker()
        assertNotNull(t.up(0, st(a, AlertLevel.INSIDE)))
        assertNull(t.up(1000, st(a, AlertLevel.INSIDE)))
        t.reset()
        val e = t.up(2000, st(a, AlertLevel.INSIDE))
        assertNotNull(e)
        assertFalse(e!!.repeat)
    }
}
