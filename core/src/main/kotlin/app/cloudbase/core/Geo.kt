package app.cloudbase.core

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class LatLon(val lat: Double, val lon: Double)

/** Spherical-earth geodesy. Good to ~0.3 %, which is well inside GPS noise at paragliding ranges. */
object Geo {
    const val EARTH_R = 6_371_008.8
    const val DEG = PI / 180.0

    /** Great-circle distance in metres. */
    fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = (lat2 - lat1) * DEG
        val dLon = (lon2 - lon1) * DEG
        val a = sin(dLat / 2).let { it * it } + cos(lat1 * DEG) * cos(lat2 * DEG) * sin(dLon / 2).let { it * it }
        return 2 * EARTH_R * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    /** Initial true bearing from point 1 to point 2, degrees 0..360. */
    fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLon = (lon2 - lon1) * DEG
        val y = sin(dLon) * cos(lat2 * DEG)
        val x = cos(lat1 * DEG) * sin(lat2 * DEG) - sin(lat1 * DEG) * cos(lat2 * DEG) * cos(dLon)
        return norm360(atan2(y, x) / DEG)
    }

    /** Point reached travelling [dist] metres from (lat, lon) on true bearing [bearingDeg]. */
    fun destination(lat: Double, lon: Double, bearingDeg: Double, dist: Double): LatLon {
        val d = dist / EARTH_R
        val b = bearingDeg * DEG
        val la1 = lat * DEG
        val la2 = asin(sin(la1) * cos(d) + cos(la1) * sin(d) * cos(b))
        val lo2 = lon * DEG + atan2(sin(b) * sin(d) * cos(la1), cos(d) - sin(la1) * sin(la2))
        return LatLon(la2 / DEG, norm180(lo2 / DEG))
    }

    fun norm360(deg: Double): Double = ((deg % 360.0) + 360.0) % 360.0

    fun norm180(deg: Double): Double = norm360(deg + 180.0) - 180.0

    /** Signed smallest difference a − b in degrees, −180..180. */
    fun angleDiff(a: Double, b: Double): Double = norm180(a - b)
}

/**
 * Flat-earth projection around an origin: x metres east, y metres north. Accurate to well under
 * 1 m within a few tens of km, and far cheaper than spherical trig in per-fix code.
 */
class LocalProjection(val lat0: Double, val lon0: Double) {
    private val mPerDegLat = Geo.EARTH_R * Geo.DEG
    private val mPerDegLon = Geo.EARTH_R * Geo.DEG * cos(lat0 * Geo.DEG)

    fun x(lon: Double): Double = Geo.norm180(lon - lon0) * mPerDegLon
    fun y(lat: Double): Double = (lat - lat0) * mPerDegLat
    fun lon(x: Double): Double = lon0 + x / mPerDegLon
    fun lat(y: Double): Double = lat0 + y / mPerDegLat
}
