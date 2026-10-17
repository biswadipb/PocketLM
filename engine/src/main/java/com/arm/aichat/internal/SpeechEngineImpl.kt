package com.arm.aichat.internal

import android.util.Log
import com.arm.aichat.InferenceEngine
import com.arm.aichat.SpeechEngine
import com.arm.aichat.SpeechException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** Native side in speech.cpp. All calls run one after another on their own thread, after the chat engine has set up the backends. */
internal class SpeechEngineImpl(private val chat: InferenceEngine) : SpeechEngine {

    companion object {
        private const val TAG = "SpeechEngineImpl"

        @Volatile
        private var instance: SpeechEngineImpl? = null

        internal fun getInstance(chat: InferenceEngine): SpeechEngineImpl =
            instance ?: synchronized(this) { instance ?: SpeechEngineImpl(chat).also { instance = it } }
    }

    private external fun nativeLoad(model: String, projector: String, threads: Int): Int
    private external fun nativeTranscribe(samples: FloatArray, maxTokens: Int): String?
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
        if (state is InferenceEngine.State.Error) throw SpeechException("The engine could not start on this phone.")
        withContext(dispatcher) {
            loadedPath = null
            sampleRate = 0
            val result = try {
                nativeLoad(modelPath, projectorPath, threads)
            } catch (e: UnsatisfiedLinkError) {
                throw SpeechException("The engine could not start on this phone.")
            }
            if (result <= 0) {
                Log.w(TAG, "Could not load the speech model, code $result")
                throw SpeechException(
                    when (result) {
                        -2 -> "The speech model's audio add-on could not be loaded. Download both files again."
                        -3 -> "That model cannot listen to audio. Choose a speech model such as Qwen3-ASR."
                        else -> "The speech model could not be loaded."
                    },
                )
            }
            loadedPath = modelPath
            sampleRate = result
        }
    }

    override suspend fun transcribe(samples: FloatArray, maxTokens: Int): String = withContext(dispatcher) {
        if (loadedPath == null) throw SpeechException("No speech model is loaded.")
        nativeTranscribe(samples, maxTokens) ?: throw SpeechException("The recording could not be turned into text.")
    }

    override suspend fun unload() {
        withContext(dispatcher) {
            nativeUnload()
            loadedPath = null
            sampleRate = 0
        }
    }
}
