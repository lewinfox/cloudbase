package app.cloudbase.core.igc

import app.cloudbase.core.Fix
import app.cloudbase.core.Geo
import java.io.BufferedReader
import java.io.Reader
import java.time.LocalDate

/**
 * A recorded flight read back from an IGC file, fix by fix, for replaying through the flight
 * computer (the demo, and tests against real flying).
 *
 * Ground speed and track come from the file's own GSP (km/h) and TRT/CCO (degrees) extension
 * fields when its I record declares them: those are the receiver's Doppler velocity. Otherwise
 * they are worked out from positions two fixes either side, which is noisier, because IGC
 * positions are only recorded to about 1.9 m.
 */
class IgcTrack private constructor(
    private val timeMs: LongArray,
    private val lat: DoubleArray,
    private val lon: DoubleArray,
    private val pressureAlt: DoubleArray,
    private val gpsAlt: DoubleArray,
    private val speed: DoubleArray,
    private val track: DoubleArray,
    /** Whether the file carries pressure altitude (a barometer was recording). */
    val hasPressure: Boolean,
) {
    val size: Int get() = timeMs.size

    fun timeMs(i: Int): Long = timeMs[i]

    /** ISA pressure altitude at fix [i], metres; NaN if the file has none. */
    fun pressureAlt(i: Int): Double = if (hasPressure) pressureAlt[i] else Double.NaN

    fun fix(i: Int): Fix = Fix(
        timeMs[i], lat[i], lon[i], gpsAlt[i], speed[i],
        // Like a live receiver: no meaningful direction when standing still.
        if (speed[i] > MIN_SPEED_FOR_TRACK) track[i] else Double.NaN,
    )

    companion object {
        private const val MIN_SPEED_FOR_TRACK = 0.7

        /** Null if the file has fewer than two usable fixes. Never throws on malformed input. */
        fun read(reader: Reader): IgcTrack? {
            var day = 0L
            var gsp: IntRange? = null
            var trt: IntRange? = null
            val t = ArrayList<Long>(); val la = ArrayList<Double>(); val lo = ArrayList<Double>()
            val pa = ArrayList<Double>(); val ga = ArrayList<Double>(); val sp = ArrayList<Double>(); val tr = ArrayList<Double>()
            var prevTod = -1
            var dayOffset = 0L
            try {
                val br = if (reader is BufferedReader) reader else BufferedReader(reader)
                while (true) {
                    val s = br.readLine()?.trim() ?: break
                    if (s.isEmpty()) continue
                    when (s[0]) {
                        'H' -> if (s.length >= 5 && s.startsWith("DTE", 2)) date(s)?.let { day = it }
                        'I' -> {
                            // Innssee CCC...: n extensions, each start and end column (1-based) and a 3-letter code.
                            var p = 3
                            while (p + 7 <= s.length) {
                                val a = s.substring(p, p + 2).toIntOrNull()
                                val b = s.substring(p + 2, p + 4).toIntOrNull()
                                if (a != null && b != null && b >= a) when (s.substring(p + 4, p + 7)) {
                                    "GSP" -> gsp = a - 1 until b
                                    "TRT", "CCO" -> if (trt == null || s.startsWith("TRT", p + 4)) trt = a - 1 until b
                                }
                                p += 7
                            }
                        }
                        'B' -> {
                            if (s.length < 35) continue
                            val tod = num(s, 1, 6); val latM = num(s, 7, 7); val lonM = num(s, 15, 8)
                            if (tod < 0 || latM < 0 || lonM < 0) continue
                            val sec = tod / 10000 * 3600 + tod / 100 % 100 * 60 + tod % 100
                            if (prevTod >= 0 && sec < prevTod) {
                                if (prevTod - sec > 12 * 3600) dayOffset += 86_400 else continue // out of order
                            }
                            if (prevTod >= 0 && sec == prevTod && dayOffset == 0L && t.isNotEmpty()) continue // duplicate second
                            prevTod = sec
                            t.add((day * 86_400 + dayOffset + sec) * 1000)
                            la.add((latM / 100000 + latM % 100000 / 60000.0) * if (s[14] == 'S') -1 else 1)
                            lo.add((lonM / 100000 + lonM % 100000 / 60000.0) * if (s[23] == 'W') -1 else 1)
                            pa.add(alt(s, 25)); ga.add(alt(s, 30))
                            sp.add(field(s, gsp)?.let { it / 3.6 } ?: Double.NaN)
                            tr.add(field(s, trt) ?: Double.NaN)
                        }
                    }
                }
            } catch (e: Exception) {
                // Truncated file: replay what was read.
            }
            val n = t.size
            if (n < 2) return null
            val lat = la.toDoubleArray(); val lon = lo.toDoubleArray()
            val speed = sp.toDoubleArray(); val track = tr.toDoubleArray()
            for (i in 0 until n) {
                if (!speed[i].isNaN() && !track[i].isNaN()) continue
                // Central difference over up to two fixes either side.
                val a = maxOf(0, i - 2); val b = minOf(n - 1, i + 2)
                val dt = (t[b] - t[a]) / 1000.0
                if (dt <= 0) { speed[i] = 0.0; track[i] = Double.NaN; continue }
                if (speed[i].isNaN()) speed[i] = Geo.distance(lat[a], lon[a], lat[b], lon[b]) / dt
                if (track[i].isNaN()) track[i] = Geo.bearing(lat[a], lon[a], lat[b], lon[b])
            }
            val pAlt = pa.toDoubleArray()
            return IgcTrack(t.toLongArray(), lat, lon, pAlt, ga.toDoubleArray(), speed, track, hasPressure = pAlt.any { it != 0.0 })
        }

        private fun num(s: String, from: Int, len: Int): Int {
            var v = 0
            for (i in from until from + len) { val d = s[i] - '0'; if (d < 0 || d > 9) return -1; v = v * 10 + d }
            return v
        }

        private fun alt(s: String, at: Int): Double {
            val neg = s[at] == '-'
            val v = num(s, if (neg) at + 1 else at, if (neg) 4 else 5)
            return if (v < 0) 0.0 else if (neg) -v.toDouble() else v.toDouble()
        }

        private fun field(s: String, r: IntRange?): Double? {
            if (r == null || r.last >= s.length) return null
            val v = num(s, r.first, r.last - r.first + 1)
            return if (v < 0) null else v.toDouble()
        }

        /** HFDTEDATE:DDMMYY,NN, HFDTE:DDMMYY or HFDTEDDMMYY → epoch day. */
        private fun date(s: String): Long? {
            val digits = s.substring(5).dropWhile { !it.isDigit() }.takeWhile { it.isDigit() }
            if (digits.length < 6) return null
            val d = digits.substring(0, 2).toInt(); val m = digits.substring(2, 4).toInt(); val y = digits.substring(4, 6).toInt()
            return try { LocalDate.of(if (y < 80) 2000 + y else 1900 + y, m, d).toEpochDay() } catch (e: Exception) { null }
        }
    }
}
