package app.cloudbase

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.OnNmeaMessageListener
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import app.cloudbase.core.Fix
import app.cloudbase.core.Geoid
import app.cloudbase.core.flight.FlightEvent
import app.cloudbase.core.flight.FlightSim
import app.cloudbase.core.flight.FlightState

/**
 * Owns the GPS and sensors for as long as a flight might be in progress, so logging and alerts
 * carry on with the screen off or another app in front. Everything is delivered on the main
 * looper and handed straight to the [app.cloudbase.core.FlightComputer].
 */
class FlightService : Service(), LocationListener, SensorEventListener {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var alerter: Alerter
    private var wakeLock: PowerManager.WakeLock? = null
    private var nmea: OnNmeaMessageListener? = null

    /** Geoid separation reported by the GPS chipset itself (NMEA GGA), and when. */
    private var nmeaGeoidSep = Double.NaN
    private var nmeaGeoidAt = 0L
    private var lastFixAt = 0L
    private var groundSince = 0L

    private var sim: FlightSim? = null
    private var simEpoch = 0L
    private var simFixAt = 0.0
    private var simBaroAt = 0.0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Hub.init(this)
        alerter = Alerter(this)
        Hub.computer.onAlert = { e -> alerter.alert(e, Hub.settings.alertSound, Hub.settings.alertVoice) }
        Hub.computer.onFlightEvent = { e -> onFlightEvent(e) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        if (!startForegroundCompat()) { stopSelf(); return START_NOT_STICKY }
        if (!Hub.serviceRunning) {
            Hub.serviceRunning = true
            // A fresh start: the demo and the real clock do not agree, and a stale flight state
            // would never see a new takeoff.
            Hub.computer.reset()
            Hub.applySettings()
            groundSince = SystemClock.elapsedRealtime()
            alerter.prepare(Hub.settings.alertVoice)
            if (Hub.settings.demo) startDemo() else startSensors()
            handler.postDelayed(watchdog, WATCHDOG_MS)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        stopSensors()
        Hub.computer.close()
        Hub.computer.onAlert = null
        Hub.computer.onFlightEvent = null
        alerter.shutdown()
        releaseWakeLock()
        Hub.serviceRunning = false
        Hub.demoTerrain = null
        sim = null
        Hub.gpsStatus = "Stopped"
        super.onDestroy()
    }

    // --- real sensors ------------------------------------------------------------------------

    @SuppressLint("MissingPermission") // checked just below
    private fun startSensors() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Hub.gpsStatus = "Location permission needed"
            return
        }
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) Hub.gpsStatus = "GPS is switched off"
        // GPS only: network/fused positions are useless in the air and cost radio time.
        lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper())
        nmea = OnNmeaMessageListener { message, _ -> onNmea(message) }.also { lm.addNmeaListener(it, handler) }

        val sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        // 20 Hz pressure and 25 Hz acceleration are plenty for a vario; faster only burns battery.
        sm.getDefaultSensor(Sensor.TYPE_PRESSURE)?.let { sm.registerListener(this, it, 50_000, handler) }
        sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sm.registerListener(this, it, 40_000, handler) }
    }

    private fun stopSensors() {
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        lm.removeUpdates(this)
        nmea?.let { lm.removeNmeaListener(it) }
        nmea = null
        (getSystemService(Context.SENSOR_SERVICE) as SensorManager).unregisterListener(this)
    }

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_PRESSURE -> Hub.computer.onPressure(e.timestamp, e.values[0].toDouble())
            Sensor.TYPE_ACCELEROMETER -> Hub.computer.onAccel(e.timestamp, e.values[0], e.values[1], e.values[2])
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onLocationChanged(loc: Location) {
        lastFixAt = SystemClock.elapsedRealtime()
        val acc = if (loc.hasAccuracy()) loc.accuracy else Float.NaN
        Hub.gpsStatus = if (acc > POOR_ACCURACY_M) "Poor GPS ±${acc.toInt()} m" else ""
        val speed = if (loc.hasSpeed()) loc.speed.toDouble() else 0.0
        Hub.computer.onFix(
            Fix(
                timeMs = loc.time,
                lat = loc.latitude,
                lon = loc.longitude,
                altMsl = mslAltitude(loc),
                speed = speed,
                // Below walking pace the receiver's bearing is noise.
                track = if (loc.hasBearing() && speed > 0.7) loc.bearing.toDouble() else Double.NaN,
                hAcc = acc,
            )
        )
        afterFix()
    }

    override fun onProviderEnabled(provider: String) { Hub.gpsStatus = "Waiting for GPS" }
    override fun onProviderDisabled(provider: String) { Hub.gpsStatus = "GPS is switched off" }
    @Deprecated("Deprecated in Java") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

    /**
     * Android's altitude is height above the WGS84 ellipsoid, which differs from sea level by up
     * to 100 m. Best source first: the platform's own MSL value, then the receiver's geoid
     * separation, then our coarse built-in EGM96 grid.
     */
    private fun mslAltitude(loc: Location): Double {
        if (Build.VERSION.SDK_INT >= 34 && loc.hasMslAltitude()) return loc.mslAltitudeMeters
        if (!loc.hasAltitude()) return Double.NaN
        val fresh = !nmeaGeoidSep.isNaN() && SystemClock.elapsedRealtime() - nmeaGeoidAt < NMEA_FRESH_MS
        return loc.altitude - if (fresh) nmeaGeoidSep else Geoid.height(loc.latitude, loc.longitude)
    }

    /** $xxGGA,time,lat,N,lon,E,quality,sats,hdop,alt,M,geoidSep,M,... */
    private fun onNmea(message: String) {
        if (message.length < 6 || !message.startsWith("GGA", 3)) return
        val f = message.split(',')
        if (f.size < 13 || f[6] == "0" || f[6].isEmpty()) return
        val sep = f[11].toDoubleOrNull() ?: return
        if (sep < -120 || sep > 100) return
        nmeaGeoidSep = sep
        nmeaGeoidAt = SystemClock.elapsedRealtime()
    }

    // --- demo --------------------------------------------------------------------------------

    private fun startDemo() {
        val s = Hub.settings
        sim = FlightSim(seed = 7, startLat = DEMO_LAT, startLon = DEMO_LON, groundElevM = DEMO_ELEV, trimSpeed = s.trimKmh / 3.6)
        // A hill at the launch and flat ground 600 m below everywhere else, which is the world
        // the simulator flies in.
        Hub.demoTerrain = { lat, lon ->
            val d = app.cloudbase.core.Geo.distance(lat, lon, DEMO_LAT, DEMO_LON)
            DEMO_ELEV - 600.0 * (d / 900.0).coerceAtMost(1.0)
        }
        simEpoch = System.currentTimeMillis()
        simFixAt = 0.0
        simBaroAt = 0.0
        Hub.gpsStatus = "Demo flight"
        handler.post(demoTick)
    }

    /** Runs the simulated flight faster than real time through exactly the same inputs as real sensors. */
    private val demoTick = object : Runnable {
        override fun run() {
            val sim = sim ?: return
            val c = Hub.computer
            var steps = (DEMO_TICK_MS / 1000.0 * DEMO_SPEED / SIM_DT).toInt()
            while (steps-- > 0 && !sim.finished) {
                sim.step(SIM_DT)
                val tNs = (sim.timeS * 1e9).toLong()
                val a = sim.accel
                c.onAccel(tNs, a[0], a[1], a[2])
                if (sim.timeS - simBaroAt >= 0.05) { simBaroAt = sim.timeS; c.onPressure(tNs, sim.pressureHpa) }
                if (sim.timeS - simFixAt >= 1.0) { simFixAt = sim.timeS; c.onFix(sim.fix(simEpoch)); afterFix() }
            }
            if (!sim.finished) handler.postDelayed(this, DEMO_TICK_MS)
        }
    }

    // --- housekeeping ----------------------------------------------------------------------

    private fun afterFix() {
        if (Hub.computer.flightState == FlightState.FLYING) groundSince = 0L
        else if (groundSince == 0L) groundSince = SystemClock.elapsedRealtime()
    }

    private fun onFlightEvent(e: FlightEvent) {
        if (e == FlightEvent.TAKEOFF) acquireWakeLock() else releaseWakeLock()
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, notification())
    }

    /** Once a second-ish check for a stalled GPS, and stop ourselves if left running on the ground unwatched. */
    private val watchdog = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            if (sim == null && lastFixAt != 0L && now - lastFixAt > GPS_LOST_MS && Hub.gpsStatus.isEmpty()) Hub.gpsStatus = "GPS signal lost"
            val idle = groundSince != 0L && now - groundSince > IDLE_STOP_MS
            if (idle && !MainActivity.visible) { stopSelf(); return }
            handler.postDelayed(this, WATCHDOG_MS)
        }
    }

    /** Only while airborne: keeps sensors and logging alive with the screen off. */
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "cloudbase:flight").apply { acquire(MAX_FLIGHT_MS) }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun startForegroundCompat(): Boolean {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Flight", NotificationManager.IMPORTANCE_LOW))
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            else startForeground(NOTIFICATION_ID, notification())
            return true
        } catch (e: RuntimeException) {
            // Say so: a silently missing instrument is worse than a notification.
            nm.notify(NOTIFICATION_ID + 1, Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("Cloudbase is not running").setContentText("Open the app to resume GPS and logging")
                .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
                .setAutoCancel(true).build())
            // Location permission revoked, or the system restarted us in the background where a
            // location service may not be started. The activity starts us again when it opens.
            return false
        }
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, FlightService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val flying = Hub.computer.flightState == FlightState.FLYING
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(if (flying) "Flight in progress" else "Ready for takeoff")
            .setContentText(if (flying && Hub.computer.recording) "Recording IGC log" else "Log starts automatically at takeoff")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    companion object {
        const val ACTION_STOP = "app.cloudbase.STOP"
        const val CHANNEL = "flight"
        const val NOTIFICATION_ID = 1
        const val POOR_ACCURACY_M = 25f
        const val NMEA_FRESH_MS = 10 * 60_000L
        const val GPS_LOST_MS = 5_000L
        const val WATCHDOG_MS = 2_000L
        /**
         * On the ground with the screen off or the app closed for this long: stop using the GPS.
         * Long enough to cover waiting on launch with the phone locked.
         */
        const val IDLE_STOP_MS = 60 * 60_000L
        const val MAX_FLIGHT_MS = 12 * 3_600_000L
        const val DEMO_TICK_MS = 50L
        const val DEMO_SPEED = 4.0
        const val SIM_DT = 0.04
        // Demo launch: the hills behind Paekākāriki, under the Wellington control area, so the
        // simulated climb runs into real airspace and shows the alerts.
        const val DEMO_LAT = -40.975
        const val DEMO_LON = 174.975
        const val DEMO_ELEV = 700.0

        fun start(context: Context) {
            context.startForegroundService(Intent(context, FlightService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FlightService::class.java))
        }
    }
}
