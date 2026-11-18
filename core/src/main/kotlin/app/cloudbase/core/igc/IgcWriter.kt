package app.cloudbase.core.igc

import java.io.Writer
import java.text.Normalizer
import java.time.LocalDate

/** Values for the IGC header. Free text; [IgcWriter] sanitises it. */
class IgcHeader(
    val pilot: String,
    val gliderType: String,
    val gliderId: String,
    val appVersion: String,
    val deviceModel: String,
    val hasPressureSensor: Boolean,
)

/**
 * Writes an IGC flight log (FAI/IGC GNSS FR Technical Specification, Appendix A, 2nd ed. AL9) as a
 * NON-approved recorder: manufacturer code `XCB` (the spec's "XYY" form for non-approved devices)
 * and serial `001`.
 *
 * Written records: A, H, B. No I, E, F, J, K, L or **G record**: a valid G (digital signature)
 * needs a private key issued by an approved manufacturer, which we do not have, so none is written
 * and the file will fail IGC VALI validation. That is intended; XContest, SeeYou, Leonardo and
 * viewers read such files fine as unsigned.
 *
 * GNSS altitude is written as MSL (geoid) altitude and declared `HFALGALTGPS:GEO`. The spec's
 * mandatory datum for approved FRs is the WGS84 ellipsoid (`ELL`), but it explicitly provides `GEO`
 * for non-approved recorders. Pressure altitude is ISA (1013.25 hPa), or declared `NIL` and
 * zero-filled when the device has no barometer.
 *
 * Lines end in CRLF. Not thread-safe. [fix] writes through a reused char buffer: no per-fix
 * allocation when [out] is a [Writer] or a [StringBuilder] (other Appendables get one String).
 */
class IgcWriter(private val out: Appendable) {
    private val line = CharArray(LINE_LEN).also {
        it[0] = 'B'
        it[LINE_LEN - 2] = '\r'
        it[LINE_LEN - 1] = '\n'
    }

    /**
     * Writes the A and H records. [dateUtcMs] is the time of the first fix; its UTC date is the
     * flight date. [flightOfDay] is the 1-based flight number of that UTC day (DTE record).
     */
    fun header(h: IgcHeader, dateUtcMs: Long, flightOfDay: Int = 1) {
        val date = LocalDate.ofEpochDay(Math.floorDiv(dateUtcMs, DAY_MS))
        val ddmmyy = StringBuilder(8)
        pad(ddmmyy, date.dayOfMonth, 2)
        pad(ddmmyy, date.monthValue, 2)
        pad(ddmmyy, date.year % 100, 2)
        record("A$MANUFACTURER$SERIAL-Cloudbase")
        record("HFDTEDATE:$ddmmyy,${twoDigits(flightOfDay)}")
        text("HFPLTPILOTINCHARGE:", h.pilot)
        record("HFCM2CREW2:")
        text("HFGTYGLIDERTYPE:", h.gliderType)
        text("HFGIDGLIDERID:", h.gliderId)
        record("HFDTMGPSDATUM:WGS84")
        text("HFRFWFIRMWAREVERSION:", h.appVersion)
        text("HFRHWHARDWAREVERSION:", h.deviceModel)
        record("HFFTYFRTYPE:Cloudbase,Android")
        // The spec wants "maker,model,channels,max alt"; Android does not tell us the channel count.
        text("HFGPSRECEIVER:", "Android,${sanitize(h.deviceModel, 30).replace(',', ' ')},0,99999")
        if (h.hasPressureSensor) {
            text("HFPRSPRESSALTSENSOR:", "Android,${sanitize(h.deviceModel, 30).replace(',', ' ')},9999")
        } else {
            record("HFPRSPRESSALTSENSOR:NIL")
        }
        record("HFALGALTGPS:GEO")
        record(if (h.hasPressureSensor) "HFALPALTPRESSURE:ISA" else "HFALPALTPRESSURE:NIL")
    }

    /**
     * One B record. [pressureAltM] is ISA pressure altitude, NaN if there is no sensor (writes
     * 00000). [gpsAltMslM] NaN means no GNSS altitude: fix validity V and 00000. A NaN or infinite
     * [lat]/[lon] writes nothing (there is no valid B record for a fix without a position).
     * Altitudes are rounded to metres and clamped to what 5 characters hold (-9999..99999).
     */
    fun fix(timeUtcMs: Long, lat: Double, lon: Double, pressureAltM: Double, gpsAltMslM: Double) {
        if (!lat.isFinite() || !lon.isFinite()) return
        val b = line
        // Rounded, not truncated: some receivers stamp fixes a few ms before the whole second.
        val sec = (Math.floorMod(timeUtcMs + 500, DAY_MS) / 1000).toInt()
        put(b, 1, sec / 3600, 2)
        put(b, 3, sec / 60 % 60, 2)
        put(b, 5, sec % 60, 2)

        // Thousandths of a minute, rounded once as a whole so that 59.9996' carries into the degrees.
        val latMin = Math.round(Math.abs(lat.coerceIn(-90.0, 90.0)) * 60000.0)
        put(b, 7, (latMin / 60000).toInt(), 2)
        put(b, 9, (latMin % 60000).toInt(), 5)
        b[14] = if (lat < 0) 'S' else 'N'

        val lonWrapped = lon - Math.floor((lon + 180.0) / 360.0) * 360.0 // [-180, 180)
        val lonMin = Math.round(Math.abs(lonWrapped) * 60000.0)
        put(b, 15, (lonMin / 60000).toInt(), 3)
        put(b, 18, (lonMin % 60000).toInt(), 5)
        b[23] = if (lonWrapped < 0) 'W' else 'E'

        b[24] = if (gpsAltMslM.isNaN()) 'V' else 'A'
        putAlt(b, 25, pressureAltM)
        putAlt(b, 30, gpsAltMslM)

        when (out) {
            is Writer -> out.write(b, 0, LINE_LEN)
            is StringBuilder -> out.append(b, 0, LINE_LEN)
            else -> out.append(String(b, 0, LINE_LEN))
        }
    }

    private fun record(s: String) {
        out.append(s).append("\r\n")
    }

    /** Header line with a sanitised value, cut so the whole line stays within the spec's 76 chars. */
    private fun text(prefix: String, value: String) {
        record(prefix + sanitize(value, MAX_LINE - prefix.length))
    }

    companion object {
        private const val MANUFACTURER = "XCB"
        private const val SERIAL = "001"
        private const val DAY_MS = 86_400_000L
        private const val LINE_LEN = 37 // B + 6 + 8 + 9 + 1 + 5 + 5 = 35, plus CRLF
        private const val MAX_LINE = 76
        private const val RESERVED = "!$*\\^~"

        /** Long IGC file name, e.g. `2026-11-18-XCB-001-01.igc`; [seq] is the flight number of the UTC day, 1-based. */
        fun fileName(dateUtcMs: Long, seq: Int): String {
            val d = LocalDate.ofEpochDay(Math.floorDiv(dateUtcMs, DAY_MS))
            val sb = StringBuilder(26)
            pad(sb, d.year, 4)
            sb.append('-')
            pad(sb, d.monthValue, 2)
            sb.append('-')
            pad(sb, d.dayOfMonth, 2)
            return sb.append('-').append(MANUFACTURER).append('-').append(SERIAL).append('-')
                .append(twoDigits(seq.coerceAtLeast(1))).append(".igc").toString()
        }

        /**
         * Makes free text legal for an IGC record (spec A6: printable ASCII 0x20..0x7D, minus the
         * reserved `! $ * \ ^ ~`). Accents are stripped (é becomes e), control characters and
         * newlines become spaces, anything else non-ASCII or reserved becomes `_`. Trimmed, and
         * cut to [maxLen] characters.
         */
        internal fun sanitize(s: String, maxLen: Int): String {
            val sb = StringBuilder(s.length)
            val decomposed = Normalizer.normalize(s, Normalizer.Form.NFD)
            var i = 0
            while (i < decomposed.length) {
                val cp = decomposed.codePointAt(i)
                i += Character.charCount(cp)
                when {
                    Character.getType(cp) == Character.NON_SPACING_MARK.toInt() -> {}
                    cp < 0x20 || cp == 0x7F -> sb.append(' ')
                    cp > 0x7D || RESERVED.indexOf(cp.toChar()) >= 0 -> sb.append('_')
                    else -> sb.append(cp.toChar())
                }
            }
            val t = sb.toString().trim()
            return if (t.length > maxLen) t.substring(0, maxLen).trimEnd() else t
        }

        private fun twoDigits(n: Int): String = if (n in 0..9) "0$n" else n.toString()

        private fun pad(sb: StringBuilder, n: Int, width: Int) {
            val s = n.toString()
            for (k in s.length until width) sb.append('0')
            sb.append(s)
        }

        /** Writes non-negative [v] as exactly [width] digits at [at]. */
        private fun put(b: CharArray, at: Int, v: Int, width: Int) {
            var x = v
            for (k in width - 1 downTo 0) {
                b[at + k] = '0' + x % 10
                x /= 10
            }
        }

        /** Five-character altitude: zero padded, or '-' plus four digits when negative; NaN is 00000. */
        private fun putAlt(b: CharArray, at: Int, altM: Double) {
            if (altM.isNaN()) {
                put(b, at, 0, 5)
                return
            }
            val m = Math.round(altM.coerceIn(-9999.0, 99999.0)).toInt()
            if (m < 0) {
                b[at] = '-'
                put(b, at + 1, -m, 4)
            } else {
                put(b, at, m, 5)
            }
        }
    }
}
