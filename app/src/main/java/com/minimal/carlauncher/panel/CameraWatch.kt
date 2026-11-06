package com.minimal.carlauncher.panel

import android.content.Context
import android.location.Location
import android.os.SystemClock
import com.minimal.carlauncher.core.CameraAhead
import com.minimal.carlauncher.core.CameraStore
import com.minimal.carlauncher.core.CameraTrack
import com.minimal.carlauncher.core.Cameras
import com.minimal.carlauncher.core.Prefs
import kotlin.math.roundToInt

/**
 * Turns GPS fixes into camera warnings, in two steps:
 * - first alert when a camera ahead comes within the chosen distance (700 m by default,
 *   300 m more above 60 km/h): "AI camera in 700 metres";
 * - final alert at 200 m: "Camera ahead", or "Slow down, limit 50" when over a known limit.
 * Once picked up, the camera is followed by distance alone until it is passed, so a bend
 * doesn't drop it; if the distance keeps growing (it's on another road) the warning ends
 * quietly. Only the nearest camera is shown; one close behind it gets "then another camera".
 * Each alert bumps [CameraStore.flashSeq] so the screens flash.
 */
internal class CameraWatch(private val context: Context) {

    private val voiceLazy = lazy { CameraVoice(context) }
    private val voice by voiceLazy
    private var track: CameraTrack? = null
    private var finalDone = false
    private var overSaid = false
    private var another = false
    /** Cameras just passed or left, by id -> elapsedRealtime: not picked up again for 3 minutes. */
    private val done = HashMap<String, Long>()

    fun onFix(l: Location) {
        val kmh = if (l.hasSpeed()) l.speed * 3.6f else 0f
        val t = track
        if (t != null) { follow(t, l, kmh); return }
        if (!l.hasSpeed() || !l.hasBearing()) return
        val now = SystemClock.elapsedRealtime()
        done.entries.removeAll { now - it.value > 180_000L }
        val range = Cameras.alertRange(Prefs.camRangeM, kmh)
        val list = Cameras.aheadAll(CameraStore.all(context), l.latitude, l.longitude, l.bearing, kmh,
            CameraStore.muted(), range).filter { it.cam.id !in done }
        val a = list.firstOrNull() ?: return
        voice.hashCode()                                 // start the speech engine early
        track = CameraTrack(a.cam, a.distanceM.toDouble())
        another = list.drop(1).any { it.distanceM - a.distanceM < 500 }
        val over = isOver(a, kmh)
        log("camera ahead: ${a.cam.kind} ${a.cam.id} ${a.distanceM} m at ${kmh.toInt()} km/h")
        if (a.distanceM <= Cameras.FINAL_M) {
            // Picked up late (just turned onto the road): one alert says it all.
            finalDone = true; overSaid = over
            voice.alert(if (over) slowDown(a) else name(a) + " ahead" + then(), if (over) 3 else 2)
        } else {
            finalDone = false; overSaid = false
            voice.alert(name(a) + (if (a.cam.limit > 0) ", limit ${a.cam.limit}," else "") +
                " in " + CameraVoice.distanceWords(a.distanceM.toDouble()) + then(), 1)
        }
        CameraStore.setCurrent(a, over, another, flash = true)
    }

    private fun follow(t: CameraTrack, l: Location, kmh: Float) {
        when (t.update(l.latitude, l.longitude)) {
            CameraTrack.Step.PASSED, CameraTrack.Step.LEFT -> { end(t); return }
            CameraTrack.Step.APPROACHING -> Unit
        }
        // Muted from the home screen while following it.
        if (CameraStore.current == null && CameraStore.muted().any { it.id == t.cam.id }) { end(t); return }
        val a = CameraAhead(t.cam, t.distanceM.roundToInt())
        val over = isOver(a, kmh)
        var flash = false
        if (!finalDone && a.distanceM <= Cameras.FINAL_M) {
            finalDone = true; overSaid = over; flash = true
            voice.alert(if (over) slowDown(a) else "Camera ahead", if (over) 3 else 2)
        } else if (finalDone && over && !overSaid) {
            overSaid = true; flash = true
            voice.alert(slowDown(a), 3)
        }
        CameraStore.setCurrent(a, over, another, flash)
    }

    private fun end(t: CameraTrack) {
        done[t.cam.id] = SystemClock.elapsedRealtime()
        track = null
        CameraStore.setCurrent(null)
    }

    private fun isOver(a: CameraAhead, kmh: Float) = a.cam.limit > 0 && kmh > a.cam.limit + 3

    private fun name(a: CameraAhead) = if (a.cam.kind == "speed") "Speed camera" else "AI camera"
    private fun slowDown(a: CameraAhead) = "Slow down, limit ${a.cam.limit}"
    private fun then() = if (another) ", then another camera" else ""

    fun release() { if (voiceLazy.isInitialized()) voice.release() }

    private fun log(msg: String) = PanelService.log(context, msg)
}
