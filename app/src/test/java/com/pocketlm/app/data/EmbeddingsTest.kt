package com.pocketlm.app.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddingsTest {

    // ---- Vectors -----------------------------------------------------------------

    @Test
    fun cosineSaysHowAlikeTwoVectorsPoint() {
        assertEquals(1f, Vectors.cosine(floatArrayOf(1f, 2f), floatArrayOf(2f, 4f)), 1e-6f)
        assertEquals(0f, Vectors.cosine(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f)), 1e-6f)
        assertEquals(-1f, Vectors.cosine(floatArrayOf(1f, 1f), floatArrayOf(-1f, -1f)), 1e-6f)
    }

    @Test
    fun oddVectorsCountAsUnrelatedInsteadOfBreaking() {
        assertEquals(0f, Vectors.cosine(floatArrayOf(0f, 0f), floatArrayOf(1f, 1f)), 0f)
        assertEquals(0f, Vectors.cosine(floatArrayOf(1f), floatArrayOf(1f, 1f)), 0f)
    }

    @Test
    fun normalisingGivesUnitLength() {
        val v = Vectors.normalized(floatArrayOf(3f, 4f))
        assertEquals(1f, Vectors.norm(v), 1e-6f)
        assertEquals(0.6f, v[0], 1e-6f)
        assertArrayEquals(floatArrayOf(0f, 0f), Vectors.normalized(floatArrayOf(0f, 0f)), 0f)
    }

    // ---- Which models, which prefixes ------------------------------------------------

    @Test
    fun embeddingModelsAreToldApartFromChatModels() {
        assertTrue(EmbeddingModels.isEmbedding("bert", "anything.gguf"))
        assertTrue(EmbeddingModels.isEmbedding("nomic-bert", "x.gguf"))
        assertTrue(EmbeddingModels.isEmbedding("qwen3", "Qwen3-Embedding-0.6B-Q8_0.gguf"))
        assertTrue(EmbeddingModels.isEmbedding(null, "bge-small-en-v1.5-q8_0.gguf"))
        assertFalse(EmbeddingModels.isEmbedding("llama", "Llama-3.2-1B-Instruct-Q4_K_M.gguf"))
        assertFalse(EmbeddingModels.isEmbedding("qwen2", "qwen2.5-0.5b-instruct-q4_k_m.gguf"))
        assertFalse(EmbeddingModels.isEmbedding(null, "SmolVLM-500M-Instruct-Q8_0.gguf"))
    }

    @Test
    fun eachModelFamilyGetsTheTagsItWasTrainedWith() {
        assertEquals(EmbeddingModels.Prefixes("search_query: ", "search_document: "), EmbeddingModels.prefixes("nomic-embed-text-v1.5.Q4_K_M.gguf"))
        assertEquals(EmbeddingModels.Prefixes("query: ", "passage: "), EmbeddingModels.prefixes("multilingual-e5-small-q8_0.gguf"))
        assertEquals(EmbeddingModels.Prefixes("query: ", "passage: "), EmbeddingModels.prefixes("e5-base-v2.Q8_0.gguf"))
        assertTrue(EmbeddingModels.prefixes("bge-small-en-v1.5-q8_0.gguf").query.startsWith("Represent this sentence"))
        assertEquals("", EmbeddingModels.prefixes("bge-small-en-v1.5-q8_0.gguf").passage)
        assertEquals(EmbeddingModels.Prefixes(), EmbeddingModels.prefixes("bge-m3-Q4_K_M.gguf"))
        assertEquals(EmbeddingModels.Prefixes(), EmbeddingModels.prefixes("all-MiniLM-L6-v2-Q8_0.gguf"))
    }

    // ---- Rank fusion -------------------------------------------------------------------

    @Test
    fun anItemBothListsLikeBeatsAnItemOnlyOneLikes() {
        val fused = RankFusion.fuse(listOf(listOf(5, 1, 2), listOf(7, 1, 3)))
        assertEquals(1, fused.first())
        assertEquals(setOf(5, 1, 2, 7, 3), fused.toSet())
    }

    @Test
    fun fusingOneListKeepsItsOrderAndEmptyListsAreFine() {
        assertEquals(listOf(4, 2, 9), RankFusion.fuse(listOf(listOf(4, 2, 9))))
        assertEquals(listOf(4, 2, 9), RankFusion.fuse(listOf(listOf(4, 2, 9), emptyList())))
        assertTrue(RankFusion.fuse(emptyList()).isEmpty())
    }

    @Test
    fun theTopOfEitherListStaysNearTheTop() {
        // Item 10 is first for meaning only, item 20 first for keywords only: both beat the rest.
        val fused = RankFusion.fuse(listOf(listOf(20, 1, 2, 3), listOf(10, 4, 5, 6)))
        assertEquals(setOf(10, 20), fused.take(2).toSet())
    }

    // ---- Saved vectors -------------------------------------------------------------------

    private val prefixes = EmbeddingModels.Prefixes("q: ", "p: ")

    @Test
    fun savedVectorsComeBackExactly() {
        val key = EmbeddingFile.key("m.gguf", 100, 5, prefixes, "the text")
        val vectors = listOf(floatArrayOf(0.1f, 0.2f, 0.3f), floatArrayOf(-1f, 0f, 1f))
        val back = EmbeddingFile.read(EmbeddingFile.write(key, vectors), key, 2)!!
        assertEquals(2, back.size)
        vectors.indices.forEach { assertArrayEquals(vectors[it], back[it], 0f) }
    }

    @Test
    fun savedVectorsAreIgnoredWhenTheModelOrTheTextChanged() {
        val key = EmbeddingFile.key("m.gguf", 100, 5, prefixes, "the text")
        val bytes = EmbeddingFile.write(key, listOf(floatArrayOf(1f)))
        assertNull(EmbeddingFile.read(bytes, EmbeddingFile.key("m.gguf", 100, 6, prefixes, "the text"), 1))
        assertNull(EmbeddingFile.read(bytes, EmbeddingFile.key("m.gguf", 100, 5, prefixes, "other text"), 1))
        assertNull(EmbeddingFile.read(bytes, EmbeddingFile.key("n.gguf", 100, 5, prefixes, "the text"), 1))
        assertNull(EmbeddingFile.read(bytes, EmbeddingFile.key("m.gguf", 100, 5, prefixes.copy(passage = "d: "), "the text"), 1))
        assertNull(EmbeddingFile.read(bytes, key, 2))
    }

    @Test
    fun damagedOrForeignFilesAreIgnored() {
        val key = EmbeddingFile.key("m.gguf", 1, 1, prefixes, "t")
        val good = EmbeddingFile.write(key, listOf(floatArrayOf(1f, 2f)))
        assertNull(EmbeddingFile.read(ByteArray(0), key, 1))
        assertNull(EmbeddingFile.read("not a vector file at all".toByteArray(), key, 1))
        assertNull(EmbeddingFile.read(good.copyOf(good.size - 3), key, 1))
        assertNull(EmbeddingFile.read(good + byteArrayOf(0), key, 1))
        assertNotEquals(key, EmbeddingFile.key("m.gguf", 1, 1, prefixes, "u"))
    }

    // ---- Searching ----------------------------------------------------------------------------

    /** Three paragraphs, each long enough to be its own passage. */
    private fun docs(): List<Pair<String, String>> {
        fun padded(text: String) = text + " " + "Further details are listed in the appendix. ".repeat(10)
        return listOf(
            "Guide" to listOf(
                padded("The cat sat quietly on the warm windowsill all afternoon."),
                padded("Our refund policy lets customers return goods within thirty days for their money back."),
                padded("The server restarts every night at two to apply updates."),
            ).joinToString("\n\n"),
        )
    }

    /** A pretend meaning space: passages and questions about the same thing share a direction. */
    private fun vectorFor(text: String): FloatArray = when {
        "refund" in text.lowercase() || "money back" in text.lowercase() || "cash" in text.lowercase() -> floatArrayOf(1f, 0f, 0f)
        "cat" in text.lowercase() || "pet" in text.lowercase() -> floatArrayOf(0f, 1f, 0f)
        else -> floatArrayOf(0f, 0f, 1f)
    }

    @Test
    fun aQuestionWorkedDifferentlyStillFindsThePassageByMeaning() {
        val keyword = Bm25Index.build(docs())
        val vectors = keyword.chunkList.map { vectorFor(it.text) }
        val search = DocumentSearch(keyword, vectors)
        assertTrue(search.hasMeaning)

        val question = "What if I want my cash handed over afterwards?"   // shares no word with the policy passage
        val byKeywordsOnly = keyword.search(question, budgetChars = 120)
        val byMeaning = search.search(question, vectorFor(question), budgetChars = 120)
        assertTrue(byMeaning.any { "refund policy" in it.text })
        assertFalse(byKeywordsOnly.any { "refund policy" in it.text })
    }

    @Test
    fun withoutAQuestionVectorOrVectorsItIsPlainKeywordSearch() {
        val keyword = Bm25Index.build(docs())
        val question = "when does the server restart"
        val expected = keyword.search(question, 300)
        assertEquals(expected, DocumentSearch(keyword, null).search(question, floatArrayOf(1f, 0f, 0f), 300))
        val vectors = keyword.chunkList.map { vectorFor(it.text) }
        assertEquals(expected, DocumentSearch(keyword, vectors).search(question, null, 300))
        assertFalse(DocumentSearch(keyword, null).hasMeaning)
    }

    @Test
    fun vectorsThatDoNotMatchThePassagesAreNotTrusted() {
        val keyword = Bm25Index.build(docs())
        val tooFew = keyword.chunkList.take(1).map { vectorFor(it.text) }
        assertFalse(DocumentSearch(keyword, tooFew).hasMeaning)
        assertFalse(DocumentSearch(keyword, emptyList()).hasMeaning)
    }

    @Test
    fun theBestPassagesComeBackInReadingOrderWithinTheBudget() {
        val keyword = Bm25Index.build(docs())
        val vectors = keyword.chunkList.map { vectorFor(it.text) }
        val hits = DocumentSearch(keyword, vectors).search("refund for the cat", vectorFor("refund"), budgetChars = 10_000)
        assertEquals(hits.sortedBy { it.index }, hits)
        val one = DocumentSearch(keyword, vectors).search("refund", vectorFor("refund"), budgetChars = 1)
        assertEquals(1, one.size)
    }

    @Test
    fun keywordRankingAndPickingMatchTheOldSearch() {
        val keyword = Bm25Index.build(docs())
        val order = keyword.rank("server restarts")
        assertTrue(order.isNotEmpty())
        assertEquals(keyword.search("server restarts", 5_000), keyword.pick(order, 5_000))
        assertTrue(keyword.rank("zzzzqq").isEmpty())
    }
}
