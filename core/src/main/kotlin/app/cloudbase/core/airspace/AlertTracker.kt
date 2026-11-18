package app.cloudbase.core.airspace

class AlertEvent(val status: AirspaceStatus, val repeat: Boolean)

/**
 * Turns the per-second status lists into discrete announcements without chatter.
 *
 * Per airspace it remembers the highest level announced. A higher level fires immediately;
 * INSIDE and IMMINENT repeat at their intervals while they persist; NEAR is announced once. The
 * remembered level is only lowered (to whatever the airspace is at now) or forgotten (if absent)
 * after the airspace has failed to reach it for [clearAfterMs], so skimming a boundary does not
 * re-announce. Not thread-safe.
 */
class AlertTracker(
    val repeatInsideMs: Long = 30_000,
    val repeatImminentMs: Long = 15_000,
    val clearAfterMs: Long = 10_000,
    /**
     * Repeats of the same alert before it goes quiet. Someone still inside after this many has
     * heard it and is there on purpose (flying a site inside a control zone by local agreement,
     * say); nagging for the rest of the flight only teaches them to mute the app. The banner
     * stays, and leaving and re-entering starts afresh.
     */
    val maxRepeats: Int = 3,
) {
    private class State {
        var announced = AlertLevel.NONE
        var announcedAtMs = 0L
        var repeats = 0
        /** Last time the airspace was present at a level >= [announced]. */
        var qualifiedAtMs = 0L
        /** Level in the current update; only meaningful when [epoch] matches. */
        var levelNow = AlertLevel.NONE
        var epoch = -1
    }

    private val states = HashMap<Airspace, State>()
    private var epoch = 0

    /** Call once per evaluate(). Returns the one event to announce now, or null. */
    fun update(statuses: List<AirspaceStatus>, nowMs: Long): AlertEvent? {
        epoch++

        // 1. Record who is present, and refresh airspaces still at or above their announced level.
        for (s in statuses) {
            if (s.level == AlertLevel.NONE) continue
            val st = states.getOrPut(s.airspace) { State() }
            st.epoch = epoch
            st.levelNow = s.level
            if (s.level >= st.announced) st.qualifiedAtMs = nowMs
        }

        // 2. Lower or forget announced levels that have not been reached for clearAfterMs.
        val it = states.values.iterator()
        while (it.hasNext()) {
            val st = it.next()
            if (nowMs - st.qualifiedAtMs < clearAfterMs) continue
            val now = if (st.epoch == epoch) st.levelNow else AlertLevel.NONE
            if (now == AlertLevel.NONE) {
                it.remove()
            } else {
                st.announced = now
                st.repeats = 0
                st.announcedAtMs = nowMs // already announced recently enough; do not repeat at once
                st.qualifiedAtMs = nowMs
            }
        }

        // 3. Pick the worst airspace due an announcement. Others stay due and fire on later calls.
        var best: AirspaceStatus? = null
        var bestRise = false
        for (s in statuses) {
            if (s.level == AlertLevel.NONE) continue
            val st = states[s.airspace] ?: continue
            val rise = s.level > st.announced
            val due = rise || (s.level == st.announced && st.repeats < maxRepeats && nowMs - st.announcedAtMs >= repeatMs(s.level))
            if (due && better(s, rise, best, bestRise)) {
                best = s
                bestRise = rise
            }
        }
        val chosen = best ?: return null
        val st = states.getValue(chosen.airspace)
        st.repeats = if (bestRise) 0 else st.repeats + 1
        st.announced = chosen.level
        st.announcedAtMs = nowMs
        st.qualifiedAtMs = nowMs
        return AlertEvent(chosen, repeat = !bestRise)
    }

    fun reset() {
        states.clear()
        epoch = 0
    }

    /** Repeat interval for a level; Long.MAX_VALUE means never (NEAR announces once). */
    private fun repeatMs(level: AlertLevel): Long = when (level) {
        AlertLevel.INSIDE -> repeatInsideMs
        AlertLevel.IMMINENT -> repeatImminentMs
        else -> Long.MAX_VALUE
    }

    private fun better(s: AirspaceStatus, rise: Boolean, b: AirspaceStatus?, bRise: Boolean): Boolean {
        if (b == null) return true
        if (s.level != b.level) return s.level > b.level
        if (rise != bRise) return rise
        return when (s.level) {
            AlertLevel.IMMINENT -> s.timeToEntryS < b.timeToEntryS
            AlertLevel.NEAR -> s.horizontalM < b.horizontalM
            else -> false
        }
    }
}
