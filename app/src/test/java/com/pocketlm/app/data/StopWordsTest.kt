package com.pocketlm.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class StopWordsTest {
    @Test
    fun parseDropsBlankLinesAndCarriageReturns() {
        assertEquals(listOf("User:", "###"), StopWords.parse("User:\r\n\n  \n###\n"))
    }

    @Test
    fun cutIndexFindsTheEarliestStopWord() {
        assertEquals(6, StopWords.cutIndex("Hello\nUser: hi ### bye", listOf("###", "User:")))
    }

    @Test
    fun cutIndexIsMinusOneWhenNothingMatches() {
        assertEquals(-1, StopWords.cutIndex("plain reply", listOf("User:")))
        assertEquals(-1, StopWords.cutIndex("anything", emptyList()))
    }
}
