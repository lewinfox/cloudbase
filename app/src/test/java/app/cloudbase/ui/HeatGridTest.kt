package app.cloudbase.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class HeatGridTest {
    private val scale = 2.0f
    private fun alpha(c: Int) = c ushr 24
    private fun rgb(c: Int) = c and 0xFFFFFF

    /** Perceived brightness, 0..255 (Rec. 601 luma). */
    private fun luma(c: Int) = 0.299 * (c shr 16 and 0xFF) + 0.587 * (c shr 8 and 0xFF) + 0.114 * (c and 0xFF)

    /** How far apart two colours are, in RGB. */
    private fun distance(a: Int, b: Int): Double {
        val dr = (a shr 16 and 0xFF) - (b shr 16 and 0xFF)
        val dg = (a shr 8 and 0xFF) - (b shr 8 and 0xFF)
        val db = (a and 0xFF) - (b and 0xFF)
        return Math.sqrt((dr * dr + dg * dg + db * db).toDouble())
    }

    @Test fun brightnessRisesSteadilyFromSinkToClimb() {
        var last = -1.0
        var v = -6f
        while (v <= 6f) {
            if (abs(v) > HeatGrid.DEAD_BAND) {
                val l = luma(HeatGrid.color(v, scale, 1f))
                assertTrue("brightness at $v m/s ($l) must not be below the value before ($last)", l >= last - 0.5)
                last = l
            }
            v += 0.25f
        }
        // Strong sink is dark, strong climb is bright.
        assertTrue(luma(HeatGrid.color(-5f, scale, 1f)) < 45)
        assertTrue(luma(HeatGrid.color(5f, scale, 1f)) > 190)
    }

    @Test fun neverUsesTheMapsGreens() {
        // The map underneath is greens and grey-blues; nothing on the scale may be green. (The
        // bright end is yellow, where green and red are both high: that is not green.)
        var v = -6f
        while (v <= 6f) {
            val c = HeatGrid.color(v, scale, 1f)
            if (c != 0) {
                val r = c shr 16 and 0xFF; val g = c shr 8 and 0xFF; val b = c and 0xFF
                assertTrue("green at $v m/s: %06X".format(rgb(c)), !(g > r + 25 && g > b + 25))
            }
            v += 0.1f
        }
    }

    @Test fun mostOfTheScaleIsSpentOnOrdinaryClimbRates() {
        // Sigmoid: half a metre per second is a much bigger colour step near zero than out at the extremes.
        val near = distance(HeatGrid.color(0.5f, scale, 1f), HeatGrid.color(1.0f, scale, 1f))
        val far = distance(HeatGrid.color(4.0f, scale, 1f), HeatGrid.color(4.5f, scale, 1f))
        assertTrue("near $near, far $far", near > 5 * far)
        // Every half-step across the ordinary range is a clearly different colour.
        var v = -2.5f
        while (v < 2.5f) {
            if (abs(v) > HeatGrid.DEAD_BAND && abs(v + 0.5f) > HeatGrid.DEAD_BAND) {
                val d = distance(HeatGrid.color(v, scale, 1f), HeatGrid.color(v + 0.5f, scale, 1f))
                assertTrue("%.1f to %.1f m/s differ by only %.0f".format(v, v + 0.5f, d), d > 18)
            }
            v += 0.5f
        }
        assertEquals(0f, HeatGrid.strength(0f, scale), 1e-6f)
        assertEquals(0.76f, HeatGrid.strength(2.0f, scale), 0.01f)
        assertEquals(HeatGrid.strength(1.3f, scale), HeatGrid.strength(-1.3f, scale), 1e-6f)
        assertTrue(HeatGrid.strength(50f, scale) <= 1f)
    }

    @Test fun upAndDownAreUnmistakablyDifferentEvenWhenWeak() {
        // The first thing to read is the sign. Just either side of still air the two colours
        // must be far apart, and each side must stay in its own family at every strength.
        val weakLift = HeatGrid.color(0.4f, scale, 1f)
        val weakSink = HeatGrid.color(-0.4f, scale, 1f)
        assertTrue("weak lift and weak sink differ by ${distance(weakLift, weakSink)}", distance(weakLift, weakSink) > 200)
        var v = 0.25f
        while (v <= 6f) {
            val up = HeatGrid.color(v, scale, 1f)
            val down = HeatGrid.color(-v, scale, 1f)
            // Lift is the orange/yellow family (red well above blue); sink the violet/indigo family (blue above red).
            assertTrue("lift at $v: %06X".format(rgb(up)), (up shr 16 and 0xFF) > (up and 0xFF) + 60)
            assertTrue("sink at $v: %06X".format(rgb(down)), (down and 0xFF) > (down shr 16 and 0xFF) + 20)
            v += 0.25f
        }
    }

    @Test fun stillAirIsNotDrawnAndOpacityIsCoverageOnly() {
        assertEquals(0, HeatGrid.color(0.1f, scale, 1f))
        assertEquals(0, HeatGrid.color(-0.15f, scale, 1f))
        // Weak and strong air are equally opaque: the colour carries the strength.
        assertEquals(HeatGrid.ALPHA, alpha(HeatGrid.color(0.4f, scale, 1f)))
        assertEquals(HeatGrid.ALPHA, alpha(HeatGrid.color(4f, scale, 1f)))
        assertTrue(alpha(HeatGrid.color(2f, scale, 0.3f)) < HeatGrid.ALPHA / 2)
    }

    @Test fun oneCellBecomesASoftBlob() {
        val g = HeatGrid(16)
        g.clear()
        g.splat(8, 8, 2f, 1f)
        g.render(scale)
        val centre = alpha(g.pixels[8 * 16 + 8])
        val next = alpha(g.pixels[8 * 16 + 9])
        val edge = alpha(g.pixels[8 * 16 + 10])
        assertEquals(HeatGrid.ALPHA, centre)
        assertTrue("fades outwards: $centre > $next > $edge > 0", centre > next && next > edge && edge > 0)
        assertEquals(0, g.pixels[8 * 16 + 11])
        // Same value everywhere it reaches: only the opacity falls off.
        assertEquals(rgb(g.pixels[8 * 16 + 8]), rgb(g.pixels[8 * 16 + 9]))
        // Symmetric.
        assertEquals(g.pixels[8 * 16 + 9], g.pixels[8 * 16 + 7])
        assertEquals(g.pixels[8 * 16 + 9], g.pixels[9 * 16 + 8])
    }

    @Test fun neighbouringLiftAndSinkBlendThroughNothing() {
        val g = HeatGrid(16)
        g.clear()
        g.splat(6, 8, 2f, 1f)
        g.splat(8, 8, -2f, 1f)
        g.render(scale)
        // Exactly between equal lift and sink the average is zero: transparent.
        assertEquals(0, g.pixels[8 * 16 + 7])
        assertTrue(luma(g.pixels[8 * 16 + 6]) > luma(g.pixels[8 * 16 + 8]) + 60)
    }

    @Test fun oldCellsCountForLess() {
        val g = HeatGrid(16)
        g.clear()
        g.splat(8, 8, 2f, 0.3f)
        g.render(scale)
        val faded = alpha(g.pixels[8 * 16 + 8])
        assertTrue(faded > 0 && faded < HeatGrid.ALPHA)
        // A fresh sample on the same spot outweighs an old one: the result is on the sink side.
        g.splat(8, 8, -2f, 1f)
        g.render(scale)
        assertTrue(luma(g.pixels[8 * 16 + 8]) < luma(HeatGrid.color(0.3f, scale, 1f)))
    }

    @Test fun aDensePatchOfOldSamplesIsAsFadedAsOneOldSample() {
        val lone = HeatGrid(16)
        lone.clear(); lone.splat(8, 8, 2f, 0.5f); lone.render(scale)
        val dense = HeatGrid(16)
        dense.clear()
        for (y in 4..12) for (x in 4..12) dense.splat(x, y, 2f, 0.5f)
        dense.render(scale)
        assertEquals(alpha(lone.pixels[8 * 16 + 8]), alpha(dense.pixels[8 * 16 + 8]))
        assertEquals(HeatGrid.ALPHA / 2.0, alpha(dense.pixels[8 * 16 + 8]).toDouble(), 2.0)
    }

    @Test fun edgesAndClearAreSafe() {
        val g = HeatGrid(8)
        g.clear()
        for (p in listOf(-5, -2, -1, 0, 7, 8, 9, 20)) { g.splat(p, p, 1f, 1f); g.splat(p, 3, 1f, 1f) }
        g.render(scale)
        g.clear()
        g.render(scale)
        assertTrue(g.pixels.all { it == 0 })
    }
}
