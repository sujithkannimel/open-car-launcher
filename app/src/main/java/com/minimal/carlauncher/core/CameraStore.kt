package com.minimal.carlauncher.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The car's camera lists: OpenStreetMap cameras (built-in copy until the phone sends a newer
 * one) and cameras the driver muted. Updates from the phone only
 * replace the OpenStreetMap list; the driver's own lists are never touched by them.
 *
 * Also holds the warning currently shown ([current]) for the home screen and the panel.
 */
object CameraStore {

    @Volatile private var osm: CameraSet? = null
    @Volatile private var mutedCache: List<Camera>? = null

    /** The warning shown now, or null. */
    @Volatile var current: CameraAhead? = null
        private set

    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }
    private fun changed() = main.post { listeners.forEach { it() } }

    fun osmSet(context: Context): CameraSet {
        osm?.let { return it }
        val s = Cameras.decodeSet(Prefs.camOsm) ?: seed(context)
        osm = s
        return s
    }

    private fun seed(context: Context): CameraSet = try {
        Cameras.decodeSet(context.assets.open("cameras_seed.json").bufferedReader().use { it.readText() })
    } catch (_: Exception) { null } ?: CameraSet("none", 0L, emptyList())

    fun muted(): List<Camera> = mutedCache ?: Cameras.decode(Prefs.camMuted).also { mutedCache = it }

    /**
     * Everything that can warn: the OpenStreetMap list. Cameras marked by hand in older
     * versions are deleted the first time (marking was dropped as not needed).
     */
    fun all(context: Context): List<Camera> {
        if (!mineCleared) { mineCleared = true; if (Prefs.camMine.isNotEmpty()) Prefs.camMine = "" }
        return osmSet(context).cams
    }
    @Volatile private var mineCleared = false

    /** A newer list from the phone. False if it was refused (broken or much smaller). */
    fun replaceOsm(context: Context, set: CameraSet): Boolean {
        val old = osmSet(context)
        if (set.version == old.version) return false
        if (!Cameras.saneReplacement(set.cams, old.cams)) return false
        Prefs.camOsm = Cameras.encodeSet(set)
        osm = set
        changed()
        return true
    }

    fun mute(c: Camera) {
        if (muted().any { it.id == c.id }) return
        val list = muted() + c
        Prefs.camMuted = Cameras.encode(list); mutedCache = list
        if (current?.cam?.id == c.id) current = null
        changed()
    }

    fun unmute(id: String) {
        val list = muted().filter { it.id != id }
        Prefs.camMuted = Cameras.encode(list); mutedCache = list
        changed()
    }

    /** Driving faster than the camera's known limit. */
    @Volatile var over = false
        private set

    /** Another camera follows close behind this one. */
    @Volatile var another = false
        private set

    /** Goes up at each alert (new camera, final warning): screens flash the cards / strip once per step. */
    @Volatile var flashSeq = 0
        private set

    /** The panel service's latest verdict. Listeners hear about it only when something visible changes. */
    fun setCurrent(a: CameraAhead?, over: Boolean = false, another: Boolean = false, flash: Boolean = false) {
        val old = current
        val oldOver = this.over
        current = a
        this.over = a != null && over
        this.another = a != null && another
        if (flash) flashSeq++
        if (flash || oldOver != this.over || old?.cam?.id != a?.cam?.id ||
            (old != null && a != null && old.distanceM / 50 != a.distanceM / 50)) changed()
    }
}
