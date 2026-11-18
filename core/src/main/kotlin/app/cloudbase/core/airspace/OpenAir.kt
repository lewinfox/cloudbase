package app.cloudbase.core.airspace

import app.cloudbase.core.Atmosphere
import app.cloudbase.core.Geo
import java.io.BufferedReader
import java.io.IOException
import java.io.Reader
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Parser for OpenAir airspace files (including the OpenAir 2.0 `AY` extension as written by
 * openAIP). Never throws: problems become warnings (with line numbers) and the offending line or
 * airspace is skipped.
 *
 * Supported geometry records: `DP`, `DC`, `DA`, `DB` with `V X=` / `V D=`. `DY` (airway segments)
 * is unsupported and drops the whole airspace. Polygon edges are treated as straight lines in
 * lat/lon, as everywhere else in this module.
 */
object OpenAir {
    class Result(val airspaces: List<Airspace>, val warnings: List<String>)

    /** At most this many warnings are kept; later ones are silently dropped. */
    const val MAX_WARNINGS = 50

    private const val NM = 1852.0
    private const val MAX_CHORD_DEV_M = 15.0
    private const val MAX_STEP_DEG = 10.0
    private const val MAX_RADIUS_NM = 1000.0

    private const val K_V = 'V'.code
    private const val K_AC = ('A'.code shl 16) or 'C'.code
    private const val K_AY = ('A'.code shl 16) or 'Y'.code
    private const val K_AN = ('A'.code shl 16) or 'N'.code
    private const val K_AL = ('A'.code shl 16) or 'L'.code
    private const val K_AH = ('A'.code shl 16) or 'H'.code
    private const val K_AF = ('A'.code shl 16) or 'F'.code
    private const val K_DP = ('D'.code shl 16) or 'P'.code
    private const val K_DC = ('D'.code shl 16) or 'C'.code
    private const val K_DA = ('D'.code shl 16) or 'A'.code
    private const val K_DB = ('D'.code shl 16) or 'B'.code
    private const val K_DY = ('D'.code shl 16) or 'Y'.code

    /** Records that are valid OpenAir but carry nothing we use. */
    private val IGNORED = intArrayOf(
        ('A'.code shl 16) or 'G'.code, ('A'.code shl 16) or 'A'.code, ('A'.code shl 16) or 'X'.code,
        ('A'.code shl 16) or 'I'.code, ('S'.code shl 16) or 'P'.code, ('S'.code shl 16) or 'B'.code,
        ('A'.code shl 16) or 'T'.code, ('T'.code shl 16) or 'O'.code, ('T'.code shl 16) or 'C'.code,
    )

    fun parse(reader: Reader): Result = Parser().run(reader)

    /** Exposed for tests. Never throws; returns null if unparseable. */
    fun parseAltitude(text: String): AltLimit? {
        val t = text.trim()
        if (t.isEmpty()) return null
        val c = Cursor()
        c.reset(t, 0, t.length)
        val first = t[0]
        if (first.isLetter()) {
            val w = c.word()
            return when (w) {
                "GND", "SFC" -> if (c.atEnd()) AltLimit(AltRef.GND, 0.0, t) else null
                "MSL", "AMSL" -> if (c.atEnd()) AltLimit(AltRef.MSL, 0.0, t) else null
                "UNL", "UNLIM", "UNLIMITED" ->
                    if (c.atEnd()) AltLimit(AltRef.STD, Double.POSITIVE_INFINITY, t) else null
                "FL" -> {
                    c.skipWs()
                    if (c.number() && c.atEnd()) AltLimit(AltRef.STD, c.num * 100.0 * Atmosphere.FT, t) else null
                }
                else -> null
            }
        }
        var negative = false
        if (first == '-') {
            negative = true
            c.p++
        }
        if (!c.number()) return null
        val value = if (negative) -c.num else c.num
        c.skipWs()
        val w1 = c.word()
        c.skipWs()
        val unitIsMetres: Boolean
        val datum: String
        when (w1) {
            "FT" -> { unitIsMetres = false; c.skipWs(); datum = c.word() }
            "M" -> { unitIsMetres = true; c.skipWs(); datum = c.word() }
            else -> { unitIsMetres = false; datum = w1 } // "3500 MSL": feet by default
        }
        if (!c.atEnd()) return null
        val metres = if (unitIsMetres) value else value * Atmosphere.FT
        return when (datum) {
            "" -> {
                // A bare "0" means the surface; "0 ft" / "0 m" is explicitly sea level.
                if (value == 0.0 && w1.isEmpty()) AltLimit(AltRef.GND, 0.0, t) else AltLimit(AltRef.MSL, metres, t)
            }
            "AMSL", "MSL", "ALT" -> AltLimit(AltRef.MSL, metres, t)
            "AGL", "GND", "ASFC", "SFC" -> AltLimit(AltRef.GND, metres, t)
            else -> null
        }
    }

    /** Hand-rolled scanner over a slice of a string. No allocation on the number/coordinate paths. */
    private class Cursor {
        var s: String = ""
        var p = 0
        var end = 0

        // Outputs of the last successful call.
        var num = 0.0
        var lat = 0.0
        var lon = 0.0
        private var hadDot = false

        fun reset(s: String, from: Int, end: Int) {
            this.s = s; this.p = from; this.end = end
        }

        fun skipWs() {
            while (p < end && s[p].isWhitespace()) p++
        }

        fun atEnd(): Boolean {
            skipWs()
            return p == end
        }

        /** Skips whitespace and at most one comma. */
        fun skipSep() {
            skipWs()
            if (p < end && s[p] == ',') p++
            skipWs()
        }

        /** Reads a run of letters, upper-cased; "" when there is none. */
        fun word(): String {
            val start = p
            while (p < end && s[p].isLetter()) p++
            return if (p == start) "" else s.substring(start, p).uppercase()
        }

        /** Unsigned decimal number into [num]. */
        fun number(): Boolean {
            val start = p
            var v = 0.0
            var digits = 0
            while (p < end && s[p] in '0'..'9') {
                v = v * 10.0 + (s[p] - '0')
                p++; digits++
            }
            if (digits == 0) {
                p = start
                return false
            }
            hadDot = false
            if (p < end && s[p] == '.') {
                hadDot = true
                p++
                var scale = 0.1
                while (p < end && s[p] in '0'..'9') {
                    v += (s[p] - '0') * scale
                    scale /= 10.0
                    p++
                }
            }
            num = v
            return true
        }

        /** Optionally signed number into [num] (used for arc angles). */
        fun signedNumber(): Boolean {
            skipWs()
            var neg = false
            if (p < end && (s[p] == '-' || s[p] == '+')) {
                neg = s[p] == '-'
                p++
            }
            if (!number()) return false
            if (neg) num = -num
            return true
        }

        /** `deg[:min[:sec]]` with a decimal fraction only on the last component, into [num]. */
        private fun dms(): Boolean {
            if (!number()) return false
            var v = num
            if (!hadDot && p < end && s[p] == ':') {
                p++
                if (!number() || num >= 60.0) return false
                v += num / 60.0
                if (!hadDot && p < end && s[p] == ':') {
                    p++
                    if (!number() || num >= 60.0) return false
                    v += num / 3600.0
                }
            }
            num = v
            return true
        }

        /**
         * `lat N|S [,] lon E|W` into [lat]/[lon]; the hemisphere letters are mandatory because a
         * bare number is ambiguous. Out-of-range values fail.
         */
        fun coord(): Boolean {
            skipWs()
            if (!dms()) return false
            var la = num
            skipWs()
            if (p >= end) return false
            when (s[p]) {
                'N', 'n' -> {}
                'S', 's' -> la = -la
                else -> return false
            }
            p++
            if (la > 90.0 || la < -90.0) return false
            skipSep()
            if (!dms()) return false
            var lo = num
            skipWs()
            if (p >= end) return false
            when (s[p]) {
                'E', 'e' -> {}
                'W', 'w' -> lo = -lo
                else -> return false
            }
            p++
            if (lo > 180.0 || lo < -180.0) return false
            lat = la
            lon = lo
            return true
        }
    }

    private class Parser {
        private val out = ArrayList<Airspace>()
        private val warnings = ArrayList<String>()
        private val cur = Cursor()
        private var lineNo = 0

        // State of the airspace being built.
        private var started = false
        private var startLine = 0
        private var cls = ""
        private var type = ""
        private var name = ""
        private var freq: String? = null
        private var lower: AltLimit? = null
        private var upper: AltLimit? = null
        private var lowerBad = false
        private var upperBad = false
        private var dropped = false
        private var lats = DoubleArray(64)
        private var lons = DoubleArray(64)
        private var n = 0
        private var hasCentre = false
        private var cLat = 0.0
        private var cLon = 0.0
        private var clockwise = true

        fun run(reader: Reader): Result {
            val br = reader as? BufferedReader ?: BufferedReader(reader)
            try {
                while (true) {
                    var line = br.readLine() ?: break
                    lineNo++
                    if (lineNo == 1 && line.isNotEmpty() && line[0] == '﻿') line = line.substring(1)
                    try {
                        handle(line)
                    } catch (e: RuntimeException) {
                        // Should be unreachable; a safety instrument must not die on a bad file.
                        warn("internal error ($e); line skipped")
                    }
                }
            } catch (e: IOException) {
                warn("read error: ${e.message}")
            }
            try {
                finish()
            } catch (e: RuntimeException) {
                warn("internal error ($e) finishing last airspace")
            }
            return Result(out, warnings)
        }

        private fun warn(msg: String) = warnAt(lineNo, msg)

        private fun warnAt(line: Int, msg: String) {
            if (warnings.size < MAX_WARNINGS) warnings.add("line $line: $msg")
        }

        private fun handle(line: String) {
            val len = line.length
            var i = 0
            while (i < len && line[i].isWhitespace()) i++
            if (i == len || line[i] == '*') return
            var e = i
            while (e < len && !line[e].isWhitespace()) e++
            var r = e
            while (r < len && line[r].isWhitespace()) r++
            var last = len
            while (last > r && line[last - 1].isWhitespace()) last--

            val tokenLen = e - i
            val key = when (tokenLen) {
                1 -> line[i].uppercaseChar().code
                2 -> (line[i].uppercaseChar().code shl 16) or line[i + 1].uppercaseChar().code
                else -> -1
            }
            if (key == K_AC) {
                finish()
                started = true
                startLine = lineNo
                cls = line.substring(r, last).uppercase()
                if (cls.isEmpty()) warn("AC without a class")
                return
            }
            if (key in IGNORED) return
            if (key != K_V && key != K_AY && key != K_AN && key != K_AL && key != K_AH && key != K_AF &&
                key != K_DP && key != K_DC && key != K_DA && key != K_DB && key != K_DY
            ) {
                warn("unknown record '${line.substring(i, e)}'")
                return
            }
            if (!started) {
                warn("'${line.substring(i, e)}' before the first AC; ignored")
                return
            }
            when (key) {
                K_AY -> {
                    val v = line.substring(r, last).uppercase()
                    type = if (v == "NONE") "" else v
                }
                K_AN -> name = line.substring(r, last)
                K_AL -> {
                    val txt = line.substring(r, last)
                    lower = parseAltitude(txt)
                    lowerBad = lower == null
                    if (lowerBad) warn("unparseable AL '$txt'")
                }
                K_AH -> {
                    val txt = line.substring(r, last)
                    upper = parseAltitude(txt)
                    upperBad = upper == null
                    if (upperBad) warn("unparseable AH '$txt'")
                }
                K_AF -> freq = if (r < last) line.substring(r, last) else null
                K_DY -> if (!dropped) {
                    dropped = true
                    warn("DY (airway) is unsupported; dropping airspace '$name'")
                }
                else -> if (!dropped) geometry(key, line, r, last)
            }
        }

        private fun geometry(key: Int, line: String, r: Int, last: Int) {
            cur.reset(line, r, last)
            when (key) {
                K_V -> variable(line, r, last)
                K_DP -> {
                    if (cur.coord() && cur.atEnd()) addPoint(cur.lat, cur.lon)
                    else warn("bad DP coordinate '${line.substring(r, last)}'")
                }
                K_DC -> {
                    if (!hasCentre) return warn("DC without V X=; ignored")
                    if (!(cur.number() && cur.atEnd())) return warn("bad DC radius '${line.substring(r, last)}'")
                    val radius = checkedRadius(cur.num * NM) ?: return
                    val step = stepDeg(radius)
                    val steps = ceil(360.0 / step).toInt()
                    addArcPoints(radius, 0.0, 360.0, steps, includeFirst = true, includeLast = false)
                }
                K_DA -> {
                    if (!hasCentre) return warn("DA without V X=; ignored")
                    if (!cur.number()) return warn("bad DA '${line.substring(r, last)}'")
                    val radiusNm = cur.num
                    cur.skipSep()
                    if (!cur.signedNumber()) return warn("bad DA '${line.substring(r, last)}'")
                    val startDeg = cur.num
                    cur.skipSep()
                    if (!cur.signedNumber() || !cur.atEnd()) return warn("bad DA '${line.substring(r, last)}'")
                    val endDeg = cur.num
                    val radius = checkedRadius(radiusNm * NM) ?: return
                    arc(radius, startDeg, endDeg, includeFirst = true, includeLast = true)
                }
                K_DB -> {
                    if (!hasCentre) return warn("DB without V X=; ignored")
                    if (!cur.coord()) return warn("bad DB '${line.substring(r, last)}'")
                    val lat1 = cur.lat
                    val lon1 = cur.lon
                    cur.skipSep()
                    if (!cur.coord() || !cur.atEnd()) return warn("bad DB '${line.substring(r, last)}'")
                    val lat2 = cur.lat
                    val lon2 = cur.lon
                    val radius = checkedRadius(Geo.distance(cLat, cLon, lat1, lon1)) ?: return
                    addPoint(lat1, lon1)
                    arc(
                        radius,
                        Geo.bearing(cLat, cLon, lat1, lon1),
                        Geo.bearing(cLat, cLon, lat2, lon2),
                        includeFirst = false,
                        includeLast = false,
                    )
                    addPoint(lat2, lon2)
                }
            }
        }

        private fun variable(line: String, r: Int, last: Int) {
            // cur is already positioned at the start of the argument: "X=lat lon", "D=+", "W=5", "Z=..."
            if (r >= last) return warn("empty V record")
            val k = line[r].uppercaseChar()
            cur.p = r + 1
            cur.skipWs()
            if (cur.p >= last || line[cur.p] != '=') return warn("bad V record '${line.substring(r, last)}'")
            cur.p++
            cur.skipWs()
            when (k) {
                'X' -> {
                    if (cur.coord() && cur.atEnd()) {
                        hasCentre = true
                        cLat = cur.lat
                        cLon = cur.lon
                    } else {
                        warn("bad V X= coordinate '${line.substring(r, last)}'")
                    }
                }
                'D' -> when (if (cur.p < last) line[cur.p] else ' ') {
                    '+' -> clockwise = true
                    '-' -> clockwise = false
                    else -> warn("bad V D= value '${line.substring(r, last)}'")
                }
                'W', 'Z' -> {}
                else -> warn("unknown V record '${line.substring(r, last)}'")
            }
        }

        private fun checkedRadius(metres: Double): Double? {
            if (metres > 0.0 && metres <= MAX_RADIUS_NM * NM) return metres
            warn("radius out of range; ignored")
            return null
        }

        /** Largest step (degrees) keeping the chord within [MAX_CHORD_DEV_M] of the arc: sagitta r(1-cos(step/2)). */
        private fun stepDeg(radius: Double): Double {
            val x = (1.0 - MAX_CHORD_DEV_M / radius).coerceAtLeast(-1.0)
            return min(MAX_STEP_DEG, 2.0 * acos(x) / Geo.DEG)
        }

        /** Arc round the centre from bearing [startDeg] to [endDeg] in the current direction. */
        private fun arc(radius: Double, startDeg: Double, endDeg: Double, includeFirst: Boolean, includeLast: Boolean) {
            val d = endDeg - startDeg
            // A sweep that normalises to 0 but whose raw angles differ (e.g. 0..360) is a full circle.
            val travel = Geo.norm360(if (clockwise) d else -d).let { if (it == 0.0 && d != 0.0) 360.0 else it }
            val sweep = if (clockwise) travel else -travel
            val steps = max(1, ceil(abs(sweep) / stepDeg(radius)).toInt())
            addArcPoints(radius, startDeg, sweep, steps, includeFirst, includeLast)
        }

        private fun addArcPoints(
            radius: Double, startDeg: Double, sweepDeg: Double, steps: Int,
            includeFirst: Boolean, includeLast: Boolean,
        ) {
            val from = if (includeFirst) 0 else 1
            val to = if (includeLast) steps else steps - 1
            for (k in from..to) {
                val p = Geo.destination(cLat, cLon, startDeg + sweepDeg * k / steps, radius)
                addPoint(p.lat, p.lon)
            }
        }

        /** Appends a vertex unless it equals the previous one. */
        private fun addPoint(lat: Double, lon: Double) {
            if (n > 0 && lats[n - 1] == lat && lons[n - 1] == lon) return
            if (n == lats.size) {
                lats = lats.copyOf(n * 2)
                lons = lons.copyOf(n * 2)
            }
            lats[n] = lat
            lons[n] = lon
            n++
        }

        /** Finalises the airspace in progress, if any, and resets per-airspace state. */
        private fun finish() {
            if (!started) return
            try {
                emit()
            } finally {
                started = false
                dropped = false
                cls = ""; type = ""; name = ""; freq = null
                lower = null; upper = null; lowerBad = false; upperBad = false
                n = 0
                hasCentre = false
                clockwise = true // direction resets for every airspace
            }
        }

        private fun emit() {
            if (dropped) return
            var count = n
            while (count > 1 && lats[count - 1] == lats[0] && lons[count - 1] == lons[0]) count-- // closing vertex
            if (count < 3) {
                warnAt(startLine, "airspace '$name' has only $count distinct vertices; dropped")
                return
            }
            val floor = lower ?: AltLimit.SURFACE.also {
                if (!lowerBad) warnAt(startLine, "airspace '$name' has no AL; assuming GND")
            }
            val ceiling = upper ?: AltLimit.UNLIMITED.also {
                if (!upperBad) warnAt(startLine, "airspace '$name' has no AH; assuming unlimited")
            }
            out.add(Airspace(name, cls, type, floor, ceiling, lats.copyOf(count), lons.copyOf(count), freq))
        }
    }
}
