package com.minimal.carlauncher.core

import android.location.Location
import android.os.SystemClock
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket

/**
 * The phone's GPS, borrowed over the live link while the car's own receiver has no fix (a cold
 * start has taken over two minutes). The panel service starts the stream when the car has had
 * no fix for a few seconds and the phone says it can share; it stops once the car's own GPS
 * has held a fix for 10 s. Phone fixes feed the trip odometer, camera warnings and the home
 * screen ("GPS (PHONE)") - never while the car's own fix is fresh.
 */
object PhoneGps {

    /** Latest phone fix and when it arrived (elapsedRealtime). */
    @Volatile var last: Location? = null
        private set
    @Volatile private var lastAt = 0L

    /** When the car's own receiver last gave a good fix (elapsedRealtime). */
    @Volatile var ownFixAt = 0L

    /** The phone says it can share (from its last link reply), and where to reach it. */
    @Volatile var available = false
    @Volatile private var ip = ""
    @Volatile private var key = ""

    @Volatile private var socket: Socket? = null
    /** After a stream that brought nothing (phone not reachable), wait a minute before trying again. */
    @Volatile private var retryAfter = 0L
    val streaming: Boolean get() = socket != null

    fun ownFresh(now: Long = SystemClock.elapsedRealtime()) = ownFixAt > 0L && now - ownFixAt < 5_000L

    /** A phone fix from the last 4 s, only while the car has none of its own. */
    fun fresh(now: Long = SystemClock.elapsedRealtime()): Location? =
        last?.takeIf { now - lastAt < 4_000L && !ownFresh(now) }

    /** After each link exchange. */
    fun onLink(canShare: Boolean, phoneIp: String, linkKey: String) {
        available = canShare; ip = phoneIp; key = linkKey
        if (!canShare) stop()
    }

    /** Opens the stream (on its own thread) unless already open; each fix goes to [onFix] on that thread. */
    fun start(onFix: (Location) -> Unit, log: (String) -> Unit) {
        if (socket != null || !available || ip.isEmpty()) return
        if (SystemClock.elapsedRealtime() < retryAfter) return
        val s = Socket()
        socket = s
        Thread {
            var n = 0
            try {
                s.connect(InetSocketAddress(ip, CarLink.PORT), 3_000)
                s.soTimeout = 15_000
                s.getOutputStream().apply { write((CarLink.seal(key, CarLink.gpsRequest(System.currentTimeMillis())) + "\n").toByteArray()); flush() }
                log("phone gps: streaming")
                val r = BufferedReader(InputStreamReader(s.getInputStream()))
                while (true) {
                    val line = r.readLine() ?: break
                    val text = CarLink.open(key, line) ?: continue
                    val f = CarLink.parsePhoneFix(text, System.currentTimeMillis()) ?: continue
                    val l = Location("phone").apply {
                        latitude = f.lat; longitude = f.lon; time = f.time
                        elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                        speed = f.speed; accuracy = f.accuracy
                        if (f.bearing >= 0f) bearing = f.bearing
                    }
                    last = l; lastAt = SystemClock.elapsedRealtime(); n++
                    onFix(l)
                }
            } catch (_: Exception) {
            } finally {
                runCatching { s.close() }
                if (socket === s) socket = null
                if (n == 0) retryAfter = SystemClock.elapsedRealtime() + 60_000L
                else log("phone gps: stopped after $n fixes")
            }
        }.start()
    }

    fun stop() {
        socket?.let { runCatching { it.close() } }
        socket = null
    }
}
