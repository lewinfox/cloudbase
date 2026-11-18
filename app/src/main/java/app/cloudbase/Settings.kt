package app.cloudbase

import android.content.Context

/** All user settings, in one small SharedPreferences file. */
class Settings(context: Context) {
    private val p = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var pilot: String by Str("pilot", "")
    var glider: String by Str("glider", "")
    var gliderId: String by Str("glider_id", "")
    /** Trim (hands-up) airspeed in km/h; only used to estimate wind before the first full circle. */
    var trimKmh: Double by Num("trim_kmh", 38.0)
    /** Sink rate at trim, m/s; used to turn climb rate into air-mass lift for the thermal map. */
    var trimSink: Double by Num("trim_sink", 1.1)
    var altFeet: Boolean by Bool("alt_feet", false)
    /** "kmh", "kt" or "mph". */
    var speedUnit: String by Str("speed_unit", "kmh")
    var alertSound: Boolean by Bool("alert_sound", true)
    var alertVoice: Boolean by Bool("alert_voice", true)
    var horizontalMarginM: Double by Num("margin_h", 300.0)
    var verticalMarginM: Double by Num("margin_v", 100.0)
    var lookaheadS: Double by Num("lookahead", 60.0)
    var autoRecord: Boolean by Bool("auto_record", true)
    var keepScreenOn: Boolean by Bool("keep_screen_on", true)
    var showSideView: Boolean by Bool("side_view", true)
    var showThermals: Boolean by Bool("thermals", true)
    var demo: Boolean by Bool("demo", false)
    // Until the first GPS fix the map opens on New Zealand, which is where this app is flown.
    var mapLat: Double by Num("map_lat", -41.5, allowNonPositive = true)
    var mapLon: Double by Num("map_lon", 173.0, allowNonPositive = true)
    var mapZoom: Double by Num("map_zoom", 6.0)
    /** False until the first-run airspace download has been attempted successfully. */
    var airspaceSeeded: Boolean by Bool("airspace_seeded", false)

    /** Country codes whose airspace files are loaded. */
    var countries: Set<String>
        get() = p.getStringSet("countries", emptySet())!!.toSet()
        set(v) = p.edit().putStringSet("countries", v).apply()

    /** Airspace names the pilot has chosen to silence and hide. */
    var ignoredAirspaces: Set<String>
        get() = p.getStringSet("ignored", emptySet())!!.toSet()
        set(v) = p.edit().putStringSet("ignored", v).apply()

    private inner class Str(val key: String, val def: String) {
        operator fun getValue(t: Any?, k: Any?): String = p.getString(key, def) ?: def
        operator fun setValue(t: Any?, k: Any?, v: String) = p.edit().putString(key, v).apply()
    }

    private inner class Bool(val key: String, val def: Boolean) {
        operator fun getValue(t: Any?, k: Any?): Boolean = p.getBoolean(key, def)
        operator fun setValue(t: Any?, k: Any?, v: Boolean) = p.edit().putBoolean(key, v).apply()
    }

    private inner class Num(val key: String, val def: Double, val allowNonPositive: Boolean = false) {
        operator fun getValue(t: Any?, k: Any?): Double =
            p.getString(key, null)?.toDoubleOrNull()?.takeIf { it.isFinite() && (allowNonPositive || it > 0) } ?: def
        operator fun setValue(t: Any?, k: Any?, v: Double) = p.edit().putString(key, v.toString()).apply()
    }
}
