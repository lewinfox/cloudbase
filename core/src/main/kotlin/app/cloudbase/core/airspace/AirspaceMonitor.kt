package app.cloudbase.core.airspace

import app.cloudbase.core.Geo
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

enum class AlertLevel { NONE, NEAR, IMMINENT, INSIDE }

enum class VerticalRelation { BELOW, WITHIN, ABOVE }

class AirspaceStatus(
    val airspace: Airspace,
    val level: AlertLevel,
    /** Where the aircraft is vertically relative to the airspace right now. */
    val relation: VerticalRelation,
    /** 0 when laterally inside, else metres to the boundary. */
    val horizontalM: Double,
    /** 0 when WITHIN; metres up to the floor when BELOW; metres down to the ceiling when ABOVE. */
    val verticalM: Double,
    /** Seconds until predicted entry, NaN if none predicted (always NaN unless IMMINENT). */
    val timeToEntryS: Double,
)

class MonitorConfig(
    val horizontalMarginM: Double = 300.0,
    val verticalMarginM: Double = 100.0,
    val lookaheadS: Double = 60.0,
)

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
        val vEast: Double,
        val vNorth: Double,
        val vario: Double,
        val dt: Double,
        val steps: Int,
        val degPerMLat: Double,
        val degPerMLon: Double,
    )

    /**
     * All ALERT-relevance airspaces with level != NONE, worst first (INSIDE, then IMMINENT by
     * time, then NEAR by distance).
     */
    fun evaluate(
        lat: Double,
        lon: Double,
        alt: AircraftAlt,
        trackDeg: Double,
        groundSpeed: Double,
        vario: Double,
    ): List<AirspaceStatus> {
        val cands = candidates
        if (cands.isEmpty()) return emptyList()
        val speed = if (groundSpeed.isNaN() || groundSpeed < 0.0) 0.0 else groundSpeed
        val horizontal = speed >= 1.0 && !trackDeg.isNaN()
        val lookahead = config.lookaheadS
        // Never look further than the aircraft can travel (plus the margin): the bbox pre-reject.
        val reachM = (max(config.horizontalMarginM, if (horizontal) speed * lookahead else 0.0)) * 1.05 + 10.0
        val degPerMLat = 1.0 / (Geo.EARTH_R * Geo.DEG)
        val dt = if (horizontal) min(SAMPLE_S, SAMPLE_M / speed) else SAMPLE_S
        val motion = Motion(
            horizontal = horizontal,
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
                AirspaceStatus(a, AlertLevel.INSIDE, relation, 0.0, 0.0, Double.NaN)
            } else {
                val t = predictEntryS(a, lat, lon, alt, motion, inside)
                if (!t.isNaN()) {
                    AirspaceStatus(a, AlertLevel.IMMINENT, relation, if (inside) 0.0 else a.distanceToBoundary(lat, lon), verticalM, t)
                } else if (verticalM <= config.verticalMarginM && bboxM <= config.horizontalMarginM) {
                    val h = if (inside) 0.0 else a.distanceToBoundary(lat, lon)
                    if (h <= config.horizontalMarginM) AirspaceStatus(a, AlertLevel.NEAR, relation, h, verticalM, Double.NaN) else null
                } else {
                    null
                }
            }
            if (status != null) (result ?: ArrayList<AirspaceStatus>().also { result = it }).add(status)
        }
        val r = result ?: return emptyList()
        r.sortWith(WORST_FIRST)
        return r
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

    /**
     * First sampled time within the lookahead at which the straight-line prediction is inside the
     * airspace both laterally and vertically, or NaN. Terrain and the msl/pressure offset are held
     * at their current values. Without a usable track only vertical entry is predicted.
     */
    private fun predictEntryS(a: Airspace, lat: Double, lon: Double, alt: AircraftAlt, m: Motion, insideNow: Boolean): Double {
        if (!m.horizontal && !insideNow) return Double.NaN
        for (k in 1..m.steps) {
            val t = min(k * m.dt, config.lookaheadS)
            if (verticalGap(a, alt, m.vario * t) != 0.0) continue // cheap test first
            if (m.horizontal && !a.contains(lat + m.vNorth * t * m.degPerMLat, lon + m.vEast * t * m.degPerMLon)) continue
            return t
        }
        return Double.NaN
    }

    private companion object {
        /** Prediction sampling: at least every 2 s and every 30 m of travel. */
        const val SAMPLE_S = 2.0
        const val SAMPLE_M = 30.0

        val WORST_FIRST = Comparator<AirspaceStatus> { x, y ->
            when {
                x.level != y.level -> y.level.ordinal - x.level.ordinal
                x.level == AlertLevel.IMMINENT -> x.timeToEntryS.compareTo(y.timeToEntryS)
                x.level == AlertLevel.NEAR -> x.horizontalM.compareTo(y.horizontalM)
                else -> 0
            }
        }
    }
}
