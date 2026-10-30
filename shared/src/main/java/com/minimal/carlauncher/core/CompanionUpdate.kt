package com.minimal.carlauncher.core

import org.json.JSONArray

/**
 * Finds a newer phone companion app in the launcher repo's releases. The companion APK is
 * named after its own version (car-companion-1.2.apk), so a launcher-only release doesn't
 * look like a phone update.
 */
object CompanionUpdate {

    data class Available(val version: String, val url: String, val tag: String)

    private val NAME = Regex("""^car-companion-v?(\d+(?:\.\d+)*)\.apk$""", RegexOption.IGNORE_CASE)

    /** Version in an asset name, or null if it isn't a companion APK. */
    fun versionOf(assetName: String): String? = NAME.find(assetName.trim())?.groupValues?.get(1)

    /** Negative if a < b, 0 if equal, positive if a > b ("1.10" > "1.9"). */
    fun compare(a: String, b: String): Int {
        val x = a.split('.').map { it.toIntOrNull() ?: 0 }
        val y = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d
        }
        return 0
    }

    /** The newest companion APK in a GitHub releases list that is newer than [current]. */
    fun newest(releasesJson: String, current: String): Available? = try {
        val arr = JSONArray(releasesJson)
        var best: Available? = null
        for (i in 0 until arr.length()) {
            val rel = arr.optJSONObject(i) ?: continue
            if (rel.optBoolean("draft", false)) continue
            val assets = rel.optJSONArray("assets") ?: continue
            for (j in 0 until assets.length()) {
                val a = assets.optJSONObject(j) ?: continue
                val v = versionOf(a.optString("name")) ?: continue
                val url = a.optString("browser_download_url").takeIf { it.startsWith("https://") } ?: continue
                if (compare(v, current) <= 0) continue
                if (best == null || compare(v, best.version) > 0) best = Available(v, url, rel.optString("tag_name"))
            }
        }
        best
    } catch (_: Exception) {
        null
    }
}
