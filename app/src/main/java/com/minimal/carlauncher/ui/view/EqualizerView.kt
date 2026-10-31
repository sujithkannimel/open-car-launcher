package com.minimal.carlauncher.ui.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import com.minimal.carlauncher.core.Neon
import kotlin.math.abs
import kotlin.math.sin

/**
 * Small neon equaliser for the Music card: bars bounce while [playing], rest low when paused.
 * Purely decorative (the head unit cannot see the real audio levels of Android Auto).
 */
class EqualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var playing = false
        set(v) { if (field != v) { field = v; invalidate() } }

    private val bars = 5
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { alpha = 70 }
    private var shaderFor = 0

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        if (shaderFor != width * 31 + height) {
            shaderFor = width * 31 + height
            val s = LinearGradient(0f, 0f, w, h, Neon.GRADIENT, null, Shader.TileMode.CLAMP)
            paint.shader = s
            glow.shader = s
        }
        val gap = w / (bars * 3f)
        val bw = (w - gap * (bars - 1)) / bars
        val t = SystemClock.uptimeMillis() / 1000.0
        for (i in 0 until bars) {
            val level = if (playing) {
                // Two sines per bar at different speeds look more like music than one.
                (0.25 + 0.75 * abs(sin(t * (2.1 + i * 0.37) + i * 1.3) * 0.6 + sin(t * 3.3 + i) * 0.4))
                    .coerceIn(0.15, 1.0)
            } else 0.18 + 0.05 * (i % 2)
            val bh = (h * level).toFloat()
            val x = i * (bw + gap)
            val r = bw / 2f
            canvas.drawRoundRect(x - 2f, h - bh - 2f, x + bw + 2f, h, r, r, glow)
            canvas.drawRoundRect(x, h - bh, x + bw, h, r, r, paint)
        }
        if (playing && isShown) postInvalidateOnAnimation()
    }
}
