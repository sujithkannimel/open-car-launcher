package com.minimal.carlauncher.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The car's camera lists: OpenStreetMap cameras (built-in copy until the phone sends a newer
 * one), cameras the driver marked, and cameras the driver muted. Updates from the phone only
 * replace the OpenStreetMap list; the driver's own lists are never touched by them.
 *
 * Also holds the warning currently shown ([current]) for the home screen and the panel.
 */
object CameraStore {

    @Volatile private var osm: CameraSet? = null
    @Volatile private var mineCache: List<Camera>? = null
    @Volatile private var mutedCache: List<Camera>? = null

    /** Last good GPS position (lat, lon, wall-clock ms) from the panel service, for "Mark camera here". */
    @Volatile var lastPos: Triple<Double, Double, Long>? = null

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

    fun mine(): List<Camera> = mineCache ?: Cameras.decode(Prefs.camMine).also { mineCache = it }
    fun muted(): List<Camera> = mutedCache ?: Cameras.decode(Prefs.camMuted).also { mutedCache = it }

    /** Everything that can warn: OpenStreetMap plus the driver's own. */
    fun all(context: Context): List<Camera> = osmSet(context).cams + mine()

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

    fun mark(lat: Double, lon: Double, now: Long = System.currentTimeMillis()): Camera {
        val c = Camera("me:$now", lat, lon, "user")
        val list = mine() + c
        Prefs.camMine = Cameras.encode(list); mineCache = list
        changed()
        return c
    }

    fun removeMine(id: String) {
        val list = mine().filter { it.id != id }
        Prefs.camMine = Cameras.encode(list); mineCache = list
        changed()
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

    /** The panel service's latest verdict. Listeners hear about it only when it changes. */
    fun setCurrent(a: CameraAhead?) {
        val old = current
        current = a
        if (old?.cam?.id != a?.cam?.id || (old != null && a != null && old.distanceM / 50 != a.distanceM / 50)) changed()
    }
}
