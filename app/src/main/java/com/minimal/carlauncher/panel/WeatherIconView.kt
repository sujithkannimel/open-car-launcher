package com.minimal.carlauncher.panel

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Tiny phone-status-bar style weather glyph, drawn with paths so it stays crisp at any size:
 * sun, moon, cloud, sun/moon behind cloud, rain, drizzle, thunder, fog, snow.
 */
class WeatherIconView(context: Context) : View(context) {

    var kind: WeatherKind = WeatherKind.CLOUDY
        set(v) { if (field != v) { field = v; invalidate() } }
    var isDay: Boolean = true
        set(v) { if (field != v) { field = v; invalidate() } }

    private val sun = Color.rgb(251, 191, 36)
    private val moon = Color.rgb(203, 213, 225)
    private val cloud = Color.rgb(148, 163, 184)
    private val rain = Color.rgb(34, 211, 238)
    private val bolt = Color.rgb(250, 204, 21)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val path = Path()
    private val rect = RectF()

    override fun onDraw(c: Canvas) {
        val s = min(width, height).toFloat()
        val ox = (width - s) / 2f
        val oy = (height - s) / 2f
        c.save()
        c.translate(ox, oy)
        stroke.strokeWidth = s * 0.085f
        when (kind) {
            WeatherKind.CLEAR -> if (isDay) drawSun(c, s * 0.5f, s * 0.5f, s * 0.22f, true) else drawMoon(c, s * 0.5f, s * 0.5f, s * 0.34f)
            WeatherKind.PARTLY -> {
                if (isDay) drawSun(c, s * 0.36f, s * 0.36f, s * 0.15f, true) else drawMoon(c, s * 0.36f, s * 0.34f, s * 0.24f)
                drawCloud(c, s, s * 0.08f, s * 0.12f)
            }
            WeatherKind.CLOUDY -> drawCloud(c, s, 0f, 0f)
            WeatherKind.FOG -> {
                drawCloud(c, s, 0f, -s * 0.1f)
                stroke.color = cloud
                c.drawLine(s * 0.18f, s * 0.82f, s * 0.82f, s * 0.82f, stroke)
                c.drawLine(s * 0.28f, s * 0.95f, s * 0.72f, s * 0.95f, stroke)
            }
            WeatherKind.DRIZZLE, WeatherKind.RAIN -> {
                drawCloud(c, s, 0f, -s * 0.12f)
                stroke.color = rain
                val n = if (kind == WeatherKind.RAIN) 3 else 2
                for (i in 0 until n) {
                    val x = s * (0.32f + i * (0.36f / (n - 1).coerceAtLeast(1)))
                    c.drawLine(x, s * 0.74f, x - s * 0.07f, s * 0.94f, stroke)
                }
            }
            WeatherKind.THUNDER -> {
                drawCloud(c, s, 0f, -s * 0.12f)
                fill.color = bolt
                path.reset()
                path.moveTo(s * 0.54f, s * 0.62f)
                path.lineTo(s * 0.38f, s * 0.82f)
                path.lineTo(s * 0.5f, s * 0.82f)
                path.lineTo(s * 0.44f, s * 1.0f)
                path.lineTo(s * 0.64f, s * 0.76f)
                path.lineTo(s * 0.52f, s * 0.76f)
                path.close()
                c.drawPath(path, fill)
            }
            WeatherKind.SNOW -> {
                drawCloud(c, s, 0f, -s * 0.12f)
                fill.color = Color.WHITE
                for (i in 0..2) c.drawCircle(s * (0.32f + i * 0.18f), s * 0.86f, s * 0.05f, fill)
            }
        }
        c.restore()
    }

    private fun drawSun(c: Canvas, cx: Float, cy: Float, r: Float, rays: Boolean) {
        fill.color = sun
        c.drawCircle(cx, cy, r, fill)
        if (!rays) return
        stroke.color = sun
        for (i in 0 until 8) {
            val a = Math.toRadians(i * 45.0)
            val r1 = r * 1.45f; val r2 = r * 1.95f
            c.drawLine(
                cx + (r1 * cos(a)).toFloat(), cy + (r1 * sin(a)).toFloat(),
                cx + (r2 * cos(a)).toFloat(), cy + (r2 * sin(a)).toFloat(), stroke
            )
        }
    }

    /** Crescent: a disc with an offset disc cut out. */
    private fun drawMoon(c: Canvas, cx: Float, cy: Float, r: Float) {
        fill.color = moon
        path.reset()
        path.addCircle(cx, cy, r, Path.Direction.CW)
        val cut = Path().apply { addCircle(cx + r * 0.55f, cy - r * 0.35f, r * 0.85f, Path.Direction.CW) }
        path.op(cut, Path.Op.DIFFERENCE)
        c.drawPath(path, fill)
    }

    private fun drawCloud(c: Canvas, s: Float, dx: Float, dy: Float) {
        fill.color = cloud
        path.reset()
        path.addCircle(s * 0.36f + dx, s * 0.6f + dy, s * 0.17f, Path.Direction.CW)
        path.addCircle(s * 0.55f + dx, s * 0.5f + dy, s * 0.22f, Path.Direction.CW)
        path.addCircle(s * 0.72f + dx, s * 0.62f + dy, s * 0.15f, Path.Direction.CW)
        rect.set(s * 0.2f + dx, s * 0.6f + dy, s * 0.86f + dx, s * 0.77f + dy)
        path.addRoundRect(rect, s * 0.08f, s * 0.08f, Path.Direction.CW)
        c.drawPath(path, fill)
    }
}
