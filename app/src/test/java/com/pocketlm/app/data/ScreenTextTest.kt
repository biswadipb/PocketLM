package com.pocketlm.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenTextTest {

    private fun text(s: String) = ScreenNode(text = s)

    @Test
    fun textIsReadInOrderFromTheTree() {
        val root = ScreenNode(children = listOf(text("Title"), ScreenNode(children = listOf(text("First"), text("Second")))))
        assertEquals("Title\nFirst\nSecond", ScreenText.extract(listOf(root)))
    }

    @Test
    fun hiddenElementsAndEverythingInsideThemAreSkipped() {
        val root = ScreenNode(children = listOf(text("Shown"), ScreenNode(visible = false, children = listOf(text("Hidden")))))
        assertEquals("Shown", ScreenText.extract(listOf(root)))
    }

    @Test
    fun passwordFieldsAreNeverRead() {
        val root = ScreenNode(children = listOf(text("Username"), ScreenNode(text = "hunter2", sensitive = true)))
        assertEquals("Username", ScreenText.extract(listOf(root)))
    }

    @Test
    fun aLabelIsUsedOnlyWhenAnElementHasNoTextAndNothingInside() {
        val root = ScreenNode(
            children = listOf(
                ScreenNode(description = "Send message"),
                ScreenNode(text = "Hello", description = "ignored when there is text"),
                ScreenNode(description = "ok"),
                ScreenNode(description = "container label", children = listOf(text("Inner"))),
            ),
        )
        assertEquals("Send message\nHello\nInner", ScreenText.extract(listOf(root)))
    }

    @Test
    fun spacingIsTidiedAndRepeatsRightAfterEachOtherAreDropped() {
        val root = ScreenNode(children = listOf(text("  Hello \n  world "), text("Hello world"), text(""), text("   ")))
        assertEquals("Hello world", ScreenText.extract(listOf(root)))
    }

    @Test
    fun aVeryLongScreenIsCutAtALineEnd() {
        val root = ScreenNode(children = (1..100).map { text("line number $it") })
        val result = ScreenText.extract(listOf(root), maxChars = 100)
        assertTrue(result.length <= 101)
        assertTrue(result.endsWith("…"))
        assertTrue(result.startsWith("line number 1\nline number 2"))
    }

    @Test
    fun nothingOnScreenGivesNothing() {
        assertEquals("", ScreenText.extract(emptyList()))
        assertEquals("", ScreenText.extract(listOf(ScreenNode())))
    }
}
