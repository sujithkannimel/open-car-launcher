package com.minimal.carcompanion

import android.content.Context
import com.minimal.carlauncher.core.CameraSet
import com.minimal.carlauncher.core.Cameras
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps the traffic-camera list fresh for the car: once a week (or on "Update now") it asks
 * OpenStreetMap for every traffic camera in Kerala, and keeps the result if it looks sane.
 * The link service hands it to the car whenever the car reports an older version.
 * After a failed try it waits a day before trying again.
 */
object CameraUpdater {

    private const val WEEK_MS = 7L * 24 * 60 * 60_000L
    private const val RETRY_MS = 24L * 60 * 60_000L
    private val SERVERS = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
    )
    @Volatile private var busy = false

    private fun sp(c: Context) = c.getSharedPreferences("cameras", Context.MODE_PRIVATE)

    /** The list to send to the car, or null before the first successful fetch. */
    fun set(c: Context): CameraSet? = Cameras.decodeSet(sp(c).getString("set", null))
    fun setJson(c: Context): String? = sp(c).getString("set", null)
    fun lastTry(c: Context) = sp(c).getLong("tryAt", 0L)
    fun lastError(c: Context) = sp(c).getString("err", "") ?: ""

    /** Fetches if a week has passed (a day after a failure). Runs on its own thread. */
    fun maybeFetch(c: Context) {
        val now = System.currentTimeMillis()
        val have = set(c)
        val due = (have == null || now - have.fetchedAt > WEEK_MS) && now - lastTry(c) > RETRY_MS
        if (due) fetchNow(c, null)
    }

    /** Fetches now; [done] (any thread) gets a short result line. */
    fun fetchNow(c: Context, done: ((String) -> Unit)?) {
        if (busy) { done?.invoke("Already updating"); return }
        busy = true
        val app = c.applicationContext
        Thread {
            val msg = try { fetch(app) } catch (e: Exception) { "Failed: ${e.javaClass.simpleName}" } finally { busy = false }
            LinkStore.note(app, "cameras: $msg")
            done?.invoke(msg)
        }.start()
    }

    private fun fetch(c: Context): String {
        sp(c).edit().putLong("tryAt", System.currentTimeMillis()).apply()
        val body = "data=" + URLEncoder.encode(Cameras.overpassQuery(), "UTF-8")
        var lastErr = "no server answered"
        for (url in SERVERS) {
            val json = try { post(url, body) } catch (e: Exception) { lastErr = "${hostOf(url)}: ${e.javaClass.simpleName}"; null } ?: continue
            val cams = try { Cameras.fromOverpass(json) } catch (_: Exception) { lastErr = "${hostOf(url)}: unreadable reply"; continue }
            val old = set(c)?.cams.orEmpty()
            if (!Cameras.saneReplacement(cams, old)) { lastErr = "only ${cams.size} cameras returned - kept the old list"; continue }
            val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            val s = CameraSet(Cameras.versionOf(day, cams), System.currentTimeMillis(), cams)
            val (added, removed) = Cameras.diff(old, cams)
            sp(c).edit().putString("set", Cameras.encodeSet(s)).putString("err", "").apply()
            return "${cams.size} cameras ($added new, $removed removed)"
        }
        sp(c).edit().putString("err", lastErr).apply()
        return "Failed: $lastErr"
    }

    private fun post(url: String, body: String): String? {
        val con = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true
            connectTimeout = 20_000; readTimeout = 120_000
            // Overpass refuses anonymous clients.
            setRequestProperty("User-Agent", "CarCompanion (personal car app; traffic camera warnings)")
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        try {
            con.outputStream.use { it.write(body.toByteArray()) }
            if (con.responseCode != 200) throw java.io.IOException("HTTP ${con.responseCode}")
            return con.inputStream.bufferedReader().use { it.readText() }
        } finally { con.disconnect() }
    }

    private fun hostOf(url: String) = URL(url).host
}
