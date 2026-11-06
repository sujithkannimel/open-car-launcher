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
 * - while driving, the charging voltage is logged and a "not charging" / "overcharging"
 *   alert raised after a few readings agree.
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
    private var lowCount = 0
    private var highCount = 0

    /**
     * New ignition: open the start window. The MCU only answers voltage queries every 15-30 s,
     * so fast polling never caught the cranking dip - it just used battery-side CPU; readings
     * now come from its own broadcasts, and the window is closed after 3 minutes at most.
     */
    fun onIgnition() {
        windowOpen = true
        seenCharging = false
        startSamples.clear()
        handler.removeCallbacks(poll)
        handler.postDelayed(poll, 30_000L)
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
        rest?.let { days = BatteryHealth.addResting(days, day, it) }
        crank?.let { days = BatteryHealth.addCrank(days, day, it) }
        if (rest != null || crank != null) Prefs.batteryLog = days
        log(String.format(Locale.US, "start: %d readings, resting %s, cranking dip %s", startSamples.size,
            rest?.let { String.format(Locale.US, "%.2fV", it) } ?: "-",
            crank?.let { String.format(Locale.US, "%.1fV", it) } ?: "not caught"))
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
            Prefs.batteryLog = bh.addCharging(Prefs.batteryLog, day, v)
            lowCount = if (v < bh.CHARGE_LOW) lowCount + 1 else 0
            highCount = if (v > bh.CHARGE_HIGH) highCount + 1 else 0
            val alert = when { lowCount >= 3 -> 1; highCount >= 2 -> 2; else -> 0 }
            if (alert != chargeAlert) {
                chargeAlert = alert
                if (alert != 0) log(if (alert == 1) "warning: not charging while driving" else "warning: overcharging")
                onAlertChanged()
            }
        } else if (chargeAlert != 0) {
            // Only meaningful while driving: clear it when the car stops.
            chargeAlert = 0; lowCount = 0; highCount = 0
            onAlertChanged()
        }
    }

    private fun log(msg: String) = PanelService.log(context, msg)
}
