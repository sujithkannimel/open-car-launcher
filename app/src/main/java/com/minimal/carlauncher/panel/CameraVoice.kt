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
                pending?.let { pending = null; speakNow(it) }
            } else {
                failed = true
                pending?.let { pending = null; beep(1) }
            }
        }
    } catch (_: Exception) { failed = true; null }
    private val ttsRef get() = tts

    private val tone: ToneGenerator? by lazy {
        try { ToneGenerator(AudioManager.STREAM_MUSIC, 100) } catch (_: Exception) { null }
    }

    /** Says [text] (or beeps [beeps] times) as the Cameras setting allows. */
    fun alert(text: String, beeps: Int, mode: String = Prefs.camAlert) {
        when (mode) {
            "off" -> return
            "beep" -> beep(beeps)
            else -> when {
                ready -> speakNow(text)
                failed || tts == null -> beep(beeps)
                else -> pending = text          // engine still starting: say it as soon as it's up
            }
        }
    }

    private fun speakNow(text: String) {
        val p = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1f) }
        val r = try { tts?.speak(text, TextToSpeech.QUEUE_FLUSH, p, "cam") } catch (_: Exception) { TextToSpeech.ERROR }
        if (r != TextToSpeech.SUCCESS) beep(1)
    }

    private fun beep(times: Int) {
        try { tone?.startTone(if (times > 1) ToneGenerator.TONE_PROP_BEEP2 else ToneGenerator.TONE_PROP_BEEP, 250) } catch (_: Exception) {}
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
