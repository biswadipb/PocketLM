package com.pocketlm.app.data

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream

/** The PDF opened fine but has no text layer, so it is probably scanned pages. */
class ScannedPdfException : IOException("No text found in that PDF. It may be scanned pages.")

/** Pulls the text out of a PDF so it can be searched like any other attached document. */
object PdfText {

    const val MAX_PAGES = 300
    const val MAX_CHARS = 2_000_000

    /** PDF files start with the bytes `%PDF`. */
    fun looksLikePdf(head: ByteArray): Boolean =
        head.size >= 4 && head[0] == '%'.code.toByte() && head[1] == 'P'.code.toByte() &&
            head[2] == 'D'.code.toByte() && head[3] == 'F'.code.toByte()

    /** Tidies extracted text: joins words split by a line-end hyphen and removes runs of blank lines. */
    fun tidy(raw: String): String = raw
        .replace("\r\n", "\n")
        .replace(Regex("""(\w)-\n(\w)"""), "$1$2")
        .replace(Regex("""[ \t]+\n"""), "\n")
        .replace(Regex("""\n{3,}"""), "\n\n")
        .trim()

    suspend fun extract(context: Context, input: InputStream): String = withContext(Dispatchers.IO) {
        PDFBoxResourceLoader.init(context.applicationContext)
        val document = try {
            PDDocument.load(input, MemoryUsageSetting.setupTempFileOnly())
        } catch (e: InvalidPasswordException) {
            throw IOException("That PDF is password protected.")
        } catch (e: Exception) {
            throw IOException("That PDF could not be read: ${e.message ?: e::class.simpleName}")
        }
        document.use { doc ->
            if (doc.isEncrypted) throw IOException("That PDF is password protected.")
            val stripper = PDFTextStripper().apply {
                sortByPosition = true
                endPage = minOf(doc.numberOfPages, MAX_PAGES)
            }
            val text = tidy(stripper.getText(doc)).take(MAX_CHARS)
            if (text.isBlank()) throw ScannedPdfException()
            text
        }
    }
}
