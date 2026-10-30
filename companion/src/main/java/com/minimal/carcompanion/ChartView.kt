package com.minimal.carcompanion

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor

/** Small line chart, newest on the right. */
class ChartView(context: Context) : View(context) {

    /** (label under the point, value). */
    var points: List<Pair<String, Float>> = emptyList()
        set(v) { field = v; invalidate() }

    var emptyText = "Appears after two full-tank fill-ups with litres entered"
    /** Format for the value above each point. */
    var valueFormat = "%.1f"
    /** Grid step label format. */
    var axisFormat = "%.0f"
    /** Minimum span of the y axis, so small wobbles don't look dramatic. */
    var minSpan = 2f

    private val dens = resources.displayMetrics.density
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3f * dens; color = Color.rgb(34, 211, 238)
        strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = com.minimal.carlauncher.core.Neon.VIOLET }
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(40, 255, 255, 255); strokeWidth = dens }
    private val axis = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = com.minimal.carlauncher.core.Neon.DIM; textSize = 11f * dens; textAlign = Paint.Align.RIGHT
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = com.minimal.carlauncher.core.Neon.DIM; textSize = 11f * dens; textAlign = Paint.Align.CENTER
    }
    private val value = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 12f * dens; textAlign = Paint.Align.CENTER; isFakeBoldText = true
    }
    private val empty = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = com.minimal.carlauncher.core.Neon.DIM; textSize = 13f * dens; textAlign = Paint.Align.CENTER
    }
    private val path = Path()

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (points.isEmpty()) {
            c.drawText(emptyText, w / 2, h / 2, empty)
            return
        }
        val left = 40f * dens; val right = w - 20f * dens
        val top = 22f * dens; val bottom = h - 22f * dens
        val vals = points.map { it.second }
        val vmin = vals.minOrNull()!!; val vmax = vals.maxOrNull()!!
        val pad = maxOf(minSpan - (vmax - vmin), 0f) / 2f + minSpan * 0.15f
        val lo = if (minSpan >= 2f) floor((vmin - pad).toDouble()).toFloat() else vmin - pad
        val hi = if (minSpan >= 2f) ceil((vmax + pad).toDouble()).toFloat() else vmax + pad
        fun y(v: Float) = bottom - (v - lo) / (hi - lo) * (bottom - top)
        fun x(i: Int) = if (points.size == 1) (left + right) / 2 else left + i * (right - left) / (points.size - 1)

        for (k in 0..2) {
            val v = lo + (hi - lo) * k / 2f
            c.drawLine(left, y(v), right, y(v), grid)
            c.drawText(String.format(Locale.US, axisFormat, v), left - 6f * dens, y(v) + 4f * dens, axis)
        }
        path.reset()
        points.forEachIndexed { i, p -> if (i == 0) path.moveTo(x(i), y(p.second)) else path.lineTo(x(i), y(p.second)) }
        // Neon gradient along the line, left (oldest) to right (newest).
        line.shader = android.graphics.LinearGradient(0f, 0f, w, 0f,
            com.minimal.carlauncher.core.Neon.GRADIENT, null, android.graphics.Shader.TileMode.CLAMP)
        if (points.size > 1) c.drawPath(path, line)
        points.forEachIndexed { i, p ->
            c.drawCircle(x(i), y(p.second), 4.5f * dens, dot)
            c.drawText(String.format(Locale.US, valueFormat, p.second), x(i), y(p.second) - 9f * dens, value)
            c.drawText(p.first, x(i), h - 6f * dens, label)
        }
    }
}
