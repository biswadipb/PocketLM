package com.arm.aichat

import com.arm.aichat.InferenceEngine.State
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Interface defining the core LLM inference operations.
 */
interface InferenceEngine {
    /**
     * Current state of the inference engine
     */
    val state: StateFlow<State>

    /**
     * Load a model from the given path.
     *
     * @param params context size, threads and sampling settings used for this session
     * @throws UnsupportedArchitectureException if model architecture not supported
     */
    suspend fun loadModel(pathToModel: String, params: GenerationParams = GenerationParams())

    /**
     * Sends a system prompt to the loaded model
     */
    suspend fun setSystemPrompt(systemPrompt: String)

    /**
     * Sends a user prompt to the loaded model and returns a Flow of generated tokens.
     */
    fun sendUserPrompt(message: String, predictLength: Int = DEFAULT_PREDICT_LENGTH): Flow<String>

    /**
     * Forgets the conversation so far and starts again with [systemPrompt]. Unlike [setSystemPrompt]
     * this works at any time while a model is loaded and idle.
     */
    suspend fun resetConversation(systemPrompt: String)

    /**
     * How much of the model's memory the conversation fills. Cheap to call at any time, even while generating.
     */
    fun contextUsage(): ContextUsage

    /** True if the most recent reply stopped because it reached its length limit rather than finishing. */
    fun lastReplyWasCut(): Boolean

    /**
     * The text most recently handed to the model (the system prompt or a message), with the chat template
     * applied. For the prompt inspector.
     */
    fun lastPromptText(): String

    /**
     * Saves the model's memory of the conversation to files starting with [pathPrefix], labelled [tag].
     * Returns the number of bytes written, [STATE_TOO_BIG] if that would exceed [maxBytes], or [STATE_FAILED]
     * (also when the conversation no longer holds [expectedTokens] tokens, so a state is never saved under the
     * label of a different conversation). Call between messages.
     */
    suspend fun saveState(pathPrefix: String, tag: String, maxBytes: Long, expectedTokens: Int): Long

    /**
     * Brings back a conversation saved with [saveState] under the same [tag], right after [loadModel].
     * Returns false if there is nothing usable; the caller then sets a system prompt as usual.
     */
    suspend fun restoreState(pathPrefix: String, tag: String): Boolean

    /**
     * Changes the temperature and top-p for further replies without reloading the model. Call between messages.
     */
    suspend fun updateSampling(temperature: Float, topP: Float)

    /**
     * Constrains further output to a GBNF grammar (for example valid JSON), or lifts the
     * constraint when [gbnf] is empty. Call between messages.
     */
    suspend fun setOutputGrammar(gbnf: String)

    /**
     * Constrains further output to JSON that follows [jsonSchema] (a JSON Schema document).
     * Returns false if the schema could not be used. Call between messages.
     */
    suspend fun setOutputSchema(jsonSchema: String): Boolean

    /**
     * Empty when no draft model was asked for; otherwise one line saying that speculative decoding is on
     * (with how many of its guesses were right so far) or why it could not be used.
     */
    fun draftStatus(): String

    /**
     * Loads a multimodal projector (an "mmproj" GGUF) for the loaded model so it can read images.
     * Call after [loadModel]. Returns false if the projector doesn't match or has no vision support.
     */
    suspend fun loadVisionProjector(pathToProjector: String): Boolean

    /**
     * Like [sendUserPrompt], but the model also sees the image at [imagePath].
     * Requires a projector loaded with [loadVisionProjector].
     */
    fun sendUserPromptWithImage(
        message: String,
        imagePath: String,
        predictLength: Int = DEFAULT_PREDICT_LENGTH,
    ): Flow<String>

    /**
     * Runs a benchmark with the specified parameters.
     */
    suspend fun bench(pp: Int, tg: Int, pl: Int, nr: Int = 1): String

    /**
     * Unloads the currently loaded model.
     */
    fun cleanUp()

    /**
     * Cleans up resources when the engine is no longer needed.
     */
    fun destroy()

    /**
     * States of the inference engine
     */
    sealed class State {
        object Uninitialized : State()
        object Initializing : State()
        object Initialized : State()

        object LoadingModel : State()
        object UnloadingModel : State()
        object ModelReady : State()

        object Benchmarking : State()
        object ProcessingSystemPrompt : State()
        object ProcessingUserPrompt : State()

        object Generating : State()

        data class Error(val exception: Exception) : State()
    }

    companion object {
        const val DEFAULT_PREDICT_LENGTH = 1024

        /** Results of [saveState] that are not a byte count. */
        const val STATE_TOO_BIG = -1L
        const val STATE_FAILED = -2L
    }
}

/**
 * Per-session generation settings. [threads] of 0 picks a sensible count for the device.
 */
data class GenerationParams(
    val contextSize: Int = 4096,
    val threads: Int = 0,
    val temperature: Float = 0.7f,
    val topP: Float = 0.95f,
    val topK: Int = 40,
    val minP: Float = 0.05f,
    val repeatPenalty: Float = 1.0f,
    /** A negative seed means a different random seed every session. */
    val seed: Int = -1,
    /** Name of a built-in chat template (for example "chatml") to use instead of the model's own; empty for its own. */
    val chatTemplate: String = "",
    /** Path of a small model that drafts tokens for the main one (speculative decoding); empty for none. */
    val draftModelPath: String = "",
)

val State.isUninterruptible
    get() = this is State.Initializing ||
        this is State.LoadingModel ||
        this is State.UnloadingModel ||
        this is State.Benchmarking ||
        this is State.ProcessingSystemPrompt ||
        this is State.ProcessingUserPrompt

val State.isModelLoaded: Boolean
    get() = this is State.ModelReady ||
        this is State.Benchmarking ||
        this is State.ProcessingSystemPrompt ||
        this is State.ProcessingUserPrompt ||
        this is State.Generating

class UnsupportedArchitectureException : Exception()

/** [used] of [total] tokens of the model's memory are taken up. [total] is 0 when no model is loaded. */
data class ContextUsage(val used: Int, val total: Int)

/** The prompt could not be processed (for example it does not fit); the loaded model is still usable. */
class PromptNotProcessed(message: String) : Exception(message)
