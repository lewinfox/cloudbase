package app.cloudbase

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
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
        // Repeats are just the tone: hearing the same sentence every 15 s is how alerts get muted.
        if (voice && !e.repeat) speak(phrase(e.status), delayMs = if (sound) 900 else 0)
    }

    private fun beep(level: AlertLevel) {
        val t = tone ?: try {
            ToneGenerator(AudioManager.STREAM_ALARM, 100).also { tone = it }
        } catch (e: RuntimeException) { return } // no audio output available
        when (level) {
            AlertLevel.NEAR -> t.startTone(ToneGenerator.TONE_PROP_BEEP2, 250)
            AlertLevel.IMMINENT -> t.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 600)
            AlertLevel.INSIDE -> t.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 800)
            AlertLevel.NONE -> {}
        }
    }

    private fun speak(text: String, delayMs: Long) {
        val engine = tts ?: TextToSpeech(context.applicationContext) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) tts?.language = Locale.getDefault()
        }.also { tts = it }
        handler.postDelayed({
            if (ttsReady) engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "alert")
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
            return when (s.level) {
                AlertLevel.INSIDE -> "Inside airspace, $what"
                AlertLevel.IMMINENT -> {
                    val t = s.timeToEntryS
                    val where = when (s.relation) {
                        VerticalRelation.BELOW -> if (s.horizontalM == 0.0) "above you" else "ahead"
                        VerticalRelation.ABOVE -> if (s.horizontalM == 0.0) "below you" else "ahead"
                        VerticalRelation.WITHIN -> "ahead"
                    }
                    if (t.isNaN()) "Airspace $where, $what" else "Airspace $where, $what, ${t.roundToInt()} seconds"
                }
                else -> "Airspace nearby, $what"
            }
        }
    }
}
