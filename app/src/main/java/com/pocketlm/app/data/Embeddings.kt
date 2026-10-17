package com.pocketlm.app.data

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.sqrt

/** Small vector helpers for comparing the meaning of two pieces of text. */
object Vectors {

    fun dot(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        for (i in 0 until minOf(a.size, b.size)) sum += a[i] * b[i]
        return sum
    }

    fun norm(a: FloatArray): Float = sqrt(dot(a, a))

    /** 1 for the same direction, 0 for unrelated, -1 for opposite. Zero vectors count as unrelated. */
    fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        val na = norm(a)
        val nb = norm(b)
        return if (na == 0f || nb == 0f) 0f else dot(a, b) / (na * nb)
    }

    fun normalized(a: FloatArray): FloatArray {
        val n = norm(a)
        return if (n == 0f) a.copyOf() else FloatArray(a.size) { a[it] / n }
    }
}

/** Which downloaded models are embedding models, and how to prepare text for each. */
object EmbeddingModels {

    /** GGUF architectures that only make sentence embeddings. */
    private val architectures = setOf(
        "bert", "nomic-bert", "nomic-bert-moe", "jina-bert-v2", "jina-bert-v3", "modern-bert", "neo-bert", "eurobert", "roberta",
    )

    private val e5Name = Regex("""(^|[-_.])e5([-_.]|$)""")

    private val nameHints = Regex("""(embed|bge|minilm|e5[-_.]|gte[-_.]|mxbai|arctic-embed|nomic|jina-)""", RegexOption.IGNORE_CASE)

    /** True if a model with this GGUF architecture / file name is an embedding model rather than a chat model. */
    fun isEmbedding(architecture: String?, fileName: String): Boolean =
        (architecture != null && architecture.lowercase() in architectures) ||
            "embed" in fileName.lowercase() ||
            (architecture == null && nameHints.containsMatchIn(fileName))

    /** Text put in front of a question and in front of a passage; models trained with such tags work much better with them. */
    data class Prefixes(val query: String = "", val passage: String = "")

    fun prefixes(fileName: String): Prefixes {
        val name = fileName.lowercase()
        return when {
            "nomic-embed" in name -> Prefixes("search_query: ", "search_document: ")
            e5Name.containsMatchIn(name) -> Prefixes("query: ", "passage: ")
            "bge-m3" in name -> Prefixes()
            "bge" in name && "-en" in name -> Prefixes("Represent this sentence for searching relevant passages: ", "")
            else -> Prefixes()
        }
    }
}

/** Merges several orderings of the same items into one, so something ranked well by either keyword or meaning ranks well. */
object RankFusion {
    private const val K = 60

    /** Reciprocal rank fusion: each list gives an item 1/(K + position); items are ordered by the total. */
    fun fuse(rankings: List<List<Int>>): List<Int> {
        val score = HashMap<Int, Double>()
        for (ranking in rankings) ranking.forEachIndexed { position, item -> score.merge(item, 1.0 / (K + position + 1), Double::plus) }
        return score.entries.sortedWith(compareByDescending<Map.Entry<Int, Double>> { it.value }.thenBy { it.key }).map { it.key }
    }
}

/** The saved vectors for one document, so it is not read by the embedding model again. */
object EmbeddingFile {
    private val MAGIC = byteArrayOf('P'.code.toByte(), 'E'.code.toByte(), 'M'.code.toByte(), 'B'.code.toByte())
    private const val VERSION = 1

    /** Bumped when text is cut into pieces differently, which makes every saved file out of date. */
    const val CHUNKING_VERSION = 1

    /** What makes the saved vectors valid: the embedding model, how text is prepared for it, and the text itself. */
    fun key(modelFile: String, modelSize: Long, modelModified: Long, prefixes: EmbeddingModels.Prefixes, text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("$modelFile|$modelSize|$modelModified|${prefixes.passage}|$CHUNKING_VERSION|".toByteArray())
        digest.update(text.toByteArray())
        return digest.digest().joinToString("") { "%02x".format(it) }.take(32)
    }

    fun write(key: String, vectors: List<FloatArray>): ByteArray {
        val dims = vectors.firstOrNull()?.size ?: 0
        val keyBytes = key.toByteArray()
        val buffer = ByteBuffer.allocate(MAGIC.size + 1 + 2 + keyBytes.size + 8 + vectors.size * dims * 4).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(MAGIC).put(VERSION.toByte()).putShort(keyBytes.size.toShort()).put(keyBytes)
        buffer.putInt(vectors.size).putInt(dims)
        for (v in vectors) {
            require(v.size == dims) { "Vectors differ in size" }
            for (x in v) buffer.putFloat(x)
        }
        return buffer.array()
    }

    /** The vectors, or null if the file is damaged, from another version, or made for other text or another model. */
    fun read(bytes: ByteArray, expectedKey: String, expectedCount: Int): List<FloatArray>? = runCatching {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val magic = ByteArray(MAGIC.size).also(buffer::get)
        if (!magic.contentEquals(MAGIC) || buffer.get().toInt() != VERSION) return null
        val keyBytes = ByteArray(buffer.short.toInt() and 0xFFFF).also(buffer::get)
        if (String(keyBytes) != expectedKey) return null
        val count = buffer.int
        val dims = buffer.int
        if (count != expectedCount || dims < 0 || buffer.remaining().toLong() != count.toLong() * dims * 4) return null
        List(count) { FloatArray(dims) { buffer.float } }
    }.getOrNull()
}

/**
 * Finds the passages that answer a question, using both keywords (BM25) and meaning (embeddings) when the latter
 * are available. Without vectors it behaves exactly like keyword search alone.
 */
class DocumentSearch(private val keyword: Bm25Index, private val vectors: List<FloatArray>?) {

    val hasMeaning: Boolean get() = vectors != null && vectors.size == keyword.chunkList.size && vectors.isNotEmpty()

    /**
     * The best passages for [question] within [budgetChars], in reading order. [questionVector] is the question as an
     * embedding (null if it could not be made); [limit] caps how many passages the meaning side offers.
     */
    fun search(question: String, questionVector: FloatArray?, budgetChars: Int, limit: Int = 24): List<Chunk> {
        if (!hasMeaning || questionVector == null) return keyword.search(question, budgetChars)

        val byKeyword = keyword.rank(question)
        val byMeaning = vectors!!.indices
            .map { it to Vectors.cosine(vectors[it], questionVector) }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
        val order = RankFusion.fuse(listOf(byKeyword, byMeaning))
        return keyword.pick(order, budgetChars)
    }
}
