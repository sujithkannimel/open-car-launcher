package com.minimal.carlauncher.core

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A traffic camera. [id] is "osm:<node id>" for OpenStreetMap ones and "me:<time>" for those
 * the driver marked. [kind] is "ai" (MVD AI camera: helmet, seat belt, phone, signal),
 * "speed" or "user". [limit] is the posted km/h limit if known, else 0.
 */
data class Camera(val id: String, val lat: Double, val lon: Double, val kind: String, val limit: Int = 0)

/** A camera list with its version (fetch day + count + checksum), e.g. "2026-11-02/812/3fa1". */
data class CameraSet(val version: String, val fetchedAt: Long, val cams: List<Camera>)

/** The camera ahead and how far, for the warning. */
data class CameraAhead(val cam: Camera, val distanceM: Int)

object Cameras {

    /** Kerala, with a little margin (cameras just over the border included). */
    const val KERALA_BBOX = "8.17,74.85,12.80,77.45"

    /**
     * Overpass query: every speed camera and every surveillance camera in Kerala. Plain tag
     * matches only - name regexes over a whole state make the server take minutes - and the
     * traffic ones are picked out here, in [fromOverpass].
     */
    fun overpassQuery(bbox: String = KERALA_BBOX): String =
        "[out:json][timeout:180];(" +
            "node[highway=speed_camera]($bbox);" +
            "node[man_made=surveillance]($bbox);" +
            ");out body;"

    private val TRAFFIC_NAME = Regex("KMVD|Keltron|AI ?Cam", RegexOption.IGNORE_CASE)
    private val TRAFFIC_OPERATOR = Regex("MVD|Motor Vehicle|Keltron|Police", RegexOption.IGNORE_CASE)

    /** A speed camera, or a surveillance camera that is clearly traffic enforcement (not shop, ATM or home CCTV). */
    fun isTraffic(tags: JSONObject): Boolean {
        if (tags.optString("highway") == "speed_camera") return true
        if (tags.optString("man_made") != "surveillance") return false
        return TRAFFIC_NAME.containsMatchIn(tags.optString("name")) ||
            tags.optString("surveillance:zone") == "traffic" ||
            TRAFFIC_OPERATOR.containsMatchIn(tags.optString("operator"))
    }

    /** Parses an Overpass JSON reply into cameras: traffic ones only, kinds assigned, parking ones dropped, duplicates merged. */
    fun fromOverpass(json: String): List<Camera> {
        val els = JSONObject(json).getJSONArray("elements")
        val out = ArrayList<Camera>()
        for (i in 0 until els.length()) {
            val e = els.getJSONObject(i)
            if (e.optString("type") != "node" || !e.has("lat")) continue
            val tags = e.optJSONObject("tags") ?: JSONObject()
            if (!isTraffic(tags)) continue
            val name = tags.optString("name")
            if (name.contains("PVDS")) continue                       // parking enforcement: not for drivers on the move
            val speed = tags.optString("highway") == "speed_camera" || name.contains("SVDS")
            val limit = tags.optString("maxspeed").filter { it.isDigit() }.take(3).toIntOrNull() ?: 0
            out += Camera("osm:" + e.getLong("id"), e.getDouble("lat"), e.getDouble("lon"),
                if (speed) "speed" else "ai", limit.takeIf { it in 10..150 } ?: 0)
        }
        return dedupe(out)
    }

    /**
     * Merges cameras within [radiusM] of each other (the same spot mapped twice): keeps one,
     * preferring a speed camera and a known limit.
     */
    fun dedupe(cams: List<Camera>, radiusM: Double = 40.0): List<Camera> {
        val sorted = cams.sortedWith(compareByDescending<Camera> { it.kind == "speed" }.thenByDescending { it.limit }.thenBy { it.id })
        val kept = ArrayList<Camera>()
        for (c in sorted) if (kept.none { distanceM(it.lat, it.lon, c.lat, c.lon) < radiusM }) kept += c
        return kept.sortedBy { it.id }
    }

    fun versionOf(day: String, cams: List<Camera>): String {
        val sum = cams.joinToString("|") { "${it.id},${(it.lat * 1e5).roundToInt()},${(it.lon * 1e5).roundToInt()},${it.kind},${it.limit}" }.hashCode()
        return "$day/${cams.size}/" + Integer.toHexString(sum).takeLast(4)
    }

    /** A new list only replaces the old one if it isn't suspiciously small (a broken download). */
    fun saneReplacement(new: List<Camera>, old: List<Camera>): Boolean =
        new.size >= 20 && (old.isEmpty() || new.size * 2 >= old.size)

    /** What changed between two lists, for the "3 new, 1 removed" note. */
    fun diff(old: List<Camera>, new: List<Camera>): Pair<Int, Int> {
        val o = old.map { it.id }.toSet(); val n = new.map { it.id }.toSet()
        return (n - o).size to (o - n).size
    }

    // ------------------------------------------------------------------ ahead detection

    /**
     * The nearest camera ahead: within [rangeM] (400 m, 600 m above 80 km/h), within 35
     * degrees of the direction of travel, and only while moving faster than 15 km/h (GPS
     * heading is unreliable when crawling). Muted cameras are skipped - matched by place, so a
     * mute survives the camera being re-mapped with a new id.
     */
    fun ahead(cams: List<Camera>, lat: Double, lon: Double, headingDeg: Float, kmh: Float, muted: List<Camera> = emptyList()): CameraAhead? {
        if (kmh < 15f) return null
        val range = if (kmh > 80f) 600.0 else 400.0
        var best: CameraAhead? = null
        for (c in cams) {
            if (abs(c.lat - lat) > 0.01 || abs(c.lon - lon) > 0.01) continue   // ~1 km box first: cheap
            val d = distanceM(lat, lon, c.lat, c.lon)
            if (d > range || d < 8.0) continue
            if (muted.any { it.id == c.id || distanceM(it.lat, it.lon, c.lat, c.lon) < 40.0 }) continue
            val off = abs(angleDiff(bearing(lat, lon, c.lat, c.lon), headingDeg.toDouble()))
            if (off > 35.0) continue
            if (best == null || d < best.distanceM) best = CameraAhead(c, d.roundToInt())
        }
        return best
    }

    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1); val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * atan2(sqrt(a), sqrt(1 - a))
    }

    fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2); val dl = Math.toRadians(lon2 - lon1)
        val y = sin(dl) * cos(p2); val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    private fun angleDiff(a: Double, b: Double): Double = ((a - b + 540.0) % 360.0) - 180.0

    // ------------------------------------------------------------------ storage

    fun encode(cams: List<Camera>): String = JSONArray().apply {
        cams.forEach { put(JSONArray().put(it.id).put(it.lat).put(it.lon).put(it.kind).put(it.limit)) }
    }.toString()

    fun decode(json: String?): List<Camera> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val a = JSONArray(json)
            (0 until a.length()).mapNotNull { i ->
                val r = a.optJSONArray(i) ?: return@mapNotNull null
                Camera(r.getString(0), r.getDouble(1), r.getDouble(2), r.optString(3, "ai"), r.optInt(4, 0))
            }
        } catch (_: Exception) { emptyList() }
    }

    fun encodeSet(s: CameraSet): String = JSONObject().put("ver", s.version).put("at", s.fetchedAt)
        .put("cams", JSONArray(encode(s.cams))).toString()

    fun decodeSet(json: String?): CameraSet? = try {
        val o = JSONObject(json ?: return null)
        CameraSet(o.getString("ver"), o.optLong("at"), decode(o.getJSONArray("cams").toString()))
    } catch (_: Exception) { null }
}
