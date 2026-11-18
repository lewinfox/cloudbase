package app.cloudbase.core.airspace

import app.cloudbase.core.Atmosphere
import app.cloudbase.core.LocalProjection

/** Test fixtures: airspaces built in metres on a flat plane around a reference point in the Alps. */
object TestAirspaces {
    const val REF_LAT = 46.0
    const val REF_LON = 8.0
    val proj = LocalProjection(REF_LAT, REF_LON)

    fun lat(yM: Double) = proj.lat(yM)
    fun lon(xM: Double) = proj.lon(xM)

    fun msl(m: Double) = AltLimit(AltRef.MSL, m, "${m.toInt()}m MSL")
    fun agl(m: Double) = AltLimit(AltRef.GND, m, "${m.toInt()}m AGL")
    fun fl(n: Int) = AltLimit.flightLevel(n)
    val gnd = AltLimit.SURFACE

    /** Polygon from x,y pairs in metres east/north of the reference point. */
    fun poly(
        name: String, cls: String, type: String, lower: AltLimit, upper: AltLimit, vararg xy: Double,
    ): Airspace {
        val n = xy.size / 2
        return Airspace(
            name, cls, type, lower, upper,
            DoubleArray(n) { lat(xy[2 * it + 1]) },
            DoubleArray(n) { lon(xy[2 * it]) },
        )
    }

    fun box(
        name: String, cls: String, type: String, lower: AltLimit, upper: AltLimit,
        x0: Double, y0: Double, x1: Double, y1: Double,
    ) = poly(name, cls, type, lower, upper, x0, y0, x1, y0, x1, y1, x0, y1)

    /** A plain 2 km square at the origin, class D CTR, surface to 1500 m MSL. */
    fun square() = box("SQ", "D", "CTR", gnd, msl(1500.0), -1000.0, -1000.0, 1000.0, 1000.0)

    /** Aircraft altitude with pressure altitude equal to MSL (no offset). */
    fun alt(msl: Double, terrain: Double = Double.NaN) = AircraftAlt(msl, msl, terrain)

    fun ft(feet: Double) = feet * Atmosphere.FT
}
