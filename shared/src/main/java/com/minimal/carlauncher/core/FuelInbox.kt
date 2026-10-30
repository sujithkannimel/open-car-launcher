package com.minimal.carlauncher.core

import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max

/**
 * Fill-ups logged on the phone. The phone writes one small JSON file per fill-up into
 * backups/inbox/ in the private repo; the head unit merges them into its fuel log when it
 * is online, then deletes the files.
 *
 * The phone doesn't know the head unit's GPS odometer, so the odometer reading is required:
 * the fill is placed using the last odometer the car knows (typed at a fill-up or correction)
 * and the GPS distance since, which puts it at the right point even if it arrives days later.
 */
data class InboxFill(
    val id: String,
    val time: Long,
    val full: Boolean,
    val litres: Float,   // -1 = not entered (full fills only)
    val odo: Int,
) {
    fun toJson(): String = JSONObject()
        .put("app", "car_launcher_fill").put("id", id).put("t", time)
        .put("full", full).put("l", litres.toDouble()).put("o", odo)
        .toString()

    companion object {
        fun parse(text: String): InboxFill? = try {
            val o = JSONObject(text)
            if (o.optString("app") != "car_launcher_fill") null else InboxFill(
                o.getString("id"), o.optLong("t"), o.optBoolean("full", true),
                o.optDouble("l", -1.0).toFloat(), o.getInt("o"),
            ).takeIf { it.odo > 0 && (it.full || it.litres > 0f) }
        } catch (_: Exception) {
            null
        }
    }
}

object FuelInbox {

    /** Entries within this many km of an existing fill-up are treated as the same fill. */
    const val DUPLICATE_KM = 3

    /** GPS odometer at which the car was at [odo] km, or null if no odometer is known yet. */
    fun gpsAt(events: List<FuelEvent>, odo: Int, currentGps: Double): Double? {
        val ref = FuelEstimator.replay(events).odoRef ?: return null
        val g = ref.second + (odo - ref.first)
        return g.coerceIn(0.0, max(currentGps, 0.0))
    }

    /** True if [f] matches a fill-up already in the log (entered in the car too). */
    fun isDuplicate(events: List<FuelEvent>, f: InboxFill): Boolean =
        FuelEstimator.replay(events).rows.any { r ->
            r.event.kind == FuelEvent.KIND_FILL && r.event.full == f.full &&
                r.odoAt >= 0 && abs(r.odoAt - f.odo) <= DUPLICATE_KM
        }

    data class MergeResult(val events: List<FuelEvent>, val added: Int, val processedIds: List<String>)

    /**
     * Adds the inbox fill-ups to the event list at the right place. Every entry is reported
     * as processed (added, duplicate, or unplaceable) so the head unit can clear the inbox.
     */
    fun merge(events: List<FuelEvent>, fills: List<InboxFill>, currentGps: Double): MergeResult {
        var out = events
        var added = 0
        val done = ArrayList<String>()
        for (f in fills.sortedBy { it.odo }) {
            done += f.id
            if (!FuelEstimator.replay(out).state.configured) continue
            if (isDuplicate(out, f)) continue
            val gps = gpsAt(out, f.odo, currentGps) ?: currentGps
            val e = FuelEvent(
                FuelEvent.KIND_FILL, f.time, gps, odo = f.odo,
                litres = if (f.litres > 0f) f.litres else -1f, full = f.full,
            )
            // Keep the log in driving order: after every event at or before this point.
            val at = out.indexOfLast { it.gpsKm <= gps } + 1
            out = out.toMutableList().also { it.add(at, e) }
            added++
        }
        return MergeResult(out, added, done)
    }
}
