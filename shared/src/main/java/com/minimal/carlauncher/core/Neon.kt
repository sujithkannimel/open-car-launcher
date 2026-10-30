package com.minimal.carlauncher.core

/**
 * The "graphite + neon" palette (option 3a), shared by the head unit and the phone app.
 *
 * A neutral graphite base keeps the screen calm; the neon gradient (cyan -> violet -> pink)
 * is used only for accents: knob rings, the compass rim, the active app, small lines.
 * Status colours (green / amber / red) stay plain so warnings always stand out.
 * ARGB ints, no Android types, so both apps and the unit tests can use them.
 */
object Neon {
    const val BG = 0xFF0C0D0F.toInt()
    const val SURFACE = 0xFF17181B.toInt()
    const val SURFACE_HI = 0xFF222429.toInt()
    const val BAR = 0xFF121316.toInt()
    const val DIVIDER = 0xFF26282D.toInt()

    const val TEXT = 0xFFF3F4F6.toInt()
    const val TEXT_2 = 0xFFC4C8CE.toInt()
    const val DIM = 0xFF8B9099.toInt()
    const val ICON = 0xFFAEB3BB.toInt()

    const val CYAN = 0xFF22D3EE.toInt()
    const val VIOLET = 0xFF8B5CF6.toInt()
    const val PINK = 0xFFF472B6.toInt()

    /** Gradient stops, start to end. */
    val GRADIENT = intArrayOf(CYAN, VIOLET, PINK)

    const val GREEN = 0xFF4ADE80.toInt()
    const val AMBER = 0xFFFBBF24.toInt()
    const val RED = 0xFFF43F5E.toInt()

    /** Same colour with a new alpha (0-255). */
    fun alpha(c: Int, a: Int): Int = (a.coerceIn(0, 255) shl 24) or (c and 0x00FFFFFF)
}
