package com.minimal.carlauncher.panel

import com.minimal.carlauncher.core.CarLink
import com.minimal.carlauncher.core.FuelInbox
import com.minimal.carlauncher.core.InboxFill
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

    class Result(val reached: String?, val added: Int)

    /** Runs one exchange on a background thread; [done] is called on that thread. */
    fun tick(done: (Result) -> Unit) {
        if (busy || !Prefs.linkKeyUploaded) return
        busy = true
        Thread {
            try { done(exchangeOnce()) } catch (_: Exception) { done(Result(null, 0)) } finally { busy = false }
        }.start()
    }

    private fun exchangeOnce(): Result {
        val key = Prefs.linkKey
        val now = System.currentTimeMillis()
        val ack = synchronized(acked) { acked.toList() }
        val line = CarLink.seal(key, CarLink.carMessage(
            Prefs.backupJson(), Prefs.liveTrip(now), Prefs.lastVoltage, now, ack))

        var fills: List<InboxFill>? = null
        var reached: String? = null
        Prefs.linkPhone.takeIf { it.isNotEmpty() }?.let { ip ->
            fills = talk(ip, key, line, 1_500)
            if (fills != null) reached = ip
        }
        if (reached == null && now - lastScanAt > SCAN_EVERY_MS) {
            lastScanAt = now
            scan(key, line)?.let { (ip, f) -> reached = ip; fills = f; Prefs.linkPhone = ip }
        }
        val got = fills ?: return Result(null, 0)
        synchronized(acked) { acked.removeAll(ack.toSet()) }
        if (got.isEmpty()) return Result(reached, 0)

        val res = FuelInbox.merge(Prefs.fuelEvents, got, Prefs.gpsOdoKm)
        if (res.added > 0) Prefs.fuelEvents = res.events
        synchronized(acked) { acked.addAll(res.processedIds) }
        return Result(reached, res.added)
    }

    /** Sends [line], reads the phone's answer. Null if nothing (valid) answered. */
    private fun talk(ip: String, key: String, line: String, connectMs: Int): List<InboxFill>? = try {
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
    private fun scan(key: String, line: String): Pair<String, List<InboxFill>>? {
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
        val found = AtomicReference<Pair<String, List<InboxFill>>?>(null)
        val pool = Executors.newFixedThreadPool(32)
        for (ip in targets) pool.execute {
            if (found.get() == null) talk(ip, key, line, 400)?.let { found.compareAndSet(null, ip to it) }
        }
        pool.shutdown()
        pool.awaitTermination(15, TimeUnit.SECONDS)
        return found.get()
    }

    private const val SCAN_EVERY_MS = 2 * 60_000L
}
