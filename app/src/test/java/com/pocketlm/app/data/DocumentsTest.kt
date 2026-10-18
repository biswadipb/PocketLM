package com.pocketlm.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentsTest {

    @Test
    fun chunkerKeepsPiecesUnderLimitAndLosesNoText() {
        val long = "Sentence number one is here. ".repeat(80)
        val text = "Intro paragraph.\n\n$long\n\nClosing words."
        val pieces = Chunker.split(text, maxChars = 200)

        assertTrue(pieces.all { it.length <= 200 })
        val rejoined = pieces.joinToString(" ").replace(Regex("""\s+"""), " ")
        assertTrue(rejoined.contains("Intro paragraph."))
        assertTrue(rejoined.contains("Closing words."))
        assertEquals(80, Regex("Sentence number one is here\\.").findAll(rejoined).count())
    }

    @Test
    fun chunkerHandlesTextWithNoBreaksAtAll() {
        val pieces = Chunker.split("x".repeat(1000), maxChars = 300)
        assertEquals(listOf(300, 300, 300, 100), pieces.map { it.length })
    }

    @Test
    fun searchFindsTheRelevantChunk() {
        val index = Bm25Index.build(
            listOf(
                "notes.txt" to "The cat sat on the mat.\n\nQuarterly revenue grew 12 percent in Europe.\n\nBananas are yellow.",
            )
        )
        val hits = index.search("how much did revenue grow in europe", budgetChars = 60)
        assertEquals(1, hits.size)
        assertTrue(hits[0].text.contains("revenue"))
    }

    @Test
    fun searchFallsBackToTheStartWhenNothingMatches() {
        val index = Bm25Index.build(listOf("a.txt" to "First part.\n\nSecond part.\n\nThird part."))
        val hits = index.search("summarise this please", budgetChars = 1000)
        assertTrue(hits.isNotEmpty())
        assertEquals(0, hits.first().index)
    }

    @Test
    fun searchReturnsChunksInReadingOrderAndRespectsBudget() {
        val doc = (1..10).joinToString("\n\n") { "Section $it talks about apples and oranges number $it." }
        val index = Bm25Index.build(listOf("fruit.txt" to doc))
        val hits = index.search("apples oranges", budgetChars = 130)

        assertTrue(hits.size in 1..3)
        assertEquals(hits.map { it.index }, hits.map { it.index }.sorted())
    }

    @Test
    fun emptyIndexReturnsNothing() {
        assertTrue(Bm25Index.build(emptyList()).search("anything", 1000).isEmpty())
    }
}
