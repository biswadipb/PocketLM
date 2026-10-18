package com.pocketlm.app.data

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Listens for one spoken sentence at a time without showing any screen, so it can be used over and over in a
 * hands-free conversation. Uses the phone's on-device recogniser when it has one. Call from the main thread.
 */
class VoiceListener(
    private val context: Context,
    private val onPartial: (String) -> Unit,
    private val onResult: (VoiceLoop.Event) -> Unit,
) {
    private var recognizer: SpeechRecognizer? = null

    /** Starts listening for one sentence; the outcome arrives through [onResult] once. */
    fun listen() {
        stop()
        val created = try {
            if (Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            } else {
                SpeechRecognizer.createSpeechRecognizer(context)
            }
        } catch (e: Exception) {
            onResult(VoiceLoop.Event.Failed("This phone has no speech recognition.", fatal = true))
            return
        }
        recognizer = created
        var finished = false
        fun finish(event: VoiceLoop.Event) {
            if (finished) return
            finished = true
            onResult(event)
        }
        created.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(onPartial)
            }

            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                finish(VoiceLoop.Event.Heard(text))
            }

            override fun onError(error: Int) = finish(VoiceLoop.failure(error))
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }
        try {
            created.startListening(intent)
        } catch (e: Exception) {
            finish(VoiceLoop.Event.Failed("Speech recognition could not start.", fatal = true))
        }
    }

    fun stop() {
        recognizer?.let {
            runCatching { it.cancel() }
            runCatching { it.destroy() }
        }
        recognizer = null
    }

    companion object {
        fun available(context: Context): Boolean = SpeechRecognizer.isRecognitionAvailable(context)
    }
}
