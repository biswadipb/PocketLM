package com.arm.aichat.internal

import android.content.Context
import android.util.Log
import com.arm.aichat.ContextUsage
import com.arm.aichat.GenerationParams
import com.arm.aichat.InferenceEngine
import com.arm.aichat.PromptNotProcessed
import com.arm.aichat.isModelLoaded
import com.arm.aichat.UnsupportedArchitectureException
import com.arm.aichat.internal.InferenceEngineImpl.Companion.getInstance
import dalvik.annotation.optimization.FastNative
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * JNI wrapper for the llama.cpp library providing Android-friendly access to large language models.
 *
 * This class implements a singleton pattern for managing the lifecycle of a single LLM instance.
 * All operations are executed on a dedicated single-threaded dispatcher to ensure thread safety
 * with the underlying C++ native code.
 *
 * The typical usage flow is:
 * 1. Get instance via [getInstance]
 * 2. Load a model with [loadModel]
 * 3. Send prompts with [sendUserPrompt]
 * 4. Generate responses as token streams
 * 5. Perform [cleanUp] when done with a model
 * 6. Properly [destroy] when completely done
 *
 * State transitions are managed automatically and validated at each operation.
 *
 * @see ai_chat.cpp for the native implementation details
 */
internal class InferenceEngineImpl private constructor(
    private val nativeLibDir: String
) : InferenceEngine {

    companion object {
        private val TAG = InferenceEngineImpl::class.java.simpleName

        @Volatile
        private var instance: InferenceEngine? = null

        /**
         * Create or obtain [InferenceEngineImpl]'s single instance.
         *
         * @param Context for obtaining native library directory
         * @throws IllegalArgumentException if native library path is invalid
         * @throws UnsatisfiedLinkError if library failed to load
         */
        internal fun getInstance(context: Context) =
            instance ?: synchronized(this) {
                val nativeLibDir = context.applicationInfo.nativeLibraryDir
                require(nativeLibDir.isNotBlank()) { "Expected a valid native library path!" }

                try {
                    Log.i(TAG, "Instantiating InferenceEngineImpl,,,")
                    InferenceEngineImpl(nativeLibDir).also { instance = it }
                } catch (e: UnsatisfiedLinkError) {
                    Log.e(TAG, "Failed to load native library from $nativeLibDir", e)
                    throw e
                }
            }
    }

    /**
     * JNI methods
     * @see ai_chat.cpp
     */
    @FastNative
    private external fun init(nativeLibDir: String)

    @FastNative
    private external fun load(modelPath: String): Int

    @FastNative
    private external fun prepare(
        nCtx: Int, nThreads: Int, temp: Float, topP: Float,
        topK: Int, minP: Float, repeatPenalty: Float, seed: Int,
    ): Int

    @FastNative
    private external fun setGrammar(grammar: String): Int

    @FastNative
    private external fun setSampling(temperature: Float, topP: Float): Int

    @FastNative
    private external fun contextUsed(): Int

    @FastNative
    private external fun contextTotal(): Int

    @FastNative
    private external fun lastReplyCut(): Boolean

    @FastNative
    private external fun loadProjector(path: String): Int

    @FastNative
    private external fun processUserPromptWithImage(prompt: String, imagePath: String, nPredict: Int): Int

    @FastNative
    private external fun systemInfo(): String

    @FastNative
    private external fun benchModel(pp: Int, tg: Int, pl: Int, nr: Int): String

    @FastNative
    private external fun processSystemPrompt(systemPrompt: String): Int

    @FastNative
    private external fun processUserPrompt(userPrompt: String, predictLength: Int): Int

    @FastNative
    private external fun generateNextToken(): String?

    @FastNative
    private external fun unload()

    @FastNative
    private external fun shutdown()

    private val _state =
        MutableStateFlow<InferenceEngine.State>(InferenceEngine.State.Uninitialized)
    override val state: StateFlow<InferenceEngine.State> = _state.asStateFlow()

    private var _readyForSystemPrompt = false
    @Volatile
    private var _cancelGeneration = false

    /**
     * Single-threaded coroutine dispatcher & scope for LLama asynchronous operations
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val llamaDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val llamaScope = CoroutineScope(llamaDispatcher + SupervisorJob())

    init {
        llamaScope.launch {
            try {
                check(_state.value is InferenceEngine.State.Uninitialized) {
                    "Cannot load native library in ${_state.value.javaClass.simpleName}!"
                }
                _state.value = InferenceEngine.State.Initializing
                Log.i(TAG, "Loading native library...")
                System.loadLibrary("ai-chat")
                init(nativeLibDir)
                _state.value = InferenceEngine.State.Initialized
                Log.i(TAG, "Native library loaded! System info: \n${systemInfo()}")

            } catch (e: Exception) {
                Log.e(TAG, "Failed to load native library", e)
                throw e
            }
        }
    }

    /**
     * Load the LLM
     */
    override suspend fun loadModel(pathToModel: String, params: GenerationParams) =
        withContext(llamaDispatcher) {
            check(_state.value is InferenceEngine.State.Initialized) {
                "Cannot load model in ${_state.value.javaClass.simpleName}!"
            }

            try {
                Log.i(TAG, "Checking access to model file... \n$pathToModel")
                File(pathToModel).let {
                    require(it.exists()) { "File not found" }
                    require(it.isFile) { "Not a valid file" }
                    require(it.canRead()) { "Cannot read file" }
                }

                Log.i(TAG, "Loading model... \n$pathToModel")
                _readyForSystemPrompt = false
                _state.value = InferenceEngine.State.LoadingModel
                load(pathToModel).let {
                    // TODO-han.yin: find a better way to pass other error codes
                    if (it != 0) throw UnsupportedArchitectureException()
                }
                prepare(
                    params.contextSize, params.threads, params.temperature, params.topP,
                    params.topK, params.minP, params.repeatPenalty, params.seed,
                ).let {
                    if (it != 0) throw IOException("Failed to prepare resources")
                }
                Log.i(TAG, "Model loaded!")
                _readyForSystemPrompt = true

                _cancelGeneration = false
                _state.value = InferenceEngine.State.ModelReady
            } catch (e: Exception) {
                Log.e(TAG, (e.message ?: "Error loading model") + "\n" + pathToModel, e)
                _state.value = InferenceEngine.State.Error(e)
                throw e
            }
        }

    /**
     * Process the plain text system prompt
     *
     * TODO-han.yin: return error code if system prompt not correct processed?
     */
    override suspend fun setSystemPrompt(prompt: String) =
        withContext(llamaDispatcher) {
            require(prompt.isNotBlank()) { "Cannot process empty system prompt!" }
            check(_readyForSystemPrompt) { "System prompt must be set ** RIGHT AFTER ** model loaded!" }
            check(_state.value is InferenceEngine.State.ModelReady) {
                "Cannot process system prompt in ${_state.value.javaClass.simpleName}!"
            }

            Log.i(TAG, "Sending system prompt...")
            _readyForSystemPrompt = false
            _state.value = InferenceEngine.State.ProcessingSystemPrompt
            processSystemPrompt(prompt).let { result ->
                if (result != 0) {
                    RuntimeException("Failed to process system prompt: $result").also {
                        _state.value = InferenceEngine.State.Error(it)
                        throw it
                    }
                }
            }
            Log.i(TAG, "System prompt processed! Awaiting user prompt...")
            _state.value = InferenceEngine.State.ModelReady
        }

    /**
     * Send plain text user prompt to LLM, which starts generating tokens in a [Flow]
     */
    override fun sendUserPrompt(
        message: String,
        predictLength: Int,
    ): Flow<String> {
        require(message.isNotEmpty()) { "User prompt discarded due to being empty!" }
        return generateReply(::textPromptProblem) { processUserPrompt(message, predictLength) }
    }

    override fun sendUserPromptWithImage(
        message: String,
        imagePath: String,
        predictLength: Int,
    ): Flow<String> {
        require(File(imagePath).isFile) { "Image not found" }
        return generateReply(::imagePromptProblem) { processUserPromptWithImage(message, imagePath, predictLength) }
    }

    override suspend fun resetConversation(systemPrompt: String) {
        withContext(llamaDispatcher) {
            require(systemPrompt.isNotBlank()) { "Cannot process empty system prompt!" }
            check(_state.value is InferenceEngine.State.ModelReady) {
                "Cannot reset the conversation in ${_state.value.javaClass.simpleName}!"
            }
            _readyForSystemPrompt = false
            _state.value = InferenceEngine.State.ProcessingSystemPrompt
            val result = processSystemPrompt(systemPrompt)
            if (result != 0) {
                RuntimeException("Failed to reset the conversation: $result").also {
                    _state.value = InferenceEngine.State.Error(it)
                    throw it
                }
            }
            _state.value = InferenceEngine.State.ModelReady
        }
    }

    override fun contextUsage(): ContextUsage =
        if (_state.value.isModelLoaded) ContextUsage(contextUsed(), contextTotal()) else ContextUsage(0, 0)

    override fun lastReplyWasCut(): Boolean = _state.value.isModelLoaded && lastReplyCut()

    override suspend fun updateSampling(temperature: Float, topP: Float) {
        withContext(llamaDispatcher) {
            check(_state.value is InferenceEngine.State.ModelReady) { "Load a model first" }
            val result = setSampling(temperature, topP)
            if (result != 0) Log.w(TAG, "Could not change the sampling, code $result")
        }
    }

    override suspend fun setOutputGrammar(gbnf: String) {
        withContext(llamaDispatcher) {
            check(_state.value is InferenceEngine.State.ModelReady) { "Load a model first" }
            val result = setGrammar(gbnf)
            if (result != 0) Log.w(TAG, "Could not set the output grammar, code $result")
        }
    }

    override suspend fun loadVisionProjector(pathToProjector: String): Boolean =
        withContext(llamaDispatcher) {
            check(_state.value is InferenceEngine.State.ModelReady) {
                "Load the model before its vision projector"
            }
            val result = loadProjector(pathToProjector)
            if (result != 0) Log.w(TAG, "Vision projector not loaded, code $result")
            result == 0
        }

    private fun textPromptProblem(code: Int) = "The model could not read that message (error $code)."

    private fun imagePromptProblem(code: Int) = when (code) {
        1 -> "This model has no vision add-on loaded."
        2 -> "The image could not be read."
        3 -> "The image could not be prepared for this model."
        4 -> "The image does not fit in what is left of the conversation. Start a new chat or raise the context length in Settings."
        else -> "The model could not read that image (error $code)."
    }

    /**
     * Runs [processPrompt] on the native side, then streams the generated tokens.
     */
    private fun generateReply(describe: (Int) -> String, processPrompt: () -> Int): Flow<String> = flow {
        check(_state.value is InferenceEngine.State.ModelReady) {
            "User prompt discarded due to: ${_state.value.javaClass.simpleName}"
        }

        try {
            Log.i(TAG, "Sending user prompt...")
            _readyForSystemPrompt = false
            _state.value = InferenceEngine.State.ProcessingUserPrompt

            val result = processPrompt()
            if (result != 0) {
                Log.e(TAG, "Failed to process user prompt: $result")
                throw PromptNotProcessed(describe(result))
            }

            Log.i(TAG, "User prompt processed. Generating assistant prompt...")
            _state.value = InferenceEngine.State.Generating
            while (!_cancelGeneration) {
                generateNextToken()?.let { utf8token ->
                    if (utf8token.isNotEmpty()) emit(utf8token)
                } ?: break
            }
            if (_cancelGeneration) {
                Log.i(TAG, "Assistant generation aborted per requested.")
            } else {
                Log.i(TAG, "Assistant generation complete. Awaiting user prompt...")
            }
            _state.value = InferenceEngine.State.ModelReady
        } catch (e: CancellationException) {
            Log.i(TAG, "Assistant generation's flow collection cancelled.")
            _state.value = InferenceEngine.State.ModelReady
            throw e
        } catch (e: PromptNotProcessed) {
            // The model is still fine; only this prompt could not be used.
            _state.value = InferenceEngine.State.ModelReady
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error during generation!", e)
            _state.value = InferenceEngine.State.Error(e)
            throw e
        }
    }.flowOn(llamaDispatcher)

    /**
     * Benchmark the model
     */
    override suspend fun bench(pp: Int, tg: Int, pl: Int, nr: Int): String =
        withContext(llamaDispatcher) {
            check(_state.value is InferenceEngine.State.ModelReady) {
                "Benchmark request discarded due to: $state"
            }
            Log.i(TAG, "Start benchmark (pp: $pp, tg: $tg, pl: $pl, nr: $nr)")
            _readyForSystemPrompt = false   // Just to be safe
            _state.value = InferenceEngine.State.Benchmarking
            benchModel(pp, tg, pl, nr).also {
                _state.value = InferenceEngine.State.ModelReady
            }
        }

    /**
     * Unloads the model and frees resources, or reset error states
     */
    override fun cleanUp() {
        _cancelGeneration = true
        runBlocking(llamaDispatcher) {
            when (val state = _state.value) {
                is InferenceEngine.State.ModelReady -> {
                    Log.i(TAG, "Unloading model and free resources...")
                    _readyForSystemPrompt = false
                    _state.value = InferenceEngine.State.UnloadingModel

                    unload()

                    _state.value = InferenceEngine.State.Initialized
                    Log.i(TAG, "Model unloaded!")
                    Unit
                }

                is InferenceEngine.State.Error -> {
                    Log.i(TAG, "Resetting error states...")
                    _state.value = InferenceEngine.State.Initialized
                    Log.i(TAG, "States reset!")
                    Unit
                }

                else -> throw IllegalStateException("Cannot unload model in ${state.javaClass.simpleName}")
            }
        }
    }

    /**
     * Cancel all ongoing coroutines and free GGML backends
     */
    override fun destroy() {
        _cancelGeneration = true
        runBlocking(llamaDispatcher) {
            _readyForSystemPrompt = false
            when(_state.value) {
                is InferenceEngine.State.Uninitialized -> {}
                is InferenceEngine.State.Initialized -> shutdown()
                else -> { unload(); shutdown() }
            }
        }
        llamaScope.cancel()
    }
}
