package com.minimal.carlauncher.core

import android.os.Handler
import android.os.Looper

/** Hands a just-finished acceleration run from the panel service to the home screen. Same process. */
object PerfLive {

    /** Set by the home screen while it is shown. */
    @Volatile var listener: ((PerfRun, Boolean) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())

    /** False when no screen took it (the caller then shows a toast). */
    fun post(run: PerfRun, best: Boolean): Boolean {
        val l = listener ?: return false
        main.post { l(run, best) }
        return true
    }
}
