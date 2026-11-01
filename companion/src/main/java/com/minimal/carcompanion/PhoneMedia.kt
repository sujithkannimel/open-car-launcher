package com.minimal.carcompanion

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import android.util.Base64
import com.minimal.carlauncher.core.CarLink
import java.io.ByteArrayOutputStream

/**
 * Granting "Notification access" to this (empty) listener is what lets the app see other
 * apps' media sessions - YouTube Music's song, cover and queue - and control them. It reads
 * no notifications.
 */
class MediaListener : NotificationListenerService()

/** Reads the phone's music player for the car, and carries out the car's button presses. */
object PhoneMedia {

    private const val ART_PX = 192
    @Volatile private var lastCmdId = 0L

    fun hasAccess(c: Context): Boolean =
        (android.provider.Settings.Secure.getString(c.contentResolver, "enabled_notification_listeners") ?: "")
            .split(':').any { it.startsWith(c.packageName + "/") }

    private fun controller(c: Context): MediaController? {
        if (!hasAccess(c)) return null
        val sessions = try {
            c.getSystemService(MediaSessionManager::class.java)
                .getActiveSessions(ComponentName(c, MediaListener::class.java))
        } catch (_: SecurityException) { return null }
        val players = sessions.filter { it.packageName != c.packageName && it.metadata != null }
        return players.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: players.firstOrNull()
    }

    /** What plays now, with the cover only when the car doesn't already have it. */
    fun nowPlaying(c: Context, carArtKey: String): CarLink.NowPlaying? {
        val mc = controller(c) ?: return null
        val md = mc.metadata ?: return null
        val title = md.getString(MediaMetadata.METADATA_KEY_TITLE) ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: return null
        val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) ?: ""
        val album = md.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
        val art: Bitmap? = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART) ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val artKey = "$title|$artist|$album|${art?.generationId ?: 0}".hashCode().toString()
        val st = mc.playbackState
        val label = try {
            val pm = c.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(mc.packageName, 0)).toString()
        } catch (_: Exception) { mc.packageName }
        return CarLink.NowPlaying(
            pkg = mc.packageName, app = label, title = title, artist = artist, album = album,
            playing = st?.state == PlaybackState.STATE_PLAYING,
            posMs = st?.position ?: -1L,
            durMs = md.getLong(MediaMetadata.METADATA_KEY_DURATION).takeIf { it > 0 } ?: -1L,
            posAt = System.currentTimeMillis() - ((android.os.SystemClock.elapsedRealtime() - (st?.lastPositionUpdateTime ?: 0L))
                .takeIf { st != null && st.lastPositionUpdateTime > 0 } ?: 0L),
            artKey = artKey,
            artB64 = if (art != null && artKey != carArtKey) encode(art) else null,
            queue = mc.queue.orEmpty().take(12).map {
                CarLink.QueueItem(it.queueId, it.description.title?.toString().orEmpty(), it.description.subtitle?.toString().orEmpty())
            },
            activeQueueId = st?.activeQueueItemId ?: -1L,
        )
    }

    private fun encode(b: Bitmap): String? = try {
        val s = minOf(1f, ART_PX.toFloat() / maxOf(b.width, b.height))
        val small = if (s < 1f) Bitmap.createScaledBitmap(b, (b.width * s).toInt().coerceAtLeast(1), (b.height * s).toInt().coerceAtLeast(1), true) else b
        val out = ByteArrayOutputStream()
        small.compress(Bitmap.CompressFormat.JPEG, 80, out)
        Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    } catch (_: Exception) { null }

    /** Runs a car button press once (the car may resend it until it hears back). */
    fun run(c: Context, cmd: CarLink.Cmd) {
        if (cmd.id == lastCmdId) return
        lastCmdId = cmd.id
        val t = controller(c)?.transportControls ?: return
        when (cmd.action) {
            "play" -> t.play()
            "pause" -> t.pause()
            "next" -> t.skipToNext()
            "prev" -> t.skipToPrevious()
            "seek" -> t.seekTo(cmd.value)
            "queue" -> t.skipToQueueItem(cmd.value)
        }
    }
}
