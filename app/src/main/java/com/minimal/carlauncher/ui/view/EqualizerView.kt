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
 * Multicolour spectrum behind the Music card: as many bars as fit (one per ~14 dp), in the
 * cyan -> violet -> pink gradient, bouncing while [playing] and resting low otherwise.
 * Translucent so the title and controls on top stay readable. Purely decorative: the head
 * unit cannot see the real audio levels of Android Auto.
 */
class EqualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var playing = false
        set(v) { if (field != v) { field = v; invalidate() } }

    private val pitch = 14f * resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { alpha = 95 }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { alpha = 35 }
    private var shaderFor = 0

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        if (shaderFor != width * 31 + height) {
            shaderFor = width * 31 + height
            val s = LinearGradient(0f, 0f, w, 0f, Neon.GRADIENT, null, Shader.TileMode.CLAMP)
            paint.shader = s
            glow.shader = s
        }
        val bars = (w / pitch).toInt().coerceAtLeast(5)
        val gap = w / (bars * 3f)
        val bw = (w - gap * (bars - 1)) / bars
        val t = SystemClock.uptimeMillis() / 1000.0
        for (i in 0 until bars) {
            val level = if (playing) {
                // Two sines per bar at different speeds look more like music than one.
                (0.12 + 0.8 * abs(sin(t * (1.7 + i * 0.13) + i) * 0.6 + sin(t * 2.9 + i * 0.7) * 0.4))
                    .coerceIn(0.08, 0.8)
            } else 0.07 + 0.03 * (i % 3)
            val bh = (h * level).toFloat()
            val x = i * (bw + gap)
            val r = bw / 2f
            canvas.drawRoundRect(x - 2f, h - bh - 2f, x + bw + 2f, h, r, r, glow)
            canvas.drawRoundRect(x, h - bh, x + bw, h, r, r, paint)
        }
        if (playing && isShown) postInvalidateOnAnimation()
    }
}
