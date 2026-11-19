package app.cloudbase.core.airspace

import app.cloudbase.core.Geo
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * How bad things are with one airspace, least to worst. Each step up is announced once.
 *  - APPROACHING: you could be in it within the warning time on your present course and climb,
 *    allowing for how far off the position and altitude could be (or you could be in it already).
 *  - INSIDE: you are in it.
 *  - CEILING: you are in it and could go out through its top within the warning time.
 */
enum class AlertLevel { NONE, APPROACHING, INSIDE, CEILING }

enum class VerticalRelation { BELOW, WITHIN, ABOVE }

class AirspaceStatus(
    val airspace: Airspace,
    val level: AlertLevel,
    /** Where the aircraft is vertically relative to the airspace right now. */
    val relation: VerticalRelation,
    /** 0 when laterally inside, else metres to the boundary. */
    val horizontalM: Double,
    /**
     * 0 when WITHIN (CEILING: metres up to the top); metres up to the floor when BELOW; metres down
     * to the ceiling when ABOVE.
     */
    val verticalM: Double,
    /**
     * APPROACHING and CEILING: seconds until the boundary, less the position error; 0 when within
     * the error already. NaN otherwise.
     */
    val timeToEntryS: Double,
)

/** The next limit above the aircraft: [metres] up to it, and how far off that figure could be. */
class Ceiling(val airspace: Airspace, val limit: AltLimit, val metres: Double, val errorM: Double)

class MonitorConfig(
    /** How much warning the pilot wants: seconds to the boundary at which to warn. */
    val lookaheadS: Double = 60.0,
)

/**
 * How far off our position could be (about 95 %, metres): horizontally, and vertically in each
 * datum an airspace limit can use. Every boundary is treated as this much closer than it looks.
 */
data class PositionError(val horizontalM: Double, val mslM: Double, val stdM: Double, val gndM: Double) {
    fun vertical(ref: AltRef): Double = when (ref) {
        AltRef.MSL -> mslM
        AltRef.STD -> stdM
        AltRef.GND -> gndM
    }

    companion object {
        val NONE = PositionError(0.0, 0.0, 0.0, 0.0)
    }
}

/**
 * The once-a-second proximity check. Not thread-safe: call from one thread.
 *
 * Every vertical limit is compared in its own datum via [AircraftAlt.inRef]; limits are never
 * converted. An unknown (NaN) aircraft altitude makes every comparison fail, which reads as
 * "within": the conservative answer for a warning device.
 */
class AirspaceMonitor(
    val policy: AirspacePolicy = AirspacePolicy(),
    val config: MonitorConfig = MonitorConfig(),
) {
    /** Only ALERT-relevance airspaces; the others can never produce a status or headroom. */
    private var candidates: Array<Airspace> = emptyArray()

    /** Candidates from AirspaceIndex.near(); filtered by policy here (HIDE dropped, and SHOW is never alerted on). */
    fun setCandidates(list: List<Airspace>) {
        candidates = list.filter { policy.relevance(it) == Relevance.ALERT }.toTypedArray()
    }

    /** Straight-line motion over the lookahead window, computed once per evaluation. */
    private class Motion(
        val horizontal: Boolean,
        val speed: Double,
        val vEast: Double,
        val vNorth: Double,
        val vario: Double,
        val dt: Double,
        val steps: Int,
        val degPerMLat: Double,
        val degPerMLon: Double,
    )

    /**
     * All ALERT-relevance airspaces with level != NONE, worst first (CEILING, INSIDE, then
     * APPROACHING soonest first). [trackDeg] and [groundSpeed] are the motion to project: the
     * caller smooths them while circling, when the present heading says nothing about where the
     * glider is going. [vario] likewise should be a climb rate averaged over several seconds.
     */
    fun evaluate(
        lat: Double,
        lon: Double,
        alt: AircraftAlt,
        trackDeg: Double,
        groundSpeed: Double,
        vario: Double,
        err: PositionError = PositionError.NONE,
    ): List<AirspaceStatus> {
        val cands = candidates
        if (cands.isEmpty()) return emptyList()
        val speed = if (groundSpeed.isNaN() || groundSpeed < 0.0) 0.0 else groundSpeed
        val horizontal = speed >= 1.0 && !trackDeg.isNaN()
        val lookahead = config.lookaheadS
        val hErr = err.horizontalM
        // Never look further than the aircraft can travel (plus the error): the bbox pre-reject.
        val reachM = ((if (horizontal) speed * lookahead else 0.0) + hErr) * 1.05 + 10.0
        val degPerMLat = 1.0 / (Geo.EARTH_R * Geo.DEG)
        val dt = if (horizontal) min(SAMPLE_S, SAMPLE_M / speed) else SAMPLE_S
        val motion = Motion(
            horizontal = horizontal,
            speed = speed,
            vEast = if (horizontal) speed * sin(trackDeg * Geo.DEG) else 0.0,
            vNorth = if (horizontal) speed * cos(trackDeg * Geo.DEG) else 0.0,
            vario = if (vario.isNaN()) 0.0 else vario,
            dt = dt,
            steps = if (lookahead > 0.0) ceil(lookahead / dt).toInt() else 0,
            degPerMLat = degPerMLat,
            degPerMLon = degPerMLat / cos(lat * Geo.DEG).coerceAtLeast(1e-6),
        )

        var result: ArrayList<AirspaceStatus>? = null
        for (a in cands) {
            val bboxM = a.bboxDistanceM(lat, lon)
            if (bboxM > reachM) continue

            val gap = verticalGap(a, alt, 0.0)
            val relation = if (gap < 0.0) VerticalRelation.BELOW else if (gap > 0.0) VerticalRelation.ABOVE else VerticalRelation.WITHIN
            val verticalM = abs(gap)
            val inside = bboxM == 0.0 && a.contains(lat, lon)

            val status: AirspaceStatus? = if (inside && relation == VerticalRelation.WITHIN) {
                val top = timeToTopS(a, alt, motion.vario, err)
                if (top.isNaN()) AirspaceStatus(a, AlertLevel.INSIDE, relation, 0.0, 0.0, Double.NaN)
                else AirspaceStatus(a, AlertLevel.CEILING, relation, 0.0, a.upper.metres - alt.inRef(a.upper.ref), top)
            } else {
                val h = if (inside) 0.0 else if (bboxM > hErr && !horizontal) Double.POSITIVE_INFINITY else a.distanceToBoundary(lat, lon)
                val nearNow = h <= hErr
                val t = if (nearNow && withinBand(a, alt, 0.0, err)) 0.0 else predictEntryS(a, lat, lon, alt, motion, nearNow, err)
                if (t.isNaN()) null else AirspaceStatus(a, AlertLevel.APPROACHING, relation, h, verticalM, t)
            }
            if (status != null) (result ?: ArrayList<AirspaceStatus>().also { result = it }).add(status)
        }
        val r = result ?: return emptyList()
        dedupeCeilings(r, err)
        r.sortWith(WORST_FIRST)
        return r
    }

    /**
     * Where the top of one airspace is the floor of the next (a control zone under a control
     * area), climbing to it is one event, not two: it is announced as approaching the airspace
     * above, and the zone you are in stays plain INSIDE.
     */
    private fun dedupeCeilings(r: ArrayList<AirspaceStatus>, err: PositionError) {
        for (i in r.indices) {
            val c = r[i]
            if (c.level != AlertLevel.CEILING) continue
            val tol = err.vertical(c.airspace.upper.ref) + SAME_LEVEL_M
            val above = r.any {
                it.level == AlertLevel.APPROACHING && it.relation == VerticalRelation.BELOW && it.horizontalM == 0.0 &&
                    abs(it.verticalM - c.verticalM) <= tol
            }
            if (above) r[i] = AirspaceStatus(c.airspace, AlertLevel.INSIDE, c.relation, 0.0, 0.0, Double.NaN)
        }
    }

    /**
     * Seconds until the top of [a] (which we are inside), less the vertical error: 0 if within the
     * error already, NaN if not within the lookahead or the top is unlimited.
     */
    private fun timeToTopS(a: Airspace, alt: AircraftAlt, vario: Double, err: PositionError): Double {
        if (a.upper.isUnlimited) return Double.NaN
        val clear = a.upper.metres - alt.inRef(a.upper.ref) - err.vertical(a.upper.ref)
        if (clear.isNaN()) return Double.NaN
        if (clear <= 0.0) return 0.0
        if (vario <= 0.0) return Double.NaN
        val t = clear / vario
        return if (t <= config.lookaheadS) t else Double.NaN
    }

    /**
     * The next boundary above: the top of the airspace you are in or the lowest floor overhead,
     * whichever is lower. Null when there is none (or altitude is unknown).
     */
    fun ceiling(lat: Double, lon: Double, alt: AircraftAlt, err: PositionError = PositionError.NONE): Ceiling? {
        var best: Ceiling? = null
        for (a in candidates) {
            val gap = verticalGap(a, alt, 0.0)
            val limit = when {
                gap < 0.0 -> a.lower
                gap == 0.0 && !a.upper.isUnlimited -> a.upper
                else -> continue
            }
            val m = limit.metres - alt.inRef(limit.ref)
            if (!(m >= 0.0) || (best != null && m >= best.metres)) continue
            if (a.contains(lat, lon)) best = Ceiling(a, limit, m, err.vertical(limit.ref))
        }
        return best
    }

    /** The lowest ALERT airspace floor above the aircraft at this position: (airspace, metres of headroom), or null. */
    fun headroom(lat: Double, lon: Double, alt: AircraftAlt): Pair<Airspace, Double>? {
        var best: Airspace? = null
        var bestM = Double.POSITIVE_INFINITY
        for (a in candidates) {
            if (a.lower.isSurface) continue
            val h = a.lower.metres - alt.inRef(a.lower.ref)
            if (!(h > 0.0) || h >= bestM) continue // floor not above us (or altitude unknown)
            if (a.contains(lat, lon)) {
                best = a
                bestM = h
            }
        }
        return best?.let { Pair(it, bestM) }
    }

    /**
     * Signed vertical gap after the aircraft has climbed [dh] metres: negative = below the floor
     * (metres short of it), positive = above the ceiling, exactly 0.0 = between them. A surface
     * floor (GND 0) never counts: GPS or terrain error must not put a pilot "below the ground".
     */
    private fun verticalGap(a: Airspace, alt: AircraftAlt, dh: Double): Double {
        val lo = a.lower
        if (!lo.isSurface) {
            val h = alt.inRef(lo.ref) + dh
            if (h < lo.metres) return h - lo.metres
        }
        val up = a.upper
        val h = alt.inRef(up.ref) + dh
        if (h > up.metres) return h - up.metres
        return 0.0
    }

    /** Between the limits, or outside them by no more than the vertical error, after climbing [dh]. */
    private fun withinBand(a: Airspace, alt: AircraftAlt, dh: Double, err: PositionError): Boolean {
        val g = verticalGap(a, alt, dh)
        return when {
            g < 0.0 -> -g <= err.vertical(a.lower.ref)
            g > 0.0 -> g <= err.vertical(a.upper.ref)
            else -> true
        }
    }

    /**
     * First sampled time within the lookahead at which the straight-line prediction could be
     * inside the airspace given the position error, or NaN. Laterally the error is added along
     * the track (the boundary is reached that much sooner); vertically the band is widened by
     * it. Terrain and the msl/pressure offset are held at their current values. Without a usable
     * track only vertical entry is predicted, and only where we already are (within the error).
     */
    private fun predictEntryS(a: Airspace, lat: Double, lon: Double, alt: AircraftAlt, m: Motion, nearNow: Boolean, err: PositionError): Double {
        if (!m.horizontal && !nearNow) return Double.NaN
        val ext = if (m.horizontal) err.horizontalM / m.speed else 0.0
        for (k in 1..m.steps) {
            val t = min(k * m.dt, config.lookaheadS)
            if (!withinBand(a, alt, m.vario * t, err)) continue // cheap test first
            if (m.horizontal) {
                val tt = t + ext
                if (!a.contains(lat + m.vNorth * tt * m.degPerMLat, lon + m.vEast * tt * m.degPerMLon)) continue
            }
            return t
        }
        return Double.NaN
    }

    private companion object {
        /** Prediction sampling: at least every 2 s and every 30 m of travel. */
        const val SAMPLE_S = 2.0
        const val SAMPLE_M = 30.0

        /** Metres within which a zone's top and the floor above it count as the same boundary. */
        const val SAME_LEVEL_M = 15.0

        val WORST_FIRST = Comparator<AirspaceStatus> { x, y ->
            when {
                x.level != y.level -> y.level.ordinal - x.level.ordinal
                x.level == AlertLevel.APPROACHING || x.level == AlertLevel.CEILING -> x.timeToEntryS.compareTo(y.timeToEntryS)
                else -> 0
            }
        }
    }
}
