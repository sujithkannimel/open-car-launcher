package com.minimal.carlauncher.ui.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import com.minimal.carlauncher.core.Neon

/**
 * A row of slanted segments that fill left to right, like a rev counter: lit segments take
 * the neon gradient (cyan, violet, pink) with a soft glow, unlit ones stay dim. Used under the
 * clock (fills with the seconds) and under the speed (fills with km/h).
 */
class SportBarView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

    var segments = 20
        set(v) { field = v.coerceAtLeast(1); invalidate() }

    /** 0..1 share of segments lit. */
    var level = 0f
        set(v) { val c = v.coerceIn(0f, 1f); if (c != field) { field = c; invalidate() } }

    private val dp = resources.displayMetrics.density
    private val on = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        maskFilter = android.graphics.BlurMaskFilter(4f * dp, android.graphics.BlurMaskFilter.Blur.NORMAL)
    }
    private val off = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Neon.alpha(0xFFFFFFFF.toInt(), 28) }
    private val path = Path()

    init { setLayerType(LAYER_TYPE_SOFTWARE, null) }  // blur needs it

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val shader = LinearGradient(0f, 0f, w.toFloat(), 0f, Neon.GRADIENT, null, Shader.TileMode.CLAMP)
        on.shader = shader
        glow.shader = LinearGradient(0f, 0f, w.toFloat(), 0f, Neon.GRADIENT, null, Shader.TileMode.CLAMP)
        glow.alpha = 150
    }

    override fun onDraw(canvas: Canvas) {
        val pad = 4f * dp
        val h = height - 2 * pad
        if (h <= 0) return
        val slant = h * 0.45f
        val gap = 3f * dp
        val usable = width - 2 * pad - slant
        val segW = (usable - gap * (segments - 1)) / segments
        val lit = Math.round(level * segments)
        for (i in 0 until segments) {
            val x = pad + i * (segW + gap)
            path.reset()
            path.moveTo(x + slant, pad)
            path.lineTo(x + slant + segW, pad)
            path.lineTo(x + segW, pad + h)
            path.lineTo(x, pad + h)
            path.close()
            if (i < lit) {
                canvas.drawPath(path, glow)
                canvas.drawPath(path, on)
            } else canvas.drawPath(path, off)
        }
    }
}
