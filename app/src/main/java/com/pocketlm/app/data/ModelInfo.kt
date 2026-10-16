package com.pocketlm.app.data

import com.arm.aichat.gguf.FileType
import com.arm.aichat.gguf.GgufMetadata
import com.arm.aichat.gguf.GgufMetadataReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** The facts about a GGUF file worth showing in the UI. Any field can be missing. */
data class ModelInfo(
    val name: String?,
    val architecture: String?,
    val parameters: String?,
    val quantisation: String?,
    val contextLength: Int?,
    val layers: Int?,
    val embeddingSize: Int?,
    val license: String?,
) {
    /** One line for the model card, e.g. "0.5B · qwen2 · Q4_K - Medium". */
    val summary: String
        get() = listOfNotNull(parameters, architecture, quantisation).joinToString(" · ")

    companion object {
        suspend fun read(path: String): ModelInfo? = withContext(Dispatchers.IO) {
            runCatching {
                File(path).inputStream().buffered().use { GgufMetadataReader.create().readStructuredMetadata(it) }
            }.getOrNull()?.toInfo()
        }

        private fun GgufMetadata.toInfo() = ModelInfo(
            name = basic.name ?: basic.nameLabel,
            architecture = architecture?.architecture,
            parameters = basic.sizeLabel,
            quantisation = architecture?.fileType
                ?.let { FileType.fromCode(it) }
                ?.takeIf { it != FileType.UNKNOWN }
                ?.label,
            contextLength = dimensions?.contextLength,
            layers = dimensions?.blockCount,
            embeddingSize = dimensions?.embeddingSize,
            license = author?.license,
        )
    }
}
