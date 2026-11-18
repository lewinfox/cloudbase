package app.cloudbase.map

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TileStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private val png = solidPng(RED)
    private val hits = AtomicInteger()
    @Volatile private var status = 200
    @Volatile private var body: ByteArray = png
    @Volatile private var lastAgent: String? = null
    @Volatile private var lastPath: String? = null

    private lateinit var server: ServerSocket
    private lateinit var source: TileSource
    private lateinit var store: TileStore

    /** Minimal one-request-per-connection HTTP server (the JDK's HttpServer is not on this classpath). */
    private fun serve(socket: ServerSocket) = Thread {
        while (!socket.isClosed) {
            try {
                socket.accept().use { c ->
                    val reader = c.getInputStream().bufferedReader()
                    val requestLine = reader.readLine() ?: return@use
                    var line = reader.readLine()
                    while (!line.isNullOrEmpty()) {
                        if (line.startsWith("User-Agent:", ignoreCase = true)) lastAgent = line.substringAfter(':').trim()
                        line = reader.readLine()
                    }
                    hits.incrementAndGet()
                    lastPath = requestLine.split(" ")[1]
                    val out = if (status == 200) body else ByteArray(0)
                    val head = "HTTP/1.1 $status X\r\nContent-Length: ${out.size}\r\nConnection: close\r\n\r\n"
                    c.getOutputStream().apply { write(head.toByteArray()); write(out); flush() }
                }
            } catch (e: java.io.IOException) {
                // socket closed by tearDown or the test
            }
        }
    }.apply { isDaemon = true; start() }

    @Before fun setUp() {
        server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        serve(server)
        source = TileSource("local", "http://127.0.0.1:${server.localPort}/t/{z}/{x}/{y}.png", 0, 15, "test")
        store = TileStore(tmp.newFolder("tiles"), "Cloudbase/test")
    }

    @After fun tearDown() = server.close()

    @Test fun pathLayoutAndHas() {
        val f = store.file(source, 10, 531, 364)
        assertEquals(store.root.resolve("local/10/531/364.png"), f)
        assertFalse(store.has(source, 10, 531, 364))
        store.put(source, 10, 531, 364, png)
        assertTrue(store.has(source, 10, 531, 364))
    }

    @Test fun downloadsOnceThenServesFromDisk() {
        val f = store.fetch(source, 3, 2, 1, true)
        assertNotNull(f)
        assertArrayEquals(png, f!!.readBytes())
        assertEquals("/t/3/2/1.png", lastPath)
        assertEquals("Cloudbase/test", lastAgent)
        assertEquals(f, store.fetch(source, 3, 2, 1, true))
        assertEquals(1, hits.get())
        assertEquals(png.size.toLong(), store.sizeBytes())
        // No temp files left behind.
        assertEquals(listOf("1.png"), f.parentFile!!.list()!!.toList())
    }

    @Test fun xWrapsAndYOutOfRangeIsNull() {
        val f = store.fetch(source, 3, 8 + 2, 1, true)
        assertEquals(store.file(source, 3, 2, 1), f)
        assertEquals("/t/3/2/1.png", lastPath)
        assertEquals(store.file(source, 3, 7, 1), store.fetch(source, 3, -1, 1, true))
        val before = hits.get()
        assertNull(store.fetch(source, 3, 0, 8, true))
        assertNull(store.fetch(source, 3, 0, -1, true))
        assertEquals(before, hits.get())
    }

    @Test fun noNetworkWhenNotAllowedOrOffline() {
        assertNull(store.fetch(source, 3, 2, 1, false))
        store.offline = true
        assertNull(store.fetch(source, 3, 2, 1, true))
        assertEquals(0, hits.get())
        // Cached tiles are still served offline.
        store.put(source, 3, 4, 4, png)
        assertNotNull(store.fetch(source, 3, 4, 4, true))
        store.offline = false
        assertNotNull(store.fetch(source, 3, 2, 1, true))
        assertEquals(1, hits.get())
    }

    @Test fun failureIsNotRetriedForSixtySeconds() {
        var now = 1_000_000L
        store.clockMs = { now }
        status = 500
        assertNull(store.fetch(source, 3, 2, 1, true))
        assertEquals(1, hits.get())
        now += 59_000
        assertNull(store.fetch(source, 3, 2, 1, true))
        assertEquals(1, hits.get())
        // Another tile is not affected by the negative cache.
        assertNull(store.fetch(source, 3, 3, 1, true))
        assertEquals(2, hits.get())
        now += 2_000
        status = 200
        assertNotNull(store.fetch(source, 3, 2, 1, true))
        assertEquals(3, hits.get())
    }

    @Test fun connectionFailureIsNegativeCachedToo() {
        server.close()
        assertNull(store.fetch(source, 3, 2, 1, true))
        assertFalse(store.has(source, 3, 2, 1))
    }

    @Test fun nonPngBodyIsRejectedAndLeavesNothing() {
        body = "<html>captive portal</html>".toByteArray()
        assertNull(store.fetch(source, 3, 2, 1, true))
        assertFalse(store.has(source, 3, 2, 1))
        assertEquals(0L, store.sizeBytes())
    }

    @Test fun clearEmptiesTheCache() {
        store.fetch(source, 3, 2, 1, true)
        assertTrue(store.sizeBytes() > 0)
        store.clear()
        assertEquals(0L, store.sizeBytes())
        assertFalse(store.has(source, 3, 2, 1))
    }
}
