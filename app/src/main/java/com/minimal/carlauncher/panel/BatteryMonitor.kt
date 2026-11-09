package com.minimal.carlauncher.panel

import android.content.Context
import android.os.Handler
import android.os.SystemClock
import com.minimal.carlauncher.core.BackupStore
import com.minimal.carlauncher.core.BatteryHealth
import com.minimal.carlauncher.core.Prefs
import java.util.Locale

/**
 * Battery health from each voltage reading:
 * - the start window: from ignition until the engine first charges (at most 3 min), readings
 *   are collected (the MCU's own broadcasts, every 15-30 s) and split into the resting
 *   voltage and the cranking dip;
 * - while driving, the charging voltage is logged, time spent draining the battery (under
 *   12.4 V) is added up, and the smart-charging-aware [ChargeWatch] raises "not charging"
 *   (no charging for 10 min of driving) or "overcharging" - each logged once per trip.
 */
internal class BatteryMonitor(
    private val context: Context,
    private val handler: Handler,
    /** elapsedRealtime of the current ignition (0 = unknown). */
    private val ignitionAt: () -> Long,
    private val isMoving: () -> Boolean,
    /** The alert changed: re-style the battery pill. */
    private val onAlertChanged: () -> Unit,
) {

    /** 0 none, 1 not charging (low while driving), 2 overcharging. */
    var chargeAlert = 0
        private set

    private var windowOpen = false
    private var seenCharging = false
    private val startSamples = ArrayList<Float>()
    private val watch = com.minimal.carlauncher.core.ChargeWatch()
    /** The car stood long enough before this start for a true resting reading. */
    private var restTrusted = true

    /**
     * New ignition: open the start window. The MCU only answers voltage queries every 15-30 s,
     * so fast polling never caught the cranking dip - it just used battery-side CPU; readings
     * now come from its own broadcasts, and the window is closed after 3 minutes at most.
     */
    fun onIgnition(parkedMs: Long) {
        windowOpen = true
        seenCharging = false
        startSamples.clear()
        watch.reset()
        restTrusted = parkedMs >= BatteryHealth.REST_PARKED_MS
        handler.removeCallbacks(poll)
        handler.postDelayed(poll, 30_000L)
        // Ask the MCU three times right away: one answer before the engine charges gives the
        // resting voltage (its own broadcasts often come too late).
        for (d in longArrayOf(0L, 1_500L, 3_000L)) handler.postDelayed({
            try { context.sendBroadcast(android.content.Intent(PanelService.NWD_VOLT_QUERY_ACTION)) } catch (_: Exception) {}
        }, d)
    }

    fun stop() = handler.removeCallbacks(poll)

    private val poll = object : Runnable {
        override fun run() {
            if (!windowOpen) return
            if (SystemClock.elapsedRealtime() - ignitionAt() >= 180_000L) { closeWindow(); return }
            handler.postDelayed(this, 30_000L)
        }
    }

    /** Saves the resting voltage and, if one was caught, the cranking dip. */
    private fun closeWindow() {
        windowOpen = false
        handler.removeCallbacks(poll)
        val (rest, crank) = BatteryHealth.splitStart(startSamples)
        val day = BackupStore.today()
        var days = Prefs.batteryLog
        // After a short stop the battery still shows surface charge: that reading flatters it.
        if (restTrusted) rest?.let { days = BatteryHealth.addResting(days, day, it) }
        crank?.let { days = BatteryHealth.addCrank(days, day, it) }
        if (rest != null || crank != null) Prefs.batteryLog = days
        log(String.format(Locale.US, "start: %d readings, resting %s, cranking dip %s", startSamples.size,
            rest?.let { String.format(Locale.US, "%.2fV", it) } ?: "-",
            crank?.let { String.format(Locale.US, "%.1fV", it) } ?: "not caught") +
            (if (!restTrusted && rest != null) " (short stop: resting not counted)" else ""))
        startSamples.clear()
    }

    /** Records resting / charging voltage and keeps the charging alert up to date. */
    fun onReading(v: Float) {
        val day = BackupStore.today()
        val bh = BatteryHealth
        val ignition = ignitionAt()
        val sinceIgnition = SystemClock.elapsedRealtime() - ignition
        val moving = isMoving()
        if (v >= bh.CHARGE_LOW) seenCharging = true
        if (windowOpen && ignition > 0L && sinceIgnition < 180_000L && !seenCharging && v < bh.RESTING_MAX && !moving) {
            startSamples += v
        }
        if (windowOpen && (sinceIgnition >= 180_000L || seenCharging)) closeWindow()

        if (moving) {
            var days = bh.addCharging(Prefs.batteryLog, day, v)
            val step = watch.feed(SystemClock.elapsedRealtime(), v)
            if (step.drainSec > 0) days = bh.addDrain(days, day, step.drainSec)
            Prefs.batteryLog = days
            if (step.newlyRaised) log(String.format(Locale.US,
                if (step.alert == 1) "warning: not charging while driving (%.1f V, no charge for 10 min)" else "warning: overcharging (%.1f V)", v))
            if (step.alert != chargeAlert) { chargeAlert = step.alert; onAlertChanged() }
        } else {
            watch.pause()
            if (chargeAlert != 0) {
                // Only meaningful while driving: clear it when the car stops (it's logged once per trip anyway).
                chargeAlert = 0
                onAlertChanged()
            }
        }
    }

    private fun log(msg: String) = PanelService.log(context, msg)
}
