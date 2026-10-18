package com.pocketlm.app.data

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Reads replies aloud with Android's built-in voice. [onSpeaking] gets the id of the message being
 * read, or null when speech stops.
 */
class Speaker(context: Context, private val onSpeaking: (String?) -> Unit, private val onProblem: (String) -> Unit) {

    private var ready = false
    private var queued: (() -> Unit)? = null

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            queued?.invoke()
        } else {
            onProblem("This phone has no text-to-speech voice installed.")
            onSpeaking(null)
        }
        queued = null
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) = onSpeaking(utteranceId.substringBefore('#'))
            override fun onDone(utteranceId: String) {
                if (utteranceId.endsWith("#last")) onSpeaking(null)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String) = onSpeaking(null)
            override fun onStop(utteranceId: String, interrupted: Boolean) {
                if (!interrupted) onSpeaking(null)
            }
        })
    }

    fun speak(id: String, markdown: String) {
        val pieces = SpeechText.chunks(SpeechText.clean(markdown))
        if (pieces.isEmpty()) return
        val start = {
            tts.language = Locale.getDefault()
            tts.stop()
            pieces.forEachIndexed { i, piece ->
                val marker = if (i == pieces.lastIndex) "last" else i.toString()
                tts.speak(piece, TextToSpeech.QUEUE_ADD, null, "$id#$marker")
            }
        }
        if (ready) start() else queued = start
    }

    fun stop() {
        queued = null
        tts.stop()
        onSpeaking(null)
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
    }
}
