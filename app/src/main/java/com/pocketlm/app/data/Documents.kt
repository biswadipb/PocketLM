package com.pocketlm.app.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import kotlin.math.ln

/** A text file attached to a chat. Its text is kept in the app's private storage under [id]. */
data class Attachment(val id: String, val name: String, val chars: Int)

class DocumentStore(private val context: Context) {

    private val dir: File
        get() = File(context.filesDir, "docs").also { it.mkdirs() }

    /** Copies a picked text file or PDF into private storage as text. Rejects other binary files and very large ones. */
    suspend fun import(uri: Uri, onProgress: (String) -> Unit = {}): Attachment = withContext(Dispatchers.IO) {
        val name = context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) c.getString(i) else null
        } ?: "document.txt"

        val resolver = context.contentResolver
        val head = resolver.openInputStream(uri)?.use { it.readUpTo(8) }
            ?: throw IOException("Cannot open the selected file")

        val text = if (PdfText.looksLikePdf(head)) {
            try {
                onProgress("Reading the PDF…")
                resolver.openInputStream(uri)!!.use { PdfText.extract(context, it) }
            } catch (e: ScannedPdfException) {
                // No text layer: draw each page and read it with text recognition instead.
                Ocr.readPdf(context, uri, onProgress)
            }
        } else {
            val bytes = resolver.openInputStream(uri)?.use { it.readUpTo(MAX_BYTES + 1) }
                ?: throw IOException("Cannot open the selected file")
            if (bytes.size > MAX_BYTES) throw IOException("File is larger than ${MAX_BYTES / 1_000_000} MB")
            if (looksBinary(bytes)) throw IOException("Only text files and PDFs are supported")
            bytes.toString(Charsets.UTF_8)
        }
        val id = UUID.randomUUID().toString()
        File(dir, "$id.txt").writeText(text)
        Attachment(id, File(name).name, text.length)
    }

    /** Saves [text] (for example text read from a photo) as an attachment called [name]. */
    suspend fun saveText(name: String, text: String): Attachment = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        File(dir, "$id.txt").writeText(text.take(MAX_BYTES))
        Attachment(id, name, text.length)
    }

    suspend fun read(id: String): String? = withContext(Dispatchers.IO) {
        File(dir, "$id.txt").takeIf { it.exists() }?.readText()
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        File(dir, "$id.emb").delete()
        File(dir, "$id.txt").delete()
    }

    /** Saved vectors for a document (see [EmbeddingFile]), or null if there are none. */
    suspend fun readVectors(id: String): ByteArray? = withContext(Dispatchers.IO) {
        File(dir, "$id.emb").takeIf { it.isFile }?.readBytes()
    }

    suspend fun writeVectors(id: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
        if (File(dir, "$id.txt").isFile) File(dir, "$id.emb").writeBytes(bytes)
    }

    private fun InputStream.readUpTo(max: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        while (total < max) {
            val n = read(buf, 0, minOf(buf.size, max - total))
            if (n < 0) break
            out.write(buf, 0, n)
            total += n
        }
        return out.toByteArray()
    }

    private fun looksBinary(bytes: ByteArray): Boolean = bytes.take(4096).any { it == 0.toByte() }

    companion object {
        const val MAX_BYTES = 2_000_000
    }
}

data class Chunk(val docName: String, val docIndex: Int, val index: Int, val text: String)

object Chunker {
    /** Splits [text] into pieces of roughly [maxChars], breaking on paragraphs, then sentences. */
    fun split(text: String, maxChars: Int = 700): List<String> {
        val pieces = ArrayList<String>()
        val current = StringBuilder()

        fun flush() {
            if (current.isNotBlank()) pieces += current.toString().trim()
            current.setLength(0)
        }

        for (para in text.split(Regex("""\n\s*\n"""))) {
            val p = para.trim()
            if (p.isEmpty()) continue
            if (p.length > maxChars) {
                flush()
                for (sentence in p.split(Regex("""(?<=[.!?])\s+|\n"""))) {
                    var s = sentence
                    while (s.length > maxChars) {
                        if (current.isNotEmpty()) flush()
                        pieces += s.take(maxChars)
                        s = s.drop(maxChars)
                    }
                    if (current.length + s.length + 1 > maxChars) flush()
                    if (current.isNotEmpty()) current.append(' ')
                    current.append(s)
                }
                flush()
            } else {
                if (current.length + p.length + 2 > maxChars) flush()
                if (current.isNotEmpty()) current.append("\n\n")
                current.append(p)
            }
        }
        flush()
        return pieces
    }
}

/**
 * Keyword search over chunks using BM25. No embedding model is needed, so it works with
 * whichever chat model is loaded and costs almost nothing on a phone.
 */
class Bm25Index(private val chunks: List<Chunk>) {

    private val termCounts: List<Map<String, Int>> = chunks.map { c ->
        tokenize(c.text).groupingBy { it }.eachCount()
    }
    private val lengths = termCounts.map { it.values.sum() }
    private val avgLength = if (lengths.isEmpty()) 1.0 else lengths.average().coerceAtLeast(1.0)
    private val docFreq: Map<String, Int> = HashMap<String, Int>().also { df ->
        termCounts.forEach { tc -> tc.keys.forEach { df.merge(it, 1, Int::plus) } }
    }

    val isEmpty get() = chunks.isEmpty()

    /** Every passage, in reading order: the position of a passage here is the number [rank] and [pick] use for it. */
    val chunkList: List<Chunk> get() = chunks

    /** The passages that share words with [query], best first, as positions in [chunkList]. Empty if none do. */
    fun rank(query: String): List<Int> {
        if (chunks.isEmpty()) return emptyList()
        val terms = tokenize(query).distinct()
        val n = chunks.size.toDouble()

        val scored = chunks.indices.map { i ->
            var score = 0.0
            for (t in terms) {
                val tf = termCounts[i][t] ?: continue
                val df = docFreq[t] ?: continue
                val idf = ln(1 + (n - df + 0.5) / (df + 0.5))
                score += idf * tf * (K1 + 1) / (tf + K1 * (1 - B + B * lengths[i] / avgLength))
            }
            i to score
        }
        return scored.filter { it.second > 0 }.sortedByDescending { it.second }.map { it.first }
    }

    /** Takes passages from [order] until [budgetChars] is used (always at least one), and returns them in reading order. */
    fun pick(order: List<Int>, budgetChars: Int): List<Chunk> {
        val picked = ArrayList<Int>()
        var used = 0
        for (i in order) {
            val len = chunks[i].text.length
            if (picked.isNotEmpty() && used + len > budgetChars) break
            picked += i
            used += len
        }
        return picked.sorted().map { chunks[it] }
    }

    /**
     * Returns the best chunks for [query] within [budgetChars], in reading order. When nothing
     * matches (e.g. "summarise this"), falls back to the start of each document.
     */
    fun search(query: String, budgetChars: Int): List<Chunk> {
        if (chunks.isEmpty()) return emptyList()
        val order = rank(query).ifEmpty { chunks.indices.toList() }
        return pick(order, budgetChars)
    }

    companion object {
        private const val K1 = 1.5
        private const val B = 0.75

        fun tokenize(text: String): List<String> =
            text.lowercase().split(Regex("""[^\p{L}\p{N}]+""")).filter { it.length > 1 }

        fun build(docs: List<Pair<String, String>>): Bm25Index {
            val all = ArrayList<Chunk>()
            docs.forEachIndexed { d, (name, text) ->
                Chunker.split(text).forEachIndexed { i, piece -> all += Chunk(name, d, i, piece) }
            }
            return Bm25Index(all)
        }
    }
}
