package app.cloudbase.map

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.floor

class MercatorTest {
    @Test fun knownTileForKnownCoordinates() {
        // Cross-checked against the standard slippy-map formulas: 46N 7E at z10 is tile 531/364.
        assertEquals(531, floor(Mercator.x(7.0, 10.0) / 256).toInt())
        assertEquals(364, floor(Mercator.y(46.0, 10.0) / 256).toInt())
    }

    @Test fun worldCornersAndCentre() {
        assertEquals(0.0, Mercator.x(-180.0, 3.0), 1e-9)
        assertEquals(256.0 * 8, Mercator.x(180.0, 3.0), 1e-9)
        assertEquals(256.0 * 4, Mercator.x(0.0, 3.0), 1e-9)
        assertEquals(256.0 * 4, Mercator.y(0.0, 3.0), 1e-9)
        assertEquals(0.0, Mercator.y(Mercator.MAX_LAT, 3.0), 0.01)
    }

    @Test fun roundTrips() {
        for (zoom in listOf(0.0, 5.5, 10.0, 17.0)) {
            for ((lat, lon) in listOf(46.0 to 7.0, -33.9 to 151.2, 0.0 to 0.0, 80.0 to -170.0)) {
                assertEquals(lon, Mercator.lon(Mercator.x(lon, zoom), zoom), 1e-9)
                assertEquals(lat, Mercator.lat(Mercator.y(lat, zoom), zoom), 1e-9)
            }
        }
    }

    @Test fun latitudeIsClamped() {
        assertEquals(Mercator.y(Mercator.MAX_LAT, 4.0), Mercator.y(89.9, 4.0), 1e-9)
        assertEquals(Mercator.MAX_LAT, Mercator.lat(-1000.0, 4.0), 1e-9)
    }

    @Test fun metresPerPixel() {
        assertEquals(156543.03, Mercator.metresPerPixel(0.0, 0.0), 0.01)
        assertEquals(156543.03 / 1024, Mercator.metresPerPixel(0.0, 10.0), 0.001)
        // Shrinks with cos(latitude): 60N is half the equatorial value.
        assertEquals(156543.03 / 2, Mercator.metresPerPixel(60.0, 0.0), 0.01)
    }
}
