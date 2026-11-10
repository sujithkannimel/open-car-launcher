package com.minimal.carcompanion

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.minimal.carlauncher.core.CarLink
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Phone side of the ADB bridge: lets Termux's adb reach the head unit over the live link,
 * even while Android Auto owns the Wi-Fi. Termux connects to 127.0.0.1:[PORT] on this phone;
 * each connection is paired with a spare connection the car keeps open to us, and bytes are
 * copied both ways. Only listens on this phone's loopback, switches itself off after
 * [ON_MINUTES], and adbd on the car still checks Termux's ADB key.
 */
object AdbBridge {
    @Volatile private var lastNoCarNote = 0L

    const val PORT = 5556
    const val ON_MINUTES = 30

    private val spares = LinkedBlockingQueue<Socket>()
    @Volatile private var listener: ServerSocket? = null
    @Volatile var until = 0L
        private set
    private val main = Handler(Looper.getMainLooper())

    fun isOn(now: Long = System.currentTimeMillis()) = now < until

    fun turnOn(c: Context) {
        val app = c.applicationContext
        until = System.currentTimeMillis() + ON_MINUTES * 60_000L
        main.removeCallbacksAndMessages(null)
        main.postDelayed({ turnOff(app) }, ON_MINUTES * 60_000L)
        if (listener == null) Thread({ listen(app) }, "adb-bridge").start()
        LinkStore.note(app, "ADB bridge on for $ON_MINUTES min: adb connect 127.0.0.1:$PORT")
    }

    fun turnOff(c: Context) {
        if (until == 0L && listener == null) return
        until = 0L
        main.removeCallbacksAndMessages(null)
        runCatching { listener?.close() }
        listener = null
        while (true) { val s = spares.poll() ?: break; runCatching { s.close() } }
        LinkStore.note(c.applicationContext, "ADB bridge off")
    }

    /** A spare connection from the car (after its sealed tunnel request). False = not wanted. */
    fun offerSpare(s: Socket): Boolean {
        if (!isOn()) return false
        s.soTimeout = 0
        // Keep at most a few; drop the oldest.
        while (spares.size >= 4) runCatching { spares.poll()?.close() }
        spares.offer(s)
        return true
    }

    private fun listen(c: Context) {
        try {
            val ss = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), PORT)) }
            listener = ss
            while (!ss.isClosed) {
                val client = try { ss.accept() } catch (_: Exception) { break }
                Thread { serve(c, client) }.start()
            }
        } catch (e: Exception) {
            LinkStore.note(c, "ADB bridge: can't listen on $PORT (${e.javaClass.simpleName})")
            listener = null
        }
    }

    private fun serve(c: Context, client: Socket) {
        client.tcpNoDelay = true
        // Wait for a live spare from the car (it sends one within a link exchange, ~30 s at most).
        val deadline = System.currentTimeMillis() + 40_000L
        while (System.currentTimeMillis() < deadline && isOn()) {
            val car = spares.poll(2, TimeUnit.SECONDS) ?: continue
            try {
                car.getOutputStream().apply { write(CarLink.TUNNEL_GO); flush() }
            } catch (_: Exception) { runCatching { car.close() }; continue }   // went stale: next one
            car.tcpNoDelay = true
            pipe(client, car)
            return
        }
        // adb retries many times a second: once a minute is enough.
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastNoCarNote > 60_000L) {
            lastNoCarNote = now
            LinkStore.note(c, "ADB bridge: car not connected - is the car on and the live link up?")
        }
        runCatching { client.close() }
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
