package app.cloudbase.core.airspace

import app.cloudbase.core.Atmosphere
import app.cloudbase.core.Geo
import java.io.IOException
import java.io.Reader
import java.io.StringReader
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAirTest {
    private fun parse(text: String) = OpenAir.parse(StringReader(text))

    private fun header(name: String = "T") = "AC D\nAN $name\nAL GND\nAH 1000ft AMSL\n"

    /** Parses [dp] as the first vertex of a triangle and returns it as (lat, lon). */
    private fun firstVertex(dp: String): Pair<Double, Double> {
        val r = parse(header() + "$dp\nDP 0:00:00 N 1:00:00 E\nDP 1:00:00 N 1:00:00 E\n")
        assertEquals("warnings for '$dp': ${r.warnings}", 0, r.warnings.size)
        assertEquals(1, r.airspaces.size)
        val a = r.airspaces[0]
        return Pair(a.lats[0], a.lons[0])
    }

    private fun assertCoord(dp: String, lat: Double, lon: Double) {
        val (la, lo) = firstVertex(dp)
        assertEquals("lat of '$dp'", lat, la, 1e-7)
        assertEquals("lon of '$dp'", lon, lo, 1e-7)
    }

    // ---- coordinates ---------------------------------------------------------------------

    @Test
    fun coordinateFormats() {
        // 40:29:11 S = -(40 + 29/60 + 11/3600) = -40.4863889 ; 176:37:00 E = 176 + 37/60 = 176.6166667
        assertCoord("DP 40:29:11 S 176:37:00 E", -40.4863889, 176.6166667)
        assertCoord("DP 40:29:11S 176:37:00E", -40.4863889, 176.6166667)
        // 40:29.183 N = 40 + 29.183/60 = 40.4863833 ; 006:37.5 W = -(6 + 37.5/60) = -6.625
        assertCoord("DP 40:29.183 N 006:37.5 W", 40.4863833, -6.625)
        assertCoord("DP 40.4864 N 6.625 W", 40.4864, -6.625)
        assertCoord("DP 40:29:11 S, 176:37:00 E", -40.4863889, 176.6166667)
        assertCoord("DP 40:29:11 S ,176:37:00 E", -40.4863889, 176.6166667)
        // seconds with decimals: 40 + 29/60 + 11.5/3600 = 40.4865278 ; 6 + 37/60 + 30.25/3600 = 6.6250694
        assertCoord("DP 40:29:11.5 N 6:37:30.25 E", 40.4865278, 6.6250694)
        assertCoord("DP 40:29:11 s 176:37:00 e", -40.4863889, 176.6166667)
        assertCoord("   dp   40:29:11 N    6:37:30 E   ", 40.4863889, 6.625)
        assertCoord("\tDP 40:29:11N 6:37:30E", 40.4863889, 6.625)
        assertCoord("DP 40:29 N 6:37 E", 40.4833333, 6.6166667)
        assertCoord("DP 46 N 7 W", 46.0, -7.0)
    }

    @Test
    fun badCoordinatesAreSkippedWithWarnings() {
        val bad = listOf(
            "DP 40:29:11 X 176:37:00 E", // bad hemisphere
            "DP 91:00:00 N 10:00:00 E", // lat out of range
            "DP 45:00:00 N 181:00:00 E", // lon out of range
            "DP 45:75:00 N 10:00:00 E", // minutes >= 60
            "DP 45:30:75 N 10:00:00 E", // seconds >= 60
            "DP 45:30.5:10 N 10:00:00 E", // fraction before a colon
            "DP 45:00:00 N", // missing lon
            "DP 45:00:00 E 10:00:00 N", // swapped hemispheres
            "DP 45.0 10.0", // no hemispheres
            "DP garbage",
            "DP",
            "DP 45:00:00 N 10:00:00 E junk",
        )
        for (dp in bad) {
            val r = parse(header() + "DP 0:00:00 N 1:00:00 E\n$dp\nDP 1:00:00 N 1:00:00 E\nDP 1:00:00 N 0:00:00 E\n")
            assertEquals("'$dp'", 1, r.airspaces.size)
            assertEquals("'$dp' must be skipped", 3, r.airspaces[0].lats.size)
            assertEquals("'$dp': ${r.warnings}", 1, r.warnings.size)
            assertTrue(r.warnings[0], r.warnings[0].startsWith("line 6:"))
        }
    }

    // ---- altitudes -----------------------------------------------------------------------

    private fun assertAlt(text: String, ref: AltRef, metres: Double) {
        val a = OpenAir.parseAltitude(text)
        assertNotNull("'$text' should parse", a)
        assertEquals("ref of '$text'", ref, a!!.ref)
        assertEquals("metres of '$text'", metres, a.metres, 1e-9)
        assertEquals("text of '$text'", text.trim(), a.text)
    }

    @Test
    fun altitudeVariants() {
        val ft3500 = 3500 * Atmosphere.FT // 1066.8
        val ft1000 = 1000 * Atmosphere.FT // 304.8
        assertAlt("GND", AltRef.GND, 0.0)
        assertAlt("SFC", AltRef.GND, 0.0)
        assertAlt("gnd", AltRef.GND, 0.0)
        assertAlt("0", AltRef.GND, 0.0)
        assertAlt("MSL", AltRef.MSL, 0.0)
        assertAlt("FL95", AltRef.STD, 9500 * Atmosphere.FT) // 2895.6
        assertAlt("FL 95", AltRef.STD, 2895.6)
        assertAlt("fl95", AltRef.STD, 2895.6)
        assertAlt("FL065", AltRef.STD, 6500 * Atmosphere.FT)
        assertAlt("3500ft AMSL", AltRef.MSL, ft3500)
        assertAlt("3500 ft MSL", AltRef.MSL, ft3500)
        assertAlt("3500FT", AltRef.MSL, ft3500)
        assertAlt("3500 MSL", AltRef.MSL, ft3500)
        assertAlt("3500 ALT", AltRef.MSL, ft3500)
        assertAlt("3500", AltRef.MSL, ft3500)
        assertAlt("1000ft AGL", AltRef.GND, ft1000)
        assertAlt("1000 AGL", AltRef.GND, ft1000)
        assertAlt("1000ft GND", AltRef.GND, ft1000)
        assertAlt("1000FT ASFC", AltRef.GND, ft1000)
        assertAlt("1000 SFC", AltRef.GND, ft1000)
        assertAlt("500m AGL", AltRef.GND, 500.0)
        assertAlt("1500M MSL", AltRef.MSL, 1500.0)
        assertAlt("1500 m", AltRef.MSL, 1500.0)
        assertAlt("  3500   ft   amsl  ", AltRef.MSL, ft3500)
        assertAlt("0 ft", AltRef.MSL, 0.0)
        assertAlt("-50 ft MSL", AltRef.MSL, -50 * Atmosphere.FT)
        assertAlt("1500.5 m", AltRef.MSL, 1500.5)
        assertEquals(3500.0 * Atmosphere.FT, 1066.8, 1e-9)
    }

    @Test
    fun altitudeUnlimited() {
        for (t in listOf("UNL", "UNLIM", "UNLIMITED", "unlimited", " Unl ")) {
            val a = OpenAir.parseAltitude(t)
            assertNotNull(t, a)
            assertTrue(t, a!!.isUnlimited)
            assertEquals(AltLimit.UNLIMITED.ref, a.ref)
            assertEquals(t.trim(), a.text)
        }
    }

    @Test
    fun altitudeGarbageIsNull() {
        for (t in listOf("", "   ", "abc", "FL", "FL x", "FL 9 5", "3500 furlongs", "ft", "m", "12.5.3", "3500 MSL FT",
            "-", "3500,", "GND 100", "UNL 5", "MSL 100", "FL95 FL", "3500ft AMSL AGL", "€", "ÄÖ")) {
            assertNull("'$t'", OpenAir.parseAltitude(t))
        }
    }

    // ---- records -------------------------------------------------------------------------

    @Test
    fun fullRecordSet() {
        val r = parse(
            """
            * a comment
            *
            AC C
            AY CTR
            AN  Test CTR * not a comment
            AF 118.100
            AL GND
            AH 3500ft AMSL
            AG Tower
            AA NONE
            AX 7000
            AI x
            SP 0,1,2,3,4
            SB 1,2,3
            AT 1,2
            TO foo
            TC bar
            V W=5
            V Z=10
            DP 46:00:00 N 8:00:00 E
            DP 46:00:00 N 8:10:00 E
            DP 46:10:00 N 8:10:00 E
            DP 46:10:00 N 8:00:00 E
            DP 46:00:00 N 8:00:00 E
            """.trimIndent(),
        )
        assertEquals(r.warnings.toString(), 0, r.warnings.size)
        assertEquals(1, r.airspaces.size)
        val a = r.airspaces[0]
        assertEquals("Test CTR * not a comment", a.name)
        assertEquals("C", a.cls)
        assertEquals("CTR", a.type)
        assertEquals("118.100", a.frequency)
        assertEquals(AltRef.GND, a.lower.ref)
        assertEquals(1066.8, a.upper.metres, 1e-9)
        assertEquals("closing vertex dropped", 4, a.lats.size)
    }

    @Test
    fun caseInsensitiveAndNoneType() {
        val r = parse("  ac d\n ay none\n an foo\n al gnd\n ah fl100\n dp 1 n 1 e\n dp 1 n 2 e\n dp 2 n 2 e\n")
        assertEquals(r.warnings.toString(), 0, r.warnings.size)
        val a = r.airspaces.single()
        assertEquals("D", a.cls)
        assertEquals("", a.type)
        assertEquals("foo", a.name)
        assertEquals(AltRef.STD, a.upper.ref)
    }

    @Test
    fun typeAndClassAreUpperCased() {
        val a = parse("AC unc\nAY tma\nAN x\nAL GND\nAH FL100\nDP 1 N 1 E\nDP 1 N 2 E\nDP 2 N 2 E\n").airspaces.single()
        assertEquals("UNC", a.cls)
        assertEquals("TMA", a.type)
    }

    @Test
    fun newAcFinalisesPreviousAndEofFinalisesLast() {
        val r = parse(
            header("one") + "DP 1 N 1 E\nDP 1 N 2 E\nDP 2 N 2 E\n" +
                header("two") + "DP 3 N 1 E\nDP 3 N 2 E\nDP 4 N 2 E",
        )
        assertEquals(listOf("one", "two"), r.airspaces.map { it.name })
        assertEquals(3.0, r.airspaces[1].lats[0], 1e-9)
    }

    @Test
    fun duplicateAndClosingVerticesDropped() {
        val a = parse(header() + "DP 1 N 1 E\nDP 1 N 1 E\nDP 1 N 2 E\nDP 2 N 2 E\nDP 2 N 2 E\nDP 1 N 1 E\n")
            .airspaces.single()
        assertEquals(3, a.lats.size)
        assertEquals(1.0, a.lats[0], 0.0)
        assertEquals(2.0, a.lons[1], 0.0)
        assertEquals(2.0, a.lats[2], 0.0)
    }

    @Test
    fun fewerThanThreeVerticesDropped() {
        val r = parse(header("tiny") + "DP 1 N 1 E\nDP 1 N 2 E\nDP 1 N 1 E\n" + header("ok") + "DP 1 N 1 E\nDP 1 N 2 E\nDP 2 N 2 E\n")
        assertEquals(listOf("ok"), r.airspaces.map { it.name })
        assertEquals(1, r.warnings.size)
        assertTrue(r.warnings[0], r.warnings[0].startsWith("line 1:") && "tiny" in r.warnings[0])
    }

    @Test
    fun missingLimitsUseConservativeDefaults() {
        val r = parse("AC D\nAN a\nDP 1 N 1 E\nDP 1 N 2 E\nDP 2 N 2 E\n")
        val a = r.airspaces.single()
        assertEquals(AltLimit.SURFACE, a.lower)
        assertEquals(AltLimit.UNLIMITED, a.upper)
        assertEquals(r.warnings.toString(), 2, r.warnings.size)
    }

    @Test
    fun unparseableLimitsUseConservativeDefaultsWithLineNumbers() {
        val r = parse("AC D\nAN a\nAL banana\nAH ???\nDP 1 N 1 E\nDP 1 N 2 E\nDP 2 N 2 E\n")
        val a = r.airspaces.single()
        assertEquals(AltLimit.SURFACE, a.lower)
        assertEquals(AltLimit.UNLIMITED, a.upper)
        assertEquals(r.warnings.toString(), 2, r.warnings.size)
        assertTrue(r.warnings[0].startsWith("line 3:"))
        assertTrue(r.warnings[1].startsWith("line 4:"))
    }

    @Test
    fun recordsBeforeFirstAcAndUnknownRecordsWarn() {
        val r = parse("AN orphan\nZZ what\nAC D\nAN a\nAL GND\nAH FL100\nDP 1 N 1 E\nDP 1 N 2 E\nDP 2 N 2 E\n")
        assertEquals(1, r.airspaces.size)
        assertEquals(r.warnings.toString(), 2, r.warnings.size)
        assertTrue(r.warnings[0].startsWith("line 1:"))
        assertTrue(r.warnings[1].startsWith("line 2:"))
    }

    @Test
    fun airwaySegmentsDropTheAirspace() {
        val r = parse(
            header("airway") + "DY 46:00:00 N 8:00:00 E\nDY 46:10:00 N 8:00:00 E\nDP 1 N 1 E\nDP 1 N 2 E\nDP 2 N 2 E\n" +
                header("fine") + "DP 1 N 1 E\nDP 1 N 2 E\nDP 2 N 2 E\n",
        )
        assertEquals(listOf("fine"), r.airspaces.map { it.name })
        assertEquals(r.warnings.toString(), 1, r.warnings.size)
        assertTrue(r.warnings[0].startsWith("line 5:") && "DY" in r.warnings[0])
    }

    @Test
    fun warningsAreCapped() {
        val sb = StringBuilder(header())
        repeat(200) { sb.append("XX nonsense\n") }
        val r = parse(sb.toString())
        assertEquals(OpenAir.MAX_WARNINGS, r.warnings.size)
        assertEquals(50, OpenAir.MAX_WARNINGS)
    }

    @Test
    fun byteOrderMarkAndCrLfTolerated() {
        val r = parse("﻿AC D\r\nAN a\r\nAL GND\r\nAH FL100\r\nDP 1 N 1 E\r\nDP 1 N 2 E\r\nDP 2 N 2 E\r\n")
        assertEquals(r.warnings.toString(), 0, r.warnings.size)
        assertEquals("a", r.airspaces.single().name)
        assertEquals("D", r.airspaces.single().cls)
    }

    // ---- circles and arcs ----------------------------------------------------------------

    private val centre = "V X=46:00:00 N 008:00:00 E\n"
    private val cLat = 46.0
    private val cLon = 8.0

    private fun bearings(a: Airspace) = DoubleArray(a.lats.size) { Geo.bearing(cLat, cLon, a.lats[it], a.lons[it]) }

    @Test
    fun circleVerticesLieOnTheRadiusAndChordsAreTight() {
        for (nm in listOf(0.5, 5.0, 30.0)) {
            val r = parse(header() + centre + "DC $nm\n")
            assertEquals(r.warnings.toString(), 0, r.warnings.size)
            val a = r.airspaces.single()
            val radius = nm * 1852.0
            for (i in a.lats.indices) {
                assertEquals("r=$nm vertex $i", radius, Geo.distance(cLat, cLon, a.lats[i], a.lons[i]), 1.0)
            }
            assertTrue(a.lats.size >= 36)
            val b = bearings(a)
            for (i in b.indices) {
                val step = abs(Geo.angleDiff(b[(i + 1) % b.size], b[i]))
                assertTrue("step $step", step <= 10.0 + 1e-6)
                val sagitta = radius * (1 - cos(step / 2 * Geo.DEG))
                assertTrue("r=$nm sagitta $sagitta", sagitta <= 15.0 + 1e-6)
            }
            // The polygon is the circle: centre inside, a point 1.01 r away outside.
            assertTrue(a.contains(cLat, cLon))
            val outside = Geo.destination(cLat, cLon, 33.0, radius * 1.01)
            assertTrue(!a.contains(outside.lat, outside.lon))
        }
    }

    @Test
    fun circleVertexCountMatchesChordRule() {
        // 5 NM = 9260 m: step = 2 acos(1 - 15/9260) = 6.52 deg -> ceil(360/6.52) = 56 vertices.
        val a = parse(header() + centre + "DC 5\n").airspaces.single()
        assertEquals(56, a.lats.size)
    }

    @Test
    fun clockwiseArcRunsFromStartToEndBearing() {
        val r = parse(header() + centre + "V D=+\nDA 10,0,90\n")
        assertEquals(r.warnings.toString(), 0, r.warnings.size)
        val a = r.airspaces.single()
        val b = bearings(a)
        assertEquals(0.0, Geo.angleDiff(b.first(), 0.0), 1e-6)
        assertEquals(0.0, Geo.angleDiff(b.last(), 90.0), 1e-6)
        var total = 0.0
        for (i in 0 until b.size - 1) {
            val d = Geo.angleDiff(b[i + 1], b[i])
            assertTrue("clockwise steps only, got $d", d > 0.0 && d <= 10.0 + 1e-6)
            total += d
        }
        assertEquals(90.0, total, 1e-6)
        for (i in a.lats.indices) assertEquals(18520.0, Geo.distance(cLat, cLon, a.lats[i], a.lons[i]), 1.0)
    }

    @Test
    fun counterClockwiseArcTheLongWay() {
        val a = parse(header() + centre + "V D=-\nDA 10,0,90\n").airspaces.single()
        val b = bearings(a)
        assertEquals(0.0, Geo.angleDiff(b.first(), 0.0), 1e-6)
        assertEquals(0.0, Geo.angleDiff(b.last(), 90.0), 1e-6)
        var total = 0.0
        for (i in 0 until b.size - 1) {
            val d = Geo.angleDiff(b[i + 1], b[i])
            assertTrue("counter-clockwise steps only, got $d", d < 0.0 && d >= -10.0 - 1e-6)
            total += d
        }
        assertEquals(-270.0, total, 1e-6)
    }

    @Test
    fun arcDirectionResetsAtEachAirspace() {
        val r = parse(
            header("first") + centre + "V D=-\nDA 10,0,90\n" +
                header("second") + centre + "DA 10,0,90\n",
        )
        assertEquals(2, r.airspaces.size)
        for ((idx, expected) in listOf(-270.0, 90.0).withIndex()) {
            val b = bearings(r.airspaces[idx])
            var total = 0.0
            for (i in 0 until b.size - 1) total += Geo.angleDiff(b[i + 1], b[i])
            assertEquals("airspace $idx", expected, total, 1e-6)
        }
    }

    @Test
    fun arcWrappingThroughNorth() {
        // Clockwise 350 -> 10 is a 20 degree arc through north.
        val a = parse(header() + centre + "DA 10,350,10\n").airspaces.single()
        val b = bearings(a)
        var total = 0.0
        for (i in 0 until b.size - 1) total += Geo.angleDiff(b[i + 1], b[i])
        assertEquals(20.0, total, 1e-6)
    }

    @Test
    fun arcZeroToThreeSixtyIsAFullCircle() {
        val a = parse(header() + centre + "DA 5,0,360\n").airspaces.single()
        assertTrue(a.contains(cLat, cLon))
        assertTrue(a.lats.size >= 56)
    }

    @Test
    fun arcAsPartOfPolygonWithSpaceSeparatedArguments() {
        val r = parse(header() + centre + "DP 46:00:00 N 008:00:00 E\nDA 5 0 90\n")
        assertEquals(r.warnings.toString(), 0, r.warnings.size)
        val a = r.airspaces.single()
        // Centre vertex + arc: a pie slice, so the point inside the quadrant is in and the opposite one is out.
        val inQuad = Geo.destination(cLat, cLon, 45.0, 3000.0)
        val outQuad = Geo.destination(cLat, cLon, 225.0, 3000.0)
        assertTrue(a.contains(inQuad.lat, inQuad.lon))
        assertTrue(!a.contains(outQuad.lat, outQuad.lon))
    }

    private fun fmt(lat: Double, lon: Double): String {
        val ns = if (lat >= 0) "N" else "S"
        val ew = if (lon >= 0) "E" else "W"
        return String.format(Locale.ROOT, "%.7f %s %.7f %s", abs(lat), ns, abs(lon), ew)
    }

    private fun parsedText(s: String): Pair<Double, Double> {
        // Re-read "<lat> N <lon> E" through the parser itself so expectations match digit for digit.
        val a = parse(header() + "DP $s\nDP 0 N 0 E\nDP 0 N 1 E\n").airspaces.single()
        return Pair(a.lats[0], a.lons[0])
    }

    @Test
    fun dbArcStartsAtPointOneEndsAtPointTwoAndKeepsRadius() {
        val p1 = Geo.destination(cLat, cLon, 0.0, 18520.0)
        val p2 = Geo.destination(cLat, cLon, 90.0, 18520.0)
        val t1 = fmt(p1.lat, p1.lon)
        val t2 = fmt(p2.lat, p2.lon)
        val (e1la, e1lo) = parsedText(t1)
        val (e2la, e2lo) = parsedText(t2)

        val cw = parse(header() + centre + "V D=+\nDB $t1, $t2\n")
        assertEquals(cw.warnings.toString(), 0, cw.warnings.size)
        val a = cw.airspaces.single()
        assertEquals(e1la, a.lats.first(), 0.0)
        assertEquals(e1lo, a.lons.first(), 0.0)
        assertEquals(e2la, a.lats.last(), 0.0)
        assertEquals(e2lo, a.lons.last(), 0.0)
        val radius = Geo.distance(cLat, cLon, e1la, e1lo)
        val b = bearings(a)
        var total = 0.0
        for (i in 0 until b.size - 1) {
            val d = Geo.angleDiff(b[i + 1], b[i])
            assertTrue(d > 0.0)
            total += d
        }
        assertEquals(90.0, total, 1e-3)
        for (i in 1 until a.lats.size - 1) {
            assertEquals(radius, Geo.distance(cLat, cLon, a.lats[i], a.lons[i]), 1.0)
        }

        val ccw = parse(header() + centre + "V D=-\nDB $t1, $t2\n").airspaces.single()
        val bb = bearings(ccw)
        var total2 = 0.0
        for (i in 0 until bb.size - 1) total2 += Geo.angleDiff(bb[i + 1], bb[i])
        assertEquals(-270.0, total2, 1e-3)
        assertEquals(e1la, ccw.lats.first(), 0.0)
        assertEquals(e2lo, ccw.lons.last(), 0.0)
    }

    @Test
    fun dbAcceptsCommaFreeCoordinatePairs() {
        val r = parse(header() + centre + "DB 46:10:00 N 008:00:00 E, 46:00:00 N 008:10:00 E\n")
        assertEquals(r.warnings.toString(), 0, r.warnings.size)
        assertTrue(r.airspaces.single().lats.size > 3)
    }

    @Test
    fun arcsAndCirclesWithoutCentreOrWithBadArgumentsWarn() {
        for (rec in listOf("DC 5", "DA 5,0,90", "DB 46 N 8 E, 46 N 9 E")) {
            val r = parse(header() + "DP 1 N 1 E\nDP 1 N 2 E\nDP 2 N 2 E\n$rec\n")
            assertEquals("$rec ${r.warnings}", 1, r.warnings.size)
            assertEquals(3, r.airspaces.single().lats.size)
        }
        for (rec in listOf("DC x", "DC -5", "DC 0", "DC 999999", "DA 5,0", "DA x,y,z", "DB 1 N 1 E", "DC 5 junk")) {
            val r = parse(header() + centre + "DP 1 N 1 E\nDP 1 N 2 E\nDP 2 N 2 E\n$rec\n")
            assertEquals("$rec ${r.warnings}", 1, r.warnings.size)
            assertEquals("$rec", 3, r.airspaces.single().lats.size)
        }
    }

    @Test
    fun badVRecordsWarn() {
        for (rec in listOf("V", "V X=", "V X=garbage", "V D=?", "V D", "V Q=1", "V X 46 N 8 E")) {
            val r = parse(header() + "$rec\nDP 1 N 1 E\nDP 1 N 2 E\nDP 2 N 2 E\n")
            assertEquals("'$rec' ${r.warnings}", 1, r.warnings.size)
            assertEquals(1, r.airspaces.size)
        }
    }

    // ---- robustness ----------------------------------------------------------------------

    @Test
    fun emptyAndWhitespaceFiles() {
        for (t in listOf("", "\n", "   \n\t\n", "* only a comment")) {
            val r = parse(t)
            assertEquals(0, r.airspaces.size)
            assertEquals(0, r.warnings.size)
        }
    }

    private val validDoc = header("Sample CTR") + centre +
        "AF 118.1\nDP 46:00:00 N 8:00:00 E\nDP 46:10:00 N 8:00:00 E\nDA 5,0,90\nV D=-\nDB 46:10:00 N 8:00:00 E, 46:00:00 N 8:10:00 E\nDC 3\n" +
        header("Second") + "DY 1 N 1 E\nDP 1 N 1 E\nDP 1 N 2 E\nDP 2 N 2 E\n"

    @Test
    fun truncatedFilesNeverThrow() {
        for (i in 0..validDoc.length) {
            val r = parse(validDoc.substring(0, i))
            assertNotNull(r)
        }
        assertEquals(1, parse(validDoc).airspaces.size) // the DY airspace is dropped
    }

    @Test
    fun randomGarbageNeverThrows() {
        val rnd = Random(12345)
        val alphabet = "ACDPVXYLHNSEW:., -+=*0123456789abcdefgFTMGLU\t\u0000é﻿"
        val prefixes = listOf("AC ", "AY ", "AN ", "AL ", "AH ", "AF ", "DP ", "DC ", "DA ", "DB ", "DY ", "V X=", "V D=", "V W=", "", "*", "  dp ")
        val sb = StringBuilder()
        repeat(30_000) {
            sb.append(prefixes[rnd.nextInt(prefixes.size)])
            repeat(rnd.nextInt(30)) { sb.append(alphabet[rnd.nextInt(alphabet.length)]) }
            sb.append('\n')
        }
        val r = parse(sb.toString())
        assertTrue(r.warnings.size <= OpenAir.MAX_WARNINGS)
        // Whatever survived must be a valid airspace.
        for (a in r.airspaces) assertTrue(a.lats.size >= 3)
    }

    @Test
    fun randomMutationsOfValidFileNeverThrow() {
        val rnd = Random(777)
        repeat(2000) {
            val chars = validDoc.toCharArray()
            repeat(1 + rnd.nextInt(5)) { chars[rnd.nextInt(chars.size)] = " :.NSEW,=-\n0129*A".random(rnd) }
            parse(String(chars))
        }
    }

    @Test
    fun readerFailureIsReportedNotThrown() {
        val reader = object : Reader() {
            var sent = false
            override fun read(cbuf: CharArray, off: Int, len: Int): Int {
                if (!sent) {
                    sent = true
                    val s = "AC D\nAN a\nAL GND\nAH FL100\nDP 1 N 1 E\nDP 1 N 2 E\nDP 2 N 2 E\n"
                    s.toCharArray(cbuf, off, 0, s.length)
                    return s.length
                }
                throw IOException("boom")
            }

            override fun close() {}
        }
        val r = OpenAir.parse(reader)
        assertEquals(1, r.airspaces.size) // what was read before the failure is kept
        assertTrue(r.warnings.any { "boom" in it })
    }
}
