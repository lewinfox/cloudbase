package app.cloudbase.core.igc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.Reader
import java.io.StringReader
import java.time.Instant

class IgcSummaryTest {
    private val nan = Double.NaN
    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()
    private fun read(s: String) = IgcSummary.read(StringReader(s))

    private val header = IgcHeader("Jane Doe", "Ozone Rush 5", "D-1234", "0.1.0", "Pixel 8", true)

    @Test
    fun roundTripFromWriter() {
        val t0 = ms("2026-11-18T10:00:00Z")
        val sb = StringBuilder()
        val w = IgcWriter(sb)
        w.header(header, t0)
        // up the 7.0E meridian in 0.1 deg steps (11119.5 m each), then back one step
        w.fix(t0, 46.0, 7.0, 990.0, 1000.4)
        w.fix(t0 + 600_000, 46.1, 7.0, 2490.0, 2500.0)
        w.fix(t0 + 1_200_000, 46.2, 7.0, 1790.0, 1800.0)
        w.fix(t0 + 1_830_000, 46.1, 7.0, 690.0, 700.6)
        val s = read(sb.toString())!!
        assertEquals(t0, s.dateUtcMs)
        assertEquals(1830, s.durationS)
        assertEquals(2500, s.maxAltM)
        assertEquals(701, s.minAltM)
        assertEquals(11120, s.distanceStraightM) // 0.1 deg of latitude = 11119.51 m
        assertEquals(33359, s.trackLengthM) // 3 legs
        assertEquals(4, s.fixCount)
        assertEquals("Jane Doe", s.pilot)
        assertEquals("Ozone Rush 5", s.glider)
    }

    @Test
    fun fallsBackToPressureAltitudeWhenGnssIsZero() {
        val t0 = ms("2026-11-18T10:00:00Z")
        val sb = StringBuilder()
        val w = IgcWriter(sb)
        w.header(header, t0)
        w.fix(t0, 46.0, 7.0, 800.0, nan) // V fix, no GNSS altitude
        w.fix(t0 + 1000, 46.0, 7.0, 1200.0, nan)
        w.fix(t0 + 2000, 46.0, 7.0, 1000.0, 1100.0) // GNSS wins over pressure
        val s = read(sb.toString())!!
        assertEquals(1200, s.maxAltM)
        assertEquals(800, s.minAltM)
    }

    @Test
    fun fixWithoutAnyAltitudeIsCountedButNotUsedForMinMax() {
        val t0 = ms("2026-11-18T10:00:00Z")
        val sb = StringBuilder()
        val w = IgcWriter(sb)
        w.header(IgcHeader("P", "G", "R", "1", "D", false), t0)
        w.fix(t0, 46.0, 7.0, nan, nan)
        w.fix(t0 + 1000, 46.0, 7.0, nan, 500.0)
        val s = read(sb.toString())!!
        assertEquals(2, s.fixCount)
        assertEquals(500, s.maxAltM)
        assertEquals(500, s.minAltM)
        val none = read("HFDTEDATE:181126,01\r\n" + "B1200004600000N00700000EV0000000000\r\n")!!
        assertEquals(0, none.maxAltM)
        assertEquals(0, none.minAltM)
    }

    @Test
    fun negativeAltitudes() {
        val t0 = ms("2026-11-18T10:00:00Z")
        val sb = StringBuilder()
        val w = IgcWriter(sb)
        w.header(header, t0)
        w.fix(t0, 31.5, 35.5, nan, -400.0) // Dead Sea shore
        w.fix(t0 + 1000, 31.5, 35.5, nan, -380.0)
        val s = read(sb.toString())!!
        assertEquals(-380, s.maxAltM)
        assertEquals(-400, s.minAltM)
    }

    @Test
    fun midnightRollover() {
        val t0 = ms("2026-11-18T23:50:00Z")
        val sb = StringBuilder()
        val w = IgcWriter(sb)
        w.header(header, t0)
        w.fix(t0, 46.0, 7.0, 0.0, 1000.0)
        w.fix(t0 + 540_000, 46.01, 7.0, 0.0, 1100.0) // 23:59:00
        w.fix(t0 + 660_000, 46.02, 7.0, 0.0, 1200.0) // 00:01:00 next day
        w.fix(t0 + 1_200_000, 46.03, 7.0, 0.0, 1300.0) // 00:10:00
        val s = read(sb.toString())!!
        assertEquals(t0, s.dateUtcMs)
        assertEquals(1200, s.durationS)
        assertEquals(4, s.fixCount)
    }

    @Test
    fun smallBackwardTimeStepIsSkippedNotARollover() {
        val s = read(
            "HFDTEDATE:181126,01\n" +
                "B1000004600000N00700000EA0000001000\n" +
                "B1000104600000N00700000EA0000001000\n" +
                "B1000054600000N00700000EA0000001000\n" + // out of order
                "B1000204600000N00700000EA0000001000\n",
        )!!
        assertEquals(3, s.fixCount)
        assertEquals(20, s.durationS)
    }

    @Test
    fun foreignFileWithOldStyleHeadersAndIRecord() {
        // Old-style date header, an I record with extension fields, comment and G records.
        val igc = """
            AXXX123 Some Logger
            HFDTE010203
            HFFXA035
            HFPLTPILOT:Hans Muster
            HFCM2CREW2:
            HFGTYGLIDERTYPE:Advance Alpha
            HFGIDGLIDERID:
            HFDTM100GPSDATUM:WGS-1984
            HFRFWFIRMWAREVERSION:1.0
            HFFTYFRTYPE:FOO,BAR
            I033638FXA3940SIU4143ENL
            B1101355206343N00006198WA0058700558279
            B1101455206362N00006185WA0059300560281
            B1101555206379N00006171WA0059900565282
            LPLTcomment line
            G4C6F1E1DAE1E8EFEB1A1A9C3E8E1C9D
        """.trimIndent().replace("\n", "\r\n")
        val s = read(igc)!!
        assertEquals(ms("2003-02-01T11:01:35Z"), s.dateUtcMs)
        assertEquals(20, s.durationS)
        assertEquals(565, s.maxAltM)
        assertEquals(558, s.minAltM)
        assertEquals(3, s.fixCount)
        assertEquals("Hans Muster", s.pilot)
        assertEquals("Advance Alpha", s.glider) // empty glider id: type is used
        // 52 06.343 -> 52 06.379 N (66.7 m) and 000 06.198 -> 000 06.171 W (30.7 m): about 73 m
        assertTrue("track ${s.trackLengthM}", s.trackLengthM in 70..77)
        assertTrue("straight ${s.distanceStraightM}", s.distanceStraightM in 70..77)
    }

    @Test
    fun dateHeaderVariants() {
        val b = "B1000004600000N00700000EA0000001000\n"
        assertEquals(ms("2009-07-28T10:00:00Z"), read("HFDTE280709\n$b")!!.dateUtcMs)
        assertEquals(ms("2009-07-28T10:00:00Z"), read("HFDTEDATE:280709,02\n$b")!!.dateUtcMs)
        assertEquals(ms("2009-07-28T10:00:00Z"), read("HFDTEDATE:280709\n$b")!!.dateUtcMs)
        assertEquals(ms("2009-07-28T10:00:00Z"), read("HODTE280709\n$b")!!.dateUtcMs) // source letter O
        assertEquals(ms("1996-03-02T10:00:00Z"), read("HFDTE020396\n$b")!!.dateUtcMs)
        assertEquals(ms("2026-11-18T10:00:00Z"), read("HFDTEDATE:181126,01\n$b")!!.dateUtcMs)
        // missing or impossible date: still a summary, dated 1970-01-01
        assertEquals(36_000_000L, read(b)!!.dateUtcMs)
        assertEquals(36_000_000L, read("HFDTE310211\n$b")!!.dateUtcMs) // 31 February
        assertEquals(36_000_000L, read("HFDTE000000\n$b")!!.dateUtcMs)
        assertEquals(36_000_000L, read("HFDTE321399\n$b")!!.dateUtcMs)
    }

    @Test
    fun pilotAndGliderVariants() {
        val b = "B1000004600000N00700000EA0000001000\n"
        val s = read("HFPLTPILOTINCHARGE: Anna Skywalker \nHFGTYGLIDERTYPE:\nHFGIDGLIDERID:OE-1234\n$b")!!
        assertEquals("Anna Skywalker", s.pilot)
        assertEquals("OE-1234", s.glider)
        val none = read(b)!!
        assertEquals("", none.pilot)
        assertEquals("", none.glider)
    }

    @Test
    fun tolerantOfBomBlankLinesAndLoneLf() {
        val s = read("﻿HFDTEDATE:181126,01\n\n\nB1000004600000N00700000EA0000001000\n\nB1000104600000N00700000EA0000001000")!!
        assertEquals(2, s.fixCount)
        assertEquals(10, s.durationS)
    }

    @Test
    fun garbageGivesNull() {
        assertNull(read(""))
        assertNull(read("hello world\nthis is not an igc file\n"))
        assertNull(read("HFDTEDATE:181126,01\nHFPLTPILOT:X\n")) // headers only
        assertNull(read("B12345\n")) // too short
        assertNull(read("BXXXXXX4600000N00700000EA0000001000\n")) // letters in time
        assertNull(read("B1000004660000N00700000EA0000001000\n")) // 66 minutes
        assertNull(read("B1000009100000N00700000EA0000001000\n")) // 91 degrees
        assertNull(read("B1000004600000N18100000EA0000001000\n")) // 181 degrees
        assertNull(read("B1000004600000X00700000EA0000001000\n")) // bad hemisphere
        assertNull(read("B1000004600000N00700000XA0000001000\n"))
        assertNull(read("B2500004600000N00700000EA0000001000\n")) // hour 25
        assertNull(read("\u0000\u0001\u0002￿\n\r\r\n"))
        assertNull(IgcSummary.read(StringReader("B" + "9".repeat(100_000))))
    }

    @Test
    fun malformedAltitudeFieldDoesNotDropTheFix() {
        // 'x' in the pressure field: the fix is kept, altitude taken from GNSS
        val s = read("B1000004600000N00700000EA00x0001000\n")
        assertNotNull(s)
        assertEquals(1, s!!.fixCount)
        assertEquals(1000, s.maxAltM)
    }

    @Test
    fun ioErrorMidFileReturnsWhatWasRead() {
        val text = "HFDTEDATE:181126,01\nB1000004600000N00700000EA0000001000\nB1000104600000N00700000EA0000001000\n"
        val broken = object : Reader() {
            private val inner = StringReader(text)
            private var served = false
            override fun read(cbuf: CharArray, off: Int, len: Int): Int {
                if (served) throw IOException("disk gone")
                served = true
                return inner.read(cbuf, off, len)
            }
            override fun close() {}
        }
        val s = IgcSummary.read(broken)
        assertEquals(2, s!!.fixCount)
        val failsImmediately = object : Reader() {
            override fun read(cbuf: CharArray, off: Int, len: Int): Int = throw IOException("nope")
            override fun close() {}
        }
        assertNull(IgcSummary.read(failsImmediately))
    }
}
