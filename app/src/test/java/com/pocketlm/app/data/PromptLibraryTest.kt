package com.pocketlm.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptLibraryTest {
    @Test
    fun customPromptsSurviveAJsonRoundTrip() {
        val prompts = listOf(SavedPrompt("a", "Title \"quoted\"", "Line one\nLine two"))
        assertEquals(prompts, PromptLibrary.fromJson(PromptLibrary.toJson(prompts)))
    }

    @Test
    fun badJsonGivesAnEmptyList() {
        assertTrue(PromptLibrary.fromJson("not json").isEmpty())
        assertTrue(PromptLibrary.fromJson(null).isEmpty())
    }
}
