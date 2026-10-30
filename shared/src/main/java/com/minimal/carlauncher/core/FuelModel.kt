package com.minimal.carlauncher.core

import java.util.Calendar
import kotlin.math.max
import kotlin.math.min

/**
 * City / highway fuel model on top of the fill-up log.
 *
 * Each measured tank (full fill to full fill) gives litres used and km driven; the trips in
 * that window tell how many of those km were at highway speed and how long the engine
 * idled. Fitting   litres - idle = cityKm / kmplCity + hwyKm / kmplHwy   across tanks
 * (with a gentle pull towards the overall average, so one odd tank can't swing it) yields
 * two km/l values. Range then uses the mix of the driver's recent trips.
 */
data class FuelModelResult(
    val kmplCity: Float,
    val kmplHwy: Float,
    /** Tanks with trip data that the split was learned from (0 = not learned yet). */
    val tanksUsed: Int,
    val litresLeft: Float,
    val rangeKm: Int,
    /** Share of recent km at highway speed, or -1 when unknown. */
    val recentHwyShare: Double,
    /** Effective km/l for the recent mix. */
    val kmplNow: Float,
    val idleLitresSinceFill: Float,
    /** Average km per driving day over the last two weeks, or -1 with too little data. */
    val dailyKm: Double,
)

object FuelModel {

    /** Typical idle consumption of a 1.0 - 1.5 L petrol engine. */
    const val IDLE_LPH = 0.7f

    /** "Highway" means 55 km/h or faster. */
    const val HWY_KMH = 55f

    /** Range kept back as reserve when forecasting the refuel day. */
    const val RESERVE_KM = 50

    private const val PRIOR_WEIGHT = 150.0 * 150.0   // about one tank's worth of evidence

    fun idleLitres(idleSec: Long): Float = idleSec / 3600f * IDLE_LPH

    private fun share(trips: List<TripRecord>): Double {
        val known = trips.filter { it.hwyKm >= 0.0 && it.km > 0.0 }
        val km = known.sumOf { it.km }
        return if (km < 1.0) -1.0 else (known.sumOf { min(it.hwyKm, it.km) } / km).coerceIn(0.0, 1.0)
    }

    /** km/l for a given highway share. */
    fun mix(kmplCity: Float, kmplHwy: Float, hwyShare: Double): Float {
        if (hwyShare < 0.0) return (kmplCity + kmplHwy) / 2f
        val lpk = (1 - hwyShare) / kmplCity + hwyShare / kmplHwy
        return (1.0 / lpk).toFloat()
    }

    /** Estimated litres used by one trip (driving plus idling). */
    fun tripLitres(t: TripRecord, kmplCity: Float, kmplHwy: Float, fallbackShare: Double): Float {
        val f = if (t.hwyKm >= 0.0 && t.km > 0.0) (t.hwyKm / t.km).coerceIn(0.0, 1.0)
            else if (fallbackShare >= 0.0) fallbackShare else 0.0
        val drive = t.km * ((1 - f) / kmplCity + f / kmplHwy)
        return drive.toFloat() + idleLitres(t.idleSec)
    }

    /**
     * Learns the split and computes the current range.
     * @param trips finished trips plus the live one, any order.
     */
    fun compute(r: FuelReplay, trips: List<TripRecord>, gpsKm: Double, now: Long): FuelModelResult {
        val s = r.state
        val k = s.kmpl

        // ---- learn kmpl city / highway from tanks that trips cover well
        var scc = 0.0; var sch = 0.0; var shh = 0.0; var scl = 0.0; var shl = 0.0; var used = 0
        for (t in r.tanks) {
            if (t.startTime <= 0L) continue
            val inTank = trips.filter { it.start >= t.startTime && it.start < t.time }
            val covered = inTank.filter { it.hwyKm >= 0.0 }.sumOf { it.km }
            if (covered < 0.5 * t.km) continue
            val f = share(inTank).takeIf { it >= 0.0 } ?: continue
            val litres = max(0.1, t.litres - idleLitres(inTank.sumOf { it.idleSec }).toDouble())
            val c = t.km * (1 - f); val h = t.km * f
            scc += c * c; sch += c * h; shh += h * h; scl += c * litres; shl += h * litres
            used++
        }
        var kc = k; var kh = k
        if (used > 0) {
            val a0 = 1.0 / (k * 0.9); val b0 = 1.0 / (k * 1.12)
            val m11 = scc + PRIOR_WEIGHT; val m12 = sch; val m22 = shh + PRIOR_WEIGHT
            val r1 = scl + PRIOR_WEIGHT * a0; val r2 = shl + PRIOR_WEIGHT * b0
            val det = m11 * m22 - m12 * m12
            if (det > 1e-9) {
                val a = (r1 * m22 - r2 * m12) / det
                val b = (m11 * r2 - m12 * r1) / det
                if (a > 0 && b > 0) {
                    kc = (1.0 / a).toFloat().coerceIn(FuelEstimator.MIN_KMPL, FuelEstimator.MAX_KMPL)
                    kh = (1.0 / b).toFloat().coerceIn(FuelEstimator.MIN_KMPL, FuelEstimator.MAX_KMPL)
                }
            }
        }

        // ---- recent mix: the last ~150 km of trips
        val newestFirst = trips.sortedByDescending { it.start }
        val recent = ArrayList<TripRecord>(); var acc = 0.0
        for (t in newestFirst) { recent += t; acc += t.km; if (acc >= 150.0) break }
        val recentShare = share(recent)

        // ---- fuel used since the last fill
        val sinceFill = trips.filter { r.anchorTime <= 0L || it.end >= r.anchorTime }
        val fillShare = share(sinceFill).takeIf { it >= 0.0 } ?: recentShare
        val idleL = idleLitres(sinceFill.sumOf { it.idleSec })
        val dist = max(0.0, gpsKm - s.anchorGpsKm)
        val litresLeft = if (!s.configured) -1f else {
            val driveL = if (used == 0 && fillShare < 0.0) dist / k
                else dist / mix(kc, kh, if (fillShare < 0.0) 0.5 else fillShare)
            // Before the split is learned, idle is already inside the single km/l average.
            max(0f, s.anchorLitres - driveL.toFloat() - (if (used > 0) idleL else 0f))
        }
        val kmplNow = if (used == 0) k else mix(kc, kh, if (recentShare < 0.0) 0.5 else recentShare)
        val range = if (!s.configured) -1 else (litresLeft * kmplNow).toInt()

        return FuelModelResult(kc, kh, used, litresLeft, range, recentShare, kmplNow, idleL, dailyKm(trips, now))
    }

    /** Average km per calendar day over the last 14 days; -1 with under 3 days of history. */
    fun dailyKm(trips: List<TripRecord>, now: Long): Double {
        if (trips.isEmpty()) return -1.0
        val first = trips.minOf { it.start }
        val days = min(14.0, (now - first) / 86_400_000.0)
        if (days < 3.0) return -1.0
        val from = now - (days * 86_400_000.0).toLong()
        val km = trips.filter { it.start >= from }.sumOf { it.km }
        return if (km <= 0.0) -1.0 else km / days
    }

    /** Wall-clock ms by which to refuel (range down to the reserve), or null if unknown. */
    fun refuelBy(rangeKm: Int, dailyKm: Double, now: Long): Long? {
        if (rangeKm < 0 || dailyKm <= 0.0) return null
        val days = max(0.0, (rangeKm - RESERVE_KM) / dailyKm)
        return Calendar.getInstance().apply {
            timeInMillis = now + (days * 86_400_000.0).toLong()
        }.timeInMillis
    }

    /** 0 unknown, 2 normal, 3 low, 4 critical (same scale as the battery pill). */
    fun level(rangeKm: Int): Int = when {
        rangeKm < 0 -> 0
        rangeKm < 40 -> 4
        rangeKm < 90 -> 3
        else -> 2
    }
}
