package com.minimal.carlauncher.ui

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.minimal.carlauncher.R
import com.minimal.carlauncher.core.Prefs
import com.minimal.carlauncher.core.RadioFrequency
import com.minimal.carlauncher.core.ZlinkStatus
import com.minimal.carlauncher.databinding.ActivityHomeBinding
import com.minimal.carlauncher.radio.MediaSessionListener
import com.minimal.carlauncher.ui.view.NeonIcons
import com.minimal.carlauncher.util.IntentUtil

/**
 * Makes two of the quick cards live:
 *
 * - Music: what is playing. A player on the head unit (ArchiveTune, YouTube Music...) shares
 *   title, artist and cover through its media session. Android Auto through ZLink shares only
 *   "audio on / off" (MAIN_AUDIO_START / STOP), so it shows "Android Auto · Playing / Paused".
 *   Previous / play-pause / next go to that player.
 * - Phone Link: ZLink's connection state.
 *
 * Reading other apps' media sessions uses the notification access already granted for the
 * radio card; without it only the Android Auto state is shown.
 */
class LiveCardsController(
    private val activity: AppCompatActivity,
    private val binding: ActivityHomeBinding,
    private val cards: QuickCardsController,
) {
    private val sessionManager = activity.getSystemService(MediaSessionManager::class.java)
    private val audio = activity.getSystemService(AudioManager::class.java)
    private val listenerComponent = ComponentName(activity, MediaSessionListener::class.java)
    private val main = Handler(Looper.getMainLooper())

    private var listening = false
    private var watched: List<MediaController> = emptyList()
    /** The player the card shows, or null for Android Auto / idle. */
    private var current: MediaController? = null

    private val zlinkListener: () -> Unit = { render() }

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = render()
        override fun onPlaybackStateChanged(state: PlaybackState?) = render()
        override fun onSessionDestroyed() = refreshSessions()
    }

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { refreshSessions(it.orEmpty()) }

    fun bind() {
        NeonIcons.apply(binding.iconProjection, R.drawable.ic_phone_link)
        NeonIcons.apply(binding.iconNav, R.drawable.ic_navigation)
        NeonIcons.apply(binding.iconMusic, R.drawable.ic_music)

        binding.btnMusicPrev.setOnClickListener { transport(KeyEvent.KEYCODE_MEDIA_PREVIOUS) }
        binding.btnMusicPlay.setOnClickListener { transport(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) }
        binding.btnMusicNext.setOnClickListener { transport(KeyEvent.KEYCODE_MEDIA_NEXT) }

        // While something plays, tapping the card opens that player instead of the stored app.
        cards.musicTap = {
            val c = current
            when {
                c != null -> IntentUtil.launchPackage(activity, c.packageName)
                aaActive() -> { cards.openProjection(); true }
                else -> false
            }
        }
    }

    fun start() {
        ZlinkStatus.addListener(zlinkListener)
        if (hasAccess() && sessionManager != null && !listening) {
            try {
                sessionManager.addOnActiveSessionsChangedListener(sessionsListener, listenerComponent, main)
                listening = true
            } catch (_: SecurityException) {}
        }
        refreshSessions()
    }

    fun stop() {
        ZlinkStatus.removeListener(zlinkListener)
        if (listening) {
            runCatching { sessionManager?.removeOnActiveSessionsChangedListener(sessionsListener) }
            listening = false
        }
        watched.forEach { runCatching { it.unregisterCallback(callback) } }
        watched = emptyList()
        binding.eqMusic.playing = false
    }

    // ------------------------------------------------------------------ sessions

    private fun hasAccess() =
        NotificationManagerCompat.getEnabledListenerPackages(activity).contains(activity.packageName)

    private fun refreshSessions(list: List<MediaController>? = null) {
        val sessions = list ?: if (hasAccess()) {
            try { sessionManager?.getActiveSessions(listenerComponent).orEmpty() } catch (_: SecurityException) { emptyList() }
        } else emptyList()
        val players = sessions.filter { isMusicPlayer(it.packageName) }
        watched.forEach { runCatching { it.unregisterCallback(callback) } }
        players.forEach { it.registerCallback(callback, main) }
        watched = players
        render()
    }

    /** Players worth showing: not us, not the tuner, not the phone-call service. */
    private fun isMusicPlayer(pkg: String): Boolean {
        if (pkg == activity.packageName || pkg == "com.android.server.telecom") return false
        if (pkg == Prefs.radioPackage || RadioFrequency.looksLikeRadioPackage(pkg)) return false
        return true
    }

    private fun isZlink(c: MediaController) = c.packageName == ZLINK_PKG

    /** Android Auto audio: ZLink says so, or its media session is up (it only exists while playing). */
    private fun aaPlaying() = ZlinkStatus.audio || watched.any { isZlink(it) }

    private fun aaActive() = aaPlaying() || ZlinkStatus.link == ZlinkStatus.Link.CONNECTED

    private fun playing(c: MediaController) = c.playbackState?.state == PlaybackState.STATE_PLAYING

    private fun titleOf(c: MediaController): String? = c.metadata?.let {
        it.getString(MediaMetadata.METADATA_KEY_TITLE) ?: it.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
    }?.takeIf { it.isNotBlank() }

    // ------------------------------------------------------------------ render

    private fun render() {
        if (activity.isFinishing) return

        val apps = watched.filter { !isZlink(it) && titleOf(it) != null }
        val playingApp = apps.firstOrNull { playing(it) }
        val pausedApp = apps.firstOrNull()
        current = when {
            playingApp != null -> playingApp
            aaPlaying() -> null
            pausedApp != null -> pausedApp
            else -> null
        }
        val c = current
        when {
            c != null -> showPlayer(c)
            aaActive() -> showAndroidAuto(aaPlaying())
            else -> showIdle()
        }
        renderPhoneLink()
    }

    private fun showPlayer(c: MediaController) {
        val md = c.metadata
        val isPlaying = playing(c)
        binding.titleMusic.text = titleOf(c)
        binding.subtitleMusic.text = md?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: appLabel(c.packageName)
        val art: Bitmap? = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        if (art != null) {
            binding.coverMusic.setImageBitmap(art)
            binding.coverMusic.visibility = View.VISIBLE
            binding.iconMusic.visibility = View.GONE
        } else {
            binding.coverMusic.visibility = View.GONE
            binding.iconMusic.visibility = View.VISIBLE
        }
        binding.eqMusic.playing = isPlaying
        showControls(isPlaying)
    }

    private fun showAndroidAuto(isPlaying: Boolean) {
        binding.titleMusic.text = ZlinkStatus.modeLabel()
        binding.subtitleMusic.text = activity.getString(if (isPlaying) R.string.music_playing else R.string.music_paused)
        binding.iconMusic.visibility = View.VISIBLE
        binding.coverMusic.visibility = View.GONE
        binding.eqMusic.playing = isPlaying
        showControls(isPlaying)
    }

    private fun showIdle() {
        binding.titleMusic.setText(R.string.card_music)
        binding.iconMusic.visibility = View.VISIBLE
        binding.coverMusic.visibility = View.GONE
        binding.eqMusic.playing = false
        binding.musicControls.visibility = View.GONE
        cards.refreshMusicLabel()
    }

    private fun showControls(isPlaying: Boolean) {
        binding.musicControls.visibility = View.VISIBLE
        binding.btnMusicPlay.setImageResource(if (isPlaying) R.drawable.ic_media_pause else R.drawable.ic_media_play)
    }

    private fun renderPhoneLink() {
        val sub = binding.subtitleProjection
        when (ZlinkStatus.link) {
            ZlinkStatus.Link.CONNECTED -> {
                sub.text = activity.getString(R.string.link_connected, ZlinkStatus.modeLabel())
                sub.setTextColor(ContextCompat.getColor(activity, R.color.cockpit_accent))
            }
            ZlinkStatus.Link.FOUND -> {
                sub.setText(R.string.link_found)
                sub.setTextColor(ContextCompat.getColor(activity, R.color.cockpit_warn))
            }
            ZlinkStatus.Link.NONE -> {
                sub.setTextColor(ContextCompat.getColor(activity, R.color.cockpit_text_dim))
                cards.refreshProjectionLabel()
            }
        }
    }

    private fun appLabel(pkg: String): String = try {
        val pm = activity.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) { pkg }

    // ------------------------------------------------------------------ controls

    private fun transport(keyCode: Int) {
        val c = current
        if (c != null) {
            val t = c.transportControls
            when (keyCode) {
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> t.skipToPrevious()
                KeyEvent.KEYCODE_MEDIA_NEXT -> t.skipToNext()
                else -> if (playing(c)) t.pause() else t.play()
            }
            return
        }
        // Android Auto: ZLink takes media keys (it owns the media button receiver).
        val now = SystemClock.uptimeMillis()
        runCatching {
            audio?.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
            audio?.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
        }
    }

    private companion object {
        const val ZLINK_PKG = "com.zjinnova.zlink"
    }
}
