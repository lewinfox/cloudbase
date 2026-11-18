package app.cloudbase.core.flight

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FlightDetectorTest {
    private val nan = Double.NaN

    /** Feeds one fix per second from [from] to [to] inclusive; returns (second, event) for every event. */
    private fun FlightDetector.feed(
        from: Int,
        to: Int,
        speed: Double,
        vario: Double = 0.0,
        agl: (Int) -> Double = { nan },
    ): List<Pair<Int, FlightEvent>> {
        val events = ArrayList<Pair<Int, FlightEvent>>()
        for (s in from..to) update(s * 1000L, speed, vario, agl(s))?.let { events.add(s to it) }
        return events
    }

    private fun flying(): FlightDetector = FlightDetector().also { it.force(FlightState.FLYING, 0L) }

    @Test
    fun carDriveWithTerrainKnownNeverStartsFlight() {
        val d = FlightDetector()
        assertEquals(emptyList<Any>(), d.feed(0, 600, speed = 15.0, agl = { 2.0 }))
        assertEquals(FlightState.GROUND, d.state)
        // even a bridge or cutting: 14 m is still below the 15 m limit
        assertEquals(emptyList<Any>(), d.feed(601, 700, speed = 15.0, agl = { 14.0 }))
    }

    @Test
    fun hikingUphillWithTerrainKnownNeverStartsFlight() {
        val d = FlightDetector()
        assertEquals(emptyList<Any>(), d.feed(0, 600, speed = 1.2, vario = 0.4, agl = { 0.0 }))
    }

    @Test
    fun normalLaunchReportsTakeoffAtStartOfRun() {
        val d = FlightDetector()
        assertEquals(emptyList<Any>(), d.feed(0, 9, speed = 0.3, agl = { 0.0 })) // standing, wing up
        // run from t=10 s, lift-off at 14 s, agl 5 / 10 / 15 at 14 / 15 / 16 s
        val events = d.feed(10, 30, speed = 3.5, agl = { s -> maxOf(0, (s - 13) * 5).toDouble() })
        assertEquals(listOf(16 to FlightEvent.TAKEOFF), events)
        assertEquals(FlightState.FLYING, d.state)
        assertEquals(10_000L, d.takeoffTimeMs)
    }

    @Test
    fun takeoffNeedsThreeSecondsOfSpeedEvenWhenHigh() {
        val d = FlightDetector()
        // agl is already 20 m (e.g. stepping off a ledge) but speed only starts now
        val events = d.feed(0, 10, speed = 3.0, agl = { 20.0 })
        assertEquals(listOf(3 to FlightEvent.TAKEOFF), events)
        assertEquals(0L, d.takeoffTimeMs)
    }

    @Test
    fun terrainUnknownLaunchBySpeed() {
        val d = FlightDetector()
        assertEquals(emptyList<Any>(), d.feed(0, 9, speed = 0.5))
        val events = d.feed(10, 30, speed = 5.0)
        assertEquals(listOf(15 to FlightEvent.TAKEOFF), events)
        assertEquals(10_000L, d.takeoffTimeMs)
    }

    @Test
    fun terrainUnknownLaunchByClimb() {
        val d = FlightDetector()
        val events = d.feed(0, 20, speed = 2.0, vario = -2.0) // sinking counts too
        assertEquals(listOf(5 to FlightEvent.TAKEOFF), events)
        assertEquals(0L, d.takeoffTimeMs)
    }

    @Test
    fun terrainUnknownBelowThresholdsDoesNothing() {
        val d = FlightDetector()
        assertEquals(emptyList<Any>(), d.feed(0, 120, speed = 3.9)) // walking pace
        assertEquals(emptyList<Any>(), d.feed(121, 240, speed = 1.0, vario = 3.0)) // elevator, no movement
        assertEquals(emptyList<Any>(), d.feed(241, 360, speed = 2.0, vario = 1.4))
    }

    @Test
    fun terrainRunDoesNotCarryOverWhenTerrainIsLost() {
        val d = FlightDetector()
        // 10 minutes of car at 20 m/s with terrain known, then the terrain database drops out
        assertEquals(emptyList<Any>(), d.feed(0, 600, speed = 20.0, agl = { 1.0 }))
        val events = d.feed(601, 610, speed = 20.0)
        assertEquals(listOf(606 to FlightEvent.TAKEOFF), events) // needs its own 5 s, starts at 601
        assertEquals(601_000L, d.takeoffTimeMs)
    }

    @Test
    fun topLandingThenImmediateRelaunch() {
        val d = flying()
        assertEquals(emptyList<Any>(), d.feed(1, 99, speed = 8.0, vario = 0.5, agl = { 120.0 }))
        // flare onto a hilltop and stand still, wing on the ground
        val landing = d.feed(100, 125, speed = 0.2, vario = 0.1, agl = { 3.0 })
        assertEquals(listOf(120 to FlightEvent.LANDING), landing)
        assertEquals(100_000L, d.landingTimeMs)
        assertEquals(FlightState.GROUND, d.state)
        // pick the wing up and go again
        val relaunch = d.feed(126, 160, speed = 4.0, agl = { s -> maxOf(0, (s - 130) * 6).toDouble() })
        assertEquals(listOf(133 to FlightEvent.TAKEOFF), relaunch)
        assertEquals(126_000L, d.takeoffTimeMs)
        assertEquals(100_000L, d.landingTimeMs)
    }

    @Test
    fun parkedInWindHighAboveTerrainDoesNotLand() {
        val d = flying()
        assertEquals(emptyList<Any>(), d.feed(1, 60, speed = 0.0, vario = 0.0, agl = { 300.0 }))
        assertEquals(FlightState.FLYING, d.state)
        // ... and 49 m is still low enough once the glider is down
        assertEquals(listOf(81 to FlightEvent.LANDING), d.feed(61, 81, speed = 0.0, agl = { 49.0 }))
    }

    @Test
    fun parkedInWindWithTerrainUnknownNeedsFortySeconds() {
        val d = flying()
        assertEquals(emptyList<Any>(), d.feed(10, 49, speed = 0.0)) // 39 s of stillness
        assertEquals(listOf(50 to FlightEvent.LANDING), d.feed(50, 50, speed = 0.0))
        assertEquals(10_000L, d.landingTimeMs)
    }

    @Test
    fun landingNeedsStillVarioToo() {
        val d = flying()
        assertEquals(emptyList<Any>(), d.feed(1, 100, speed = 0.0, vario = 0.8, agl = { 5.0 })) // wing still rising
        assertEquals(listOf(121 to FlightEvent.LANDING), d.feed(101, 130, speed = 1.4, vario = nan, agl = { 5.0 }))
        assertEquals(101_000L, d.landingTimeMs)
    }

    @Test
    fun missingSpeedNeverCountsAsStill() {
        val d = flying()
        assertEquals(emptyList<Any>(), d.feed(1, 100, speed = nan, agl = { 2.0 }))
    }

    @Test
    fun gapRestartsTakeoffRun() {
        val d = FlightDetector()
        assertEquals(emptyList<Any>(), d.feed(0, 3, speed = 5.0)) // 3 s of run so far
        // tunnel: no fixes for 17 s; the run must not be stitched together
        assertEquals(emptyList<Any>(), d.feed(20, 24, speed = 5.0))
        assertEquals(listOf(25 to FlightEvent.TAKEOFF), d.feed(25, 25, speed = 5.0))
        assertEquals(20_000L, d.takeoffTimeMs)
    }

    @Test
    fun gapExactlyTenSecondsIsTolerated() {
        val d = FlightDetector()
        d.feed(0, 0, speed = 5.0)
        assertEquals(listOf(10 to FlightEvent.TAKEOFF), d.feed(10, 10, speed = 5.0))
        assertEquals(0L, d.takeoffTimeMs)
    }

    @Test
    fun gapRestartsLandingRun() {
        val d = flying()
        assertEquals(emptyList<Any>(), d.feed(100, 115, speed = 0.0, agl = { 2.0 }))
        assertEquals(emptyList<Any>(), d.feed(130, 148, speed = 0.0, agl = { 2.0 }))
        assertEquals(listOf(150 to FlightEvent.LANDING), d.feed(149, 150, speed = 0.0, agl = { 2.0 }))
        assertEquals(130_000L, d.landingTimeMs)
    }

    @Test
    fun timeGoingBackwardsDropsRuns() {
        val d = FlightDetector()
        d.feed(100, 104, speed = 5.0)
        assertEquals(emptyList<Any>(), d.feed(50, 54, speed = 5.0)) // clock jump: 4 s, not 9
        assertEquals(listOf(55 to FlightEvent.TAKEOFF), d.feed(55, 55, speed = 5.0))
        assertEquals(50_000L, d.takeoffTimeMs)
    }

    @Test
    fun outlierInsideTakeoffRunOnlyDelaysIt() {
        val d = FlightDetector()
        d.feed(10, 11, speed = 5.0)
        d.feed(12, 12, speed = 0.0) // one bad fix
        val events = d.feed(13, 30, speed = 5.0)
        assertEquals(listOf(18 to FlightEvent.TAKEOFF), events)
        assertEquals(13_000L, d.takeoffTimeMs)
    }

    @Test
    fun singleSpeedSpikeDoesNotTakeOff() {
        val d = FlightDetector()
        assertEquals(emptyList<Any>(), d.feed(0, 30, speed = 0.0, agl = { 0.0 }))
        assertNull(d.update(31_000L, 40.0, 0.0, 30.0)) // multipath glitch
        assertEquals(emptyList<Any>(), d.feed(32, 120, speed = 0.0, agl = { 0.0 }))
        // same with terrain unknown, the glitch also has a vario spike
        assertNull(d.update(121_000L, 40.0, 9.0, nan))
        assertEquals(emptyList<Any>(), d.feed(122, 220, speed = 0.0))
        assertEquals(FlightState.GROUND, d.state)
    }

    @Test
    fun singleStillFixDoesNotLandInFlight() {
        val d = flying()
        assertEquals(emptyList<Any>(), d.feed(1, 30, speed = 9.0, agl = { 8.0 }))
        assertNull(d.update(31_000L, 0.0, 0.0, 8.0)) // one bogus zero-speed fix
        assertEquals(emptyList<Any>(), d.feed(32, 200, speed = 9.0, agl = { 8.0 }))
        assertEquals(FlightState.FLYING, d.state)
    }

    @Test
    fun aBriefBreakInTheStillnessIsForgiven() {
        val d = flying()
        d.feed(100, 110, speed = 0.0, agl = { 1.0 })
        d.update(111_000L, 3.0, 0.0, 1.0) // one stride while bunching up the wing
        // The 20 s still count carries on from 100 s rather than starting again.
        assertEquals(listOf(120 to FlightEvent.LANDING), d.feed(112, 140, speed = 0.0, agl = { 1.0 }))
        assertEquals(100_000L, d.landingTimeMs)
    }

    @Test
    fun aLongerBreakRestartsTheClock() {
        val d = flying()
        d.feed(100, 110, speed = 0.0, agl = { 1.0 })
        // Five seconds on the move: that was not a landing.
        assertEquals(emptyList<Pair<Int, FlightEvent>>(), d.feed(111, 115, speed = 6.0, agl = { 1.0 }))
        assertEquals(listOf(136 to FlightEvent.LANDING), d.feed(116, 140, speed = 0.0, agl = { 1.0 }))
        assertEquals(116_000L, d.landingTimeMs)
    }

    @Test
    fun forceSetsStateAndTimesAndClearsRuns() {
        val d = FlightDetector()
        d.feed(0, 3, speed = 5.0) // takeoff run 3 s in
        d.force(FlightState.FLYING, 1_234L)
        assertEquals(FlightState.FLYING, d.state)
        assertEquals(1_234L, d.takeoffTimeMs)
        d.feed(4, 20, speed = 0.0, agl = { 1.0 })
        // still run of 16 s must not carry over a forced landing, nor the old run a forced takeoff
        d.force(FlightState.GROUND, 20_500L)
        assertEquals(FlightState.GROUND, d.state)
        assertEquals(20_500L, d.landingTimeMs)
        assertEquals(1_234L, d.takeoffTimeMs)
        // the 3 s run from before the force is gone: a fresh run from 21 s needs until 26 s
        assertEquals(emptyList<Any>(), d.feed(21, 25, speed = 5.0))
        assertEquals(listOf(26 to FlightEvent.TAKEOFF), d.feed(26, 26, speed = 5.0))
        assertEquals(21_000L, d.takeoffTimeMs)
    }

    @Test
    fun forceFlyingThenNormalLanding() {
        val d = FlightDetector()
        d.force(FlightState.FLYING, 5_000L)
        assertEquals(listOf(25 to FlightEvent.LANDING), d.feed(5, 65, speed = 0.0, agl = { 1.0 }))
        assertEquals(5_000L, d.landingTimeMs)
    }

    @Test
    fun resetReturnsToInitialState() {
        val d = FlightDetector()
        d.feed(0, 10, speed = 5.0)
        assertEquals(FlightState.FLYING, d.state)
        d.reset()
        assertEquals(FlightState.GROUND, d.state)
        assertEquals(0L, d.takeoffTimeMs)
        assertEquals(0L, d.landingTimeMs)
    }
}
