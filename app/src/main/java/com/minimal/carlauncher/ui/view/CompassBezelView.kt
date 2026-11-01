package com.minimal.carlauncher.ui.view

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.minimal.carlauncher.R
import com.minimal.carlauncher.core.Neon
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The ring around the map portal, drawn as a sports-car rev counter for the speed:
 * 40 slanted segments over 270 degrees (0 at bottom-left, 160 km/h at bottom-right) that
 * light up with the speed in zones - cyan to 60, violet to 100, pink to 120, red above -
 * with white ticks and labels at 0 / 40 / 60 / 100 / 120 / 160.
 *
 * A small cyan pointer on the inner edge still shows where north is ([northDeg], the same
 * number osmdroid uses as its map orientation).
 */
class CompassBezelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var northDeg: Float = 0f
        set(value) {
            if (abs(value - field) < 0.5f) return
            field = value
            invalidate()
        }

    /** Shown speed in km/h (animates toward [setSpeed]'s target). */
    private var shownKmh = 0f
    private var target = 0f
    private var anim: ValueAnimator? = null

    /** New speed from GPS (km/h); the arc sweeps to it smoothly. */
    fun setSpeed(kmh: Float) {
        val t = kmh.coerceIn(0f, MAX_KMH)
        if (abs(t - target) < 0.3f) return
        target = t
        anim?.cancel()
        anim = ValueAnimator.ofFloat(shownKmh, t).apply {
            duration = 650L
            interpolator = DecelerateInterpolator()
            addUpdateListener { shownKmh = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private val density = resources.displayMetrics.density
    private val bezelWidth = resources.getDimension(R.dimen.map_bezel_width)
    private val font = try { ResourcesCompat.getFont(context, R.font.audiowide) } catch (_: Exception) { null }

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.cockpit_surface)
    }
    private val offPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0x26, 0x28, 0x2D) }
    private val onPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        maskFilter = BlurMaskFilter(5f * density, BlurMaskFilter.Blur.NORMAL)
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Neon.TEXT; strokeWidth = 2f * density; strokeCap = Paint.Cap.ROUND
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = font
        color = Neon.TEXT_2
    }
    private val northPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Neon.CYAN
        setShadowLayer(4f * density, 0f, 0f, Neon.CYAN)
    }
    private val seg = Path()
    private val tri = Path()

    init { setLayerType(LAYER_TYPE_SOFTWARE, null) }   // blur and shadow need it

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val outer = min(cx, cy)
        val inner = outer - bezelWidth
        canvas.drawCircle(cx, cy, outer, ringPaint)

        // Arc in the outer part of the ring, labels in the inner part.
        val pad = 2.5f * density
        val r1 = outer - pad
        val r0 = r1 - bezelWidth * 0.40f
        val lit = Math.round(shownKmh / MAX_KMH * SEGMENTS)
        for (i in 0 until SEGMENTS) {
            val a0 = angle(i * MAX_KMH / SEGMENTS)
            val a1 = angle((i + 0.72f) * MAX_KMH / SEGMENTS)
            seg.reset()
            seg.moveTo(px(cx, r1, a0), py(cy, r1, a0))
            seg.lineTo(px(cx, r1, a1), py(cy, r1, a1))
            seg.lineTo(px(cx, r0, a1), py(cy, r0, a1))
            seg.lineTo(px(cx, r0, a0), py(cy, r0, a0))
            seg.close()
            if (i < lit) {
                val c = zone((i + 0.5f) * MAX_KMH / SEGMENTS)
                glowPaint.color = Neon.alpha(c, 170)
                onPaint.color = c
                canvas.drawPath(seg, glowPaint)
                canvas.drawPath(seg, onPaint)
            } else canvas.drawPath(seg, offPaint)
        }

        labelPaint.textSize = bezelWidth * 0.30f
        val labelR = r0 - bezelWidth * 0.26f
        val shift = -(labelPaint.ascent() + labelPaint.descent()) / 2f
        for (v in MARKS) {
            val a = angle(v)
            canvas.drawLine(px(cx, r1 + pad * 0.6f, a), py(cy, r1 + pad * 0.6f, a),
                px(cx, r0 - 2f * density, a), py(cy, r0 - 2f * density, a), tickPaint)
            labelPaint.color = if (v >= 120f) Neon.RED else Neon.TEXT_2
            canvas.drawText(v.toInt().toString(), px(cx, labelR, a), py(cy, labelR, a) + shift, labelPaint)
        }

        // North pointer: a small triangle on the inner edge of the ring.
        val na = Math.toRadians((northDeg - 90f).toDouble()).toFloat()
        val tip = inner + 1f * density
        val base = inner + bezelWidth * 0.22f
        val half = 0.07f
        tri.reset()
        tri.moveTo(px(cx, tip, na), py(cy, tip, na))
        tri.lineTo(px(cx, base, na - half), py(cy, base, na - half))
        tri.lineTo(px(cx, base, na + half), py(cy, base, na + half))
        tri.close()
        canvas.drawPath(tri, northPaint)
    }

    /** Screen angle (radians, 0 = 3 o'clock, clockwise) for a speed on the 270 degree arc. */
    private fun angle(kmh: Float): Float = Math.toRadians(135.0 + kmh / MAX_KMH * 270.0).toFloat()
    private fun px(cx: Float, r: Float, a: Float) = cx + r * cos(a)
    private fun py(cy: Float, r: Float, a: Float) = cy + r * sin(a)

    private fun zone(kmh: Float): Int = when {
        kmh < 60f -> Neon.CYAN
        kmh < 100f -> Neon.VIOLET
        kmh < 120f -> Neon.PINK
        else -> Neon.RED
    }

    private companion object {
        const val MAX_KMH = 160f
        const val SEGMENTS = 40
        val MARKS = floatArrayOf(0f, 40f, 60f, 100f, 120f, 160f)
    }
}
