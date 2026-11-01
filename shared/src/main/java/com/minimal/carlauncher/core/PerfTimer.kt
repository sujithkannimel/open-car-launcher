package com.minimal.carlauncher.core

import org.json.JSONArray
import org.json.JSONObject

/** One timed run: [kind] is "0-60", "0-100" or "40-80"; [sec] its time. */
data class PerfRun(val kind: String, val sec: Float, val at: Long)

/**
 * Times acceleration runs from GPS speed, automatically - there is nothing to arm, so it never
 * invites a launch. A run from standstill starts when the car pulls away and is timed to 60
 * and 100 km/h; an overtaking run (40-80 km/h) starts whenever the car accelerates through 40.
 * Crossing times are interpolated between fixes, so 1 Hz GPS gives about +-0.3 s.
 *
 * A run is dropped if the car slows down, a fix is missing or stale, or it takes too long
 * (so normal gentle driving doesn't fill the list with 40-second "runs").
 */
class PerfTimer {

    private class Run(val from: Float, val targets: List<Float>, val startAt: Long, val maxSec: Float) {
        var peak = from
        val done = HashSet<Float>()
    }

    private var prevT = 0L
    private var prevV = -1f
    private var standing = false
    private val runs = ArrayList<Run>()

    /** Feeds one good fix ([kmh] from GPS). Returns the runs that just finished. */
    fun feed(t: Long, kmh: Float): List<PerfRun> {
        val out = ArrayList<PerfRun>()
        val pt = prevT; val pv = prevV
        prevT = t; prevV = kmh
        if (pv < 0f || t - pt > MAX_GAP_MS || t <= pt) { runs.clear(); standing = kmh < STILL_KMH; return out }

        // Start: pulling away from standstill, crossing 2 km/h.
        if (standing && kmh >= STILL_KMH) runs += Run(0f, listOf(60f, 100f), cross(pt, pv, t, kmh, STILL_KMH), 30f)
        standing = kmh < STILL_KMH
        // Start: accelerating up through 40.
        if (pv < 40f && kmh >= 40f && runs.none { it.from == 40f })
            runs += Run(40f, listOf(80f), cross(pt, pv, t, kmh, 40f), 20f)

        val it = runs.iterator()
        while (it.hasNext()) {
            val r = it.next()
            for (target in r.targets) if (target !in r.done && pv < target && kmh >= target) {
                val sec = (cross(pt, pv, t, kmh, target) - r.startAt) / 1000f
                r.done += target
                if (sec > 0.5f && sec <= r.maxSec) out += PerfRun(label(r.from, target), sec, t)
            }
            r.peak = maxOf(r.peak, kmh)
            val tooLong = (t - r.startAt) / 1000f > r.maxSec
            val slowed = kmh < r.peak - LIFT_KMH || (r.from > 0f && kmh < r.from - LIFT_KMH)
            if (r.done.size == r.targets.size || tooLong || slowed) it.remove()
        }
        return out
    }

    private fun cross(t0: Long, v0: Float, t1: Long, v1: Float, at: Float): Long =
        if (v1 <= v0) t1 else t0 + ((at - v0) / (v1 - v0) * (t1 - t0)).toLong()

    private fun label(from: Float, to: Float) = "${from.toInt()}-${to.toInt()}"

    companion object {
        const val STILL_KMH = 2f
        const val LIFT_KMH = 4f
        const val MAX_GAP_MS = 2_500L
        val KINDS = listOf("0-60", "0-100", "40-80")
        const val KEEP = 40

        fun best(runs: List<PerfRun>, kind: String): PerfRun? = runs.filter { it.kind == kind }.minByOrNull { it.sec }

        fun add(runs: List<PerfRun>, r: PerfRun): List<PerfRun> = (runs + r).takeLast(KEEP)

        fun encode(runs: List<PerfRun>): String = JSONArray().apply {
            runs.forEach { put(JSONObject().put("k", it.kind).put("s", it.sec.toDouble()).put("t", it.at)) }
        }.toString()

        fun decode(json: String?): List<PerfRun> {
            if (json.isNullOrBlank()) return emptyList()
            return try {
                val a = JSONArray(json)
                (0 until a.length()).mapNotNull { i ->
                    val o = a.optJSONObject(i) ?: return@mapNotNull null
                    PerfRun(o.optString("k"), o.optDouble("s").toFloat(), o.optLong("t"))
                }
            } catch (_: Exception) { emptyList() }
        }
    }
}
