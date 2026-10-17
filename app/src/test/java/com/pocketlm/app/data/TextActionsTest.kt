package com.pocketlm.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextActionsTest {

    @Test
    fun theComponentTheSelectionCameThroughPicksTheAction() {
        assertEquals(TextAction.Explain, TextAction.fromComponent("com.pocketlm.app.ExplainAlias"))
        assertEquals(TextAction.Translate, TextAction.fromComponent("com.pocketlm.app.TranslateAlias"))
        assertEquals(TextAction.Summarise, TextAction.fromComponent("com.pocketlm.app.SummariseAlias"))
        assertEquals(TextAction.Improve, TextAction.fromComponent("com.pocketlm.app.ImproveAlias"))
    }

    @Test
    fun theMainEntryAndUnknownComponentsAreNotActions() {
        assertNull(TextAction.fromComponent("com.pocketlm.app.MainActivity"))
        assertNull(TextAction.fromComponent(null))
        assertNull(TextAction.fromComponent(""))
    }

    @Test
    fun thePromptHasTheInstructionThenTheTrimmedText() {
        assertEquals("Explain this in simple terms:\n\nentropy", TextAction.Explain.promptFor("  entropy \n"))
        assertTrue(TextAction.Translate.promptFor("hola").startsWith("Translate"))
    }

    @Test
    fun everyActionHasItsOwnAlias() {
        assertEquals(TextAction.entries.size, TextAction.entries.map { it.aliasName }.toSet().size)
    }
}
