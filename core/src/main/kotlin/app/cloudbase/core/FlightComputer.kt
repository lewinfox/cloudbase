package app.cloudbase.core

import app.cloudbase.core.airspace.AircraftAlt
import app.cloudbase.core.airspace.AirspaceInfo
import app.cloudbase.core.airspace.Airspace
import app.cloudbase.core.airspace.AirspaceIndex
import app.cloudbase.core.airspace.AirspaceMonitor
import app.cloudbase.core.airspace.AirspacePolicy
import app.cloudbase.core.airspace.AirspaceStatus
import app.cloudbase.core.airspace.AlertEvent
import app.cloudbase.core.airspace.AlertTracker
import app.cloudbase.core.airspace.Ceiling
import app.cloudbase.core.airspace.MonitorConfig
import app.cloudbase.core.airspace.PositionError
import app.cloudbase.core.airspace.contains
import app.cloudbase.core.flight.FlightDetector
import app.cloudbase.core.flight.FlightEvent
import app.cloudbase.core.flight.FlightState
import app.cloudbase.core.flight.GlideReach
import app.cloudbase.core.flight.Netto
import app.cloudbase.core.flight.ThermalMap
import app.cloudbase.core.flight.VarioFilter
import app.cloudbase.core.flight.VerticalAccel
import app.cloudbase.core.flight.WindEstimate
import app.cloudbase.core.flight.WindEstimator
import app.cloudbase.core.igc.IgcHeader
import app.cloudbase.core.igc.IgcWriter
import kotlin.math.abs
import kotlin.math.max

/** Where flight logs go. The computer decides when; the app decides where. */
interface FlightLogSink {
    /** Open a new log whose first fix is at [firstFixMs]; null if it cannot be created. */
    fun open(firstFixMs: Long): Appendable?
    fun flush()
    fun close()
}

/**
 * Where the glider is going: the one projection every display and warning uses. [trackDeg] is
 * degrees true, NaN before there has been any movement to go by; [speed] m/s over the ground;
 * [climb] m/s, averaged over several seconds.
 */
data class Heading(val trackDeg: Double, val speed: Double, val climb: Double)

/** How close the next airspace limit above is: see [FlightComputer.ceilingLevel]. */
enum class CeilingLevel { NONE, AMBER, RED }

/**
 * Turns raw sensor samples into everything the screen shows. Not thread-safe by design: feed it
 * and read it from one thread (the app uses the main looper), so there is nothing to lock and
 * nothing to get subtly wrong.
 *
 * Altitude: with a barometer, the Kalman filter runs on ISA pressure altitude (smooth, and
 * exactly what flight levels are defined in) and a slowly tracked offset to GPS turns that into
 * altitude above sea level. Without one, the filter runs on GPS altitude directly.
 */
class FlightComputer(private val terrain: (lat: Double, lon: Double) -> Double) {
    // --- configuration -------------------------------------------------------------------
    var trimSinkMs = 1.1
    var autoRecord = true
    var logSink: FlightLogSink? = null
    var igcHeader = IgcHeader("", "", "", "", "", false)
    var onAlert: ((AlertEvent) -> Unit)? = null
    var onFlightEvent: ((FlightEvent) -> Unit)? = null
    /** A log opened or closed (by the buttons or by takeoff and landing). */
    var onRecordingChanged: (() -> Unit)? = null

    var trimSpeedMs: Double
        get() = wind.trimSpeed
        set(v) { wind.trimSpeed = v }

    // --- live values (read after each onFix; `version` changes when they do) ---------------
    var version = 0L; private set
    var fix: Fix? = null; private set
    /** Best altitude AMSL, metres; NaN before the first usable fix. */
    var altMsl = Double.NaN; private set
    /** ISA pressure altitude, NaN without a barometer. */
    var pressureAlt = Double.NaN; private set
    var terrainElev = Double.NaN; private set
    val agl: Double get() = altMsl - terrainElev
    val climb: Double get() = if (vario.initialised) vario.vario else Double.NaN
    /** Vertical speed of the air itself, m/s. */
    var airMass = Double.NaN; private set
    var turnRateDegS = 0.0; private set
    /** True while turning steadily one way, i.e. working a thermal. */
    var circling = false; private set
    var windEstimate: WindEstimate? = null; private set
    var statuses: List<AirspaceStatus> = emptyList(); private set
    /** Lowest relevant airspace floor overhead and the metres of clearance to it. */
    var headroom: Pair<Airspace, Double>? = null; private set
    /** The airspace alert to show, or null: announced, and not dismissed by the pilot. */
    var banner: AirspaceStatus? = null; private set
    /** Dropped through a floor into another zone: shown when there is no [banner], for [INFO_MS]; or null. */
    var info: AirspaceInfo? = null; private set
    /** The next airspace limit above: top of the one you are in, or the lowest floor overhead. */
    var ceiling: Ceiling? = null; private set
    /** How close [ceiling] is, in time at the present climb, allowing for altitude error. */
    var ceilingLevel = CeilingLevel.NONE; private set
    /**
     * Where the glider is heading: the present track when flying straight; while circling, the
     * drift averaged over a turn or two (the heading sweeps round every few seconds and says
     * nothing about where you are going), or the last straight-line track when there is too
     * little drift to give a direction.
     */
    var heading = Heading(Double.NaN, 0.0, 0.0); private set
    /** How far off position and altitude could be, as used for the airspace checks. */
    var positionError = PositionError.NONE; private set
    var thermalCore: ThermalMap.Core? = null; private set
    /** Glide ratio over the ground, averaged over the last several seconds; NaN when not descending. */
    var glideRatio = Double.NaN; private set
    /** Where the current track and glide ratio meet the ground, or null (climbing, or terrain unknown). */
    var glideImpact: LatLon? = null; private set
    /** Set false to skip the terrain walk when the line is not shown. */
    var computeGlide = true
    var nearby: List<Airspace> = emptyList(); private set
    val flightState: FlightState get() = detector.state
    val takeoffTimeMs: Long get() = detector.takeoffTimeMs
    var hasBaro = false; private set
    var recording = false; private set
    /** When the open log's recording began (its first fix may be a few seconds earlier). */
    var recordStartMs = 0L; private set

    /**
     * Lift and sink met in the last [LIVE_THERMAL_MS], carried downwind with the air: where the
     * thermal you are working is NOW. This is the one to centre on.
     */
    val thermals = ThermalMap(maxAgeMs = LIVE_THERMAL_MS)

    /**
     * The same samples left where they were met, over the ground, for the whole flight: "there
     * was lift over that spur half an hour ago". It does not drift, because what lasts is the
     * place that triggers thermals, not the air that was there at the time.
     */
    val thermalHistory = ThermalMap(maxAgeMs = WHOLE_FLIGHT_MS, maxCells = HISTORY_CELLS)
    val trail = Trail(1800)

    // --- internals ---------------------------------------------------------------------------
    private val vario = VarioFilter()
    private val vertical = VerticalAccel()
    private val wind = WindEstimator()
    private val detector = FlightDetector()
    private var monitor = AirspaceMonitor()
    private val alerts = AlertTracker()
    private var index: AirspaceIndex? = null
    private var indexLat = Double.NaN
    private var indexLon = Double.NaN

    private var lastAccelNs = 0L
    private var lastPressureNs = 0L
    private var accelSinceBaro = 0
    private var accelSinceFix = 0
    private var pressureSinceFix = 0
    private var lastFixMs = 0L
    private var velE = Double.NaN
    private var velN = Double.NaN
    private var climbTrend = Double.NaN
    private var straightTrack = Double.NaN
    private val velT = LongArray(VEL_RING)
    private val velEs = DoubleArray(VEL_RING)
    private val velNs = DoubleArray(VEL_RING)
    private val velTr = DoubleArray(VEL_RING)
    private var velHead = 0
    private var velCount = 0
    /** Record pressed: keep a log open until stop or a landing, even on the ground. */
    private var manualOn = false
    /** Stop pressed in the air: no automatic log until the next landing. */
    private var manualOff = false
    private var lastTrack = Double.NaN
    private var mslOffset = 0.0
    private var mslOffsetN = 0
    private var avgSpeed = Double.NaN
    private var turningS = 0.0
    private var straightS = 0.0
    private var avgClimb = Double.NaN

    private var writer: IgcWriter? = null
    private var unflushed = 0
    private var openRetryIn = 0
    private val pre = PreBuffer(120)

    fun setAirspace(index: AirspaceIndex?, policy: AirspacePolicy = AirspacePolicy(), config: MonitorConfig = MonitorConfig()) {
        this.index = index
        monitor = AirspaceMonitor(policy, config)
        // The alert tracker is deliberately kept: this is called for every settings change, and an
        // airspace already announced must not be announced again because a unit was switched.
        alerts.airspaceChanged()
        indexLat = Double.NaN
        nearby = emptyList()
        statuses = emptyList()
        headroom = null
        banner = null
        info = null
        ceiling = null
        ceilingLevel = CeilingLevel.NONE
        version++
    }

    /** The pilot has seen the banner: an alert stays hidden until that airspace gets worse. */
    fun dismissBanner() {
        val b = banner
        if (b != null) {
            alerts.dismiss(b)
            banner = alerts.banner(statuses)
        } else {
            alerts.dismissInfo()
            info = null
        }
        version++
    }

    /** Accelerometer sample, device frame, m/s² including gravity. [tNs] is the sensor's own clock. */
    fun onAccel(tNs: Long, ax: Float, ay: Float, az: Float) {
        val dt = (tNs - lastAccelNs) * 1e-9
        lastAccelNs = tNs
        if (dt <= 0 || dt > 1.0) { vertical.reset(); return }
        val a = vertical.update(ax, ay, az, dt)
        accelSinceBaro++
        accelSinceFix++
        vario.predict(dt, a)
    }

    fun onPressure(tNs: Long, hPa: Double) {
        if (hPa < 300 || hPa > 1100) return
        val dt = (tNs - lastPressureNs) * 1e-9
        lastPressureNs = tNs
        pressureSinceFix++
        if (!hasBaro) return // filter is still running on GPS altitude; onFix switches it over
        // Without an accelerometer the barometer has to drive the time step itself.
        if (accelSinceBaro == 0 && dt > 0 && dt < 2.0) vario.predict(dt, Double.NaN)
        accelSinceBaro = 0
        vario.update(Atmosphere.pressureAltitude(hPa), BARO_SIGMA_M)
    }

    fun onFix(f: Fix) {
        val dtS = if (lastFixMs == 0L) 0.0 else (f.timeMs - lastFixMs) / 1000.0
        if (dtS < 0) return // out-of-order fix
        lastFixMs = f.timeMs
        fix = f

        updateAltitude(f, dtS)
        terrainElev = terrain(f.lat, f.lon)

        if (dtS > 0 && dtS < 5 && !f.track.isNaN() && !lastTrack.isNaN() && f.speed > 2) {
            val rate = Geo.angleDiff(f.track, lastTrack) / dtS
            turnRateDegS += 0.4 * (rate - turnRateDegS)
        } else if (f.speed <= 2) {
            turnRateDegS = 0.0
        }
        lastTrack = f.track
        updateCircling(dtS, flying = detector.state == FlightState.FLYING)

        val v = climb
        updateHeading(f, v, dtS)
        // Climb rate from GPS altitude alone is too noisy (±0.6 m/s standing still) to tell the
        // detector anything; without a barometer it works from speed and height above ground.
        val event = if (autoRecord) detector.update(f.timeMs, f.speed, if (hasBaro) v else Double.NaN, agl) else null
        val flying = detector.state == FlightState.FLYING
        if (event == FlightEvent.TAKEOFF) startFlight()

        val w = wind.estimate(f.timeMs)
        windEstimate = w
        if (flying && !v.isNaN()) {
            wind.add(f.timeMs, f.speed, f.track, altMsl)
            val tas = w?.airspeed ?: (trimSpeedMs / Math.sqrt(Atmosphere.densityRatio(altMsl.orZero())))
            airMass = Netto.airMass(v, turnRateDegS, tas, trimSinkMs)
            if (w != null && dtS > 0 && dtS < 10) thermals.drift(w.wind, dtS)
            thermals.add(f.lat, f.lon, airMass, f.timeMs)
            thermalHistory.add(f.lat, f.lon, airMass, f.timeMs)
            // Only mark lift you could actually climb in: air rising faster than the glider sinks.
            // (On a real flight a lower bar had the ring on screen almost the whole time.)
            thermalCore = thermals.bestLift(f.lat, f.lon, 400.0, f.timeMs, minClimb = trimSinkMs + CORE_MARGIN_MS)
            trail.add(f.lat, f.lon)
        } else {
            airMass = Double.NaN
            thermalCore = null
        }

        updateGlide(f, v, dtS, flying)
        checkAirspace(f)
        record(f, event)
        if (event != null) onFlightEvent?.invoke(event)
        version++
    }

    /**
     * The record button. Starts a log now, whatever the flight detector thinks. On the ground it
     * does not pretend a flight has begun: takeoff is still detected (and carries on in the same
     * log), and only a landing after that, or [stopRecording], ends it, so standing on launch
     * with the wing laid out is never mistaken for having landed. With automatic start and stop
     * switched off there is no detector, so this also marks the flight as started.
     * Returns false with no fix yet.
     */
    fun startRecording(): Boolean {
        val f = fix ?: return false
        manualOff = false
        if (writer != null) return true
        manualOn = true
        if (!autoRecord && detector.state == FlightState.GROUND) {
            detector.force(FlightState.FLYING, f.timeMs)
            startFlight()
            onFlightEvent?.invoke(FlightEvent.TAKEOFF)
        }
        openLog(f, f.timeMs)
        onRecordingChanged?.invoke()
        version++
        return true
    }

    /**
     * The stop button. Closes the log. In the air, automatic recording stays off until the next
     * landing, so the takeoff detector does not open a new log seconds later.
     */
    fun stopRecording() {
        val f = fix
        manualOn = false
        close()
        if (detector.state == FlightState.FLYING) {
            if (!autoRecord && f != null) {
                detector.force(FlightState.GROUND, f.timeMs)
                onFlightEvent?.invoke(FlightEvent.LANDING)
            } else {
                manualOff = true
            }
        }
        onRecordingChanged?.invoke()
        version++
    }

    /** Each flight starts with a clean sheet. */
    private fun startFlight() {
        thermals.clear(); thermalHistory.clear(); trail.clear()
    }

    /** Call when shutting down so an open log is closed cleanly. */
    fun close() {
        if (writer != null) { logSink?.close(); writer = null; recording = false }
    }

    /**
     * Forget everything about the flight so far. Called whenever the source of data starts
     * afresh (service start, switching to or from the demo): the new source's clock may be
     * behind the old one's, and a flight that was in progress will simply be detected again
     * and get a new log, rather than carrying on unrecorded.
     */
    fun reset() {
        close()
        manualOn = false; manualOff = false
        detector.reset()
        vario.reset()
        vertical.reset()
        wind.reset()
        alerts.reset()
        thermals.clear()
        thermalHistory.clear()
        trail.clear()
        fix = null
        altMsl = Double.NaN; pressureAlt = Double.NaN; terrainElev = Double.NaN
        airMass = Double.NaN; turnRateDegS = 0.0; circling = false
        windEstimate = null; statuses = emptyList(); headroom = null; thermalCore = null
        banner = null; info = null; ceiling = null; ceilingLevel = CeilingLevel.NONE; positionError = PositionError.NONE
        velE = Double.NaN; velN = Double.NaN; climbTrend = Double.NaN; straightTrack = Double.NaN; velCount = 0
        heading = Heading(Double.NaN, 0.0, 0.0)
        glideRatio = Double.NaN; glideImpact = null
        hasBaro = false
        lastAccelNs = 0L; lastPressureNs = 0L
        accelSinceBaro = 0; accelSinceFix = 0; pressureSinceFix = 0
        lastFixMs = 0L; lastTrack = Double.NaN
        mslOffsetN = 0
        avgSpeed = Double.NaN; avgClimb = Double.NaN; turningS = 0.0; straightS = 0.0
        indexLat = Double.NaN
        unflushed = 0; openRetryIn = 0
        version++
    }

    private fun updateAltitude(f: Fix, dtS: Double) {
        val baroNow = pressureSinceFix > 0
        val accelNow = accelSinceFix > 0
        pressureSinceFix = 0
        accelSinceFix = 0
        if (baroNow != hasBaro) {
            // Altitude source changed (start-up, or a sensor died): start the filter afresh.
            hasBaro = baroNow
            vario.reset()
            mslOffsetN = 0
        }
        if (hasBaro) {
            if (vario.initialised && !f.altMsl.isNaN()) {
                // Average quickly at first, then follow GPS slowly: its altitude wanders by metres
                // over minutes, the barometer drifts with weather and temperature over longer.
                mslOffsetN = minOf(mslOffsetN + 1, MSL_OFFSET_SAMPLES)
                val d = f.altMsl - vario.altitude
                mslOffset = if (mslOffsetN == 1) d else mslOffset + (d - mslOffset) / mslOffsetN
            }
            pressureAlt = if (vario.initialised) vario.altitude else Double.NaN
            altMsl = if (mslOffsetN > 0) pressureAlt + mslOffset else f.altMsl
        } else {
            if (!accelNow && dtS > 0) vario.predict(dtS, Double.NaN)
            if (!f.altMsl.isNaN()) vario.update(f.altMsl, GPS_ALT_SIGMA_M)
            pressureAlt = Double.NaN
            altMsl = if (vario.initialised) vario.altitude else f.altMsl
        }
    }

    /**
     * Circling starts after [CIRCLE_ENTER_S] of steady turning (about half a turn, so a single
     * course change does not count) and ends after [CIRCLE_EXIT_S] of flying straight (so
     * re-centring or one wide turn does not drop out of it).
     */
    private fun updateCircling(dtS: Double, flying: Boolean) {
        if (!flying || dtS <= 0 || dtS > 5) { circling = false; turningS = 0.0; straightS = 0.0; return }
        if (abs(turnRateDegS) >= CIRCLE_RATE_DEG_S) { turningS += dtS; straightS = 0.0 } else { straightS += dtS; turningS = 0.0 }
        if (!circling && turningS >= CIRCLE_ENTER_S) circling = true
        if (circling && straightS >= CIRCLE_EXIT_S) circling = false
    }

    /**
     * Glide ratio from smoothed ground speed and sink (time constant [GLIDE_TAU_S]): a single
     * second's values would swing the line about with every bump.
     */
    private fun updateGlide(f: Fix, v: Double, dtS: Double, flying: Boolean) {
        if (!flying || v.isNaN() || dtS <= 0 || dtS > 5) {
            avgSpeed = Double.NaN; avgClimb = Double.NaN
            glideRatio = Double.NaN; glideImpact = null
            return
        }
        if (avgSpeed.isNaN()) { avgSpeed = f.speed; avgClimb = v } else {
            val k = dtS / (GLIDE_TAU_S + dtS)
            avgSpeed += k * (f.speed - avgSpeed)
            avgClimb += k * (v - avgClimb)
        }
        glideRatio = if (avgClimb < -MIN_SINK_FOR_GLIDE && avgSpeed > 2) avgSpeed / -avgClimb else Double.NaN
        val dir = heading.trackDeg
        glideImpact = if (computeGlide && !glideRatio.isNaN() && !dir.isNaN()) GlideReach.impact(f.lat, f.lon, altMsl, dir, glideRatio, terrain) else null
    }

    private fun checkAirspace(f: Fix) {
        val idx = index
        if (idx == null || altMsl.isNaN()) return
        if (indexLat.isNaN() || Geo.distance(f.lat, f.lon, indexLat, indexLon) > REQUERY_M) {
            indexLat = f.lat
            indexLon = f.lon
            nearby = idx.near(f.lat, f.lon, NEARBY_RADIUS_M)
            monitor.setCandidates(nearby)
        }
        // Without a barometer flight levels are compared against GPS altitude: the best available.
        val alt = AircraftAlt(altMsl, if (pressureAlt.isNaN()) altMsl else pressureAlt, terrainElev)
        val err = positionError(f)
        positionError = err
        val h = heading
        val climbNow = h.climb
        statuses = monitor.evaluate(f.lat, f.lon, alt, h.trackDeg, h.speed, climbNow, err)
        headroom = monitor.headroom(f.lat, f.lon, alt)
        ceiling = monitor.ceiling(f.lat, f.lon, alt, err)
        ceilingLevel = ceilingLevel(ceiling, climbNow)
        alerts.update(
            statuses, f.timeMs,
            overhead = { it.contains(f.lat, f.lon) },
            belowFloor = { !it.lower.isSurface && alt.inRef(it.lower.ref) < it.lower.metres && it.contains(f.lat, f.lon) },
        )?.let { onAlert?.invoke(it) }
        banner = alerts.banner(statuses)
        info = alerts.info?.takeIf { f.timeMs - it.atMs < INFO_MS }
    }

    /**
     * Mean ground velocity into [velE], [velN]: over the last full turn while circling (the
     * airspeed then goes once round the compass and cancels, leaving only the drift; any other
     * window leaves a wobble of 20 degrees or more each turn), else over the last
     * [STILL_WINDOW_S]. Intervals between fixes are integrated with the trapezium rule, and the
     * one that completes the turn only for the part of it that does.
     */
    private fun meanVelocity(nowMs: Long) {
        var se = 0.0; var sn = 0.0; var st = 0.0
        var turned = 0.0
        var fullTurn = false
        for (k in 0 until velCount - 1) {
            val i = (velHead - k + VEL_RING) % VEL_RING
            val p = (i - 1 + VEL_RING) % VEL_RING
            val dt = (velT[i] - velT[p]) / 1000.0
            if (dt <= 0) break
            var w = 1.0
            if (circling) {
                if (velTr[i].isNaN() || velTr[p].isNaN()) break
                val step = abs(Geo.angleDiff(velTr[i], velTr[p]))
                if (turned + step >= 360.0) { w = if (step > 0) (360.0 - turned) / step else 0.0; fullTurn = true }
                turned += step
            } else if ((nowMs - velT[p]) / 1000.0 > STILL_WINDOW_S) {
                w = ((STILL_WINDOW_S * 1000 - (nowMs - velT[i])) / 1000.0 / dt).coerceIn(0.0, 1.0)
            }
            // Over the part [w] of the interval nearest the later fix, the velocity runs from
            // the later fix's value to a point [w] of the way to the earlier one.
            val ePart = velEs[i] + (velEs[p] - velEs[i]) * w / 2
            val nPart = velNs[i] + (velNs[p] - velNs[i]) * w / 2
            se += ePart * dt * w; sn += nPart * dt * w; st += dt * w
            if (fullTurn || w < 1.0) break
        }
        if (circling && !fullTurn) {
            // Not a whole turn on record yet: keep what we had, so the heading does not lurch.
            if (velE.isNaN()) { velE = velEs[velHead]; velN = velNs[velHead] }
            return
        }
        if (st > 0) { velE = se / st; velN = sn / st } else { velE = velEs[velHead]; velN = velNs[velHead] }
    }

    /** Updates [heading]; see there. Averaged velocity and climb, since a single second's swing with every turn and bump. */
    private fun updateHeading(f: Fix, v: Double, dtS: Double) {
        val track = f.track
        val e = if (track.isNaN()) 0.0 else f.speed * Math.sin(Math.toRadians(track))
        val n = if (track.isNaN()) 0.0 else f.speed * Math.cos(Math.toRadians(track))
        if (dtS <= 0 || dtS > 10) velCount = 0
        velHead = (velHead + 1) % VEL_RING
        velT[velHead] = f.timeMs; velEs[velHead] = e; velNs[velHead] = n; velTr[velHead] = track
        velCount = minOf(velCount + 1, VEL_RING)
        meanVelocity(f.timeMs)
        if (!v.isNaN()) {
            if (climbTrend.isNaN() || dtS <= 0 || dtS > 10) climbTrend = v else climbTrend += (1 - Math.exp(-dtS / CLIMB_TAU_S)) * (v - climbTrend)
        }
        val climb = if (climbTrend.isNaN()) 0.0 else climbTrend
        heading = if (!circling && !track.isNaN()) {
            straightTrack = track
            Heading(track, f.speed, climb)
        } else {
            val drift = Math.hypot(velE, velN)
            val dir = if (drift >= MIN_DRIFT_MS) Math.toDegrees(Math.atan2(velE, velN)).let { if (it < 0) it + 360 else it } else straightTrack
            Heading(dir, drift, climb)
        }
    }

    /**
     * About 95 % bounds on position and altitude, from the receiver's own accuracy figures and
     * the sensors available. With a barometer, altitude moves with the pressure and its offset
     * to sea level is averaged over many GPS fixes, so it is much tighter than one GPS reading;
     * flight levels are read from the pressure itself. Without one, altitude is GPS alone and
     * flight levels can only be guessed from it, as far off as the day's QNH is from 1013.
     * None of these figures has been checked in the air.
     */
    private fun positionError(f: Fix): PositionError {
        val hAcc = if (f.hAcc.isNaN() || f.hAcc <= 0f) DEFAULT_H_ACC_M else f.hAcc.toDouble()
        val vAcc = if (!f.vAcc.isNaN() && f.vAcc > 0f) f.vAcc.toDouble() else hAcc * V_PER_H_ACC
        val h = max(2 * hAcc, MIN_H_ERR_M)
        val msl: Double
        val std: Double
        if (hasBaro && mslOffsetN > 0) {
            // GPS altitude errors stay correlated for many seconds, so far fewer independent
            // samples than fixes go into the offset.
            val independent = max(1.0, mslOffsetN / GPS_CORRELATION_FIXES)
            msl = max(2 * vAcc / Math.sqrt(independent), MIN_V_ERR_M)
            std = BARO_STD_ERR_M
        } else {
            msl = max(2 * vAcc, MIN_V_ERR_M)
            std = Math.hypot(msl, QNH_SPREAD_M)
        }
        return PositionError(h, msl, std, Math.hypot(msl, TERRAIN_ERR_M))
    }

    /**
     * Amber when the ceiling could be reached in twice the warning time (or is within twice the
     * error), red within the warning time (or within the error). A level is only left once
     * clear of it by [CEILING_HYSTERESIS_M], so GPS altitude wandering at a threshold does not
     * flicker it.
     */
    private fun ceilingLevel(c: Ceiling?, climb: Double): CeilingLevel {
        if (c == null) return CeilingLevel.NONE
        val up = max(climb, 0.0)
        val t = monitor.config.lookaheadS
        val clear = c.metres - c.errorM
        val keep = if (ceilingLevel == CeilingLevel.NONE) 0.0 else CEILING_HYSTERESIS_M
        val redKeep = if (ceilingLevel == CeilingLevel.RED) CEILING_HYSTERESIS_M else 0.0
        return when {
            clear <= up * t + redKeep -> CeilingLevel.RED
            clear <= up * 2 * t + c.errorM + keep -> CeilingLevel.AMBER
            else -> CeilingLevel.NONE
        }
    }

    private fun record(f: Fix, event: FlightEvent?) {
        // Open at takeoff (or when the record button was pressed); if that failed (storage not
        // ready), keep trying rather than fly the whole flight unrecorded.
        val flying = detector.state == FlightState.FLYING
        val wanted = manualOn || (flying && !manualOff)
        if (writer == null && wanted && (event == FlightEvent.TAKEOFF || --openRetryIn <= 0)) {
            if (openLog(f, if (manualOn) recordStartMs else detector.takeoffTimeMs)) onRecordingChanged?.invoke()
        }
        val w = writer
        if (w != null) {
            w.fix(f.timeMs, f.lat, f.lon, pressureAlt, f.altMsl)
            if (++unflushed >= FLUSH_EVERY) { logSink?.flush(); unflushed = 0 }
        } else {
            pre.add(f.timeMs, f.lat, f.lon, pressureAlt, f.altMsl)
        }
        if (event == FlightEvent.LANDING) {
            manualOn = false; manualOff = false
            if (w != null) { close(); onRecordingChanged?.invoke() }
        }
    }

    /**
     * Opens the log for a recording that began at [startMs], with the buffered fixes from a few
     * seconds before it so the launch run is in the file. [f] goes in from the buffer if it is
     * there already (the record button), else the caller writes it next.
     */
    private fun openLog(f: Fix, startMs: Long): Boolean {
        openRetryIn = OPEN_RETRY_FIXES
        recordStartMs = startMs
        val start = startMs - PRE_TAKEOFF_MS
        val first = pre.firstTimeAtOrAfter(start, f.timeMs)
        val out = logSink?.open(first) ?: return false
        val w = IgcWriter(out)
        // Whether there is a barometer is only known once sensors have reported, so it is
        // filled in here, not when the header details were set.
        val h = igcHeader
        w.header(IgcHeader(h.pilot, h.gliderType, h.gliderId, h.appVersion, h.deviceModel, hasBaro), first)
        pre.forEachFrom(start) { t, lat, lon, pAlt, gAlt -> w.fix(t, lat, lon, pAlt, gAlt) }
        writer = w
        recording = true
        return true
    }

    private fun Double.orZero() = if (isNaN()) 0.0 else this

    companion object {
        const val BARO_SIGMA_M = 0.35
        const val GPS_ALT_SIGMA_M = 4.0
        const val MSL_OFFSET_SAMPLES = 90
        const val NEARBY_RADIUS_M = 30_000.0
        const val REQUERY_M = 5_000.0
        /** Fixes from just before the detected takeoff are kept so the launch run is in the log. */
        const val PRE_TAKEOFF_MS = 5_000L
        /** A crash or flat battery loses at most this many seconds of log. */
        const val FLUSH_EVERY = 10
        const val CORE_MARGIN_MS = 0.3
        /**
         * How long a sample rides the wind before it is pinned to the ground. Long enough to cover
         * centring a climb (you and the air you sampled drift downwind together); beyond that,
         * what is worth remembering is the place, since thermals come off terrain.
         */
        const val LIVE_THERMAL_MS = 5 * 60_000L
        /** Longer than any flight: history is cleared at the next takeoff, not by age. */
        const val WHOLE_FLIGHT_MS = 24 * 3_600_000L
        /** About 16 hours of straight-line flying at one new 30 m cell every three seconds. */
        const val HISTORY_CELLS = 20_000
        const val OPEN_RETRY_FIXES = 20
        /** Ground velocities kept for averaging over a turn: more than the slowest turn. */
        const val VEL_RING = 64
        /** Averaging window when not turning (standing, or no track). */
        const val STILL_WINDOW_S = 15.0
        const val CLIMB_TAU_S = 10.0
        /** Slower drift than this gives no usable direction: keep the last straight-line track. */
        const val MIN_DRIFT_MS = 1.0
        /** Assumed receiver accuracy when it does not report one (the demo's IGC file). */
        const val DEFAULT_H_ACC_M = 5.0
        /** Phone GPS altitude is typically about 1.5x worse than its horizontal position. */
        const val V_PER_H_ACC = 1.5
        const val MIN_H_ERR_M = 10.0
        const val MIN_V_ERR_M = 5.0
        const val GPS_CORRELATION_FIXES = 10.0
        /** A phone barometer reads within about 1 hPa: 8 m of flight level. Two of those. */
        const val BARO_STD_ERR_M = 16.0
        /** QNH strays from 1013 by 10 hPa or so on an ordinary day: two of those, in metres. */
        const val QNH_SPREAD_M = 170.0
        /** SRTM-grade terrain heights. */
        const val TERRAIN_ERR_M = 20.0
        const val CEILING_HYSTERESIS_M = 10.0
        /** How long a change-of-airspace note stays up. */
        const val INFO_MS = 30_000L
        const val GLIDE_TAU_S = 8.0
        /** A thermalling turn is 15-25 degrees a second; 8 is already a deliberate, held turn. */
        const val CIRCLE_RATE_DEG_S = 8.0
        const val CIRCLE_ENTER_S = 10.0
        const val CIRCLE_EXIT_S = 12.0
        /** Sinking slower than this is as good as level: the glide line would run off the map. */
        const val MIN_SINK_FOR_GLIDE = 0.3
    }
}

/** The last [capacity] positions flown, oldest first, for drawing the track. */
class Trail(private val capacity: Int) {
    private val lats = DoubleArray(capacity)
    private val lons = DoubleArray(capacity)
    private var head = 0
    var size = 0; private set

    fun add(lat: Double, lon: Double) {
        lats[head] = lat; lons[head] = lon
        head = (head + 1) % capacity
        if (size < capacity) size++
    }

    /** i = 0 is the oldest point. */
    fun lat(i: Int) = lats[(head - size + i + capacity) % capacity]
    fun lon(i: Int) = lons[(head - size + i + capacity) % capacity]
    fun clear() { size = 0; head = 0 }
}

/** Ring of recent fixes kept while on the ground, written out when a takeoff is detected. */
private class PreBuffer(private val n: Int) {
    private val t = LongArray(n)
    private val lat = DoubleArray(n)
    private val lon = DoubleArray(n)
    private val pAlt = DoubleArray(n)
    private val gAlt = DoubleArray(n)
    private var head = 0
    private var size = 0

    fun add(time: Long, la: Double, lo: Double, p: Double, g: Double) {
        t[head] = time; lat[head] = la; lon[head] = lo; pAlt[head] = p; gAlt[head] = g
        head = (head + 1) % n
        if (size < n) size++
    }

    fun firstTimeAtOrAfter(start: Long, fallback: Long): Long {
        for (i in 0 until size) {
            val k = (head - size + i + n) % n
            if (t[k] >= start) return t[k]
        }
        return fallback
    }

    fun forEachFrom(start: Long, f: (Long, Double, Double, Double, Double) -> Unit) {
        for (i in 0 until size) {
            val k = (head - size + i + n) % n
            if (t[k] >= start) f(t[k], lat[k], lon[k], pAlt[k], gAlt[k])
        }
        size = 0
        head = 0
    }
}
