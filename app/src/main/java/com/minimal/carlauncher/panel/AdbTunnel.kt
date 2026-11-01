package com.minimal.carlauncher.panel

import com.minimal.carlauncher.core.CarLink
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/**
 * Car side of the ADB bridge. The phone can't open a connection to the car over the Android
 * Auto Wi-Fi, so while the phone app's bridge is on (it says so in its sealed reply) the car
 * keeps a spare connection open to the phone. When Termux's adb connects on the phone, the
 * phone sends one byte down a spare; the car then connects to its own adbd (127.0.0.1:5555)
 * and copies bytes both ways. ADB's own key check still applies.
 */
object AdbTunnel {

    private const val SPARES = 2
    private const val ADBD_PORT = 5555

    @Volatile private var wanted = false
    @Volatile private var phoneIp = ""
    @Volatile private var key = ""
    private val waiting = AtomicInteger(0)
    @Volatile private var triedRoot = false

    /** Log sink (the panel's log). */
    @Volatile var log: ((String) -> Unit)? = null

    /** Called after every link exchange with what the phone said. */
    fun update(on: Boolean, ip: String, linkKey: String) {
        if (on && !wanted) log?.invoke("adb bridge: on (phone $ip)")
        if (!on && wanted) log?.invoke("adb bridge: off")
        wanted = on; phoneIp = ip; key = linkKey
        if (on) topUp()
    }

    private fun topUp() {
        while (wanted && waiting.get() < SPARES) {
            waiting.incrementAndGet()
            Thread { spare() }.start()
        }
    }

    /** One spare: wait at the phone until it's used (or dropped), then bridge to adbd. */
    private fun spare() {
        var phone: Socket? = null
        var used = false
        try {
            phone = Socket().apply { connect(InetSocketAddress(phoneIp, CarLink.PORT), 3_000); soTimeout = 0; tcpNoDelay = true }
            phone.getOutputStream().apply { write((CarLink.seal(key, CarLink.tunnelRequest(System.currentTimeMillis())) + "\n").toByteArray()); flush() }
            val go = phone.getInputStream().read()
            waiting.decrementAndGet(); used = true
            if (go != CarLink.TUNNEL_GO) { phone.close(); return }   // dropped by the phone: wait for the next exchange
            topUp()                                               // keep a spare ready for adb's next connection
            val adbd = connectAdbd() ?: run {
                log?.invoke("adb bridge: adbd isn't listening on port $ADBD_PORT - turn on Wi-Fi ADB on the head unit")
                phone.close(); return
            }
            pipe(phone, adbd)
        } catch (_: Exception) {
            runCatching { phone?.close() }
        } finally {
            if (!used) {
                waiting.decrementAndGet()
                // Phone gone or bridge switched off: retry later via the next link exchange.
            }
        }
    }

    private fun connectAdbd(): Socket? {
        fun tryOnce(): Socket? = try {
            Socket().apply { connect(InetSocketAddress("127.0.0.1", ADBD_PORT), 1_500); tcpNoDelay = true }
        } catch (_: Exception) { null }
        tryOnce()?.let { return it }
        // Rooted unit: ask adbd to listen on TCP once (a root prompt may appear the first time).
        if (!triedRoot) {
            triedRoot = true
            try {
                Runtime.getRuntime().exec(arrayOf("su", "-c", "setprop service.adb.tcp.port $ADBD_PORT; stop adbd; start adbd"))
                    .waitFor()
                log?.invoke("adb bridge: asked adbd to listen on TCP $ADBD_PORT")
                Thread.sleep(2_000)
            } catch (_: Exception) {}
            return tryOnce()
        }
        return null
    }

    private fun pipe(a: Socket, b: Socket) {
        fun copy(i: InputStream, o: OutputStream) = Thread {
            try {
                val buf = ByteArray(32 * 1024)
                while (true) { val n = i.read(buf); if (n < 0) break; o.write(buf, 0, n); o.flush() }
            } catch (_: Exception) {
            } finally { runCatching { a.close() }; runCatching { b.close() } }
        }.apply { start() }
        copy(a.getInputStream(), b.getOutputStream())
        copy(b.getInputStream(), a.getOutputStream())
    }
}
