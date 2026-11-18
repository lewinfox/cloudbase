package app.cloudbase.core.flight

import kotlin.math.abs

enum class FlightState { GROUND, FLYING }

enum class FlightEvent { TAKEOFF, LANDING }

/**
 * Decides when a flight starts and ends, from ~1 Hz GPS fixes.
 *
 * A "run" is a streak of consecutive fixes that all meet a condition; its length is the time from
 * its first fix to the current one. One fix that fails the condition ends the run, and the next
 * qualifying fix starts a new one, so a single outlier can delay an event by a few seconds but can
 * neither cancel it for good nor fake it. If the gap between two fixes exceeds [GAP_MS] (or time
 * goes backwards) every run is dropped.
 *
 * TAKEOFF (only while GROUND), first that applies:
 *  - terrain known (agl not NaN): ground speed >= [TAKEOFF_SPEED] for [TAKEOFF_SUSTAIN_MS] and
 *    agl >= [TAKEOFF_MIN_AGL] now. A drive up to launch hugs the terrain and never gets 15 m up.
 *  - terrain unknown: ground speed >= [TAKEOFF_SPEED_NO_TERRAIN] for [TAKEOFF_SUSTAIN_NO_TERRAIN_MS],
 *    or |vario| >= [TAKEOFF_VARIO] with ground speed >= [TAKEOFF_VARIO_MIN_SPEED] for the same time.
 *    These runs only count fixes without terrain data. (A car at >= 4 m/s for 5 s therefore does
 *    start a log when there is no terrain data at all.)
 *  [takeoffTimeMs] is the first fix of the run that triggered it (the earliest, if several did),
 *  so it is when the pilot started running, not when the event fired.
 *
 * LANDING (only while FLYING): ground speed < [LAND_SPEED] and |vario| < [LAND_VARIO] (NaN vario
 * counts as 0; NaN speed never counts as still) and, if terrain is known, agl < [LAND_MAX_AGL].
 *  - for [LAND_SUSTAIN_MS] if every fix of the run knew the terrain,
 *  - otherwise for [LAND_SUSTAIN_NO_TERRAIN_MS].
 *  [landingTimeMs] is the first fix of the still run, when the glider came to rest.
 *
 * Both times are 0 until the first corresponding event (or [force]).
 */
class FlightDetector {
    var state: FlightState = FlightState.GROUND
        private set

    /** Time (ms) the current/last flight actually started: the first fix of the run that triggered takeoff. */
    var takeoffTimeMs: Long = 0L
        private set

    /** For a LANDING event: the time the aircraft actually came to rest (start of the still period). */
    var landingTimeMs: Long = 0L
        private set

    private class Run {
        var start = NONE
        /** Extends the run with a qualifying fix at [t]; returns its length so far in ms. */
        fun hit(t: Long): Long {
            if (start == NONE) start = t
            return t - start
        }
        fun miss() {
            start = NONE
        }
    }

    private val speedRun = Run() // speed >= 2.5
    private val fastRun = Run() // speed >= 4
    private val varioRun = Run() // |vario| >= 1.5 and speed >= 1.5
    private val stillRun = Run() // at rest (landing), terrain irrelevant
    private val stillKnownRun = Run() // at rest, terrain known on every fix
    private var lastFixMs = NONE

    /**
     * Feed each GPS fix (~1 Hz). [vario] m/s (NaN if unknown); [aglM] = height above terrain, NaN if
     * terrain unknown. Returns an event on a transition, else null.
     */
    fun update(timeMs: Long, groundSpeed: Double, vario: Double, aglM: Double): FlightEvent? {
        if (lastFixMs != NONE) {
            val dt = timeMs - lastFixMs
            if (dt > GAP_MS || dt < 0) clearRuns()
        }
        lastFixMs = timeMs
        val terrainKnown = !aglM.isNaN()

        if (state == FlightState.GROUND) {
            val speed = run(speedRun, groundSpeed >= TAKEOFF_SPEED, timeMs)
            val fast = run(fastRun, !terrainKnown && groundSpeed >= TAKEOFF_SPEED_NO_TERRAIN, timeMs)
            val lift = run(
                varioRun,
                !terrainKnown && abs(vario) >= TAKEOFF_VARIO && groundSpeed >= TAKEOFF_VARIO_MIN_SPEED,
                timeMs,
            )
            var start = NONE
            if (terrainKnown) {
                if (speed >= TAKEOFF_SUSTAIN_MS && aglM >= TAKEOFF_MIN_AGL) start = speedRun.start
            } else {
                if (fast >= TAKEOFF_SUSTAIN_NO_TERRAIN_MS) start = fastRun.start
                if (lift >= TAKEOFF_SUSTAIN_NO_TERRAIN_MS && (start == NONE || varioRun.start < start)) {
                    start = varioRun.start
                }
            }
            if (start != NONE) {
                enter(FlightState.FLYING, start)
                return FlightEvent.TAKEOFF
            }
        } else {
            val still = groundSpeed < LAND_SPEED && abs(if (vario.isNaN()) 0.0 else vario) < LAND_VARIO &&
                (!terrainKnown || aglM < LAND_MAX_AGL)
            val any = run(stillRun, still, timeMs)
            val known = run(stillKnownRun, still && terrainKnown, timeMs)
            var start = NONE
            if (known >= LAND_SUSTAIN_MS) start = stillKnownRun.start
            if (any >= LAND_SUSTAIN_NO_TERRAIN_MS && (start == NONE || stillRun.start < start)) {
                start = stillRun.start
            }
            if (start != NONE) {
                enter(FlightState.GROUND, start)
                return FlightEvent.LANDING
            }
        }
        return null
    }

    fun reset() {
        state = FlightState.GROUND
        takeoffTimeMs = 0L
        landingTimeMs = 0L
        lastFixMs = NONE
        clearRuns()
    }

    /** Manual override from the UI: switch to [state] as of [timeMs]. Sets the matching takeoff or landing time. */
    fun force(state: FlightState, timeMs: Long) {
        enter(state, timeMs)
    }

    private fun enter(next: FlightState, timeMs: Long) {
        state = next
        if (next == FlightState.FLYING) takeoffTimeMs = timeMs else landingTimeMs = timeMs
        clearRuns()
    }

    private fun run(r: Run, qualifies: Boolean, t: Long): Long {
        if (!qualifies) {
            r.miss()
            return -1L
        }
        return r.hit(t)
    }

    private fun clearRuns() {
        speedRun.miss()
        fastRun.miss()
        varioRun.miss()
        stillRun.miss()
        stillKnownRun.miss()
    }

    companion object {
        const val TAKEOFF_SPEED = 2.5 // m/s ground speed, terrain known
        const val TAKEOFF_SUSTAIN_MS = 3_000L
        const val TAKEOFF_MIN_AGL = 15.0 // m
        const val TAKEOFF_SPEED_NO_TERRAIN = 4.0 // m/s
        const val TAKEOFF_VARIO = 1.5 // m/s, absolute
        const val TAKEOFF_VARIO_MIN_SPEED = 1.5 // m/s
        const val TAKEOFF_SUSTAIN_NO_TERRAIN_MS = 5_000L
        const val LAND_SPEED = 1.5 // m/s
        const val LAND_VARIO = 0.5 // m/s, absolute
        const val LAND_MAX_AGL = 50.0 // m
        const val LAND_SUSTAIN_MS = 20_000L
        const val LAND_SUSTAIN_NO_TERRAIN_MS = 40_000L
        const val GAP_MS = 10_000L
        private const val NONE = Long.MIN_VALUE
    }
}
