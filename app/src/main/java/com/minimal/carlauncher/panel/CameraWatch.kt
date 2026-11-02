package com.minimal.carlauncher.panel

import android.content.Context
import android.location.Location
import android.media.AudioManager
import android.media.ToneGenerator
import com.minimal.carlauncher.core.CameraStore
import com.minimal.carlauncher.core.Cameras
import com.minimal.carlauncher.core.Prefs

/**
 * Checks each GPS fix for a traffic camera ahead and keeps [CameraStore.current] up to date.
 * A new camera chimes once (twice when over its known limit). A warning clears after a few
 * fixes without a camera ahead, so a wobble in the GPS heading doesn't make it flicker.
 */
internal class CameraWatch(private val context: Context) {

    private var lastId: String? = null
    private var misses = 0
    private var overChimed = false
    private val tone: ToneGenerator? by lazy {
        try { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70) } catch (_: Exception) { null }
    }

    fun onFix(l: Location) {
        CameraStore.lastPos = Triple(l.latitude, l.longitude, System.currentTimeMillis())
        if (!l.hasSpeed() || !l.hasBearing()) { miss(); return }
        val kmh = l.speed * 3.6f
        val a = Cameras.ahead(CameraStore.all(context), l.latitude, l.longitude, l.bearing, kmh, CameraStore.muted())
        if (a == null) { miss(); return }
        misses = 0
        val over = a.cam.limit > 0 && kmh > a.cam.limit + 3
        if (a.cam.id != lastId) {
            lastId = a.cam.id
            overChimed = over
            chime(if (over) 2 else 1)
            log("camera ahead: ${a.cam.kind} ${a.cam.id} ${a.distanceM} m at ${kmh.toInt()} km/h")
        } else if (over && !overChimed) {
            overChimed = true
            chime(2)
        }
        CameraStore.setCurrent(a)
    }

    private fun miss() {
        if (CameraStore.current == null) return
        if (++misses >= 3) { CameraStore.setCurrent(null); lastId = null; misses = 0 }
    }

    private fun chime(times: Int) {
        if (!Prefs.camChime) return
        try { tone?.startTone(if (times > 1) ToneGenerator.TONE_PROP_BEEP2 else ToneGenerator.TONE_PROP_BEEP, 180) } catch (_: Exception) {}
    }

    fun release() { try { tone?.release() } catch (_: Exception) {} }

    private fun log(msg: String) = PanelService.log(context, msg)
}
