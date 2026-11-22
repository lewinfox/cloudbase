package app.cloudbase

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import app.cloudbase.core.airspace.Airspace
import app.cloudbase.core.airspace.AirspaceInfo
import app.cloudbase.core.airspace.AirspaceStatus
import app.cloudbase.core.airspace.AlertLevel
import app.cloudbase.core.airspace.VerticalRelation
import app.cloudbase.core.airspace.contains
import app.cloudbase.core.flight.FlightState
import app.cloudbase.map.MapView
import app.cloudbase.map.TileLayer
import app.cloudbase.map.TileSource
import app.cloudbase.ui.AircraftOverlay
import app.cloudbase.ui.AirspaceOverlay
import app.cloudbase.ui.AirspaceStyle
import app.cloudbase.ui.AlertBanner
import app.cloudbase.ui.Brand
import app.cloudbase.ui.AirspacePage
import app.cloudbase.ui.FlightsPage
import app.cloudbase.ui.GlyphButton
import app.cloudbase.ui.MenuSheet
import app.cloudbase.ui.InstrumentBar
import app.cloudbase.ui.OfflinePage
import app.cloudbase.ui.Page
import app.cloudbase.ui.RecordButton
import app.cloudbase.ui.SettingsPage
import app.cloudbase.ui.SideView
import app.cloudbase.ui.Theme
import app.cloudbase.ui.Theme.dp
import app.cloudbase.ui.ThermalOverlay
import app.cloudbase.ui.Units
import kotlin.math.roundToInt

/**
 * The only activity: instruments on top, the map in the middle, the side view below. Plain
 * framework views built in code. The screen is repainted when the flight computer has new data
 * (once per GPS fix), never on a free-running timer.
 */
class MainActivity : Activity() {
    private lateinit var instruments: InstrumentBar
    private lateinit var map: MapView
    private lateinit var layer: TileLayer
    private lateinit var side: SideView
    private lateinit var banner: AlertBanner
    private lateinit var chip: TextView
    private lateinit var record: RecordButton
    private lateinit var recenter: View
    private var menu: View? = null
    private lateinit var root: FrameLayout
    private val airspaceOverlay by lazy { AirspaceOverlay(this) }
    private var page: Page? = null

    private val handler = Handler(Looper.getMainLooper())
    private var lastVersion = -1L
    private var lastStatus = ""
    private var zoomedToFix = false
    private var exiting = false
    private var wasCircling = false
    /** Zoom to go back to when the thermal is left; NaN when we did not zoom in for it. */
    private var zoomBeforeThermal = Double.NaN
    // Zoom levels as they look on screen, whatever the pixel density.
    private val flyingZoom get() = FLYING_ZOOM + map.densityZoom
    private val closeZoom get() = THERMAL_ZOOM + map.densityZoom
    private val tick = object : Runnable {
        override fun run() {
            val v = Hub.computer.version
            if (v != lastVersion || Hub.gpsStatus != lastStatus) {
                lastVersion = v
                lastStatus = Hub.gpsStatus
                refresh()
            }
            handler.postDelayed(this, 250)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Hub.init(this)
        val s = Hub.settings

        layer = TileLayer(Hub.tiles, TileSource.OPEN_TOPO)
        map = MapView(this, layer, TileSource.OPEN_TOPO).apply {
            setCenter(s.mapLat, s.mapLon)
            zoom = s.mapZoom
            overlays.add(ThermalOverlay(context))
            overlays.add(airspaceOverlay)
            overlays.add(AircraftOverlay(context))
            onFollowChanged = { following -> recenter.visibility = if (following) View.GONE else View.VISIBLE }
            onLongPress = { lat, lon -> showAirspaceAt(lat, lon) }
        }
        Hub.elevation.onTileLoaded = { lastVersion = -1 }
        Hub.airspace.onChanged = { airspaceChanged(); page?.rebuild() }

        instruments = InstrumentBar(this)
        side = SideView(this) { map.projection.metresPerPixel }
        map.onZoomChanged = { if (side.visibility == View.VISIBLE) side.refresh() }
        banner = AlertBanner(this) { Hub.computer.dismissBanner(); refresh() }
        chip = Theme.body(this, "", Theme.TEXT, 12.5f).apply {
            typeface = Theme.CONDENSED_BOLD
            background = GradientDrawable().apply { cornerRadius = dp(15f); setColor(Color.WHITE) }
            elevation = dp(3f)
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }
        record = RecordButton(this) { toggleRecording() }
        recenter = GlyphButton(this, GlyphButton.Glyph.RECENTRE) { map.follow = true; Hub.computer.fix?.let { map.setCenter(it.lat, it.lon) } }.apply { visibility = View.GONE }

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // Room for the buttons' shadows, which a tight parent would clip.
            clipToPadding = false
            setPadding(dp(6), dp(4), dp(4), dp(6))
            addView(GlyphButton(context, GlyphButton.Glyph.MENU) { showMenu() }, buttonLp())
            addView(GlyphButton(context, GlyphButton.Glyph.PLUS) { map.zoom = map.zoom + 1 }, buttonLp())
            addView(GlyphButton(context, GlyphButton.Glyph.MINUS) { map.zoom = map.zoom - 1 }, buttonLp())
            addView(recenter, buttonLp())
        }
        val mapFrame = FrameLayout(this).apply {
            addView(map, FrameLayout.LayoutParams(-1, -1))
            addView(buttons, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(0, dp(6), dp(6), 0) })
            // Clear of the record button, level with its centre.
            addView(chip, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(78), dp(25)) })
            addView(record, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(12), dp(12)) })
        }
        root = FrameLayout(this).apply {
            fitsSystemWindows = true
            // The status bar sits on the same sky blue as the headers.
            setBackgroundColor(Brand.SKY_BOTTOM)
            addView(LinearLayout(context).apply {
                setBackgroundColor(Theme.BG)
                orientation = LinearLayout.VERTICAL
                addView(instruments, LinearLayout.LayoutParams(-1, -2))
                addView(banner, LinearLayout.LayoutParams(-1, -2))
                addView(mapFrame, LinearLayout.LayoutParams(-1, 0, 1f))
                addView(side, LinearLayout.LayoutParams(-1, dp(150)))
            })
        }
        setContentView(root)
        applyLayout()
        ensurePermissions()
    }

    override fun onStart() {
        super.onStart()
        visible = true
        lastVersion = -1
        // The service may have stopped itself (long wait with the screen off) or been stopped from
        // its notification; coming back to the app always brings it back.
        if (!Hub.serviceRunning && !exiting) restartService()
        handler.post(tick)
    }

    override fun onStop() {
        visible = false
        handler.removeCallbacks(tick)
        val s = Hub.settings
        s.mapLat = map.centerLat; s.mapLon = map.centerLon; s.mapZoom = map.zoom
        super.onStop()
    }

    override fun onDestroy() {
        Hub.elevation.onTileLoaded = null
        Hub.airspace.onChanged = null
        layer.shutdown()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (menu != null) closeMenu() else if (page != null) closePage() else @Suppress("DEPRECATION") super.onBackPressed()
    }

    // --- per-fix refresh ---------------------------------------------------------------------

    private fun refresh() {
        val c = Hub.computer
        instruments.refresh()
        c.fix?.let {
            if (map.follow) {
                // First fix: come in from the country-wide opening view to flying scale.
                if (!zoomedToFix) { zoomedToFix = true; if (map.zoom < flyingZoom - 2) map.zoom = flyingZoom }
                thermalZoom(c.circling)
                map.setCenter(it.lat, it.lon)
            }
        }
        map.invalidate()
        if (side.visibility == View.VISIBLE) side.refresh()
        showBanner(c.banner, c.info)

        record.recording = c.recording
        val rec = if (c.recording) {
            val s = ((c.fix?.timeMs ?: 0L) - c.recordStartMs).coerceAtLeast(0) / 1000
            "REC %d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60)
        } else if (c.flightState == FlightState.FLYING) "Not recording" else ""
        val text = listOf(rec, Hub.gpsStatus).filter { it.isNotEmpty() }.joinToString(" · ")
        chip.text = text
        chip.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
        chip.setTextColor(if (c.recording) Theme.DANGER else Theme.TEXT)
    }

    /**
     * Start is immediate. Stop asks first: a gloved mis-tap in the air would otherwise end the
     * log, and with automatic recording it would not restart until the next landing.
     */
    private fun toggleRecording() {
        val c = Hub.computer
        if (!c.recording) {
            if (!c.startRecording()) toast("No GPS fix yet")
            refresh()
            return
        }
        AlertDialog.Builder(this).setMessage("Stop recording this flight?")
            .setPositiveButton("Stop recording") { _, _ -> c.stopRecording(); refresh() }
            .setNegativeButton("Keep recording", null).show()
    }

    private fun toast(msg: String) = android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()

    /**
     * Zoom in close while circling, so the lift/sink map around the glider is big enough to
     * centre on, and back out to where the pilot had it when the thermal is left. Only while
     * the map is following: a pilot who has panned away is looking at something else.
     */
    private fun thermalZoom(circling: Boolean) {
        if (circling == wasCircling) return
        wasCircling = circling
        if (circling) {
            if (Hub.settings.thermalZoom && map.zoom < closeZoom) {
                zoomBeforeThermal = map.zoom
                map.zoom = closeZoom
            }
        } else if (!zoomBeforeThermal.isNaN()) {
            // Leave the zoom alone if the pilot changed it during the climb.
            if (map.zoom == closeZoom) map.zoom = zoomBeforeThermal
            zoomBeforeThermal = Double.NaN
        }
    }

    private fun showBanner(s: AirspaceStatus?, info: AirspaceInfo?) {
        if (s != null) {
            banner.show(describe(s, Units(Hub.settings)), if (s.level == AlertLevel.APPROACHING) AlertBanner.Kind.WARN else AlertBanner.Kind.DANGER)
        } else if (info != null) {
            banner.show(describe(info), AlertBanner.Kind.INFO)
        } else {
            banner.hide()
        }
    }

    // --- menu and pages ------------------------------------------------------------------------

    private fun showMenu() {
        closeMenu()
        val sheet = MenuSheet(this, listOf(
            "Flights" to { open(FlightsPage(this)) },
            "Airspace" to { open(AirspacePage(this)) },
            "Offline maps" to { open(OfflinePage(this)) },
            "Settings" to { open(SettingsPage(this)) },
            "Stop and exit" to { confirmExit() },
        )) { closeMenu() }
        menu = sheet
        root.addView(sheet, FrameLayout.LayoutParams(-1, -1))
    }

    private fun closeMenu() {
        menu?.let { root.removeView(it) }
        menu = null
    }

    private fun confirmExit() {
        val exit = { exiting = true; FlightService.stop(this); finish() }
        if (!Hub.computer.recording) { exit(); return }
        AlertDialog.Builder(this).setMessage("A flight is being recorded. Stop the log and exit?")
            .setPositiveButton("Stop and exit") { _, _ -> exit() }.setNegativeButton("Keep flying", null).show()
    }

    /** Show a page over the map; used by pages that lead to further pages. */
    fun openPage(p: Page) = open(p)

    private fun open(p: Page) {
        closePage()
        page = p
        p.rebuild()
        root.addView(p.view, FrameLayout.LayoutParams(-1, -1))
    }

    fun closePage() {
        page?.let { root.removeView(it.view) }
        page = null
        lastVersion = -1
    }

    /** Apply the settings that change the layout itself. */
    fun applyLayout() {
        val s = Hub.settings
        side.visibility = if (s.showSideView) View.VISIBLE else View.GONE
        if (s.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        lastVersion = -1
    }

    fun airspaceChanged() {
        airspaceOverlay.invalidate()
        lastVersion = -1
    }

    /** [minLat, minLon, maxLat, maxLon] of what the map shows, for the offline download. */
    fun mapBounds(): DoubleArray? {
        val p = map.projection
        return if (map.width == 0) null else doubleArrayOf(p.minLat, p.minLon, p.maxLat, p.maxLon)
    }

    private fun showAirspaceAt(lat: Double, lon: Double) {
        val hits = airspaceOverlay.visible.filter { it.contains(lat, lon) }
        if (hits.isEmpty()) return
        val labels = hits.map { "${AirspaceStyle.title(it)}\n${it.lower.text} – ${it.upper.text}" }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Airspace here").setItems(labels) { _, i -> showAirspace(hits[i]) }
            .setNegativeButton("Close", null).show()
    }

    private fun showAirspace(a: Airspace) {
        val s = Hub.settings
        val text = buildString {
            append("From ${a.lower.text} to ${a.upper.text}")
            if (a.type.isNotEmpty()) append("\nType ${a.type}")
            append("\nListed as class ${a.cls} (the name is more reliable)")
            a.frequency?.let { append("\nFrequency $it") }
        }
        AlertDialog.Builder(this).setTitle(a.name).setMessage(text)
            .setPositiveButton("Close", null)
            .setNeutralButton("Ignore this airspace") { _, _ ->
                s.ignoredAirspaces = s.ignoredAirspaces + a.name
                Hub.applySettings()
                airspaceChanged()
            }.show()
    }

    // --- permissions, service, file import -------------------------------------------------

    private fun ensurePermissions() {
        val need = ArrayList<String>()
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            // Android 12+ ignores a request for precise location unless approximate is asked for with it.
            need.add(Manifest.permission.ACCESS_COARSE_LOCATION)
            need.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.POST_NOTIFICATIONS)
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), REQ_PERMISSIONS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == REQ_PERMISSIONS) restartService()
    }

    /** Start the flight service if we are allowed to use the GPS. */
    fun restartService() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) FlightService.start(this)
        else Hub.gpsStatus = "Location permission needed"
    }

    fun pickAirspaceFile() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQ_IMPORT)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val uri = data?.data
        if (requestCode != REQ_IMPORT || resultCode != RESULT_OK || uri == null) return
        val display = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "airspace"
        // Parsing a national file takes a moment; keep it off the main thread.
        Thread {
            val name = try { contentResolver.openInputStream(uri)?.use { Hub.airspace.import(display, it) } } catch (e: Exception) { null }
            handler.post {
                if (isFinishing || isDestroyed) return@post
                if (name == null) {
                    AlertDialog.Builder(this).setMessage("That file does not look like OpenAir airspace.").setPositiveButton("OK", null).show()
                } else {
                    Hub.settings.countries = Hub.settings.countries + name
                    Hub.airspace.load(Hub.settings.countries)
                }
            }
        }.start()
    }

    private fun buttonLp() = LinearLayout.LayoutParams(dp(44), dp(44)).apply { bottomMargin = dp(8) }

    companion object {
        const val REQ_PERMISSIONS = 1
        const val REQ_IMPORT = 2
        /** About 3 km across the screen: enough to see where you are going. */
        const val FLYING_ZOOM = 14.0
        /** About 600 m across: one thermal fills the map. */
        const val THERMAL_ZOOM = 16.0
        /** Whether the UI is in front; the service uses it to decide when it has been forgotten. */
        @Volatile var visible = false

        /** Dropped through a floor: the zone you are in now, and the one above you left. */
        fun describe(n: AirspaceInfo): String {
            fun names(l: List<Airspace>) = l.joinToString(", ") { AirspaceStyle.title(it) }
            return "IN ${names(n.entered)} · below ${names(n.left)}"
        }

        /** One line for the banner: what, where, how soon. */
        fun describe(s: AirspaceStatus, u: Units): String {
            val a = s.airspace
            val name = AirspaceStyle.title(a)
            val secs = if (s.timeToEntryS.isNaN() || s.timeToEntryS < 1) "" else " in ${s.timeToEntryS.roundToInt()} s"
            return when (s.level) {
                AlertLevel.INSIDE -> "INSIDE $name · ${a.lower.text}–${a.upper.text}"
                AlertLevel.CEILING -> "CEILING$secs · $name · top ${a.upper.text}, ${u.altWithUnit(s.verticalM)} above"
                else -> "APPROACHING $name$secs · ${where(s, u)}"
            }
        }

        private fun where(s: AirspaceStatus, u: Units): String {
            val side = if (s.horizontalM > 0) "${s.horizontalM.roundToInt()} m away" else ""
            val vert = when (s.relation) {
                VerticalRelation.BELOW -> "floor ${u.altWithUnit(s.verticalM)} above"
                VerticalRelation.ABOVE -> "top ${u.altWithUnit(s.verticalM)} below"
                VerticalRelation.WITHIN -> "at your height"
            }
            return if (side.isEmpty()) vert else "$side, $vert"
        }
    }
}
