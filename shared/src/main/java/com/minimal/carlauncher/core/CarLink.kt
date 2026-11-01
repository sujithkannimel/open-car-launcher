package com.minimal.carlauncher.core

import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Live link between the head unit and the phone app, without internet.
 *
 * During wireless Android Auto the phone keeps the car's Wi-Fi private to Android Auto, so the
 * phone cannot start a connection to the car; the car CAN connect to the phone (tested). So the
 * car connects to the phone's [PORT] every ~30 s and sends one line; the phone answers with one
 * line (fill-ups waiting on the phone); the car answers with one acknowledgement line.
 *
 * Every line is AES-256-GCM with a random key the car makes once and shares through the
 * private backup repo (backups/link.json). GCM also authenticates, so a device on the same
 * network can neither read nor fake a message. Messages carry a timestamp; stale ones are
 * refused.
 */
object CarLink {

    const val PORT = 47123
    const val LINK_FILE = "link.json"
    const val MAX_AGE_MS = 5 * 60_000L
    private const val TAG_BITS = 128
    private val rng = SecureRandom()

    fun newKey(): String = ByteArray(32).also { rng.nextBytes(it) }.let { Base64.getEncoder().encodeToString(it) }

    private fun keySpec(key: String) = SecretKeySpec(Base64.getDecoder().decode(key), "AES")

    /** One line: base64(iv | ciphertext+tag). */
    fun seal(key: String, plain: String): String {
        val iv = ByteArray(12).also { rng.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, keySpec(key), GCMParameterSpec(TAG_BITS, iv))
        val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(iv + ct)
    }

    /** The plain text, or null if the key is wrong or the line was tampered with. */
    fun open(key: String, line: String): String? = try {
        val all = Base64.getDecoder().decode(line.trim())
        if (all.size < 12 + 16) null else {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, keySpec(key), GCMParameterSpec(TAG_BITS, all.copyOfRange(0, 12)))
            String(c.doFinal(all, 12, all.size - 12), Charsets.UTF_8)
        }
    } catch (_: Exception) {
        null
    }

    // ------------------------------------------------------------------ messages

    /** Car -> phone: the same backup JSON the cloud gets, plus the trip in progress. */
    fun carMessage(backupJson: String, live: TripRecord?, voltage: Float, now: Long, acked: List<String>): String =
        JSONObject()
            .put("k", "car").put("t", now)
            .put("backup", JSONObject(backupJson))
            .put("live", if (live == null) JSONObject.NULL else JSONArray(TripLog.encode(listOf(live))))
            .put("v", voltage.toDouble())
            .put("ack", JSONArray(acked))
            .toString()

    data class CarMsg(val time: Long, val backupJson: String, val live: TripRecord?, val voltage: Float, val acked: List<String>)

    fun parseCar(text: String, now: Long): CarMsg? = try {
        val o = JSONObject(text)
        val t = o.getLong("t")
        if (o.optString("k") != "car" || kotlin.math.abs(now - t) > MAX_AGE_MS) null
        else CarMsg(
            t,
            o.getJSONObject("backup").toString(),
            o.optJSONArray("live")?.let { TripLog.decode(it.toString()).firstOrNull() },
            o.optDouble("v", -1.0).toFloat(),
            o.optJSONArray("ack")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
        )
    } catch (_: Exception) {
        null
    }

    /** Phone -> car: fill-ups logged on the phone that the car hasn't acknowledged yet. */
    fun phoneMessage(fills: List<InboxFill>, now: Long): String = JSONObject()
        .put("k", "phone").put("t", now)
        .put("fills", JSONArray(fills.map { JSONObject(it.toJson()) }))
        .toString()

    fun parsePhone(text: String, now: Long): List<InboxFill>? = try {
        val o = JSONObject(text)
        if (o.optString("k") != "phone" || kotlin.math.abs(now - o.getLong("t")) > MAX_AGE_MS) null
        else o.optJSONArray("fills")?.let { a -> (0 until a.length()).mapNotNull { InboxFill.parse(a.getJSONObject(it).toString()) } }.orEmpty()
    } catch (_: Exception) {
        null
    }

    /** backups/link.json content (the key file the phone fetches once). */
    fun linkFile(key: String): String = JSONObject().put("app", "car_launcher_link").put("key", key).toString()

    fun parseLinkFile(text: String): String? = try {
        JSONObject(text).takeIf { it.optString("app") == "car_launcher_link" }?.optString("key")?.takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        null
    }
}
