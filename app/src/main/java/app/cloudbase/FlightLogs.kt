package app.cloudbase

import app.cloudbase.core.FlightLogSink
import app.cloudbase.core.igc.IgcSummary
import app.cloudbase.core.igc.IgcWriter
import java.io.BufferedWriter
import java.io.File

/** IGC files on disk: opened by the flight computer at takeoff, listed by the Flights screen. */
class FlightLogs(val dir: File) : FlightLogSink {
    private var out: BufferedWriter? = null
    var current: File? = null; private set

    init { dir.mkdirs() }

    override fun open(firstFixMs: Long): Appendable? = try {
        dir.mkdirs()
        var seq = 1
        while (File(dir, IgcWriter.fileName(firstFixMs, seq)).exists()) seq++
        val f = File(dir, IgcWriter.fileName(firstFixMs, seq))
        current = f
        f.bufferedWriter(Charsets.US_ASCII).also { out = it }
    } catch (e: Exception) { null }

    override fun flush() { try { out?.flush() } catch (e: Exception) { /* disk full: keep flying */ } }

    override fun close() {
        try { out?.close() } catch (e: Exception) { }
        out = null
        current = null
    }

    /** Newest first. */
    fun list(): List<File> = dir.listFiles { f -> f.name.endsWith(".igc", ignoreCase = true) }?.sortedByDescending { it.name } ?: emptyList()

    fun summary(f: File): IgcSummary? = try { f.bufferedReader().use { IgcSummary.read(it) } } catch (e: Exception) { null }
}
