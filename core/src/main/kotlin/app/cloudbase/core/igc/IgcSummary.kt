package app.cloudbase.core.igc

import app.cloudbase.core.Geo
import java.io.BufferedReader
import java.io.Reader
import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * Headline numbers of an IGC file, for the flights list. [dateUtcMs] is the time of the first fix
 * (1970-01-01 plus the time of day if the file has no usable date header).
 */
class IgcSummary(
    val dateUtcMs: Long,
    val durationS: Int,
    val maxAltM: Int,
    val minAltM: Int,
    val distanceStraightM: Int,
    val trackLengthM: Int,
    val fixCount: Int,
    val pilot: String,
    val glider: String,
) {
    companion object {
        private const val DAY_S = 86_400
        private const val ROLLOVER_S = 12 * 3600

        /**
         * Reads a whole IGC file. Returns null if it holds no valid B record; never throws (an I/O
         * error mid-file just ends the read). Lines are streamed, nothing is kept but running totals.
         *
         * - Altitude is the GNSS altitude, or the pressure altitude where GNSS is 0 (no fix, or 'V').
         *   A fix with both 0 is counted but not used for min/max altitude.
         * - Midnight UTC rollover: a time of day that drops by more than 12 h starts a new day; a
         *   smaller backwards step is an out-of-order fix and is skipped.
         * - Dates: `HFDTEDATE:DDMMYY,NN`, `HFDTE:DDMMYY`, and old `HFDTEDDMMYY` all work.
         * - Fixes with validity 'V' are kept: a recorder repeats the last position for them, which adds
         *   no distance, and our own writer marks fixes without GNSS altitude that way.
         */
        fun read(reader: Reader): IgcSummary? {
            var dateDay = Long.MIN_VALUE // epoch day of the DTE header
            var pilot = ""
            var gliderType = ""
            var gliderId = ""

            var count = 0
            var firstSec = 0L
            var lastSec = 0L
            var prevTod = 0
            var dayOffset = 0L
            var firstLat = 0.0
            var firstLon = 0.0
            var lastLat = 0.0
            var lastLon = 0.0
            var track = 0.0
            var maxAlt = Int.MIN_VALUE
            var minAlt = Int.MAX_VALUE

            try {
                val br = if (reader is BufferedReader) reader else BufferedReader(reader)
                while (true) {
                    val raw = br.readLine() ?: break
                    val s = if (raw.startsWith("﻿")) raw.substring(1) else raw
                    if (s.isEmpty()) continue
                    when (s[0]) {
                        'B' -> {
                            if (s.length < 35) continue
                            val tod = digits(s, 1, 6)
                            val latMin = digits(s, 7, 7)
                            val lonMin = digits(s, 15, 8)
                            if (tod < 0 || latMin < 0 || lonMin < 0) continue
                            val hh = tod / 10000
                            val mm = tod / 100 % 100
                            val ss = tod % 100
                            val latDeg = latMin / 100000
                            val lonDeg = lonMin / 100000
                            if (hh > 23 || mm > 59 || ss > 60 || latDeg > 90 || lonDeg > 180) continue
                            val latM = latMin % 100000
                            val lonM = lonMin % 100000
                            if (latM >= 60000 || lonM >= 60000) continue
                            val ns = s[14]
                            val ew = s[23]
                            if ((ns != 'N' && ns != 'S') || (ew != 'E' && ew != 'W')) continue
                            val lat = (latDeg + latM / 60000.0) * (if (ns == 'S') -1 else 1)
                            val lon = (lonDeg + lonM / 60000.0) * (if (ew == 'W') -1 else 1)

                            val todS = hh * 3600 + mm * 60 + ss
                            if (count > 0) {
                                if (todS < prevTod) {
                                    if (prevTod - todS > ROLLOVER_S) dayOffset += DAY_S else continue
                                }
                            }
                            val pAlt = signedInt(s, 25)
                            val gAlt = signedInt(s, 30)
                            val alt = if (gAlt != 0 && gAlt != BAD) gAlt else if (pAlt != 0 && pAlt != BAD) pAlt else BAD
                            if (alt != BAD) {
                                if (alt > maxAlt) maxAlt = alt
                                if (alt < minAlt) minAlt = alt
                            }

                            val sec = dayOffset + todS
                            if (count == 0) {
                                firstSec = sec
                                firstLat = lat
                                firstLon = lon
                            } else {
                                track += Geo.distance(lastLat, lastLon, lat, lon)
                            }
                            lastSec = sec
                            lastLat = lat
                            lastLon = lon
                            prevTod = todS
                            count++
                        }
                        'H' -> if (s.length >= 5) when (s.substring(2, 5)) {
                            "DTE" -> parseDate(s)?.let { dateDay = it }
                            "PLT" -> pilot = headerValue(s)
                            "GTY" -> gliderType = headerValue(s)
                            "GID" -> gliderId = headerValue(s)
                        }
                    }
                }
            } catch (e: Exception) {
                // Truncated or unreadable file: summarise what we got.
            }

            if (count == 0) return null
            val day = if (dateDay == Long.MIN_VALUE) 0L else dateDay
            val noAlt = maxAlt == Int.MIN_VALUE
            return IgcSummary(
                dateUtcMs = day * 86_400_000L + firstSec * 1000L,
                durationS = (lastSec - firstSec).toInt(),
                maxAltM = if (noAlt) 0 else maxAlt,
                minAltM = if (noAlt) 0 else minAlt,
                distanceStraightM = Geo.distance(firstLat, firstLon, lastLat, lastLon).roundToInt(),
                trackLengthM = track.roundToInt(),
                fixCount = count,
                pilot = pilot,
                glider = gliderType.ifBlank { gliderId },
            )
        }

        private const val BAD = Int.MIN_VALUE

        /** [len] decimal digits at [at] as an Int, or -1 if any character is not a digit. */
        private fun digits(s: String, at: Int, len: Int): Int {
            var v = 0
            for (i in at until at + len) {
                val c = s[i]
                if (c < '0' || c > '9') return -1
                v = v * 10 + (c - '0')
            }
            return v
        }

        /** Five-character altitude field, "-0123" or "01234"; [BAD] if malformed. */
        private fun signedInt(s: String, at: Int): Int {
            if (s[at] == '-') {
                val v = digits(s, at + 1, 4)
                return if (v < 0) BAD else -v
            }
            val v = digits(s, at, 5)
            return if (v < 0) BAD else v
        }

        /** Epoch day from the first run of six digits after "DTE", so every header style parses. */
        private fun parseDate(s: String): Long? {
            var i = 5
            while (i + 6 <= s.length) {
                if (digits(s, i, 6) >= 0) {
                    val dd = digits(s, i, 2)
                    val mm = digits(s, i + 2, 2)
                    val yy = digits(s, i + 4, 2)
                    return try {
                        LocalDate.of(if (yy < 80) 2000 + yy else 1900 + yy, mm, dd).toEpochDay()
                    } catch (e: Exception) {
                        null
                    }
                }
                i++
            }
            return null
        }

        /** Text after the colon (or after the 3-letter code when there is none), trimmed. */
        private fun headerValue(s: String): String {
            val colon = s.indexOf(':')
            return (if (colon >= 0) s.substring(colon + 1) else s.substring(5)).trim()
        }
    }
}
