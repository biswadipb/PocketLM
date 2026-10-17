package com.arm.aichat.internal

import android.util.Log
import com.arm.aichat.InferenceEngine
import com.arm.aichat.VoiceEngine
import com.arm.aichat.VoiceException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** Native side in voice.cpp and voice_core.h. All calls run one after another on their own thread. */
internal class VoiceEngineImpl(private val chat: InferenceEngine) : VoiceEngine {

    companion object {
        private const val TAG = "VoiceEngineImpl"

        @Volatile
        private var instance: VoiceEngineImpl? = null

        internal fun getInstance(chat: InferenceEngine): VoiceEngineImpl =
            instance ?: synchronized(this) { instance ?: VoiceEngineImpl(chat).also { instance = it } }
    }

    private external fun nativeLoad(model: String, projector: String, threads: Int): Int
    private external fun nativeSpeak(text: String, language: String, speaker: String?, maxFrames: Int, topK: Int, topP: Float, seed: Int): FloatArray?
    private external fun nativeLastError(): String
    private external fun nativeCancel()
    private external fun nativeUnload()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val dispatcher = Dispatchers.IO.limitedParallelism(1)

    @Volatile
    override var loadedPath: String? = null
        private set

    @Volatile
    override var sampleRate: Int = 0
        private set

    override suspend fun load(modelPath: String, projectorPath: String, threads: Int) {
        val state = chat.state.first { it !is InferenceEngine.State.Uninitialized && it !is InferenceEngine.State.Initializing }
        if (state is InferenceEngine.State.Error) throw VoiceException("The engine could not start on this phone.")
        withContext(dispatcher) {
            loadedPath = null
            sampleRate = 0
            val result = try {
                nativeLoad(modelPath, projectorPath, threads)
            } catch (e: UnsatisfiedLinkError) {
                throw VoiceException("The engine could not start on this phone.")
            }
            if (result <= 0) {
                Log.w(TAG, "Could not load the voice model, code $result")
                throw VoiceException(
                    when (result) {
                        -2 -> "The voice model's audio add-on could not be loaded. Download both files again."
                        -3 -> "That model cannot speak. Choose a text-to-speech model such as Qwen3-TTS."
                        else -> "The voice model could not be loaded. There may not be enough free memory."
                    },
                )
            }
            loadedPath = modelPath
            sampleRate = result
        }
    }

    override suspend fun speak(text: String, language: String, speakerFile: String?, maxFrames: Int): FloatArray =
        withContext(dispatcher) {
            if (loadedPath == null) throw VoiceException("No voice model is loaded.")
            nativeSpeak(text, language, speakerFile, maxFrames, 40, 0.95f, -1)
                ?: throw VoiceException(nativeLastError().ifBlank { "The voice model could not speak that." })
        }

    override fun cancel() {
        if (loadedPath != null) nativeCancel()
    }

    override suspend fun unload() {
        withContext(dispatcher) {
            nativeUnload()
            loadedPath = null
            sampleRate = 0
        }
    }
}
