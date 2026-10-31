package com.minimal.carlauncher.ui.view

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.widget.ImageView
import androidx.core.content.ContextCompat
import com.minimal.carlauncher.core.Neon

/**
 * Paints a vector icon with the neon gradient (cyan -> violet -> pink) in dark mode: the icon
 * is drawn as a mask and the gradient is kept only where the icon is. Light mode keeps the
 * plain accent tint.
 */
object NeonIcons {

    fun apply(view: ImageView, resId: Int) {
        val night = (view.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        if (!night) { view.setImageResource(resId); return }
        view.post {
            val w = view.width - view.paddingLeft - view.paddingRight
            val h = view.height - view.paddingTop - view.paddingBottom
            if (w <= 0 || h <= 0) { view.setImageResource(resId); return@post }
            val d = ContextCompat.getDrawable(view.context, resId)?.mutate() ?: return@post
            d.setTintList(null)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            d.setBounds(0, 0, w, h)
            d.draw(c)
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), Neon.GRADIENT, null, Shader.TileMode.CLAMP)
                xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
            }
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
            view.imageTintList = null
            view.setImageBitmap(bmp)
        }
    }
}
