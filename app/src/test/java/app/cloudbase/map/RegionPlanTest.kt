package app.cloudbase.map

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RegionPlanTest {
    @get:Rule val tmp = TemporaryFolder()

    private val store by lazy { TileStore(tmp.root, "test") }
    private val downloader by lazy { RegionDownloader(store) }

    @Test fun wholeWorldCountsEveryTile() {
        val plan = downloader.plan(TileSource.TERRARIUM, -85.0511, -180.0, 85.0511, 180.0, 0..2)
        assertEquals(1 + 4 + 16, plan.tiles)
        assertEquals(0, plan.alreadyCached)
    }

    @Test fun pointIsOneTilePerZoom() {
        val plan = downloader.plan(TileSource.OPEN_TOPO, 46.0, 7.0, 46.0, 7.0, 10..12)
        assertEquals(3, plan.tiles)
    }

    @Test fun boxSpanningTwoColumns() {
        // At z10, lon 7.0 is in column 531 and 7.2 in column 532; lat 45.9 and 46.0 share row 364.
        val plan = downloader.plan(TileSource.OPEN_TOPO, 45.9, 7.0, 46.0, 7.2, 10..10)
        assertEquals(2, plan.tiles)
    }

    @Test fun zoomsAreClampedToTheSourceRange() {
        // OpenTopo starts at z2: zooms 0..2 is just z2's 16 tiles.
        assertEquals(16, downloader.plan(TileSource.OPEN_TOPO, -85.0511, -180.0, 85.0511, 180.0, 0..2).tiles)
        assertEquals(0, downloader.plan(TileSource.OPEN_TOPO, 46.0, 7.0, 46.0, 7.0, 18..20).tiles)
    }

    @Test fun cachedTilesAreCounted() {
        val f = store.file(TileSource.OPEN_TOPO, 10, 531, 364)
        f.parentFile!!.mkdirs()
        f.writeBytes(byteArrayOf(1))
        val plan = downloader.plan(TileSource.OPEN_TOPO, 45.9, 7.0, 46.0, 7.2, 10..10)
        assertEquals(2, plan.tiles)
        assertEquals(1, plan.alreadyCached)
    }
}
