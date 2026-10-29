package com.minimal.carlauncher.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Backup format: the launcher's settings file as typed JSON, minus anything that is a
 * location (map position, weather) or short-lived (update cache, the trip in progress).
 *
 * Pure functions over a Map so they can be unit tested; reading and writing the actual
 * SharedPreferences happens in [Prefs].
 */
object Backup {

    const val APP_ID = "car_launcher"
    const val VERSION = 1

    /** Never written to a backup: locations and short-lived values. */
    val EXCLUDED = setOf(
        Prefs.KEY_MAP_LAT, Prefs.KEY_MAP_LON, Prefs.KEY_MAP_ZOOM, Prefs.KEY_LAST_COURSE,
        Prefs.KEY_NAV_DESTINATION, Prefs.KEY_WEATHER_JSON, Prefs.KEY_WEATHER_AT,
        Prefs.KEY_LAST_CHECK_MS, Prefs.KEY_CACHED_TAG, Prefs.KEY_CACHED_URL, Prefs.KEY_PENDING_APK,
        Prefs.KEY_TRIP_START, Prefs.KEY_TRIP_LAST_SEEN, Prefs.KEY_TRIP_LAST_GPS,
        Prefs.KEY_CUR_TRIP_GPS, Prefs.KEY_CUR_TRIP_DRIVE, Prefs.KEY_CUR_TRIP_IDLE, Prefs.KEY_CUR_TRIP_MAX, Prefs.KEY_CUR_TRIP_HWY,
        Prefs.KEY_USER_OPENED_ZLINK, Prefs.KEY_LAST_AUTO_BACKUP,
        // Secrets and cloud bookkeeping: never written into a backup file.
        Prefs.KEY_CLOUD_REPO, Prefs.KEY_CLOUD_TOKEN, Prefs.KEY_CLOUD_PASS,
        Prefs.KEY_CLOUD_LAST_DAY, Prefs.KEY_CLOUD_STATUS,
    )

    fun toJson(values: Map<String, *>, createdMs: Long): String {
        val prefs = JSONObject()
        for ((k, v) in values) {
            if (k in EXCLUDED || v == null) continue
            val (t, jv) = when (v) {
                is String -> "s" to v
                is Int -> "i" to v
                is Long -> "l" to v
                is Float -> "f" to v.toDouble()
                is Boolean -> "b" to v
                is Set<*> -> "ss" to JSONArray(v.filterIsInstance<String>().sorted())
                else -> continue
            }
            prefs.put(k, JSONObject().put("t", t).put("v", jv))
        }
        return JSONObject()
            .put("app", APP_ID).put("v", VERSION).put("created", createdMs)
            .put("prefs", prefs)
            .toString(1)
    }

    /** Backup contents, or null if the text is not a launcher backup. */
    data class Parsed(val createdMs: Long, val values: Map<String, Any>)

    fun parse(json: String): Parsed? = try {
        val root = JSONObject(json)
        if (root.optString("app") != APP_ID) null else {
            val p = root.getJSONObject("prefs")
            val out = LinkedHashMap<String, Any>()
            for (k in p.keys()) {
                if (k in EXCLUDED) continue
                val o = p.getJSONObject(k)
                out[k] = when (o.getString("t")) {
                    "s" -> o.getString("v")
                    "i" -> o.getInt("v")
                    "l" -> o.getLong("v")
                    "f" -> o.getDouble("v").toFloat()
                    "b" -> o.getBoolean("v")
                    "ss" -> o.getJSONArray("v").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
                    else -> continue
                }
            }
            Parsed(root.optLong("created"), out)
        }
    } catch (_: Exception) {
        null
    }

    /**
     * GPS odometer after a restore. The fuel log's distances are relative to it, so it must
     * continue from the backup: after a reinstall the current value only counts km since
     * then, so the two are added; on the same install the current value is already larger.
     */
    fun mergedGpsKm(backupKm: Double, currentKm: Double): Double =
        if (currentKm < backupKm) backupKm + currentKm else currentKm
}
