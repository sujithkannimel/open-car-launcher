package com.minimal.carlauncher.core

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale

/**
 * Cloud copies of the backup in a PRIVATE GitHub repo, through the contents API, using a
 * fine-grained token limited to that one repo. Refuses to upload to a public repo.
 * All calls block: run them off the main thread.
 */
object CloudBackup {

    private const val API = "https://api.github.com"
    private const val DIR = "backups"
    private const val PREFIX = "car_launcher_backup_"

    data class Result(val ok: Boolean, val message: String)

    private class Http(val code: Int, val body: String)

    private fun call(method: String, path: String, token: String, body: String? = null, raw: Boolean = false): Http {
        val c = (URL(API + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", if (raw) "application/vnd.github.raw" else "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "MinimalCarLauncher")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        try {
            if (body != null) c.outputStream.use { it.write(body.toByteArray()) }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            return Http(code, text)
        } finally {
            c.disconnect()
        }
    }

    private fun repoPath(repo: String) = "/repos/" + repo.trim().trim('/')

    private fun enc(name: String) = URLEncoder.encode(name, "UTF-8").replace("+", "%20")

    /** Checks the repo exists, is private, and the token can write to it. */
    fun test(repo: String, token: String): Result = try {
        if (!repo.contains('/')) Result(false, "Repo must look like owner/name")
        else {
            val r = call("GET", repoPath(repo), token)
            when (r.code) {
                200 -> {
                    val o = JSONObject(r.body)
                    val canPush = o.optJSONObject("permissions")?.optBoolean("push", false) ?: false
                    when {
                        !o.optBoolean("private", false) ->
                            Result(false, "This repo is PUBLIC. Use a private repo for backups.")
                        !canPush -> Result(false, "Token can read but not write. Give it Contents: Read and write.")
                        else -> Result(true, "Connected to private repo $repo")
                    }
                }
                401 -> Result(false, "Token not accepted (wrong, expired or revoked)")
                403 -> Result(false, "Token has no access to this repo")
                404 -> Result(false, "Repo not found, or the token is not allowed to see it")
                else -> Result(false, "GitHub replied ${r.code}")
            }
        }
    } catch (e: Exception) {
        Result(false, "No connection: ${e.javaClass.simpleName}")
    }

    /** Uploads [content] as backups/[name], replacing a file of the same name. */
    fun upload(repo: String, token: String, name: String, content: String): Result = try {
        val path = "${repoPath(repo)}/contents/$DIR/${enc(name)}"
        // Private-repo check before anything leaves the car.
        val meta = call("GET", repoPath(repo), token)
        if (meta.code == 200 && !JSONObject(meta.body).optBoolean("private", false)) {
            Result(false, "Refused: repo is public")
        } else {
            val existing = call("GET", path, token)
            val sha = if (existing.code == 200) JSONObject(existing.body).optString("sha").ifBlank { null } else null
            val body = JSONObject()
                .put("message", "Backup $name")
                .put("content", Base64.getEncoder().encodeToString(content.toByteArray(Charsets.UTF_8)))
            if (sha != null) body.put("sha", sha)
            val r = call("PUT", path, token, body.toString())
            when (r.code) {
                200, 201 -> Result(true, "Uploaded $name")
                401 -> Result(false, "Token not accepted")
                403, 404 -> Result(false, "No write access to $repo")
                409, 422 -> Result(false, "GitHub rejected the file (${r.code})")
                else -> Result(false, "GitHub replied ${r.code}")
            }
        }
    } catch (e: Exception) {
        Result(false, "No connection: ${e.javaClass.simpleName}")
    }

    /** Backup file names in the repo, newest first, or null if the list failed. */
    fun list(repo: String, token: String): List<String>? = try {
        val r = call("GET", "${repoPath(repo)}/contents/$DIR", token)
        when (r.code) {
            200 -> JSONArray(r.body).let { a ->
                (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("name") }
                    .filter { it.startsWith(PREFIX) }
                    .sortedDescending()
            }
            404 -> emptyList()
            else -> null
        }
    } catch (_: Exception) {
        null
    }

    fun download(repo: String, token: String, name: String): String? = try {
        val r = call("GET", "${repoPath(repo)}/contents/$DIR/${enc(name)}", token, raw = true)
        if (r.code == 200) r.body else null
    } catch (_: Exception) {
        null
    }

    /** The backup text to upload: plain, or encrypted when a passphrase is set. */
    fun payload(): String {
        val plain = Prefs.backupJson()
        val pass = Prefs.cloudPass
        return if (pass.isEmpty()) plain else BackupCrypto.encrypt(plain, pass)
    }

    fun dailyName(): String = PREFIX + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) + ".json"

    fun manualName(): String = PREFIX + SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(Date()) + ".json"

    const val INBOX = "inbox"

    /**
     * Merges fill-ups logged on the phone (JSON files in backups/inbox) into the fuel log and deletes
     * them from the repo. Returns how many were added. Background thread only.
     */
    fun processInbox(): Int {
        if (!Prefs.cloudConfigured) return 0
        val repo = Prefs.cloudRepo; val token = Prefs.cloudToken
        val list = try { call("GET", "${repoPath(repo)}/contents/$DIR/$INBOX", token) } catch (_: Exception) { return 0 }
        if (list.code != 200) return 0
        val files = try {
            JSONArray(list.body).let { a ->
                (0 until a.length()).mapNotNull { a.optJSONObject(it) }
                    .filter { it.optString("name").endsWith(".json") }
                    .map { it.optString("name") to it.optString("sha") }
            }
        } catch (_: Exception) { return 0 }
        if (files.isEmpty()) return 0
        val fills = ArrayList<Pair<InboxFill, Pair<String, String>>>()
        for ((name, sha) in files) {
            val r = try { call("GET", "${repoPath(repo)}/contents/$DIR/$INBOX/${enc(name)}", token, raw = true) } catch (_: Exception) { continue }
            if (r.code != 200) continue
            val f = InboxFill.parse(r.body)
            if (f == null) { delete(repo, token, "$DIR/$INBOX/$name", sha); continue }
            fills += f to (name to sha)
        }
        if (fills.isEmpty()) return 0
        val res = FuelInbox.merge(Prefs.fuelEvents, fills.map { it.first }, Prefs.gpsOdoKm)
        if (res.added > 0) Prefs.fuelEvents = res.events
        // Clear what was handled (added or duplicate) so it isn't merged twice.
        fills.filter { it.first.id in res.processedIds }.forEach { (_, ns) ->
            delete(repo, token, "$DIR/$INBOX/${ns.first}", ns.second)
        }
        return res.added
    }

    private fun delete(repo: String, token: String, path: String, sha: String) {
        try {
            val parts = path.split("/").joinToString("/") { enc(it) }
            call("DELETE", "${repoPath(repo)}/contents/$parts", token,
                JSONObject().put("message", "Merged into the car's fuel log").put("sha", sha).toString())
        } catch (_: Exception) {}
    }

    const val LATEST = "latest.json"

    /**
     * Keeps backups/latest.json fresh for the phone companion app: uploads when a trip was
     * saved or the fuel log changed, and otherwise at most hourly. Background thread only.
     * Returns true if it uploaded.
     */
    fun uploadLatestIfDue(): Boolean {
        if (!Prefs.cloudConfigured) return false
        val key = Prefs.latestKey()
        val now = System.currentTimeMillis()
        val changed = key != Prefs.cloudLatestKey
        if (!changed && now - Prefs.cloudLatestAt < 60 * 60_000L) return false
        val res = upload(Prefs.cloudRepo, Prefs.cloudToken, LATEST, payload())
        if (res.ok) { Prefs.cloudLatestKey = key; Prefs.cloudLatestAt = now }
        return res.ok
    }

    /** Daily upload; call from a background thread. Returns true if it ran (ok or not). */
    fun uploadIfDue(): Boolean {
        if (!Prefs.cloudConfigured) return false
        val day = BackupStore.today()
        if (Prefs.cloudLastDay == day) return false
        val res = upload(Prefs.cloudRepo, Prefs.cloudToken, dailyName(), payload())
        val time = SimpleDateFormat("d MMM HH:mm", Locale.US).format(Date())
        Prefs.cloudStatus = "${res.message} ($time)"
        if (res.ok) Prefs.cloudLastDay = day
        return true
    }
}
