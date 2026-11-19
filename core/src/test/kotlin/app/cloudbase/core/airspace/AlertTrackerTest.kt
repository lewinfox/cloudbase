package app.cloudbase.core.airspace

import app.cloudbase.core.airspace.TestAirspaces.box
import app.cloudbase.core.airspace.TestAirspaces.gnd
import app.cloudbase.core.airspace.TestAirspaces.msl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class AlertTrackerTest {
    private fun asp(name: String) = box(name, "D", "CTR", gnd, msl(1000.0), 0.0, 0.0, 1000.0, 1000.0)
    private val a = asp("a")
    private val b = asp("b")

    private val inA = st(a, AlertLevel.INSIDE)
    private val nearA = st(a, AlertLevel.APPROACHING, 0.0)
    private val inB = st(b, AlertLevel.INSIDE)
    private val nearB = st(b, AlertLevel.APPROACHING, 30.0)

    private fun st(x: Airspace, level: AlertLevel, timeS: Double = Double.NaN) =
        AirspaceStatus(x, level, VerticalRelation.WITHIN, 0.0, 0.0, timeS)

    private fun AlertTracker.up(nowS: Int, vararg s: AirspaceStatus) = update(s.toList(), nowS * 1000L)?.status

    /** Feeds the same statuses once a second over [from, to) and returns everything announced. */
    private fun AlertTracker.hold(from: Int, to: Int, vararg s: AirspaceStatus) =
        (from until to).mapNotNull { up(it, *s) }

    @Test
    fun insideAtStartIsAnnouncedOnceAndNeverRepeated() {
        val t = AlertTracker()
        assertSame(inA, t.up(0, inA))
        assertEquals(emptyList<AirspaceStatus>(), t.hold(1, 600, inA))
        assertSame(inA, t.banner(listOf(inA)))
    }

    @Test
    fun eachStepUpIsAnnounced() {
        val t = AlertTracker()
        assertSame(nearB, t.up(0, nearB))
        assertNull(t.up(1, nearB))
        val ceiling = st(b, AlertLevel.CEILING, 20.0)
        assertSame(inB, t.up(2, inB))
        assertSame(ceiling, t.up(3, ceiling))
    }

    @Test
    fun crossingFromAIntoBDoesNotAnnounceApproachingA() {
        val t = AlertTracker()
        t.up(0, inA)
        t.up(1, inA, nearB)
        assertNull(t.up(2, inA, nearB))
        // Over the boundary: B is new, A is now "approaching" by distance.
        assertSame(inB, t.up(3, inB, nearA))
        assertEquals(emptyList<AirspaceStatus>(), t.hold(4, 400, inB, nearA))
        // And the banner never shows "approaching A", even once A's memory lapses.
        assertSame(inB, t.banner(listOf(inB, nearA)))
        t.dismiss(inB)
        assertNull(t.banner(listOf(inB, nearA)))
    }

    @Test
    fun weavingAlongASharedBoundaryIsQuiet() {
        val t = AlertTracker()
        t.up(0, inA, nearB)
        t.up(1, inA, nearB)
        t.up(2, inB, nearA)
        // Back and forth every 20 s for ten minutes.
        val heard = (3 until 600).mapNotNull { s -> if (s / 20 % 2 == 0) t.up(s, inA, nearB) else t.up(s, inB, nearA) }
        assertEquals(emptyList<AirspaceStatus>(), heard)
    }

    @Test
    fun goingBackInAfterBeingClearForLongEnoughIsNews() {
        val t = AlertTracker()
        t.up(0, inA)
        t.hold(1, 60, nearA)
        assertNull(t.up(60, inA)) // within two minutes of leaving: same event
        t.hold(61, 200)           // clear of it entirely for over two minutes
        assertSame(inA, t.up(200, inA))
    }

    @Test
    fun aZoneYouAreAboveOrBelowIsNotForgotten() {
        // Climbed out of A's top into B and stayed up there for ten minutes, over A all the while.
        val t = AlertTracker()
        t.up(0, inA)
        (1 until 600).forEach { t.update(listOf(inB), it * 1000L, overhead = { true }) }
        // Gliding back down into A: approaching it, then in it, and neither is news.
        assertNull(t.update(listOf(nearA, inB), 600_000L, overhead = { true })?.status)
        assertNull(t.update(listOf(inA), 601_000L, overhead = { true })?.status)
    }

    @Test
    fun theCeilingCanWarnAgainSoonerThanOtherLevels() {
        val t = AlertTracker()
        val top = st(a, AlertLevel.CEILING, 0.0)
        t.up(0, inA)
        assertSame(top, t.up(1, top))
        t.hold(2, 20, inA)
        assertNull(t.up(20, top)) // back up within 30 s: GPS wander, not news
        t.hold(21, 60, inA)
        assertSame(top, t.up(60, top)) // the next thermal, a while later
    }

    @Test
    fun dismissedStaysHiddenUntilItGetsWorse() {
        val t = AlertTracker()
        t.up(0, nearA)
        t.dismiss(nearA)
        assertNull(t.banner(listOf(nearA)))
        assertSame(inA, t.up(1, inA))
        assertSame(inA, t.banner(listOf(inA)))
        t.dismiss(inA)
        assertNull(t.banner(listOf(inA)))
    }

    @Test
    fun dismissingOneShowsTheNext() {
        val t = AlertTracker()
        t.up(0, inA, nearB)
        t.up(1, inA, nearB)
        assertSame(inA, t.banner(listOf(inA, nearB)))
        t.dismiss(inA)
        assertSame(nearB, t.banner(listOf(inA, nearB)))
    }

    @Test
    fun worstRiseFirstThenTheRest() {
        val t = AlertTracker()
        assertSame(inA, t.up(0, inA, nearB))
        assertSame(nearB, t.up(1, inA, nearB))
        assertNull(t.up(2, inA, nearB))
    }

    /** As [up], with B stacked over A: whenever we are not in B we are under its floor. */
    private fun AlertTracker.stacked(nowS: Int, vararg s: AirspaceStatus) =
        update(s.toList(), nowS * 1000L, overhead = { true }, belowFloor = { it === b && s.none { x -> x.airspace === b && x.level >= AlertLevel.INSIDE } })?.status

    @Test
    fun droppingThroughAFloorIntoAnotherZoneLeavesANote() {
        val t = AlertTracker()
        t.stacked(0, inA)
        assertSame(inB, t.stacked(1, inB))
        assertNull("B was announced", t.info)
        // Down through B's floor back into A: not an alert, but noted.
        assertNull(t.stacked(2, inA))
        assertEquals(listOf(a), t.info!!.entered)
        assertEquals(listOf(b), t.info!!.left)
        assertEquals(2000L, t.info!!.atMs)
        t.dismissInfo()
        assertNull(t.info)
    }

    @Test
    fun sidewaysCrossingsAndLeavingAirspaceLeaveNoNote() {
        val t = AlertTracker()
        t.up(0, inA)
        t.up(1, inA, nearB)
        t.up(2, inB, nearA)
        t.up(3, inA, nearB) // back into A sideways, within its memory
        assertNull(t.info)
        t.up(4)             // out of everything
        assertNull(t.info)
    }

    @Test
    fun noNoteForAnEntryThatIsAboutToBeAnnounced() {
        val t = AlertTracker()
        t.up(0, inA, inB)
        assertNull(t.info)
        assertSame(inB, t.up(1, inA, inB))
        assertNull(t.info)
    }

    @Test
    fun ignoringTheZoneYouAreInIsNotLeavingIt() {
        val t = AlertTracker()
        t.stacked(0, inA)
        t.stacked(1, inB)
        t.airspaceChanged()
        t.stacked(2, inA) // B now ignored, so absent: not a drop through its floor
        assertNull(t.info)
    }

    @Test
    fun resetForgetsEverything() {
        val t = AlertTracker()
        t.up(0, inA)
        t.reset()
        assertSame(inA, t.up(1, inA))
    }
}
