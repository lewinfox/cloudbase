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

    // Summaries by path and size: a finished log never changes, so each is read once per run.
    private val summaries = java.util.concurrent.ConcurrentHashMap<String, java.util.Optional<IgcSummary>>()
    private fun key(f: File) = "${f.path}:${f.length()}"

    fun isRead(f: File) = summaries.containsKey(key(f))
    fun cached(f: File): IgcSummary? = summaries[key(f)]?.orElse(null)

    /** Reads and caches a log's summary. Slow for long flights: call off the main thread. */
    fun read(f: File) {
        val s = try { f.bufferedReader().use { IgcSummary.read(it) } } catch (e: Exception) { null }
        summaries[key(f)] = java.util.Optional.ofNullable(s)
    }
}
