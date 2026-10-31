package com.minimal.carlauncher.core

import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/** One finished trip (ignition on to off). */
data class TripRecord(
    val start: Long,          // wall-clock ms
    val end: Long,
    val km: Double,
    val driveSec: Long,       // moving
    val idleSec: Long,        // engine on, standing still
    val maxKmh: Float,
    /** km driven at highway speed (>= 55 km/h); -1 = not recorded (older trips). */
    val hwyKm: Double = -1.0,
) {
    val durationSec: Long get() = ((end - start) / 1000L).coerceAtLeast(0L)

    /**
     * Engine on but the car never really moved (warming up, waiting, working on the head
     * unit in the driveway). Kept so its idling and fuel are not lost.
     */
    val isParked: Boolean get() = km < TripLog.MIN_KM && driveSec < TripLog.MIN_DRIVE_SEC

    /** Average speed while moving, or 0. */
    val avgKmh: Float get() = if (driveSec > 30) (km / (driveSec / 3600.0)).toFloat() else 0f
}

data class TripTotals(val km: Double, val driveSec: Long, val trips: Int)

object TripLog {

    const val MAX_TRIPS = 200
    const val MIN_KM = 0.3
    const val MIN_DRIVE_SEC = 120L
    /** Engine-on idling that counts as a "parked idling" session when the car didn't move. */
    const val PARKED_MIN_IDLE_SEC = 180L

    /** Worth keeping: moved a little, drove a couple of minutes, or idled 3+ minutes parked. */
    fun worthKeeping(t: TripRecord) =
        t.km >= MIN_KM || t.driveSec >= MIN_DRIVE_SEC || t.idleSec >= PARKED_MIN_IDLE_SEC

    fun append(list: List<TripRecord>, t: TripRecord): List<TripRecord> =
        if (!worthKeeping(t)) list else (list + t).takeLast(MAX_TRIPS)

    /** Totals of real trips; parked idling sessions add no km and are not counted as trips. */
    fun totals(list: List<TripRecord>, fromMs: Long): TripTotals {
        val sel = list.filter { it.start >= fromMs && !it.isParked }
        return TripTotals(sel.sumOf { it.km }, sel.sumOf { it.driveSec }, sel.size)
    }

    /** Engine idling (seconds) from [fromMs], trips and parked sessions together. */
    fun idleSince(list: List<TripRecord>, fromMs: Long): Long =
        list.filter { it.start >= fromMs }.sumOf { it.idleSec }

    fun startOfToday(now: Long): Long = Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** Monday 00:00 of this week. */
    fun startOfWeek(now: Long): Long = Calendar.getInstance().apply {
        timeInMillis = startOfToday(now)
        val dow = get(Calendar.DAY_OF_WEEK)               // Sunday = 1
        add(Calendar.DAY_OF_MONTH, -((dow + 5) % 7))
    }.timeInMillis

    fun startOfMonth(now: Long): Long = Calendar.getInstance().apply {
        timeInMillis = startOfToday(now)
        set(Calendar.DAY_OF_MONTH, 1)
    }.timeInMillis

    fun encode(list: List<TripRecord>): String = JSONArray().apply {
        list.forEach { t ->
            put(JSONObject().apply {
                put("s", t.start); put("e", t.end); put("k", t.km)
                put("d", t.driveSec); put("i", t.idleSec); put("m", t.maxKmh.toDouble()); put("h", t.hwyKm)
            })
        }
    }.toString()

    fun decode(json: String?): List<TripRecord> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                TripRecord(
                    o.optLong("s"), o.optLong("e"), o.optDouble("k", 0.0),
                    o.optLong("d"), o.optLong("i"), o.optDouble("m", 0.0).toFloat(),
                    o.optDouble("h", -1.0)
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
