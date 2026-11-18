package app.cloudbase.screenshots

import android.Manifest
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import app.cloudbase.FlightService
import app.cloudbase.Hub
import app.cloudbase.MainActivity
import app.cloudbase.core.Atmosphere
import app.cloudbase.core.igc.IgcTrack
import app.cloudbase.core.pressureAt
import app.cloudbase.ui.GlyphButton
import app.cloudbase.ui.HeatGrid
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration

/**
 * Renders the real screens during the demo flight (a recorded real flight) to docs/screenshots/. Not a pass/fail test;
 * it is how the design gets looked at without a phone. Needs the network (map tiles) and an
 * NZ airspace file: AIRSPACE_SAMPLES=/dir/with/nz_asp_v2.txt, else it downloads one.
 * Run: ./gradlew :app:testDebugUnitTest -Pscreenshots
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h740dp-xhdpi")
class ScreenshotTest {
    private val outDir = File(System.getProperty("screenshotDir") ?: "build/screenshots").apply { mkdirs() }
    private lateinit var track: IgcTrack
    private var index = 0

    private fun idle(ms: Long = 600) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    /** Let background loaders (tiles, airspace parsing) finish and deliver to the main thread. */
    private fun settle(realMs: Long) {
        val end = System.currentTimeMillis() + realMs
        while (System.currentTimeMillis() < end) { Thread.sleep(100); idle(300) }
    }

    /** Replay the demo flight up to [seconds] after its first fix, exactly as the service does. */
    private fun flyTo(seconds: Int) {
        val c = Hub.computer
        while (index < track.size && (track.timeMs(index) - track.timeMs(0)) / 1000 <= seconds) {
            if (track.hasPressure) c.onPressure(track.timeMs(index) * 1_000_000L, Atmosphere.pressureAt(track.pressureAlt(index)))
            c.onFix(track.fix(index))
            index++
        }
    }

    /** As [flyTo], but slowly over the last few fixes, so terrain tiles asked for along the way have arrived. */
    private fun flyToWithTerrain(seconds: Int) {
        flyTo(seconds - 12)
        for (s in seconds - 11..seconds) { flyTo(s); settle(900) }
    }

    private fun snap(act: MainActivity, name: String) {
        settle(1000)
        val root = act.window.decorView
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        // Robolectric has no frame loop: the first draw is what asks for the map tiles, so draw,
        // wait for them, and draw again.
        root.draw(Canvas(bmp))
        settle(6000)
        root.draw(Canvas(bmp))
        File(outDir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun find(v: View, match: (View) -> Boolean): View? {
        if (match(v)) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i), match)?.let { return it }
        return null
    }

    @Test
    fun render() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.POST_NOTIFICATIONS)

        // Airspace in place before the app starts, as if downloaded on an earlier run.
        val sample = File(System.getProperty("airspaceSamples").orEmpty(), "nz_asp_v2.txt")
        val dir = File(app.filesDir, "airspace").apply { mkdirs() }
        if (sample.exists()) sample.copyTo(File(dir, "nz.txt"), overwrite = true)
        app.getSharedPreferences("settings", 0).edit()
            .putStringSet("countries", setOf("nz")).putBoolean("airspace_seeded", sample.exists())
            .putString("pilot", "A. Pilot").putString("glider", "Advance Epsilon 9")
            .putString("map_zoom", "15.0").commit()

        Hub.init(app)
        settle(4000)
        Hub.gpsStatus = ""
        track = app.assets.open(FlightService.DEMO_ASSET).bufferedReader().use { IgcTrack.read(it) }!!

        // The whole flight is inside the Queenstown control zone, as the real one was.
        flyTo(300)
        val act = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        settle(2000)
        snap(act, "2-inside-airspace")
        // What a pilot flying this site would do: tell the app to stop minding that zone.
        Hub.settings.ignoredAirspaces = Hub.computer.statuses.map { it.airspace.name }.toSet()
        Hub.applySettings()
        act.airspaceChanged()
        flyToWithTerrain(1060); snap(act, "1-thermalling")
        // The same moment in each lift colour scale, for choosing between them.
        for (p in HeatGrid.Palette.values()) {
            Hub.settings.liftColours = p.name
            act.airspaceChanged() // forces a repaint
            snap(act, "palette-${p.name.lowercase()}")
        }
        Hub.settings.liftColours = "TURBO"
        flyToWithTerrain(1575); snap(act, "3-glide-to-ground")
        flyTo(Int.MAX_VALUE) // land, so the logbook has a finished flight in it

        for ((label, name) in listOf("Flights" to "5-flights", "Airspace" to "6-airspace", "Settings" to "7-settings")) {
            find(act.window.decorView) { it is GlyphButton && it.contentDescription == "Menu" }!!.performClick()
            idle()
            if (label == "Flights") snap(act, "8-menu")
            find(act.window.decorView) { it is TextView && it.text.toString() == label && it.hasOnClickListeners() }!!.performClick()
            idle()
            snap(act, name)
            act.closePage()
        }
    }
}
