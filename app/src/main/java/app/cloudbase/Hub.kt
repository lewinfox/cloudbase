package app.cloudbase

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import app.cloudbase.core.FlightComputer
import app.cloudbase.core.airspace.AirspacePolicy
import app.cloudbase.core.airspace.MonitorConfig
import app.cloudbase.core.igc.IgcHeader
import app.cloudbase.map.Elevation
import app.cloudbase.map.RegionDownloader
import app.cloudbase.map.TileSource
import app.cloudbase.map.TileStore
import java.io.File

/**
 * The few long-lived objects, shared by the service (which feeds them) and the activity (which
 * draws them). Everything here is touched from the main thread only.
 */
@SuppressLint("StaticFieldLeak") // application context only
object Hub {
    lateinit var app: Context; private set
    lateinit var settings: Settings; private set
    lateinit var tiles: TileStore; private set
    lateinit var elevation: Elevation; private set
    lateinit var computer: FlightComputer; private set
    lateinit var airspace: AirspaceRepo; private set
    lateinit var logs: FlightLogs; private set
    /** One for the whole app, so a download keeps its cancel button when the page is reopened. */
    lateinit var downloader: RegionDownloader; private set

    /** One line for the status chip, e.g. "Waiting for GPS". Empty when all is well. */
    var gpsStatus = "Waiting for GPS"
    var serviceRunning = false
    private var ready = false

    fun init(context: Context) {
        if (ready) return
        ready = true
        app = context.applicationContext
        settings = Settings(app)
        tiles = TileStore(File(app.filesDir, "tiles"), "Cloudbase/${version()} (Android paragliding instrument)")
        tiles.keepForever = setOf(TileSource.TERRARIUM.id)
        tiles.maxBytes = (settings.mapCacheMb * 1024 * 1024).toLong()
        // Tidy up once per launch, off the main thread: walking tens of thousands of files takes a while.
        Thread({ tiles.trim() }, "tile-trim").apply { isDaemon = true; priority = Thread.MIN_PRIORITY; start() }
        elevation = Elevation(tiles)
        downloader = RegionDownloader(tiles)
        computer = FlightComputer { lat, lon -> terrainAt(lat, lon) }
        logs = FlightLogs(File(app.getExternalFilesDir(null) ?: app.filesDir, "flights"))
        computer.logSink = logs
        airspace = AirspaceRepo(File(app.filesDir, "airspace"))
        applySettings()
        airspace.load(settings.countries)
        seedAirspace()
    }

    /**
     * First run: fetch New Zealand airspace without being asked, so the app is never flown with
     * an empty airspace map by accident. Retried on each launch until it succeeds once.
     */
    private fun seedAirspace() {
        if (settings.airspaceSeeded) return
        airspace.download(HOME_COUNTRY) { error ->
            if (error != null) return@download
            settings.airspaceSeeded = true
            settings.countries = settings.countries + HOME_COUNTRY
            airspace.load(settings.countries)
        }
    }

    const val HOME_COUNTRY = "nz"

    /** Ground elevation AMSL, NaN if not known (yet). */
    fun terrainAt(lat: Double, lon: Double): Double = elevation.at(lat, lon)

    /** Push settings into the flight computer. Call after any setting changes. */
    fun applySettings() {
        val s = settings
        computer.trimSpeedMs = s.trimKmh / 3.6
        computer.trimSinkMs = s.trimSink
        computer.autoRecord = s.autoRecord
        computer.computeGlide = s.showGlide
        computer.igcHeader = IgcHeader(
            s.pilot, s.glider, s.gliderId, version(),
            "${Build.MANUFACTURER} ${Build.MODEL}", computer.hasBaro,
        )
        computer.setAirspace(airspace.index, policy(), MonitorConfig(s.horizontalMarginM, s.verticalMarginM, s.lookaheadS))
    }

    fun policy() = AirspacePolicy(ignoredNames = settings.ignoredAirspaces, alertMbz = settings.alertMbz)

    fun version(): String = try {
        app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "?"
    } catch (e: Exception) { "?" }
}
