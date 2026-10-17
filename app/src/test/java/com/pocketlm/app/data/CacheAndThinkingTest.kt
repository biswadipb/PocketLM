package com.pocketlm.app.data

import com.arm.aichat.GenerationParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CacheAndThinkingTest {

    private val info = ModelInfo(
        name = null, architecture = "llama", parameters = null, quantisation = null, contextLength = 4096,
        layers = 16, embeddingSize = 2048, license = null, headCount = 32, headCountKv = 8, keyLength = 64, valueLength = 64,
    )

    @Test
    fun aCompressedCacheTakesLessMemoryInProportion() {
        val normal = MemoryEstimate.cacheBytes(info, 8192, 16)
        val eight = MemoryEstimate.cacheBytes(info, 8192, 8)
        val four = MemoryEstimate.cacheBytes(info, 8192, 4)
        assertEquals(16L * 8192 * (64 + 64) * 8 * 2, normal)
        assertEquals(normal * 8.5 / 16, eight.toDouble(), normal * 0.001)
        assertEquals(normal * 4.5 / 16, four.toDouble(), normal * 0.001)
        assertTrue(four < eight && eight < normal)
    }

    @Test
    fun theDefaultCacheIsTheNormalOneAndOddValuesCountAsNormal() {
        assertEquals(16, GenerationParams().cacheBits)
        assertEquals(MemoryEstimate.cacheBytes(info, 4096), MemoryEstimate.cacheBytes(info, 4096, 16))
        assertEquals(MemoryEstimate.cacheBytes(info, 4096, 16), MemoryEstimate.cacheBytes(info, 4096, 12))
    }

    @Test
    fun aSmallerCacheLetsABiggerModelFitInTheEstimate() {
        val gb = 1024L * 1024 * 1024
        val normal = MemoryEstimate.estimate(2 * gb, info, 16384, 3 * gb, 6 * gb, 16)
        val small = MemoryEstimate.estimate(2 * gb, info, 16384, 3 * gb, 6 * gb, 4)
        assertTrue(small.neededBytes < normal.neededBytes)
    }

    private val turns = listOf(Turn(true, "hi"), Turn(false, "hello"))

    @Test
    fun aSavedChatStateIsOnlyReusedWithTheSameKindOfCache() {
        val normal = ChatState.tag("m", 4096, "", "sys", turns)
        val eight = ChatState.tag("m", 4096, "", "sys", turns, cacheBits = 8)
        val four = ChatState.tag("m", 4096, "", "sys", turns, cacheBits = 4)
        assertNotEquals(normal, eight)
        assertNotEquals(eight, four)
        assertEquals(normal, ChatState.tag("m", 4096, "", "sys", turns, cacheBits = 16))
        assertFalse(normal.contains("kv"))
    }

    @Test
    fun theCacheChoiceIsKeptWithAModelsOwnSettings() {
        val saved = mapOf("m.gguf" to ModelSettings(params = GenerationParams(contextSize = 8192, cacheBits = 8)))
        assertEquals(8, ModelSettingsStore.fromJson(ModelSettingsStore.toJson(saved)).getValue("m.gguf").params!!.cacheBits)
        val odd = """{"m.gguf":{"preset":null,"template":"","draft":null,"ctx":2048,"cache":12}}"""
        assertEquals(16, ModelSettingsStore.fromJson(odd).getValue("m.gguf").params!!.cacheBits)
        val old = """{"m.gguf":{"preset":null,"template":"","draft":null,"ctx":2048}}"""
        assertEquals(16, ModelSettingsStore.fromJson(old).getValue("m.gguf").params!!.cacheBits)
    }

    @Test
    fun theThinkingChoiceIsKeptWithTheChat() {
        val chat = SavedChat("x", "t", 1, null, listOf(StoredMessage("hi", true)), thinking = false)
        val json = ChatCodec.encode(chat)
        assertEquals(false, json.getBoolean("thinking"))
        assertEquals(chat, ChatCodec.decode(json.toString()))
    }

    @Test
    fun chatsThatThinkAreWrittenAsTheyAlwaysWereAndOldOnesLoadAsThinking() {
        val chat = SavedChat("x", "t", 1, null, listOf(StoredMessage("hi", true)))
        assertFalse(ChatCodec.encode(chat).has("thinking"))
        val old = """{"id":"x","title":"t","updatedAt":1,"modelFile":null,"messages":[{"text":"hi","isUser":true}]}"""
        assertTrue(ChatCodec.decode(old).thinking)
    }
}
