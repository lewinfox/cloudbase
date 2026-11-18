package app.cloudbase.core.flight

import app.cloudbase.core.Geo
import app.cloudbase.core.LatLon

/**
 * Where the glider meets the ground if it carries on as it is: same track, same glide ratio over
 * the ground. Because the ratio is measured over the ground (ground speed ÷ sink rate) it already
 * includes the wind and any lift or sink being flown through, so no wind correction is applied.
 */
object GlideReach {
    /** Terrain is sampled this often along the line; finer than the terrain data itself is pointless. */
    const val STEP_M = 50.0
    const val MAX_M = 20_000.0

    /**
     * Walks along [trackDeg] from the aircraft, descending 1 m per [glideRatio] m travelled, until
     * the path is at or below the ground. [terrain] gives ground elevation AMSL, NaN where unknown.
     * Returns null when there is no answer worth drawing: not descending, no track, terrain
     * unknown anywhere along the path (guessing there would be worse than saying nothing), or no
     * ground reached within [MAX_M].
     */
    fun impact(
        lat: Double, lon: Double, altMsl: Double, trackDeg: Double, glideRatio: Double,
        terrain: (lat: Double, lon: Double) -> Double,
    ): LatLon? {
        if (altMsl.isNaN() || trackDeg.isNaN() || !(glideRatio > 0.0) || glideRatio.isInfinite()) return null
        var prevD = 0.0
        var prevClear = altMsl - terrain(lat, lon) // height above ground at the previous sample
        if (prevClear.isNaN()) return null
        if (prevClear <= 0.0) return LatLon(lat, lon)
        var d = STEP_M
        while (d <= MAX_M) {
            val p = Geo.destination(lat, lon, trackDeg, d)
            val ground = terrain(p.lat, p.lon)
            if (ground.isNaN()) return null
            val clear = altMsl - d / glideRatio - ground
            if (clear <= 0.0) {
                // Interpolate between the last sample above ground and this one below it.
                val hit = prevD + (d - prevD) * prevClear / (prevClear - clear)
                return Geo.destination(lat, lon, trackDeg, hit)
            }
            prevD = d
            prevClear = clear
            d += STEP_M
        }
        return null
    }
}
