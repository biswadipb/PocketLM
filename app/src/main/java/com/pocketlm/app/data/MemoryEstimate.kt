package com.pocketlm.app.data

/**
 * Rough RAM needed to run a GGUF model: the weights, the attention cache for the chosen context
 * length, and some working memory. It is an estimate, so it is only used to warn, never to block.
 */
object MemoryEstimate {

    enum class Level { Comfortable, Tight, TooBig }

    data class Result(val neededBytes: Long, val level: Level)

    private const val OVERHEAD_BYTES = 256L * 1024 * 1024
    private const val BYTES_PER_CACHE_VALUE = 2L // the cache is normally stored as 16-bit floats

    /** What a cache number takes compared with 16 bits: the 8-bit and 4-bit kinds also store a scale for each block. */
    fun cacheShare(cacheBits: Int): Double = when (cacheBits) {
        8 -> 8.5 / 16
        4 -> 4.5 / 16
        else -> 1.0
    }

    /** Size of the key/value cache. Without model details a generous guess is used. */
    fun cacheBytes(info: ModelInfo?, contextSize: Int, cacheBits: Int = 16): Long {
        val layers = info?.layers?.toLong() ?: return (contextSize * 128L * 1024 * cacheShare(cacheBits)).toLong()
        val kvWidth: Long = when {
            info.headCountKv != null && info.keyLength != null ->
                (info.keyLength + (info.valueLength ?: info.keyLength)).toLong() * info.headCountKv
            info.headCountKv != null && info.headCount != null && info.embeddingSize != null ->
                2L * info.embeddingSize * info.headCountKv / info.headCount
            info.embeddingSize != null -> 2L * info.embeddingSize
            else -> 4096L
        }
        return (layers * contextSize * kvWidth * BYTES_PER_CACHE_VALUE * cacheShare(cacheBits)).toLong()
    }

    fun estimate(
        fileBytes: Long,
        info: ModelInfo?,
        contextSize: Int,
        availableBytes: Long,
        totalBytes: Long,
        cacheBits: Int = 16,
    ): Result {
        val needed = fileBytes + fileBytes / 20 + cacheBytes(info, contextSize, cacheBits) + OVERHEAD_BYTES
        val level = when {
            needed > availableBytes || needed > totalBytes * 3 / 4 -> Level.TooBig
            needed > availableBytes * 7 / 10 || needed > totalBytes / 2 -> Level.Tight
            else -> Level.Comfortable
        }
        return Result(needed, level)
    }
}
