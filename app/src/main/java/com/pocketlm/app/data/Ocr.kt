package com.pocketlm.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Reads printed text out of pictures, on the phone, with Google's ML Kit (Latin alphabets). */
object Ocr {

    const val MAX_PAGES = 40
    private const val TARGET_WIDTH = 1600
    private const val MAX_HEIGHT = 3200

    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    /** The size to draw a PDF page at so small print is legible, without making the bitmap huge. */
    fun renderSize(pageWidth: Int, pageHeight: Int): Pair<Int, Int> {
        if (pageWidth <= 0 || pageHeight <= 0) return TARGET_WIDTH to TARGET_WIDTH
        val scale = minOf(TARGET_WIDTH.toFloat() / pageWidth, MAX_HEIGHT.toFloat() / pageHeight)
        return (pageWidth * scale).toInt().coerceAtLeast(1) to (pageHeight * scale).toInt().coerceAtLeast(1)
    }

    /** Joins the text of each page, labelled so answers can point to a page, and skips pages with no text. */
    fun combinePages(pages: List<String>): String =
        pages.mapIndexedNotNull { i, text -> text.trim().takeIf { it.isNotEmpty() }?.let { "[Page ${i + 1}]\n$it" } }
            .joinToString("\n\n")

    suspend fun recognize(context: Context, photo: Uri): String =
        recognize(InputImage.fromFilePath(context, photo))

    private suspend fun recognize(image: InputImage): String = suspendCancellableCoroutine { cont ->
        recognizer.process(image)
            .addOnSuccessListener { cont.resume(it.text) }
            .addOnFailureListener { cont.resumeWithException(IOException("Text recognition failed: ${it.message}")) }
    }

    /**
     * Reads a scanned PDF by drawing each page and recognising it. [onProgress] gets a short status line.
     * Only the first [MAX_PAGES] pages are read.
     */
    suspend fun readPdf(context: Context, pdf: Uri, onProgress: (String) -> Unit): String = withContext(Dispatchers.IO) {
        // PdfRenderer needs a file it can seek in, which a shared document may not be, so copy it first.
        val temp = File(context.cacheDir, "ocr-${UUID.randomUUID()}.pdf")
        try {
            context.contentResolver.openInputStream(pdf)?.use { input -> temp.outputStream().use { input.copyTo(it) } }
                ?: throw IOException("Cannot open the PDF")
            ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                val renderer = try {
                    PdfRenderer(fd)
                } catch (e: Exception) {
                    throw IOException("That PDF could not be opened: ${e.message ?: e::class.simpleName}")
                }
                renderer.use { r ->
                    val count = minOf(r.pageCount, MAX_PAGES)
                    val pages = ArrayList<String>(count)
                    for (i in 0 until count) {
                        onProgress("Reading scanned page ${i + 1} of $count…")
                        val page = r.openPage(i)
                        val bitmap = try {
                            val (w, h) = renderSize(page.width, page.height)
                            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                                it.eraseColor(Color.WHITE)
                                page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            }
                        } finally {
                            page.close()
                        }
                        try {
                            pages += recognize(InputImage.fromBitmap(bitmap, 0))
                        } finally {
                            bitmap.recycle()
                        }
                    }
                    val text = combinePages(pages)
                    if (text.isBlank()) throw IOException("No text could be read from those pages.")
                    text
                }
            }
        } finally {
            temp.delete()
        }
    }
}
