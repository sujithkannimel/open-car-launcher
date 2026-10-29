package com.minimal.carlauncher.core

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

/**
 * Software fuel gauge: no car sensor, just arithmetic the driver keeps honest.
 *
 * - At set-up the driver gives tank size, a rough km/l and how full the tank is now.
 * - Distance comes from the GPS odometer (km driven, accumulated by PanelService) or, more
 *   accurately, from car odometer readings typed in at fill-ups or as corrections.
 * - Range left = fuel at the last fill (litres) * km/l - km driven since then.
 * - Between two FULL fills, with the litres from the pump, the real km/l is measured and
 *   blended into the estimate, so it converges on the car's actual consumption.
 *
 * Everything the driver enters is kept as a list of [FuelEvent]s and the [FuelState] is
 * rebuilt from it by [FuelEstimator.replay], so any entry can be edited or deleted later.
 */
data class FuelState(
    val tankL: Float = 37f,
    val kmpl: Float = 18f,
    /** Litres in the tank at the last anchor (set-up or fill-up). */
    val anchorLitres: Float = -1f,
    /** GPS odometer (km) at the last anchor. */
    val anchorGpsKm: Double = 0.0,
    /** Car odometer typed at the last FULL fill, or -1. */
    val lastFullOdo: Int = -1,
    /** GPS odometer at the last FULL fill. */
    val lastFullGpsKm: Double = -1.0,
    /** Litres added by partial top-ups since the last FULL fill. */
    val partialLitresSinceFull: Float = 0f,
    /** Car odometer (real or estimated) at the last anchor, or -1. */
    val anchorOdo: Int = -1,
) {
    val configured: Boolean get() = anchorLitres >= 0f
}

/** One thing the driver entered. */
data class FuelEvent(
    val kind: String,              // KIND_SETUP / KIND_FILL / KIND_ODO
    val time: Long,                // wall-clock ms, 0 = unknown (migrated)
    val gpsKm: Double,             // GPS odometer when entered
    val odo: Int = -1,             // car odometer typed, or -1
    val litres: Float = -1f,       // pump litres, or -1
    val full: Boolean = false,     // fill: to the brim?
    val tankL: Float = 0f,         // set-up only
    val kmpl: Float = 0f,          // set-up only
    val fraction: Float = 1f,      // set-up only: how full at set-up
) {
    companion object {
        const val KIND_SETUP = "setup"
        const val KIND_FILL = "fill"
        const val KIND_ODO = "odo"
    }
}

/** One row of the fill-up log, as shown to the driver. */
data class FuelLogRow(
    val index: Int,                // position in the event list (for edit / delete)
    val event: FuelEvent,
    /** Car odometer at this entry: typed, or estimated from GPS since the last typed one. */
    val odoAt: Int,
    val odoEstimated: Boolean,
    /** km since the previous fill or set-up, or -1. */
    val kmSincePrev: Double,
    /** km/l measured for the tank this full fill closed, or -1. */
    val measuredKmpl: Float,
)

/** A tank measured between two full fills. */
data class TankResult(val time: Long, val km: Double, val litres: Float, val kmpl: Float)

data class FuelReplay(
    val state: FuelState,
    val rows: List<FuelLogRow>,
    val tanks: List<TankResult>,
    /** Last odometer the driver typed and the GPS odometer at that moment, or null. */
    val odoRef: Pair<Int, Double>?,
) {
    /** Estimated car odometer now, or -1 if the driver never typed one. */
    fun estimatedOdo(gpsKm: Double): Int =
        odoRef?.let { (o, g) -> (o + max(0.0, gpsKm - g)).toInt() } ?: -1
}

object FuelEstimator {

    const val MIN_KMPL = 5f
    const val MAX_KMPL = 40f

    /** Learned km/l only counts after a meaningful stretch. */
    const val MIN_LEARN_KM = 50.0

    /** Weight of a new measurement when blending into the running km/l. */
    const val LEARN_WEIGHT = 0.4f

    fun setup(tankL: Float, kmpl: Float, fraction: Float, gpsKm: Double): FuelState {
        val tank = tankL.coerceIn(10f, 120f)
        return FuelState(
            tankL = tank,
            kmpl = kmpl.coerceIn(MIN_KMPL, MAX_KMPL),
            anchorLitres = tank * fraction.coerceIn(0f, 1f),
            anchorGpsKm = gpsKm,
        )
    }

    fun litresLeft(s: FuelState, gpsKm: Double): Float {
        if (!s.configured) return -1f
        val used = (max(0.0, gpsKm - s.anchorGpsKm) / s.kmpl).toFloat()
        return max(0f, s.anchorLitres - used)
    }

    fun rangeKm(s: FuelState, gpsKm: Double): Int =
        if (!s.configured) -1 else (litresLeft(s, gpsKm) * s.kmpl).toInt()

    /**
     * km/l for the stretch since the previous full fill, or null when it cannot be measured
     * (no previous full fill, no litres, too short, or an absurd value).
     */
    fun measure(s: FuelState, litres: Float?, odo: Int?, gpsKm: Double): Float? {
        if (s.lastFullGpsKm < 0.0 || litres == null || litres <= 0f) return null
        val distKm = if (odo != null && s.lastFullOdo >= 0 && odo > s.lastFullOdo) {
            (odo - s.lastFullOdo).toDouble()
        } else {
            gpsKm - s.lastFullGpsKm
        }
        val fuel = litres + s.partialLitresSinceFull
        if (distKm < MIN_LEARN_KM || fuel <= 0f) return null
        val measured = (distKm / fuel).toFloat()
        return measured.takeIf { it in MIN_KMPL..MAX_KMPL }
    }

    /**
     * Records a fill-up.
     * @param full true = filled to the brim.
     * @param litres litres from the pump; required for a partial fill, optional for full.
     * @param odo car odometer in km, or null.
     */
    fun fill(s: FuelState, full: Boolean, litres: Float?, odo: Int?, gpsKm: Double): FuelState {
        val left = if (s.configured) litresLeft(s, gpsKm) else 0f
        if (!full) {
            val add = (litres ?: 0f).coerceAtLeast(0f)
            return s.copy(
                anchorLitres = min(s.tankL, left + add),
                anchorGpsKm = gpsKm,
                partialLitresSinceFull = s.partialLitresSinceFull + add,
                anchorOdo = odo ?: -1,
            )
        }
        var kmpl = s.kmpl
        measure(s, litres, odo, gpsKm)?.let { kmpl = kmpl * (1f - LEARN_WEIGHT) + it * LEARN_WEIGHT }
        return s.copy(
            kmpl = kmpl.coerceIn(MIN_KMPL, MAX_KMPL),
            anchorLitres = s.tankL,
            anchorGpsKm = gpsKm,
            lastFullOdo = odo ?: -1,
            lastFullGpsKm = gpsKm,
            partialLitresSinceFull = 0f,
            anchorOdo = odo ?: -1,
        )
    }

    /**
     * Driver typed the current odometer: re-base the distance since the last anchor (and
     * the last full fill) on the real odometer instead of GPS.
     */
    fun correct(s: FuelState, odo: Int, gpsKm: Double): FuelState {
        var out = s
        if (s.anchorOdo >= 0) {
            val real = max(0, odo - s.anchorOdo).toDouble()
            out = out.copy(anchorGpsKm = gpsKm - real)
        }
        if (s.lastFullOdo >= 0 && s.lastFullGpsKm >= 0.0) {
            val real = max(0, odo - s.lastFullOdo).toDouble()
            out = out.copy(lastFullGpsKm = gpsKm - real)
        }
        return out
    }

    /** Rebuilds state, log rows and per-tank results from the driver's entries, in order. */
    fun replay(events: List<FuelEvent>): FuelReplay {
        var s = FuelState()
        var odoRef: Pair<Int, Double>? = null
        val rows = ArrayList<FuelLogRow>()
        val tanks = ArrayList<TankResult>()
        var prevGps = -1.0
        var prevOdo = -1

        fun est(g: Double): Int = odoRef?.let { (o, rg) -> (o + max(0.0, g - rg)).toInt() } ?: -1

        events.forEachIndexed { i, e ->
            val typed = e.odo >= 0
            val odoAt = if (typed) e.odo else est(e.gpsKm)
            when (e.kind) {
                FuelEvent.KIND_SETUP -> {
                    s = setup(e.tankL, e.kmpl, e.fraction, e.gpsKm).copy(anchorOdo = odoAt)
                    rows += FuelLogRow(i, e, odoAt, !typed && odoAt >= 0, -1.0, -1f)
                    prevGps = e.gpsKm; prevOdo = odoAt
                }
                FuelEvent.KIND_FILL -> {
                    if (!s.configured) return@forEachIndexed
                    val litres = e.litres.takeIf { it > 0f }
                    val odoArg = odoAt.takeIf { it >= 0 }
                    val measured = if (e.full) measure(s, litres, odoArg, e.gpsKm) else null
                    if (measured != null) {
                        val km = if (odoArg != null && s.lastFullOdo >= 0 && odoArg > s.lastFullOdo)
                            (odoArg - s.lastFullOdo).toDouble() else e.gpsKm - s.lastFullGpsKm
                        tanks += TankResult(e.time, km, (litres ?: 0f) + s.partialLitresSinceFull, measured)
                    }
                    val since = when {
                        prevOdo >= 0 && odoAt >= 0 && odoAt >= prevOdo -> (odoAt - prevOdo).toDouble()
                        prevGps >= 0.0 -> max(0.0, e.gpsKm - prevGps)
                        else -> -1.0
                    }
                    s = fill(s, e.full, litres, odoArg, e.gpsKm)
                    rows += FuelLogRow(i, e, odoAt, !typed && odoAt >= 0, since, measured ?: -1f)
                    prevGps = e.gpsKm; prevOdo = odoAt
                }
                FuelEvent.KIND_ODO -> {
                    if (typed) {
                        s = correct(s, e.odo, e.gpsKm)
                        if (prevOdo < 0 && s.anchorOdo < 0) {
                            // First odometer ever typed: back-fill the anchor from GPS.
                            val back = (e.odo - max(0.0, e.gpsKm - s.anchorGpsKm)).toInt()
                            s = s.copy(anchorOdo = back)
                        }
                        rows += FuelLogRow(i, e, e.odo, false, -1.0, -1f)
                    }
                }
            }
            if (typed) odoRef = e.odo to e.gpsKm
        }
        return FuelReplay(s, rows, tanks, odoRef)
    }

    /** 0 unknown, 2 normal, 3 low, 4 critical (same scale as the battery pill). */
    fun level(s: FuelState, gpsKm: Double): Int {
        val r = rangeKm(s, gpsKm)
        return when {
            r < 0 -> 0
            r < 40 -> 4
            r < 90 -> 3
            else -> 2
        }
    }

    // ---------------------------------------------------------------- storage

    fun encode(events: List<FuelEvent>): String = JSONArray().apply {
        events.forEach { e ->
            put(JSONObject().apply {
                put("k", e.kind); put("t", e.time); put("g", e.gpsKm); put("o", e.odo)
                put("l", e.litres.toDouble()); put("f", e.full); put("tk", e.tankL.toDouble())
                put("m", e.kmpl.toDouble()); put("fr", e.fraction.toDouble())
            })
        }
    }.toString()

    fun decode(json: String?): List<FuelEvent> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                FuelEvent(
                    kind = o.optString("k"), time = o.optLong("t"), gpsKm = o.optDouble("g", 0.0),
                    odo = o.optInt("o", -1), litres = o.optDouble("l", -1.0).toFloat(),
                    full = o.optBoolean("f"), tankL = o.optDouble("tk", 0.0).toFloat(),
                    kmpl = o.optDouble("m", 0.0).toFloat(), fraction = o.optDouble("fr", 1.0).toFloat(),
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Turns a pre-history FuelState into equivalent events (time unknown = 0). */
    fun migrate(s: FuelState): List<FuelEvent> {
        if (!s.configured) return emptyList()
        if (s.lastFullGpsKm >= 0.0) {
            val out = mutableListOf(
                FuelEvent(FuelEvent.KIND_SETUP, 0L, s.lastFullGpsKm, tankL = s.tankL, kmpl = s.kmpl, fraction = 1f),
                FuelEvent(FuelEvent.KIND_FILL, 0L, s.lastFullGpsKm, odo = s.lastFullOdo, full = true),
            )
            if (s.partialLitresSinceFull > 0f) {
                out += FuelEvent(FuelEvent.KIND_FILL, 0L, s.anchorGpsKm, litres = s.partialLitresSinceFull)
            }
            return out
        }
        return listOf(
            FuelEvent(FuelEvent.KIND_SETUP, 0L, s.anchorGpsKm, tankL = s.tankL, kmpl = s.kmpl,
                fraction = (s.anchorLitres / s.tankL).coerceIn(0f, 1f))
        )
    }
}
