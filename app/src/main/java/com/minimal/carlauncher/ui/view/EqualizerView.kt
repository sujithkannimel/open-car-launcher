package com.minimal.carlauncher.ui.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

/**
 * Rainbow spectrum behind the Music card: as many bars as fit (one per ~14 dp), each its own
 * colour across the full rainbow, with the colours slowly drifting while [playing]. A small
 * peak dot rides above each bar and falls back slowly, like a hi-fi visualiser. Translucent so
 * the title and controls on top stay readable. Decorative only: the head unit cannot see the
 * real audio levels of Android Auto.
 */
class EqualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var playing = false
        set(v) { if (field != v) { field = v; invalidate() } }

    private val density = resources.displayMetrics.density
    private val pitch = 14f * density
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cap = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hsv = FloatArray(3)
    private var peaks = FloatArray(0)
    private var lastMs = 0L
    private var hueShift = 0f

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val bars = (w / pitch).toInt().coerceAtLeast(5)
        if (peaks.size != bars) peaks = FloatArray(bars)
        val gap = w / (bars * 3f)
        val bw = (w - gap * (bars - 1)) / bars
        val now = SystemClock.uptimeMillis()
        val dt = if (lastMs == 0L) 0f else ((now - lastMs) / 1000f).coerceAtMost(0.1f)
        lastMs = now
        if (playing) hueShift = (hueShift + dt * 24f) % 360f      // a full colour lap in 15 s
        val t = now / 1000.0
        val capH = 3f * density
        for (i in 0 until bars) {
            val level = if (playing) {
                // Two sines per bar at different speeds look more like music than one.
                (0.12 + 0.8 * abs(sin(t * (1.7 + i * 0.13) + i) * 0.6 + sin(t * 2.9 + i * 0.7) * 0.4))
                    .coerceIn(0.08, 0.86).toFloat()
            } else 0.07f + 0.03f * (i % 3)
            // Peaks jump up with the bar and sink back at a steady pace.
            peaks[i] = max(level, peaks[i] - dt * 0.35f)

            hsv[0] = (i.toFloat() / bars * 300f + hueShift) % 360f   // red ... violet
            hsv[1] = 0.85f; hsv[2] = 1f
            val c = Color.HSVToColor(hsv)
            bar.color = c; bar.alpha = 125
            cap.color = c; cap.alpha = if (playing) 220 else 90

            val x = i * (bw + gap)
            val bh = h * level
            val r = bw / 2f
            canvas.drawRoundRect(x, h - bh, x + bw, h, r, r, bar)
            val py = h - h * peaks[i] - capH * 2.2f
            if (py > 0f) canvas.drawRoundRect(x, py, x + bw, py + capH, capH / 2f, capH / 2f, cap)
        }
        val settling = peaks.any { it > 0.12f }
        if ((playing || settling) && isShown) postInvalidateOnAnimation() else lastMs = 0L
    }
}
