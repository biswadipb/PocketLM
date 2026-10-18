package com.pocketlm.app.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** An adapter a model is set to use, and how strongly: 1.0 is as trained, 0 is off. */
data class AdapterSetting(val file: String, val scale: Float = 1f) {
    companion object {
        const val MIN_SCALE = 0f
        const val MAX_SCALE = 2f
    }
}

/** An imported adapter file. */
data class LocalAdapter(val fileName: String, val path: String, val sizeBytes: Long)

/** Decides whether an adapter file can go with a model. Pure, so it can be tested. */
object LoraCheck {

    /**
     * Why [adapterType] / [adapterArchitecture] cannot be used with a model of [modelArchitecture], or null if it
     * looks fine. Unknown values are given the benefit of the doubt; loading shows the final answer.
     */
    fun problem(adapterType: String?, adapterArchitecture: String?, modelArchitecture: String?): String? = when {
        adapterType != null && adapterType != "adapter" ->
            "That file is a whole model, not an adapter. Adapters are small files made with LoRA training."
        adapterArchitecture != null && modelArchitecture != null && !adapterArchitecture.equals(modelArchitecture, ignoreCase = true) ->
            "That adapter is for a $adapterArchitecture model, but this one is $modelArchitecture."
        else -> null
    }

    /** Anything that is not a plain, short file name ending in .gguf is refused. */
    fun safeName(name: String): String? {
        val base = File(name).name.trim()
        val withExt = if (base.endsWith(".gguf", ignoreCase = true)) base else "$base.gguf"
        return withExt.takeIf { it.length in 6..160 && Regex("""[A-Za-z0-9._ ()+-]+""").matches(it) && !it.startsWith(".") }
            ?: withExt.map { if (it.isLetterOrDigit() || it in "._-") it else '_' }.joinToString("").trimStart('.').takeIf { it.length >= 6 }
    }

    /** What makes a saved chat state specific to a set of adapters, so a state made without them is never reused with them. */
    fun signature(adapters: List<LoraAdapterInfo>): String =
        adapters.sortedBy { it.file }.joinToString(",") { "${it.file}:${it.size}:${it.modified}:${"%.3f".format(it.scale)}" }
}

/** What identifies an adapter file as it was when a chat state was saved. */
data class LoraAdapterInfo(val file: String, val size: Long, val modified: Long, val scale: Float)

/** Keeps LoRA adapter files, apart from the models, in the app's private storage. */
class AdapterStore(private val context: Context) {

    private val dir: File
        get() = File(context.filesDir, "adapters").also { it.mkdirs() }

    suspend fun list(): List<LocalAdapter> = withContext(Dispatchers.IO) {
        dir.listFiles { f -> f.isFile && f.name.endsWith(".gguf") }
            .orEmpty()
            .sortedBy { it.name.lowercase() }
            .map { LocalAdapter(it.name, it.absolutePath, it.length()) }
    }

    fun file(name: String): File? = File(dir, name).takeIf { it.isFile && it.parentFile == dir }

    /** Copies a picked adapter into private storage and returns its file name. Nothing is kept if it is not usable. */
    suspend fun import(uri: Uri, modelArchitecture: String?): String = withContext(Dispatchers.IO) {
        val picked = context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) c.getString(i) else null
        } ?: "adapter-${System.currentTimeMillis()}.gguf"
        val name = LoraCheck.safeName(picked) ?: throw IOException("That file name cannot be used.")
        val target = File(dir, name)
        val tmp = File(dir, "$name.part")
        try {
            (context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open the selected file"))
                .use { src -> tmp.outputStream().use { src.copyTo(it) } }
            val info = ModelInfoReader.readAdapter(tmp) ?: throw IOException("That file is not a GGUF file.")
            LoraCheck.problem(info.type, info.architecture, modelArchitecture)?.let { throw IOException(it) }
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) throw IOException("Could not save the adapter.")
        } finally {
            tmp.delete()
        }
        name
    }

    /** Removes adapter files that no model is set to use. */
    suspend fun prune(inUse: Set<String>) = withContext(Dispatchers.IO) {
        dir.listFiles().orEmpty().filter { it.name !in inUse }.forEach { it.delete() }
    }
}

/** The few facts about an adapter file that matter before using it. */
data class AdapterFileInfo(val type: String?, val architecture: String?)

object ModelInfoReader {
    /** Reads the header of an adapter file; null if it is not a GGUF file. */
    suspend fun readAdapter(file: File): AdapterFileInfo? = runCatching {
        file.inputStream().buffered().use {
            val meta = com.arm.aichat.gguf.GgufMetadataReader.create().readStructuredMetadata(it)
            AdapterFileInfo(meta.additional?.type, meta.architecture?.architecture)
        }
    }.getOrNull()
}
