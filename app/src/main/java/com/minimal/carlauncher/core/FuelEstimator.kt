package com.minimal.carlauncher.core

import kotlin.math.max
import kotlin.math.min

/**
 * Software fuel gauge: no car sensor, just arithmetic the driver keeps honest.
 *
 * - At setup the driver gives tank size, a rough km/l and how full the tank is now.
 * - Distance comes from the GPS odometer (km driven, accumulated by PanelService) or, more
 *   accurately, from car odometer readings typed in at fill-ups.
 * - Range left = fuel at the last fill (litres) * km/l - km driven since then.
 * - Between two FULL fills, with the litres from the pump, the real km/l is measured and
 *   blended into the estimate, so it converges on the car's actual consumption.
 *
 * Pure state + functions so it can be unit tested; persistence is the caller's job.
 */
data class FuelState(
    val tankL: Float = 37f,
    val kmpl: Float = 18f,
    /** Litres in the tank at the last anchor (setup or fill-up). */
    val anchorLitres: Float = -1f,
    /** GPS odometer (km) at the last anchor. */
    val anchorGpsKm: Double = 0.0,
    /** Car odometer typed at the last FULL fill, or -1. */
    val lastFullOdo: Int = -1,
    /** GPS odometer at the last FULL fill. */
    val lastFullGpsKm: Double = -1.0,
    /** Litres added by partial top-ups since the last FULL fill. */
    val partialLitresSinceFull: Float = 0f,
) {
    val configured: Boolean get() = anchorLitres >= 0f
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
            )
        }

        // Full fill: learn km/l from the stretch since the previous full fill, if possible.
        var kmpl = s.kmpl
        val haveLastFull = s.lastFullGpsKm >= 0.0
        if (haveLastFull && litres != null && litres > 0f) {
            val distKm = if (odo != null && s.lastFullOdo >= 0 && odo > s.lastFullOdo) {
                (odo - s.lastFullOdo).toDouble()
            } else {
                gpsKm - s.lastFullGpsKm
            }
            val fuel = litres + s.partialLitresSinceFull
            if (distKm >= MIN_LEARN_KM && fuel > 0f) {
                val measured = (distKm / fuel).toFloat()
                if (measured in MIN_KMPL..MAX_KMPL) {
                    kmpl = kmpl * (1f - LEARN_WEIGHT) + measured * LEARN_WEIGHT
                }
            }
        }
        return s.copy(
            kmpl = kmpl.coerceIn(MIN_KMPL, MAX_KMPL),
            anchorLitres = s.tankL,
            anchorGpsKm = gpsKm,
            lastFullOdo = odo ?: -1,
            lastFullGpsKm = gpsKm,
            partialLitresSinceFull = 0f,
        )
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
}
