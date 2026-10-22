package com.minimal.carlauncher.panel

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View

/**
 * Draws a sleek, modern curved arc alongside the rotary knob with interactive upper/lower touch targets.
 *
 * Concentric with the physical hardware knob:
 * - isLeftKnob: curves convex toward the right, hugging the right bezel of the left knob (VOL +/-).
 * - !isLeftKnob: curves convex toward the left, hugging the left bezel of the right knob (Track Next/Prev).
 */
class KnobArcView(
    context: Context,
    private val isLeftKnob: Boolean,
    private val topLabel: String,
    private val bottomLabel: String,
    private val midLabel: String? = null,
    private val onTopClick: () -> Unit,
    private val onBottomClick: () -> Unit
) : View(context) {

    private val dens = resources.displayMetrics.density
    private val arcBounds = RectF()

    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * dens
        color = Color.argb(160, 255, 255, 255)
        strokeCap = Paint.Cap.ROUND
    }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f * dens
        color = Color.argb(60, 65, 199, 240) // Modern neon cyan glow
        strokeCap = Paint.Cap.ROUND
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 15f * dens
        typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    private val midTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(190, 140, 175, 210)
        textSize = 10f * dens
        typeface = Typeface.create("sans-serif-bold", Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        letterSpacing = 0.08f
    }

    private val activeGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(35, 65, 199, 240)
    }

    private var pressedZone = 0 // 0 none, 1 top, 2 bottom

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val cy = h / 2f
        val r = 70f * dens
        val offset = 50f * dens

        if (isLeftKnob) {
            val cx = -offset
            arcBounds.set(cx - r, cy - r, cx + r, cy + r)
        } else {
            val cx = w.toFloat() + offset
            arcBounds.set(cx - r, cy - r, cx + r, cy + r)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        if (pressedZone == 1) {
            canvas.drawRect(0f, 0f, w, h / 2f, activeGlowPaint)
        } else if (pressedZone == 2) {
            canvas.drawRect(0f, h / 2f, w, h, activeGlowPaint)
        }

        if (isLeftKnob) {
            canvas.drawArc(arcBounds, -36f, 72f, false, glowPaint)
            canvas.drawArc(arcBounds, -36f, 72f, false, arcPaint)

            canvas.drawText(topLabel, w * 0.65f, h * 0.28f, textPaint)
            if (midLabel != null) {
                canvas.drawText(midLabel, w * 0.68f, h * 0.54f, midTextPaint)
            }
            canvas.drawText(bottomLabel, w * 0.65f, h * 0.80f, textPaint)
        } else {
            canvas.drawArc(arcBounds, 144f, 72f, false, glowPaint)
            canvas.drawArc(arcBounds, 144f, 72f, false, arcPaint)

            canvas.drawText(topLabel, w * 0.35f, h * 0.28f, textPaint)
            if (midLabel != null) {
                canvas.drawText(midLabel, w * 0.32f, h * 0.54f, midTextPaint)
            }
            canvas.drawText(bottomLabel, w * 0.35f, h * 0.80f, textPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                pressedZone = if (event.y < height / 2f) 1 else 2
                invalidate()
                if (pressedZone == 1) onTopClick() else onBottomClick()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                pressedZone = 0
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
