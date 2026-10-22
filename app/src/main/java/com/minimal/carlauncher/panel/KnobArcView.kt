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
 * @param isLeftKnob true for the left knob (arc curves convex toward the right, e.g. VOL + / -);
 *                   false for the right knob (arc curves convex toward the left, e.g. Track Next / Prev).
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
        strokeWidth = 2f * dens
        color = Color.argb(120, 255, 255, 255)
        strokeCap = Paint.Cap.ROUND
    }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f * dens
        color = Color.argb(40, 65, 199, 240) // subtle neon cyan glow
        strokeCap = Paint.Cap.ROUND
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 14f * dens
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    private val midTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 140, 160, 180)
        textSize = 10f * dens
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val inset = 6f * dens
        if (isLeftKnob) {
            // Arc hugs the right rim of the left circle
            arcBounds.set(-w * 0.8f, inset, w.toFloat() - inset, h.toFloat() - inset)
        } else {
            // Arc hugs the left rim of the right circle
            arcBounds.set(inset, inset, w * 1.8f, h.toFloat() - inset)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        if (isLeftKnob) {
            // Arc spanning roughly -45° to +45°
            canvas.drawArc(arcBounds, -40f, 80f, false, glowPaint)
            canvas.drawArc(arcBounds, -40f, 80f, false, arcPaint)

            // Draw top label (+)
            canvas.drawText(topLabel, w * 0.65f, h * 0.28f, textPaint)
            // Mid label (e.g. VOL)
            if (midLabel != null) {
                canvas.drawText(midLabel, w * 0.82f, h * 0.53f, midTextPaint)
            }
            // Draw bottom label (-)
            canvas.drawText(bottomLabel, w * 0.65f, h * 0.78f, textPaint)
        } else {
            // Arc spanning roughly 135° to 225°
            canvas.drawArc(arcBounds, 140f, 80f, false, glowPaint)
            canvas.drawArc(arcBounds, 140f, 80f, false, arcPaint)

            // Draw top label (Next track)
            canvas.drawText(topLabel, w * 0.35f, h * 0.28f, textPaint)
            // Mid label if any
            if (midLabel != null) {
                canvas.drawText(midLabel, w * 0.18f, h * 0.53f, midTextPaint)
            }
            // Draw bottom label (Prev track)
            canvas.drawText(bottomLabel, w * 0.35f, h * 0.78f, textPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            if (event.y < height / 2f) {
                onTopClick()
            } else {
                onBottomClick()
            }
            return true
        }
        return super.onTouchEvent(event)
    }
}
