package com.pocketlm.app.data

/**
 * Rough RAM needed to run a GGUF model: the weights, the attention cache for the chosen context
 * length, and some working memory. It is an estimate, so it is only used to warn, never to block.
 */
object MemoryEstimate {

    enum class Level { Comfortable, Tight, TooBig }

    data class Result(val neededBytes: Long, val level: Level)

    private const val OVERHEAD_BYTES = 256L * 1024 * 1024
    private const val BYTES_PER_CACHE_VALUE = 2L // the cache is stored as 16-bit floats

    /** Size of the key/value cache. Without model details a generous guess is used. */
    fun cacheBytes(info: ModelInfo?, contextSize: Int): Long {
        val layers = info?.layers?.toLong() ?: return contextSize * 128L * 1024
        val kvWidth: Long = when {
            info.headCountKv != null && info.keyLength != null ->
                (info.keyLength + (info.valueLength ?: info.keyLength)).toLong() * info.headCountKv
            info.headCountKv != null && info.headCount != null && info.embeddingSize != null ->
                2L * info.embeddingSize * info.headCountKv / info.headCount
            info.embeddingSize != null -> 2L * info.embeddingSize
            else -> 4096L
        }
        return layers * contextSize * kvWidth * BYTES_PER_CACHE_VALUE
    }

    fun estimate(
        fileBytes: Long,
        info: ModelInfo?,
        contextSize: Int,
        availableBytes: Long,
        totalBytes: Long,
    ): Result {
        val needed = fileBytes + fileBytes / 20 + cacheBytes(info, contextSize) + OVERHEAD_BYTES
        val level = when {
            needed > availableBytes || needed > totalBytes * 3 / 4 -> Level.TooBig
            needed > availableBytes * 7 / 10 || needed > totalBytes / 2 -> Level.Tight
            else -> Level.Comfortable
        }
        return Result(needed, level)
    }
}
