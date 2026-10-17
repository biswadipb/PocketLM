package com.arm.aichat

/** The embedding model could not be used. The message says why, in words for the user. */
class EmbeddingException(message: String) : Exception(message)

/**
 * A second, separate model that turns text into vectors, so that passages with a similar meaning land close
 * together. It does not touch the chat model.
 */
interface EmbeddingEngine {
    /** Size of the vectors the loaded model makes; 0 when none is loaded. */
    val dimensions: Int

    /** File the loaded model came from, or null. */
    val loadedPath: String?

    /**
     * Loads the model at [path]. [maxTokens] is the longest text read in one piece (longer text is cut).
     * Replaces any model loaded before.
     * @throws EmbeddingException if the file is not an embedding model, or cannot be loaded
     */
    suspend fun load(path: String, threads: Int = 0, maxTokens: Int = 512)

    /** The meaning of [text] as a vector of unit length. Needs a loaded model. */
    suspend fun embed(text: String): FloatArray

    /** How many tokens [text] is for the loaded model; -1 when none is loaded. */
    suspend fun countTokens(text: String): Int

    suspend fun unload()
}
