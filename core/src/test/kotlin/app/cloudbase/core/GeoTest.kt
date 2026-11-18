package app.cloudbase.core

import org.junit.Assert.assertEquals
import org.junit.Test

class GeoTest {
    @Test fun distanceAndBearing() {
        // One degree of latitude.
        assertEquals(111_195.0, Geo.distance(0.0, 0.0, 1.0, 0.0), 5.0)
        assertEquals(0.0, Geo.bearing(0.0, 0.0, 1.0, 0.0), 1e-9)
        assertEquals(90.0, Geo.bearing(0.0, 0.0, 0.0, 1.0), 1e-9)
        assertEquals(270.0, Geo.bearing(0.0, 0.0, 0.0, -1.0), 1e-9)
    }

    @Test fun destinationRoundTrips() {
        val p = Geo.destination(-43.5, 172.5, 37.0, 12_345.0)
        assertEquals(12_345.0, Geo.distance(-43.5, 172.5, p.lat, p.lon), 0.01)
        assertEquals(37.0, Geo.bearing(-43.5, 172.5, p.lat, p.lon), 0.01)
    }

    @Test fun localProjectionMatchesSphereNearby() {
        val proj = LocalProjection(46.0, 7.0)
        val p = Geo.destination(46.0, 7.0, 60.0, 8_000.0)
        val d = Math.hypot(proj.x(p.lon), proj.y(p.lat))
        assertEquals(8_000.0, d, 8.0)
        assertEquals(p.lat, proj.lat(proj.y(p.lat)), 1e-12)
        assertEquals(p.lon, proj.lon(proj.x(p.lon)), 1e-12)
    }

    @Test fun atmosphere() {
        assertEquals(0.0, Atmosphere.pressureAltitude(1013.25), 1e-6)
        assertEquals(1000.0, Atmosphere.pressureAltitude(898.75), 1.0)
        assertEquals(3000.0, Atmosphere.pressureAltitude(701.09), 2.0)
        val qnh = Atmosphere.qnh(900.0, 1100.0)
        assertEquals(1100.0, Atmosphere.altitude(900.0, qnh), 1e-6)
        assertEquals(0.7423, Atmosphere.densityRatio(3000.0), 0.002)
    }
}
