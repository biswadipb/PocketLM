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

    @Test
    fun theBuiltInPromptsHaveUniqueIdsAndTheirFieldsAreUnderstood() {
        assertEquals(PromptLibrary.defaults.size, PromptLibrary.defaults.map { it.id }.toSet().size)
        val withFields = PromptLibrary.defaults.filter { PromptFields.hasFields(it.text) }
        assertTrue(withFields.map { it.id }.containsAll(listOf("reply", "plan")))
        assertEquals(listOf("tone"), PromptFields.asks(PromptLibrary.defaults.first { it.id == "reply" }.text).map { it.label })
        assertTrue(PromptFields.usesClipboard(PromptLibrary.defaults.first { it.id == "reply" }.text))
    }
}
