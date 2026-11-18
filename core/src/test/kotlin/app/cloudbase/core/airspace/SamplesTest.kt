package app.cloudbase.core.airspace

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Optional check against real OpenAir exports (not committed; licence CC BY-NC). Point the
 * `AIRSPACE_SAMPLES` environment variable at a directory of `*.txt` files to run it; otherwise it
 * is skipped. Results are printed to the test's standard output.
 */
class SamplesTest {
    @Test
    fun parseRealFiles() {
        val dirName = System.getProperty("airspaceSamples").orEmpty()
        assumeTrue("AIRSPACE_SAMPLES not set", dirName.isNotEmpty())
        val dir = File(dirName)
        assumeTrue("$dirName is not a directory", dir.isDirectory)
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".txt") }.orEmpty().sortedBy { it.name }
        assumeTrue("no *.txt files in $dirName", files.isNotEmpty())

        val policy = AirspacePolicy()
        var largest: File? = null
        var largestMs = 0.0
        for (f in files) {
            if (largest == null || f.length() > largest.length()) largest = f
            val acLines = f.useLines { lines -> lines.count { it.trimStart().startsWith("AC ", ignoreCase = true) } }
            val t0 = System.nanoTime()
            val result = f.bufferedReader().use { OpenAir.parse(it) }
            val ms = (System.nanoTime() - t0) / 1e6
            if (f == largest) largestMs = ms
            val vertices = result.airspaces.sumOf { it.lats.size }
            println("SAMPLE ${f.name}: ${f.length() / 1024} KiB, AC lines=$acLines, airspaces=${result.airspaces.size}, " +
                "vertices=$vertices, warnings=${result.warnings.size}, parse=${"%.0f".format(ms)} ms")
            for (w in result.warnings) println("  WARNING $w")

            var maxLatSpan = 0.0
            var maxLonSpan = 0.0
            for (a in result.airspaces) {
                assertTrue("${a.name}: lat range", a.minLat <= a.maxLat && a.minLat >= -90.0 && a.maxLat <= 90.0)
                assertTrue("${a.name}: lon range", a.minLon <= a.maxLon && a.minLon >= -180.0 && a.maxLon <= 180.0)
                maxLatSpan = maxOf(maxLatSpan, a.maxLat - a.minLat)
                maxLonSpan = maxOf(maxLonSpan, a.maxLon - a.minLon)
                assertTrue("${a.name}: >= 3 vertices", a.lats.size >= 3)
            }
            println("  max bbox span: ${"%.2f".format(maxLatSpan)} deg lat, ${"%.2f".format(maxLonSpan)} deg lon")
            assertEquals("${f.name}: airspace count vs AC lines", acLines, result.airspaces.size)
            assertTrue("${f.name}: bbox spans are sane", maxLatSpan < 60.0 && maxLonSpan < 120.0)

            // Class/type -> relevance table, for eyeballing the policy.
            val table = result.airspaces.groupingBy { "cls=${it.cls.padEnd(3)} type=${it.type.padEnd(8)} -> ${policy.relevance(it)}" }.eachCount()
            for ((k, v) in table.entries.sortedByDescending { it.value }) println("  POLICY $k  x$v")
        }
        println("SAMPLE largest file ${largest!!.name} parsed in ${"%.0f".format(largestMs)} ms")
    }
}
