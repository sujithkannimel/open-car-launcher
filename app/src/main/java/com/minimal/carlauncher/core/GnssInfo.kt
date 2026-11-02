package com.minimal.carlauncher.core

/** Satellite status from the panel service's GPS session, for the "acquiring" readout and diagnostics. */
object GnssInfo {
    /** Satellites used in the last fix / currently seen; -1 = no status yet. */
    @Volatile var used = -1
    @Volatile var visible = -1

    fun label(): String = if (visible < 0) "" else "$used/$visible sats"
}
