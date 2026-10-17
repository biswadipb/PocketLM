package com.pocketlm.app

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import com.arm.aichat.isModelLoaded
import com.pocketlm.app.data.ApiException
import com.pocketlm.app.data.ApiMessage
import com.pocketlm.app.data.AppLog
import com.pocketlm.app.data.Bm25Index
import com.pocketlm.app.data.ChatRequest
import com.pocketlm.app.data.HeadlessModels
import com.pocketlm.app.data.HeadlessParams
import com.pocketlm.app.data.KnowledgeStore
import com.pocketlm.app.data.MemoryNotes
import com.pocketlm.app.data.ModelSettingsStore
import com.pocketlm.app.data.ModelStore
import com.pocketlm.app.data.NotesContext
import com.pocketlm.app.data.NotesIndexer
import com.pocketlm.app.data.NotesScanner
import com.pocketlm.app.data.NotesUse
import com.pocketlm.app.data.PromptJob
import com.pocketlm.app.data.Presets
import com.pocketlm.app.data.SettingKeys
import com.pocketlm.app.data.SourceRef
import com.pocketlm.app.data.SpeechModels
import com.pocketlm.app.data.Thinking
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * Keeps a prompt that runs by itself from colliding with the chat screen: while one runs, loading a model or sending a
 * message from the screen waits for it to finish.
 */
object HeadlessGate {
    private val mutex = Mutex()

    @Volatile
    var busy: Boolean = false
        private set

    private val _finished = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Fires after each run, so the chat list can show a chat the run saved. */
    val finished: SharedFlow<Unit> = _finished.asSharedFlow()

    suspend fun <T> exclusive(block: suspend () -> T): T = mutex.withLock {
        busy = true
        try {
            block()
        } finally {
            busy = false
            _finished.tryEmit(Unit)
        }
    }
}

/** How a prompt that ran by itself turned out. */
sealed interface PromptResult {
    data class Done(val text: String, val model: String?, val sources: List<SourceRef>) : PromptResult

    /** Nothing to do, for a reason worth telling (no note changed since the last run). */
    data class Skipped(val message: String) : PromptResult

    data class Failed(val message: String) : PromptResult
}

/**
 * Runs one prompt with no chat on screen. If the app is open with a model loaded, that model answers through the same
 * path as the local API. Otherwise the model is loaded here, used once, and unloaded again.
 */
class PromptRunner(private val context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val knowledge = KnowledgeStore(File(context.filesDir, "knowledge"))

    /** [lastRun] is when a scheduled prompt last ran, for "notes changed since". */
    suspend fun run(job: PromptJob, lastRun: Long = 0): PromptResult = HeadlessGate.exclusive {
        try {
            withTimeout(MAX_RUN_MS) { runInner(job, lastRun) }
        } catch (e: TimeoutCancellationException) {
            PromptResult.Failed("It took longer than ${MAX_RUN_MS / 60_000} minutes, so it was stopped.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.note("Prompt without a screen failed: ${e.message ?: e::class.simpleName}")
            PromptResult.Failed(e.message ?: e::class.simpleName ?: "Something went wrong.")
        }
    }

    private suspend fun runInner(job: PromptJob, lastRun: Long): PromptResult {
        val params = HeadlessParams.read(PrefsSource(prefs), null)
        val step = when (val notes = notesFor(job, lastRun, params.contextSize)) {
            is NotesStep.Stop -> return notes.result
            is NotesStep.Use -> notes
        }
        val system = systemFor(job)

        return when (val written = viaApp(job, system, step.prompt) ?: viaEngine(job, system, step.prompt)) {
            is Generated.Failed -> PromptResult.Failed(written.message)
            is Generated.Text -> {
                val reply = Thinking.answerOnly(written.text).trim()
                if (reply.isEmpty()) PromptResult.Failed("The model wrote nothing.") else PromptResult.Done(reply, written.model, step.sources)
            }
        }
    }

    private sealed interface Generated {
        data class Text(val text: String, val model: String?) : Generated
        data class Failed(val message: String) : Generated
    }

    private sealed interface NotesStep {
        class Use(val prompt: String, val sources: List<SourceRef>) : NotesStep
        class Stop(val result: PromptResult) : NotesStep
    }

    /** The prompt with the notes it asks for in front of it, after bringing the saved copy of the folder up to date. */
    private suspend fun notesFor(job: PromptJob, lastRun: Long, contextSize: Int): NotesStep {
        if (job.notes == NotesUse.None) return NotesStep.Use(job.prompt, emptyList())
        val tree = prefs.getString(NOTES_FOLDER, null)?.let(Uri::parse)
            ?: return NotesStep.Stop(PromptResult.Failed("No notes folder is chosen. Choose one in Settings, My notes."))
        try {
            NotesIndexer(NotesScanner(context), knowledge).refresh(tree)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return NotesStep.Stop(PromptResult.Failed(e.message ?: "The notes folder could not be read."))
        }
        // About three characters to a token, keeping room for the reply and a margin.
        val budget = ((contextSize - job.maxTokens - 400).coerceAtLeast(600) * 9) / 4
        return when (job.notes) {
            NotesUse.Recent -> {
                val since = lastRun
                val label = if (since > 0) DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(since)) else "the start"
                val built = NotesContext.recent(knowledge.manifest(), knowledge::readText, since, label, budget, job.prompt)
                if (built == null) NotesStep.Stop(PromptResult.Skipped("No notes changed since $label."))
                else NotesStep.Use(built.prompt, built.sources)
            }
            NotesUse.Search -> {
                val docs = knowledge.documents().filter { it.second.isNotBlank() }
                val built = NotesContext.search(if (docs.isEmpty()) null else Bm25Index.build(docs), job.prompt, budget)
                if (built == null) NotesStep.Use(job.prompt, emptyList()) else NotesStep.Use(built.prompt, built.sources)
            }
            NotesUse.None -> NotesStep.Use(job.prompt, emptyList())
        }
    }

    /** The prompt that sets the model's manner: the request's own, or the chosen preset (with memory notes, for the person's own schedules). */
    private fun systemFor(job: PromptJob): String {
        job.system?.let { return it }
        val custom = Presets.fromJson(prefs.getString(SettingKeys.PRESETS, null))
        val id = prefs.getString(SettingKeys.PRESET_ID, Presets.DEFAULT_ID)
        val preset = (Presets.builtIn + custom).firstOrNull { it.id == id } ?: Presets.builtIn.first()
        // Prompts other apps send never see the memory notes, as with the local API.
        return if (job.kind == PromptJob.Kind.Scheduled) {
            MemoryNotes.withNotes(preset.prompt, MemoryNotes.fromJson(prefs.getString(SettingKeys.NOTES, null)))
        } else {
            preset.prompt
        }
    }

    /** Through the open app, if it has a model loaded. Null when it has not (or is not open), so the caller loads one itself. */
    private suspend fun viaApp(job: PromptJob, system: String, prompt: String): Generated? {
        val backend = ApiGateway.backend ?: return null
        val request = ChatRequest(
            messages = listOf(ApiMessage("system", system), ApiMessage("user", prompt)),
            stream = false,
            maxTokens = job.maxTokens,
        )
        repeat(BUSY_TRIES) {
            try {
                val out = StringBuilder()
                backend.complete(request) { out.append(it) }
                return Generated.Text(out.toString(), backend.modelNames().firstOrNull())
            } catch (e: ApiException) {
                when {
                    e.status != 503 -> return Generated.Failed(e.message ?: "The app could not answer.")
                    e.message.orEmpty().contains("No model is loaded") -> return null
                    else -> delay(BUSY_WAIT_MS) // the chat is busy; try again shortly
                }
            }
        }
        return Generated.Failed("PocketLM was busy with a chat. Try again later.")
    }

    private suspend fun viaEngine(job: PromptJob, system: String, prompt: String): Generated {
        val models = ModelStore(context).list().filterNot { SpeechModels.isSpeechFile(it.fileName) }
        val choice = HeadlessModels.pick(
            job.model, preferred = null, last = prefs.getString(LAST_MODEL, null),
            models = models.map { HeadlessModels.Choice(it.fileName, it.sizeBytes) },
        )
        val model = when (choice) {
            is HeadlessModels.Pick.Found -> models.first { it.fileName == choice.fileName }
            is HeadlessModels.Pick.Missing -> return Generated.Failed("The model ${choice.requested} is not downloaded.")
            HeadlessModels.Pick.NoModels -> return Generated.Failed("No chat model is downloaded.")
        }

        val engine = AiChat.getInferenceEngine(context)
        val own = ModelSettingsStore.fromJson(prefs.getString(MODEL_SETTINGS, null))[model.fileName]
        val params = HeadlessParams.read(PrefsSource(prefs), own)
        if (engine.state.value.isModelLoaded || engine.state.value is InferenceEngine.State.Error) engine.cleanUp()
        engine.state.first { it is InferenceEngine.State.Initialized }
        try {
            engine.loadModel(model.path, params)
            if (engine.supportsThinking()) engine.setThinking(false)
            engine.setSystemPrompt(system)
            val out = StringBuilder()
            engine.sendUserPrompt(prompt, job.maxTokens).collect { out.append(it) }
            return Generated.Text(out.toString(), model.fileName.removeSuffix(".gguf"))
        } finally {
            // Whatever happened, give the memory back.
            withContext(NonCancellable) { runCatching { if (engine.state.value.isModelLoaded) engine.cleanUp() } }
        }
    }

    private class PrefsSource(private val prefs: SharedPreferences) : HeadlessParams.Source {
        override fun int(key: String, default: Int) = prefs.getInt(key, default)
        override fun float(key: String, default: Float) = prefs.getFloat(key, default)
    }

    companion object {
        const val NOTES_FOLDER = "notes_folder"
        const val LAST_MODEL = "last_model"
        private const val MODEL_SETTINGS = "model_settings"
        private const val MAX_RUN_MS = 9 * 60_000L
        private const val BUSY_TRIES = 20
        private const val BUSY_WAIT_MS = 6_000L
    }
}
