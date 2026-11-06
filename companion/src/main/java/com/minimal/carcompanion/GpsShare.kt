package com.minimal.carcompanion

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.minimal.carlauncher.core.CarLink
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Lends the phone's GPS to the car while the car's own receiver has no fix yet. The car asks
 * (a sealed "gps" request on its own connection); this side switches GPS on, writes one sealed
 * fix per second and switches it off again as soon as the car hangs up, or after 10 minutes
 * (the car asks again if it still needs it). Positions only travel over the encrypted link.
 */
object GpsShare {

    private const val MAX_MS = 10 * 60_000L
    private fun sp(c: Context) = c.getSharedPreferences("gps_share", Context.MODE_PRIVATE)

    fun enabled(c: Context) = sp(c).getBoolean("on", false)
    fun setEnabled(c: Context, on: Boolean) = sp(c).edit().putBoolean("on", on).apply()

    fun hasFine(c: Context) = c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    /** "Allow all the time": needed because the link runs with the phone locked in a pocket. */
    fun hasBackground(c: Context) = Build.VERSION.SDK_INT < 29 ||
        c.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Told to the car in every reply, so it only asks when the answer can be yes. */
    fun canShare(c: Context) = enabled(c) && hasFine(c) && hasBackground(c)

    /** Serves one car request on [s] (blocks this thread until the car hangs up). */
    fun serve(c: Context, s: Socket, key: String) {
        if (!canShare(c)) return
        val lm = c.getSystemService(LocationManager::class.java) ?: return
        val q = LinkedBlockingQueue<Location>(16)
        val listener = LocationListener { q.offer(it) }
        val main = Handler(Looper.getMainLooper())
        main.post {
            try { lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f, listener, Looper.getMainLooper()) }
            catch (_: SecurityException) {} catch (_: Exception) {}
        }
        LinkStore.note(c, "gps: sharing with the car")
        var sent = 0
        try {
            s.soTimeout = 0
            s.tcpNoDelay = true
            val out = s.getOutputStream()
            try { lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) } catch (_: SecurityException) { null }
                ?.takeIf { System.currentTimeMillis() - it.time < 3_000L }?.let { q.offer(it) }
            val until = SystemClock.elapsedRealtime() + MAX_MS
            while (SystemClock.elapsedRealtime() < until) {
                val l = q.poll(3, TimeUnit.SECONDS)
                val f = l?.let {
                    CarLink.PhoneFix(it.latitude, it.longitude, if (it.hasSpeed()) it.speed else 0f,
                        if (it.hasBearing()) it.bearing else -1f, if (it.hasAccuracy()) it.accuracy else 99f, it.time)
                }
                out.write((CarLink.seal(key, CarLink.phoneFix(f, System.currentTimeMillis())) + "\n").toByteArray())
                out.flush()
                if (f != null) sent++
            }
        } catch (_: Exception) {
            // The car hung up: it has its own fix now, or the link dropped.
        } finally {
            main.post { try { lm.removeUpdates(listener) } catch (_: Exception) {} }
            LinkStore.note(c, "gps: stopped after $sent fixes")
        }
    }
}
