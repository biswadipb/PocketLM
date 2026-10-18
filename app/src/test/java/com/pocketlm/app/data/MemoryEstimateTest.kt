package com.pocketlm.app.data

import com.pocketlm.app.data.MemoryEstimate.Level
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryEstimateTest {

    private val gb = 1024L * 1024 * 1024

    // Shape of a 3B Llama-style model: 28 layers, 8 KV heads of width 128.
    private val llama3b = ModelInfo(
        name = null, architecture = "llama", parameters = "3B", quantisation = null,
        contextLength = 131072, layers = 28, embeddingSize = 3072, license = null,
        headCount = 24, headCountKv = 8, keyLength = 128, valueLength = 128,
    )

    @Test
    fun cacheSizeFollowsLayersContextAndKvWidth() {
        // 28 layers * 4096 tokens * (128 + 128) * 8 heads * 2 bytes
        assertEquals(28L * 4096 * 256 * 8 * 2, MemoryEstimate.cacheBytes(llama3b, 4096))
    }

    @Test
    fun cacheDoublesWhenContextDoubles() {
        assertEquals(
            2 * MemoryEstimate.cacheBytes(llama3b, 4096),
            MemoryEstimate.cacheBytes(llama3b, 8192),
        )
    }

    @Test
    fun cacheFallsBackToHeadCountsThenEmbeddingWidth() {
        val noKeyLength = llama3b.copy(keyLength = null, valueLength = null)
        // 2 * 3072 * 8 / 24 = 2048 per layer per token
        assertEquals(28L * 4096 * 2048 * 2, MemoryEstimate.cacheBytes(noKeyLength, 4096))

        val bare = llama3b.copy(keyLength = null, valueLength = null, headCountKv = null, headCount = null)
        assertEquals(28L * 4096 * 6144 * 2, MemoryEstimate.cacheBytes(bare, 4096))
    }

    @Test
    fun unknownModelStillGetsAGenerousGuess() {
        assertTrue(MemoryEstimate.cacheBytes(null, 4096) > 0)
    }

    @Test
    fun smallModelOnABigPhoneIsComfortable() {
        val r = MemoryEstimate.estimate(400L * 1024 * 1024, null, 4096, availableBytes = 4 * gb, totalBytes = 8 * gb)
        assertEquals(Level.Comfortable, r.level)
    }

    @Test
    fun modelThatNeedsMoreThanIsFreeIsTooBig() {
        val r = MemoryEstimate.estimate(2 * gb, llama3b, 4096, availableBytes = 2 * gb, totalBytes = 6 * gb)
        assertEquals(Level.TooBig, r.level)
    }

    @Test
    fun usingMostOfTheFreeMemoryIsTight() {
        val needed = MemoryEstimate.estimate(2 * gb, llama3b, 4096, availableBytes = 100 * gb, totalBytes = 100 * gb).neededBytes
        val r = MemoryEstimate.estimate(2 * gb, llama3b, 4096, availableBytes = needed * 10 / 8, totalBytes = 100 * gb)
        assertEquals(Level.Tight, r.level)
    }

    @Test
    fun largerContextRaisesTheNeed() {
        val small = MemoryEstimate.estimate(2 * gb, llama3b, 2048, 10 * gb, 10 * gb).neededBytes
        val large = MemoryEstimate.estimate(2 * gb, llama3b, 16384, 10 * gb, 10 * gb).neededBytes
        assertTrue(large > small)
    }
}
