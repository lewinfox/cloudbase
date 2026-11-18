package app.cloudbase.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HeatGridTest {
    private fun alpha(c: Int) = c ushr 24
    private fun red(c: Int) = c shr 16 and 0xFF
    private fun blue(c: Int) = c and 0xFF

    @Test fun colourIsGraduatedAndSplitByDirection() {
        // Nothing inside the dead band.
        assertEquals(0, HeatGrid.color(0.1f, 3f, 1f))
        assertEquals(0, HeatGrid.color(-0.15f, 3f, 1f))
        // Lift is warm, sink is cool.
        val lift = HeatGrid.color(2f, 3f, 1f)
        val sink = HeatGrid.color(-2f, 3f, 1f)
        assertTrue(red(lift) > blue(lift) + 80)
        assertTrue(blue(sink) > red(sink) + 80)
        // Stronger is more opaque, monotonically, and saturates at full scale.
        var last = 0
        for (v in listOf(0.3f, 0.6f, 1f, 1.5f, 2f, 2.5f, 3f)) {
            val a = alpha(HeatGrid.color(v, 3f, 1f))
            assertTrue("alpha at $v", a > last)
            last = a
        }
        assertEquals(HeatGrid.color(3f, 3f, 1f), HeatGrid.color(8f, 3f, 1f))
        assertEquals(HeatGrid.MAX_ALPHA, alpha(HeatGrid.color(3f, 3f, 1f)))
        // The hue itself changes with strength, not only the opacity.
        assertTrue(HeatGrid.color(0.5f, 3f, 1f) and 0xFFFFFF != HeatGrid.color(2.8f, 3f, 1f) and 0xFFFFFF)
        // Coverage fades.
        assertTrue(alpha(HeatGrid.color(2f, 3f, 0.3f)) < alpha(lift))
    }

    @Test fun oneCellBecomesASoftBlob() {
        val g = HeatGrid(16)
        g.clear()
        g.splat(8, 8, 2f, 1f)
        g.render(3f)
        val centre = alpha(g.pixels[8 * 16 + 8])
        val next = alpha(g.pixels[8 * 16 + 9])
        val edge = alpha(g.pixels[8 * 16 + 10])
        assertEquals(alpha(HeatGrid.color(2f, 3f, 1f)), centre)
        assertTrue("fades outwards: $centre > $next > $edge > 0", centre > next && next > edge && edge > 0)
        assertEquals(0, g.pixels[8 * 16 + 11])
        // Same value everywhere it reaches: only the opacity falls off.
        assertEquals(g.pixels[8 * 16 + 8] and 0xFFFFFF, g.pixels[8 * 16 + 9] and 0xFFFFFF)
        // Symmetric.
        assertEquals(g.pixels[8 * 16 + 9], g.pixels[8 * 16 + 7])
        assertEquals(g.pixels[8 * 16 + 9], g.pixels[9 * 16 + 8])
    }

    @Test fun neighbouringLiftAndSinkBlendThroughNothing() {
        val g = HeatGrid(16)
        g.clear()
        g.splat(6, 8, 2f, 1f)
        g.splat(8, 8, -2f, 1f)
        g.render(3f)
        // Exactly between equal lift and sink the average is zero: transparent, not purple.
        assertEquals(0, g.pixels[8 * 16 + 7])
        assertTrue(red(g.pixels[8 * 16 + 6]) > blue(g.pixels[8 * 16 + 6]))
        assertTrue(blue(g.pixels[8 * 16 + 8]) > red(g.pixels[8 * 16 + 8]))
    }

    @Test fun oldCellsCountForLess() {
        val g = HeatGrid(16)
        g.clear()
        g.splat(8, 8, 2f, 0.3f)
        g.render(3f)
        val faded = alpha(g.pixels[8 * 16 + 8])
        assertTrue(faded > 0 && faded < alpha(HeatGrid.color(2f, 3f, 1f)))
        // A fresh sample on the same spot outweighs an old one.
        g.splat(8, 8, -2f, 1f)
        g.render(3f)
        assertTrue(blue(g.pixels[8 * 16 + 8]) > red(g.pixels[8 * 16 + 8]))
    }

    @Test fun edgesAndClearAreSafe() {
        val g = HeatGrid(8)
        g.clear()
        for (p in listOf(-5, -2, -1, 0, 7, 8, 9, 20)) { g.splat(p, p, 1f, 1f); g.splat(p, 3, 1f, 1f) }
        g.render(3f)
        g.clear()
        g.render(3f)
        assertTrue(g.pixels.all { it == 0 })
    }
}
