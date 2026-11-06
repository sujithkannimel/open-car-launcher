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

    /** Where the car is (for "where did I park"); speed in m/s. */
    data class Pos(val lat: Double, val lon: Double, val at: Long, val speed: Float)

    /** Extra car state for alerts, parking and diagnostics. */
    data class CarExtra(
        val appVersion: String = "",
        /** 0 none, 1 not charging, 2 overcharging (the panel's battery pill). */
        val chargeAlert: Int = 0,
        val pos: Pos? = null,
        /** Panel log and status lines for the phone's diagnostics screen. */
        val diag: String? = null,
        /** Art key of the cover the car already has, so the phone only resends a new one. */
        val npArtKey: String = "",
        /** A tap on the car's music controls, for the phone's player. */
        val cmd: Cmd? = null,
        /** Version of the camera list the car has, so the phone only sends a newer one. */
        val camVer: String = "",
    )

    /** Player command: play, pause, next, prev, seek (value = ms), queue (value = queue item id). */
    data class Cmd(val id: Long, val action: String, val value: Long = 0L)

    data class QueueItem(val id: Long, val title: String, val subtitle: String)

    /** What the phone's music app is playing. [artB64] is a small JPEG, sent only when the car lacks [artKey]. */
    data class NowPlaying(
        val pkg: String, val app: String, val title: String, val artist: String, val album: String,
        val playing: Boolean, val posMs: Long, val durMs: Long, val posAt: Long,
        val artKey: String, val artB64: String?, val queue: List<QueueItem>, val activeQueueId: Long,
    ) {
        /** Position now, running on from [posAt] while playing. */
        fun positionAt(now: Long): Long =
            if (!playing || posMs < 0) posMs else (posMs + (now - posAt)).let { if (durMs > 0) it.coerceAtMost(durMs) else it }
    }

    private fun npToJson(n: NowPlaying) = JSONObject()
        .put("pkg", n.pkg).put("app", n.app).put("ti", n.title).put("ar", n.artist).put("al", n.album)
        .put("pl", n.playing).put("pos", n.posMs).put("dur", n.durMs).put("pat", n.posAt)
        .put("ak", n.artKey).put("art", n.artB64 ?: JSONObject.NULL)
        .put("q", JSONArray(n.queue.map { JSONObject().put("id", it.id).put("t", it.title).put("s", it.subtitle) }))
        .put("aq", n.activeQueueId)

    private fun npFromJson(o: JSONObject) = NowPlaying(
        o.optString("pkg"), o.optString("app"), o.optString("ti"), o.optString("ar"), o.optString("al"),
        o.optBoolean("pl"), o.optLong("pos", -1), o.optLong("dur", -1), o.optLong("pat"),
        o.optString("ak"), if (o.isNull("art")) null else o.optString("art").takeIf { it.isNotEmpty() },
        o.optJSONArray("q")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) }
            .map { QueueItem(it.optLong("id"), it.optString("t"), it.optString("s")) } }.orEmpty(),
        o.optLong("aq", -1),
    )

    /** Car -> phone: the same backup JSON the cloud gets, plus the trip in progress. */
    fun carMessage(
        backupJson: String, live: TripRecord?, voltage: Float, now: Long, acked: List<String>,
        extra: CarExtra = CarExtra(),
    ): String = JSONObject()
        .put("k", "car").put("t", now)
        .put("backup", JSONObject(backupJson))
        .put("live", if (live == null) JSONObject.NULL else JSONArray(TripLog.encode(listOf(live))))
        .put("v", voltage.toDouble())
        .put("ack", JSONArray(acked))
        .put("app", extra.appVersion)
        .put("ca", extra.chargeAlert)
        .put("pos", extra.pos?.let { JSONObject().put("la", it.lat).put("lo", it.lon).put("t", it.at).put("sp", it.speed.toDouble()) } ?: JSONObject.NULL)
        .put("diag", extra.diag ?: JSONObject.NULL)
        .put("nak", extra.npArtKey)
        .put("cmd", extra.cmd?.let { JSONObject().put("id", it.id).put("a", it.action).put("v", it.value) } ?: JSONObject.NULL)
        .put("cv", extra.camVer)
        .toString()

    data class CarMsg(
        val time: Long, val backupJson: String, val live: TripRecord?, val voltage: Float, val acked: List<String>,
        val extra: CarExtra = CarExtra(),
    )

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
            CarExtra(
                o.optString("app"),
                o.optInt("ca", 0),
                o.optJSONObject("pos")?.let { Pos(it.getDouble("la"), it.getDouble("lo"), it.optLong("t"), it.optDouble("sp", 0.0).toFloat()) },
                if (o.isNull("diag")) null else o.optString("diag"),
                o.optString("nak"),
                o.optJSONObject("cmd")?.let { Cmd(it.getLong("id"), it.getString("a"), it.optLong("v")) },
                o.optString("cv"),
            ),
        )
    } catch (_: Exception) {
        null
    }

    /** A launcher APK the phone has ready for the car (integrity: size + SHA-256, in the sealed reply). */
    data class Offer(val version: String, val size: Long, val sha256: String)

    /** [adb]: the phone's ADB bridge is on - the car keeps a tunnel to its own adbd open. */
    /** [cams]: a newer camera list (Cameras.encodeSet) when the car's version differs. */
    /** [gps]: the phone can share its GPS while the car has no fix (setting on, permission granted). */
    data class PhoneMsg(val fills: List<InboxFill>, val offer: Offer?, val np: NowPlaying? = null, val adb: Boolean = false,
                        val cams: String? = null, val gps: Boolean = false)

    /** Phone -> car: fill-ups the car hasn't acknowledged yet, an update if the phone has one, what plays. */
    fun phoneMessage(fills: List<InboxFill>, now: Long, offer: Offer? = null, np: NowPlaying? = null, adb: Boolean = false,
                     cams: String? = null, gps: Boolean = false): String = JSONObject()
        .put("k", "phone").put("t", now)
        .put("fills", JSONArray(fills.map { JSONObject(it.toJson()) }))
        .put("offer", offer?.let { JSONObject().put("ver", it.version).put("size", it.size).put("sha", it.sha256) } ?: JSONObject.NULL)
        .put("np", np?.let { npToJson(it) } ?: JSONObject.NULL)
        .put("adb", adb)
        .put("cams", cams ?: JSONObject.NULL)
        .put("gps", gps)
        .toString()

    fun parsePhone(text: String, now: Long): PhoneMsg? = try {
        val o = JSONObject(text)
        if (o.optString("k") != "phone" || kotlin.math.abs(now - o.getLong("t")) > MAX_AGE_MS) null
        else PhoneMsg(
            o.optJSONArray("fills")?.let { a -> (0 until a.length()).mapNotNull { InboxFill.parse(a.getJSONObject(it).toString()) } }.orEmpty(),
            o.optJSONObject("offer")?.let { Offer(it.getString("ver"), it.getLong("size"), it.getString("sha")) },
            o.optJSONObject("np")?.let { npFromJson(it) },
            o.optBoolean("adb", false),
            if (o.isNull("cams")) null else o.optString("cams").ifEmpty { null },
            o.optBoolean("gps", false),
        )
    } catch (_: Exception) {
        null
    }

    /** Car -> phone on a second connection: "send me that APK". The phone streams the raw bytes back. */
    fun apkRequest(version: String, now: Long): String =
        JSONObject().put("k", "getapk").put("t", now).put("ver", version).toString()

    fun parseApkRequest(text: String, now: Long): String? = try {
        val o = JSONObject(text)
        if (o.optString("k") != "getapk" || kotlin.math.abs(now - o.getLong("t")) > MAX_AGE_MS) null
        else o.getString("ver")
    } catch (_: Exception) {
        null
    }

    /**
     * Car -> phone: a spare ADB tunnel. After this sealed line the car waits for one byte
     * ([TUNNEL_GO]); then it connects to its own adbd and both sockets carry raw ADB traffic.
     */
    fun tunnelRequest(now: Long): String = JSONObject().put("k", "adbtunnel").put("t", now).toString()

    fun isTunnelRequest(text: String, now: Long): Boolean = try {
        val o = JSONObject(text)
        o.optString("k") == "adbtunnel" && kotlin.math.abs(now - o.getLong("t")) <= MAX_AGE_MS
    } catch (_: Exception) {
        false
    }

    const val TUNNEL_GO = 0x47

    /**
     * Phone GPS for the car while its own receiver has no fix (a cold start can take minutes):
     * the car opens a connection with this request and the phone writes one sealed [PhoneFix]
     * line per fix (about one a second) until the car hangs up. Lines without a position keep
     * the connection alive while the phone itself is still searching.
     */
    fun gpsRequest(now: Long): String = JSONObject().put("k", "gps").put("t", now).toString()

    fun isGpsRequest(text: String, now: Long): Boolean = try {
        val o = JSONObject(text)
        o.optString("k") == "gps" && kotlin.math.abs(now - o.getLong("t")) <= MAX_AGE_MS
    } catch (_: Exception) {
        false
    }

    /** One phone fix: degrees, m/s, degrees (or -1), metres, wall-clock ms. */
    data class PhoneFix(val lat: Double, val lon: Double, val speed: Float, val bearing: Float, val accuracy: Float, val time: Long)

    fun phoneFix(f: PhoneFix?, now: Long): String = JSONObject().put("k", "fix").put("t", now).apply {
        if (f != null) put("la", f.lat).put("lo", f.lon).put("sp", f.speed.toDouble()).put("br", f.bearing.toDouble())
            .put("ac", f.accuracy.toDouble()).put("ft", f.time)
    }.toString()

    /** The fix in a phone line, or null for a keep-alive, a stale line or anything else. */
    fun parsePhoneFix(text: String, now: Long): PhoneFix? = try {
        val o = JSONObject(text)
        if (o.optString("k") != "fix" || kotlin.math.abs(now - o.getLong("t")) > MAX_AGE_MS || !o.has("la")) null
        else PhoneFix(o.getDouble("la"), o.getDouble("lo"), o.optDouble("sp", 0.0).toFloat(), o.optDouble("br", -1.0).toFloat(),
            o.optDouble("ac", 99.0).toFloat(), o.optLong("ft", now))
    } catch (_: Exception) {
        null
    }

    /** Lower-case hex SHA-256 of a file. */
    fun sha256(file: java.io.File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { inp ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = inp.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** backups/link.json content (the key file the phone fetches once). */
    fun linkFile(key: String): String = JSONObject().put("app", "car_launcher_link").put("key", key).toString()

    fun parseLinkFile(text: String): String? = try {
        JSONObject(text).takeIf { it.optString("app") == "car_launcher_link" }?.optString("key")?.takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        null
    }
}
