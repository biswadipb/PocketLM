package com.pocketlm.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatStateTest {

    private val chat = listOf(Turn(true, "hi"), Turn(false, "hello"))

    private fun tag(
        model: String = "m.gguf:100:1",
        ctx: Int = 4096,
        template: String = "",
        system: String = "You help.",
        turns: List<Turn> = chat,
    ) = ChatState.tag(model, ctx, template, system, turns)

    @Test
    fun theSameChatGetsTheSameLabel() {
        assertEquals(tag(), tag())
    }

    @Test
    fun anythingThatChangesTheModelsMemoryChangesTheLabel() {
        val base = tag()
        assertNotEquals(base, tag(model = "m.gguf:100:2"))
        assertNotEquals(base, tag(ctx = 2048))
        assertNotEquals(base, tag(template = "chatml"))
        assertNotEquals(base, tag(system = "You help a lot."))
        assertNotEquals(base, tag(turns = chat + Turn(true, "more")))
        assertNotEquals(base, tag(turns = listOf(Turn(true, "hi"), Turn(false, "hullo"))))
        assertNotEquals(base, tag(turns = listOf(Turn(false, "hi"), Turn(false, "hello"))))
        assertNotEquals(base, tag(turns = listOf(Turn(true, "hi"), Turn(false, "hello", summary = true))))
    }

    @Test
    fun splittingTextDifferentlyIsNotConfusedForTheSame() {
        val a = tag(turns = listOf(Turn(true, "ab"), Turn(true, "c")))
        val b = tag(turns = listOf(Turn(true, "a"), Turn(true, "bc")))
        assertNotEquals(a, b)
    }

    @Test
    fun theOldestStatesGoFirst() {
        val entries = listOf(
            StatePolicy.Entry("new", 400, 300),
            StatePolicy.Entry("old", 400, 100),
            StatePolicy.Entry("mid", 400, 200),
        )
        assertEquals(listOf("old"), StatePolicy.evict(entries, budget = 1000))
        assertEquals(listOf("old", "mid"), StatePolicy.evict(entries, budget = 500))
    }

    @Test
    fun theChatBeingSavedIsNeverRemoved() {
        val entries = listOf(StatePolicy.Entry("a", 400, 100), StatePolicy.Entry("b", 400, 200))
        assertEquals(listOf("b"), StatePolicy.evict(entries, budget = 500, keep = "a"))
    }

    @Test
    fun nothingIsRemovedWithinBudget() {
        assertTrue(StatePolicy.evict(listOf(StatePolicy.Entry("a", 10, 1)), budget = 100).isEmpty())
    }

    @Test
    fun aStateNeverFillsTheLastOfThePhonesSpace() {
        assertEquals(StatePolicy.MAX_STATE_BYTES, StatePolicy.maxBytes(freeSpace = 50L * 1024 * 1024 * 1024))
        assertEquals(100L * 1024 * 1024, StatePolicy.maxBytes(freeSpace = StatePolicy.FREE_SPACE_MARGIN + 100L * 1024 * 1024))
        assertEquals(0, StatePolicy.maxBytes(freeSpace = 1024))
    }
}
