package com.minimal.carlauncher.panel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.SystemClock
import android.provider.Settings
import com.minimal.carlauncher.core.Prefs
import com.minimal.carlauncher.core.ZlinkStatus

/**
 * Keeps the current screen when ZLink jumps to the front by itself.
 *
 * - On connect: ZLink broadcasts "com.zjinnova.zlink" with status=ACTION_ZJ_PHONEFOUND, then
 *   starts its own MainActivity. The app that was on screen is remembered and, if ZLink takes
 *   the front within [STAY_WINDOW_MS], put back.
 * - After ignition: within [GUARD_MS] ZLink may come forward on a cold boot without that
 *   broadcast; then the last other app is restored (at most twice per ignition).
 * Outside those cases (floating button, Phone Link tile) ZLink opens normally.
 *
 * Also feeds every ZLink broadcast to [ZlinkStatus] for the home screen's cards.
 */
internal class ZlinkGuard(
    private val context: Context,
    private val handler: Handler,
    /** elapsedRealtime of the current ignition (0 = unknown). */
    private val ignitionAt: () -> Long,
    private val reversing: () -> Boolean,
) {

    private var receiver: BroadcastReceiver? = null
    private var stayPrevApp: String? = null
    private var stayUntilMs = 0L
    private var stayBounces = 0
    private var guardBounces = 0
    private var lastNonZlinkTop: String? = null
    private val ownPkg = context.packageName

    fun register() {
        if (receiver != null) return
        receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val status = intent.getStringExtra("status") ?: return
                if (ZlinkStatus.onBroadcast(status, intent.getStringExtra("phoneMode"))) log("zlink: $status")
                if (!Prefs.stayOnConnect) return
                if (status == "ACTION_ZJ_PHONEFOUND" || (status == "CONNECTED" && stayUntilMs == 0L)) {
                    val top = topApp(context)
                    stayPrevApp = top?.takeIf { it != ZLINK_PKG && it !in NEVER_RETURN_TO }
                        ?: stayPrevApp ?: ownPkg
                    stayUntilMs = SystemClock.uptimeMillis() + STAY_WINDOW_MS
                    stayBounces = 0
                    log("zlink $status -- will keep $stayPrevApp in front")
                    for (d in longArrayOf(700, 1500, 3000, 5000, 8000, STAY_WINDOW_MS)) {
                        handler.postDelayed({ bounceBack() }, d)
                    }
                }
            }
        }
        try { context.registerReceiver(receiver, IntentFilter(ZLINK_ACTION)) } catch (_: Exception) {}
    }

    fun unregister() {
        receiver?.let { try { context.unregisterReceiver(it) } catch (_: Exception) {} }
        receiver = null
    }

    /** New ignition: the takeover guard may bounce ZLink back again. */
    fun onIgnition() { guardBounces = 0 }

    /** Re-checks a few times right after the service (re)starts, e.g. after a cold boot. */
    fun scheduleChecks() {
        for (d in longArrayOf(1_500, 4_000, 8_000, 15_000)) handler.postDelayed({ check() }, d)
    }

    /**
     * Within GUARD_MS of ignition on, if ZLink is in front and the driver did not open it
     * from the Phone Link tile, go back. At most twice per ignition, so the floating button
     * still wins if the driver really wants Android Auto that early.
     */
    fun check() {
        val top = topApp(context) ?: return
        if (top != ZLINK_PKG) {
            if (top !in NEVER_RETURN_TO) lastNonZlinkTop = top
            return
        }
        val ignition = ignitionAt()
        if (!Prefs.stayOnConnect || reversing()) return
        if (ignition == 0L) return
        if (SystemClock.elapsedRealtime() - ignition > GUARD_MS) return
        if (guardBounces >= 2 || userOpenedRecently()) return
        if (SystemClock.uptimeMillis() < stayUntilMs) return   // broadcast path is handling it
        guardBounces++
        val prev = lastNonZlinkTop?.takeIf { it != ZLINK_PKG } ?: ownPkg
        log("zlink in front after ignition -- returning to $prev (guard $guardBounces)")
        returnTo(prev)
    }

    private fun userOpenedRecently() =
        System.currentTimeMillis() - Prefs.userOpenedZlinkAt < USER_OPEN_GRACE_MS

    private fun bounceBack() {
        if (SystemClock.uptimeMillis() > stayUntilMs) { stayUntilMs = 0L; return }
        if (topApp(context) != ZLINK_PKG || stayBounces >= 3) return
        if (userOpenedRecently() || reversing()) return
        val prev = stayPrevApp ?: ownPkg
        stayBounces++
        log("zlink jumped to front -- returning to $prev")
        returnTo(prev)
    }

    /** Brings [target] (or the launcher) back to the front. */
    private fun returnTo(target: String) {
        val prev = if (target in NEVER_RETURN_TO) ownPkg else target
        val home = Intent(context, Class.forName("com.minimal.carlauncher.ui.HomeActivity"))
        val intent = if (prev == ownPkg) home
        else context.packageManager.getLaunchIntentForPackage(prev) ?: home
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        } catch (e: Exception) {
            log("stay-on-screen failed: $e")
        }
    }

    private fun log(msg: String) = PanelService.log(context, msg)

    companion object {
        const val ZLINK_ACTION = "com.zjinnova.zlink"
        const val ZLINK_PKG = "com.zjinnova.zlink"

        /** Screens the stay-on-screen logic must never bring back (reverse camera). */
        private val NEVER_RETURN_TO = setOf("com.nwd.backcar")
        private const val STAY_WINDOW_MS = 10_000L
        private const val GUARD_MS = 3 * 60_000L
        private const val USER_OPEN_GRACE_MS = 20_000L

        /** The app in front, as the NWD firmware reports it. */
        fun topApp(context: Context): String? = try {
            Settings.System.getString(context.contentResolver, "key_top_app")
        } catch (_: Exception) { null }
    }
}
