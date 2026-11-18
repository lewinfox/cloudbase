package app.cloudbase.map

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sinh

/** Web-Mercator in "world pixels at zoom z": 256-px tiles, origin top-left (lon -180, lat 85.0511). */
object Mercator {
    const val TILE = 256
    const val MAX_LAT = 85.0511

    /** Equatorial radius of the spherical Mercator model (not the mean radius used by Geo). */
    private const val R = 6_378_137.0

    /** Width of the whole world in pixels at [zoom]. */
    fun worldSize(zoom: Double): Double = TILE * 2.0.pow(zoom)

    /** Longitude is not wrapped, so callers can work across the antimeridian. */
    fun x(lon: Double, zoom: Double): Double = (lon + 180.0) / 360.0 * worldSize(zoom)

    /** Latitude is clamped to ±[MAX_LAT]. */
    fun y(lat: Double, zoom: Double): Double {
        val s = sin(lat.coerceIn(-MAX_LAT, MAX_LAT) * PI / 180.0)
        return (0.5 - ln((1 + s) / (1 - s)) / (4 * PI)) * worldSize(zoom)
    }

    fun lon(x: Double, zoom: Double): Double = x / worldSize(zoom) * 360.0 - 180.0

    /** Result is clamped to ±[MAX_LAT], so a y off the map edge gives the edge latitude. */
    fun lat(y: Double, zoom: Double): Double {
        val n = PI * (1 - 2 * y / worldSize(zoom))
        return (atan(sinh(n)) * 180.0 / PI).coerceIn(-MAX_LAT, MAX_LAT)
    }

    fun metresPerPixel(lat: Double, zoom: Double): Double =
        cos(lat.coerceIn(-MAX_LAT, MAX_LAT) * PI / 180.0) * 2 * PI * R / worldSize(zoom)
}
