package app.cloudbase.core.airspace

import app.cloudbase.core.Geo
import app.cloudbase.core.LocalProjection
import java.util.Arrays
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

// Planar geometry on lat/lon. Polygon edges are straight lines in lat/lon space (a linear image
// of the local flat-earth plane, so straight stays straight under LocalProjection too).
// Out of scope: polygons crossing the antimeridian (longitudes jump from +180 to -180), and
// polar polygons. Very long edges are also approximated: a great-circle edge of 100 km at 60N
// bulges ~300 m from its lat/lon chord, which we ignore.

private const val M_PER_DEG_LAT = Geo.EARTH_R * Geo.DEG

/** True when the point is inside the polygon (bbox reject, then even-odd ray cast). No allocation. */
fun Airspace.contains(lat: Double, lon: Double): Boolean {
    if (lat < minLat || lat > maxLat || lon < minLon || lon > maxLon) return false
    val la = lats
    val lo = lons
    var inside = false
    var j = la.size - 1
    for (i in la.indices) {
        val yi = la[i]
        val yj = la[j]
        if ((yi > lat) != (yj > lat)) {
            val x = lo[i] + (lo[j] - lo[i]) * (lat - yi) / (yj - yi)
            if (lon < x) inside = !inside
        }
        j = i
    }
    return inside
}

/** Metres from the point to the nearest polygon edge (always >= 0, whether inside or outside). */
fun Airspace.distanceToBoundary(lat: Double, lon: Double): Double {
    val proj = LocalProjection(lat, lon)
    val n = lats.size
    var px = proj.x(lons[n - 1])
    var py = proj.y(lats[n - 1])
    var best = Double.POSITIVE_INFINITY
    for (i in 0 until n) {
        val qx = proj.x(lons[i])
        val qy = proj.y(lats[i])
        // Distance from the origin (the query point) to segment p-q.
        val ex = qx - px
        val ey = qy - py
        val len2 = ex * ex + ey * ey
        val t = if (len2 == 0.0) 0.0 else (-(px * ex + py * ey) / len2).coerceIn(0.0, 1.0)
        val cx = px + t * ex
        val cy = py + t * ey
        val d2 = cx * cx + cy * cy
        if (d2 < best) best = d2
        px = qx
        py = qy
    }
    return sqrt(best)
}

/**
 * Metres from the point to the bounding box (0 inside it). A cheap lower bound of the distance to
 * the polygon; the east-west scale uses the latitude midway to the nearest bbox latitude.
 */
internal fun Airspace.bboxDistanceM(lat: Double, lon: Double): Double {
    val dLat = if (lat < minLat) minLat - lat else if (lat > maxLat) lat - maxLat else 0.0
    val dLon = if (lon < minLon) minLon - lon else if (lon > maxLon) lon - maxLon else 0.0
    if (dLon == 0.0) return dLat * M_PER_DEG_LAT
    val nearestLat = lat.coerceIn(minLat, maxLat)
    val dx = dLon * M_PER_DEG_LAT * cos((lat + nearestLat) / 2.0 * Geo.DEG)
    val dy = dLat * M_PER_DEG_LAT
    return sqrt(dx * dx + dy * dy)
}

/** Stretches [fromM, toM] of a straight line (start, true bearing, length) that lie laterally inside the polygon. */
class Slab(val airspace: Airspace, val fromM: Double, val toM: Double)

/**
 * Where the straight line from (lat, lon) on [bearingDeg] for [lengthM] is laterally inside each
 * candidate. Exact segment/edge intersections in a local flat projection; a concave polygon can
 * give several slabs, and a start point already inside gives a slab beginning at 0. Slabs of
 * zero length (tangent touches) are dropped. Result is sorted by [Slab.fromM].
 */
fun crossSection(candidates: List<Airspace>, lat: Double, lon: Double, bearingDeg: Double, lengthM: Double): List<Slab> {
    if (!(lengthM > 0.0)) return emptyList()
    val proj = LocalProjection(lat, lon)
    val b = bearingDeg * Geo.DEG
    val dx = sin(b) // east component of the unit direction
    val dy = cos(b) // north component
    val result = ArrayList<Slab>()
    var crossings = DoubleArray(32)
    for (a in candidates) {
        val n = a.lats.size
        if (crossings.size < n) crossings = DoubleArray(n)
        var count = 0
        var ax = proj.x(a.lons[n - 1])
        var ay = proj.y(a.lats[n - 1])
        // side >= 0: point is left of (or on) the infinite line through the origin along (dx, dy).
        // Counting "on the line" as left keeps crossing parity consistent when the line passes
        // exactly through a vertex.
        var sideA = dx * ay - dy * ax
        for (i in 0 until n) {
            val bx = proj.x(a.lons[i])
            val by = proj.y(a.lats[i])
            val sideB = dx * by - dy * bx
            if ((sideA >= 0.0) != (sideB >= 0.0)) {
                val u = sideA / (sideA - sideB)
                // Distance along the line of the intersection point (may be negative or beyond the length).
                crossings[count++] = dx * (ax + u * (bx - ax)) + dy * (ay + u * (by - ay))
            }
            ax = bx; ay = by; sideA = sideB
        }
        if (count < 2) continue
        Arrays.sort(crossings, 0, count)
        // Along the infinite line: outside before the 1st crossing, inside between 1st and 2nd, ...
        var k = 0
        while (k + 1 < count) {
            val from = max(crossings[k], 0.0)
            val to = min(crossings[k + 1], lengthM)
            if (to > from) result.add(Slab(a, from, to))
            k += 2
        }
    }
    result.sortBy { it.fromM }
    return result
}
