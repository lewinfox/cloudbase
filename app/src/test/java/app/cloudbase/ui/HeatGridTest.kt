package app.cloudbase.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class HeatGridTest {
    private fun alpha(c: Int) = c ushr 24
    private fun rgb(c: Int) = c and 0xFFFFFF
    private fun r(c: Int) = c shr 16 and 0xFF
    private fun g(c: Int) = c shr 8 and 0xFF
    private fun b(c: Int) = c and 0xFF
    private fun col(v: Float) = HeatGrid.color(v, 1f)

    /** Perceived brightness, 0..255 (Rec. 601 luma). */
    private fun luma(c: Int) = 0.299 * r(c) + 0.587 * g(c) + 0.114 * b(c)

    /** How far apart two colours are, in RGB. */
    private fun distance(x: Int, y: Int): Double {
        val dr = r(x) - r(y); val dg = g(x) - g(y); val db = b(x) - b(y)
        return Math.sqrt((dr * dr + dg * dg + db * db).toDouble())
    }

    @Test fun liftIsSpreadOverManyColoursAcrossTheRangeThatMatters() {
        // Each step up in climb, from weak to very strong, is a clearly different colour...
        val steps = listOf(0.3f, 0.7f, 1.5f, 2.5f, 4f, 7f)
        for (i in 0 until steps.size - 1) {
            val d = distance(col(steps[i]), col(steps[i + 1]))
            assertTrue("%.1f and %.1f m/s differ by only %.0f".format(steps[i], steps[i + 1], d), d > 55)
        }
        // ...and so is each half metre through the ordinary thermal range.
        var v = 0.5f
        while (v < 3.5f) {
            val d = distance(col(v), col(v + 0.5f))
            assertTrue("%.1f and %.1f m/s differ by only %.0f".format(v, v + 0.5f, d), d > 25)
            v += 0.5f
        }
        // The named colours land where the comment says they do.
        val yellow = col(0.7f); val orange = col(1.5f); val red = col(2.5f); val crimson = col(4f); val deep = col(9f)
        assertTrue("0.7 m/s is yellow: %06X".format(rgb(yellow)), r(yellow) > 240 && g(yellow) > 190 && b(yellow) < 90)
        assertTrue("1.5 m/s is orange: %06X".format(rgb(orange)), r(orange) > 240 && g(orange) in 110..190 && b(orange) < 40)
        assertTrue("2.5 m/s is red: %06X".format(rgb(red)), r(red) > 230 && g(red) < 100 && b(red) < 40)
        assertTrue("4 m/s is crimson: %06X".format(rgb(crimson)), r(crimson) in 190..230 && g(crimson) < 30)
        assertTrue("very strong is deep crimson: %06X".format(rgb(deep)), r(deep) in 130..170 && g(deep) < 15)
        // Stronger lift is deeper, so the best lift stands out most against a pale map.
        var last = 256.0
        v = 0.3f
        while (v <= 8f) { val l = luma(col(v)); assertTrue("brightness at $v", l <= last + 0.5); last = l; v += 0.1f }
    }

    @Test fun sinkRunsFromVioletToNearBlack() {
        var last = 256.0
        var v = 0.3f
        while (v <= 6f) { val l = luma(col(-v)); assertTrue("brightness at -$v", l <= last + 0.5); last = l; v += 0.1f }
        assertTrue(luma(col(-5f)) < 45)
        // Sink uses its range up sooner than lift: it only needs to say "bad" and "worse".
        assertTrue(HeatGrid.strength(-2f) > HeatGrid.strength(2f) + 0.2f)
        assertEquals(0.76f, HeatGrid.strength(-HeatGrid.SINK_SCALE), 0.01f)
        assertEquals(0.76f, HeatGrid.strength(HeatGrid.LIFT_SCALE), 0.01f)
        assertTrue(HeatGrid.strength(50f) <= 1f && HeatGrid.strength(-50f) <= 1f)
    }

    @Test fun upAndDownAreUnmistakablyDifferentAtEveryStrength() {
        assertTrue(distance(col(0.4f), col(-0.4f)) > 200)
        var v = 0.25f
        while (v <= 8f) {
            val up = col(v); val down = col(-v)
            // Lift is the yellow/orange/red family (red well above blue); sink the violet/indigo family (blue above red).
            assertTrue("lift at $v: %06X".format(rgb(up)), r(up) > b(up) + 60)
            assertTrue("sink at $v: %06X".format(rgb(down)), b(down) > r(down) + 20)
            assertTrue("lift $v vs sink $v", distance(up, down) > 120)
            v += 0.25f
        }
    }

    @Test fun neverUsesTheMapsGreens() {
        // The map underneath is greens and grey-blues. (Yellow has green and red both high: not green.)
        var v = -8f
        while (v <= 8f) {
            val c = col(v)
            if (c != 0) assertTrue("green at $v m/s: %06X".format(rgb(c)), !(g(c) > r(c) + 25 && g(c) > b(c) + 25))
            v += 0.1f
        }
    }

    @Test fun stillAirIsNotDrawnAndOpacityIsCoverageOnly() {
        assertEquals(0, col(0.1f))
        assertEquals(0, col(-0.15f))
        // Weak and strong air are equally opaque: the colour carries the strength.
        assertEquals(HeatGrid.ALPHA, alpha(col(0.4f)))
        assertEquals(HeatGrid.ALPHA, alpha(col(4f)))
        assertTrue(alpha(HeatGrid.color(2f, 0.3f)) < HeatGrid.ALPHA / 2)
    }

    @Test fun oneCellBecomesASoftBlob() {
        val grid = HeatGrid(16)
        grid.clear()
        grid.splat(8, 8, 2f, 1f)
        grid.render()
        val centre = alpha(grid.pixels[8 * 16 + 8])
        val next = alpha(grid.pixels[8 * 16 + 9])
        val edge = alpha(grid.pixels[8 * 16 + 10])
        assertEquals(HeatGrid.ALPHA, centre)
        assertTrue("fades outwards: $centre > $next > $edge > 0", centre > next && next > edge && edge > 0)
        assertEquals(0, grid.pixels[8 * 16 + 11])
        // Same value everywhere it reaches: only the opacity falls off.
        assertEquals(rgb(grid.pixels[8 * 16 + 8]), rgb(grid.pixels[8 * 16 + 9]))
        // Symmetric.
        assertEquals(grid.pixels[8 * 16 + 9], grid.pixels[8 * 16 + 7])
        assertEquals(grid.pixels[8 * 16 + 9], grid.pixels[9 * 16 + 8])
    }

    @Test fun neighbouringLiftAndSinkBlendThroughNothing() {
        val grid = HeatGrid(16)
        grid.clear()
        grid.splat(6, 8, 2f, 1f)
        grid.splat(8, 8, -2f, 1f)
        grid.render()
        // Exactly between equal lift and sink the average is zero: transparent.
        assertEquals(0, grid.pixels[8 * 16 + 7])
        assertTrue(r(grid.pixels[8 * 16 + 6]) > b(grid.pixels[8 * 16 + 6]))
        assertTrue(b(grid.pixels[8 * 16 + 8]) > r(grid.pixels[8 * 16 + 8]))
    }

    @Test fun oldCellsCountForLess() {
        val grid = HeatGrid(16)
        grid.clear()
        grid.splat(8, 8, 2f, 0.3f)
        grid.render()
        val faded = alpha(grid.pixels[8 * 16 + 8])
        assertTrue(faded > 0 && faded < HeatGrid.ALPHA)
        // A fresh sample on the same spot outweighs an old one: the result is on the sink side.
        grid.splat(8, 8, -2f, 1f)
        grid.render()
        assertTrue(b(grid.pixels[8 * 16 + 8]) > r(grid.pixels[8 * 16 + 8]))
    }

    @Test fun aDensePatchOfOldSamplesIsAsFadedAsOneOldSample() {
        val lone = HeatGrid(16)
        lone.clear(); lone.splat(8, 8, 2f, 0.5f); lone.render()
        val dense = HeatGrid(16)
        dense.clear()
        for (y in 4..12) for (x in 4..12) dense.splat(x, y, 2f, 0.5f)
        dense.render()
        assertEquals(alpha(lone.pixels[8 * 16 + 8]), alpha(dense.pixels[8 * 16 + 8]))
        assertEquals(HeatGrid.ALPHA / 2.0, alpha(dense.pixels[8 * 16 + 8]).toDouble(), 2.0)
    }

    @Test fun edgesAndClearAreSafe() {
        val grid = HeatGrid(8)
        grid.clear()
        for (p in listOf(-5, -2, -1, 0, 7, 8, 9, 20)) { grid.splat(p, p, 1f, 1f); grid.splat(p, 3, 1f, 1f) }
        grid.render()
        grid.clear()
        grid.render()
        assertTrue(grid.pixels.all { it == 0 })
    }
}
