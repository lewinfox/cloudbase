package app.cloudbase.ui

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.FileProvider
import app.cloudbase.FlightService
import app.cloudbase.Hub
import app.cloudbase.MainActivity
import app.cloudbase.map.RegionDownloader
import app.cloudbase.map.TileSource
import app.cloudbase.ui.Theme.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A full-screen list of rows over the map: the pattern every secondary screen uses. */
abstract class Page(protected val act: MainActivity, private val title: String) {
    protected val list = Theme.column(act, 20, 0)
    val view: View = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Theme.BG)
        isClickable = true // swallow touches so the map underneath does not pan
        // Header: the logo's sky, with its cloud.
        addView(Theme.row(act).apply {
            background = Brand.sky()
            setPadding(act.dp(6), 0, act.dp(18), 0)
            addView(TextView(act).apply {
                text = "‹"; textSize = 30f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
                background = Theme.ripple(null, Color.argb(60, 255, 255, 255))
                setOnClickListener { act.closePage() }
            }, LinearLayout.LayoutParams(act.dp(48), act.dp(56)))
            addView(Theme.body(act, title, Color.WHITE, 21f).apply { typeface = Theme.CONDENSED_BOLD; letterSpacing = 0.04f }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(Brand.cloud(act), LinearLayout.LayoutParams(act.dp(36), act.dp(24)))
        })
        addView(ScrollView(act).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    abstract fun build()

    fun rebuild() { list.removeAllViews(); build() }

    protected fun section(text: String) {
        list.addView(Theme.label(act, text, Theme.ACCENT).apply { setPadding(0, act.dp(22), 0, act.dp(6)) })
    }

    protected fun note(text: String) {
        list.addView(Theme.body(act, text, Theme.MUTED, 13f).apply { setPadding(0, act.dp(4), 0, act.dp(8)) })
    }

    protected fun row(name: String, value: String, onClick: (() -> Unit)? = null) {
        list.addView(Theme.row(act).apply {
            minimumHeight = act.dp(52)
            addView(Theme.body(act, name), LinearLayout.LayoutParams(0, -2, 1f))
            addView(Theme.body(act, value, if (onClick == null) Theme.MUTED else Theme.ACCENT).apply {
                gravity = Gravity.END
                setPadding(act.dp(12), 0, 0, 0)
            })
            if (onClick != null) { background = Theme.ripple(null); setOnClickListener { onClick() } }
        })
        list.addView(Theme.rule(act))
    }

    protected fun toggle(name: String, value: Boolean, set: (Boolean) -> Unit) {
        list.addView(Theme.row(act).apply {
            minimumHeight = act.dp(52)
            addView(Theme.body(act, name), LinearLayout.LayoutParams(0, -2, 1f))
            addView(SwitchView(act, value))
            contentDescription = "$name, ${if (value) "on" else "off"}"
            background = Theme.ripple(null)
            setOnClickListener { set(!value); Hub.applySettings(); rebuild() }
        })
        list.addView(Theme.rule(act))
    }

    protected fun button(text: String, primary: Boolean = false, danger: Boolean = false, onClick: () -> Unit) {
        list.addView(Theme.button(act, text, primary, danger, onClick), LinearLayout.LayoutParams(-1, -2).apply { topMargin = act.dp(12) })
    }

    protected fun number(title: String, current: Double, min: Double, max: Double, set: (Double) -> Unit) {
        val input = EditText(act).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(if (current % 1.0 == 0.0) current.toInt().toString() else current.toString())
            selectAll()
        }
        dialog(title, input) {
            val v = input.text.toString().replace(',', '.').toDoubleOrNull()
            if (v != null && v in min..max) { set(v); Hub.applySettings(); rebuild() }
        }
    }

    protected fun text(title: String, current: String, set: (String) -> Unit) {
        val input = EditText(act).apply { setText(current); setSingleLine(); selectAll() }
        dialog(title, input) { set(input.text.toString().trim()); Hub.applySettings(); rebuild() }
    }

    protected fun choose(title: String, options: List<String>, current: Int, set: (Int) -> Unit) {
        AlertDialog.Builder(act).setTitle(title)
            .setSingleChoiceItems(options.toTypedArray(), current) { d, i -> set(i); Hub.applySettings(); d.dismiss(); rebuild() }
            .show()
    }

    private fun dialog(title: String, input: EditText, ok: () -> Unit) {
        val wrap = LinearLayout(act).apply { setPadding(act.dp(20), act.dp(8), act.dp(20), 0); addView(input, LinearLayout.LayoutParams(-1, -2)) }
        AlertDialog.Builder(act).setTitle(title).setView(wrap)
            .setPositiveButton("OK") { _, _ -> ok() }.setNegativeButton("Cancel", null).show()
    }
}

class SettingsPage(act: MainActivity) : Page(act, "Settings") {
    override fun build() {
        val s = Hub.settings
        section("Glider")
        row("Trim speed", "${fmt(s.trimKmh)} km/h") { number("Trim (hands-up) airspeed, km/h", s.trimKmh, 20.0, 70.0) { s.trimKmh = it } }
        row("Sink rate at trim", "${fmt(s.trimSink)} m/s") { number("Sink rate at trim speed, m/s", s.trimSink, 0.5, 3.0) { s.trimSink = it } }
        note("Wind is measured from your drift whenever you circle. Trim speed is only used to estimate it from gentler turns before that.")

        section("Airspace alerts")
        toggle("Alert tone", s.alertSound) { s.alertSound = it }
        toggle("Spoken alert", s.alertVoice) { s.alertVoice = it }
        row("Mandatory broadcast zones", if (s.alertMbz) "Alert" else "Show only") { s.alertMbz = !s.alertMbz; Hub.applySettings(); act.airspaceChanged(); rebuild() }
        row("Warning time", "${fmt(s.lookaheadS)} s") { number("Warn this many seconds before a boundary", s.lookaheadS, 15.0, 180.0) { s.lookaheadS = it } }
        note("Warns when you could reach an airspace boundary within this time on your present course and climb. Boundaries count as closer by however far off the GPS (and barometer, if the phone has one) could be, so with a poor fix warnings come earlier. Each change is announced once: dismiss the banner with ✕ or a swipe left.")

        section("Display")
        row("Altitude", if (s.altFeet) "Feet" else "Metres") { choose("Altitude in", listOf("Metres", "Feet"), if (s.altFeet) 1 else 0) { s.altFeet = it == 1 } }
        val speeds = listOf("kmh", "kt", "mph")
        row("Speed", listOf("km/h", "knots", "mph")[speeds.indexOf(s.speedUnit).coerceAtLeast(0)]) {
            choose("Speed in", listOf("km/h", "knots", "mph"), speeds.indexOf(s.speedUnit).coerceAtLeast(0)) { s.speedUnit = speeds[it] }
        }
        toggle("Thermal heat map", s.showThermals) { s.showThermals = it }
        val palette = HeatGrid.Palette.values().firstOrNull { it.name == s.liftColours } ?: HeatGrid.Palette.TURBO
        row("Thermal colours", palette.label) { act.openPage(ColoursPage(act)) }
        toggle("Zoom in when thermalling", s.thermalZoom) { s.thermalZoom = it }
        toggle("Glide-to-ground line", s.showGlide) { s.showGlide = it }
        toggle("Side view", s.showSideView) { s.showSideView = it; act.applyLayout() }
        toggle("Keep screen on", s.keepScreenOn) { s.keepScreenOn = it; act.applyLayout() }

        section("Flight log")
        toggle("Start and stop automatically", s.autoRecord) { s.autoRecord = it }
        row("Pilot", s.pilot.ifEmpty { "Not set" }) { text("Pilot name", s.pilot) { s.pilot = it } }
        row("Glider", s.glider.ifEmpty { "Not set" }) { text("Glider make and model", s.glider) { s.glider = it } }
        row("Glider ID", s.gliderId.ifEmpty { "Not set" }) { text("Glider ID or registration", s.gliderId) { s.gliderId = it } }

        section("Try it out")
        toggle("Demo flight", s.demo) {
            s.demo = it
            // The service reads this at start, so restart it on the other data source.
            FlightService.stop(act)
            act.restartService()
            rebuild()
        }
        note("Replays a real 42-minute flight from Coronet Peak at 4× speed, through the same code as live sensors. It is inside the Queenstown control zone throughout, so the airspace alert shows straight away; press and hold on the zone to ignore it.")
        note("Cloudbase ${Hub.version()} · Map © OpenStreetMap contributors, SRTM; style © OpenTopoMap (CC-BY-SA) · Terrain: Mapzen/AWS terrain tiles · Airspace: openAIP (CC BY-NC 4.0)")
    }

    private fun fmt(v: Double) = if (v % 1.0 == 0.0) v.toInt().toString() else v.toString()
}

class FlightsPage(act: MainActivity) : Page(act, "Flights") {
    private var reading = false
    private val date = SimpleDateFormat("EEE d MMM yyyy, HH:mm", Locale.getDefault())

    override fun build() {
        val c = Hub.computer
        val u = Units(Hub.settings)
        if (c.recording) {
            button("Stop recording now", danger = true) { c.stopRecording(); rebuild() }
        } else {
            button("Start recording now") { c.startRecording(); rebuild() }
        }
        note("Logs start at takeoff and stop at landing by themselves, or use the red record button on the map. Files are standard IGC; they carry no validation signature, so some competitions will not accept them.")
        section("Logbook")
        val files = Hub.logs.list()
        val pending = ArrayList<java.io.File>()
        if (files.isEmpty()) note("No flights yet.")
        for (f in files) {
            if (f == Hub.logs.current) { row(f.name, "Recording…"); continue }
            val s = Hub.logs.cached(f)
            if (s == null && !Hub.logs.isRead(f)) pending.add(f)
            val line = if (s == null) (if (Hub.logs.isRead(f)) "Empty log" else "Reading…") else
                "${s.durationS / 3600}h ${"%02d".format(s.durationS / 60 % 60)}m · max ${u.altWithUnit(s.maxAltM.toDouble())} · ${"%.1f".format(s.trackLengthM / 1000.0)} km flown"
            list.addView(Theme.column(act, 0, 10).apply {
                addView(Theme.body(act, if (s == null) f.name else date.format(Date(s.dateUtcMs))))
                addView(Theme.body(act, line, Theme.MUTED, 13f))
                background = Theme.ripple(null)
                setOnClickListener {
                    AlertDialog.Builder(act).setTitle(f.name)
                        .setItems(arrayOf("Share or upload", "Delete")) { _, i ->
                            if (i == 0) share(f) else AlertDialog.Builder(act).setMessage("Delete this flight log?")
                                .setPositiveButton("Delete") { _, _ -> f.delete(); rebuild() }.setNegativeButton("Cancel", null).show()
                        }.show()
                }
            })
            list.addView(Theme.rule(act))
        }
        if (pending.isNotEmpty()) readSummaries(pending)
    }

    /** Hours-long logs take a moment to read, so that happens off the main thread, once per file. */
    private fun readSummaries(files: List<java.io.File>) {
        if (reading) return
        reading = true
        Thread {
            for (f in files) Hub.logs.read(f)
            list.post { reading = false; rebuild() }
        }.start()
    }

    private fun share(f: java.io.File) {
        val uri = FileProvider.getUriForFile(act, "${act.packageName}.files", f)
        val send = Intent(Intent.ACTION_SEND).setType("application/octet-stream")
            .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        act.startActivity(Intent.createChooser(send, f.name))
    }
}

class AirspacePage(act: MainActivity) : Page(act, "Airspace") {
    private var busy = ""

    override fun build() {
        val repo = Hub.airspace
        val s = Hub.settings
        note(if (repo.loading) "Loading…" else repo.summary)
        if (busy.isNotEmpty()) note(busy)

        section("Countries")
        val all = repo.countries()
        val here = Hub.computer.fix?.let { f -> all.filter { it.contains(f.lat, f.lon) && it.code !in s.countries } } ?: emptyList()
        for (c in here) button("Download ${c.name} (you are here)", primary = true) { download(c.code) }
        for (code in s.countries.sorted()) {
            val name = all.firstOrNull { it.code == code }?.name ?: code.removePrefix(app.cloudbase.AirspaceRepo.IMPORT_PREFIX)
            val age = repo.ageDays(code)
            row(name, if (age > 28) "$age days old" else "Up to date") {
                val imported = code.startsWith(app.cloudbase.AirspaceRepo.IMPORT_PREFIX)
                val items = if (imported) arrayOf("Remove") else arrayOf("Update now", "Remove")
                AlertDialog.Builder(act).setTitle(name).setItems(items) { _, i ->
                    if (!imported && i == 0) download(code)
                    else { repo.delete(code); s.countries = s.countries - code; repo.load(s.countries); rebuild() }
                }.show()
            }
        }
        button("Add a country") {
            AlertDialog.Builder(act).setTitle("Airspace for")
                .setItems(all.map { it.name }.toTypedArray()) { _, i -> download(all[i].code) }.show()
        }
        button("Import an OpenAir file") { act.pickAirspaceFile() }
        note("Airspace changes. Update before a flying trip, and check NOTAMs: temporary restrictions are not in these files.")

        if (s.ignoredAirspaces.isNotEmpty()) {
            section("Ignored")
            for (name in s.ignoredAirspaces.sorted()) row(name, "Restore") { s.ignoredAirspaces = s.ignoredAirspaces - name; Hub.applySettings(); act.airspaceChanged(); rebuild() }
        }
        if (repo.warnings.isNotEmpty()) {
            section("Problems reading files")
            note(repo.warnings.take(12).joinToString("\n"))
        }
        note("Data: openAIP, CC BY-NC 4.0. Not an official source; you remain responsible for knowing the airspace you fly in.")
    }

    private fun download(code: String) {
        busy = "Downloading…"
        rebuild()
        Hub.airspace.download(code) { error ->
            busy = error?.let { "Download failed: $it" } ?: ""
            if (error == null) {
                Hub.settings.countries = Hub.settings.countries + code
                Hub.airspace.load(Hub.settings.countries)
            }
            rebuild()
        }
    }
}

class OfflinePage(act: MainActivity) : Page(act, "Offline maps") {
    private val downloader = Hub.downloader
    private var status = ""
    /** Stored size in MB; measured off the main thread because it walks every tile file. */
    private var storedMb = -1.0
    private var measuring = false

    override fun build() {
        val s = Hub.settings
        note("Every map tile you look at is kept on the phone, so anywhere you have viewed works without signal. To prepare a flying area in full detail, frame it on the map, then download it here.")
        row("Stored on this phone", if (storedMb < 0) "…" else "%.0f MB".format(storedMb))
        row("Storage limit", "${s.mapCacheMb.toInt()} MB") {
            choose("Keep at most", LIMITS_MB.map { "$it MB" }, LIMITS_MB.indexOf(s.mapCacheMb.toInt())) {
                s.mapCacheMb = LIMITS_MB[it].toDouble()
                Hub.tiles.maxBytes = LIMITS_MB[it] * 1024L * 1024L
                storedMb = -1.0
                Thread { Hub.tiles.trim(); list.post { rebuild() } }.start()
            }
        }
        note("When the limit is reached, the tiles you have gone longest without looking at are deleted first, so your usual sites stay. Terrain heights are small and are always kept.")
        if (status.isNotEmpty()) note(status)
        if (downloader.running) {
            button("Cancel download", danger = true) { downloader.cancel(); status = "Cancelled"; rebuild() }
        } else {
            button("Download the area on screen", primary = true) { start() }
            button("Delete all stored maps", danger = true) {
                AlertDialog.Builder(act).setMessage("Delete all stored map and terrain tiles?")
                    .setPositiveButton("Delete") { _, _ -> Hub.tiles.clear(); storedMb = -1.0; rebuild() }.setNegativeButton("Cancel", null).show()
            }
        }
        if (storedMb < 0 && !measuring) {
            measuring = true
            Thread {
                val mb = Hub.tiles.sizeBytes() / 1e6
                list.post { measuring = false; storedMb = mb; rebuild() }
            }.start()
        }
    }

    private fun start() {
        val p = act.mapBounds() ?: return
        val plan = downloader.plan(TileSource.OPEN_TOPO, p[0], p[1], p[2], p[3], ZOOMS)
        if (plan.tiles > MAX_TILES) {
            status = "That area is too large (${plan.tiles} tiles). Zoom in and try again."
            rebuild(); return
        }
        // A download bigger than the limit would evict its own first tiles as it went.
        val roomFor = Hub.settings.mapCacheMb * 1e6 / AVERAGE_TILE_BYTES
        if (plan.tiles > roomFor * 0.8) {
            status = "That area needs about ${(plan.tiles * AVERAGE_TILE_BYTES / 1e6).toInt()} MB. Raise the storage limit first."
            rebuild(); return
        }
        status = "Starting…"
        rebuild()
        // Terrain first: it is small, and it is what airspace-above-ground limits depend on.
        downloader.start(TileSource.TERRARIUM, p[0], p[1], p[2], p[3], Hub.elevation.zoom..Hub.elevation.zoom, MAX_TILES, { _, _ -> }) { _ ->
            downloader.start(TileSource.OPEN_TOPO, p[0], p[1], p[2], p[3], ZOOMS, MAX_TILES,
                { done, total -> status = "Downloading $done of $total"; rebuild() },
                { ok -> status = if (ok) "Done. This area now works offline." else "Stopped before finishing; what was fetched is kept."; storedMb = -1.0; rebuild() })
        }
    }

    companion object {
        /** Overview levels up to the detail shown while thermalling on most screens. */
        val ZOOMS = 9..15
        const val MAX_TILES = 6000
        const val AVERAGE_TILE_BYTES = 30_000.0
        val LIMITS_MB = listOf(250, 500, 1000, 2000)
    }
}

/** Choose the thermal map's colour scale by looking at each one on a real piece of flight. */
class ColoursPage(act: MainActivity) : Page(act, "Thermal colours") {
    override fun build() {
        val s = Hub.settings
        note("The same climb from the demo flight, drawn in each scale over the real map. The bar under each shows sink on the left and lift on the right, in m/s. Tap one to use it.")
        // The sample is worked out once, off the main thread; the tiles fill in when it is ready.
        PaletteSample.load(act, { r -> list.post(r) }) { rebuild() }
        for (p in HeatGrid.Palette.values()) {
            val tile = PalettePreview(act, p, selected = p.name == s.liftColours)
            tile.contentDescription = p.label + if (p.name == s.liftColours) ", selected" else ""
            tile.setOnClickListener { s.liftColours = p.name; act.airspaceChanged(); rebuild() }
            list.addView(tile, LinearLayout.LayoutParams(-1, -2).apply { topMargin = act.dp(10) })
        }
        note("Rainbow has the most distinct colours. Viridis gets steadily brighter with lift. Heat uses no greens, so it has nothing in common with the map. Plasma is one continuous scale and is the only one that also changes the sink colours.")
    }
}
