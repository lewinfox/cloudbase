package app.cloudbase.core.flight

import app.cloudbase.core.Geo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class GlideReachTest {
    private val lat = -44.6
    private val lon = 168.9

    private fun dist(p: app.cloudbase.core.LatLon) = Geo.distance(lat, lon, p.lat, p.lon)

    @Test fun flatGround() {
        // 500 m above flat ground at 8:1 reaches 4000 m.
        val p = GlideReach.impact(lat, lon, 1500.0, 90.0, 8.0) { _, _ -> 1000.0 }
        assertNotNull(p)
        assertEquals(4000.0, dist(p!!), 1.0)
        assertEquals(90.0, Geo.bearing(lat, lon, p.lat, p.lon), 0.5)
    }

    @Test fun risingGroundIsHitSooner() {
        // Ground climbs 1 m per 10 m northwards from 1000 m: 500 = d/8 + d/10 → d = 2222 m.
        val p = GlideReach.impact(lat, lon, 1500.0, 0.0, 8.0) { la, lo -> 1000.0 + Geo.distance(lat, lon, la, lo) / 10.0 }
        assertEquals(2222.2, dist(p!!), 3.0)
    }

    @Test fun ridgeInTheWayStopsTheLine() {
        // A wall between 1000 and 1100 m out; the path is at 1375 m there, the wall top at 1400.
        // Terrain is sampled every 50 m, so the hit is placed within one step of the wall face.
        val p = GlideReach.impact(lat, lon, 1500.0, 45.0, 8.0) { la, lo ->
            if (Geo.distance(lat, lon, la, lo) in 1000.0..1100.0) 1400.0 else 1000.0
        }
        val d = dist(p!!)
        assert(d in 950.0..1050.0) { "hit at $d" }
    }

    @Test fun noAnswerCases() {
        val flat = { _: Double, _: Double -> 1000.0 }
        assertNull(GlideReach.impact(lat, lon, 1500.0, Double.NaN, 8.0, flat))
        assertNull(GlideReach.impact(lat, lon, 1500.0, 0.0, 0.0, flat))
        assertNull(GlideReach.impact(lat, lon, 1500.0, 0.0, -5.0, flat))
        assertNull(GlideReach.impact(lat, lon, Double.NaN, 0.0, 8.0, flat))
        // Too high to come down within range.
        assertNull(GlideReach.impact(lat, lon, 5000.0, 0.0, 9.0, flat))
        // Terrain unknown part-way: say nothing rather than guess.
        assertNull(GlideReach.impact(lat, lon, 1500.0, 0.0, 8.0) { la, lo -> if (Geo.distance(lat, lon, la, lo) > 500) Double.NaN else 1000.0 })
        assertNull(GlideReach.impact(lat, lon, 1500.0, 0.0, 8.0) { _, _ -> Double.NaN })
    }

    @Test fun alreadyOnTheGround() {
        val p = GlideReach.impact(lat, lon, 1000.0, 0.0, 8.0) { _, _ -> 1000.0 }
        assertEquals(0.0, dist(p!!), 0.01)
    }
}
