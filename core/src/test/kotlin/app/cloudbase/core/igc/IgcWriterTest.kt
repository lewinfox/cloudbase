package app.cloudbase.core.igc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringWriter
import java.time.Instant

class IgcWriterTest {
    private val nan = Double.NaN
    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    private val header = IgcHeader("Jane Doe", "Ozone Rush 5", "D-1234", "0.1.0", "Pixel 8", true)

    /** The B record (without CRLF) for one fix. */
    private fun b(t: Long, lat: Double, lon: Double, p: Double = 0.0, g: Double = 0.0): String {
        val sb = StringBuilder()
        IgcWriter(sb).fix(t, lat, lon, p, g)
        assertTrue("CRLF", sb.endsWith("\r\n"))
        assertEquals(37, sb.length)
        return sb.substring(0, 35)
    }

    private val noon = ms("2026-11-18T12:00:00Z")

    @Test
    fun goldenFile() {
        val sb = StringBuilder()
        val w = IgcWriter(sb)
        val t0 = ms("2026-11-18T10:00:00Z")
        w.header(header, t0)
        w.fix(t0, 46.0, 7.75, 1523.4, 1560.6)
        w.fix(t0 + 1400, 46.0005, 7.7505, nan, -12.4)
        w.fix(t0 + 2000, -33.8688, -151.2093, 1000.0, 1000.5)
        val expected = listOf(
            "AXCB001-Cloudbase",
            "HFDTEDATE:181126,01",
            "HFPLTPILOTINCHARGE:Jane Doe",
            "HFCM2CREW2:",
            "HFGTYGLIDERTYPE:Ozone Rush 5",
            "HFGIDGLIDERID:D-1234",
            "HFDTMGPSDATUM:WGS84",
            "HFRFWFIRMWAREVERSION:0.1.0",
            "HFRHWHARDWAREVERSION:Pixel 8",
            "HFFTYFRTYPE:Cloudbase,Android",
            "HFGPSRECEIVER:Android,Pixel 8,0,99999",
            "HFPRSPRESSALTSENSOR:Android,Pixel 8,9999",
            "HFALGALTGPS:GEO",
            "HFALPALTPRESSURE:ISA",
            "B1000004600000N00745000EA0152301561",
            "B1000014600030N00745030EA00000-0012",
            "B1000023352128S15112558WA0100001001",
        ).joinToString("") { it + "\r\n" }
        assertEquals(expected, sb.toString())
    }

    @Test
    fun headerWithoutPressureSensorDeclaresNil() {
        val sb = StringBuilder()
        IgcWriter(sb).header(IgcHeader("P", "G", "R", "1", "Dev", false), noon, flightOfDay = 3)
        val lines = sb.toString().split("\r\n")
        assertTrue("HFDTEDATE:181126,03" in lines)
        assertTrue("HFPRSPRESSALTSENSOR:NIL" in lines)
        assertTrue("HFALPALTPRESSURE:NIL" in lines)
        assertTrue("HFALGALTGPS:GEO" in lines)
    }

    @Test
    fun headerDateIsUtcDate() {
        val sb = StringBuilder()
        IgcWriter(sb).header(header, ms("2026-01-05T23:59:59Z"))
        assertTrue(sb.contains("HFDTEDATE:050126,01\r\n"))
        val sb2 = StringBuilder()
        IgcWriter(sb2).header(header, ms("2026-01-06T00:00:00Z"))
        assertTrue(sb2.contains("HFDTEDATE:060126,01\r\n"))
    }

    @Test
    fun signsAtEquatorAndPrimeMeridian() {
        assertEquals("B1200000000000N00000000EA0000000000", b(noon, 0.0, 0.0))
        assertEquals("B1200000000000N00000000EA0000000000", b(noon, -0.0, -0.0))
        assertEquals("B1200000000006S00000000EA0000000000", b(noon, -0.0001, 0.0))
        assertEquals("B1200000000000N00000006WA0000000000", b(noon, 0.0, -0.0001))
        assertEquals("B1200000000006N00000006EA0000000000", b(noon, 0.0001, 0.0001))
        assertEquals("B1200004600000N00745000EA0000000000", b(noon, 46.0, 7.75))
        assertEquals("B1200003352128S15112558WA0000000000", b(noon, -33.8688, -151.2093))
    }

    @Test
    fun minutesCarryIntoDegrees() {
        // 10 deg 59.9996' must be 11 00.000, never 10 60000
        assertEquals("B1200001100000N", b(noon, 10.0 + 59.9996 / 60.0, 0.0).substring(0, 15))
        assertEquals("B1200004700000N", b(noon, 46.999995, 0.0).substring(0, 15))
        assertEquals("B1200004659999N", b(noon, 46.99999, 0.0).substring(0, 15))
        assertEquals("B1200000000000N00800000E", b(noon, 0.0, 7.0 + 59.9996 / 60.0).substring(0, 24))
        assertEquals("B1200004659999S", b(noon, -46.99999, 0.0).substring(0, 15))
        assertEquals("B1200004700000S", b(noon, -46.999995, 0.0).substring(0, 15))
    }

    @Test
    fun longitudeNearAntimeridian() {
        assertEquals("17959994E", b(noon, 0.0, 179.9999).substring(15, 24))
        assertEquals("17959994W", b(noon, 0.0, -179.9999).substring(15, 24))
        assertEquals("18000000E", b(noon, 0.0, 179.999999).substring(15, 24)) // rounds up to the meridian
        assertEquals("18000000W", b(noon, 0.0, -179.999999).substring(15, 24))
        assertEquals("18000000W", b(noon, 0.0, 180.0).substring(15, 24)) // 180 exactly is written as W
        assertEquals("17900000E", b(noon, 0.0, -181.0).substring(15, 24)) // out of range values wrap
        assertEquals("17900000W", b(noon, 0.0, 181.0).substring(15, 24))
    }

    @Test
    fun polesAndOutOfRangeLatitude() {
        assertEquals("9000000N", b(noon, 90.0, 0.0).substring(7, 15))
        assertEquals("9000000S", b(noon, -90.0, 0.0).substring(7, 15))
        assertEquals("9000000N", b(noon, 95.0, 0.0).substring(7, 15))
    }

    @Test
    fun altitudeFormatting() {
        fun alts(p: Double, g: Double) = b(noon, 0.0, 0.0, p, g).substring(25)
        assertEquals("0123401235", alts(1234.4, 1234.6))
        assertEquals("-0123-0123", alts(-123.4, -123.4))
        assertEquals("-9999-9999", alts(-9999.0, -20000.0)) // clamped to 5 characters
        assertEquals("9999999999", alts(99999.4, 123456.0))
        assertEquals("0000000000", alts(0.4, -0.4)) // never "-0000"
        assertEquals("0100000000", alts(1000.0, 0.0))
        assertEquals("-0001-0001", alts(-1.0, -0.6))
    }

    @Test
    fun nanAltitudes() {
        // no pressure sensor: still a valid fix with 00000 pressure altitude
        assertEquals("B1200000000000N00000000EA0000001234", b(noon, 0.0, 0.0, nan, 1234.0))
        // no GNSS altitude: V and 00000
        assertEquals("B1200000000000N00000000EV0123400000", b(noon, 0.0, 0.0, 1234.0, nan))
        assertEquals("B1200000000000N00000000EV0000000000", b(noon, 0.0, 0.0, nan, nan))
    }

    @Test
    fun timeOfDay() {
        val day = ms("2026-11-18T00:00:00Z")
        assertEquals("235959", b(day + 86_399_499, 1.0, 1.0).substring(1, 7))
        assertEquals("000000", b(day + 86_399_999, 1.0, 1.0).substring(1, 7)) // 1 ms early rounds to the next second
        assertEquals("000000", b(day + 86_400_000, 1.0, 1.0).substring(1, 7)) // across midnight
        assertEquals("000001", b(day + 86_401_000, 1.0, 1.0).substring(1, 7))
        assertEquals("010203", b(day + (3600 + 120 + 3) * 1000L + 499, 1.0, 1.0).substring(1, 7)) // rounded to the nearest second
        assertEquals("235959", b(-1000L, 1.0, 1.0).substring(1, 7)) // before 1970 is fine
        assertEquals("000000", b(0L, 1.0, 1.0).substring(1, 7))
    }

    @Test
    fun fixWithoutPositionIsSkipped() {
        val sb = StringBuilder()
        val w = IgcWriter(sb)
        w.fix(noon, nan, 7.0, 0.0, 0.0)
        w.fix(noon, 46.0, Double.POSITIVE_INFINITY, 0.0, 0.0)
        assertEquals("", sb.toString())
    }

    @Test
    fun sameOutputForWriterBuilderAndGenericAppendable() {
        val sw = StringWriter()
        val sb = StringBuilder()
        val generic = object : Appendable {
            val inner = StringBuilder()
            override fun append(csq: CharSequence?): Appendable = apply { inner.append(csq) }
            override fun append(csq: CharSequence?, start: Int, end: Int): Appendable = apply { inner.append(csq, start, end) }
            override fun append(c: Char): Appendable = apply { inner.append(c) }
        }
        for (out in listOf<Appendable>(sw, sb, generic)) {
            val w = IgcWriter(out)
            w.header(header, noon)
            for (i in 0 until 5) w.fix(noon + i * 1000L, 46.0 + i * 0.001, 7.0, 1000.0 + i, 1010.0 + i)
        }
        assertEquals(sb.toString(), sw.toString())
        assertEquals(sb.toString(), generic.inner.toString())
    }

    @Test
    fun bufferIsNotLeakedBetweenFixes() {
        val sb = StringBuilder()
        val w = IgcWriter(sb)
        w.fix(noon, -33.8688, -151.2093, 12345.0, 12345.0)
        w.fix(noon, 1.0, 1.0, nan, nan)
        val lines = sb.toString().split("\r\n")
        assertEquals("B1200000100000N00100000EV0000000000", lines[1])
    }

    @Test
    fun headerValuesAreSanitised() {
        val sb = StringBuilder()
        IgcWriter(sb).header(
            IgcHeader("José Müller\r\nB000000", "A$*B", "日本", "1.0~", "Pixel\t8, Pro", false),
            noon,
        )
        val lines = sb.toString().split("\r\n").dropLast(1)
        assertEquals(14, lines.size) // the injected newline did not create a record
        assertTrue(lines.contains("HFPLTPILOTINCHARGE:Jose Muller  B000000"))
        assertTrue(lines.contains("HFGTYGLIDERTYPE:A__B"))
        assertTrue(lines.contains("HFGIDGLIDERID:__"))
        assertTrue(lines.contains("HFRFWFIRMWAREVERSION:1.0_"))
        assertTrue(lines.contains("HFRHWHARDWAREVERSION:Pixel 8, Pro"))
        assertTrue(lines.contains("HFGPSRECEIVER:Android,Pixel 8  Pro,0,99999"))
        for (l in lines) {
            for (c in l) assertTrue("illegal char ${c.code} in $l", c.code in 0x20..0x7D && c !in "!\$*\\^~")
            assertTrue("line too long: $l", l.length <= 76)
        }
    }

    @Test
    fun sanitizeDetails() {
        assertEquals("Jose Muller", IgcWriter.sanitize("José Müller", 50))
        assertEquals("e", IgcWriter.sanitize("é", 50)) // combining accent
        assertEquals("a b", IgcWriter.sanitize("a\nb", 50))
        assertEquals("x_y", IgcWriter.sanitize("x😀y", 50)) // emoji is one underscore
        assertEquals("a_b_c_d_e_f", IgcWriter.sanitize("a!b\$c*d\\e^f", 50))
        assertEquals("a_b", IgcWriter.sanitize("a~b", 50))
        assertEquals("a{b}|c", IgcWriter.sanitize("a{b}|c", 50)) // 0x7B..0x7D are legal
        assertEquals("a b", IgcWriter.sanitize("a\u007Fb", 50)) // DEL
        assertEquals("abc", IgcWriter.sanitize("  abc  ", 50))
        assertEquals("abc", IgcWriter.sanitize("abc def", 3))
        assertEquals("abc", IgcWriter.sanitize("abc  def", 5))
        assertEquals("", IgcWriter.sanitize("", 5))
    }

    @Test
    fun longValuesAreCutToLineLimit() {
        val sb = StringBuilder()
        IgcWriter(sb).header(IgcHeader("N".repeat(200), "G", "R", "1", "D", true), noon)
        val pilot = sb.toString().split("\r\n").first { it.startsWith("HFPLT") }
        assertEquals(76, pilot.length)
    }

    @Test
    fun fileNames() {
        assertEquals("2026-11-18-XCB-001-01.igc", IgcWriter.fileName(ms("2026-11-18T10:00:00Z"), 1))
        assertEquals("2026-11-18-XCB-001-12.igc", IgcWriter.fileName(ms("2026-11-18T23:59:59Z"), 12))
        assertEquals("2026-11-19-XCB-001-02.igc", IgcWriter.fileName(ms("2026-11-19T00:00:00Z"), 2))
        assertEquals("2026-01-05-XCB-001-01.igc", IgcWriter.fileName(ms("2026-01-05T08:00:00Z"), 0)) // seq is 1-based
        assertEquals("2026-11-18-XCB-001-100.igc", IgcWriter.fileName(ms("2026-11-18T10:00:00Z"), 100))
    }
}
