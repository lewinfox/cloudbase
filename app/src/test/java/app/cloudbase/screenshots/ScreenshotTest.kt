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
import app.cloudbase.core.Geo
import app.cloudbase.core.flight.FlightSim
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
 * Renders the real screens during the demo flight to docs/screenshots/. Not a pass/fail test;
 * it is how the design gets looked at without a phone. Needs the network (map tiles) and an
 * NZ airspace file: AIRSPACE_SAMPLES=/dir/with/nz_asp_v2.txt, else it downloads one.
 * Run: ./gradlew :app:testDebugUnitTest -Pscreenshots
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h740dp-xhdpi")
class ScreenshotTest {
    private val outDir = File(System.getProperty("screenshotDir") ?: "build/screenshots").apply { mkdirs() }
    private lateinit var sim: FlightSim
    private var nextBaro = 0.0
    private var nextFix = 0.0

    private fun idle(ms: Long = 600) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    /** Let background loaders (tiles, airspace parsing) finish and deliver to the main thread. */
    private fun settle(realMs: Long) {
        val end = System.currentTimeMillis() + realMs
        while (System.currentTimeMillis() < end) { Thread.sleep(100); idle(300) }
    }

    private fun flyTo(timeS: Double) {
        val c = Hub.computer
        while (sim.timeS < timeS && !sim.finished) {
            sim.step(0.04)
            val t = (sim.timeS * 1e9).toLong()
            sim.accel.let { c.onAccel(t, it[0], it[1], it[2]) }
            if (sim.timeS >= nextBaro) { nextBaro += 0.05; c.onPressure(t, sim.pressureHpa) }
            if (sim.timeS >= nextFix) { nextFix += 1.0; c.onFix(sim.fix(1_795_000_000_000L)) }
        }
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
        Hub.demoTerrain = { lat, lon ->
            FlightService.DEMO_ELEV - 600.0 * (Geo.distance(lat, lon, FlightService.DEMO_LAT, FlightService.DEMO_LON) / 900.0).coerceAtMost(1.0)
        }
        sim = FlightSim(seed = 7, startLat = FlightService.DEMO_LAT, startLon = FlightService.DEMO_LON, groundElevM = FlightService.DEMO_ELEV)

        flyTo(150.0)
        val act = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        settle(2000)
        snap(act, "1-thermalling")
        flyTo(262.0); snap(act, "2-airspace-warning")
        flyTo(340.0); snap(act, "3-inside-airspace")
        flyTo(790.0); snap(act, "4-glide-to-ground")
        flyTo(1e9) // land, so the logbook has a finished flight in it

        for ((label, name) in listOf("Flights" to "5-flights", "Airspace" to "6-airspace", "Settings" to "7-settings")) {
            find(act.window.decorView) { it is TextView && it.text.toString() == "≡" }!!.performClick()
            idle()
            val dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
            val list = dialog.listView
            val index = (0 until list.adapter.count).first { list.adapter.getItem(it).toString() == label }
            shadowOf(list).performItemClick(index)
            idle()
            snap(act, name)
            act.closePage()
        }
    }
}
