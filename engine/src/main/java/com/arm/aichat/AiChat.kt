package com.arm.aichat

import android.content.Context
import com.arm.aichat.internal.EmbeddingEngineImpl
import com.arm.aichat.internal.InferenceEngineImpl

/**
 * Main entry point for Arm's AI Chat library.
 */
object AiChat {
    /**
     * Get the inference engine single instance.
     */
    fun getInferenceEngine(context: Context) = InferenceEngineImpl.getInstance(context)

    /** The single embedding engine, for turning text into vectors. It shares the chat engine's native library. */
    fun getEmbeddingEngine(context: Context): EmbeddingEngine =
        EmbeddingEngineImpl.getInstance(getInferenceEngine(context))
}
