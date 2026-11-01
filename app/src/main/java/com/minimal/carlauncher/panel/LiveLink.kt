package com.minimal.carlauncher.panel

import android.content.Context
import com.minimal.carlauncher.BuildConfig
import com.minimal.carlauncher.core.CarLink
import com.minimal.carlauncher.core.CompanionUpdate
import com.minimal.carlauncher.update.ApkInstaller
import java.io.File
import com.minimal.carlauncher.core.FuelInbox
import com.minimal.carlauncher.core.Prefs
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Car side of [CarLink]: every heartbeat, push the car's data straight to the phone app and
 * collect fill-ups logged on the phone. Works on home Wi-Fi and on the Android Auto link (the
 * car may connect to the phone there even though the phone may not connect to the car).
 *
 * The phone's address is not known up front, so the last good one is tried first and,
 * failing that, the car's own subnets are scanned for the phone's port at most every 2 min.
 */
object LiveLink {

    @Volatile private var busy = false
    private var lastScanAt = 0L
    /** Fill-up ids merged from the phone, reported back in the next message. */
    private val acked = ArrayList<String>()

    /** [update]: a launcher APK received from the phone (tag to file), ready to install. */
    class Result(val reached: String?, val added: Int, val update: Pair<String, File>? = null, val note: String? = null)

    /** Runs one exchange on a background thread; [done] is called on that thread. */
    fun tick(context: Context, extra: () -> CarLink.CarExtra, done: (Result) -> Unit) {
        if (busy || !Prefs.linkKeyUploaded) return
        busy = true
        val app = context.applicationContext
        Thread {
            try {
                // Carry the cover the car already has and any music button press for the phone.
                val cmd = com.minimal.carlauncher.core.PhoneMedia.pendingCmd
                done(exchangeOnce(app, extra().copy(npArtKey = com.minimal.carlauncher.core.PhoneMedia.artKey, cmd = cmd), cmd))
            } catch (_: Exception) { done(Result(null, 0)) } finally { busy = false }
        }.start()
    }

    private fun exchangeOnce(context: Context, extra: CarLink.CarExtra, cmd: CarLink.Cmd?): Result {
        val key = Prefs.linkKey
        val now = System.currentTimeMillis()
        val ack = synchronized(acked) { acked.toList() }
        val line = CarLink.seal(key, CarLink.carMessage(
            Prefs.backupJson(), Prefs.liveTrip(now), Prefs.lastVoltage, now, ack, extra))

        var reply: CarLink.PhoneMsg? = null
        var reached: String? = null
        Prefs.linkPhone.takeIf { it.isNotEmpty() }?.let { ip ->
            reply = talk(ip, key, line, 1_500)
            if (reply != null) reached = ip
        }
        if (reached == null && now - lastScanAt > SCAN_EVERY_MS) {
            lastScanAt = now
            scan(key, line)?.let { (ip, m) -> reached = ip; reply = m; Prefs.linkPhone = ip }
        }
        val got = reply ?: return Result(null, 0)
        val ip = reached ?: return Result(null, 0)
        AdbTunnel.update(got.adb, ip, key)
        synchronized(acked) { acked.removeAll(ack.toSet()) }
        com.minimal.carlauncher.core.PhoneMedia.onReply(got.np, cmd)

        var added = 0
        if (got.fills.isNotEmpty()) {
            val res = FuelInbox.merge(Prefs.fuelEvents, got.fills, Prefs.gpsOdoKm)
            if (res.added > 0) Prefs.fuelEvents = res.events
            synchronized(acked) { acked.addAll(res.processedIds) }
            added = res.added
        }
        var note: String? = null
        val update = got.offer?.let { o ->
            fetchUpdate(context, ip, key, o) { note = it }
        }
        return Result(ip, added, update, note)
    }

    /**
     * Downloads a newer launcher the phone is offering, over a second connection. The size and
     * SHA-256 came inside the sealed reply, so a corrupted or swapped file is thrown away; Android
     * also refuses an APK not signed with this app's key.
     */
    private fun fetchUpdate(context: Context, ip: String, key: String, offer: CarLink.Offer, note: (String) -> Unit): Pair<String, File>? {
        if (CompanionUpdate.compare(offer.version, BuildConfig.VERSION_NAME) <= 0) return null
        if (offer.size <= 0 || offer.size > MAX_APK_BYTES) { note("update ${offer.version}: bad size ${offer.size}"); return null }
        val tag = "v" + offer.version
        val dest = ApkInstaller.apkFileFor(context, tag)
        // Already here (e.g. received before a restart): offer it again rather than staying silent.
        if (dest.exists() && dest.length() == offer.size && CarLink.sha256(dest) == offer.sha256) return tag to dest
        // Don't hammer the phone: one try per minute.
        val now = System.currentTimeMillis()
        if (now - lastFetchAt < 60_000L) return null
        lastFetchAt = now
        note("update ${offer.version}: downloading ${offer.size / 1024} KB from the phone")
        val part = File(dest.path + ".part")
        try {
            Socket().use { s ->
                s.connect(InetSocketAddress(ip, CarLink.PORT), 2_000)
                s.soTimeout = 15_000
                s.getOutputStream().apply {
                    write((CarLink.seal(key, CarLink.apkRequest(offer.version, System.currentTimeMillis())) + "\n").toByteArray())
                    flush()
                }
                val inp = s.getInputStream()
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var left = offer.size
                    while (left > 0) {
                        val n = inp.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                        if (n < 0) break
                        out.write(buf, 0, n); left -= n
                    }
                }
            }
            if (part.length() != offer.size) { note("update ${offer.version}: got ${part.length()} of ${offer.size} bytes"); part.delete(); return null }
            if (CarLink.sha256(part) != offer.sha256) { note("update ${offer.version}: checksum mismatch"); part.delete(); return null }
            dest.delete()
            if (!part.renameTo(dest)) { note("update ${offer.version}: could not save"); part.delete(); return null }
            return tag to dest
        } catch (e: Exception) {
            note("update ${offer.version}: failed after ${part.length() / 1024} KB - ${e.javaClass.simpleName}: ${e.message}")
            part.delete()
            return null
        }
    }

    /** Sends [line], reads the phone's answer. Null if nothing (valid) answered. */
    private fun talk(ip: String, key: String, line: String, connectMs: Int): CarLink.PhoneMsg? = try {
        Socket().use { s ->
            s.connect(InetSocketAddress(ip, CarLink.PORT), connectMs)
            s.soTimeout = 3_000
            s.getOutputStream().apply { write((line + "\n").toByteArray()); flush() }
            val reply = BufferedReader(InputStreamReader(s.getInputStream())).readLine() ?: return null
            val text = CarLink.open(key, reply) ?: return null
            CarLink.parsePhone(text, System.currentTimeMillis())
        }
    } catch (_: Exception) {
        null
    }

    /** Tries every address on the car's own /24 networks in parallel; first valid answer wins. */
    private fun scan(key: String, line: String): Pair<String, CarLink.PhoneMsg>? {
        val targets = ArrayList<String>()
        try {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp || nif.isLoopback) continue
                for (a in nif.interfaceAddresses) {
                    val ip = a.address as? Inet4Address ?: continue
                    if (!ip.isSiteLocalAddress) continue
                    val b = ip.address
                    val own = b[3].toInt() and 0xFF
                    val base = "${b[0].toInt() and 0xFF}.${b[1].toInt() and 0xFF}.${b[2].toInt() and 0xFF}."
                    for (h in 1..254) if (h != own) targets += base + h
                }
            }
        } catch (_: Exception) {}
        if (targets.isEmpty()) return null
        val found = AtomicReference<Pair<String, CarLink.PhoneMsg>?>(null)
        val pool = Executors.newFixedThreadPool(32)
        for (ip in targets) pool.execute {
            if (found.get() == null) talk(ip, key, line, 400)?.let { found.compareAndSet(null, ip to it) }
        }
        pool.shutdown()
        pool.awaitTermination(15, TimeUnit.SECONDS)
        return found.get()
    }

    @Volatile private var lastFetchAt = 0L
    private const val SCAN_EVERY_MS = 2 * 60_000L
    private const val MAX_APK_BYTES = 80L * 1024 * 1024
}
