package com.minimal.carlauncher.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Battery health from the head unit's real voltage signal (NWD CAN broadcast, ~every 30 s).
 *
 * - Resting voltage: a reading in the first minutes after ignition on, before the engine is
 *   charging (< 13.0 V, not moving). The lowest one each day is kept, which is usually the
 *   morning start after the longest rest. A slow decline over weeks means an ageing battery.
 * - Charging voltage: readings while moving. Healthy alternator output is 13.2 - 15.0 V.
 *
 * The head unit measures at its own supply wire, typically 0.1 - 0.2 V below the battery
 * terminals, so the bands below are set slightly lower than textbook values.
 */
data class BatteryDay(
    val day: String,             // yyyy-MM-dd
    val resting: Float = -1f,    // lowest resting reading that day, -1 = none
    val chargeSum: Double = 0.0,
    val chargeN: Int = 0,
    val chargeMin: Float = -1f,
    val chargeMax: Float = -1f,
) {
    val chargeAvg: Float get() = if (chargeN > 0) (chargeSum / chargeN).toFloat() else -1f
}

object BatteryHealth {

    const val MAX_DAYS = 400
    const val CHARGE_LOW = 13.2f
    const val CHARGE_HIGH = 15.0f
    const val RESTING_MAX = 13.0f

    enum class Rest(val label: String) { GOOD("Good"), OK("OK"), WEAK("Weak"), POOR("Poor - get it tested") }

    fun restingBand(v: Float): Rest = when {
        v >= 12.45f -> Rest.GOOD
        v >= 12.25f -> Rest.OK
        v >= 12.0f -> Rest.WEAK
        else -> Rest.POOR
    }

    fun addResting(days: List<BatteryDay>, day: String, v: Float): List<BatteryDay> = upsert(days, day) {
        it.copy(resting = if (it.resting < 0f) v else minOf(it.resting, v))
    }

    fun addCharging(days: List<BatteryDay>, day: String, v: Float): List<BatteryDay> = upsert(days, day) {
        it.copy(
            chargeSum = it.chargeSum + v, chargeN = it.chargeN + 1,
            chargeMin = if (it.chargeMin < 0f) v else minOf(it.chargeMin, v),
            chargeMax = maxOf(it.chargeMax, v),
        )
    }

    private fun upsert(days: List<BatteryDay>, day: String, f: (BatteryDay) -> BatteryDay): List<BatteryDay> {
        val i = days.indexOfFirst { it.day == day }
        val out = days.toMutableList()
        if (i >= 0) out[i] = f(out[i]) else out += f(BatteryDay(day))
        return out.sortedBy { it.day }.takeLast(MAX_DAYS)
    }

    /**
     * Resting-voltage trend in volts per 30 days (least squares over the last 90 days), or
     * null with fewer than 5 readings spread over at least 10 days.
     */
    fun trendPer30Days(days: List<BatteryDay>): Double? {
        val pts = days.filter { it.resting > 0f }.takeLast(90).mapNotNull { d ->
            dayNumber(d.day)?.let { it.toDouble() to d.resting.toDouble() }
        }
        if (pts.size < 5) return null
        if (pts.last().first - pts.first().first < 10) return null
        val n = pts.size
        val mx = pts.sumOf { it.first } / n; val my = pts.sumOf { it.second } / n
        val sxx = pts.sumOf { (it.first - mx) * (it.first - mx) }
        if (sxx <= 0.0) return null
        val sxy = pts.sumOf { (it.first - mx) * (it.second - my) }
        return sxy / sxx * 30.0
    }

    fun trendLabel(perMonth: Double?): String = when {
        perMonth == null -> "Needs about two weeks of starts"
        perMonth <= -0.10 -> "Declining (%.2f V/month) - have the battery tested soon".format(perMonth)
        perMonth <= -0.04 -> "Slowly declining (%.2f V/month) - keep an eye on it".format(perMonth)
        else -> "Stable"
    }

    /** Days since 1970 for "yyyy-MM-dd", without time-zone surprises. */
    fun dayNumber(day: String): Long? = try {
        val p = day.split("-").map { it.toInt() }
        java.time.LocalDate.of(p[0], p[1], p[2]).toEpochDay()
    } catch (_: Exception) {
        null
    }

    fun encode(days: List<BatteryDay>): String = JSONArray().apply {
        days.forEach { d ->
            put(JSONObject().put("d", d.day).put("r", d.resting.toDouble()).put("cs", d.chargeSum)
                .put("cn", d.chargeN).put("lo", d.chargeMin.toDouble()).put("hi", d.chargeMax.toDouble()))
        }
    }.toString()

    fun decode(json: String?): List<BatteryDay> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val a = JSONArray(json)
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                BatteryDay(
                    o.optString("d"), o.optDouble("r", -1.0).toFloat(), o.optDouble("cs", 0.0),
                    o.optInt("cn"), o.optDouble("lo", -1.0).toFloat(), o.optDouble("hi", -1.0).toFloat()
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
