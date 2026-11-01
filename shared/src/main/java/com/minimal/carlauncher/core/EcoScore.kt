package com.minimal.carlauncher.core

import java.util.Calendar
import kotlin.math.roundToInt

/**
 * A simple smooth-driving score per trip, from what the head unit can measure with GPS alone:
 * hard accelerations, hard brakes, idling share and very high speed. 100 is perfect.
 * Penalties are per 10 km so a long trip isn't punished for being long.
 */
object EcoScore {

    /** Thresholds the panel uses on GPS speed (m/s^2): about 0-40 km/h in under 3.7 s. */
    const val HARD_ACCEL_MS2 = 3.0
    const val HARD_BRAKE_MS2 = -3.5
    const val FAST_KMH = 120f
    /** Shorter trips get no score: too little driving to judge. */
    const val MIN_KM = 2.0

    data class Result(val score: Int, val tip: String)

    fun of(t: TripRecord): Result? {
        if (t.isParked || t.km < MIN_KM || t.hardBrake < 0 || t.hardAccel < 0) return null
        val per10 = 10.0 / t.km
        val brakeP = t.hardBrake * per10 * 8.0
        val accelP = t.hardAccel * per10 * 6.0
        val total = (t.driveSec + t.idleSec).coerceAtLeast(1)
        val idleShare = t.idleSec.toDouble() / total
        val idleP = ((idleShare - 0.10).coerceAtLeast(0.0) * 100.0 * 0.6)
        val speedP = if (t.maxKmh > FAST_KMH) ((t.maxKmh - FAST_KMH) / 4.0).coerceAtMost(10.0) else 0.0
        val score = (100.0 - brakeP - accelP - idleP - speedP).coerceIn(30.0, 100.0).roundToInt()
        val worst = listOf(
            brakeP to "Brake earlier and more gently",
            accelP to "Pull away more smoothly",
            idleP to "Switch off when waiting for long",
            speedP to "Ease off at very high speed",
        ).maxByOrNull { it.first }!!
        val tip = if (worst.first < 3.0) "Smooth drive" else worst.second
        return Result(score, tip)
    }

    /** Average score of the scored trips in a list, or -1. */
    fun average(trips: List<TripRecord>): Int {
        val s = trips.mapNotNull { of(it)?.score }
        return if (s.isEmpty()) -1 else s.average().roundToInt()
    }
}

/** Totals for a period (a week, a month) for the summaries. */
data class PeriodSummary(
    val trips: Int, val km: Double, val driveSec: Long, val idleSec: Long, val litres: Double, val eco: Int,
)

object Summaries {

    fun of(trips: List<TripRecord>, from: Long, to: Long, litresOf: (TripRecord) -> Float): PeriodSummary {
        val sel = trips.filter { it.start in from until to }
        val real = sel.filter { !it.isParked }
        return PeriodSummary(
            real.size, real.sumOf { it.km }, real.sumOf { it.driveSec }, sel.sumOf { it.idleSec },
            sel.sumOf { litresOf(it).toDouble() }, EcoScore.average(real),
        )
    }

    /** Monday 00:00 of the week before the one containing [now]. */
    fun lastWeekStart(now: Long): Long = Calendar.getInstance().apply {
        timeInMillis = TripLog.startOfWeek(now); add(Calendar.DAY_OF_MONTH, -7)
    }.timeInMillis

    fun prevMonthStart(now: Long): Long = Calendar.getInstance().apply {
        timeInMillis = TripLog.startOfMonth(now); add(Calendar.MONTH, -1)
    }.timeInMillis

    /** "+12%" / "-5%" / "" when there is nothing to compare with. */
    fun change(now: Double, before: Double): String {
        if (before <= 0.0) return ""
        val p = ((now - before) / before * 100).roundToInt()
        return (if (p >= 0) "+" else "") + p + "%"
    }
}
