package com.minimal.carcompanion

import com.minimal.carlauncher.core.BackupCrypto
import com.minimal.carlauncher.core.BatteryDay
import com.minimal.carlauncher.core.BatteryHealth
import com.minimal.carlauncher.core.FuelEstimator
import com.minimal.carlauncher.core.FuelModel
import com.minimal.carlauncher.core.FuelInbox
import com.minimal.carlauncher.core.FuelModelResult
import com.minimal.carlauncher.core.InboxFill
import com.minimal.carlauncher.core.FuelReplay
import com.minimal.carlauncher.core.TripLog
import com.minimal.carlauncher.core.TripRecord
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Everything the app shows, decoded from one head-unit backup. */
class CarData(
    val createdMs: Long,
    val sourceName: String,
    val trips: List<TripRecord>,
    val fuel: FuelReplay,
    val model: FuelModelResult,
    val battery: List<BatteryDay>,
    val gpsKm: Double,
    val lastVoltage: Float,
    /** The raw fuel log, for merging pending phone entries. */
    val fuelEvents: List<com.minimal.carlauncher.core.FuelEvent> = emptyList(),
) {
    val tank get() = fuel.state.tankL
    val configured get() = fuel.state.configured
    val estOdometer get() = fuel.estimatedOdo(gpsKm)

    /** Fill-ups saved on the phone and not yet merged by the car. */
    var pending: List<InboxFill> = emptyList()

    /** Same data with the phone's pending fill-ups applied, so the phone shows them at once. */
    fun withPending(fills: List<InboxFill>, events: List<com.minimal.carlauncher.core.FuelEvent>): CarData {
        if (fills.isEmpty()) return this.also { it.pending = fills }
        val merged = FuelInbox.merge(events, fills, gpsKm).events
        val f = FuelEstimator.replay(merged)
        return CarData(createdMs, sourceName, trips, f, FuelModel.compute(f, trips, gpsKm, createdMs),
            battery, gpsKm, lastVoltage, merged).also { it.pending = fills }
    }

    companion object {
        /** Parses the launcher's backup JSON (the decrypted text). */
        fun parse(text: String, sourceName: String): CarData? = try {
            val root = JSONObject(text)
            if (root.optString("app") != "car_launcher") null else {
                val prefs = root.getJSONObject("prefs")
                fun str(k: String): String? = prefs.optJSONObject(k)?.optString("v")
                val gpsBits = prefs.optJSONObject("pref_gps_odo_km")?.optLong("v") ?: 0L
                val gps = java.lang.Double.longBitsToDouble(gpsBits)
                val trips = TripLog.decode(str("pref_trip_history"))
                val events = FuelEstimator.decode(str("pref_fuel_events"))
                val fuel = FuelEstimator.replay(events)
                val created = root.optLong("created")
                CarData(
                    createdMs = created,
                    sourceName = sourceName,
                    trips = trips,
                    fuel = fuel,
                    model = FuelModel.compute(fuel, trips, gps, created),
                    battery = BatteryHealth.decode(str("pref_battery_log")),
                    gpsKm = gps,
                    lastVoltage = prefs.optJSONObject("pref_last_voltage")?.optDouble("v", -1.0)?.toFloat() ?: -1f,
                    fuelEvents = events,
                )
            }
        } catch (_: Exception) {
            null
        }
    }
}

/** Reads the car's backups from the private GitHub repo with a read-only token. */
object CarCloud {

    private const val API = "https://api.github.com"
    private const val DIR = "backups"

    class Result(val data: CarData?, val rawText: String?, val error: String?)

    private class Http(val code: Int, val body: String)

    private fun get(path: String, token: String, raw: Boolean = false) = call("GET", path, token, null, raw)

    private fun call(method: String, path: String, token: String, body: String?, raw: Boolean = false): Http {
        val c = (URL(API + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", if (raw) "application/vnd.github.raw" else "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "CarCompanion")
            if (body != null) { doOutput = true; setRequestProperty("Content-Type", "application/json") }
        }
        try {
            if (body != null) c.outputStream.use { it.write(body.toByteArray()) }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            return Http(code, stream?.bufferedReader()?.use { it.readText() } ?: "")
        } finally {
            c.disconnect()
        }
    }

    private fun repo(r: String) = "/repos/" + r.trim().trim('/')

    /**
     * Newest data: latest.json when present (updated after every trip), compared with the
     * newest dated backups by their creation time. Blocking: call off the main thread.
     */
    /** Saves a fill-up for the car to pick up. Returns an error message, or null on success. */
    fun putFill(repoName: String, token: String, f: InboxFill): String? = try {
        val body = JSONObject()
            .put("message", "Fill-up from phone")
            .put("content", java.util.Base64.getEncoder().encodeToString(f.toJson().toByteArray()))
        val r = call("PUT", "${repo(repoName)}/contents/$DIR/inbox/fill_${f.id}.json", token, body.toString())
        when (r.code) {
            200, 201 -> null
            401 -> "Token not accepted"
            403, 404 -> "This token can't write to the repo. Give it Contents: Read and write."
            else -> "GitHub replied ${r.code}"
        }
    } catch (_: Exception) {
        "No connection"
    }

    /** Fill-ups saved on the phone that the car hasn't merged yet. */
    fun pendingFills(repoName: String, token: String): List<InboxFill> = try {
        val l = get("${repo(repoName)}/contents/$DIR/inbox", token)
        if (l.code != 200) emptyList() else {
            val names = JSONArray(l.body).let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("name") } }
                .filter { it.endsWith(".json") }
            names.mapNotNull { n ->
                val r = get("${repo(repoName)}/contents/$DIR/inbox/$n", token, raw = true)
                if (r.code == 200) InboxFill.parse(r.body) else null
            }
        }
    } catch (_: Exception) {
        emptyList()
    }

    fun fetch(repoName: String, token: String, passphrase: String): Result = try {
        val list = get("${repo(repoName)}/contents/$DIR", token)
        when (list.code) {
            200 -> {
                val names = JSONArray(list.body).let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("name") } }
                    .filter { it.endsWith(".json") }
                val candidates = (listOf("latest.json").filter { it in names } +
                    names.filter { it.startsWith("car_launcher_backup_") }.sortedDescending().take(3)).distinct()
                if (candidates.isEmpty()) Result(null, null, "No backups in the repo yet")
                else {
                    var best: Result? = null
                    var lastError: String? = null
                    for (n in candidates) {
                        val r = get("${repo(repoName)}/contents/$DIR/$n", token, raw = true)
                        if (r.code != 200) { lastError = "Download failed (${r.code})"; continue }
                        val text = if (BackupCrypto.isEncrypted(r.body)) {
                            if (passphrase.isEmpty()) { lastError = "Backups are encrypted: enter the passphrase"; continue }
                            BackupCrypto.decrypt(r.body, passphrase) ?: run { lastError = "Wrong passphrase"; null }
                        } else r.body
                        val d = text?.let { CarData.parse(it, n) } ?: continue
                        val cur = best?.data
                        if (cur == null || d.createdMs > cur.createdMs) best = Result(d, text, null)
                    }
                    best ?: Result(null, null, lastError ?: "Couldn't read the backups")
                }
            }
            401 -> Result(null, null, "Token not accepted (wrong, expired or revoked)")
            403 -> Result(null, null, "Token has no access to this repo")
            404 -> Result(null, null, "Repo or backups folder not found")
            else -> Result(null, null, "GitHub replied ${list.code}")
        }
    } catch (e: Exception) {
        Result(null, null, "No connection")
    }
}
