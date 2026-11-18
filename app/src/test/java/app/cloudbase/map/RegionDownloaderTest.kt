package app.cloudbase.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RegionDownloaderTest {
    @get:Rule val tmp = TemporaryFolder()

    private val source = TileSource("t", "http://127.0.0.1:9/{z}/{x}/{y}.png", 0, 15, "test")
    private val store by lazy { TileStore(tmp.newFolder(), "test") }
    private val downloader by lazy { RegionDownloader(store) }

    /** Runs the main looper until [cond] holds; the worker posts from its own thread. */
    private fun pump(cond: () -> Boolean) {
        assertTrue(waitFor { ShadowLooper.idleMainLooper(); cond() })
    }

    @Test fun overLimitPlanIsRefusedWithoutRunning() {
        var result: Boolean? = null
        downloader.start(source, -80.0, -170.0, 80.0, 170.0, 0..8, maxTiles = 100, progress = { _, _ -> }, done = { result = it })
        pump { result != null }
        assertEquals(false, result)
        assertFalse(downloader.running)
    }

    @Test fun cachedRegionCompletesWithoutNetwork() {
        val png = solidPng(RED)
        for (x in 0..1) for (y in 0..1) store.put(source, 1, x, y, png)
        store.put(source, 0, 0, 0, png)
        var result: Boolean? = null
        var last = 0 to 0
        downloader.start(source, -80.0, -170.0, 80.0, 170.0, 0..1, progress = { d, t -> last = d to t }, done = { result = it })
        assertTrue(downloader.running)
        pump { result != null }
        assertEquals(true, result)
        assertEquals(5 to 5, last)
        assertFalse(downloader.running)
    }

    @Test fun stopsAfterRepeatedNetworkFailures() {
        // Nothing listens on port 9: every tile fails fast, and the job gives up after 3.
        var result: Boolean? = null
        downloader.start(source, -80.0, -170.0, 80.0, 170.0, 1..1, progress = { _, _ -> }, done = { result = it })
        pump { result != null }
        assertEquals(false, result)
    }

    @Test fun offlineStoreIsRefused() {
        store.offline = true
        var result: Boolean? = null
        downloader.start(source, -80.0, -170.0, 80.0, 170.0, 0..1, progress = { _, _ -> }, done = { result = it })
        pump { result != null }
        assertEquals(false, result)
    }
}
