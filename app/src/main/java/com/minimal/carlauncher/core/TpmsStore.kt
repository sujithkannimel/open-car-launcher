package com.minimal.carlauncher.core

import android.os.SystemClock
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The latest tyre readings the phone sent over the live link, and the low-pressure / fast-leak
 * warning logic. Readings are shown on the Tyres screen and the home strip; a warning is spoken
 * and flashed like a camera alert.
 */
object TpmsStore {

    data class Tyre(val pos: String, val psi: Float, val tempC: Float, val batt: Int, val at: Long) {
        val fresh: Boolean get() = SystemClock.elapsedRealtime() - at < 120_000L
        val ageSec: Int get() = ((SystemClock.elapsedRealtime() - at) / 1000L).toInt()
    }

    @Volatile private var map: Map<String, Tyre> = emptyMap()
    /** Pressure a short while ago, for fast-leak detection: pos -> (psi, elapsed). */
    private val history = HashMap<String, Pair<Float, Long>>()

    /** The current warning, or null. [leak] means a fast drop rather than a steady low. */
    @Volatile var warnPos: String? = null
        private set
    @Volatile var warnLeak = false
        private set

    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }
    private fun changed() = listeners.forEach { it() }

    fun tyre(pos: String): Tyre? = map[pos]?.takeIf { it.fresh }
    fun all(): List<Tyre> = Tpms.POSITIONS.mapNotNull { tyre(it) }
    fun any(): Boolean = all().isNotEmpty()

    /** Last reading for a wheel, however old (for display: sensors sleep when parked). */
    fun last(pos: String): Tyre? = map[pos]
    /** Any wheel we've ever heard from, fresh or stale. */
    fun anyKnown(): Boolean = Tpms.POSITIONS.any { map[it] != null }

    /** New readings from the phone. Returns a position to warn about now (new warning), else null. */
    fun update(tyres: List<Tpms.Tyre>): String? {
        if (tyres.isEmpty()) return null
        val now = SystemClock.elapsedRealtime()
        val m = HashMap(map)
        var leakPos: String? = null
        for (t in tyres) {
            if (t.psi < 0f) continue
            val at = now - t.ageSec * 1000L
            // Fast leak: dropped >= LEAK_DROP since a reading 20-180 s ago.
            history[t.pos]?.let { (oldPsi, oldAt) ->
                val dt = now - oldAt
                if (dt in 20_000L..180_000L && oldPsi - t.psi >= LEAK_DROP) leakPos = t.pos
            }
            if (history[t.pos] == null || now - (history[t.pos]?.second ?: 0L) > 30_000L)
                history[t.pos] = t.psi to now
            m[t.pos] = Tyre(t.pos, t.psi, t.tempC, t.batt, at)
        }
        map = m
        // Decide the warning: a fast leak wins, else the lowest tyre under the limit.
        val low = all().filter { it.psi >= 0.1f && it.psi < Prefs.tpmsLowPsi }.minByOrNull { it.psi }
        val newPos = leakPos ?: low?.pos
        val newLeak = leakPos != null
        val raise = newPos != null && (newPos != warnPos || newLeak != warnLeak)
        warnPos = newPos; warnLeak = newLeak
        changed()
        return if (raise) newPos else null
    }

    fun clearStale() { if (map.values.any { !it.fresh }) changed() }

    private const val LEAK_DROP = 4f
}
