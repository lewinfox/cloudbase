package app.cloudbase.map

import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.floor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ElevationTest {
    @get:Rule val tmp = TemporaryFolder()

    private val z = 11
    private val tx = 1100
    private val ty = 700

    /** Terrarium encoding of whole metres. */
    private fun encode(metres: Int): Int {
        val v = metres + 32768
        return Color.rgb(v shr 8, v and 0xFF, 0)
    }

    private fun store(offline: Boolean = true) =
        TileStore(tmp.newFolder(), "test").also { it.offline = offline }

    private fun lat(py: Double) = Mercator.lat(ty * 256 + py, z.toDouble())
    private fun lon(px: Double) = Mercator.lon(tx * 256 + px, z.toDouble())

    private fun seeded(): Elevation {
        val store = store()
        store.put(TileSource.TERRARIUM, z, tx, ty, pngBytes { x, y ->
            when {
                x == 10 && y == 20 -> encode(-50)    // below sea level (Dead Sea style)
                x == 11 && y == 20 -> encode(4500)   // above 4000 m
                x == 10 && y == 21 -> encode(1000)
                else -> encode(100)
            }
        })
        return Elevation(store, z)
    }

    /** First call returns NaN and starts the load; wait for it. */
    private fun Elevation.load(lat: Double, lon: Double): Double {
        assertTrue(waitFor { !at(lat, lon).isNaN() })
        return at(lat, lon)
    }

    @Test fun decodesNegativeAndHighElevationsAtPixelCentres() {
        val e = seeded()
        assertTrue(e.at(lat(20.5), lon(10.5)).isNaN())
        assertEquals(-50.0, e.load(lat(20.5), lon(10.5)), 1e-6)
        assertEquals(4500.0, e.at(lat(20.5), lon(11.5)), 1e-6)
        assertEquals(100.0, e.at(lat(100.5), lon(100.5)), 1e-6)
        e.shutdown()
    }

    @Test fun interpolatesBilinearly() {
        val e = seeded()
        e.load(lat(20.5), lon(10.5))
        // Midway between the -50 and 4500 pixels.
        assertEquals((-50 + 4500) / 2.0, e.at(lat(20.5), lon(11.0)), 1e-6)
        // Centre of the 2x2 block -50, 4500, 1000, 100.
        assertEquals((-50 + 4500 + 1000 + 100) / 4.0, e.at(lat(21.0), lon(11.0)), 1e-6)
        e.shutdown()
    }

    @Test fun tileEdgesClampToTheTile() {
        val e = seeded()
        e.load(lat(5.0), lon(5.0))
        assertEquals(100.0, e.at(lat(0.0), lon(0.0)), 1e-6)
        assertEquals(100.0, e.at(lat(255.99), lon(255.99)), 1e-6)
        e.shutdown()
    }

    @Test fun unavailableTileGivesNaNAndOfflineStartsNoLoad() {
        val e = Elevation(store(offline = true), z)
        assertTrue(e.at(lat(5.0), lon(5.0)).isNaN())
        Thread.sleep(100)
        assertTrue(e.at(lat(5.0), lon(5.0)).isNaN())
        e.shutdown()
    }

    @Test fun burstOfCallsOnOneTileIsSafe() {
        val e = seeded()
        val results = (0 until 100).map { e.at(lat(30.0 + it), lon(30.0 + it)) }
        assertTrue(results.all { it.isNaN() || it == 100.0 })
        assertTrue(waitFor { !e.at(lat(30.0), lon(30.0)).isNaN() })
        e.shutdown()
    }

    @Test fun corruptTileIsDeletedSoItCanBeRefetched() {
        val store = store()
        store.put(TileSource.TERRARIUM, z, tx, ty, byteArrayOf(1, 2, 3))
        val e = Elevation(store, z)
        e.at(lat(5.0), lon(5.0))
        assertTrue(waitFor { !store.has(TileSource.TERRARIUM, z, tx, ty) })
        e.shutdown()
    }

    @Test fun tileIndexMatchesMercator() {
        assertEquals(tx, floor(Mercator.x(lon(5.0), z.toDouble()) / 256).toInt())
    }
}
