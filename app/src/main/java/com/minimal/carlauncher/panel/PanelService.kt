package com.minimal.car.launcher.panel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.minimal.car.launcher.core.Prefs

/**
 * Backup broadcast receiver for ACC-on / boot / sleep-out intents.
 *
 * The primary restart mechanism is the HOME activity: Android restarts the launcher on every
 * ACC-on, and the launcher's `onResume` starts [PanelService]. This receiver is a belt-and-
 * suspenders fallback for edge cases where the launcher process survives sleep but the overlay
 * window was dropped.
 *
 * **NWD firmware caveat:** the firmware force-stops all user apps on ACC off and sets
 * `stopped=true`, which blocks ALL broadcasts. This receiver therefore only works after the
 * launcher (HOME) has already been restarted - clearing the stopped flag - and a subsequent
 * broadcast fires. It cannot fix the cold start on its own.
 */
class PanelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return

        PanelService.log(context, "broadcast: $action")

        if (!Prefs.panelEnabled) return
        if (!Settings.canDrawOverlays(context)) {
            PanelService.log(context, "overlay permission missing - ignoring broadcast")
            return
        }

        val svc = Intent(context, PanelService::class.java).apply {
            this.action = PanelService.ACTION_WAKE
        }
        try {
            context.startForegroundService(svc)
            PanelService.log(context, "panel service started by: $action")
        } catch (e: Exception) {
            PanelService.log(context, "panel start failed: $e")
        }
    }
}
