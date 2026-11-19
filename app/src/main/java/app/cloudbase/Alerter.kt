package app.cloudbase

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import app.cloudbase.core.airspace.AirspaceStatus
import app.cloudbase.core.airspace.AlertEvent
import app.cloudbase.core.airspace.AlertLevel
import app.cloudbase.core.airspace.VerticalRelation
import java.util.Locale
import kotlin.math.roundToInt

/** Sounds for airspace alerts: a tone pattern you can recognise without looking, then (optionally) words. */
class Alerter(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private var tone: ToneGenerator? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    fun alert(e: AlertEvent, sound: Boolean, voice: Boolean) {
        if (sound) beep(e.status.level)
        if (voice) speak(phrase(e.status), delayMs = if (sound) 900 else 0)
    }

    private fun beep(level: AlertLevel) {
        val t = tone ?: try {
            ToneGenerator(AudioManager.STREAM_ALARM, 100).also { tone = it }
        } catch (e: RuntimeException) { return } // no audio output available
        when (level) {
            AlertLevel.APPROACHING -> t.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 600)
            AlertLevel.INSIDE, AlertLevel.CEILING -> t.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 800)
            AlertLevel.NONE -> {}
        }
    }

    /** Start the speech engine ahead of need: it takes a second or more, and the first alert must not be the one that is lost. */
    fun prepare(voice: Boolean) {
        if (!voice || tts != null) return
        tts = TextToSpeech(context.applicationContext) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) tts?.language = Locale.getDefault()
        }
    }

    private fun speak(text: String, delayMs: Long) {
        prepare(true)
        // Same stream as the tones, so both follow the alarm volume rather than media volume.
        val params = Bundle().apply { putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_ALARM) }
        handler.postDelayed({
            if (ttsReady) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "alert")
        }, delayMs)
    }

    fun shutdown() {
        handler.removeCallbacksAndMessages(null)
        tone?.release(); tone = null
        tts?.shutdown(); tts = null; ttsReady = false
    }

    companion object {
        fun phrase(s: AirspaceStatus): String {
            // The spoken name is the place ("WELLINGTON"), not the designator, which is noise by ear.
            val parts = s.airspace.name.split(',').map { it.trim() }
            val what = (if (parts.size >= 2) parts.drop(1).joinToString(" ") else s.airspace.name).lowercase()
            val secs = if (s.timeToEntryS.isNaN() || s.timeToEntryS < 1) "" else ", ${s.timeToEntryS.roundToInt()} seconds"
            return when (s.level) {
                AlertLevel.INSIDE -> "Inside airspace, $what"
                AlertLevel.CEILING -> "Ceiling, $what$secs"
                AlertLevel.APPROACHING -> {
                    val where = when (s.relation) {
                        VerticalRelation.BELOW -> if (s.horizontalM == 0.0) " above" else ""
                        VerticalRelation.ABOVE -> if (s.horizontalM == 0.0) " below" else ""
                        VerticalRelation.WITHIN -> ""
                    }
                    "Approaching $what$where$secs"
                }
                AlertLevel.NONE -> ""
            }
        }
    }
}
