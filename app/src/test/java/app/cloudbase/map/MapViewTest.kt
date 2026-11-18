package app.cloudbase.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.floor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MapViewTest {
    @get:Rule val tmp = TemporaryFolder()

    // A source whose host is unreachable: the test must be satisfied from the disk cache alone.
    private val source = TileSource("t", "http://127.0.0.1:9/{z}/{x}/{y}.png", 2, 15, "attribution")
    private lateinit var layer: TileLayer
    private lateinit var view: MapView
    private val z = 10

    @Before fun setUp() {
        val store = TileStore(tmp.newFolder(), "test").also { it.offline = true }
        // Checkerboard of solid tiles around 46N 7E (tile 531/364 at z10).
        val red = solidPng(RED)
        val blue = solidPng(BLUE)
        for (x in 525..537) for (y in 358..370) store.put(source, z, x, y, if ((x + y) % 2 == 0) red else blue)
        layer = TileLayer(store, source)
        view = MapView(RuntimeEnvironment.getApplication(), layer, source)
        view.measure(View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1280, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 720, 1280)
        view.zoom = z.toDouble()
        view.setCenter(46.0, 7.0)
    }

    @After fun tearDown() = layer.shutdown()

    private fun render(): Bitmap {
        val bmp = Bitmap.createBitmap(720, 1280, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))
        return bmp
    }

    private fun tileColour(x: Int, y: Int) = if ((x + y) % 2 == 0) RED else BLUE

    @Test fun drawsTheTileUnderTheCentreAndItsNeighbour() {
        render() // first pass only requests the tiles
        val tx = floor(Mercator.x(7.0, 10.0) / 256).toInt()
        val ty = floor(Mercator.y(46.0, 10.0) / 256).toInt()
        assertEquals(531 to 364, tx to ty)
        assertTrue(waitFor { layer.peek(z, tx, ty) != null && layer.peek(z, tx + 1, ty) != null })

        val bmp = render()
        assertEquals(tileColour(tx, ty), bmp.getPixel(360, 640))
        assertEquals(tileColour(tx + 1, ty), bmp.getPixel(360 + 256, 640))
        assertNotEquals(bmp.getPixel(360, 640), bmp.getPixel(360 + 256, 640))
    }

    @Test fun missingTileFallsBackToAnAncestor() {
        // Cache only the z9 parent of the centre tile (colour distinct from the z10 checkerboard).
        val parent = solidPng(android.graphics.Color.GREEN)
        val store = TileStore(tmp.newFolder(), "test").also { it.offline = true }
        store.put(source, 9, 531 shr 1, 364 shr 1, parent)
        val l2 = TileLayer(store, source)
        val v2 = MapView(RuntimeEnvironment.getApplication(), l2, source)
        v2.measure(View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1280, View.MeasureSpec.EXACTLY))
        v2.layout(0, 0, 720, 1280)
        v2.zoom = 10.0
        v2.setCenter(46.0, 7.0)
        // Only z9 is cached, so z10 tiles stay missing; load the ancestor into memory via get().
        l2.get(9, 531 shr 1, 364 shr 1)
        assertTrue(waitFor { l2.peek(9, 531 shr 1, 364 shr 1) != null })
        val bmp = Bitmap.createBitmap(720, 1280, Bitmap.Config.ARGB_8888)
        v2.draw(Canvas(bmp))
        assertEquals(android.graphics.Color.GREEN, bmp.getPixel(360, 640))
        l2.shutdown()
    }

    @Test fun projectionRoundTrips() {
        val p = view.projection
        for (x in listOf(0f, 100.5f, 360f, 719f)) assertEquals(x, p.x(p.lon(x)), 0.01f)
        for (y in listOf(0f, 333.3f, 640f, 1279f)) assertEquals(y, p.y(p.lat(y)), 0.01f)
        assertEquals(7.0, p.lon(360f), 1e-6)
        assertEquals(46.0, p.lat(640f), 1e-6)
        assertEquals(720, p.width)
        assertEquals(1280, p.height)
        assertEquals(10.0, p.zoom, 0.0)
        assertTrue(p.minLat < 46.0 && p.maxLat > 46.0 && p.minLon < 7.0 && p.maxLon > 7.0)
        assertEquals(Mercator.metresPerPixel(46.0, 10.0), p.metresPerPixel, 1e-9)
    }

    @Test fun projectionPicksNearestWorldCopyAcrossAntimeridian() {
        view.setCenter(0.0, 179.9)
        val p = view.projection
        assertTrue(p.x(-179.9) > 360f) // just east of the centre, not a world away
        assertTrue(p.minLon < p.maxLon || p.maxLon < 0) // wraps, but no crash
        render()
    }

    @Test fun setCenterKeepsTheCentreAndIgnoresSubPixelMoves() {
        view.follow = true
        view.setCenter(46.5, 7.5)
        assertEquals(46.5, view.centerLat, 1e-9)
        assertEquals(7.5, view.centerLon, 1e-9)
        assertTrue(view.follow)
        val mpp = view.projection.metresPerPixel
        view.setCenter(46.5, 7.5 + 0.5 * mpp / 111_000.0 / Math.cos(Math.toRadians(46.5)))
        assertEquals(7.5, view.centerLon, 0.0) // under one pixel: ignored
    }

    @Test fun zoomIsClampedToSourceRangePlusOverzoom() {
        view.zoom = 99.0
        assertEquals(17.0, view.zoom, 0.0)
        view.zoom = -3.0
        assertEquals(2.0, view.zoom, 0.0)
    }

    @Test fun dragTurnsFollowOffAndMovesTheMap() {
        var notified: Boolean? = null
        view.onFollowChanged = { notified = it }
        view.follow = true
        val t = SystemClock.uptimeMillis()
        view.dispatchTouchEvent(event(t, MotionEvent.ACTION_DOWN, 360f, 640f))
        for (i in 1..5) view.dispatchTouchEvent(event(t + i * 10, MotionEvent.ACTION_MOVE, 360f - i * 40, 640f))
        view.dispatchTouchEvent(event(t + 60, MotionEvent.ACTION_UP, 160f, 640f))
        assertFalse(view.follow)
        assertEquals(false, notified)
        // Finger moved left, so the map content moved left and the centre moved east.
        assertTrue(view.centerLon > 7.0)
    }

    @Test fun tapReportsGeographicPosition() {
        var tapped: Pair<Double, Double>? = null
        view.onTap = { lat, lon -> tapped = lat to lon }
        val t = SystemClock.uptimeMillis()
        view.dispatchTouchEvent(event(t, MotionEvent.ACTION_DOWN, 360f, 640f))
        view.dispatchTouchEvent(event(t + 20, MotionEvent.ACTION_UP, 360f, 640f))
        // Single taps are confirmed after the double-tap timeout, via the main looper.
        org.robolectric.shadows.ShadowLooper.idleMainLooper(1, java.util.concurrent.TimeUnit.SECONDS)
        assertEquals(46.0, tapped!!.first, 1e-6)
        assertEquals(7.0, tapped!!.second, 1e-6)
        assertTrue(view.follow)
    }

    private fun event(t: Long, action: Int, x: Float, y: Float) = MotionEvent.obtain(t, t, action, x, y, 0)
}
