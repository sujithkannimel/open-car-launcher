package com.minimal.carlauncher.core

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

/**
 * What the phone's music app is playing (YouTube Music through Android Auto), as reported by
 * the phone app over the live link, and the car's button presses waiting to go back to it.
 * Fed by LiveLink on the panel service; read by the home screen's Music card. Same process.
 */
object PhoneMedia {

    @Volatile var np: CarLink.NowPlaying? = null
        private set
    @Volatile var art: Bitmap? = null
        private set
    @Volatile var artKey: String = ""
        private set
    @Volatile var at: Long = 0L
        private set
    @Volatile var pendingCmd: CarLink.Cmd? = null
        private set

    /** Set by the panel service: run a link exchange now (for snappy button presses). */
    @Volatile var kick: (() -> Unit)? = null

    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }

    /** Fresh enough to show: heard from the phone in the last 20 s. */
    fun fresh(now: Long = System.currentTimeMillis()): CarLink.NowPlaying? =
        np?.takeIf { now - at < 20_000L && it.title.isNotBlank() }

    /** A reply arrived; [sent] is the command it carried (cleared once acknowledged). */
    fun onReply(reply: CarLink.NowPlaying?, sent: CarLink.Cmd?) {
        if (sent != null && pendingCmd?.id == sent.id) pendingCmd = null
        if (reply?.artB64 != null) {
            art = try {
                val b = Base64.decode(reply.artB64, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(b, 0, b.size)
            } catch (_: Exception) { null }
            artKey = reply.artKey
        } else if (reply == null || reply.artKey != artKey) {
            if (reply == null) { art = null; artKey = "" }
        }
        np = reply
        at = System.currentTimeMillis()
        main.post { listeners.forEach { it() } }
    }

    /** A tap on the car's controls: queue it for the phone and update the card at once. */
    fun command(action: String, value: Long = 0L) {
        pendingCmd = CarLink.Cmd(System.nanoTime(), action, value)
        np?.let { cur ->
            when (action) {
                "play" -> np = cur.copy(playing = true, posMs = cur.positionAt(System.currentTimeMillis()), posAt = System.currentTimeMillis())
                "pause" -> np = cur.copy(playing = false, posMs = cur.positionAt(System.currentTimeMillis()), posAt = System.currentTimeMillis())
            }
        }
        main.post { listeners.forEach { it() } }
        kick?.invoke()
    }
}
