package com.pocketlm.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptRecordTest {

    private val record = PromptRecord(
        system = "You help.",
        steps = listOf(
            PromptStep("Your message", "What is 2+2?", "<|user|>What is 2+2?<|assistant|>"),
            PromptStep("Result of a tool the model used", "4", "4"),
        ),
        settings = "Model: m.gguf",
    )

    @Test
    fun theTextShowsEverySection() {
        val text = record.asText()
        assertTrue("SYSTEM PROMPT\nYou help." in text)
        assertTrue("--- 1. Your message ---" in text)
        assertTrue("--- 2. Result of a tool the model used ---" in text)
        assertTrue("<|user|>What is 2+2?<|assistant|>" in text)
    }

    @Test
    fun theWrappedCopyIsOnlyShownWhenItDiffers() {
        val text = record.asText()
        assertEquals(1, Regex("after the chat template").findAll(text).count())
    }

    @Test
    fun longTextsAreShortenedInTheMiddle() {
        val clipped = PromptRecord.clip("a".repeat(100_000))
        assertTrue(clipped.length < 31_000)
        assertTrue("characters left out" in clipped)
        assertFalse("characters left out" in PromptRecord.clip("short"))
    }
}
