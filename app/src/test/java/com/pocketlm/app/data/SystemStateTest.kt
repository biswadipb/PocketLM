package com.pocketlm.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemStateTest {

    private fun tag(
        model: String = "m:1:1", ctx: Int = 4096, template: String = "", prompt: String = "You are helpful.", adapters: String = "", bits: Int = 16,
    ) = ChatState.tag(model, ctx, template, prompt, emptyList(), adapters, bits)

    @Test
    fun theSamePromptWithTheSameModelAndSettingsHasTheSameLabel() {
        assertEquals(tag(), tag())
    }

    @Test
    fun anythingThatChangesWhatTheModelRemembersChangesTheLabel() {
        val base = tag()
        assertNotEquals(base, tag(prompt = "You are helpful!"))
        assertNotEquals(base, tag(model = "m:2:1"))
        assertNotEquals(base, tag(ctx = 8192))
        assertNotEquals(base, tag(template = "chatml"))
        assertNotEquals(base, tag(adapters = "a:1:1:1.0"))
        assertNotEquals(base, tag(bits = 8))
    }

    @Test
    fun theFileOfAPromptIsNamedAfterItsLabelAndOnlyTheNewestFewAreKept() {
        val entries = listOf("sys-a" to 10L, "sys-b" to 50L, "sys-c" to 30L, "sys-d" to 40L)
        assertEquals(listOf("sys-c", "sys-a"), StatePolicy.surplus(entries, 2))
        assertTrue(StatePolicy.surplus(entries, 4).isEmpty())
        assertTrue(StatePolicy.surplus(entries, 10).isEmpty())
        assertEquals(4, StatePolicy.surplus(entries, 0).size)
        assertTrue(StatePolicy.surplus(emptyList(), 3).isEmpty())
    }

    @Test
    fun onlyLongPromptsAreWorthSaving() {
        assertTrue(StatePolicy.MIN_SYSTEM_PROMPT_CHARS > "You are Piko, a helpful assistant running entirely on the user's phone.".length)
        assertTrue(StatePolicy.MAX_SYSTEM_STATE_BYTES < StatePolicy.MAX_STATE_BYTES)
    }
}
