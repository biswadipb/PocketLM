package com.pocketlm.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VariantsTest {

    private fun reply(text: String) = Reply(text)
    private fun texts(list: List<Reply>) = list.map { it.text }

    @Test
    fun regeneratingKeepsTheOldReplyAndPutsTheNewOneLast() {
        val first = Variants.startNew(reply("A"), OtherReplies())
        assertEquals(listOf("A"), texts(first.others))
        assertEquals(1, first.position)

        val second = Variants.startNew(reply("B"), first)
        assertEquals(listOf("A", "B"), texts(second.others))
        assertEquals(2, second.position)
        assertEquals(3, Variants.count(second.others))
    }

    @Test
    fun pagingSwapsTheShownReplyWithTheStoredOne() {
        val (shown, rest) = Variants.select(reply("C"), OtherReplies(listOf(reply("A"), reply("B")), 2), target = 0)
        assertEquals("A", shown.text)
        assertEquals(listOf("B", "C"), texts(rest.others))
        assertEquals(0, rest.position)

        val (back, again) = Variants.select(shown, rest, target = 2)
        assertEquals("C", back.text)
        assertEquals(listOf("A", "B"), texts(again.others))
        assertEquals(2, again.position)
    }

    @Test
    fun pagingPastTheEndsStaysInRange() {
        val versions = OtherReplies(listOf(reply("A")), 1)
        assertEquals("A", Variants.select(reply("B"), versions, target = -5).first.text)
        assertEquals("B", Variants.select(reply("B"), versions, target = 99).first.text)
    }

    @Test
    fun onlyTheNewestVersionsAreKept() {
        var versions = OtherReplies()
        for (i in 1..20) versions = Variants.startNew(reply("v$i"), versions)
        assertEquals(Variants.MAX_VERSIONS - 1, versions.others.size)
        assertEquals("v20", versions.others.last().text)
        assertEquals(versions.others.size, versions.position)
    }

    @Test
    fun aReplyThatNeverAppearedBringsTheLastOneBack() {
        val (back, rest) = Variants.restoreLast(OtherReplies(listOf(reply("A"), reply("B")), 2))!!
        assertEquals("B", back.text)
        assertEquals(listOf("A"), texts(rest.others))
        assertEquals(1, rest.position)
        assertNull(Variants.restoreLast(OtherReplies()))
    }
}
