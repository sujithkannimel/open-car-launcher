package com.minimal.carlauncher.panel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.minimal.carlauncher.core.Prefs

/**
 * Listens for boot, power-on, and platform-specific ACC-on broadcasts to wake the bottom panel.
 */
class PanelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        PanelService.log(context, "PanelReceiver: $action")
        if (!Prefs.panelEnabled) return
        val serviceIntent = Intent(context, PanelService::class.java).apply {
            this.action = PanelService.ACTION_WAKE
        }
        try {
            context.startForegroundService(serviceIntent)
        } catch (e: Exception) {
            PanelService.log(context, "PanelReceiver start failed: $e")
        }
    }
}
