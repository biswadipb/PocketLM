package com.pocketlm.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/**
 * Lets PocketLM be chosen as the phone's default assistant. Once chosen, the gesture the phone uses for its
 * assistant (holding the home button or power button, or swiping from a corner; it varies by phone) opens
 * PocketLM ready to type, like the Quick Settings tile does.
 */
object PocketAssist {

    /** True when Android's "digital assistant app" setting points at PocketLM. */
    fun isDefault(context: Context): Boolean = try {
        Settings.Secure.getString(context.contentResolver, "assistant")?.startsWith(context.packageName + "/") == true
    } catch (e: Exception) {
        false
    }

    /** Opens the phone's own screen for choosing the assistant app. */
    fun openSettings(context: Context) {
        val attempts = listOf(
            Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
            Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        )
        for (intent in attempts) {
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (e: ActivityNotFoundException) {
                // try the next one
            }
        }
    }
}

/** Android needs this service to list PocketLM as an assistant; the work is done by the session below. */
class PocketAssistService : VoiceInteractionService()

class PocketAssistSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = PocketAssistSession(this)
}

/** Runs when the assistant gesture is used: opens the chat with the message box focused. */
class PocketAssistSession(context: Context) : VoiceInteractionSession(context) {
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        startAssistantActivity(
            Intent(context, MainActivity::class.java).setAction(IntentActions.ASK),
        )
        hide()
    }
}

/**
 * Android requires a voice assistant to name a speech recogniser. PocketLM does not recognise speech
 * (dictation uses the phone's own), so this one always reports an error and is not offered to other apps.
 */
class PocketRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        runCatching { listener?.error(SpeechRecognizer.ERROR_CLIENT) }
    }

    override fun onCancel(listener: Callback?) {}

    override fun onStopListening(listener: Callback?) {}
}
