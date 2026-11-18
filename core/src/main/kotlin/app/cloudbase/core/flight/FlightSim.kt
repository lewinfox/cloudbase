package app.cloudbase.core.flight

import app.cloudbase.core.Atmosphere
import app.cloudbase.core.Fix
import app.cloudbase.core.LocalProjection
import app.cloudbase.core.Wind
import java.util.Random
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Deterministic synthetic flight: a test fixture and the app's demo mode, not a flight model.
 *
 * Script: stand at launch, take off, glide to thermal 1, circle up to [CLIMB_TO_M] above launch,
 * glide to thermal 2 through a patch of sinking air, circle up again, glide out (at a higher,
 * sinkier speed so the descent to the landing field is not 25 minutes of nothing), land 600 m below
 * launch, stand for 60 s, finish.
 *
 * Everything is computed in the frame of the AIR, where thermals are fixed; the ground position is
 * the air position plus wind * time. That makes "thermals drift with the wind" automatic.
 * Coordinates are metres east (x) / north (y) of the start point.
 */
class FlightSim(
    val seed: Long = 1,
    val startLat: Double = 46.0,
    val startLon: Double = 7.0,
    val groundElevM: Double = 1500.0,
    val wind: Wind = Wind(250.0, 4.0),
    val trimSpeed: Double = 10.5,
    val sensorNoise: Boolean = true,
) {
    /** Position at t = 0; it drifts with the wind. */
    class Thermal(val lat: Double, val lon: Double, val strength: Double, val radiusM: Double)

    val thermals: List<Thermal>

    var timeS: Double = 0.0
        private set
    var finished: Boolean = false
        private set
    var flying: Boolean = false
        private set

    // Sensor outputs (latest step).
    var pressureHpa: Double = Atmosphere.P0
        private set
    val accel: FloatArray = FloatArray(3)

    // Truth.
    val trueLat: Double get() = proj.lat(gy)
    val trueLon: Double get() = proj.lon(gx)
    var trueAlt: Double = groundElevM
        private set
    var trueVario: Double = 0.0
        private set
    var trueAirMassClimb: Double = 0.0
        private set
    var trueTas: Double = 0.0
        private set
    val headingDeg: Double get() = Math.toDegrees(heading).let { ((it % 360.0) + 360.0) % 360.0 }

    // --- Scenario geometry (air frame) ---
    private val proj = LocalProjection(startLat, startLon)
    private val windE = -wind.speed * sin(Math.toRadians(wind.fromDeg)) // the air moves TOWARD fromDeg + 180
    private val windN = -wind.speed * cos(Math.toRadians(wind.fromDeg))
    private val tx = DoubleArray(2)
    private val ty = DoubleArray(2)
    private val tStrength = doubleArrayOf(3.2, 3.4)
    private val tRadius = doubleArrayOf(90.0, 100.0)
    private val orbitX = DoubleArray(2) // where the pilot actually centres: thermal centre + offset
    private val orbitY = DoubleArray(2)
    private val sinkX: Double
    private val sinkY: Double

    // --- Dynamic state ---
    private enum class Phase { STAND, GLIDE1, CIRCLE1, GLIDE2, CIRCLE2, FINAL, LANDED }
    private var phase = Phase.STAND
    private var phaseT = 0.0
    private var gx = 0.0 // ground position
    private var gy = 0.0
    private var ax = 0.0 // air position
    private var ay = 0.0
    private var heading = 0.0 // rad, compass
    private var turnRate = 0.0 // rad/s, + clockwise
    private var vertAccel = 0.0
    private var entryX = 0.0
    private var entryY = 0.0
    private var groundVx = 0.0
    private var groundVy = 0.0

    private val noiseRng = Random(seed)
    private val fixRng = Random(seed xor 0x5DEECE66DL)

    init {
        val scenario = Random(seed * 31 + 7)
        // Thermal 1: ~450 m from the take-off point (in the air frame), roughly across the wind.
        val takeoffAx = -windE * TAKEOFF_S
        val takeoffAy = -windN * TAKEOFF_S
        val b1 = Math.toRadians(100.0 + 20.0 * (scenario.nextDouble() - 0.5))
        val d1 = 450.0 + 50.0 * (scenario.nextDouble() - 0.5)
        tx[0] = takeoffAx + d1 * sin(b1); ty[0] = takeoffAy + d1 * cos(b1)
        val b2 = Math.toRadians(40.0 + 20.0 * (scenario.nextDouble() - 0.5))
        val d2 = 1500.0 + 100.0 * (scenario.nextDouble() - 0.5)
        tx[1] = tx[0] + d2 * sin(b2); ty[1] = ty[0] + d2 * cos(b2)
        for (i in 0..1) {
            val a = 2 * PI * scenario.nextDouble()
            orbitX[i] = tx[i] + ORBIT_OFFSET_M * sin(a)
            orbitY[i] = ty[i] + ORBIT_OFFSET_M * cos(a)
        }
        sinkX = 0.5 * (tx[0] + tx[1])
        sinkY = 0.5 * (ty[0] + ty[1])
        thermals = List(2) { Thermal(proj.lat(ty[it]), proj.lon(tx[it]), tStrength[it], tRadius[it]) }
        updateSensors()
    }

    /** Advance simulated time. Call with small steps (<= 0.05 s). */
    fun step(dtS: Double) {
        if (finished) return
        timeS += dtS
        phaseT += dtS
        when (phase) {
            Phase.STAND -> if (timeS >= TAKEOFF_S) takeOff()
            Phase.LANDED -> if (phaseT >= LANDED_S) finished = true
            else -> fly(dtS)
        }
        updateSensors()
    }

    private fun takeOff() {
        flying = true
        phase = Phase.GLIDE1
        phaseT = 0.0
        ax = gx - windE * timeS
        ay = gy - windN * timeS
        beginGlideTo(orbitX[0], orbitY[0])
        heading = atan2(entryX - ax, entryY - ay)
    }

    /** Set the entry point E so that a clockwise circle of radius V/omega around (ox, oy) is joined tangentially. */
    private fun beginGlideTo(ox: Double, oy: Double) {
        val beta = atan2(ox - ax, oy - ay)
        val radius = tas(trueAlt) / CIRCLE_RATE
        entryX = ox + radius * -cos(beta)
        entryY = oy + radius * sin(beta)
    }

    private fun tas(alt: Double) = trimSpeed / sqrt(Atmosphere.densityRatio(alt))

    private fun fly(dt: Double) {
        val speedFactor = if (phase == Phase.FINAL) FINAL_SPEED_FACTOR else 1.0
        val v = tas(trueAlt) * speedFactor
        trueTas = v

        // Turn-rate command: circle, or steer toward the entry point / final heading.
        var cmd: Double
        when (phase) {
            Phase.CIRCLE1, Phase.CIRCLE2 -> cmd = CIRCLE_RATE
            else -> {
                val desired = if (phase == Phase.FINAL) FINAL_HEADING else atan2(entryX - ax, entryY - ay)
                cmd = angleDiff(desired, heading).coerceIn(-1.0, 1.0) * MAX_TURN_RATE
            }
        }
        turnRate += (cmd - turnRate) * minOf(1.0, dt / ROLL_TAU_S)
        heading += turnRate * dt

        val sinH = sin(heading)
        val cosH = cos(heading)
        groundVx = v * sinH + windE
        groundVy = v * cosH + windN
        ax += v * sinH * dt
        ay += v * cosH * dt
        gx += groundVx * dt
        gy += groundVy * dt

        // Vertical: air mass lift minus own sink (grows with load factor^1.5), through a first-order lag.
        val airMass = airMassClimb(ax, ay)
        val tanBank = turnRate * v / G
        val sink = TRIM_SINK * speedFactor.pow(3.0) * (1.0 + tanBank * tanBank).pow(0.75)
        val target = airMass - sink
        vertAccel = (target - trueVario) / VERTICAL_TAU_S
        val vOld = trueVario
        trueVario += vertAccel * dt
        trueAlt += 0.5 * (vOld + trueVario) * dt
        trueAirMassClimb = airMass

        phaseStep(dt)
    }

    private fun phaseStep(dt: Double) {
        when (phase) {
            Phase.GLIDE1 -> if (hypot(entryX - ax, entryY - ay) < ENTRY_RADIUS_M) enter(Phase.CIRCLE1)
            Phase.GLIDE2 -> if (hypot(entryX - ax, entryY - ay) < ENTRY_RADIUS_M) enter(Phase.CIRCLE2)
            Phase.CIRCLE1 -> if (trueAlt >= groundElevM + CLIMB_TO_M) {
                beginGlideTo(orbitX[1], orbitY[1])
                enter(Phase.GLIDE2)
            }
            Phase.CIRCLE2 -> if (trueAlt >= groundElevM + CLIMB_TO_M) enter(Phase.FINAL)
            else -> {}
        }
        if (trueAlt <= groundElevM - DESCENT_TO_M) land()
    }

    private fun enter(next: Phase) {
        phase = next
        phaseT = 0.0
    }

    private fun land() {
        trueAlt = groundElevM - DESCENT_TO_M
        trueVario = 0.0
        trueAirMassClimb = 0.0
        trueTas = 0.0
        vertAccel = 0.0
        turnRate = 0.0
        groundVx = 0.0
        groundVy = 0.0
        flying = false
        enter(Phase.LANDED)
    }

    /** Sum of the two Gaussian thermals and the (negative) sinking patch between them, at an air-frame position. */
    private fun airMassClimb(x: Double, y: Double): Double {
        var lift = 0.0
        for (i in 0..1) lift += gaussian(x - tx[i], y - ty[i], tStrength[i], tRadius[i])
        lift += gaussian(x - sinkX, y - sinkY, SINK_STRENGTH, SINK_RADIUS_M)
        return lift
    }

    private fun gaussian(dx: Double, dy: Double, strength: Double, radius: Double): Double {
        val d2 = (dx * dx + dy * dy) / (radius * radius)
        return if (d2 > 25.0) 0.0 else strength * exp(-d2)
    }

    private fun angleDiff(a: Double, b: Double): Double {
        var d = (a - b) % (2 * PI)
        if (d > PI) d -= 2 * PI
        if (d < -PI) d += 2 * PI
        return d
    }

    private fun updateSensors() {
        // Specific force: the pilot's weight hangs along the glider's up axis (coordinated flight), so
        // its length is sqrt(centripetal² + (g + vertical accel)²) and its direction is fixed in the
        // body. The phone is strapped on tilted: that direction in device axes is (UX, UY, UZ).
        val centripetal = if (flying) turnRate * trueTas else 0.0
        val s = hypot(centripetal, G + vertAccel)
        val noise = sensorNoise
        accel[0] = (s * UX + (if (noise) ACC_BIAS_X + ACC_SIGMA * noiseRng.nextGaussian() else 0.0)).toFloat()
        accel[1] = (s * UY + (if (noise) ACC_BIAS_Y + ACC_SIGMA * noiseRng.nextGaussian() else 0.0)).toFloat()
        accel[2] = (s * UZ + (if (noise) ACC_BIAS_Z + ACC_SIGMA * noiseRng.nextGaussian() else 0.0)).toFloat()
        // ISA pressure at the true altitude: the inverse of Atmosphere.altitude with QNH 1013.25.
        val p = Atmosphere.P0 * (1.0 - trueAlt / 44330.769).pow(1.0 / 0.190263)
        pressureHpa = p + if (noise) BARO_SIGMA_HPA * noiseRng.nextGaussian() else 0.0
    }

    /** GPS fix at the current simulated time. Speed and track are derived from the (noisy) ground velocity. */
    fun fix(epochStartMs: Long): Fix {
        var e = gx
        var n = gy
        var alt = trueAlt
        var vx = groundVx
        var vy = groundVy
        if (sensorNoise) {
            e += GPS_POS_SIGMA * fixRng.nextGaussian()
            n += GPS_POS_SIGMA * fixRng.nextGaussian()
            alt += GPS_ALT_SIGMA * fixRng.nextGaussian()
            vx += GPS_VEL_SIGMA * fixRng.nextGaussian()
            vy += GPS_VEL_SIGMA * fixRng.nextGaussian()
        }
        val speed = hypot(vx, vy)
        // A receiver cannot tell its course when (nearly) stationary.
        val track = if (speed < 0.5) Double.NaN else (Math.toDegrees(atan2(vx, vy)) + 360.0) % 360.0
        return Fix(epochStartMs + (timeS * 1000.0).roundToLong(), proj.lat(n), proj.lon(e), alt, speed, track, 3f)
    }

    companion object {
        private const val CLIMB_TO_M = 1000.0
        private const val G = 9.80665
        private const val TAKEOFF_S = 30.0
        private const val LANDED_S = 60.0
        private const val DESCENT_TO_M = 600.0
        private const val CIRCLE_RATE = 2 * PI / 18.0   // 18 s per turn
        private const val MAX_TURN_RATE = 0.35          // rad/s when steering between thermals
        private const val ROLL_TAU_S = 1.2              // roll-in / roll-out time constant
        private const val VERTICAL_TAU_S = 0.5          // how quickly the glider's vertical speed follows the air
        private const val TRIM_SINK = 1.1
        private const val FINAL_SPEED_FACTOR = 1.2      // accelerated: sink scales with speed³ -> 1.9 m/s
        private val FINAL_HEADING = Math.toRadians(140.0)
        private const val ENTRY_RADIUS_M = 5.0
        private const val ORBIT_OFFSET_M = 20.0         // the pilot never centres perfectly
        private const val SINK_STRENGTH = -2.5
        private const val SINK_RADIUS_M = 110.0

        // Sensor model.
        private const val ACC_SIGMA = 0.15
        private const val ACC_BIAS_X = 0.07
        private const val ACC_BIAS_Y = -0.05
        private const val ACC_BIAS_Z = 0.09
        private const val BARO_SIGMA_HPA = 0.03
        private const val GPS_POS_SIGMA = 2.0
        private const val GPS_ALT_SIGMA = 4.0
        private const val GPS_VEL_SIGMA = 0.15

        // Unit vector of the glider's up axis in the device frame (device tilted ~32 degrees).
        private val UX: Double
        private val UY: Double
        private val UZ: Double

        init {
            val x = 0.42
            val y = -0.31
            val z = 0.85
            val norm = sqrt(x * x + y * y + z * z)
            UX = x / norm; UY = y / norm; UZ = z / norm
        }
    }
}
