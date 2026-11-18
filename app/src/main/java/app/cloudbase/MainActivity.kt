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
import app.cloudbase.ui.AirspacePage
import app.cloudbase.ui.FlightsPage
import app.cloudbase.ui.InstrumentBar
import app.cloudbase.ui.OfflinePage
import app.cloudbase.ui.Page
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
    private lateinit var banner: TextView
    private lateinit var chip: TextView
    private lateinit var recenter: TextView
    private lateinit var root: FrameLayout
    private val airspaceOverlay by lazy { AirspaceOverlay(this) }
    private val bannerShape = GradientDrawable()
    private var page: Page? = null

    private val handler = Handler(Looper.getMainLooper())
    private var lastVersion = -1L
    private var lastStatus = ""
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
            onTap = { lat, lon -> showAirspaceAt(lat, lon) }
        }
        layer.onTileLoaded = { map.invalidate() }
        Hub.elevation.onTileLoaded = { lastVersion = -1 }
        Hub.airspace.onChanged = { airspaceChanged(); page?.rebuild() }

        instruments = InstrumentBar(this)
        side = SideView(this)
        banner = Theme.body(this, "", Color.WHITE, 16f).apply {
            typeface = Theme.CONDENSED_BOLD
            background = bannerShape
            setPadding(dp(14), dp(10), dp(14), dp(10))
            visibility = View.GONE
        }
        bannerShape.cornerRadius = dp(8f)
        chip = Theme.body(this, "", Theme.TEXT, 12.5f).apply {
            typeface = Theme.CONDENSED_BOLD
            background = GradientDrawable().apply { cornerRadius = dp(14f); setColor(Color.argb(235, 255, 255, 255)); setStroke(1, Theme.LINE) }
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }
        recenter = mapButton("◎") { map.follow = true; Hub.computer.fix?.let { map.setCenter(it.lat, it.lon) } }.apply { visibility = View.GONE }

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(mapButton("≡") { showMenu() }, buttonLp())
            addView(mapButton("+") { map.zoom = (map.zoom + 1).roundToInt().toDouble() }, buttonLp())
            addView(mapButton("−") { map.zoom = (map.zoom - 1).roundToInt().toDouble() }, buttonLp())
            addView(recenter, buttonLp())
        }
        val mapFrame = FrameLayout(this).apply {
            addView(map, FrameLayout.LayoutParams(-1, -1))
            addView(banner, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { setMargins(dp(10), dp(10), dp(62), 0) })
            addView(buttons, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(0, dp(10), dp(10), 0) })
            addView(chip, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(10), dp(10)) })
        }
        root = FrameLayout(this).apply {
            fitsSystemWindows = true
            setBackgroundColor(Theme.BG)
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(instruments, LinearLayout.LayoutParams(-1, -2))
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
        layer.onTileLoaded = null
        Hub.elevation.onTileLoaded = null
        Hub.airspace.onChanged = null
        layer.shutdown()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (page != null) closePage() else @Suppress("DEPRECATION") super.onBackPressed()
    }

    // --- per-fix refresh ---------------------------------------------------------------------

    private fun refresh() {
        val c = Hub.computer
        instruments.refresh()
        c.fix?.let { if (map.follow) map.setCenter(it.lat, it.lon) }
        map.invalidate()
        if (side.visibility == View.VISIBLE) side.refresh()
        showBanner(c.statuses.firstOrNull())

        val rec = if (c.recording) {
            val s = ((c.fix?.timeMs ?: 0L) - c.takeoffTimeMs).coerceAtLeast(0) / 1000
            "● REC %d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60)
        } else if (c.flightState == FlightState.FLYING) "Flying · not recording" else "Ready · log starts at takeoff"
        chip.text = if (Hub.gpsStatus.isEmpty()) rec else if (c.recording) "$rec · ${Hub.gpsStatus}" else Hub.gpsStatus
        chip.setTextColor(if (c.recording) Theme.DANGER else Theme.TEXT)
    }

    private fun showBanner(s: AirspaceStatus?) {
        if (s == null) { banner.visibility = View.GONE; return }
        bannerShape.setColor(if (s.level == AlertLevel.NEAR) Theme.WARN else Theme.DANGER)
        banner.text = describe(s, Units(Hub.settings))
        banner.visibility = View.VISIBLE
    }

    // --- menu and pages ------------------------------------------------------------------------

    private fun showMenu() {
        val items = arrayOf("Flights", "Airspace", "Offline maps", "Settings", "Stop and exit")
        AlertDialog.Builder(this).setItems(items) { _, i ->
            when (i) {
                0 -> open(FlightsPage(this))
                1 -> open(AirspacePage(this))
                2 -> open(OfflinePage(this))
                3 -> open(SettingsPage(this))
                4 -> confirmExit()
            }
        }.show()
    }

    private fun confirmExit() {
        val exit = { FlightService.stop(this); finish() }
        if (!Hub.computer.recording) { exit(); return }
        AlertDialog.Builder(this).setMessage("A flight is being recorded. Stop the log and exit?")
            .setPositiveButton("Stop and exit") { _, _ -> exit() }.setNegativeButton("Keep flying", null).show()
    }

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
        val labels = hits.map { "${it.kind} ${it.name}\n${it.lower.text} – ${it.upper.text}" }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Airspace here").setItems(labels) { _, i -> showAirspace(hits[i]) }
            .setNegativeButton("Close", null).show()
    }

    private fun showAirspace(a: Airspace) {
        val s = Hub.settings
        val text = buildString {
            append("Class ${a.cls}"); if (a.type.isNotEmpty()) append(" · ${a.type}")
            append("\nFrom ${a.lower.text} to ${a.upper.text}")
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
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.POST_NOTIFICATIONS)
        if (need.isEmpty()) restartService() else requestPermissions(need.toTypedArray(), REQ_PERMISSIONS)
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
                if (name == null) {
                    AlertDialog.Builder(this).setMessage("That file does not look like OpenAir airspace.").setPositiveButton("OK", null).show()
                } else {
                    Hub.settings.countries = Hub.settings.countries + name
                    Hub.airspace.load(Hub.settings.countries)
                }
            }
        }.start()
    }

    private fun mapButton(text: String, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        textSize = 22f
        gravity = Gravity.CENTER
        setTextColor(Theme.TEXT)
        background = Theme.ripple(GradientDrawable().apply { cornerRadius = dp(8f); setColor(Color.argb(240, 255, 255, 255)); setStroke(1, Theme.LINE) })
        setOnClickListener { onClick() }
    }

    private fun buttonLp() = LinearLayout.LayoutParams(dp(44), dp(44)).apply { bottomMargin = dp(8) }

    companion object {
        const val REQ_PERMISSIONS = 1
        const val REQ_IMPORT = 2
        /** Whether the UI is in front; the service uses it to decide when it has been forgotten. */
        @Volatile var visible = false

        /** One line for the banner: what, where, how soon. */
        fun describe(s: AirspaceStatus, u: Units): String {
            val a = s.airspace
            val name = "${a.kind} ${a.name}"
            return when (s.level) {
                AlertLevel.INSIDE -> "INSIDE $name · ${a.lower.text}–${a.upper.text}"
                AlertLevel.IMMINENT -> {
                    val t = if (s.timeToEntryS.isNaN()) "" else " in ${s.timeToEntryS.roundToInt()} s"
                    "AIRSPACE$t · $name · ${where(s, u)}"
                }
                else -> "Near $name · ${where(s, u)}"
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
