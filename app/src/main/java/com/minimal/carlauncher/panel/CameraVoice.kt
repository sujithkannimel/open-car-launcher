package com.minimal.carlauncher.panel

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.speech.tts.TextToSpeech
import com.minimal.carlauncher.core.Prefs
import java.util.Locale

/**
 * Speaks camera warnings over whatever is playing. Uses the navigation-voice audio type (the
 * same as a map app's directions) and never asks for audio focus, so music is not paused - it
 * keeps playing underneath. Without a speech engine it plays a beep instead, on the music
 * stream (the old notification-stream beep was inaudible over music).
 */
class CameraVoice(context: Context) {

    private val app = context.applicationContext
    @Volatile private var ready = false
    @Volatile private var failed = false
    private var pending: String? = null
    private var pendingBeeps = 1

    private val tts: TextToSpeech? = try {
        TextToSpeech(app) { status ->
            val t = ttsRef
            if (status == TextToSpeech.SUCCESS && t != null) {
                val lang = t.setLanguage(Locale("en", "IN"))
                if (lang == TextToSpeech.LANG_MISSING_DATA || lang == TextToSpeech.LANG_NOT_SUPPORTED) t.setLanguage(Locale.UK)
                t.setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                ready = true
                pending?.let { pending = null; speakNow(it, pendingBeeps) }
            } else {
                failed = true
                pending?.let { pending = null; beep(pendingBeeps) }
            }
        }
    } catch (_: Exception) { failed = true; null }
    private val ttsRef get() = tts

    private val tone: ToneGenerator? by lazy {
        try { ToneGenerator(AudioManager.STREAM_MUSIC, 100) } catch (_: Exception) { null }
    }

    /**
     * Says [text] as the Cameras setting allows, or plays a beep pattern: [beeps] 1 = first
     * alert (three beeps), 2 = final alert (two long), 3 = over the limit (rapid, twice).
     */
    fun alert(text: String, beeps: Int, mode: String = Prefs.camAlert) {
        when (mode) {
            "off" -> return
            "beep" -> beep(beeps)
            else -> when {
                ready -> speakNow(text, beeps)
                failed || tts == null -> beep(beeps)
                else -> { pending = text; pendingBeeps = beeps }   // engine still starting: say it as soon as it's up
            }
        }
    }

    private fun speakNow(text: String, beeps: Int = 1) {
        val p = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1f) }
        val r = try { tts?.speak(text, TextToSpeech.QUEUE_FLUSH, p, "cam") } catch (_: Exception) { TextToSpeech.ERROR }
        if (r != TextToSpeech.SUCCESS) beep(beeps)
    }

    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /** A pattern loud and long enough to hear over music: (on ms, off ms) steps. */
    private fun beep(kind: Int) {
        val steps = when (kind) {
            1 -> listOf(180 to 140, 180 to 140, 180 to 0)
            2 -> listOf(450 to 200, 450 to 0)
            else -> listOf(110 to 70, 110 to 70, 110 to 300, 110 to 70, 110 to 70, 110 to 0)
        }
        var at = 0L
        for ((on, off) in steps) {
            main.postDelayed({ try { tone?.startTone(ToneGenerator.TONE_DTMF_D, on) } catch (_: Exception) {} }, at)
            at += on + off
        }
    }

    fun release() {
        try { tts?.shutdown() } catch (_: Exception) {}
        try { tone?.release() } catch (_: Exception) {}
    }

    companion object {
        /** "700 metres", "1 kilometre", "1.3 kilometres": rounded to 100 m. */
        fun distanceWords(m: Double): String {
            val r = (Math.round(m / 100.0) * 100).toInt().coerceAtLeast(100)
            return when {
                r < 1000 -> "$r metres"
                r == 1000 -> "1 kilometre"
                r % 1000 == 0 -> "${r / 1000} kilometres"
                else -> String.format(Locale.US, "%.1f kilometres", r / 1000.0)
            }
        }
    }
}
