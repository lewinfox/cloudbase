package app.cloudbase.core

/**
 * One GPS fix. Altitude is metres above mean sea level (the geoid), already corrected from the
 * ellipsoid height Android reports; NaN when unknown. [track] is degrees true, NaN when the
 * receiver cannot tell (standing still).
 */
data class Fix(
    val timeMs: Long,
    val lat: Double,
    val lon: Double,
    val altMsl: Double,
    val speed: Double,
    val track: Double,
    /** Receiver's horizontal accuracy, metres (68 %); NaN when it does not say. */
    val hAcc: Float = Float.NaN,
    /** Receiver's vertical accuracy, metres (68 %); NaN when it does not say. */
    val vAcc: Float = Float.NaN,
)

/** Wind as conventionally reported: the direction it blows FROM, degrees true, and speed in m/s. */
data class Wind(val fromDeg: Double, val speed: Double)
