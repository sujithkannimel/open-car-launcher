package com.minimal.carlauncher.panel

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import com.minimal.carlauncher.core.Neon
import com.minimal.carlauncher.core.Prefs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * Draws both rotary-knob decorations across the whole strip, using one shared geometry:
 *
 * - the volume number / play-pause icon, glowing neon, in the visible part of each
 *   knob lens (no filled disc: the lens shows the dark panel), and
 * - a curved arc concentric with that same centre, sitting just outside the bezel,
 *   split into an upper and lower touch zone.
 *
 * Because the arc is drawn from the knob centre (not from a separately positioned box),
 * disc and arc can never drift apart. Geometry comes from [Prefs] and is tuned on the unit
 * through PanelService's adjust mode.
 *
 * Touches outside the discs and arc zones return false, so they fall through to the
 * buttons and labels underneath / beside this view.
 */
class KnobsView(
    context: Context,
    private val leftTop: String,
    private val leftBottom: String,
    private val rightTop: String,
    private val rightBottom: String,
    private val onLeftTop: () -> Unit,
    private val onLeftBottom: () -> Unit,
    private val onRightTop: () -> Unit,
    private val onRightBottom: () -> Unit,
    private val onLeftDisc: () -> Unit,
    private val onRightDisc: () -> Unit,
    /** Arc zones that auto-repeat while held (volume). */
    private val repeatLeft: Boolean = true,
) : View(context) {

    var leftText: String = ""
        set(v) { if (field != v) { field = v; invalidate() } }
    var rightText: String = ""
        set(v) { if (field != v) { field = v; invalidate() } }

    /** Draws crosshairs + outlines so the knob centre can be lined up by eye. */
    var showGuides = false
        set(v) { field = v; invalidate() }

    /**
     * Graphite + neon: white volume number with a violet glow, a pink play icon, and arcs
     * filled with the cyan -> violet -> pink gradient (shader set per knob in [drawKnob]).
     */
    private val discText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Neon.TEXT
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        setShadowLayer(12f, 0f, 0f, Neon.alpha(Neon.VIOLET, 200))
    }
    private val captionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Neon.DIM
        textSize = 11f
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
        letterSpacing = 0.18f
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Neon.PINK
        style = Paint.Style.FILL
        setShadowLayer(12f, 0f, 0f, Neon.alpha(Neon.PINK, 190))
    }
    private val iconPath = android.graphics.Path()
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.WHITE
        strokeCap = Paint.Cap.ROUND
        setShadowLayer(9f, 0f, 0f, Neon.alpha(Neon.VIOLET, 200))
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 12f
        color = Color.WHITE
        alpha = 70
        strokeCap = Paint.Cap.ROUND
    }
    private val pressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 16f
        color = Color.WHITE
        alpha = 150
        strokeCap = Paint.Cap.ROUND
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Neon.TEXT
        textSize = 22f
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Neon.alpha(Neon.ICON, 150)
        strokeWidth = 2f
        strokeCap = Paint.Cap.ROUND
    }

    /** Top-to-bottom neon gradient across one knob's arc. */
    private fun neonShader(cy: Float) = LinearGradient(
        0f, cy - arcR, 0f, cy + arcR, Neon.GRADIENT, null, Shader.TileMode.CLAMP
    )
    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.rgb(255, 64, 129)
    }

    private val rect = RectF()

    // Geometry (px), refreshed from Prefs on every layout/draw.
    private var lcx = 0f; private var lcy = 0f
    private var rcx = 0f; private var rcy = 0f
    private var discR = 0f; private var arcR = 0f

    private fun loadGeometry() {
        lcx = Prefs.knobLeftX.toFloat()
        lcy = Prefs.knobLeftY.toFloat()
        rcx = width - Prefs.knobRightX.toFloat()
        rcy = Prefs.knobRightY.toFloat()
        discR = Prefs.knobDisc / 2f
        arcR = Prefs.knobArcRadius.toFloat()
    }

    /** Right edge (px from left) of everything drawn for the left knob. */
    fun leftExtent(): Int = (Prefs.knobLeftX + Prefs.knobArcRadius + LABEL_OUT + 14).toInt()

    /** Same for the right knob, measured from the right edge. */
    fun rightExtent(): Int = (Prefs.knobRightX + Prefs.knobArcRadius + LABEL_OUT + 14).toInt()

    /**
     * Visible arc span, in canvas degrees on the knob's right side (0 = 3 o'clock,
     * negative = up). Clipped so the arc stays inside the strip.
     */
    private fun span(cy: Float): Pair<Float, Float> {
        val top = 12f
        val bottom = height - 10f
        val up = Math.toDegrees(asin(((cy - top) / arcR).coerceIn(-1f, 1f).toDouble())).toFloat()
        val down = Math.toDegrees(asin(((bottom - cy) / arcR).coerceIn(-1f, 1f).toDouble())).toFloat()
        return Pair(-min(up, MAX_HALF_SWEEP), min(down, MAX_HALF_SWEEP))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        loadGeometry()
        drawKnob(canvas, lcx, lcy, leftSide = true, leftText, leftTop, leftBottom, caption = "VOL")
        drawKnob(canvas, rcx, rcy, leftSide = false, rightText, rightTop, rightBottom, caption = null)
    }

    private fun drawKnob(
        c: Canvas, cx: Float, cy: Float, leftSide: Boolean,
        text: String, topLabel: String, bottomLabel: String, caption: String?
    ) {
        // No filled disc: the lens shows the dark panel, and the content glows on it.

        // Centre text goes where the screen actually shows through the lens: the knob
        // centres sit near the screen's bottom corners, so the true centre is partly
        // off-screen and text drawn there would be cut in half.
        val (vx, vy, room) = visibleCentre(cx, cy, min(discR, arcR * 0.6f))
        // User offset (adjust mode): VOL / PLAY targets nudge the content inside the lens.
        val tx = vx + (if (leftSide) Prefs.volDx else Prefs.playDx)
        val tyc = vy + (if (leftSide) Prefs.volDy else Prefs.playDy)
        if (leftSide) {
            discText.textSize = (room * 0.9f).coerceIn(20f, 54f)
            val numY = tyc - (if (caption != null) 6f else 0f)
            val ty = numY - (discText.descent() + discText.ascent()) / 2f
            c.drawText(text, tx, ty, discText)
            if (caption != null) c.drawText(caption, tx, ty + 18f, captionPaint)
        } else {
            drawPlayPause(c, tx, tyc, (room * 0.55f).coerceIn(14f, 30f), text.contains('\u275A'))
        }

        // Arc: right side of the left knob, left side of the right knob (mirror).
        val (a0, a1) = span(cy)
        val mid = (a0 + a1) / 2f
        rect.set(cx - arcR, cy - arcR, cx + arcR, cy + arcR)
        val start = if (leftSide) a0 else 180f - a1
        val sweep = a1 - a0
        val shader = neonShader(cy)
        glowPaint.shader = shader; arcPaint.shader = shader; pressPaint.shader = shader
        c.drawArc(rect, start, sweep, false, glowPaint)
        c.drawArc(rect, start, sweep, false, arcPaint)

        val zone = if (leftSide) pressedLeft else pressedRight
        if (zone != 0) {
            val (ps, pe) = if (zone == 1) a0 to mid else mid to a1
            val s = if (leftSide) ps else 180f - pe
            c.drawArc(rect, s, pe - ps, false, pressPaint)
        }

        // Divider tick between the two zones
        tick(c, cx, cy, mirror(mid, leftSide))

        // Labels just outside the arc, a little in from each end
        label(c, cx, cy, mirror(a0 + (mid - a0) * 0.45f, leftSide), topLabel)
        label(c, cx, cy, mirror(a1 - (a1 - mid) * 0.45f, leftSide), bottomLabel)

        if (showGuides) {
            c.drawCircle(cx, cy, discR, guidePaint)
            c.drawLine(cx - discR - 10, cy, cx + discR + 10, cy, guidePaint)
            c.drawLine(cx, cy - discR - 10, cx, cy + discR + 10, guidePaint)
        }
    }

    /**
     * Centroid of the part of circle (cx, cy, r) that lies on screen, plus the radius of
     * the largest circle around it that stays both on screen and inside the lens.
     */
    private fun visibleCentre(cx: Float, cy: Float, r: Float): Triple<Float, Float, Float> {
        val w = width.toFloat(); val h = height.toFloat()
        var sx = 0f; var sy = 0f; var n = 0
        val step = 4f
        var y = maxOf(0f, cy - r)
        while (y <= minOf(h, cy + r)) {
            var x = maxOf(0f, cx - r)
            while (x <= minOf(w, cx + r)) {
                if (hypot(x - cx, y - cy) <= r) { sx += x; sy += y; n++ }
                x += step
            }
            y += step
        }
        if (n == 0) return Triple(cx, cy, r)
        val gx = sx / n; val gy = sy / n
        val room = minOf(minOf(gx, w - gx), minOf(gy, h - gy), r - hypot(gx - cx, gy - cy))
        return Triple(gx, gy, room.coerceAtLeast(10f))
    }

    /** Crisp vector play triangle or pause bars, centred on (x, y), half-size s. */
    private fun drawPlayPause(c: Canvas, x: Float, y: Float, s: Float, paused: Boolean) {
        iconPath.reset()
        if (paused) {
            val bw = s * 0.42f; val gap = s * 0.36f; val r = bw * 0.35f
            c.drawRoundRect(x - gap / 2 - bw, y - s, x - gap / 2, y + s, r, r, iconPaint)
            c.drawRoundRect(x + gap / 2, y - s, x + gap / 2 + bw, y + s, r, r, iconPaint)
        } else {
            // Optical centring: shift right a little so the triangle looks centred.
            val ox = x + s * 0.15f
            iconPath.moveTo(ox - s * 0.75f, y - s)
            iconPath.lineTo(ox + s * 0.95f, y)
            iconPath.lineTo(ox - s * 0.75f, y + s)
            iconPath.close()
            c.drawPath(iconPath, iconPaint)
        }
    }

    private fun mirror(deg: Float, leftSide: Boolean) = if (leftSide) deg else 180f - deg

    private fun tick(c: Canvas, cx: Float, cy: Float, deg: Float) {
        val r = Math.toRadians(deg.toDouble())
        val r1 = arcR + 6f; val r2 = arcR + 14f
        c.drawLine(
            cx + (r1 * cos(r)).toFloat(), cy + (r1 * sin(r)).toFloat(),
            cx + (r2 * cos(r)).toFloat(), cy + (r2 * sin(r)).toFloat(), tickPaint
        )
    }

    private fun label(c: Canvas, cx: Float, cy: Float, deg: Float, text: String) {
        val r = Math.toRadians(deg.toDouble())
        val rr = arcR + LABEL_OUT
        val x = cx + (rr * cos(r)).toFloat()
        val y = cy + (rr * sin(r)).toFloat() - (labelPaint.descent() + labelPaint.ascent()) / 2f
        c.drawText(text, x, y, labelPaint)
    }

    // ------------------------------------------------------------------ touch

    private var pressedLeft = 0   // 0 none, 1 top, 2 bottom, 3 disc
    private var pressedRight = 0
    private val repeater = Handler(Looper.getMainLooper())
    private var repeatAction: (() -> Unit)? = null
    private val repeatTick = object : Runnable {
        override fun run() {
            repeatAction?.invoke()
            repeater.postDelayed(this, REPEAT_MS)
        }
    }

    /** Returns (knob 1=left 2=right, zone 1=top 2=bottom 3=disc) or null. */
    private fun hit(x: Float, y: Float): Pair<Int, Int>? {
        loadGeometry()
        for (knob in 1..2) {
            val cx = if (knob == 1) lcx else rcx
            val cy = if (knob == 1) lcy else rcy
            val d = hypot(x - cx, y - cy)
            if (d <= discR) return knob to 3
            val outward = if (knob == 1) x >= cx else x <= cx
            if (outward && d in (arcR - 26f)..(arcR + LABEL_OUT + 24f)) {
                val (a0, a1) = span(cy)
                var deg = Math.toDegrees(atan2((y - cy).toDouble(), (x - cx).toDouble())).toFloat()
                if (knob == 2) deg = 180f - deg
                if (deg > 180f) deg -= 360f
                if (deg < a0 - 14f || deg > a1 + 14f) continue
                return knob to (if (deg < (a0 + a1) / 2f) 1 else 2)
            }
        }
        return null
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val (knob, zone) = hit(e.x, e.y) ?: return false
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                if (knob == 1) pressedLeft = zone else pressedRight = zone
                invalidate()
                val action: () -> Unit = when {
                    knob == 1 && zone == 1 -> onLeftTop
                    knob == 1 && zone == 2 -> onLeftBottom
                    knob == 1 -> onLeftDisc
                    zone == 1 -> onRightTop
                    zone == 2 -> onRightBottom
                    else -> onRightDisc
                }
                action()
                if (knob == 1 && zone != 3 && repeatLeft) {
                    repeatAction = action
                    repeater.postDelayed(repeatTick, REPEAT_DELAY_MS)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                repeater.removeCallbacks(repeatTick)
                repeatAction = null
                pressedLeft = 0; pressedRight = 0
                invalidate()
                return true
            }
        }
        return true
    }

    override fun onDetachedFromWindow() {
        repeater.removeCallbacks(repeatTick)
        super.onDetachedFromWindow()
    }

    companion object {
        private const val LABEL_OUT = 24f
        private const val MAX_HALF_SWEEP = 62f
        private const val REPEAT_DELAY_MS = 450L
        private const val REPEAT_MS = 140L
    }
}
