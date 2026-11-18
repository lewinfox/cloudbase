package app.cloudbase.core.airspace

import app.cloudbase.core.Atmosphere

enum class AltRef {
    /** Height above the ground directly below. */
    GND,
    /** Altitude above mean sea level. */
    MSL,
    /** Pressure altitude on 1013.25 hPa: flight levels. */
    STD,
}

/**
 * A vertical limit. [metres] is measured in the datum [ref]. "Unlimited" is represented as
 * STD with [metres] = Double.POSITIVE_INFINITY; the surface is GND 0.
 */
data class AltLimit(val ref: AltRef, val metres: Double, val text: String) {
    val isSurface: Boolean get() = ref == AltRef.GND && metres <= 0.0
    val isUnlimited: Boolean get() = metres == Double.POSITIVE_INFINITY

    companion object {
        val SURFACE = AltLimit(AltRef.GND, 0.0, "GND")
        val UNLIMITED = AltLimit(AltRef.STD, Double.POSITIVE_INFINITY, "UNL")
        fun flightLevel(fl: Int) = AltLimit(AltRef.STD, fl * 100 * Atmosphere.FT, "FL$fl")
    }
}

/**
 * Where the aircraft is vertically, in every datum an airspace limit can use, so a limit is
 * compared in its own datum instead of being converted (which is where errors creep in).
 * [terrain] is ground elevation AMSL below the aircraft, NaN when unknown. [pressureAlt] should
 * be set to [msl] when there is no barometer.
 */
data class AircraftAlt(val msl: Double, val pressureAlt: Double, val terrain: Double) {
    /** Aircraft altitude expressed in [ref]. For GND with unknown terrain, assumes sea-level ground. */
    fun inRef(ref: AltRef): Double = when (ref) {
        AltRef.MSL -> msl
        AltRef.STD -> pressureAlt
        AltRef.GND -> if (terrain.isNaN()) msl else msl - terrain
    }
}

/**
 * One airspace volume: a closed polygon (first vertex not repeated) between two vertical limits.
 * Arcs and circles from the source file are already tessellated into vertices.
 *
 * [cls] is the OpenAir AC value upper-cased ("A".."G", "UNC", or legacy values such as "CTR",
 * "R", "Q", "P", "GP", "W", "TMZ", "RMZ"). [type] is the OpenAir 2.0 AY value upper-cased
 * ("CTR", "TMA", "R", "P", "Q", "TMZ", ... or "" when absent or "NONE").
 */
class Airspace(
    val name: String,
    val cls: String,
    val type: String,
    val lower: AltLimit,
    val upper: AltLimit,
    val lats: DoubleArray,
    val lons: DoubleArray,
    val frequency: String? = null,
) {
    val minLat: Double
    val maxLat: Double
    val minLon: Double
    val maxLon: Double

    init {
        require(lats.size == lons.size && lats.size >= 3) { "airspace '$name' needs at least 3 vertices" }
        var a = Double.POSITIVE_INFINITY
        var b = Double.NEGATIVE_INFINITY
        var c = Double.POSITIVE_INFINITY
        var d = Double.NEGATIVE_INFINITY
        for (i in lats.indices) {
            if (lats[i] < a) a = lats[i]
            if (lats[i] > b) b = lats[i]
            if (lons[i] < c) c = lons[i]
            if (lons[i] > d) d = lons[i]
        }
        minLat = a; maxLat = b; minLon = c; maxLon = d
    }

    /** Short label for the kind of airspace: the type when there is one, else the class. */
    val kind: String get() = type.ifEmpty { cls }

    override fun toString(): String = "$kind $name ${lower.text}–${upper.text}"
}
