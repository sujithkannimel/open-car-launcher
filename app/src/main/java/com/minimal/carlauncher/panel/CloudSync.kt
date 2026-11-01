package com.minimal.carlauncher.panel

import android.content.Context
import android.os.Handler
import android.os.SystemClock
import com.minimal.carlauncher.core.BackupStore
import com.minimal.carlauncher.core.CloudBackup
import com.minimal.carlauncher.core.Prefs

/**
 * Background housekeeping on the heartbeat, each off the main thread and at most one at a time:
 * - a daily backup file in Download/CarLauncher (kept 7 days);
 * - the private GitHub repo: daily backup, latest.json, the live-link key, and fill-ups the
 *   phone left in the inbox (checked every 15 min). After a failure, retried every 15 min.
 */
internal class CloudSync(
    private val context: Context,
    private val handler: Handler,
    /** Fill-ups from the phone were merged (main thread). */
    private val onFillsAdded: () -> Unit,
) {

    @Volatile private var cloudBusy = false
    @Volatile private var lastInboxCheck = 0L
    private var lastCloudTry = 0L
    @Volatile private var backingUp = false

    fun tick() {
        maybeAutoBackup()
        maybeCloudUpload()
    }

    private fun maybeCloudUpload() {
        if (cloudBusy || !Prefs.cloudConfigured) return
        val dailyDone = Prefs.cloudLastDay == BackupStore.today()
        val latestDue = Prefs.latestKey() != Prefs.cloudLatestKey ||
            System.currentTimeMillis() - Prefs.cloudLatestAt > 60 * 60_000L
        val inboxDue = SystemClock.elapsedRealtime() - lastInboxCheck > 15 * 60_000L || lastInboxCheck == 0L
        if (dailyDone && !latestDue && !inboxDue && Prefs.linkKeyUploaded) return
        val now = SystemClock.elapsedRealtime()
        if (lastCloudTry != 0L && now - lastCloudTry < 15 * 60_000L) return
        lastCloudTry = now
        cloudBusy = true
        Thread {
            try {
                if (inboxDue) {
                    val added = CloudBackup.processInbox()
                    lastInboxCheck = SystemClock.elapsedRealtime()
                    if (added > 0) {
                        log("cloud: $added fill-up(s) from the phone added")
                        handler.post { onFillsAdded() }
                    }
                }
                if (CloudBackup.uploadLinkKeyIfNeeded()) log("cloud: live-link key shared")
                if (CloudBackup.uploadIfDue()) log("cloud: " + Prefs.cloudStatus)
                if (CloudBackup.uploadLatestIfDue()) log("cloud: latest.json updated")
            } finally {
                cloudBusy = false
            }
        }.start()
    }

    private fun maybeAutoBackup() {
        if (backingUp || Prefs.lastAutoBackupDay == BackupStore.today()) return
        backingUp = true
        Thread {
            try {
                BackupStore.autoBackupIfDue(context)
                log("auto backup: " + Prefs.lastAutoBackupDay)
            } finally {
                backingUp = false
            }
        }.start()
    }

    private fun log(msg: String) = PanelService.log(context, msg)
}
