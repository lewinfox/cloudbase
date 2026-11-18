package app.cloudbase.core.airspace

import app.cloudbase.core.airspace.TestAirspaces.box
import app.cloudbase.core.airspace.TestAirspaces.gnd
import app.cloudbase.core.airspace.TestAirspaces.lat
import app.cloudbase.core.airspace.TestAirspaces.lon
import app.cloudbase.core.airspace.TestAirspaces.msl
import app.cloudbase.core.airspace.TestAirspaces.poly
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AirspaceGeometryTest {
    private fun sq() = TestAirspaces.square() // x,y in [-1000, 1000]

    /** U shape: a 4 km x 3 km block with a 2 km wide, 2 km deep notch cut into the top. */
    private fun uShape() = poly(
        "U", "D", "CTR", gnd, msl(3000.0),
        -2000.0, 0.0, 2000.0, 0.0, 2000.0, 3000.0, 1000.0, 3000.0, 1000.0, 1000.0, -1000.0, 1000.0, -1000.0, 3000.0, -2000.0, 3000.0,
    )

    private fun inside(a: Airspace, x: Double, y: Double) = a.contains(lat(y), lon(x))
    private fun dist(a: Airspace, x: Double, y: Double) = a.distanceToBoundary(lat(y), lon(x))

    // ---- contains --------------------------------------------------------------------------

    @Test
    fun containsConvex() {
        val a = sq()
        assertTrue(inside(a, 0.0, 0.0))
        assertTrue(inside(a, 999.0, 999.0))
        assertTrue(inside(a, -999.0, 500.0))
        assertFalse(inside(a, 1001.0, 0.0))
        assertFalse(inside(a, 0.0, -1001.0))
        assertFalse(inside(a, 5000.0, 5000.0))
        assertFalse(inside(a, -1001.0, -1001.0))
    }

    @Test
    fun containsConcave() {
        val a = uShape()
        assertTrue(inside(a, -1500.0, 2000.0)) // left arm
        assertTrue(inside(a, 1500.0, 2000.0)) // right arm
        assertTrue(inside(a, 0.0, 500.0)) // base
        assertFalse(inside(a, 0.0, 2000.0)) // inside the notch
        assertFalse(inside(a, 0.0, 3500.0)) // above the notch
        assertFalse(inside(a, 0.0, -1.0))
    }

    @Test
    fun containsIsIndependentOfWindingAndStartVertex() {
        val ccw = poly("a", "D", "CTR", gnd, msl(1.0), -1000.0, -1000.0, 1000.0, -1000.0, 1000.0, 1000.0, -1000.0, 1000.0)
        val cw = poly("a", "D", "CTR", gnd, msl(1.0), -1000.0, -1000.0, -1000.0, 1000.0, 1000.0, 1000.0, 1000.0, -1000.0)
        val rot = poly("a", "D", "CTR", gnd, msl(1.0), 1000.0, 1000.0, -1000.0, 1000.0, -1000.0, -1000.0, 1000.0, -1000.0)
        for (a in listOf(ccw, cw, rot)) {
            assertTrue(inside(a, 100.0, -200.0))
            assertFalse(inside(a, 1100.0, 0.0))
        }
    }

    @Test
    fun containsWhenRayPassesThroughAVertex() {
        // Diamond: the query latitude equals the left and right vertices' latitude.
        val d = poly("d", "D", "CTR", gnd, msl(1.0), 0.0, -1000.0, 1000.0, 0.0, 0.0, 1000.0, -1000.0, 0.0)
        assertTrue(inside(d, 0.0, 0.0))
        assertTrue(inside(d, 500.0, 0.0))
        assertFalse(inside(d, 1500.0, 0.0))
        assertFalse(inside(d, -1500.0, 0.0))
    }

    // ---- distanceToBoundary ----------------------------------------------------------------

    @Test
    fun distanceConvex() {
        val a = sq()
        assertEquals(500.0, dist(a, 500.0, 0.0), 1.0) // inside, 500 m from the east edge
        assertEquals(1000.0, dist(a, 0.0, 0.0), 1.0) // centre: all edges 1000 m
        assertEquals(300.0, dist(a, 1300.0, 0.0), 1.0) // outside east
        assertEquals(500.0, dist(a, 1300.0, 1400.0), 1.5) // outside beyond the corner: 3-4-5
        assertEquals(10.0, dist(a, -990.0, 200.0), 0.5)
    }

    @Test
    fun distanceIsNeverNegativeAndIsZeroOnAnEdge() {
        val a = sq()
        assertEquals(0.0, dist(a, 1000.0, 0.0), 0.5)
        assertEquals(0.0, dist(a, 0.0, -1000.0), 0.5)
        for (x in listOf(-3000.0, -500.0, 0.0, 700.0, 4000.0)) for (y in listOf(-2500.0, 0.0, 300.0)) {
            assertTrue(dist(a, x, y) >= 0.0)
        }
    }

    @Test
    fun distanceConcave() {
        val a = uShape()
        // In the notch (floor at y = 1000, walls at x = +-1000): 400 m from the right wall.
        assertEquals(400.0, dist(a, 600.0, 2000.0), 1.0)
        // In the notch, 300 m above the notch floor, 1000 m from either wall.
        assertEquals(300.0, dist(a, 0.0, 1300.0), 1.0)
        // Inside the base, 200 m below the notch floor.
        assertEquals(200.0, dist(a, 0.0, 800.0), 1.0)
        // Outside, 100 m above the right arm top.
        assertEquals(100.0, dist(a, 1500.0, 3100.0), 1.0)
        // Over the notch's mouth, 500 m above the arm tops but the nearest edge is the arm's inner corner:
        // from (0, 3500) the nearest points are the inner top corners (+-1000, 3000): sqrt(1000^2 + 500^2).
        assertEquals(sqrt(1000.0 * 1000.0 + 500.0 * 500.0), dist(a, 0.0, 3500.0), 1.5)
    }

    @Test
    fun distanceAtHighLatitudeUsesLocalScale() {
        // At 60N a degree of longitude is half as long: a 0.01 deg step east is ~556 m.
        val a = Airspace(
            "hl", "D", "CTR", gnd, msl(1.0),
            doubleArrayOf(60.0, 60.1, 60.1, 60.0), doubleArrayOf(10.0, 10.0, 10.1, 10.1),
        )
        val expected = 0.01 * 111_195.0 * 0.5 // 0.01 deg west of the west edge at lon 10.0
        assertEquals(expected, a.distanceToBoundary(60.05, 9.99), 3.0)
    }

    // ---- crossSection ----------------------------------------------------------------------

    private fun cross(a: Airspace, x: Double, y: Double, bearing: Double, length: Double) =
        crossSection(listOf(a), lat(y), lon(x), bearing, length)

    @Test
    fun crossSectionStraightThrough() {
        // Start 5 km west of the square, fly east 10 km: enters at 4000, leaves at 6000.
        val s = cross(sq(), -5000.0, 0.0, 90.0, 10_000.0)
        assertEquals(1, s.size)
        assertEquals(4000.0, s[0].fromM, 1.0)
        assertEquals(6000.0, s[0].toM, 1.0)
    }

    @Test
    fun crossSectionDiagonalChord() {
        // From (-3000,-3000) heading NE: passes through the corners (-1000,-1000) and (1000,1000).
        val s = cross(sq(), -3000.0, -3000.0, 45.0, 10_000.0)
        assertEquals(1, s.size)
        val corner = 2000.0 * sqrt(2.0)
        assertEquals(corner, s[0].fromM, 2.0)
        assertEquals(corner + 2000.0 * sqrt(2.0), s[0].toM, 2.0)
    }

    @Test
    fun crossSectionStartInside() {
        val s = cross(sq(), 0.0, 0.0, 90.0, 10_000.0)
        assertEquals(1, s.size)
        assertEquals(0.0, s[0].fromM, 0.0)
        assertEquals(1000.0, s[0].toM, 1.0)
    }

    @Test
    fun crossSectionEndsInsideIsClippedToLength() {
        val s = cross(sq(), -5000.0, 0.0, 90.0, 4500.0)
        assertEquals(1, s.size)
        assertEquals(4000.0, s[0].fromM, 1.0)
        assertEquals(4500.0, s[0].toM, 0.0)
        // Fully inside a short line.
        val t = cross(sq(), -500.0, 0.0, 90.0, 200.0)
        assertEquals(1, t.size)
        assertEquals(0.0, t[0].fromM, 0.0)
        assertEquals(200.0, t[0].toM, 0.0)
    }

    @Test
    fun crossSectionMissesAndWrongDirection() {
        assertTrue(cross(sq(), -5000.0, 2000.0, 90.0, 10_000.0).isEmpty()) // passes north of it
        assertTrue(cross(sq(), -5000.0, 0.0, 270.0, 10_000.0).isEmpty()) // heading away
        assertTrue(cross(sq(), -5000.0, 0.0, 90.0, 3000.0).isEmpty()) // too short to reach
        assertTrue(cross(sq(), -5000.0, 0.0, 90.0, 0.0).isEmpty())
        assertTrue(cross(sq(), -5000.0, 0.0, 90.0, -5.0).isEmpty())
    }

    @Test
    fun crossSectionConcaveDoubleCrossing() {
        // Tolerance 2.5 m: the fixture is built on a projection at 46.0N, the query projects at 46.018N.
        // Fly north at x = -1500 ... that is the left arm only. Fly east along y = 2000 across both
        // arms and the notch: left arm x in [-2000,-1000], right arm x in [1000, 2000].
        val s = cross(uShape(), -3000.0, 2000.0, 90.0, 6000.0)
        assertEquals(2, s.size)
        assertEquals(1000.0, s[0].fromM, 2.5)
        assertEquals(2000.0, s[0].toM, 2.5)
        assertEquals(4000.0, s[1].fromM, 2.5)
        assertEquals(5000.0, s[1].toM, 2.5)
    }

    @Test
    fun crossSectionConcaveFromInsideAcrossNotch() {
        // Start in the left arm, fly east: out into the notch at 500 m, back in at 2500 m, out at 3500 m.
        val s = cross(uShape(), -1500.0, 2000.0, 90.0, 10_000.0)
        assertEquals(2, s.size)
        assertEquals(0.0, s[0].fromM, 0.0)
        assertEquals(500.0, s[0].toM, 2.5)
        assertEquals(2500.0, s[1].fromM, 2.5)
        assertEquals(3500.0, s[1].toM, 2.5)
    }

    @Test
    fun crossSectionNorthThroughNotchBaseOnly() {
        // Heading south from above the notch at x = 0 hits the notch floor at y = 1000: inside from 2000 m
        // (3000 - 1000) until the bottom edge at y = 0, i.e. 3000 m.
        val s = cross(uShape(), 0.0, 3000.0, 180.0, 5000.0)
        assertEquals(1, s.size)
        assertEquals(2000.0, s[0].fromM, 1.0)
        assertEquals(3000.0, s[0].toM, 1.0)
    }

    @Test
    fun crossSectionThroughAVertexHasConsistentParity() {
        val diamond = poly("d", "D", "CTR", gnd, msl(1.0), 0.0, -1000.0, 1000.0, 0.0, 0.0, 1000.0, -1000.0, 0.0)
        // East along y = 0 passes exactly through the left and right vertices.
        val s = cross(diamond, -3000.0, 0.0, 90.0, 6000.0)
        assertEquals(1, s.size)
        assertEquals(2000.0, s[0].fromM, 1.0)
        assertEquals(4000.0, s[0].toM, 1.0)
        // Grazing a single vertex (line along y = 1000 through the top vertex) yields nothing.
        assertTrue(cross(diamond, -3000.0, 1000.0, 90.0, 6000.0).isEmpty())
    }

    @Test
    fun crossSectionOverSeveralAirspacesIsSortedByStart() {
        val far = box("far", "D", "CTR", gnd, msl(1.0), 3000.0, -500.0, 4000.0, 500.0)
        val near = box("near", "D", "CTR", gnd, msl(1.0), 500.0, -500.0, 1500.0, 500.0)
        val outOfWay = box("none", "D", "CTR", gnd, msl(1.0), 500.0, 2000.0, 1500.0, 3000.0)
        val s = crossSection(listOf(far, outOfWay, near), lat(0.0), lon(0.0), 90.0, 5000.0)
        assertEquals(listOf("near", "far"), s.map { it.airspace.name })
        assertEquals(500.0, s[0].fromM, 1.0)
        assertEquals(3000.0, s[1].fromM, 1.0)
    }

    @Test
    fun crossSectionBearingsOtherThanEastWest() {
        // Heading south-west into the square from the north-east.
        val s = cross(sq(), 3000.0, 3000.0, 225.0, 10_000.0)
        assertEquals(1, s.size)
        assertEquals(2000.0 * sqrt(2.0), s[0].fromM, 2.0)
        // Due north from below.
        val n = cross(sq(), 200.0, -4000.0, 0.0, 10_000.0)
        assertEquals(3000.0, n[0].fromM, 1.0)
        assertEquals(5000.0, n[0].toM, 1.0)
    }
}
