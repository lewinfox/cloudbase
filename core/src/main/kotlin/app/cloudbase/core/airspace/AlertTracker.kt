package app.cloudbase.core.airspace

class AlertEvent(val status: AirspaceStatus)

/**
 * Dropped through the floor of [left] into [entered] (a control area into the zone below it, say)
 * without an alert saying so. Shown, not sounded.
 */
class AirspaceInfo(val entered: List<Airspace>, val left: List<Airspace>, val atMs: Long)

/**
 * Turns the per-second status lists into announcements, made only when something changes.
 *
 * Per airspace it remembers the highest level announced. Rising above it (clear → approaching →
 * inside → ceiling) is announced, once; there are no repeats. Falling is silent: crossing from
 * airspace A into B leaves you just outside A, which is "approaching A" by distance, and that
 * must not be announced. The remembered level is only lowered, or forgotten, once the airspace
 * has stayed below it for [clearAfterMs] ([ceilingClearAfterMs] for a ceiling) and you are no
 * longer over or under it, so weaving along a boundary does not announce every crossing, and
 * gliding back down into the zone you climbed out of is not news either.
 *
 * The banner shows only what was announced and not yet dismissed: a level reached silently (a
 * fall) is never shown, and a dismissed airspace stays hidden until it rises again.
 * Not thread-safe.
 */
class AlertTracker(
    val clearAfterMs: Long = 120_000,
    /**
     * Shorter: climbing back to the top of the zone in the next thermal is a new event worth a
     * warning, but GPS altitude wandering across the threshold for a few seconds is not.
     */
    val ceilingClearAfterMs: Long = 30_000,
) {
    private class State {
        var announced = AlertLevel.NONE
        /** Hidden from the banner at or below this level. */
        var dismissed = AlertLevel.NONE
        /** Last time the airspace was present at a level >= [announced]. */
        var qualifiedAtMs = 0L
        /** Level in the current update; only meaningful when [epoch] matches. */
        var levelNow = AlertLevel.NONE
        var epoch = -1
    }

    private val states = HashMap<Airspace, State>()
    private var epoch = 0
    private var insideNow: Set<Airspace> = emptySet()
    private var rebaseline = false

    /** The latest silent change of airspace, or null. Cleared by [dismissInfo] and [reset]. */
    var info: AirspaceInfo? = null
        private set

    /**
     * Call once per evaluate() with its (worst-first) list. [overhead] says whether the aircraft
     * is within an airspace's outline (at any height), [belowFloor] whether it is within the
     * outline and under the floor. Returns the one event to announce now, or null.
     */
    fun update(
        statuses: List<AirspaceStatus>,
        nowMs: Long,
        overhead: (Airspace) -> Boolean = { false },
        belowFloor: (Airspace) -> Boolean = { false },
    ): AlertEvent? {
        epoch++

        // 1. Record who is present, and refresh airspaces still at or above their announced level.
        for (s in statuses) {
            if (s.level == AlertLevel.NONE) continue
            val st = states.getOrPut(s.airspace) { State() }
            st.epoch = epoch
            st.levelNow = s.level
            if (s.level >= st.announced) st.qualifiedAtMs = nowMs
        }

        // 2. Lower or forget announced levels that have not been reached for long enough. The
        //    lower level was reached by falling, so it is not shown either.
        val it = states.entries.iterator()
        while (it.hasNext()) {
            val (airspace, st) = it.next()
            val ceiling = st.announced == AlertLevel.CEILING
            val wait = if (ceiling) ceilingClearAfterMs else clearAfterMs
            if (nowMs - st.qualifiedAtMs < wait) continue
            if (!ceiling && overhead(airspace)) continue
            val now = if (st.epoch == epoch) st.levelNow else AlertLevel.NONE
            if (now == AlertLevel.NONE) {
                it.remove()
            } else if (now < st.announced) {
                st.announced = now
                st.dismissed = maxOf(st.dismissed, now)
                st.qualifiedAtMs = nowMs
            }
        }

        // 3. Announce the worst rise. Others stay risen and are announced on later calls.
        var event: AlertEvent? = null
        for (s in statuses) {
            val st = states[s.airspace] ?: continue
            if (s.level <= st.announced) continue
            st.announced = s.level
            st.qualifiedAtMs = nowMs
            event = AlertEvent(s)
            break
        }

        // 4. Dropped out of the bottom of one into another without an alert saying so: a note.
        val inside = statuses.filter { it.level >= AlertLevel.INSIDE }.map { it.airspace }.toSet()
        if (rebaseline) {
            rebaseline = false
            insideNow = inside
        } else if (inside != insideNow) {
            val entered = inside - insideNow
            val left = (insideNow - inside).filter(belowFloor)
            val announced = event != null && event.status.level >= AlertLevel.INSIDE && event.status.airspace in entered
            // Entering one that is (or will next second be) announced says it all; a rise still
            // waiting its turn is about to be announced, so wait for it too.
            val pending = entered.any { a -> statuses.any { it.airspace === a && it.level > (states[a]?.announced ?: AlertLevel.NONE) } }
            if (entered.isNotEmpty() && left.isNotEmpty() && !announced && !pending) {
                info = AirspaceInfo(entered.toList(), left, nowMs)
            }
            insideNow = inside
        }
        return event
    }

    fun dismissInfo() {
        info = null
    }

    /**
     * The airspace set or which zones are ignored changed: the next update says where we are
     * without a note, so ignoring the zone you are in does not read as having left it.
     */
    fun airspaceChanged() {
        rebaseline = true
        info = null
    }

    /** The status for the banner: the worst one that was announced and has not been dismissed. */
    fun banner(statuses: List<AirspaceStatus>): AirspaceStatus? = statuses.firstOrNull { s ->
        val st = states[s.airspace]
        st != null && s.level == st.announced && s.level > st.dismissed
    }

    /** The pilot has seen it: hide [s] until its airspace rises above this level. */
    fun dismiss(s: AirspaceStatus) {
        val st = states[s.airspace] ?: return
        st.dismissed = maxOf(st.dismissed, s.level)
    }

    fun reset() {
        states.clear()
        epoch = 0
        insideNow = emptySet()
        info = null
    }
}
