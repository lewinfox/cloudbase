package app.cloudbase.core

import kotlin.math.pow

/** ICAO standard atmosphere, troposphere only (valid to 11 km). */
object Atmosphere {
    const val P0 = 1013.25
    const val FT = 0.3048

    /** Altitude in metres for a pressure in hPa, given the sea-level pressure [qnh]. */
    fun altitude(hPa: Double, qnh: Double = P0): Double = 44330.769 * (1.0 - (hPa / qnh).pow(0.190263))

    /** Pressure altitude (what flight levels are measured in), metres. */
    fun pressureAltitude(hPa: Double): Double = altitude(hPa, P0)

    /** The sea-level pressure that makes [hPa] read as [altM] metres. */
    fun qnh(hPa: Double, altM: Double): Double = hPa / (1.0 - altM / 44330.769).pow(1.0 / 0.190263)

    /** Air density relative to sea level at [altM]. True airspeed = indicated / sqrt(this). */
    fun densityRatio(altM: Double): Double = (1.0 - altM / 44330.769).pow(4.25588)
}

/** Pressure in hPa at [altM] metres of ISA pressure altitude: the inverse of [Atmosphere.pressureAltitude]. */
fun Atmosphere.pressureAt(altM: Double): Double = Atmosphere.P0 * Math.pow(1.0 - altM / 44330.769, 1.0 / 0.190263)
