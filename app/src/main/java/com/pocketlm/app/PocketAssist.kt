package com.pocketlm.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.app.assist.AssistStructure
import android.content.Intent
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import com.pocketlm.app.data.ScreenNode
import com.pocketlm.app.data.ScreenText
import java.io.File
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

/**
 * Runs when the assistant gesture is used. If the phone passes along what is on the screen (the user allows that in
 * the phone's assistant settings), PocketLM opens a fresh chat with the screen's text and a screenshot attached;
 * otherwise it just opens the chat with the message box focused.
 */
class PocketAssistSession(context: Context) : VoiceInteractionSession(context) {

    private val handler = Handler(Looper.getMainLooper())
    private var wantsText = false
    private var wantsShot = false
    private var gotText = false
    private var gotShot = false
    private var screenText: String? = null
    private var screenshot: String? = null
    private var launched = false

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        wantsText = showFlags and SHOW_WITH_ASSIST != 0
        wantsShot = showFlags and SHOW_WITH_SCREENSHOT != 0
        gotText = false
        gotShot = false
        screenText = null
        screenshot = null
        launched = false
        if (!wantsText && !wantsShot) {
            launch()
        } else {
            // The screen's contents arrive a moment after the session is shown, if they arrive at all.
            handler.postDelayed(::launch, WAIT_MS)
        }
    }

    override fun onHandleAssist(state: AssistState) {
        if (state.index == 0) {
            val structure = state.assistStructure
            screenText = structure?.let { ScreenText.extract(it.toScreenNodes()) }?.takeIf { it.isNotBlank() }
        }
        gotText = true
        launchIfComplete()
    }

    override fun onHandleScreenshot(screenshot: Bitmap?) {
        this.screenshot = screenshot?.let(::saveScreenshot)
        gotShot = true
        launchIfComplete()
    }

    private fun launchIfComplete() {
        if ((!wantsText || gotText) && (!wantsShot || gotShot)) launch()
    }

    private fun launch() {
        if (launched) return
        launched = true
        handler.removeCallbacksAndMessages(null)
        val intent = Intent(context, MainActivity::class.java)
        if (screenText != null || screenshot != null) {
            intent.setAction(IntentActions.ASK_SCREEN)
                .putExtra(IntentActions.EXTRA_SCREEN_TEXT, screenText?.take(ScreenText.MAX_CHARS))
                .putExtra(IntentActions.EXTRA_SCREENSHOT, screenshot)
        } else {
            intent.setAction(IntentActions.ASK)
        }
        startAssistantActivity(intent)
        hide()
    }

    /** Saves the screenshot, shrunk to a size a model can use, in the cache; the chat deletes it once it is attached. */
    private fun saveScreenshot(bitmap: Bitmap): String? = try {
        val scale = minOf(1f, MAX_SHOT_SIDE.toFloat() / maxOf(bitmap.width, bitmap.height))
        val small = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true) else bitmap
        val file = File(context.cacheDir, "screen-ask.jpg")
        file.outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        file.absolutePath
    } catch (e: Exception) {
        null
    }

    private companion object {
        const val WAIT_MS = 1_500L
        const val MAX_SHOT_SIDE = 1600
    }
}

/** What the assistant API says is on screen, as plain nodes the text extraction can read. */
private fun AssistStructure.toScreenNodes(): List<ScreenNode> =
    (0 until windowNodeCount).mapNotNull { i -> getWindowNodeAt(i).rootViewNode?.toScreenNode() }

private fun AssistStructure.ViewNode.toScreenNode(): ScreenNode {
    val klass = inputType and InputType.TYPE_MASK_CLASS
    val variation = inputType and InputType.TYPE_MASK_VARIATION
    val password = (klass == InputType.TYPE_CLASS_TEXT &&
        (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)) ||
        (klass == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
    return ScreenNode(
        text = text?.toString(),
        description = contentDescription?.toString(),
        visible = visibility == View.VISIBLE,
        sensitive = password || isAssistBlocked,
        children = (0 until childCount).map { getChildAt(it).toScreenNode() },
    )
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
