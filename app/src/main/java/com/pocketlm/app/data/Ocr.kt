package com.pocketlm.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.google.android.gms.common.moduleinstall.InstallStatusListener
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A writing system the text recognition can read. Each has its own language data, downloaded when first used. */
enum class OcrScript(val id: String, val label: String, val covers: String) {
    Latin("latin", "Latin alphabet", "English, French, Spanish, German, Italian, Portuguese, Vietnamese and many more"),
    Chinese("chinese", "Chinese", "Simplified and traditional Chinese, and Latin text"),
    Japanese("japanese", "Japanese", "Japanese, and Latin text"),
    Korean("korean", "Korean", "Korean, and Latin text"),
    Devanagari("devanagari", "Devanagari", "Hindi, Marathi, Nepali, Sanskrit, and Latin text");

    companion object {
        fun fromId(id: String?): OcrScript = entries.firstOrNull { it.id == id } ?: Latin
    }
}

/**
 * Reads printed text out of pictures, on the phone, with Google's ML Kit. The language data is not packed into
 * PocketLM: Google Play services downloads it the first time a writing system is used, and it works offline after.
 */
object Ocr {

    const val MAX_PAGES = 40
    private const val TARGET_WIDTH = 1600
    private const val MAX_HEIGHT = 3200

    /** The writing system to read. Chosen in Settings. */
    @Volatile
    var script: OcrScript = OcrScript.Latin

    private val recognizers = HashMap<OcrScript, TextRecognizer>()

    private fun recognizerFor(script: OcrScript): TextRecognizer = synchronized(recognizers) {
        recognizers.getOrPut(script) {
            TextRecognition.getClient(
                when (script) {
                    OcrScript.Latin -> TextRecognizerOptions.DEFAULT_OPTIONS
                    OcrScript.Chinese -> ChineseTextRecognizerOptions.Builder().build()
                    OcrScript.Japanese -> JapaneseTextRecognizerOptions.Builder().build()
                    OcrScript.Korean -> KoreanTextRecognizerOptions.Builder().build()
                    OcrScript.Devanagari -> DevanagariTextRecognizerOptions.Builder().build()
                },
            )
        }
    }

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { cont.resume(it) }
        addOnFailureListener { cont.resumeWithException(it) }
    }

    /** "Downloading… 42%" for a download that is [done] of [total] bytes in; just "Downloading…" while the size is unknown. */
    fun downloadStatus(label: String, done: Long, total: Long): String =
        if (total > 0) "Downloading $label text recognition… ${(done * 100 / total).coerceIn(0, 100)}%"
        else "Downloading $label text recognition…"

    /**
     * Makes sure the language data for [script] is on the phone, downloading it through Google Play services if
     * not. Reports progress through [onProgress]. Throws an [IOException] with a plain explanation on failure.
     */
    suspend fun ensureReady(context: Context, script: OcrScript = this.script, onProgress: (String) -> Unit = {}) {
        val recognizer = recognizerFor(script)
        val installer = ModuleInstall.getClient(context)
        try {
            if (installer.areModulesAvailable(recognizer).await().areModulesAvailable()) return
            onProgress(downloadStatus(script.label, 0, 0))
            suspendCancellableCoroutine<Unit> { cont ->
                val listener = object : InstallStatusListener {
                    override fun onInstallStatusUpdated(update: ModuleInstallStatusUpdate) {
                        when (update.installState) {
                            ModuleInstallStatusUpdate.InstallState.STATE_COMPLETED -> {
                                installer.unregisterListener(this)
                                if (cont.isActive) cont.resume(Unit)
                            }
                            ModuleInstallStatusUpdate.InstallState.STATE_FAILED,
                            ModuleInstallStatusUpdate.InstallState.STATE_CANCELED -> {
                                installer.unregisterListener(this)
                                if (cont.isActive) cont.resumeWithException(IOException("The download did not finish."))
                            }
                            ModuleInstallStatusUpdate.InstallState.STATE_DOWNLOADING -> update.progressInfo?.let {
                                onProgress(downloadStatus(script.label, it.bytesDownloaded, it.totalBytesToDownload))
                            }
                            else -> Unit
                        }
                    }
                }
                val request = ModuleInstallRequest.newBuilder().addApi(recognizer).setListener(listener).build()
                installer.installModules(request)
                    .addOnSuccessListener { response ->
                        if (response.areModulesAlreadyInstalled()) {
                            installer.unregisterListener(listener)
                            if (cont.isActive) cont.resume(Unit)
                        }
                    }
                    .addOnFailureListener { error ->
                        installer.unregisterListener(listener)
                        if (cont.isActive) cont.resumeWithException(error)
                    }
                cont.invokeOnCancellation { installer.unregisterListener(listener) }
            }
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException(
                "Text recognition needs Google Play services to download its ${script.label} language data once " +
                    "(${e.message ?: e::class.simpleName}). Connect to the internet and try again.",
            )
        }
    }

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

    suspend fun recognize(context: Context, photo: Uri, onProgress: (String) -> Unit = {}): String {
        ensureReady(context, script, onProgress)
        return recognize(InputImage.fromFilePath(context, photo))
    }

    private suspend fun recognize(image: InputImage): String = suspendCancellableCoroutine { cont ->
        recognizerFor(script).process(image)
            .addOnSuccessListener { cont.resume(it.text) }
            .addOnFailureListener { cont.resumeWithException(IOException("Text recognition failed: ${it.message}")) }
    }

    /**
     * Reads a scanned PDF by drawing each page and recognising it. [onProgress] gets a short status line.
     * Only the first [MAX_PAGES] pages are read.
     */
    suspend fun readPdf(context: Context, pdf: Uri, onProgress: (String) -> Unit): String = withContext(Dispatchers.IO) {
        ensureReady(context, script, onProgress)
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
