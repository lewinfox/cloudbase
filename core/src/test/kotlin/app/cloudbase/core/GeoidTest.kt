package app.cloudbase.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class GeoidTest {
    // lat, lon, EGM96 height from the full 15' grid (bilinear), printed by tools/make_geoid.py --points.
    private val reference = arrayOf(
        doubleArrayOf(46.0, 7.75, 52.537),
        doubleArrayOf(-43.5, 172.6, 11.478),
        doubleArrayOf(51.5, -0.1, 45.929),
        doubleArrayOf(5.0, 79.0, -106.594),
        doubleArrayOf(0.0, 0.0, 17.162),
        doubleArrayOf(90.0, 0.0, 13.606),
        doubleArrayOf(-90.0, 0.0, -29.534),
        doubleArrayOf(64.0, -20.0, 66.727),
        doubleArrayOf(35.7, 139.7, 36.802),
        doubleArrayOf(-33.9, 151.2, 22.304),
        doubleArrayOf(40.7, -74.0, -32.774),
        doubleArrayOf(-12.0, -77.0, 24.029),
        doubleArrayOf(28.0, 86.9, -28.644),
        doubleArrayOf(-0.5, 113.0, 45.756),
        doubleArrayOf(61.0, -150.0, 8.159),
        doubleArrayOf(-22.0, -45.0, -2.733),
        doubleArrayOf(37.0, -119.0, -26.771),
        doubleArrayOf(47.3, 12.0, 48.103),
        doubleArrayOf(-41.3, 174.8, 12.529),
        doubleArrayOf(8.0, 115.0, 35.967),
        doubleArrayOf(-1.0, -179.9, 20.932),
        doubleArrayOf(10.0, 179.9, 12.777),
        doubleArrayOf(-60.0, -60.0, 20.199),
        doubleArrayOf(78.0, 15.0, 32.406),
        doubleArrayOf(27.0, 0.5, 26.027),
        doubleArrayOf(-89.9, 100.0, -29.613),
        doubleArrayOf(89.9, -100.0, 13.720),
    )

    @Test
    fun steepIslandsAreOnlyRoughlyRight() {
        // Big Island of Hawaii sits on a geoid feature much narrower than 2 degrees: documented worst case.
        val err = abs(Geoid.height(19.5, -155.5) - 26.576)
        println("Geoid: Hawaii error %.1f m".format(err))
        assertTrue("error $err", err < 20.0)
    }

    @Test
    fun knownPlaces() {
        assertEquals(52.5, Geoid.height(46.0, 7.75), 2.5) // Zermatt
        assertEquals(11.5, Geoid.height(-43.5, 172.6), 2.5) // Christchurch
        assertEquals(45.9, Geoid.height(51.5, -0.1), 2.5) // London
        assertEquals(-106.6, Geoid.height(5.0, 79.0), 4.0) // global minimum: steep, 2 degree grid is ~3 m off here
    }

    @Test
    fun coarseGridCloseToFullResolution() {
        var worst = 0.0
        var sum = 0.0
        var within25 = 0
        for (r in reference) {
            val err = abs(Geoid.height(r[0], r[1]) - r[2])
            worst = maxOf(worst, err)
            sum += err
            if (err <= 2.5) within25++
            assertTrue("(${r[0]}, ${r[1]}) error $err", err <= 4.0) // a few steep spots exceed 2.5 m
        }
        println("Geoid: ${reference.size} points, max error %.2f m, mean %.2f m, $within25 within 2.5 m".format(worst, sum / reference.size))
        assertTrue(within25 >= reference.size * 3 / 4)
    }

    @Test
    fun nodesAreExactToOneMetre() {
        // 2 degree node: value is the stored whole-metre sample of the full grid.
        assertEquals(17.0, Geoid.height(0.0, 0.0), 0.0)
        assertEquals(14.0, Geoid.height(90.0, 123.0), 0.0) // pole value 13.6 rounds to 14
        assertEquals(-30.0, Geoid.height(-90.0, -77.0), 0.0) // -29.53 rounds to -30
    }

    @Test
    fun longitudeWraps() {
        assertEquals(Geoid.height(10.0, -180.0), Geoid.height(10.0, 180.0), 1e-9)
        assertEquals(Geoid.height(10.0, -170.0), Geoid.height(10.0, 190.0), 1e-9)
        assertEquals(Geoid.height(10.0, 179.0), Geoid.height(10.0, -181.0), 1e-9)
        assertEquals(Geoid.height(-33.0, 150.0), Geoid.height(-33.0, 150.0 - 720.0), 1e-9)
        // continuity across the seam: 1.999 deg either side differs by well under a metre of gradient
        assertEquals(Geoid.height(10.0, 179.999), Geoid.height(10.0, -179.999), 0.01)
    }

    @Test
    fun polesClamp() {
        assertEquals(Geoid.height(90.0, 0.0), Geoid.height(95.0, 0.0), 1e-9)
        assertEquals(Geoid.height(-90.0, 40.0), Geoid.height(-123.0, 40.0), 1e-9)
    }

    @Test
    fun bilinearInterpolatesBetweenNodes() {
        val a = Geoid.height(40.0, 10.0)
        val b = Geoid.height(40.0, 12.0)
        assertEquals((a + b) / 2, Geoid.height(40.0, 11.0), 1e-9)
        val c = Geoid.height(42.0, 10.0)
        assertEquals((a + c) / 2, Geoid.height(41.0, 10.0), 1e-9)
    }

    @Test
    fun nonFiniteInputGivesNaN() {
        assertTrue(Geoid.height(Double.NaN, 0.0).isNaN())
        assertTrue(Geoid.height(0.0, Double.POSITIVE_INFINITY).isNaN())
    }
}
