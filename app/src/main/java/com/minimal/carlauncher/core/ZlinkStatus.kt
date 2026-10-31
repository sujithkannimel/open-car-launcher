package com.minimal.carlauncher.core

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/**
 * ZLink's state as it announces it: action "com.zjinnova.zlink", extra "status".
 * Seen on the K2401: ACTION_ZJ_PHONEFOUND, CONNECTED (phoneMode=auto_wireless), DISCONNECT,
 * MAIN_AUDIO_START / MAIN_AUDIO_STOP (Android Auto sound on / off), MAIN_PAGE_SHOW / HIDDEN.
 *
 * The broadcasts are not sticky, so the always-running panel service feeds them in and the
 * home screen reads the result. Same process, so a plain object is enough.
 */
object ZlinkStatus {

    enum class Link { NONE, FOUND, CONNECTED }

    @Volatile var link: Link = Link.NONE
        private set
    @Volatile var audio: Boolean = false
        private set
    @Volatile var phoneMode: String? = null
        private set

    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }

    /** Feeds one broadcast. Returns true if anything changed. */
    fun onBroadcast(status: String, mode: String?): Boolean {
        val before = Triple(link, audio, phoneMode)
        when (status) {
            "ACTION_ZJ_PHONEFOUND" -> if (link == Link.NONE) link = Link.FOUND
            "CONNECTED" -> link = Link.CONNECTED
            "DISCONNECT", "DISCONNECTED", "LINK_STATUS_DISCONNECTED" -> { link = Link.NONE; audio = false }
            "MAIN_AUDIO_START" -> { audio = true; if (link == Link.NONE) link = Link.CONNECTED }
            "MAIN_AUDIO_STOP" -> audio = false
        }
        if (mode != null) phoneMode = mode
        val changed = before != Triple(link, audio, phoneMode)
        if (changed) main.post { listeners.forEach { it() } }
        return changed
    }

    /** Readable phone mode for the Phone Link card. */
    fun modeLabel(): String = when (phoneMode) {
        "auto_wireless" -> "Android Auto"
        "auto_wired" -> "Android Auto (USB)"
        "carplay_wireless", "carplay_wired" -> "CarPlay"
        null -> "Android Auto"
        else -> "Phone"
    }
}
