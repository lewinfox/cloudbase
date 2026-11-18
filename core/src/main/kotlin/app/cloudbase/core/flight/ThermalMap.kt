package app.cloudbase.core.flight

import app.cloudbase.core.LocalProjection
import app.cloudbase.core.Wind
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * Heat map of air-mass vertical speed, kept in the frame of the air.
 *
 * Thermals drift with the wind, so the grid is laid out in *air coordinates*: a cell's stored
 * position s is its ground position minus the accumulated wind drift D at the time it was written
 * (s = p - D(t_rec)). Its present ground position is always s + D(now). Consequences:
 *  - [drift] is O(1): it only advances D.
 *  - a sample taken later lands in the same cell as an earlier sample of the same air, however far
 *    the air has moved in between, because both are converted with the D of their own time;
 *  - cells written at different times keep their correct relative positions as D keeps growing.
 *
 * Storage is an open-addressing hash table (linear probing, backward-shift deletion, load <= 0.5)
 * over parallel primitive arrays, so add() never allocates. Cell (i, j) is centred on
 * (i*cellM, j*cellM) in air coordinates relative to the first sample, so the first sample sits
 * exactly on a cell centre.
 *
 * Limitations: flat-earth projection anchored at the first sample (fine within a few tens of km,
 * not for a flight across a continent without [clear]); bounds crossing the antimeridian are not handled.
 */
class ThermalMap(val cellM: Double = 30.0, val maxAgeMs: Long = 20 * 60_000L, val maxCells: Int = 3000) {

    fun interface Visitor { fun cell(lat: Double, lon: Double, climb: Double, ageMs: Long) }

    /** Result of [bestLift]. [climb] is the strongest cell used. */
    class Core(val lat: Double, val lon: Double, val climb: Double)

    init {
        require(cellM > 0.0 && maxCells > 0 && maxAgeMs > 0)
    }

    private val capacity: Int
    private val mask: Int
    private val shift: Int
    private val keys: LongArray
    private val climbs: DoubleArray
    private val times: LongArray

    init {
        var cap = 16
        while (cap < 2 * maxCells) cap = cap shl 1
        capacity = cap
        mask = cap - 1
        shift = 64 - Integer.numberOfTrailingZeros(cap)
        keys = LongArray(cap) { EMPTY }
        climbs = DoubleArray(cap)
        times = LongArray(cap)
    }

    private var proj: LocalProjection? = null
    private var driftE = 0.0
    private var driftN = 0.0
    private var lastPurgeMs = 0L
    private var count = 0

    /** Number of cells held (expired ones disappear at the next purge, at most [PURGE_INTERVAL_MS] later). */
    val size: Int get() = count

    fun clear() {
        keys.fill(EMPTY)
        count = 0
        proj = null
        driftE = 0.0
        driftN = 0.0
        lastPurgeMs = 0L
    }

    /** Record air-mass vertical speed (m/s) at a position. */
    fun add(lat: Double, lon: Double, climb: Double, timeMs: Long) {
        if (lat.isNaN() || lon.isNaN() || climb.isNaN()) return
        val p = proj ?: LocalProjection(lat, lon).also { proj = it; lastPurgeMs = timeMs }
        // (A clock that jumps backwards also triggers a purge, which is harmless.)
        if (timeMs - lastPurgeMs !in 0 until PURGE_INTERVAL_MS) purge(timeMs)

        val sx = p.x(lon) - driftE
        val sy = p.y(lat) - driftN
        val ci = floor(sx / cellM + 0.5)
        val cj = floor(sy / cellM + 0.5)
        if (kotlin.math.abs(ci) > MAX_INDEX || kotlin.math.abs(cj) > MAX_INDEX) return
        val key = pack(ci.toInt(), cj.toInt())

        var slot = home(key)
        while (true) {
            val k = keys[slot]
            if (k == EMPTY) break
            if (k == key) {
                if (timeMs - times[slot] > maxAgeMs) {
                    climbs[slot] = climb // expired but not yet purged: start afresh
                } else {
                    climbs[slot] += NEW_WEIGHT * (climb - climbs[slot])
                }
                times[slot] = timeMs
                return
            }
            slot = (slot + 1) and mask
        }
        if (count >= maxCells) {
            purge(timeMs)
            if (count >= maxCells) removeAt(oldestSlot())
            // Backward-shift deletion may have moved entries: probe again for the free slot.
            slot = home(key)
            while (keys[slot] != EMPTY) slot = (slot + 1) and mask
        }
        keys[slot] = key
        climbs[slot] = climb
        times[slot] = timeMs
        count++
    }

    /** Move the whole map downwind: thermals drift with the air. Call every fix with the elapsed time. */
    fun drift(wind: Wind, dtS: Double) {
        // Wind.fromDeg is where the wind comes FROM; the air moves the opposite way.
        val toward = Math.toRadians(wind.fromDeg + 180.0)
        driftE += wind.speed * sin(toward) * dtS
        driftN += wind.speed * cos(toward) * dtS
    }

    /** Visit cells inside the bounds (drifted positions), without allocating. */
    fun forEach(minLat: Double, minLon: Double, maxLat: Double, maxLon: Double, nowMs: Long, visitor: Visitor) {
        val p = proj ?: return
        // Compare in projected metres; convert to lat/lon only for the survivors.
        val minX = p.x(minLon); val maxX = p.x(maxLon)
        val minY = p.y(minLat); val maxY = p.y(maxLat)
        for (slot in 0 until capacity) {
            val key = keys[slot]
            if (key == EMPTY) continue
            val age = nowMs - times[slot]
            if (age > maxAgeMs) continue
            val x = cellX(key) + driftE
            val y = cellY(key) + driftN
            if (x < minX || x > maxX || y < minY || y > maxY) continue
            visitor.cell(p.lat(y), p.lon(x), climbs[slot], age)
        }
    }

    /**
     * Centre of the best recent lift within [radiusM] of (lat, lon): the climb-weighted centroid of
     * cells that are >= 60 % of the strongest eligible cell and > 0.5 m/s, newer than
     * [maxAgeForCoreMs]. Null if there is no such cell.
     */
    fun bestLift(lat: Double, lon: Double, radiusM: Double, nowMs: Long, maxAgeForCoreMs: Long = 4 * 60_000L): Core? {
        val p = proj ?: return null
        val px = p.x(lon)
        val py = p.y(lat)
        val r2 = radiusM * radiusM
        val ageLimit = minOf(maxAgeForCoreMs, maxAgeMs)

        var best = MIN_CORE_CLIMB
        for (slot in 0 until capacity) {
            val key = keys[slot]
            if (key == EMPTY || nowMs - times[slot] > ageLimit) continue
            if (inside(key, px, py, r2) && climbs[slot] > best) best = climbs[slot]
        }
        if (best <= MIN_CORE_CLIMB) return null

        val threshold = CORE_FRACTION * best
        var sumW = 0.0
        var sumX = 0.0
        var sumY = 0.0
        for (slot in 0 until capacity) {
            val key = keys[slot]
            if (key == EMPTY || nowMs - times[slot] > ageLimit) continue
            val w = climbs[slot]
            if (w < threshold || w <= MIN_CORE_CLIMB || !inside(key, px, py, r2)) continue
            sumW += w
            sumX += w * (cellX(key) + driftE)
            sumY += w * (cellY(key) + driftN)
        }
        return Core(p.lat(sumY / sumW), p.lon(sumX / sumW), best)
    }

    private fun inside(key: Long, px: Double, py: Double, r2: Double): Boolean {
        val dx = cellX(key) + driftE - px
        val dy = cellY(key) + driftN - py
        return dx * dx + dy * dy <= r2
    }

    // --- Hash table internals ---

    private fun cellX(key: Long): Double = (key shr 32).toInt() * cellM
    private fun cellY(key: Long): Double = key.toInt() * cellM

    private fun home(key: Long): Int = ((key * HASH_MULT) ushr shift).toInt()

    /** Drop every cell older than [maxAgeMs]. O(capacity), run at most every [PURGE_INTERVAL_MS]. */
    private fun purge(nowMs: Long) {
        lastPurgeMs = nowMs
        var slot = 0
        while (slot < capacity) {
            if (keys[slot] != EMPTY && nowMs - times[slot] > maxAgeMs) {
                // Backward shift may pull a not-yet-examined entry into this slot: re-test it.
                removeAt(slot)
            } else {
                slot++
            }
        }
    }

    private fun oldestSlot(): Int {
        var oldest = -1
        var t = Long.MAX_VALUE
        for (slot in 0 until capacity) {
            if (keys[slot] != EMPTY && times[slot] < t) { t = times[slot]; oldest = slot }
        }
        return oldest
    }

    /** Linear-probing deletion without tombstones: shift later members of the cluster back over the hole. */
    private fun removeAt(slot: Int) {
        var hole = slot
        var j = slot
        while (true) {
            j = (j + 1) and mask
            val k = keys[j]
            if (k == EMPTY) break
            val h = home(k)
            // Entry j may fill the hole only if its home is not cyclically within (hole, j].
            val movable = if (hole <= j) (h <= hole || h > j) else (h <= hole && h > j)
            if (movable) {
                keys[hole] = k
                climbs[hole] = climbs[j]
                times[hole] = times[j]
                hole = j
            }
        }
        keys[hole] = EMPTY
        count--
    }

    private companion object {
        const val EMPTY = Long.MIN_VALUE
        const val NEW_WEIGHT = 0.4
        const val MAX_INDEX = 1.0e9
        const val PURGE_INTERVAL_MS = 10_000L
        const val MIN_CORE_CLIMB = 0.5
        const val CORE_FRACTION = 0.6
        const val HASH_MULT = -7046029254386353131L // 0x9E3779B97F4A7C15

        fun pack(i: Int, j: Int): Long = (i.toLong() shl 32) or (j.toLong() and 0xFFFFFFFFL)
    }
}
