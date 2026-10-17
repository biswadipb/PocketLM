package com.arm.aichat.internal

import android.util.Log
import com.arm.aichat.EmbeddingEngine
import com.arm.aichat.EmbeddingException
import com.arm.aichat.InferenceEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Native side in embedder.cpp. All calls run one after another on their own thread. The shared library and
 * the backends are set up by the chat engine, so this waits for it first.
 */
internal class EmbeddingEngineImpl(private val chat: InferenceEngine) : EmbeddingEngine {

    companion object {
        private const val TAG = "EmbeddingEngineImpl"

        @Volatile
        private var instance: EmbeddingEngineImpl? = null

        internal fun getInstance(chat: InferenceEngine): EmbeddingEngineImpl =
            instance ?: synchronized(this) { instance ?: EmbeddingEngineImpl(chat).also { instance = it } }
    }

    private external fun nativeLoad(path: String, threads: Int, maxTokens: Int): Int
    private external fun nativeEmbed(text: String): FloatArray?
    private external fun nativeCountTokens(text: String): Int
    private external fun nativeUnload()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val dispatcher = Dispatchers.IO.limitedParallelism(1)

    @Volatile
    override var dimensions: Int = 0
        private set

    @Volatile
    override var loadedPath: String? = null
        private set

    private suspend fun waitForBackends() {
        val state = chat.state.first { it !is InferenceEngine.State.Uninitialized && it !is InferenceEngine.State.Initializing }
        if (state is InferenceEngine.State.Error) throw EmbeddingException("The engine could not start on this phone.")
    }

    override suspend fun load(path: String, threads: Int, maxTokens: Int) {
        waitForBackends()
        withContext(dispatcher) {
            dimensions = 0
            loadedPath = null
            val result = try {
                nativeLoad(path, threads, maxTokens)
            } catch (e: UnsatisfiedLinkError) {
                throw EmbeddingException("The engine could not start on this phone.")
            }
            if (result <= 0) {
                Log.w(TAG, "Could not load the embedding model, code $result")
                throw EmbeddingException(
                    when (result) {
                        -2 -> "That model does not make text embeddings. Choose an embedding model such as BGE, Nomic Embed or E5."
                        -3 -> "That kind of model cannot be used for embeddings."
                        else -> "The embedding model could not be loaded."
                    },
                )
            }
            dimensions = result
            loadedPath = path
        }
    }

    override suspend fun embed(text: String): FloatArray = withContext(dispatcher) {
        if (dimensions <= 0) throw EmbeddingException("No embedding model is loaded.")
        nativeEmbed(text) ?: throw EmbeddingException("The text could not be embedded.")
    }

    override suspend fun countTokens(text: String): Int = withContext(dispatcher) {
        if (dimensions <= 0) -1 else nativeCountTokens(text)
    }

    override suspend fun unload() {
        withContext(dispatcher) {
            nativeUnload()
            dimensions = 0
            loadedPath = null
        }
    }
}
