package app.cloudbase.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TileStoreLruTest {
    @get:Rule val tmp = TemporaryFolder()
    private val map = TileSource("map", "http://127.0.0.1:9/{z}/{x}/{y}.png", 0, 17, "")
    private val terrain = TileSource("terrain", "http://127.0.0.1:9/{z}/{x}/{y}.png", 0, 13, "")
    private val day = 24 * 3_600_000L
    private val now = 1_800_000_000_000L

    private fun store() = TileStore(tmp.root, "test").apply { wallClockMs = { now } }

    /** A cached tile of [kb] KB last used [daysAgo] days ago. */
    private fun put(store: TileStore, source: TileSource, x: Int, kb: Int, daysAgo: Int): File {
        val f = store.file(source, 10, x, 0)
        f.parentFile!!.mkdirs()
        f.writeBytes(ByteArray(kb * 1024))
        f.setLastModified(now - daysAgo * day)
        return f
    }

    @Test fun underTheLimitNothingIsDeleted() {
        val s = store()
        val a = put(s, map, 1, 100, 300)
        s.maxBytes = 1024 * 1024
        assertEquals(0L, s.trim())
        assertTrue(a.exists())
    }

    @Test fun leastRecentlyUsedGoFirstDownToNinetyPercent() {
        val s = store()
        val tiles = (1..10).map { put(s, map, it, 100, daysAgo = it) } // 1 MB; tile 10 is the stalest
        s.maxBytes = 600 * 1024L
        val freed = s.trim()
        // Target is 540 KB: five tiles stay, the five oldest go.
        assertEquals(500 * 1024L, freed)
        for (i in 0 until 5) assertTrue("tile ${i + 1} kept", tiles[i].exists())
        for (i in 5 until 10) assertFalse("tile ${i + 1} deleted", tiles[i].exists())
        assertEquals(500 * 1024L, s.evictableBytes())
        // Already under the limit: a second pass does nothing.
        assertEquals(0L, s.trim())
    }

    @Test fun readingATileKeepsItAlive() {
        val s = store()
        val old = put(s, map, 1, 100, daysAgo = 400)
        val newer = put(s, map, 2, 100, daysAgo = 30)
        // Shown on the map today.
        assertEquals(old, s.fetch(map, 10, 1, 0, allowNetwork = false))
        assertEquals(now, old.lastModified())
        s.maxBytes = 150 * 1024L
        s.trim()
        assertTrue(old.exists())
        assertFalse(newer.exists())
    }

    @Test fun aTileUsedWithinTheDayIsNotRewritten() {
        val s = store()
        val f = put(s, map, 1, 10, daysAgo = 0)
        f.setLastModified(now - 3_600_000L)
        s.fetch(map, 10, 1, 0, allowNetwork = false)
        assertEquals(now - 3_600_000L, f.lastModified())
    }

    @Test fun protectedSourcesAreNeverEvictedAndDoNotCount() {
        val s = store()
        s.keepForever = setOf(terrain.id)
        val height = put(s, terrain, 1, 500, daysAgo = 900)
        val a = put(s, map, 1, 100, daysAgo = 2)
        s.maxBytes = 200 * 1024L
        assertEquals(0L, s.trim()) // 100 KB of map tiles is under the limit; terrain is not counted
        assertTrue(height.exists())
        s.maxBytes = 50 * 1024L
        s.trim()
        assertFalse(a.exists())
        assertTrue(height.exists())
    }

    @Test fun emptyStore() {
        val s = TileStore(File(tmp.root, "missing"), "test")
        s.maxBytes = 1
        assertEquals(0L, s.trim())
        assertEquals(0L, s.evictableBytes())
    }
}
