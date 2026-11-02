package com.minimal.carlauncher.panel

import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import com.minimal.carlauncher.core.EcoScore
import com.minimal.carlauncher.core.FuelModel
import com.minimal.carlauncher.core.PerfLive
import com.minimal.carlauncher.core.PerfRun
import com.minimal.carlauncher.core.PerfTimer
import com.minimal.carlauncher.core.Prefs
import com.minimal.carlauncher.core.TripLog
import com.minimal.carlauncher.core.TripRecord
import java.util.Locale

/**
 * The GPS odometer and the trip in progress: distance between good fixes, drive / idle time,
 * top speed, highway km, hard accelerations and brakes (for the eco score) and timed
 * acceleration runs. Runs inside the panel's foreground service (type "location"), so it
 * keeps counting while Maps or ZLink are in front. In-memory counters are moved into Prefs
 * every ~10 s and when a trip ends.
 */
internal class TripTracker(
    private val context: Context,
    /** False only when the alternator clearly isn't charging (standing with just ACC on). */
    private val engineMaybeRunning: () -> Boolean,
    /** The car just came to a stop (push the parking spot to the phone). */
    private val onStopped: () -> Unit,
    /** Every fix that passes the accuracy check (camera warnings). */
    private val onGoodFix: (Location) -> Unit = {},
) {

    var lastFix: Location? = null
        private set

    private var locMgr: LocationManager? = null
    private var pendingKm = 0.0
    private var driveAcc = 0.0
    private var idleAcc = 0.0
    private var maxKmh = 0f
    private var hwyAcc = 0.0
    private var hardAccel = 0
    private var hardBrake = 0
    private var lastHardAt = 0L
    private var hardRef: Location? = null
    private var wasMoving = false
    private var lastFlush = 0L
    private val perf = PerfTimer()

    private val listener = object : LocationListener {
        override fun onLocationChanged(l: Location) = onFix(l)
        @Deprecated("framework")
        override fun onStatusChanged(p: String?, s: Int, e: android.os.Bundle?) = Unit
        override fun onProviderEnabled(p: String) = Unit
        override fun onProviderDisabled(p: String) = Unit
    }

    /** GPS odometer reading including distance not yet saved. */
    val odometerKm: Double get() = Prefs.gpsOdoKm + pendingKm

    /** Moving faster than ~15 km/h on a fix from the last 15 s. */
    fun movingNow(): Boolean {
        val f = lastFix ?: return false
        val age = System.currentTimeMillis() - f.time
        return age < 15_000L && f.hasSpeed() && f.speed >= 15f / 3.6f
    }

    fun start() {
        if (locMgr != null) return
        if (context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED) {
            log("gps odometer: no location permission")
            return
        }
        try {
            val m = context.getSystemService(LocationManager::class.java) ?: return
            m.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper())
            locMgr = m
        } catch (e: Exception) {
            log("gps odometer failed: $e")
        }
    }

    /** Stops GPS and saves everything counted so far. */
    fun stop() {
        try { locMgr?.removeUpdates(listener) } catch (_: Exception) {}
        locMgr = null
        if (pendingKm > 0) { Prefs.gpsOdoKm = Prefs.gpsOdoKm + pendingKm; pendingKm = 0.0 }
        flush(force = true)
    }

    /** Adds distance between good fixes; drops noisy, stale, parked and impossible jumps. */
    private fun onFix(l: Location) {
        if (!l.hasAccuracy() || l.accuracy > 30f) return
        val p = lastFix
        lastFix = l
        onGoodFix(l)
        if (p == null) return
        val dt = (l.time - p.time) / 1000.0
        if (dt <= 0.0 || dt > 60.0) return
        val d = p.distanceTo(l).toDouble()
        if (d / dt > 70.0) return                                  // > 250 km/h: a GPS jump
        // Trip timing: moving vs idling (engine on, standing still).
        val mps = if (l.hasSpeed()) l.speed.toDouble() else d / dt
        // Standing still only counts as idling while the engine runs (alternator charging);
        // with just the screen on (ACC) no fuel is used. No recent reading: count it.
        if (mps >= 1.5) driveAcc += dt else if (engineMaybeRunning()) idleAcc += dt
        // Hard events over ~2 s spans: 1 s GPS speed steps are too noisy on their own.
        val ref = hardRef
        if (ref == null || l.time - ref.time > 3_500L) hardRef = l
        else if (l.time - ref.time >= 1_800L) { countHardEvents(ref, l, (l.time - ref.time) / 1000.0); hardRef = l }
        if (l.hasSpeed() && l.accuracy <= 20f) perf.feed(l.time, l.speed * 3.6f).forEach { onPerfRun(it) }
        // Just stopped: push to the phone now, so its "parked here" spot is where the car stands.
        if (mps >= 2.0) wasMoving = true
        else if (wasMoving && mps < 0.5) { wasMoving = false; onStopped() }
        if (l.hasSpeed() && l.speed < 70f) maxKmh = maxOf(maxKmh, l.speed * 3.6f)
        flush(force = false)
        if (d < 5.0 || (l.hasSpeed() && l.speed < 1f && d < 30.0)) return   // parked jitter
        pendingKm += d / 1000.0
        if (mps * 3.6 >= FuelModel.HWY_KMH) hwyAcc += d / 1000.0
        if (pendingKm >= 0.2) {
            Prefs.gpsOdoKm = Prefs.gpsOdoKm + pendingKm
            pendingKm = 0.0
        }
    }

    /**
     * Hard acceleration / braking from the change in GPS speed between two good fixes close in
     * time. One event per 5 s at most, so one long hard stop counts once.
     */
    private fun countHardEvents(p: Location, l: Location, dt: Double) {
        if (!p.hasSpeed() || !l.hasSpeed() || dt < 0.8 || dt > 3.5) return
        if (l.accuracy > 15f || p.accuracy > 15f) return
        if (maxOf(p.speed, l.speed) < 3f) return                      // ~10 km/h: parking moves don't count
        val a = (l.speed - p.speed) / dt
        val now = SystemClock.elapsedRealtime()
        if (now - lastHardAt < 5_000L) return
        when {
            a >= EcoScore.HARD_ACCEL_MS2 -> { hardAccel++; lastHardAt = now }
            a <= EcoScore.HARD_BRAKE_MS2 -> { hardBrake++; lastHardAt = now }
        }
    }

    /** A timed run finished: keep it and show it (the home screen if open, else a toast). */
    private fun onPerfRun(r: PerfRun) {
        val before = PerfTimer.best(Prefs.perfRuns, r.kind)
        Prefs.perfRuns = PerfTimer.add(Prefs.perfRuns, r)
        val best = before == null || r.sec < before.sec
        log(String.format(Locale.US, "perf %s %.1fs%s", r.kind, r.sec, if (best) " best" else ""))
        if (!PerfLive.post(r, best))
            Toast.makeText(context, String.format(Locale.US, "%s km/h  %.1f s%s", r.kind, r.sec,
                if (best) "  — new best!" else ""), Toast.LENGTH_SHORT).show()
    }

    /** Moves the in-memory counters into Prefs (every ~10 s, or now). */
    fun flush(force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastFlush < 10_000L) return
        lastFlush = now
        val drive = driveAcc.toLong(); val idle = idleAcc.toLong()
        if (drive > 0) { Prefs.curTripDriveSec = Prefs.curTripDriveSec + drive; driveAcc -= drive }
        if (idle > 0) { Prefs.curTripIdleSec = Prefs.curTripIdleSec + idle; idleAcc -= idle }
        if (maxKmh > Prefs.curTripMaxKmh) Prefs.curTripMaxKmh = maxKmh
        if (hwyAcc > 0.0) { Prefs.curTripHwyKm = Prefs.curTripHwyKm + hwyAcc.toFloat(); hwyAcc = 0.0 }
        if (hardAccel > 0) { Prefs.curTripHardAccel = Prefs.curTripHardAccel + hardAccel; hardAccel = 0 }
        if (hardBrake > 0) { Prefs.curTripHardBrake = Prefs.curTripHardBrake + hardBrake; hardBrake = 0 }
    }

    /** Saves the trip that ended at the last heartbeat (car was off since then). */
    fun finishTrip() {
        val start = Prefs.tripStart
        val end = Prefs.tripLastSeen
        if (start <= 0L || end <= start) return
        val startGps = Prefs.curTripGpsStart
        val km = if (startGps <= 0.0 || Prefs.tripLastGps <= 0.0) 0.0
            else (Prefs.tripLastGps - startGps).coerceAtLeast(0.0)
        val rec = TripRecord(
            start, end, km, Prefs.curTripDriveSec, Prefs.curTripIdleSec, Prefs.curTripMaxKmh,
            Prefs.curTripHwyKm.toDouble().coerceAtMost(km), Prefs.curTripHardAccel, Prefs.curTripHardBrake
        )
        Prefs.tripHistory = TripLog.append(Prefs.tripHistory, rec)
        log(if (rec.isParked) String.format(Locale.US, "parked idling saved: %d min", rec.idleSec / 60)
            else String.format(Locale.US, "trip saved: %.1f km", km))
    }

    /** Starts a new trip at [now]: the previous one must already be finished. */
    fun beginTrip(now: Long) {
        Prefs.tripStart = now
        Prefs.curTripGpsStart = odometerKm
        Prefs.curTripDriveSec = 0L
        Prefs.curTripIdleSec = 0L
        Prefs.curTripMaxKmh = 0f
        Prefs.curTripHwyKm = 0f
        Prefs.curTripHardAccel = 0
        Prefs.curTripHardBrake = 0
        driveAcc = 0.0; idleAcc = 0.0; maxKmh = 0f; hwyAcc = 0.0
        hardAccel = 0; hardBrake = 0
    }

    /** "⏱ 1:05" since the trip started. */
    fun tripText(): String {
        val start = Prefs.tripStart
        if (start <= 0L) return "⏱ 0:00"
        val min = ((System.currentTimeMillis() - start) / 60_000L).coerceAtLeast(0L)
        return String.format(Locale.US, "⏱ %d:%02d", min / 60, min % 60)
    }

    private fun log(msg: String) = PanelService.log(context, msg)
}
