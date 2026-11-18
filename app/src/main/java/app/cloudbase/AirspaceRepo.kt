package app.cloudbase

import android.os.Handler
import android.os.Looper
import app.cloudbase.core.airspace.Airspace
import app.cloudbase.core.airspace.AirspaceIndex
import app.cloudbase.core.airspace.OpenAir
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Airspace files on disk (OpenAir text, one per country or imported file) and the parsed index
 * built from them. Files come from openAIP's public exports, which need no account or key.
 */
class AirspaceRepo(private val dir: File) {
    class Country(val code: String, val name: String, val minLon: Double, val minLat: Double, val maxLon: Double, val maxLat: Double) {
        fun contains(lat: Double, lon: Double) = lat in minLat..maxLat && lon in minLon..maxLon
    }

    var index: AirspaceIndex? = null; private set
    /** Human-readable result of the last load, e.g. "1,204 airspaces · 2 warnings". */
    var summary = "No airspace loaded"; private set
    var warnings: List<String> = emptyList(); private set
    var loading = false; private set
    var onChanged: (() -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private var loadGeneration = 0

    init { dir.mkdirs() }

    fun file(code: String) = File(dir, "$code.txt")
    fun downloaded(code: String) = file(code).length() > 0
    fun ageDays(code: String): Long = (System.currentTimeMillis() - file(code).lastModified()) / 86_400_000L

    /** Files that were imported by hand rather than downloaded by country code. */
    fun imported(): List<String> = dir.list()?.filter { it.startsWith(IMPORT_PREFIX) }?.map { it.removeSuffix(".txt") }?.sorted() ?: emptyList()

    fun countries(): List<Country> = try {
        Hub.app.assets.open("airspace_countries.tsv").bufferedReader().useLines { lines ->
            lines.mapNotNull { line ->
                val f = line.split('\t')
                val b = f.getOrNull(2)?.split(',')?.mapNotNull { it.toDoubleOrNull() }
                if (f.size < 3 || b == null || b.size != 4) null else Country(f[0], f[1], b[0], b[1], b[2], b[3])
            }.sortedBy { it.name }.toList()
        }
    } catch (e: Exception) { emptyList() }

    /** Parse the given files (country codes or import names) off the main thread and publish the index. */
    fun load(names: Set<String>) {
        loading = true
        // Loads can overlap (first-run download, an import, an update); only the newest may publish.
        val generation = ++loadGeneration
        thread(name = "airspace-load", isDaemon = true) {
            val all = ArrayList<Airspace>()
            val warn = ArrayList<String>()
            for (name in names.sorted()) {
                val f = file(name)
                if (!f.exists()) continue
                try {
                    val r = f.bufferedReader().use { OpenAir.parse(it) }
                    all.addAll(r.airspaces)
                    r.warnings.mapTo(warn) { "$name: $it" }
                } catch (e: Exception) {
                    warn.add("$name: ${e.message}")
                }
            }
            main.post {
                if (generation != loadGeneration) return@post
                index = if (all.isEmpty()) null else AirspaceIndex(all)
                warnings = warn
                summary = if (all.isEmpty()) "No airspace loaded"
                else "%,d airspaces".format(all.size) + if (warn.isEmpty()) "" else " · ${warn.size} warnings"
                loading = false
                Hub.applySettings()
                onChanged?.invoke()
            }
        }
    }

    /** Fetch a country's file. [done] gets null on success or an error message, on the main thread. */
    fun download(code: String, done: (String?) -> Unit) {
        thread(name = "airspace-dl", isDaemon = true) {
            val error = try {
                val c = URL(URL_TEMPLATE.replace("{cc}", code)).openConnection() as HttpURLConnection
                c.connectTimeout = 10_000
                c.readTimeout = 30_000
                c.setRequestProperty("User-Agent", Hub.tiles.userAgent)
                try {
                    if (c.responseCode != 200) "Server said ${c.responseCode}"
                    else c.inputStream.use { save(code, it) }
                } finally { c.disconnect() }
            } catch (e: Exception) { e.message ?: e.javaClass.simpleName }
            main.post { done(error) }
        }
    }

    /** Store an OpenAir file picked by the user. Returns the name it was stored under, or null if it isn't OpenAir. */
    fun import(displayName: String, input: InputStream): String? {
        val name = IMPORT_PREFIX + displayName.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9_-]"), "_").take(40)
        return if (save(name, input) == null) name else null
    }

    fun delete(name: String) { file(name).delete() }

    /** Writes via a temp file so a dropped connection never leaves half an airspace file. Returns an error or null. */
    private fun save(name: String, input: InputStream): String? {
        val tmp = File(dir, "$name.${System.nanoTime()}.tmp")
        tmp.outputStream().use { input.copyTo(it) }
        val ok = tmp.bufferedReader().use { r -> OpenAir.parse(r).airspaces.isNotEmpty() }
        if (!ok) { tmp.delete(); return "Not an OpenAir airspace file" }
        if (tmp.renameTo(file(name))) return null
        tmp.delete()
        return "Could not save"
    }

    companion object {
        const val URL_TEMPLATE = "https://storage.openaip.net/openaip-system-exports/{cc}_asp_v2.txt"
        const val IMPORT_PREFIX = "file-"
    }
}
