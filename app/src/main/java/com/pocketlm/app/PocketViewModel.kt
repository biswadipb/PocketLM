package com.pocketlm.app

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import androidx.biometric.BiometricManager
import androidx.core.content.ContextCompat
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.arm.aichat.AiChat
import com.arm.aichat.EmbeddingException
import com.arm.aichat.GenerationParams
import com.arm.aichat.GpuDevice
import com.arm.aichat.GpuStatus
import com.arm.aichat.InferenceEngine
import com.arm.aichat.LoraAdapter
import com.arm.aichat.isModelLoaded
import com.pocketlm.app.data.AppLog
import com.pocketlm.app.data.ChatImporter
import com.pocketlm.app.data.AdapterStore
import com.pocketlm.app.data.ChatTags
import com.pocketlm.app.data.DisplayScale
import com.pocketlm.app.data.IncognitoLedger
import com.pocketlm.app.data.KnowledgeStore
import com.pocketlm.app.data.NotesRules
import com.pocketlm.app.data.NotesScanner
import com.pocketlm.app.data.NoteSuggestions
import com.pocketlm.app.data.ShareIntake
import com.pocketlm.app.data.LocalAdapter
import com.pocketlm.app.data.LoraAdapterInfo
import com.pocketlm.app.data.LoraCheck
import com.pocketlm.app.data.SpeechText
import com.pocketlm.app.data.ThrottleLevel
import com.pocketlm.app.data.ThrottlePolicy
import com.pocketlm.app.data.ChatTemplates
import com.pocketlm.app.data.ContextMeter
import com.pocketlm.app.data.Attachment
import com.pocketlm.app.data.ChatState
import com.pocketlm.app.data.ChatStateCache
import com.pocketlm.app.data.JsonSchema
import com.pocketlm.app.data.MemoryNotes
import com.pocketlm.app.data.ModelSettings
import com.pocketlm.app.data.ModelSettingsStore
import com.pocketlm.app.data.Note
import com.pocketlm.app.data.OtherReplies
import com.pocketlm.app.data.PromptRecord
import com.pocketlm.app.data.PromptStep
import com.pocketlm.app.data.Replay
import com.pocketlm.app.data.Reply
import com.pocketlm.app.data.SettingKeys
import com.pocketlm.app.data.SyncListener
import com.pocketlm.app.data.SyncOutcome
import com.pocketlm.app.data.SyncRunner
import com.pocketlm.app.data.StatePolicy
import com.pocketlm.app.data.FolderBackend
import com.pocketlm.app.data.SyncCrypto
import com.pocketlm.app.data.SyncException
import com.pocketlm.app.data.SyncHeader
import com.pocketlm.app.data.SyncKind
import com.pocketlm.app.data.SyncNames
import com.pocketlm.app.data.SyncState
import com.pocketlm.app.data.SyncStatus
import com.pocketlm.app.data.SyncTarget
import com.pocketlm.app.data.checkAccess
import com.pocketlm.app.data.Summaries
import com.pocketlm.app.data.Turn
import com.pocketlm.app.data.Variants
import com.pocketlm.app.data.VoiceListener
import com.pocketlm.app.data.VoiceLoop
import com.pocketlm.app.data.WebPage
import com.pocketlm.app.data.BackupManager
import com.arm.aichat.ContextUsage
import com.pocketlm.app.data.ApiBackend
import com.pocketlm.app.data.Chunker
import com.pocketlm.app.data.DocumentSearch
import com.pocketlm.app.data.EmbeddingFile
import com.pocketlm.app.data.EmbeddingModels
import com.pocketlm.app.data.EmbeddingsResult
import com.pocketlm.app.data.ApiException
import com.pocketlm.app.data.ApiTools
import com.pocketlm.app.data.BenchResult
import com.pocketlm.app.data.ChatRequest
import com.pocketlm.app.data.Bm25Index
import com.pocketlm.app.data.ChatExport
import com.pocketlm.app.data.ChatStore
import com.pocketlm.app.data.DeviceStatus
import com.pocketlm.app.data.DocumentStore
import com.pocketlm.app.data.GenStats
import com.pocketlm.app.data.HubSource
import com.pocketlm.app.data.HfFile
import com.pocketlm.app.data.HfModel
import com.pocketlm.app.data.SavedChat
import com.pocketlm.app.data.SettingsBackup
import com.pocketlm.app.data.StopWords
import com.pocketlm.app.data.Thinking
import com.pocketlm.app.data.ToolId
import com.pocketlm.app.data.Tools
import com.pocketlm.app.data.StorageManager
import com.pocketlm.app.data.StorageReport
import com.pocketlm.app.data.StoredMessage
import com.pocketlm.app.data.LocalModel
import com.pocketlm.app.data.IdlePolicy
import com.pocketlm.app.data.ImageStore
import com.pocketlm.app.data.JsonGrammar
import com.pocketlm.app.data.ModelCatalog
import com.pocketlm.app.data.ModelHub
import com.pocketlm.app.data.ModelInfo
import com.pocketlm.app.data.ModelStore
import com.pocketlm.app.data.Preset
import com.pocketlm.app.data.GpuChoice
import com.pocketlm.app.data.GpuGuard
import com.pocketlm.app.data.CatalogModel
import com.pocketlm.app.data.CatalogStore
import com.arm.aichat.SpeechException
import com.pocketlm.app.data.Audio
import com.pocketlm.app.data.MicRecorder
import com.pocketlm.app.data.Ocr
import com.pocketlm.app.data.SpeechModels
import com.pocketlm.app.data.Transcript
import com.pocketlm.app.data.Projects
import com.pocketlm.app.data.ProjectStore
import com.pocketlm.app.data.Project
import com.pocketlm.app.data.RemoteCatalog
import com.pocketlm.app.data.SpeedPreset
import com.pocketlm.app.data.SpeedPresets
import com.pocketlm.app.data.SearchConfig
import com.pocketlm.app.data.SearchProvider
import com.pocketlm.app.data.SelfSignedCertificate
import com.pocketlm.app.data.TlsStore
import com.pocketlm.app.data.LocalNetwork
import com.pocketlm.app.data.OcrScript
import com.pocketlm.app.data.Presets
import com.pocketlm.app.data.PromptLibrary
import com.pocketlm.app.data.SavedPrompt
import com.pocketlm.app.data.Speaker
import com.pocketlm.app.ui.ThemeMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import org.json.JSONObject
import java.security.SecureRandom
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

data class ChatMessage(
    val text: String,
    val isUser: Boolean,
    val id: String = UUID.randomUUID().toString(),
    val stats: GenStats? = null,
    val imagePath: String? = null,
    /** True for a summary that stands in for everything said before it. */
    val summary: Boolean = false,
    /** Earlier versions of this reply (from regenerating), and where the shown one sits among all of them. */
    val others: List<Reply> = emptyList(),
    val position: Int = 0,
    val starred: Boolean = false,
    /** Photos after the first one, when a message carries several. */
    val moreImages: List<String> = emptyList(),
) {
    /** Every photo sent with this message, in order. */
    val images: List<String> get() = listOfNotNull(imagePath) + moreImages
}

data class SearchState(
    val query: String = "",
    val loading: Boolean = false,
    val searched: Boolean = false,
    val results: List<HfModel> = emptyList(),
    /** What to ask for to get the next page of results, or null when there are no more. */
    val next: String? = null,
    val loadingMore: Boolean = false,
)

/** The HTTPS certificate of the API server: its SHA-256 [fingerprint], the certificate as [pem] text, and when it expires. */
data class ApiCertificate(val fingerprint: String, val pem: String, val expiresMs: Long)

/** Files of one repository: null [files] while still loading. */
data class RepoFiles(val files: List<HfFile>? = null, val error: String? = null)

enum class ComparePhase { Waiting, Loading, Generating, Done, Failed }

/** What dictation on the phone is doing: nothing, recording your voice, or writing it down. */
enum class DictationState { Idle, Listening, Transcribing }

/** One model's side of a comparison. */
data class CompareSide(
    val modelName: String,
    val phase: ComparePhase = ComparePhase.Waiting,
    val text: String = "",
    val stats: GenStats? = null,
)

/** Download progress in 0..1, or null while the size is still unknown. */
data class DownloadState(val progress: Float?)

class PocketViewModel(app: Application) : AndroidViewModel(app) {

    private val engine: InferenceEngine = AiChat.getInferenceEngine(app)
    private val store = ModelStore(app)

    val engineState: StateFlow<InferenceEngine.State> = engine.state

    private val _models = MutableStateFlow<List<LocalModel>>(emptyList())
    val models = _models.asStateFlow()

    private val _loadedModel = MutableStateFlow<LocalModel?>(null)
    val loadedModel = _loadedModel.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages = _messages.asStateFlow()

    private val _generating = MutableStateFlow(false)
    val generating = _generating.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading = _loading.asStateFlow()

    private val center = DownloadCenter.get(app)

    /** Downloads in progress. They run outside this screen so they continue in the background. */
    val downloads = center.downloads

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    private val prefs = app.getSharedPreferences("settings", Application.MODE_PRIVATE)

    private val _themeMode = MutableStateFlow(
        ThemeMode.entries.firstOrNull { it.name == prefs.getString(KEY_THEME, null) } ?: ThemeMode.System
    )
    val themeMode = _themeMode.asStateFlow()

    fun setThemeMode(mode: ThemeMode) {
        _themeMode.value = mode
        prefs.edit().putString(KEY_THEME, mode.name).apply()
    }

    private val _textScale = MutableStateFlow(DisplayScale.snapText(prefs.getFloat(KEY_TEXT_SCALE, 1f)))

    /** How much bigger or smaller than the phone's own setting the app's text is shown. */
    val textScale = _textScale.asStateFlow()

    fun setTextScale(scale: Float) {
        val snapped = DisplayScale.snapText(scale)
        _textScale.value = snapped
        prefs.edit().putFloat(KEY_TEXT_SCALE, snapped).apply()
    }

    private val _generationParams = MutableStateFlow(
        GenerationParams(
            contextSize = prefs.getInt(KEY_CTX, DEFAULT_PARAMS.contextSize),
            threads = prefs.getInt(KEY_THREADS, DEFAULT_PARAMS.threads),
            temperature = prefs.getFloat(KEY_TEMP, DEFAULT_PARAMS.temperature),
            topP = prefs.getFloat(KEY_TOP_P, DEFAULT_PARAMS.topP),
            topK = prefs.getInt(KEY_TOP_K, DEFAULT_PARAMS.topK),
            minP = prefs.getFloat(KEY_MIN_P, DEFAULT_PARAMS.minP),
            repeatPenalty = prefs.getFloat(KEY_REPEAT, DEFAULT_PARAMS.repeatPenalty),
            seed = prefs.getInt(KEY_SEED, DEFAULT_PARAMS.seed),
            cacheBits = prefs.getInt(KEY_CACHE_BITS, DEFAULT_PARAMS.cacheBits),
        )
    )
    val generationParams = _generationParams.asStateFlow()

    /** Takes effect the next time a model is loaded (context size and threads are fixed per session). */
    fun setGenerationParams(params: GenerationParams) {
        _generationParams.value = params
        prefs.edit()
            .putInt(KEY_CTX, params.contextSize)
            .putInt(KEY_THREADS, params.threads)
            .putFloat(KEY_TEMP, params.temperature)
            .putFloat(KEY_TOP_P, params.topP)
            .putInt(KEY_TOP_K, params.topK)
            .putFloat(KEY_MIN_P, params.minP)
            .putFloat(KEY_REPEAT, params.repeatPenalty)
            .putInt(KEY_SEED, params.seed)
            .putInt(KEY_CACHE_BITS, params.cacheBits)
            .apply()
    }

    // ---- Settings that belong to one model ------------------------------------------

    private val _modelSettings = MutableStateFlow(ModelSettingsStore.fromJson(prefs.getString(KEY_MODEL_SETTINGS, null)))

    /** Each model's own settings, by file name. A model without an entry follows the app-wide settings. */
    val modelSettings = _modelSettings.asStateFlow()

    /** What applies while [model] is loaded: its own settings if it has them, with its chat template and draft model. */
    private fun paramsFor(model: LocalModel): GenerationParams {
        val own = _modelSettings.value[model.fileName]
        val draft = own?.draftFile?.takeIf { it != model.fileName }
            ?.let { name -> _models.value.firstOrNull { it.fileName == name }?.path }
            .orEmpty()
        val adapters = own?.adapters.orEmpty().mapNotNull { a ->
            adapterStore.file(a.file)?.let { LoraAdapter(it.absolutePath, a.scale) }
        }
        // A GPU is used only if this model asks for one and it has not been switched off after a crash.
        val gpu = if (_gpuDisabled.value) "" else own?.gpu.orEmpty()
        return (own?.params ?: _generationParams.value)
            .copy(chatTemplate = own?.chatTemplate.orEmpty(), draftModelPath = draft, adapters = adapters, gpuBackend = gpu)
    }

    // ---- Graphics processors (GPU) ---------------------------------------------------------

    private val gpuGuard = GpuGuard(object : GpuGuard.Flags {
        override fun get(key: String) = prefs.getBoolean(key, false)

        // Written straight away: it has to be on disk even if the app dies a moment later.
        override fun set(key: String, value: Boolean) {
            prefs.edit().putBoolean(key, value).commit()
        }
    })

    private val _gpuDisabled = MutableStateFlow(gpuGuard.disabled)

    /** True when the GPU was switched off because the app ended while trying it. */
    val gpuDisabled = _gpuDisabled.asStateFlow()

    private val _gpuDevices = MutableStateFlow<List<GpuDevice>?>(null)

    /** The GPUs found the last time the phone was checked, or null if it has not been checked. */
    val gpuDevices = _gpuDevices.asStateFlow()

    private val _probingGpus = MutableStateFlow(false)
    val probingGpus = _probingGpus.asStateFlow()

    private val _gpuStatus = MutableStateFlow(GpuStatus(false, ""))

    /** Where the loaded model is running, for the Models screen. */
    val gpuStatus = _gpuStatus.asStateFlow()

    /** Looks for GPUs this phone can use. This is the first time the phone's graphics driver is touched. */
    fun checkForGpus() {
        if (_probingGpus.value || _gpuDisabled.value) return
        _probingGpus.value = true
        viewModelScope.launch {
            gpuGuard.attemptStarting()
            try {
                _gpuDevices.value = engine.listGpus()
            } catch (e: Exception) {
                _gpuDevices.value = emptyList()
                _error.value = "Could not look for GPUs: ${e.message ?: e::class.simpleName}"
            } finally {
                gpuGuard.attemptFinished()
                _probingGpus.value = false
            }
        }
    }

    fun allowGpuAgain() {
        gpuGuard.allowAgain()
        _gpuDisabled.value = false
    }

    private val adapterStore = AdapterStore(app)

    /** The adapter files in the app's storage, for choosing from in a model's settings. */
    private val _adapters = MutableStateFlow<List<LocalAdapter>>(emptyList())
    val adapters = _adapters.asStateFlow()

    private fun refreshAdapters() {
        viewModelScope.launch { _adapters.value = adapterStore.list() }
    }

    /**
     * Copies an adapter file into the app for [model]. [onDone] gets its file name, or the reason it cannot be used.
     */
    fun importAdapter(uri: Uri, model: LocalModel, onDone: (name: String?, problem: String?) -> Unit) {
        viewModelScope.launch {
            try {
                val architecture = ModelInfo.read(model.path)?.architecture
                val name = adapterStore.import(uri, architecture)
                refreshAdapters()
                onDone(name, null)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                onDone(null, e.message ?: "That file could not be used.")
            }
        }
    }

    /** Deletes adapter files that no model uses any more. */
    private fun pruneAdapters() {
        val inUse = _modelSettings.value.values.flatMap { it.adapters }.map { it.file }.toSet()
        viewModelScope.launch {
            adapterStore.prune(inUse)
            _adapters.value = adapterStore.list()
        }
    }

    /** The context length that will be used for [model], for the memory estimate. */
    fun contextFor(model: LocalModel): Int = paramsFor(model).contextSize

    private fun presetOf(model: LocalModel?): String? = model?.let { _modelSettings.value[it.fileName]?.presetId }

    /** Saves [settings] for [model], or makes it follow the app-wide settings again when null. */
    fun setModelSettings(model: LocalModel, settings: ModelSettings?) {
        val before = paramsFor(model)
        val presetBefore = presetOf(model)
        _modelSettings.update { map -> if (settings == null) map - model.fileName else map + (model.fileName to settings) }
        prefs.edit().putString(KEY_MODEL_SETTINGS, ModelSettingsStore.toJson(_modelSettings.value)).apply()
        pruneAdapters()
        if (_loadedModel.value?.fileName != model.fileName || _sleeping.value) return

        val after = paramsFor(model)
        if (presetBefore != presetOf(model)) refreshSystemPrompt()
        // Temperature and top-p can change on the spot; the rest needs the model loaded again.
        if (!anythingRunning()) {
            viewModelScope.launch {
                runCatching {
                    awaitModelReady()
                    engine.updateSampling(after.temperature, after.topP)
                }
            }
        }
        if (before.copy(temperature = 0f, topP = 0f) != after.copy(temperature = 0f, topP = 0f)) {
            _error.value = "Saved. Load the model again for the other changes to apply."
        }
    }

    // ---- Notes the model always remembers ---------------------------------------------

    private val _notes = MutableStateFlow(MemoryNotes.fromJson(prefs.getString(KEY_NOTES, null)))

    /** Facts about the user that are added to every chat's system prompt while switched on. */
    val notes = _notes.asStateFlow()

    private fun changeNotes(change: (List<Note>) -> List<Note>) {
        _notes.update(change)
        prefs.edit().putString(KEY_NOTES, MemoryNotes.toJson(_notes.value)).apply()
        refreshSystemPrompt()
        scheduleSettingsSync()
    }

    fun addNote(text: String) {
        val clean = text.trim().take(MemoryNotes.MAX_NOTE_CHARS)
        if (clean.isEmpty()) return
        changeNotes { it + Note(UUID.randomUUID().toString(), clean) }
    }

    fun updateNote(note: Note, text: String) {
        val clean = text.trim().take(MemoryNotes.MAX_NOTE_CHARS)
        if (clean.isEmpty()) return
        changeNotes { list -> list.map { if (it.id == note.id) it.copy(text = clean) else it } }
    }

    fun setNoteEnabled(note: Note, on: Boolean) {
        changeNotes { list -> list.map { if (it.id == note.id) it.copy(enabled = on) else it } }
    }

    fun deleteNote(note: Note) {
        changeNotes { list -> list.filterNot { it.id == note.id } }
    }

    /** Rebuilds the model's memory with the current system prompt and replays the open chat, when the model is idle. */
    private fun refreshSystemPrompt() {
        val model = _loadedModel.value ?: return
        if (_sleeping.value || anythingRunning()) return
        viewModelScope.launch {
            try {
                resetOrReload(model)
                pendingContext = Replay.build(turns())
            } catch (e: Exception) {
                _error.value = "Could not update the model's instructions: ${e.message ?: e::class.simpleName}"
            }
        }
    }

    // ---- Saved chat state ---------------------------------------------------------------

    private val stateCache = ChatStateCache(app)

    private val _saveStates = MutableStateFlow(prefs.getBoolean(KEY_SAVE_STATES, true))

    /** Whether the model's memory of each chat is saved, so reopening a chat does not mean reading it all again. */
    val saveStates = _saveStates.asStateFlow()

    fun setSaveStates(on: Boolean) {
        _saveStates.value = on
        prefs.edit().putBoolean(KEY_SAVE_STATES, on).apply()
        if (!on) viewModelScope.launch { stateCache.clear() }
    }

    /** The open chat as the model should see it again: what is on screen, minus empty bubbles. */
    private fun turns(messages: List<ChatMessage> = _messages.value): List<Turn> =
        messages.filter { it.isUser || it.text.isNotEmpty() }.map { Turn(it.isUser, it.text, it.summary) }

    /** Everything a saved state depends on: change any of it and the state no longer applies. */
    private fun stateTag(model: LocalModel, history: List<Turn>): String {
        val file = File(model.path)
        val params = paramsFor(model)
        return ChatState.tag(
            ChatState.modelId(model.fileName, file.length(), file.lastModified()),
            params.contextSize, params.chatTemplate, chatSystemPrompt, history,
            LoraCheck.signature(params.adapters.map { a ->
                File(a.path).let { f -> LoraAdapterInfo(f.name, f.length(), f.lastModified(), a.scale) }
            }),
            params.cacheBits,
        )
    }

    /** Saves what the model remembers of the open chat, once a reply is finished. */
    private fun saveChatState() {
        if (!_saveStates.value || _incognito.value) return
        val id = currentChatId ?: return
        val model = _loadedModel.value ?: return
        val history = turns()
        if (_sleeping.value || contextStale || pendingContext != null || history.none { it.isUser }) return
        val used = engine.contextUsage().used
        if (used <= 0) return
        viewModelScope.launch {
            if (!apiMutex.tryLock()) return@launch
            try {
                if (_generating.value || _loading.value || history != turns() || id != currentChatId) return@launch
                awaitModelReady()
                val result = engine.saveState(stateCache.prefix(id), stateTag(model, history), stateCache.maxBytes(), used)
                if (result > 0) stateCache.trim(keep = id)
                else if (result == InferenceEngine.STATE_TOO_BIG) stateCache.delete(id)
            } catch (e: Exception) {
                AppLog.note("Could not save the chat state: ${e.message}")
            } finally {
                apiMutex.unlock()
            }
        }
    }

    // ---- Compare two models ---------------------------------------------------------

    private val _compare = MutableStateFlow<List<CompareSide>>(emptyList())
    val compare = _compare.asStateFlow()

    private val _comparing = MutableStateFlow(false)
    val comparing = _comparing.asStateFlow()

    private var compareJob: Job? = null

    private fun updateSide(index: Int, change: (CompareSide) -> CompareSide) {
        _compare.update { list -> list.mapIndexed { i, side -> if (i == index) change(side) else side } }
    }

    /**
     * Asks both models the same [prompt], one after the other (only one fits in memory at a time),
     * then puts the chat's own model back.
     */
    fun runCompare(a: LocalModel, b: LocalModel, prompt: String) {
        val question = prompt.trim()
        if (question.isEmpty()) return
        if (a.path == b.path) {
            _error.value = "Pick two different models."
            return
        }
        if (_generating.value || _loading.value || _benchmarking.value || _comparing.value || _apiBusy.value) return

        val previous = _loadedModel.value
        val models = listOf(a, b)
        _compare.value = models.map { CompareSide(it.fileName) }
        _comparing.value = true
        _loading.value = true

        compareJob = viewModelScope.launch {
            try {
                models.forEachIndexed { i, model ->
                    updateSide(i) { it.copy(phase = ComparePhase.Loading) }
                    try {
                        loadIntoEngine(model, applyJson = false)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        updateSide(i) { it.copy(phase = ComparePhase.Failed, text = "Could not load: ${e.message ?: e::class.simpleName}") }
                        return@forEachIndexed
                    }

                    updateSide(i) { it.copy(phase = ComparePhase.Generating) }
                    val start = SystemClock.elapsedRealtime()
                    var firstAt = 0L
                    var count = 0
                    try {
                        engine.sendUserPrompt(question).collect { token ->
                            if (count == 0) firstAt = SystemClock.elapsedRealtime()
                            count++
                            updateSide(i) { it.copy(text = it.text + token) }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        updateSide(i) { it.copy(phase = ComparePhase.Failed, text = it.text + "\n\nFailed: ${e.message ?: e::class.simpleName}") }
                        return@forEachIndexed
                    }
                    val genMs = (SystemClock.elapsedRealtime() - firstAt).coerceAtLeast(1)
                    val stats = if (count > 0) GenStats(
                        tokens = count,
                        tokensPerSecond = if (count > 1) (count - 1) * 1000f / genMs else null,
                        firstTokenMs = firstAt - start,
                    ) else null
                    updateSide(i) { it.copy(phase = ComparePhase.Done, stats = stats) }
                }
            } finally {
                withContext(NonCancellable) {
                    try {
                        if (previous != null) {
                            val restored = loadIntoEngine(previous, restoreFor = turns())
                            pendingContext = if (restored) null else Replay.build(turns())
                        } else {
                            if (engine.state.value.isModelLoaded) engine.cleanUp()
                            _loadedModel.value = null
                            _visionReady.value = false
                        }
                    } catch (e: Exception) {
                        _loadedModel.value = null
                        _error.value = "Could not restore the previous model: ${e.message ?: e::class.simpleName}"
                    }
                    _comparing.value = false
                    _loading.value = false
                }
            }
        }
    }

    fun cancelCompare() {
        compareJob?.cancel()
    }

    // ---- App lock ---------------------------------------------------------------

    private val _lockEnabled = MutableStateFlow(prefs.getBoolean(KEY_LOCK, false))
    val lockEnabled = _lockEnabled.asStateFlow()

    private val _locked = MutableStateFlow(_lockEnabled.value)

    /** True while the unlock screen covers the app. Starts locked when the lock is on. */
    val locked = _locked.asStateFlow()

    fun setAppLock(enabled: Boolean) {
        if (enabled) {
            val allowed = BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
            if (BiometricManager.from(getApplication()).canAuthenticate(allowed) != BiometricManager.BIOMETRIC_SUCCESS) {
                _error.value = "Set up a screen lock or fingerprint in your phone's settings first."
                return
            }
        }
        _lockEnabled.value = enabled
        _locked.value = false
        prefs.edit().putBoolean(KEY_LOCK, enabled).apply()
    }

    fun unlock() {
        _locked.value = false
    }

    fun lockIfEnabled() {
        if (_lockEnabled.value) _locked.value = true
    }

    // ---- Benchmark --------------------------------------------------------------

    private val _benchmarks = MutableStateFlow(BenchResult.fromJson(prefs.getString(KEY_BENCH, null)))
    val benchmarks = _benchmarks.asStateFlow()

    private val _benchmarking = MutableStateFlow(false)
    val benchmarking = _benchmarking.asStateFlow()

    /** Times prompt reading and reply speed for the loaded model. Takes a few seconds to a minute. */
    fun runBenchmark() {
        val model = _loadedModel.value ?: return
        if (_benchmarking.value || _generating.value || _loading.value || _apiBusy.value) return
        viewModelScope.launch {
            _benchmarking.value = true
            try {
                if (!wakeIfAsleep()) return@launch
                val raw = engine.bench(pp = 128, tg = 64, pl = 1, nr = 2)
                val (prompt, generation) = BenchResult.parseSpeeds(raw)
                if (prompt == null && generation == null) {
                    _error.value = "Benchmark failed. Try unloading other models and run it again."
                } else {
                    val result = BenchResult(model.fileName, prompt, generation, System.currentTimeMillis())
                    _benchmarks.update { (listOf(result) + it).take(MAX_BENCH_RESULTS) }
                    prefs.edit().putString(KEY_BENCH, BenchResult.toJson(_benchmarks.value)).apply()
                }
            } catch (e: Exception) {
                _error.value = "Benchmark failed: ${e.message ?: e::class.simpleName}"
            } finally {
                _benchmarking.value = false
            }
        }
    }

    fun clearBenchmarks() {
        _benchmarks.value = emptyList()
        prefs.edit().remove(KEY_BENCH).apply()
    }

    // ---- Battery and heat -------------------------------------------------------

    private val _deviceStatus = MutableStateFlow(DeviceStatus())
    val deviceStatus = _deviceStatus.asStateFlow()

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = readBattery(intent)
    }

    private val thermalListener = PowerManager.OnThermalStatusChangedListener { status ->
        _deviceStatus.update { it.copy(thermal = status) }
    }

    private fun readBattery(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        if (level < 0 || scale <= 0) return
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        _deviceStatus.update { it.copy(batteryPercent = level * 100 / scale, charging = charging) }
    }

    private fun startDeviceMonitoring() {
        val app = getApplication<Application>()
        ContextCompat.registerReceiver(
            app, batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED,
        )?.let(::readBattery)
        val power = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        _deviceStatus.update { it.copy(thermal = power.currentThermalStatus) }
        power.addThermalStatusListener(thermalListener)
    }

    private val _autoThrottle = MutableStateFlow(prefs.getBoolean(KEY_AUTO_THROTTLE, true))

    /** Whether the model is held back (fewer threads, shorter replies) when the phone is hot or low on battery. */
    val autoThrottle = _autoThrottle.asStateFlow()

    fun setAutoThrottle(on: Boolean) {
        _autoThrottle.value = on
        prefs.edit().putBoolean(KEY_AUTO_THROTTLE, on).apply()
    }

    private val _throttle = MutableStateFlow(ThrottleLevel.None)

    /** How far the model is being held back right now. */
    val throttle = _throttle.asStateFlow()

    /** Applies the throttle whenever the phone's heat or battery, or the setting, changes. */
    private fun watchThrottle() {
        viewModelScope.launch {
            combine(_deviceStatus, _autoThrottle) { status, on -> status to on }.collect { (status, on) ->
                val level = if (on) ThrottlePolicy.level(status, _throttle.value) else ThrottleLevel.None
                if (level != _throttle.value) {
                    _throttle.value = level
                    runCatching { engine.setThreadShare(ThrottlePolicy.threadPercent(level)) }
                    AppLog.note("Throttle: $level (thermal ${status.thermal}, battery ${status.batteryPercent}%, charging ${status.charging})")
                }
            }
        }
    }

    private fun stopDeviceMonitoring() {
        val app = getApplication<Application>()
        runCatching { app.unregisterReceiver(batteryReceiver) }
        (app.getSystemService(Context.POWER_SERVICE) as PowerManager).removeThermalStatusListener(thermalListener)
    }

    // ---- Storage ----------------------------------------------------------------

    private val storageManager = StorageManager(app)
    private val _storage = MutableStateFlow<StorageReport?>(null)
    val storage = _storage.asStateFlow()

    /**
     * Images and documents some chat still uses. The saved chats are read from disk here rather than taken
     * from memory, which is empty until the first load finishes and would make everything look unused.
     */
    private suspend fun referencedFiles(): Pair<Set<String>, Set<String>> {
        val chats = chatStore.list().also { _chats.value = it }
        val images = (chats.flatMap { c -> c.messages.flatMap { it.images } } +
            _messages.value.flatMap { it.images } + _pendingImages.value).toSet()
        val documents = (
            chats.flatMap { c -> c.attachments.map { it.id } } + _attachments.value.map { it.id } +
                _projects.value.flatMap { p -> p.documents.map { it.id } }
            ).toSet()
        return images to documents
    }

    fun refreshStorage() {
        viewModelScope.launch {
            val (images, documents) = referencedFiles()
            _storage.value = storageManager.report(images, documents)
        }
    }

    fun cleanUpStorage() {
        viewModelScope.launch {
            val (images, documents) = referencedFiles()
            val removed = storageManager.deleteUnused(images, documents)
            storageManager.clearCache()
            _error.value = if (removed == 0) "Cache cleared. No unused files found." else "Cache cleared and $removed unused files removed."
            refreshStorage()
        }
    }

    fun deleteAllChats() {
        viewModelScope.launch {
            _chats.value.forEach { chat ->
                rememberDeletion(chat)
                chatStore.delete(chat.id)
                chat.attachments.forEach { docStore.delete(it.id) }
                chat.messages.flatMap { it.images }.forEach { imageStore.delete(it) }
            }
            stateCache.clear()
            clearConversation()
            _loadedModel.value?.let { load(it) }
            refreshChats()
            refreshStorage()
            scheduleSync()
        }
    }

    // ---- Text from other apps ---------------------------------------------------

    private val _incomingText = MutableStateFlow<String?>(null)

    /** Text shared into the app, waiting to be put in the message box. */
    val incomingText = _incomingText.asStateFlow()

    fun receiveText(text: String) {
        _incomingText.value = text.take(MAX_INCOMING_CHARS)
    }

    fun consumeIncomingText() {
        _incomingText.value = null
    }

    // ---- Local API server ------------------------------------------------------------

    private val apiMutex = Mutex()
    private val _apiBusy = MutableStateFlow(false)

    /** True while the API server is answering a request, which uses the same model as the chat. */
    val apiBusy = _apiBusy.asStateFlow()

    private val _apiEnabled = MutableStateFlow(prefs.getBoolean(KEY_API_ENABLED, false))
    val apiEnabled = _apiEnabled.asStateFlow()

    val apiStatus = ApiGateway.status

    private val _apiPort = MutableStateFlow(prefs.getInt(ApiService.KEY_PORT, ApiService.DEFAULT_PORT))
    val apiPort = _apiPort.asStateFlow()

    // A key always exists, so the server is never open to everyone on the network by accident.
    private val _apiKey = MutableStateFlow(
        prefs.getString(ApiService.KEY_API_KEY, null) ?: newApiKey().also { prefs.edit().putString(ApiService.KEY_API_KEY, it).apply() }
    )
    val apiKey = _apiKey.asStateFlow()

    private fun newApiKey(): String {
        val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return "pk-" + bytes.joinToString("") { "%02x".format(it) }
    }

    fun setApiEnabled(on: Boolean) {
        _apiEnabled.value = on
        prefs.edit().putBoolean(KEY_API_ENABLED, on).apply()
        if (on) ApiService.start(getApplication()) else ApiService.stop(getApplication())
    }

    private val _apiHttps = MutableStateFlow(prefs.getBoolean(ApiService.KEY_HTTPS, false))

    /** Whether the API server uses HTTPS, with a certificate this phone makes for itself. */
    val apiHttps = _apiHttps.asStateFlow()

    private val _wifiOnly = MutableStateFlow(prefs.getBoolean(DownloadCenter.KEY_WIFI_ONLY, false))

    /** Whether model downloads wait for Wi-Fi (or another connection that is not billed by use). */
    val wifiOnly = _wifiOnly.asStateFlow()

    fun setWifiOnly(on: Boolean) {
        _wifiOnly.value = on
        prefs.edit().putBoolean(DownloadCenter.KEY_WIFI_ONLY, on).apply()
    }

    // ---- The model catalog, with the online additions -----------------------------------------

    private val catalogStore = CatalogStore(app)
    private val _remoteCatalog = MutableStateFlow<RemoteCatalog.Parsed?>(null)

    private val _catalogUpdates = MutableStateFlow(prefs.getBoolean(KEY_CATALOG_UPDATES, true))

    /** Whether the model list is refreshed from PocketLM's repository when Models is opened. */
    val catalogUpdates = _catalogUpdates.asStateFlow()

    fun setCatalogUpdates(on: Boolean) {
        _catalogUpdates.value = on
        prefs.edit().putBoolean(KEY_CATALOG_UPDATES, on).apply()
    }

    /** The built-in models plus any the online list adds. */
    val catalog: StateFlow<List<CatalogModel>> = _remoteCatalog
        .map { RemoteCatalog.merge(ModelCatalog.models, it?.models.orEmpty()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ModelCatalog.models)

    /** The built-in search models plus any the online list adds. */
    val embeddingCatalog: StateFlow<List<CatalogModel>> = _remoteCatalog
        .map { RemoteCatalog.merge(ModelCatalog.embeddings, it?.embeddings.orEmpty()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ModelCatalog.embeddings)

    /** How many models the online list added, for a line on the Models screen. */
    val onlineModelCount: StateFlow<Int> = _remoteCatalog
        .map { parsed -> parsed?.let { p -> p.models.count { m -> ModelCatalog.models.none { it.fileName == m.fileName } } } ?: 0 }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    /** Reads the saved online list, and fetches a fresh one if it is due and the setting is on. */
    fun refreshCatalog() {
        viewModelScope.launch {
            if (_remoteCatalog.value == null) _remoteCatalog.value = withContext(Dispatchers.IO) { catalogStore.cached() }
            if (_catalogUpdates.value && catalogStore.due()) {
                catalogStore.refresh()?.let { _remoteCatalog.value = it }
            }
        }
    }

    private val tlsStore = TlsStore(File(app.filesDir, ApiService.TLS_DIR))

    private val _apiCertificate = MutableStateFlow<ApiCertificate?>(null)

    /** The certificate the HTTPS server uses, for checking or handing to the apps that call it. */
    val apiCertificate = _apiCertificate.asStateFlow()

    fun setApiHttps(on: Boolean) {
        _apiHttps.value = on
        prefs.edit().putBoolean(ApiService.KEY_HTTPS, on).apply()
        if (on) refreshApiCertificate() else _apiCertificate.value = null
        restartApiIfOn()
    }

    /** Reads (making it first, if needed) the certificate the server will use. */
    fun refreshApiCertificate() {
        if (!_apiHttps.value) return
        viewModelScope.launch {
            _apiCertificate.value = try {
                withContext(Dispatchers.IO) {
                    val certificate = tlsStore.identity(LocalNetwork.addresses()).certificate
                    ApiCertificate(SelfSignedCertificate.fingerprint(certificate), SelfSignedCertificate.pem(certificate), certificate.notAfter.time)
                }
            } catch (e: Exception) {
                _error.value = "Could not make the HTTPS certificate: ${e.message}"
                null
            }
        }
    }

    /** Makes a new certificate. Apps that trusted the old one have to be given the new one. */
    fun resetApiCertificate() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { tlsStore.delete() }
            refreshApiCertificate()
            restartApiIfOn()
        }
    }

    private fun restartApiIfOn() {
        if (_apiEnabled.value) {
            ApiService.stop(getApplication())
            ApiService.start(getApplication())
        }
    }

    fun setApiPort(port: Int) {
        if (port !in 1024..65535) {
            _error.value = "Pick a port between 1024 and 65535."
            return
        }
        _apiPort.value = port
        prefs.edit().putInt(ApiService.KEY_PORT, port).apply()
        if (_apiEnabled.value) {
            ApiService.stop(getApplication())
            ApiService.start(getApplication())
        }
    }

    fun regenerateApiKey() {
        val key = newApiKey()
        _apiKey.value = key
        prefs.edit().putString(ApiService.KEY_API_KEY, key).apply()
    }

    private val apiBackend = object : ApiBackend {
        override fun modelNames() = listOfNotNull(
            _loadedModel.value?.fileName?.removeSuffix(".gguf"),
            selectedEmbeddingModel()?.fileName?.removeSuffix(".gguf"),
        )
        override suspend fun complete(request: ChatRequest, onToken: (String) -> Unit) = serveApiRequest(request, onToken)

        override suspend fun embed(inputs: List<String>): EmbeddingsResult {
            val model = selectedEmbeddingModel()
                ?: throw ApiException(503, "No embedding model is selected. Choose one in PocketLM, Settings, Document search.")
            return try {
                ensureEmbedder(model)
                val vectors = inputs.map { embedder.embed(it) }
                EmbeddingsResult(vectors, model.fileName.removeSuffix(".gguf"), inputs.sumOf { embedder.countTokens(it).coerceAtLeast(0) })
            } catch (e: EmbeddingException) {
                throw ApiException(503, e.message ?: "The embedding model could not be used.")
            }
        }
    }

    private suspend fun awaitModelReady() {
        withTimeoutOrNull(5_000) {
            engine.state.first { it is InferenceEngine.State.ModelReady || it is InferenceEngine.State.Error }
        }
    }

    /**
     * Answers one API request with the loaded model. The model keeps no memory between API calls, so the
     * earlier turns in the request are replayed inside the prompt. Afterwards the chat's own context is restored.
     */
    private suspend fun serveApiRequest(request: ChatRequest, onToken: (String) -> Unit): Int {
        if (_loadedModel.value == null) throw ApiException(503, "No model is loaded. Load one in PocketLM first.")
        if (_generating.value || _loading.value || _benchmarking.value || _comparing.value) {
            throw ApiException(503, "PocketLM is busy with the chat. Try again in a moment.")
        }
        if (!apiMutex.tryLock()) throw ApiException(503, "Another API request is running. Try again in a moment.")
        _apiBusy.value = true
        touch()
        val chatSampling = _loadedModel.value?.let(::paramsFor) ?: _generationParams.value
        var changedSampling = false
        try {
            if (!wakeIfAsleep(restore = false)) throw ApiException(503, "The model could not be loaded again.")
            val baseSystem = request.messages.firstOrNull { it.role == "system" }?.content?.takeIf { it.isNotBlank() } ?: selectedPrompt
            // Functions the caller describes are explained to the model in its instructions.
            val system = if (request.usesTools) baseSystem.trimEnd() + "\n\n" + ApiTools.systemBlock(request.tools, request.toolChoice) else baseSystem
            val conversation = ApiTools.conversation(request.messages)
            val question = conversation.question
            if (question.isBlank()) throw ApiException(400, "The last user message is empty.")
            val earlier = Replay.build(conversation.earlier)
            val prompt = if (earlier == null) question
            else "Here is our earlier conversation:\n$earlier\n\nContinue from there. My new message: $question"

            engine.setOutputGrammar("")
            engine.resetConversation(system)
            if (request.jsonSchema != null) {
                if (!engine.setOutputSchema(request.jsonSchema)) {
                    throw ApiException(400, "response_format: the model could not use that schema")
                }
            } else if (request.jsonObject) {
                engine.setOutputGrammar(JsonGrammar.gbnf)
            }
            if (request.temperature != null || request.topP != null) {
                engine.updateSampling(request.temperature ?: chatSampling.temperature, request.topP ?: chatSampling.topP)
                changedSampling = true
            }
            var count = 0
            engine.sendUserPrompt(prompt, request.maxTokens ?: InferenceEngine.DEFAULT_PREDICT_LENGTH).collect {
                count++
                onToken(it)
            }
            return count
        } finally {
            withContext(NonCancellable) {
                try {
                    awaitModelReady()
                    if (changedSampling) engine.updateSampling(chatSampling.temperature, chatSampling.topP)
                    engine.resetConversation(chatSystemPrompt)
                    refreshContextUsage()
                    applyOutputConstraint()
                    pendingContext = Replay.build(turns())
                    contextStale = false
                } catch (e: Exception) {
                    _error.value = "Could not restore the chat after an API request: ${e.message}"
                }
                touch()
                _apiBusy.value = false
                apiMutex.unlock()
            }
        }
    }

    // ---- Backup and restore ------------------------------------------------------------

    /** The settings that travel in a backup. Secrets (API key, app lock) and per-phone choices are left out. */
    private val backupKeys = setOf(
        KEY_THEME, KEY_TEXT_SCALE, KEY_CTX, KEY_CACHE_BITS, KEY_THREADS, KEY_TEMP, KEY_TOP_P, KEY_TOP_K, KEY_MIN_P, KEY_REPEAT, KEY_SEED,
        KEY_STOP, KEY_PRESETS, KEY_PRESET_ID, KEY_PROMPTS, KEY_TOOLS, KEY_BG_REPLIES, KEY_READ_ALOUD, KEY_IDLE_MINUTES,
        KEY_MAX_REPLY, KEY_MODEL_SETTINGS, KEY_NOTES, KEY_SAVE_STATES, KEY_OCR_SCRIPT,
    )

    private fun backupManager(): BackupManager {
        val files = getApplication<Application>().filesDir
        return BackupManager(File(files, "chats"), File(files, "images"), File(files, "docs"))
    }

    fun exportBackup(uri: Uri) {
        viewModelScope.launch {
            try {
                val chats = withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openOutputStream(uri)?.use { out ->
                        backupManager().export(out, SettingsBackup.toJson(prefs.all, backupKeys), System.currentTimeMillis())
                    } ?: throw IOException("Cannot write to that location")
                }
                _error.value = "Backed up $chats chats and your settings"
            } catch (e: Exception) {
                _error.value = "Backup failed: ${e.message ?: e::class.simpleName}"
            }
        }
    }

    fun importBackup(uri: Uri) {
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use { backupManager().restore(it) }
                        ?: throw IOException("Cannot open that file")
                }
                var settingsApplied = 0
                result.settings?.let { json -> settingsApplied = applySettings(json, backupKeys) }
                refreshChats()
                scheduleSync()
                _error.value = buildString {
                    append("Restored ${result.chatsRestored} chats")
                    if (result.chatsKept > 0) append(" (${result.chatsKept} were already up to date)")
                    if (settingsApplied > 0) append(" and your settings")
                }
            } catch (e: Exception) {
                _error.value = "Restore failed: ${e.message ?: e::class.simpleName}"
            }
        }
    }

    /** Writes the allowed settings from [json] into preferences and updates the screens. Returns how many were applied. */
    private fun applySettings(json: JSONObject, allowed: Set<String>): Int {
        val editor = prefs.edit()
        val applied = SettingsBackup.apply(json, allowed) { key, value ->
            when (value) {
                is String -> editor.putString(key, value)
                is Int -> editor.putInt(key, value)
                is Float -> editor.putFloat(key, value)
                is Boolean -> editor.putBoolean(key, value)
            }
        }
        editor.apply()
        reloadSettings()
        return applied
    }

    /** Reads every backed-up setting from preferences again, after a restore changed them. */
    private fun reloadSettings() {
        _themeMode.value = ThemeMode.entries.firstOrNull { it.name == prefs.getString(KEY_THEME, null) } ?: ThemeMode.System
        _textScale.value = DisplayScale.snapText(prefs.getFloat(KEY_TEXT_SCALE, 1f))
        _generationParams.value = GenerationParams(
            contextSize = prefs.getInt(KEY_CTX, DEFAULT_PARAMS.contextSize),
            threads = prefs.getInt(KEY_THREADS, DEFAULT_PARAMS.threads),
            temperature = prefs.getFloat(KEY_TEMP, DEFAULT_PARAMS.temperature),
            topP = prefs.getFloat(KEY_TOP_P, DEFAULT_PARAMS.topP),
            topK = prefs.getInt(KEY_TOP_K, DEFAULT_PARAMS.topK),
            minP = prefs.getFloat(KEY_MIN_P, DEFAULT_PARAMS.minP),
            repeatPenalty = prefs.getFloat(KEY_REPEAT, DEFAULT_PARAMS.repeatPenalty),
            seed = prefs.getInt(KEY_SEED, DEFAULT_PARAMS.seed),
            cacheBits = prefs.getInt(KEY_CACHE_BITS, DEFAULT_PARAMS.cacheBits),
        )
        _stopWords.value = prefs.getString(KEY_STOP, "") ?: ""
        _customPresets.value = Presets.fromJson(prefs.getString(KEY_PRESETS, null))
        _selectedPresetId.value = prefs.getString(KEY_PRESET_ID, null) ?: Presets.DEFAULT_ID
        _customPrompts.value = PromptLibrary.fromJson(prefs.getString(KEY_PROMPTS, null))
        _enabledTools.value = Tools.parseEnabled(prefs.getString(KEY_TOOLS, null))
        _backgroundReplies.value = prefs.getBoolean(KEY_BG_REPLIES, true)
        _readAloud.value = prefs.getBoolean(KEY_READ_ALOUD, false)
        _idleMinutes.value = prefs.getInt(KEY_IDLE_MINUTES, 0)
        _maxReplyTokens.value = prefs.getInt(KEY_MAX_REPLY, InferenceEngine.DEFAULT_PREDICT_LENGTH)
        setOcrScriptInternal(OcrScript.fromId(prefs.getString(KEY_OCR_SCRIPT, null)))
        _modelSettings.value = ModelSettingsStore.fromJson(prefs.getString(KEY_MODEL_SETTINGS, null))
        _notes.value = MemoryNotes.fromJson(prefs.getString(KEY_NOTES, null))
        _saveStates.value = prefs.getBoolean(KEY_SAVE_STATES, true)
    }

    // ---- Sync between devices ---------------------------------------------------------

    private val syncRunner = SyncRunner.get(app)
    private val syncConfig = syncRunner.config
    private val syncState = syncRunner.state
    private var syncDebounce: Job? = null
    private var syncAgain = false

    private val _sync = MutableStateFlow(SyncStatus())

    /** What Settings shows about syncing: where to, when it last worked, and what went wrong. */
    val syncStatus = _sync.asStateFlow()

    private fun publishSyncStatus(running: Boolean = false, progress: String = "", connectError: String? = null) {
        _sync.value = syncConfig.status(syncState.get().lastSyncAt).copy(running = running, progress = progress, connectError = connectError)
    }

    /** Hears about syncs from the shared runner, including those the background job starts. */
    private val syncListener = object : SyncListener {
        override fun onStarted() = publishSyncStatus(running = true, progress = "Starting")

        override fun onProgress(text: String) {
            _sync.update { it.copy(progress = text) }
        }

        override fun onFinished(outcome: SyncOutcome) {
            publishSyncStatus()
            if (outcome is SyncOutcome.Done && outcome.report.changedHere) refreshChats()
            if (syncAgain) {
                syncAgain = false
                viewModelScope.launch { scheduleSync(SYNC_SOON_MS) }
            }
        }

        override fun onSettingsApplied() {
            reloadSettings()
        }

        // The chat on screen is never replaced under the user; it updates after they leave it.
        override fun openChatIds(): Set<String> = setOfNotNull(currentChatId)
    }

    /**
     * Connects this phone to a sync location. The first phone to use a location sets its passphrase (and must
     * repeat it); phones that join later enter the same one. Nothing is stored unless the location can be written to.
     */
    fun connectSync(target: SyncTarget, passphrase: String, repeat: String) {
        if (_sync.value.running) return
        target.problem()?.let { publishSyncStatus(connectError = it); return }
        if (passphrase.isEmpty()) { publishSyncStatus(connectError = "Enter a passphrase."); return }
        viewModelScope.launch {
            publishSyncStatus(running = true, progress = "Connecting")
            try {
                val app = getApplication<Application>()
                val (header, key) = withContext(Dispatchers.IO) {
                    val backend = target.open(app)
                    backend.checkAccess()
                    val existing = backend.get(SyncNames.HEADER)
                    if (existing != null) {
                        val header = SyncHeader.fromJson(String(existing, Charsets.UTF_8))
                        val key = SyncCrypto.keyFor(header, passphrase.toCharArray())
                            ?: throw SyncException("That passphrase does not match the one used for this sync location.")
                        header to key
                    } else {
                        if (passphrase.length < SyncRunner.MIN_PASSPHRASE) {
                            throw SyncException("Choose a passphrase of at least ${SyncRunner.MIN_PASSPHRASE} characters.")
                        }
                        if (passphrase != repeat) throw SyncException("The two passphrases are not the same.")
                        val (header, key) = SyncCrypto.newHeader(passphrase.toCharArray())
                        backend.put(SyncNames.HEADER, header.toJson().toByteArray())
                        header to key
                    }
                }
                val named = if (target.kind == SyncKind.Folder) {
                    target.copy(folderName = withContext(Dispatchers.IO) { FolderBackend.displayName(app, Uri.parse(target.folderUri)) })
                } else target
                syncConfig.save(named, header, key)
                // What the old location held says nothing about the new one; deletions made here still apply.
                syncState.update { SyncState(tombstones = it.tombstones) }
                syncRunner.updateBackgroundJob()
                publishSyncStatus()
                runSync(manual = true)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                publishSyncStatus(connectError = "Could not connect: ${e.message ?: e::class.simpleName}")
            }
        }
    }

    /** Enters the new passphrase after another device changed it, and carries on syncing with the same location. */
    fun rejoinSync(passphrase: String) {
        val connection = syncConfig.load()
        if (connection == null) {
            publishSyncStatus(connectError = "Sync needs to be set up again on this phone.")
            return
        }
        connectSync(connection.target, passphrase, passphrase)
    }

    /** Locks the sync location with a new passphrase; other devices are asked for it the next time they sync. */
    fun changeSyncPassphrase(current: String, new: String, repeat: String) {
        if (_sync.value.running) return
        viewModelScope.launch {
            publishSyncStatus(running = true, progress = "Checking")
            val outcome = withContext(Dispatchers.IO) { syncRunner.changePassphrase(current, new, repeat) }
            publishSyncStatus(connectError = (outcome as? SyncOutcome.Failed)?.message)
            _error.value = when (outcome) {
                is SyncOutcome.Done -> "Passphrase changed. Enter it on your other devices when they ask."
                is SyncOutcome.Failed -> outcome.message
                is SyncOutcome.Busy -> "A sync is running. Try again in a moment."
                else -> "Passphrase was not changed."
            }
        }
    }

    fun disconnectSync() {
        syncDebounce?.cancel()
        syncConfig.clear()
        syncState.update { SyncState(tombstones = it.tombstones) }
        syncRunner.updateBackgroundJob()
        publishSyncStatus()
        _error.value = "Sync is off. Your chats here and the files at the sync location were left as they are."
    }

    fun setSyncSettings(on: Boolean) {
        syncConfig.setSyncSettings(on)
        publishSyncStatus(_sync.value.running, _sync.value.progress)
        if (on) scheduleSync(SYNC_SOON_MS)
    }

    fun setAutoSync(on: Boolean) {
        syncConfig.setAutoSync(on)
        syncRunner.updateBackgroundJob()
        publishSyncStatus(_sync.value.running, _sync.value.progress)
    }

    fun setSyncWifiOnly(on: Boolean) {
        syncConfig.setWifiOnly(on)
        syncRunner.updateBackgroundJob()
        publishSyncStatus(_sync.value.running, _sync.value.progress)
    }

    /** How often the phone looks for changes while the app is closed, in minutes; 0 for never. */
    fun setSyncBackgroundMinutes(minutes: Int) {
        syncConfig.setBackgroundMinutes(minutes)
        syncRunner.updateBackgroundJob()
        publishSyncStatus(_sync.value.running, _sync.value.progress)
    }

    fun syncNow() = runSync(manual = true)

    /** Syncs shortly after something changed, once the changes stop coming. */
    private fun scheduleSync(delayMs: Long = SYNC_DEBOUNCE_MS) {
        if (!syncConfig.configured || !syncConfig.autoSync) return
        syncDebounce?.cancel()
        syncDebounce = viewModelScope.launch {
            delay(delayMs)
            runSync(manual = false)
        }
    }

    /** For changes to settings, presets, prompts and notes, which only matter when settings sync is on. */
    private fun scheduleSettingsSync() {
        if (syncConfig.syncSettings) scheduleSync()
    }

    /** One sync at a time. A request that arrives during a sync is run right after it, so no change is missed. */
    private fun runSync(manual: Boolean) {
        viewModelScope.launch {
            val job = currentCoroutineContext()[Job]
            val outcome = withContext(Dispatchers.IO) { syncRunner.run(manual) { job?.isActive == false } }
            when (outcome) {
                SyncOutcome.Busy -> syncAgain = true
                is SyncOutcome.Done -> if (manual) _error.value = outcome.report.summary()
                is SyncOutcome.Failed -> if (manual) _error.value = "Sync failed: ${outcome.message}"
                else -> {}
            }
        }
    }

    /** The photo and document files at the sync location that belong to [chat], so a deletion can remove them. */
    private fun remoteFilesOf(chat: SavedChat): List<String> =
        chat.messages.flatMap { it.images }.map { File(it).name }.filter(SyncNames::validImage).map(SyncNames::image) +
            chat.attachments.map { it.id }.filter(SyncNames::validId).map(SyncNames::doc)

    private suspend fun rememberDeletion(chat: SavedChat) {
        if (!syncConfig.configured) return
        withContext(Dispatchers.IO) { syncState.addTombstone(chat.id, System.currentTimeMillis(), remoteFilesOf(chat)) }
    }

    // ---- Conversation memory, cut-off replies, reply length ------------------------------

    private val _contextUsage = MutableStateFlow(ContextUsage(0, 0))

    /** How much of the model's memory this conversation fills. */
    val contextUsage = _contextUsage.asStateFlow()

    private fun refreshContextUsage() {
        _contextUsage.value =
            if (_sleeping.value || _loadedModel.value == null) ContextUsage(0, 0) else engine.contextUsage()
    }

    private val _cutOffId = MutableStateFlow<String?>(null)

    /** Id of a reply that stopped at its length limit, so a Continue button can be offered. */
    val cutOffId = _cutOffId.asStateFlow()

    private val _maxReplyTokens = MutableStateFlow(prefs.getInt(KEY_MAX_REPLY, InferenceEngine.DEFAULT_PREDICT_LENGTH))

    /** Longest a reply may be, in tokens. Applies to the next reply. */
    val maxReplyTokens = _maxReplyTokens.asStateFlow()

    /** The speed preset the current settings match exactly, or null when they are a custom mix. */
    val speedPreset: StateFlow<SpeedPreset?> =
        combine(_generationParams, _maxReplyTokens) { params, maxReply -> SpeedPresets.detect(params, maxReply) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, SpeedPresets.detect(_generationParams.value, _maxReplyTokens.value))

    /** Sets threads, context length, memory cache size and reply length together. Applies the next time a model loads. */
    fun applySpeedPreset(preset: SpeedPreset) {
        setGenerationParams(SpeedPresets.apply(_generationParams.value, preset))
        setMaxReplyTokens(SpeedPresets.maxReplyFor(preset))
    }

    fun setMaxReplyTokens(tokens: Int) {
        _maxReplyTokens.value = tokens
        prefs.edit().putInt(KEY_MAX_REPLY, tokens).apply()
    }

    /** Asks the model to carry on from where a cut-off reply stopped, adding to the same reply. */
    fun continueReply() {
        val last = _messages.value.lastOrNull() ?: return
        if (last.isUser || last.id != _cutOffId.value || anythingRunning()) return
        _cutOffId.value = null
        val prompt = "Continue exactly where you stopped. Do not repeat what you already wrote."
        if (_sleeping.value) {
            viewModelScope.launch { if (wakeIfAsleep()) startGeneration(prompt, continuing = true) }
        } else {
            startGeneration(prompt, continuing = true)
        }
    }

    // ---- Freeing memory when idle ----------------------------------------------------

    private val _idleMinutes = MutableStateFlow(prefs.getInt(KEY_IDLE_MINUTES, 0))

    /** Minutes of inactivity after which the loaded model is unloaded to free memory; 0 means never. */
    val idleMinutes = _idleMinutes.asStateFlow()

    fun setIdleMinutes(minutes: Int) {
        _idleMinutes.value = minutes
        prefs.edit().putInt(KEY_IDLE_MINUTES, minutes).apply()
        touch()
    }

    private val _sleeping = MutableStateFlow(false)

    /** True while the model is unloaded to save memory. The next message wakes it. */
    val sleeping = _sleeping.asStateFlow()

    private var lastActive = SystemClock.elapsedRealtime()

    private fun touch() {
        lastActive = SystemClock.elapsedRealtime()
    }

    private fun anythingRunning() =
        _generating.value || _loading.value || _benchmarking.value || _comparing.value || _apiBusy.value

    private fun sleepIfIdle() {
        val idle = IdlePolicy.shouldSleep(
            nowMs = SystemClock.elapsedRealtime(),
            lastActiveMs = lastActive,
            timeoutMs = _idleMinutes.value * 60_000L,
            busy = anythingRunning(),
            hasModel = _loadedModel.value != null,
            asleep = _sleeping.value,
        )
        if (!idle) return
        runCatching { engine.cleanUp() }
        _sleeping.value = true
        refreshContextUsage()
    }

    /**
     * Reloads the model if it was put to sleep, bringing back the chat's saved state when there is one (or
     * replaying the chat otherwise). With [excludeLast] the newest message, which is about to be answered, is
     * left out of that. Returns false (and reports why) if loading fails.
     */
    private suspend fun wakeIfAsleep(excludeLast: Boolean = false, restore: Boolean = true): Boolean {
        if (!_sleeping.value) return true
        val model = _loadedModel.value ?: run { _sleeping.value = false; return false }
        _loading.value = true
        return try {
            val history = turns().let { if (excludeLast && it.lastOrNull()?.isUser == true) it.dropLast(1) else it }
            val restored = loadIntoEngine(model, restoreFor = history.takeIf { restore })
            pendingContext = if (restored) null else Replay.build(history)
            touch()
            true
        } catch (e: Exception) {
            _loadedModel.value = null
            _error.value = "Could not wake ${model.fileName}: ${e.message ?: e::class.simpleName}"
            false
        } finally {
            _sleeping.value = false
            _loading.value = false
        }
    }

    // ---- Tools the model may use -------------------------------------------------------

    private val _enabledTools = MutableStateFlow(Tools.parseEnabled(prefs.getString(KEY_TOOLS, null)))

    /** Tools the model is told about. Takes effect the next time a model loads or a new chat starts. */
    val enabledTools = _enabledTools.asStateFlow()

    private val _searchServer = MutableStateFlow(prefs.getString(KEY_SEARCH_SERVER, "") ?: "")

    /** Address of the SearXNG server used by the web search tool. */
    val searchServer = _searchServer.asStateFlow()

    fun setSearchServer(address: String) {
        _searchServer.value = address
        prefs.edit().putString(KEY_SEARCH_SERVER, address).apply()
    }

    private val _searchProvider = MutableStateFlow(
        SearchProvider.initial(prefs.getString(KEY_SEARCH_PROVIDER, null), prefs.getString(KEY_SEARCH_SERVER, "") ?: ""),
    )

    /** Which service the web search tool asks. */
    val searchProvider = _searchProvider.asStateFlow()

    fun setSearchProvider(provider: SearchProvider) {
        _searchProvider.value = provider
        prefs.edit().putString(KEY_SEARCH_PROVIDER, provider.id).apply()
    }

    private val _braveKey = MutableStateFlow(prefs.getString(KEY_BRAVE_KEY, "") ?: "")

    /** The Brave Search API key. Kept on this phone only: it is left out of backups and sync. */
    val braveKey = _braveKey.asStateFlow()

    fun setBraveKey(key: String) {
        _braveKey.value = key.trim()
        prefs.edit().putString(KEY_BRAVE_KEY, key.trim()).apply()
    }

    private fun searchConfig() = SearchConfig(_searchProvider.value, _searchServer.value, _braveKey.value)

    fun setToolEnabled(tool: ToolId, on: Boolean) {
        _enabledTools.update { if (on) it + tool else it - tool }
        prefs.edit().putString(KEY_TOOLS, Tools.serialise(_enabledTools.value)).apply()
    }

    // ---- Background replies ---------------------------------------------------------

    private val _backgroundReplies = MutableStateFlow(prefs.getBoolean(KEY_BG_REPLIES, true))

    /** Keep replies going when the app is not on screen, and notify when one is ready. */
    val backgroundReplies = _backgroundReplies.asStateFlow()

    fun setBackgroundReplies(on: Boolean) {
        _backgroundReplies.value = on
        prefs.edit().putBoolean(KEY_BG_REPLIES, on).apply()
    }

    private var appVisible = true

    /** Called by the activity as it comes to the front and leaves it. */
    fun setAppVisible(visible: Boolean) {
        appVisible = visible
        if (!visible) cancelDictation()
    }

    // ---- Read aloud --------------------------------------------------------------

    private val _speakingId = MutableStateFlow<String?>(null)

    /** Id of the message being read aloud, or null. */
    val speakingId = _speakingId.asStateFlow()

    private val _readAloud = MutableStateFlow(prefs.getBoolean(KEY_READ_ALOUD, false))

    /** Whether new replies are read aloud automatically when they finish. */
    val readAloud = _readAloud.asStateFlow()

    fun setReadAloud(on: Boolean) {
        _readAloud.value = on
        prefs.edit().putBoolean(KEY_READ_ALOUD, on).apply()
        if (!on) stopSpeaking()
    }

    // Created on first use and shut down in onCleared, so an unused voice engine is never started.
    private var speaker: Speaker? = null

    fun speak(message: ChatMessage) = speakText(message.id, Thinking.answerOnly(message.text))

    private fun speakText(id: String, text: String) {
        val voice = speaker ?: Speaker(
            getApplication(),
            onSpeaking = {
                _speakingId.value = it
                if (it == null) voiceEvent(VoiceLoop.Event.SpeechFinished)
            },
            onProblem = { _error.value = it },
        ).also { speaker = it }
        voice.speak(id, text)
    }

    fun stopSpeaking() {
        speaker?.stop()
    }

    // ---- Hands-free voice conversation ---------------------------------------------------

    private val _voice = MutableStateFlow(VoiceLoop.State())

    /** Where the hands-free conversation is: off, listening, waiting for the reply, or speaking it. */
    val voiceState = _voice.asStateFlow()

    private val _voicePartial = MutableStateFlow("")

    /** What the microphone has heard so far of the sentence being spoken. */
    val voicePartial = _voicePartial.asStateFlow()

    private var voiceListener: VoiceListener? = null

    /** Starts listening. The microphone permission must already be granted. */
    fun startVoiceMode() {
        if (_voice.value.phase != VoiceLoop.Phase.Off) return
        if (_loadedModel.value == null) { _error.value = "Load a model first."; return }
        if (!VoiceListener.available(getApplication())) { _error.value = "This phone has no speech recognition."; return }
        stopSpeaking()
        voiceEvent(VoiceLoop.Event.Start)
    }

    fun stopVoiceMode() = voiceEvent(VoiceLoop.Event.Stop)

    /** Runs one step of the conversation. Safe to call from any thread. */
    private fun voiceEvent(event: VoiceLoop.Event) {
        val action = synchronized(_voice) {
            val (state, action) = VoiceLoop.next(_voice.value, event)
            _voice.value = state
            state.note?.let { _error.value = it }
            action
        }
        viewModelScope.launch { runVoiceAction(action) }
    }

    private suspend fun runVoiceAction(action: VoiceLoop.Action) {
        when (action) {
            VoiceLoop.Action.None -> {}
            VoiceLoop.Action.Listen -> {
                _voicePartial.value = ""
                // A short pause, so the end of the spoken reply is not heard as the next question.
                delay(VOICE_PAUSE_MS)
                if (_voice.value.phase != VoiceLoop.Phase.Listening) return
                val listener = voiceListener ?: VoiceListener(
                    getApplication(),
                    onPartial = { _voicePartial.value = it },
                    onResult = { voiceEvent(it) },
                ).also { voiceListener = it }
                listener.listen()
            }
            is VoiceLoop.Action.Send -> {
                _voicePartial.value = ""
                if (!send(action.text)) voiceEvent(VoiceLoop.Event.ReplyReady(null))
            }
            is VoiceLoop.Action.Speak -> {
                val say = SpeechText.chunks(SpeechText.clean(action.text))
                if (say.isEmpty()) voiceEvent(VoiceLoop.Event.SpeechFinished)
                else _messages.value.lastOrNull()?.let { speakText(it.id, action.text) }
            }
            VoiceLoop.Action.StopAll -> {
                _voicePartial.value = ""
                voiceListener?.stop()
                stopSpeaking()
            }
        }
    }

    /** Called when a reply has finished (or failed), so a hands-free conversation can carry on. */
    private fun voiceReplyFinished() {
        if (_voice.value.phase != VoiceLoop.Phase.Thinking) return
        val reply = _messages.value.lastOrNull()?.takeIf { !it.isUser }?.let { Thinking.answerOnly(it.text) }
        voiceEvent(VoiceLoop.Event.ReplyReady(reply))
    }

    // ---- Quick access (tile, widget, shortcut) -------------------------------------

    private val _askRequests = MutableStateFlow(0)

    /** Counts requests to open the chat with the message box focused. */
    val askRequests = _askRequests.asStateFlow()

    fun requestAsk() {
        _askRequests.update { it + 1 }
    }

    // ---- Prompt library ---------------------------------------------------------

    private val _customPrompts = MutableStateFlow(PromptLibrary.fromJson(prefs.getString(KEY_PROMPTS, null)))

    /** Built-in prompts followed by the user's own. */
    val prompts: StateFlow<List<SavedPrompt>> =
        _customPrompts.map { PromptLibrary.defaults + it }
            .stateIn(viewModelScope, SharingStarted.Eagerly, PromptLibrary.defaults)

    fun savePrompt(title: String, text: String) {
        _customPrompts.update { it + SavedPrompt(UUID.randomUUID().toString(), title.trim(), text) }
        prefs.edit().putString(KEY_PROMPTS, PromptLibrary.toJson(_customPrompts.value)).apply()
        scheduleSettingsSync()
    }

    fun deletePrompt(prompt: SavedPrompt) {
        if (prompt.builtIn) return
        _customPrompts.update { list -> list.filterNot { it.id == prompt.id } }
        prefs.edit().putString(KEY_PROMPTS, PromptLibrary.toJson(_customPrompts.value)).apply()
        scheduleSettingsSync()
    }

    private val _stopWords = MutableStateFlow(prefs.getString(KEY_STOP, "") ?: "")

    /** The raw stop word text, one per line. Stops a reply as soon as one appears; applies at once. */
    val stopWords = _stopWords.asStateFlow()

    fun setStopWords(text: String) {
        _stopWords.value = text
        prefs.edit().putString(KEY_STOP, text).apply()
    }

    /** Thrown inside the reply collector to end generation cleanly when a stop word appears. */
    private class StopWordHit : CancellationException("stop word")

    private val _customPresets = MutableStateFlow(Presets.fromJson(prefs.getString(KEY_PRESETS, null)))
    private val _selectedPresetId = MutableStateFlow(prefs.getString(KEY_PRESET_ID, null) ?: Presets.DEFAULT_ID)
    val selectedPresetId = _selectedPresetId.asStateFlow()

    /** Built-in presets followed by the user's own. */
    val presets: StateFlow<List<Preset>> =
        _customPresets.map { Presets.builtIn + it }
            .stateIn(viewModelScope, SharingStarted.Eagerly, Presets.builtIn)

    private val _chatPresetId = MutableStateFlow<String?>(null)

    /** Preset chosen for just this chat, or null to follow the app-wide default. */
    val chatPresetId = _chatPresetId.asStateFlow()

    private val _thinking = MutableStateFlow(true)

    /** Whether a model that thinks first may do so in this chat. */
    val thinking = _thinking.asStateFlow()

    private val _thinkingSupported = MutableStateFlow(false)

    /** True if the loaded model writes a thinking section, so the switch is worth showing. */
    val thinkingSupported = _thinkingSupported.asStateFlow()

    fun setChatThinking(on: Boolean) {
        _thinking.value = on
        viewModelScope.launch { runCatching { engine.setThinking(on) } }
        if (_messages.value.isNotEmpty()) saveCurrentChat()
    }

    private val _jsonMode = MutableStateFlow(false)

    /** True while replies are restricted to valid JSON. */
    val jsonMode = _jsonMode.asStateFlow()

    private val selectedPrompt: String
        get() {
            // This chat's own choice wins, then the loaded model's, then the app-wide default.
            val id = _chatPresetId.value ?: presetOf(_loadedModel.value) ?: _selectedPresetId.value
            return presets.value.firstOrNull { it.id == id }?.prompt ?: Presets.builtIn.first().prompt
        }

    /** The preset's prompt, then what to remember about the user, then (when tools are on) how to ask for them. */
    private val chatSystemPrompt: String
        get() = Tools.withInstructions(
            Projects.withInstructions(MemoryNotes.withNotes(selectedPrompt, _notes.value), currentProject()),
            _enabledTools.value,
        )

    fun setChatPreset(id: String?) {
        _chatPresetId.value = id
        reloadKeepingChat()
    }

    /**
     * Starts the empty chat as [character]: its prompt applies to this chat, and its greeting is already said, so the
     * model carries on from it.
     */
    fun startChatAs(character: Preset) {
        if (anythingRunning() || _messages.value.isNotEmpty() || _loadedModel.value == null) return
        _chatPresetId.value = character.id
        if (character.greeting.isNotBlank()) _messages.value = listOf(ChatMessage(character.greeting.trim(), false))
        refreshSystemPrompt()
    }

    fun switchModel(model: LocalModel) {
        if (model.path == _loadedModel.value?.path) return
        reloadKeepingChat(model)
    }

    fun setJsonMode(on: Boolean) {
        _jsonMode.value = on
        if (!on) _jsonSchema.value = null
        applyConstraintNow()
    }

    private val _jsonSchema = MutableStateFlow<String?>(null)

    /** A JSON Schema this chat's replies must follow, or null. */
    val jsonSchema = _jsonSchema.asStateFlow()

    /** Makes replies in this chat follow [schema] (a JSON Schema), or lifts that when null. */
    fun setJsonSchema(schema: String?) {
        val text = schema?.trim()?.takeIf { it.isNotEmpty() }
        if (text != null) {
            JsonSchema.problem(text)?.let { _error.value = it; return }
        }
        _jsonSchema.value = text
        if (text != null) _jsonMode.value = true
        applyConstraintNow()
    }

    private fun applyConstraintNow() {
        if (_loadedModel.value == null || _sleeping.value || _generating.value || _loading.value) return
        viewModelScope.launch {
            try {
                applyOutputConstraint()
            } catch (e: Exception) {
                _error.value = "Could not change JSON mode: ${e.message}"
            }
        }
    }

    /** Applies this chat's JSON setting to the loaded model: its schema, plain JSON, or no restriction. */
    private suspend fun applyOutputConstraint() {
        val schema = _jsonSchema.value
        if (schema != null && engine.setOutputSchema(schema)) return
        if (schema != null) {
            _jsonSchema.value = null
            _error.value = "The model could not use that schema, so replies only have to be valid JSON."
        }
        engine.setOutputGrammar(if (_jsonMode.value) JsonGrammar.gbnf else "")
    }

    /** Reloads the engine (for a new model or prompt) and replays the chat on the next message. */
    private fun reloadKeepingChat(model: LocalModel? = _loadedModel.value) {
        if (model == null || _generating.value || _loading.value || _benchmarking.value || _apiBusy.value) return
        pendingContext = Replay.build(turns())
        load(model, resetChat = false)
    }

    /** Takes effect the next time a model loads or a new chat starts. */
    fun selectPreset(id: String) {
        _selectedPresetId.value = id
        prefs.edit().putString(KEY_PRESET_ID, id).apply()
    }

    /** Adds a preset, or replaces the custom preset with the same id. */
    fun savePreset(preset: Preset) {
        _customPresets.update { list -> list.filterNot { it.id == preset.id } + preset }
        prefs.edit().putString(KEY_PRESETS, Presets.toJson(_customPresets.value)).apply()
        scheduleSettingsSync()
    }

    fun deletePreset(preset: Preset) {
        if (preset.builtIn) return
        _customPresets.update { list -> list.filterNot { it.id == preset.id } }
        prefs.edit().putString(KEY_PRESETS, Presets.toJson(_customPresets.value)).apply()
        if (_selectedPresetId.value == preset.id) selectPreset(Presets.DEFAULT_ID)
        scheduleSettingsSync()
    }

    private var generationJob: Job? = null

    fun reportError(message: String) {
        _error.value = message
    }

    fun dismissError() {
        _error.value = null
    }

    // ---- Vision -----------------------------------------------------------------

    private val imageStore = ImageStore(app)

    private val _projectors = MutableStateFlow<List<LocalModel>>(emptyList())
    val projectors = _projectors.asStateFlow()

    private val _projectorChoice = MutableStateFlow(
        prefs.all.filterKeys { it.startsWith(KEY_PROJECTOR) }
            .mapKeys { it.key.removePrefix(KEY_PROJECTOR) }
            .mapNotNull { (k, v) -> (v as? String)?.let { k to it } }
            .toMap()
    )

    /** Which projector file each model uses, by file name. Applies the next time the model loads. */
    val projectorChoice = _projectorChoice.asStateFlow()

    fun setProjector(model: LocalModel, projectorFileName: String?) {
        _projectorChoice.update { if (projectorFileName == null) it - model.fileName else it + (model.fileName to projectorFileName) }
        prefs.edit().apply {
            if (projectorFileName == null) remove(KEY_PROJECTOR + model.fileName)
            else putString(KEY_PROJECTOR + model.fileName, projectorFileName)
        }.apply()
    }

    private val _visionReady = MutableStateFlow(false)

    /** True when the loaded model has a working projector, so images can be sent. */
    val visionReady = _visionReady.asStateFlow()

    private val _pendingImages = MutableStateFlow<List<String>>(emptyList())

    /** Photos waiting to be sent with the next message (up to [MAX_IMAGES]). */
    val pendingImages = _pendingImages.asStateFlow()

    private suspend fun attachImageNow(uri: Uri) {
        if (!_visionReady.value) {
            _error.value = "This model can't read images. Load a vision model and pick its projector in Models."
            return
        }
        if (_pendingImages.value.size >= MAX_IMAGES) {
            _error.value = "A message can carry up to $MAX_IMAGES photos."
            return
        }
        try {
            val path = imageStore.import(uri).also { if (_incognito.value) incognitoLedger.add(IncognitoLedger.image(it)) }
            _pendingImages.update { it + path }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _error.value = e.message ?: "Could not attach the image"
        }
    }

    /** Takes one waiting photo off the message, or all of them when [path] is null. */
    fun removePendingImage(path: String? = null) {
        val removed = if (path == null) _pendingImages.value else _pendingImages.value.filter { it == path }
        removed.forEach { photo -> viewModelScope.launch { imageStore.delete(photo) } }
        _pendingImages.update { list -> if (path == null) emptyList() else list - path }
    }

    private val _modelInfo = MutableStateFlow<Map<String, ModelInfo>>(emptyMap())

    /** GGUF header details keyed by model path; filled in after the file list loads. */
    val modelInfo = _modelInfo.asStateFlow()

    fun refreshModels() {
        viewModelScope.launch {
            val all = store.list()
            val allProjectors = store.listProjectors()
            // Speech models (for dictation) live in the same folder but are not chat models.
            val list = all.filterNot { SpeechModels.isSpeechFile(it.fileName) }
            _models.value = list
            _projectors.value = allProjectors.filterNot { SpeechModels.isSpeechFile(it.fileName) }
            _speechModels.value = all.filter { SpeechModels.isSpeechFile(it.fileName) }
            _speechProjectors.value = allProjectors.filter { SpeechModels.isSpeechFile(it.fileName) }
            // A catalog vision model comes with its own projector: pair them automatically.
            catalog.value.forEach { c ->
                val projector = c.projectorFileName ?: return@forEach
                val model = list.firstOrNull { it.fileName == c.fileName } ?: return@forEach
                if (_projectors.value.any { it.fileName == projector } && model.fileName !in _projectorChoice.value) {
                    setProjector(model, projector)
                }
            }
            list.filter { it.path !in _modelInfo.value }.forEach { m ->
                ModelInfo.read(m.path)?.let { info -> _modelInfo.update { it + (m.path to info) } }
            }
        }
    }

    // ---- Model management -------------------------------------------------

    /** Downloads whichever of the model and its vision add-on are not on the phone yet. */
    fun download(model: CatalogModel) {
        if (_models.value.none { it.fileName == model.fileName }) startDownload(model.url, model.fileName)
        if (model.projectorUrl != null && model.projectorFileName != null &&
            _projectors.value.none { it.fileName == model.projectorFileName }
        ) {
            startDownload(model.projectorUrl, model.projectorFileName)
        }
    }

    fun downloadFromUrl(url: String) {
        val name = url.substringBefore('?').substringAfterLast('/')
        if (!name.endsWith(".gguf")) {
            _error.value = "The link must point to a .gguf file"
            return
        }
        startDownload(url, name)
    }

    private fun startDownload(url: String, fileName: String) = center.start(url, fileName)

    // ---- Hugging Face search ----------------------------------------------------

    private val _search = MutableStateFlow(SearchState())
    val search = _search.asStateFlow()

    private val _repoFiles = MutableStateFlow<Map<String, RepoFiles>>(emptyMap())
    val repoFiles = _repoFiles.asStateFlow()

    private var searchJob: Job? = null

    private val _hubSource = MutableStateFlow(
        HubSource.entries.firstOrNull { it.name == prefs.getString(KEY_HUB, null) } ?: HubSource.HuggingFace
    )

    /** Which repository the search screen looks in. */
    val hubSource = _hubSource.asStateFlow()

    private val _mirrorUrl = MutableStateFlow(prefs.getString(KEY_MIRROR, "") ?: "")

    /** Address of a Hugging Face compatible server, used when the source is [HubSource.Mirror]. */
    val mirrorUrl = _mirrorUrl.asStateFlow()

    /** Bumped whenever the source changes, so answers from the old source can be ignored. */
    private var hubEpoch = 0

    fun setHubSource(source: HubSource) {
        if (source == _hubSource.value) return
        _hubSource.value = source
        prefs.edit().putString(KEY_HUB, source.name).apply()
        resetSearch()
    }

    fun setMirrorUrl(url: String) {
        _mirrorUrl.value = url
        prefs.edit().putString(KEY_MIRROR, url).apply()
        if (_hubSource.value == HubSource.Mirror) resetSearch()
    }

    private fun resetSearch() {
        hubEpoch++
        searchJob?.cancel()
        _search.value = SearchState()
        _repoFiles.value = emptyMap()
    }

    private fun currentHub(): ModelHub = ModelHub.create(_hubSource.value, _mirrorUrl.value)

    fun searchHub(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        searchJob?.cancel()
        val hub = try {
            currentHub()
        } catch (e: Exception) {
            _error.value = e.message ?: "Could not open that repository"
            return
        }
        val epoch = hubEpoch
        _search.value = SearchState(query = q, loading = true)
        searchJob = viewModelScope.launch {
            try {
                val page = hub.searchPage(q)
                if (epoch == hubEpoch) {
                    _search.value = SearchState(query = q, searched = true, results = page.results, next = page.next)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _search.value = SearchState(query = q, searched = true)
                _error.value = "Search failed: ${e.message}"
            }
        }
    }

    /** Fetches the next page of the current search and adds it below the results already shown. */
    fun loadMoreResults() {
        val current = _search.value
        val cursor = current.next ?: return
        if (current.loadingMore || current.loading) return
        val hub = try {
            currentHub()
        } catch (e: Exception) {
            _error.value = e.message ?: "Could not open that repository"
            return
        }
        val epoch = hubEpoch
        _search.value = current.copy(loadingMore = true)
        searchJob = viewModelScope.launch {
            try {
                val page = hub.searchPage(current.query, cursor)
                if (epoch == hubEpoch) {
                    val seen = current.results.map { it.id }.toSet()
                    _search.value = current.copy(
                        results = current.results + page.results.filter { it.id !in seen },
                        next = page.next,
                        loadingMore = false,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _search.value = current.copy(loadingMore = false)
                _error.value = "Could not load more results: ${e.message}"
            }
        }
    }

    fun loadRepoFiles(repoId: String) {
        if (_repoFiles.value[repoId]?.files != null) return
        val hub = try {
            currentHub()
        } catch (e: Exception) {
            _error.value = e.message ?: "Could not open that repository"
            return
        }
        val epoch = hubEpoch
        _repoFiles.update { it + (repoId to RepoFiles()) }
        viewModelScope.launch {
            try {
                val files = hub.files(repoId)
                if (epoch == hubEpoch) _repoFiles.update { it + (repoId to RepoFiles(files = files)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (epoch == hubEpoch) {
                    _repoFiles.update { it + (repoId to RepoFiles(error = e.message ?: "Could not load files")) }
                }
            }
        }
    }

    fun downloadFromHub(repoId: String, file: HfFile) =
        startDownload(currentHub().downloadUrl(repoId, file.path), file.fileName)

    fun cancelDownload(fileName: String) {
        center.cancel(fileName)
    }

    fun import(uri: Uri) {
        viewModelScope.launch {
            try {
                store.import(uri)
                refreshModels()
            } catch (e: Exception) {
                _error.value = "Import failed: ${e.message}"
            }
        }
    }

    fun delete(model: LocalModel) {
        viewModelScope.launch {
            if (_loadedModel.value?.path == model.path) unload()
            if (_embeddingFile.value == model.fileName) {
                runCatching { embedder.unload() }
                _embeddingFile.value = ""
                prefs.edit().putString(KEY_EMBED_MODEL, "").apply()
                vectorJob?.cancel()
                docVectors = null
            }
            store.delete(model.fileName)
            refreshModels()
        }
    }

    // ---- Inference -----------------------------------------------------------

    /**
     * Loads [model] into the engine. With [resetChat] the conversation is cleared too; opening a
     * saved chat passes false so its messages stay on screen while the native context is rebuilt.
     */
    fun load(model: LocalModel, resetChat: Boolean = true, restore: Boolean = false, incognito: Boolean = false) {
        if (_loading.value || _benchmarking.value || _apiBusy.value) return
        if (embeddingModels.value.any { it.path == model.path }) {
            _error.value = "${model.fileName.removeSuffix(".gguf")} is an embedding model, for searching documents by meaning. " +
                "It cannot chat. Choose it in Settings, Document search."
            return
        }
        viewModelScope.launch {
            _loading.value = true
            try {
                if (resetChat) {
                    clearConversation()
                    _incognito.value = incognito
                }
                val restored = loadIntoEngine(model, restoreFor = turns().takeIf { restore })
                if (restored) pendingContext = null
            } catch (e: Exception) {
                _loadedModel.value = null
                _error.value = "Could not load ${model.fileName}: ${e.message ?: e::class.simpleName}"
            } finally {
                _loading.value = false
            }
        }
    }

    /**
     * Loads [model] into the engine. When [restoreFor] (the chat as it stood) is given, the chat's saved state is
     * brought back instead of reading the system prompt again; returns true if that worked.
     */
    private suspend fun loadIntoEngine(model: LocalModel, applyJson: Boolean = true, restoreFor: List<Turn>? = null): Boolean {
        stopGeneration()
        if (engine.state.value.isModelLoaded) engine.cleanUp()
        awaitIdleEngine()
        val params = paramsFor(model)
        if (params.gpuBackend.isNotEmpty()) gpuGuard.attemptStarting()
        try {
            engine.loadModel(model.path, params)
        } finally {
            // Reached only if the app is still alive, so whatever happened, it did not take the app down.
            if (params.gpuBackend.isNotEmpty()) gpuGuard.attemptFinished()
        }
        _gpuStatus.value = engine.gpuStatus()
        if (params.gpuBackend.isNotEmpty() && !_gpuStatus.value.onGpu) {
            _gpuStatus.value.text.takeIf { it.isNotEmpty() }?.let { _error.value = it }
        }

        var restored = false
        val chatId = currentChatId
        if (restoreFor != null && restoreFor.isNotEmpty() && chatId != null && _saveStates.value) {
            restored = try {
                engine.restoreState(stateCache.prefix(chatId), stateTag(model, restoreFor))
            } catch (e: Exception) {
                false
            }
        }
        if (!restored) engine.setSystemPrompt(chatSystemPrompt)
        contextStale = false
        _loadedModel.value = model
        _sleeping.value = false
        touch()

        _visionReady.value = false
        val projector = _projectorChoice.value[model.fileName]?.let { name ->
            _projectors.value.firstOrNull { it.fileName == name }
        }
        if (projector != null) {
            _visionReady.value = engine.loadVisionProjector(projector.path)
            if (!_visionReady.value) {
                _error.value = "${projector.fileName} doesn't work with ${model.fileName}. Images are off."
            }
        }
        if (applyJson) applyOutputConstraint()
        engine.draftStatus().takeIf { it.isNotEmpty() && !it.startsWith("Draft model on") }?.let { _error.value = it }
        engine.adapterStatus().takeIf { it.isNotEmpty() }?.let { _error.value = it }
        if (!engine.cacheAsRequested()) {
            _error.value = "This phone could not use the compressed memory cache, so the normal one is in use."
        }
        engine.setThinking(_thinking.value)
        _thinkingSupported.value = engine.supportsThinking()
        refreshContextUsage()
        return restored
    }

    /** Starts the model's memory over with the current system prompt, without loading the model again. */
    private suspend fun resetOrReload(model: LocalModel) {
        if (_sleeping.value || !engine.state.value.isModelLoaded) {
            loadIntoEngine(model)
        } else {
            stopGeneration()
            awaitModelReady()
            engine.resetConversation(chatSystemPrompt)
            refreshContextUsage()
        }
        contextStale = false
    }

    /** True when the model's memory no longer matches the reply on screen (another version of it was chosen). */
    private var contextStale = false

    fun unload() {
        _sleeping.value = false
        _contextUsage.value = ContextUsage(0, 0)
        stopGeneration()
        if (engine.state.value.isModelLoaded) engine.cleanUp()
        _loadedModel.value = null
        _visionReady.value = false
        _gpuStatus.value = GpuStatus(false, "")
        clearConversation()
    }

    /** Starts a fresh chat. The native context can only be reset by reloading the model. */
    fun newChat() {
        _loadedModel.value?.let { load(it) }
    }

    /** Sends [text] as the next message. Returns false if it could not be sent right now. */
    fun send(text: String): Boolean {
        val images = _pendingImages.value
        val prompt = text.trim().ifEmpty { if (images.isNotEmpty()) (if (images.size > 1) "Describe these images." else "Describe this image.") else "" }
        if (prompt.isEmpty() || _generating.value || _benchmarking.value || _apiBusy.value || _loadedModel.value == null) return false

        stopSpeaking()
        if (currentChatId == null) currentChatId = UUID.randomUUID().toString()
        _pendingImages.value = emptyList()
        _messages.update { it + ChatMessage(prompt, true, imagePath = images.firstOrNull(), moreImages = images.drop(1)) }
        touch()
        if (_sleeping.value || contextStale) {
            viewModelScope.launch {
                if (readyForMessage()) startGeneration(prompt, images) else voiceReplyFinished()
            }
        } else {
            startGeneration(prompt, images)
        }
        return true
    }

    /** Wakes the model, or rebuilds its memory, when it no longer matches the chat on screen. */
    private suspend fun readyForMessage(): Boolean {
        val model = _loadedModel.value ?: return false
        if (_sleeping.value && !wakeIfAsleep(excludeLast = true)) return false
        if (contextStale) {
            _loading.value = true
            try {
                resetOrReload(model)
                pendingContext = Replay.build(turns().dropLast(1))
            } catch (e: Exception) {
                _error.value = "Could not get the model ready: ${e.message ?: e::class.simpleName}"
                return false
            } finally {
                _loading.value = false
            }
        }
        return true
    }

    /** Re-asks the last user message. The engine can't undo a reply, so its context is rebuilt first. */
    fun regenerate() {
        val msgs = _messages.value
        if (msgs.size < 2 || msgs.last().isUser || msgs.last().summary || !msgs[msgs.lastIndex - 1].isUser) return
        val last = msgs.last()
        // The reply being replaced stays available as an earlier version.
        val versions = if (last.text.isNotEmpty()) Variants.startNew(Reply(last.text, last.stats), OtherReplies(last.others, last.position)) else null
        rerunFrom(msgs.lastIndex - 1, msgs[msgs.lastIndex - 1].text, versions)
    }

    /** Shows another version of the latest reply. The next message continues from the version shown. */
    fun selectReply(id: String, target: Int) {
        if (anythingRunning()) return
        val message = _messages.value.lastOrNull() ?: return
        if (message.id != id || message.isUser || message.summary) return
        val (reply, versions) = Variants.select(Reply(message.text, message.stats), OtherReplies(message.others, message.position), target)
        if (versions.position == message.position) return
        stopSpeaking()
        _messages.update { it.dropLast(1) + message.copy(text = reply.text, stats = reply.stats, others = versions.others, position = versions.position) }
        _cutOffId.value = null
        contextStale = true
        saveCurrentChat()
    }

    /** Replaces one of your messages with [newText] and answers again, dropping everything after it. */
    fun editMessage(id: String, newText: String) {
        val text = newText.trim()
        val index = _messages.value.indexOfFirst { it.id == id && it.isUser }
        if (index < 0 || text.isEmpty()) return
        rerunFrom(index, text)
    }

    /** Rebuilds the engine context from the messages before [index], then answers [prompt] again. */
    private fun rerunFrom(index: Int, prompt: String, versions: OtherReplies? = null) {
        if (_generating.value || _loading.value) return
        val model = _loadedModel.value ?: return
        val msgs = _messages.value
        val images = msgs[index].images
        val prior = msgs.take(index)
        val discardedImages = msgs.drop(index + 1).flatMap { it.images }

        viewModelScope.launch {
            _loading.value = true
            try {
                resetOrReload(model)
            } catch (e: Exception) {
                _loadedModel.value = null
                _error.value = "Could not reload ${model.fileName}: ${e.message ?: e::class.simpleName}"
                return@launch
            } finally {
                _loading.value = false
            }
            discardedImages.forEach { imageStore.delete(it) }
            _messages.value = prior + ChatMessage(prompt, true, imagePath = images.firstOrNull(), moreImages = images.drop(1))
            pendingContext = Replay.build(turns(prior))
            pendingVariants = versions
            startGeneration(prompt, images)
        }
    }

    /** The earlier versions of the reply that is about to be regenerated. */
    private var pendingVariants: OtherReplies? = null

    /** Streams a reply to [prompt], which must already be the last message in the list. */
    private fun startGeneration(prompt: String, imagePaths: List<String> = emptyList(), continuing: Boolean = false) {
        _cutOffId.value = null
        val carried = pendingVariants
        pendingVariants = null
        // After reopening a saved chat the engine has no memory of it, so replay it once.
        val replay = pendingContext
        pendingContext = null
        val systemText = chatSystemPrompt
        val steps = ArrayList<PromptStep>()

        if (!continuing) {
            _messages.update { it + ChatMessage("", false, others = carried?.others.orEmpty(), position = carried?.position ?: 0) }
        }
        _generating.value = true
        if (_backgroundReplies.value) {
            ReplyGateway.stopRequested = ::stopGeneration
            ReplyService.start(getApplication())
        }
        val tools = _enabledTools.value
        // With tools on, the end of a tool request also ends the round so the app can run it.
        val stops = StopWords.parse(_stopWords.value) + if (tools.isEmpty()) emptyList() else listOf(Tools.CLOSE_TAG)
        generationJob = viewModelScope.launch {
            val start = SystemClock.elapsedRealtime()
            var firstAt = 0L
            var count = 0

            val question = if (continuing) prompt else runCatching { withDocumentContext(prompt) }.getOrDefault(prompt)
            val enginePrompt = replay?.let {
                "Here is our earlier conversation:\n$it\n\nContinue from there. My new message: $question"
            } ?: question
            val firstTitle = if (continuing) "Request to continue" else "Your message" +
                (if (question != prompt) ", with excerpts from your documents" else "") +
                (if (replay != null) ", with the earlier conversation replayed" else "")

            /** Streams one model turn into the reply bubble after [base]; returns what the model wrote. */
            suspend fun streamRound(prompt: String, image: List<String>, base: String, title: String): String {
                var written = ""
                var recorded = false
                val reply = if (image.isNotEmpty() && _visionReady.value) {
                    engine.sendUserPromptWithImages(prompt, image, ThrottlePolicy.replyCap(_maxReplyTokens.value, _throttle.value))
                } else {
                    engine.sendUserPrompt(prompt, ThrottlePolicy.replyCap(_maxReplyTokens.value, _throttle.value))
                }
                try {
                    reply.collect { token ->
                        if (!recorded) {
                            // The model has read the message by now, so what it was given can be looked up.
                            recorded = true
                            steps += PromptStep(title, PromptRecord.clip(prompt), PromptRecord.clip(engine.lastPromptText()))
                            _promptRecord.value = PromptRecord(systemText, steps.toList(), promptSettings())
                        }
                        if (count == 0) firstAt = SystemClock.elapsedRealtime()
                        count++
                        if (count % 16 == 0) refreshContextUsage()
                        written += token
                        val cut = StopWords.cutIndex(written, stops)
                        if (cut >= 0) written = written.substring(0, cut).trimEnd()
                        // Hide a tool request while it is being typed; it is replaced by a short note afterwards.
                        val shown = base + written.substringBefore(Tools.OPEN_TAG)
                        _messages.update { list -> list.dropLast(1) + list.last().copy(text = shown) }
                        if (cut >= 0) throw StopWordHit()
                    }
                } catch (e: StopWordHit) {
                    // A stop word ended this turn: that is a normal finish.
                }
                return written
            }

            try {
                var prompt = enginePrompt
                var image: List<String> = imagePaths
                var base = if (continuing) _messages.value.last().text else ""
                var toolRounds = 0
                while (true) {
                    val written = streamRound(prompt, image, base, if (toolRounds == 0) firstTitle else "Result of a tool the model used")
                    val call = if (tools.isEmpty()) null else Tools.parseCall(written)
                    if (call == null || toolRounds >= Tools.MAX_ROUNDS) break

                    val outcome = Tools.run(call, tools, search = searchConfig())
                    val before = written.substringBefore(Tools.OPEN_TAG).trim()
                    base = listOf(base.trimEnd(), before, Tools.statusLine(call, outcome))
                        .filter { it.isNotEmpty() }.joinToString("\n\n") + "\n\n"
                    _messages.update { list -> list.dropLast(1) + list.last().copy(text = base) }
                    prompt = Tools.followUp(call, outcome)
                    image = emptyList()
                    toolRounds++
                }
                // Reached only when the reply finished by itself: did it stop because it ran out of length?
                _cutOffId.value = if (engine.lastReplyWasCut()) _messages.value.lastOrNull()?.takeIf { !it.isUser }?.id else null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = "Generation failed: ${e.message ?: e::class.simpleName}"
            } finally {
                _generating.value = false
                touch()
                refreshContextUsage()
                ReplyService.stop(getApplication())
                if (count == 0) {
                    // Nothing was written (an error, or stopped before the first word): drop the empty bubble,
                    // and bring back the version of the reply it was going to replace.
                    _messages.update { list ->
                        val empty = list.lastOrNull()?.takeIf { !it.isUser && it.text.isEmpty() } ?: return@update list
                        val earlier = Variants.restoreLast(OtherReplies(empty.others, empty.position))
                        if (earlier == null) list.dropLast(1)
                        else list.dropLast(1) + empty.copy(
                            text = earlier.first.text, stats = earlier.first.stats,
                            others = earlier.second.others, position = earlier.second.position,
                        )
                    }
                }
                if (count > 0) {
                    val genMs = (SystemClock.elapsedRealtime() - firstAt).coerceAtLeast(1)
                    val stats = GenStats(
                        tokens = count,
                        tokensPerSecond = if (count > 1) (count - 1) * 1000f / genMs else null,
                        firstTokenMs = firstAt - start,
                    )
                    _messages.update { list -> list.dropLast(1) + list.last().copy(stats = stats) }
                    if (_readAloud.value && _voice.value.phase == VoiceLoop.Phase.Off) _messages.value.lastOrNull()?.let(::speak)
                }
                saveCurrentChat()
                saveChatState()
                voiceReplyFinished()
                if (count > 0 && !appVisible && _backgroundReplies.value) {
                    val chatTitle = _chats.value.firstOrNull { it.id == currentChatId }?.title
                        ?: _messages.value.firstOrNull { it.isUser }?.text?.lineSequence()?.first()?.take(48).orEmpty()
                    if (_incognito.value) ReplyNotifier.replyReady(getApplication(), "Incognito chat", "Your reply is ready.")
                    else ReplyNotifier.replyReady(getApplication(), chatTitle, Thinking.answerOnly(_messages.value.lastOrNull()?.text.orEmpty()))
                }
            }
        }
    }

    // ---- Summarise and continue -----------------------------------------------------

    private val _summarising = MutableStateFlow(false)

    /** True while the chat is being condensed into a summary. */
    val summarising = _summarising.asStateFlow()

    /**
     * Has the model condense the chat so far into a short summary, then carries on from that summary with the
     * memory freed up. The earlier messages stay on screen; only what the model remembers becomes shorter.
     */
    /** The state of asking the model for notes worth remembering from the chat. */
    sealed interface NoteSuggestionState {
        data object Idle : NoteSuggestionState
        data object Working : NoteSuggestionState
        data class Ready(val suggestions: List<String>) : NoteSuggestionState
    }

    private val _noteSuggestions = MutableStateFlow<NoteSuggestionState>(NoteSuggestionState.Idle)
    val noteSuggestions = _noteSuggestions.asStateFlow()

    /**
     * Asks the model what lasting facts about the user this chat reveals, to offer as memory notes. Nothing is added
     * until the user picks. Like summarising, it uses the model's memory, so the chat is replayed on the next message.
     */
    fun suggestNotes() {
        val model = _loadedModel.value ?: return
        if (anythingRunning() || _noteSuggestions.value is NoteSuggestionState.Working) return
        val history = turns()
        if (history.none { it.isUser }) return

        stopSpeaking()
        _noteSuggestions.value = NoteSuggestionState.Working
        _generating.value = true
        if (_backgroundReplies.value) {
            ReplyGateway.stopRequested = ::stopGeneration
            ReplyService.start(getApplication())
        }
        generationJob = viewModelScope.launch {
            var written = ""
            var finished = false
            try {
                if (_sleeping.value && !wakeIfAsleep()) return@launch
                touch()
                awaitModelReady()
                if (_jsonMode.value || _jsonSchema.value != null) engine.setOutputGrammar("")
                engine.updateSampling(0.2f, paramsFor(model).topP)
                engine.resetConversation(NoteSuggestions.SYSTEM_PROMPT)
                val room = Summaries.transcriptChars(engine.contextUsage().total)
                val known = _notes.value.map { it.text }
                engine.sendUserPrompt(NoteSuggestions.requestFor(NoteSuggestions.transcript(history, room), known), NoteSuggestions.REPLY_TOKENS)
                    .collect { written += it }
                finished = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = "Could not look for notes: ${e.message ?: e::class.simpleName}"
            } finally {
                withContext(NonCancellable) {
                    _noteSuggestions.value = if (finished) {
                        NoteSuggestionState.Ready(NoteSuggestions.parse(written, _notes.value.map { it.text }))
                    } else {
                        NoteSuggestionState.Idle
                    }
                    try {
                        awaitModelReady()
                        val settings = paramsFor(model)
                        engine.updateSampling(settings.temperature, settings.topP)
                        engine.resetConversation(chatSystemPrompt)
                        applyOutputConstraint()
                        pendingContext = Replay.build(turns())
                        contextStale = false
                    } catch (e: Exception) {
                        _error.value = "Could not get the model ready again: ${e.message ?: e::class.simpleName}"
                    }
                    _generating.value = false
                    ReplyService.stop(getApplication())
                    touch()
                    refreshContextUsage()
                }
            }
        }
    }

    /** Adds the chosen suggestions as memory notes and closes the suggestion list. */
    fun addSuggestedNotes(texts: List<String>) {
        texts.forEach(::addNote)
        _noteSuggestions.value = NoteSuggestionState.Idle
    }

    fun dismissNoteSuggestions() {
        _noteSuggestions.value = NoteSuggestionState.Idle
    }

    fun summariseChat() {
        val model = _loadedModel.value ?: return
        if (anythingRunning()) return
        val history = turns()
        if (history.size < 2 || history.none { it.isUser }) return

        stopSpeaking()
        _cutOffId.value = null
        val placeholder = ChatMessage("", false, summary = true)
        // Treated like a reply, so Stop, the message box, the idle timer and the background notice all behave.
        _generating.value = true
        _summarising.value = true
        if (_backgroundReplies.value) {
            ReplyGateway.stopRequested = ::stopGeneration
            ReplyService.start(getApplication())
        }
        generationJob = viewModelScope.launch {
            var written = ""
            var finished = false
            try {
                if (_sleeping.value && !wakeIfAsleep()) return@launch
                _messages.update { it + placeholder }
                touch()
                val settings = paramsFor(model)
                awaitModelReady()
                if (_jsonMode.value || _jsonSchema.value != null) engine.setOutputGrammar("")
                engine.updateSampling(0.3f, settings.topP)

                val inPlace = !contextStale && pendingContext == null && Summaries.canSummariseInPlace(engine.contextUsage())
                val request = if (inPlace) {
                    Summaries.REQUEST
                } else {
                    // The model's memory is too full (or does not hold the chat): read the written chat instead.
                    engine.resetConversation(Summaries.SYSTEM_PROMPT)
                    val room = Summaries.transcriptChars(engine.contextUsage().total)
                    Summaries.requestFor(Replay.build(history, room).orEmpty())
                }
                engine.sendUserPrompt(request, Summaries.REPLY_TOKENS).collect { token ->
                    written += token
                    val shown = Summaries.clean(written)
                    _messages.update { list -> list.dropLast(1) + list.last().copy(text = shown) }
                }
                finished = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = "Summarising failed: ${e.message ?: e::class.simpleName}"
            } finally {
                withContext(NonCancellable) {
                    val summary = Summaries.clean(written)
                    val keep = finished && summary.isNotEmpty()
                    if (finished && summary.isEmpty()) _error.value = "The model did not write a summary. Try again."
                    _messages.update { list ->
                        val last = list.lastOrNull()
                        if (last?.id != placeholder.id) list
                        else if (keep) list.dropLast(1) + last.copy(text = summary)
                        else list.dropLast(1)
                    }
                    try {
                        awaitModelReady()
                        val settings = paramsFor(model)
                        engine.updateSampling(settings.temperature, settings.topP)
                        engine.resetConversation(chatSystemPrompt)
                        applyOutputConstraint()
                        // The next message starts from the summary (or from the whole chat, if summarising failed).
                        pendingContext = Replay.build(turns())
                        contextStale = false
                    } catch (e: Exception) {
                        _error.value = "Could not get the model ready again: ${e.message ?: e::class.simpleName}"
                    }
                    _generating.value = false
                    _summarising.value = false
                    ReplyService.stop(getApplication())
                    touch()
                    refreshContextUsage()
                    if (keep) saveCurrentChat()
                }
            }
        }
    }

    // ---- What the model was given ---------------------------------------------------

    private val _promptRecord = MutableStateFlow<PromptRecord?>(null)

    /** The system prompt and messages handed to the model for the latest reply. Kept in memory only. */
    val promptRecord = _promptRecord.asStateFlow()

    private fun promptSettings(): String {
        val model = _loadedModel.value
        val params = model?.let(::paramsFor) ?: _generationParams.value
        val usage = engine.contextUsage()
        return buildString {
            append("Model: ").append(model?.fileName ?: "none").append("\n")
            append("Memory used: ").append(ContextMeter.label(usage)).append("\n")
            append("Temperature ").append("%.2f".format(params.temperature))
            append(", top-p ").append("%.2f".format(params.topP))
            append(", top-k ").append(params.topK)
            append(", min-p ").append("%.2f".format(params.minP))
            append(", repeat penalty ").append("%.2f".format(params.repeatPenalty)).append("\n")
            append("Chat template: ").append(ChatTemplates.label(params.chatTemplate)).append("\n")
            append("Reply limit: ").append(_maxReplyTokens.value).append(" tokens")
            if (_jsonSchema.value != null) append("\nReplies follow a JSON schema")
            else if (_jsonMode.value) append("\nReplies must be valid JSON")
            engine.gpuStatus().text.takeIf { it.isNotEmpty() }?.let { append("\n").append(if (engine.gpuStatus().onGpu) "Running on the GPU: " else "GPU: ").append(it) }
            engine.draftStatus().takeIf { it.isNotEmpty() }?.let { append("\n").append(it) }
            params.adapters.takeIf { it.isNotEmpty() }?.let { list ->
                append("\nAdapters: ").append(list.joinToString(", ") { File(it.path).name.removeSuffix(".gguf") + " x" + it.scale })
            }
            engine.adapterStatus().takeIf { it.isNotEmpty() }?.let { append("\n").append(it) }
        }
    }

    // ---- Web pages as documents ------------------------------------------------------

    /** Fetches the page at [address] and attaches its text to the chat like a document. */
    fun attachWebPage(address: String) {
        if (_attachStatus.value != null) return
        viewModelScope.launch { attachWebPageNow(address) }
    }

    private suspend fun attachWebPageNow(address: String) {
        val url = WebPage.normalise(address)
        if (url == null) {
            _error.value = "That doesn't look like a web address."
            return
        }
        _attachStatus.value = "Reading the page…"
        try {
            val page = withContext(Dispatchers.IO) { WebPage.fetch(url) }
            val name = page.title.replace(Regex("\\s+"), " ").take(48).ifBlank { "Web page" }
            val doc = docStore.saveText(name, "Source: $url\n\n${page.text}").also { noteIncognitoDoc(it) }
            _attachments.update { it + doc }
            rebuildDocIndex()
            if (_messages.value.isNotEmpty()) saveCurrentChat()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _error.value = e.message ?: "Could not read that page"
        } finally {
            _attachStatus.value = null
        }
    }

    // ---- Diagnostics -------------------------------------------------------------------

    private val _diagnostics = MutableStateFlow<String?>(null)

    /** A report for bug reports: device, app state, earlier exits, a crash if there was one, and the recent log. */
    val diagnostics = _diagnostics.asStateFlow()

    fun refreshDiagnostics() {
        val model = _loadedModel.value
        val details = buildString {
            append("Model: ").append(model?.fileName ?: "none loaded")
            if (_sleeping.value) append(" (unloaded to save memory)")
            append("\n")
            model?.let {
                val p = paramsFor(it)
                append("Context ").append(p.contextSize).append(", threads ").append(if (p.threads == 0) "auto" else p.threads.toString())
                append(", template ").append(p.chatTemplate.ifEmpty { "automatic" }).append("\n")
                engine.draftStatus().takeIf { s -> s.isNotEmpty() }?.let { s -> append(s).append("\n") }
                val gpu = engine.gpuStatus()
                append("GPU: ").append(
                    when {
                        gpu.onGpu -> "in use, ${gpu.text}"
                        _gpuDisabled.value -> "switched off after the app closed while trying it"
                        p.gpuBackend.isEmpty() -> "off"
                        else -> "asked for (${p.gpuBackend}) but not used: ${gpu.text.ifEmpty { "no reason given" }}"
                    },
                ).append("\n")
                append("Vision: ").append(if (_visionReady.value) "on" else "off").append("\n")
            }
            append("Engine state: ").append(engine.state.value.javaClass.simpleName).append("\n")
            append("Saved chat states: ").append(if (_saveStates.value) "on" else "off")
        }
        viewModelScope.launch {
            _diagnostics.value = withContext(Dispatchers.IO) { AppLog.report(getApplication(), details) }
        }
    }

    fun clearCrashReport() {
        AppLog.clearCrash()
        refreshDiagnostics()
    }

    // ---- Projects -----------------------------------------------------------------------------

    private val projectStore = ProjectStore(app)
    private val _projects = MutableStateFlow(projectStore.load())

    /** The user's projects: shared instructions and documents for the chats in them. */
    val projects = _projects.asStateFlow()

    private val _chatProjectId = MutableStateFlow<String?>(null)

    /** The project the open chat belongs to, or null. */
    val chatProjectId = _chatProjectId.asStateFlow()

    private fun currentProject(): Project? = _chatProjectId.value?.let { id -> _projects.value.firstOrNull { it.id == id } }

    /** The documents attached to this chat followed by those of its project. */
    private fun allDocuments(): List<Attachment> = _attachments.value + currentProject()?.documents.orEmpty()

    private fun changeProjects(change: (List<Project>) -> List<Project>) {
        _projects.update(change)
        projectStore.save(_projects.value)
    }

    /** Makes a project and returns its id, or null if [name] is blank. */
    fun createProject(name: String, instructions: String): String? {
        val clean = Projects.cleanName(name) ?: return null
        val id = UUID.randomUUID().toString()
        changeProjects { it + Project(id, clean, instructions.trim().take(Projects.MAX_INSTRUCTIONS)) }
        return id
    }

    fun updateProject(id: String, name: String, instructions: String) {
        val clean = Projects.cleanName(name) ?: return
        changeProjects { list ->
            list.map { if (it.id == id) it.copy(name = clean, instructions = instructions.trim().take(Projects.MAX_INSTRUCTIONS)) else it }
        }
        if (_chatProjectId.value == id) refreshForProjectChange()
    }

    /** Deletes the project and its documents. Chats that were in it stay, as chats without a project. */
    fun deleteProject(project: Project) {
        val wasCurrent = _chatProjectId.value == project.id
        changeProjects { list -> list.filterNot { it.id == project.id } }
        viewModelScope.launch { project.documents.forEach { docStore.delete(it.id) } }
        if (wasCurrent) {
            _chatProjectId.value = null
            refreshForProjectChange()
        }
    }

    fun addProjectDocument(projectId: String, uri: Uri) {
        val project = _projects.value.firstOrNull { it.id == projectId } ?: return
        if (project.documents.size >= Projects.MAX_DOCUMENTS) {
            _error.value = "A project can hold up to ${Projects.MAX_DOCUMENTS} documents."
            return
        }
        if (_attachStatus.value != null) return
        viewModelScope.launch {
            _attachStatus.value = "Reading the file…"
            try {
                val doc = docStore.import(uri) { _attachStatus.value = it }
                changeProjects { list -> list.map { if (it.id == projectId) it.copy(documents = it.documents + doc) else it } }
                if (_chatProjectId.value == projectId) rebuildDocIndex()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = e.message ?: "Could not add the file"
            } finally {
                _attachStatus.value = null
            }
        }
    }

    fun removeProjectDocument(projectId: String, doc: Attachment) {
        changeProjects { list -> list.map { if (it.id == projectId) it.copy(documents = it.documents.filterNot { d -> d.id == doc.id }) else it } }
        viewModelScope.launch {
            docStore.delete(doc.id)
            if (_chatProjectId.value == projectId) rebuildDocIndex()
        }
    }

    /** Puts the open chat in a project, or takes it out of one. */
    fun setChatProject(id: String?) {
        _chatProjectId.value = id?.takeIf { wanted -> _projects.value.any { it.id == wanted } }
        refreshForProjectChange()
    }

    /** Starts an empty chat in [project]. */
    fun startChatInProject(project: Project) {
        if (anythingRunning()) return
        clearConversation()
        _chatProjectId.value = project.id
        refreshForProjectChange()
    }

    /** The model must be told the project's instructions again, and its documents searched. */
    private fun refreshForProjectChange() {
        contextStale = true
        refreshSystemPrompt()
        viewModelScope.launch { rebuildDocIndex() }
        if (_messages.value.isNotEmpty()) saveCurrentChat()
    }

    // ---- Dictation on the phone -----------------------------------------------------------------

    private val speech = AiChat.getSpeechEngine(app)
    private val speechMutex = Mutex()
    private val _speechModels = MutableStateFlow<List<LocalModel>>(emptyList())
    private val _speechProjectors = MutableStateFlow<List<LocalModel>>(emptyList())
    private val _speechModelFile = MutableStateFlow(prefs.getString(KEY_SPEECH_MODEL, "") ?: "")

    /** File name of the speech model used for dictation, or empty to use the phone's own speech recogniser. */
    val speechModelFile = _speechModelFile.asStateFlow()

    /** Speech models on the phone that have their audio add-on too, so they can be used. */
    val speechModels: StateFlow<List<LocalModel>> =
        combine(_speechModels, _speechProjectors) { models, projectors -> models.filter { SpeechModels.projectorFor(it.fileName, projectors) != null } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** True when dictation will use the speech model on the phone rather than the phone's own recogniser. */
    val offlineDictationReady: StateFlow<Boolean> =
        combine(_speechModelFile, speechModels) { file, models -> file.isNotEmpty() && models.any { it.fileName == file } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _dictation = MutableStateFlow(DictationState.Idle)
    val dictation = _dictation.asStateFlow()

    private val _dictatedText = MutableStateFlow<String?>(null)

    /** Words just written down from your voice, for the message box to take. */
    val dictatedText = _dictatedText.asStateFlow()

    fun consumeDictatedText() {
        _dictatedText.value = null
    }

    private var recorder: MicRecorder? = null
    private var speechUnloadJob: Job? = null

    fun setSpeechModel(fileName: String) {
        _speechModelFile.value = fileName
        prefs.edit().putString(KEY_SPEECH_MODEL, fileName).apply()
        if (fileName.isEmpty()) viewModelScope.launch { runCatching { speech.unload() } }
    }

    /** Starts recording your voice, or, if it is recording, stops and writes down what it heard. */
    fun toggleDictation() {
        when (_dictation.value) {
            DictationState.Idle -> startListening()
            DictationState.Listening -> finishListening()
            DictationState.Transcribing -> Unit
        }
    }

    private fun startListening() {
        if (!offlineDictationReady.value) return
        val mic = MicRecorder(onLimit = { viewModelScope.launch { if (_dictation.value == DictationState.Listening) finishListening() } })
        if (!mic.start()) {
            _error.value = "The microphone could not be used. Check that PocketLM may use it and that no other app is recording."
            return
        }
        recorder = mic
        _dictation.value = DictationState.Listening
        speechUnloadJob?.cancel()
        // The model loads while you speak, so it is ready when you finish.
        viewModelScope.launch { ensureSpeechLoaded() }
    }

    private fun finishListening() {
        val heard = recorder?.stop() ?: FloatArray(0)
        recorder = null
        _dictation.value = DictationState.Transcribing
        viewModelScope.launch {
            try {
                val trimmed = Audio.trimSilence(heard, speech.sampleRate.takeIf { it > 0 } ?: 16_000)
                if (trimmed.isEmpty()) {
                    _error.value = "No speech was heard."
                    return@launch
                }
                if (!ensureSpeechLoaded()) return@launch
                val text = Transcript.clean(speech.transcribe(trimmed))
                if (text.isEmpty()) _error.value = "No speech was heard." else _dictatedText.value = text
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = (e as? SpeechException)?.message ?: "Could not write down what you said: ${e.message ?: e::class.simpleName}"
            } finally {
                _dictation.value = DictationState.Idle
                scheduleSpeechUnload()
            }
        }
    }

    /** Stops recording without using it, for example when the app is left. */
    fun cancelDictation() {
        if (_dictation.value != DictationState.Listening) return
        recorder?.stop()
        recorder = null
        _dictation.value = DictationState.Idle
        scheduleSpeechUnload()
    }

    private suspend fun ensureSpeechLoaded(): Boolean = speechMutex.withLock {
        val model = _speechModels.value.firstOrNull { it.fileName == _speechModelFile.value }
        val projector = model?.let { SpeechModels.projectorFor(it.fileName, _speechProjectors.value) }
        if (model == null || projector == null) {
            _error.value = "The speech model's files are missing. Download them again in Settings."
            return@withLock false
        }
        if (speech.loadedPath == model.path) return@withLock true
        try {
            speech.load(model.path, projector.path)
            true
        } catch (e: SpeechException) {
            _error.value = e.message
            false
        }
    }

    /** The speech model takes about a gigabyte of memory, so it is let go a couple of minutes after the last use. */
    private fun scheduleSpeechUnload() {
        speechUnloadJob?.cancel()
        speechUnloadJob = viewModelScope.launch {
            delay(SPEECH_KEEP_MS)
            if (_dictation.value == DictationState.Idle) runCatching { speech.unload() }
        }
    }

    // ---- Replying from the notification -------------------------------------------------------

    /** Takes a message typed into the "reply ready" notification, unless the app is locked or busy. */
    private val notificationReply: (String) -> Boolean = { text ->
        !_locked.value && _loadedModel.value != null && !_loading.value && !_generating.value && send(text)
    }

    init {
        ReplyGateway.onNotificationReply = notificationReply
    }

    // ---- Text recognition language ------------------------------------------------------

    private val _ocrScript = MutableStateFlow(OcrScript.fromId(prefs.getString(KEY_OCR_SCRIPT, null)))

    /** The writing system that scanned pages and photos are read as. */
    val ocrScript = _ocrScript.asStateFlow()

    private fun setOcrScriptInternal(script: OcrScript) {
        _ocrScript.value = script
        Ocr.script = script
    }

    fun setOcrScript(script: OcrScript) {
        setOcrScriptInternal(script)
        prefs.edit().putString(KEY_OCR_SCRIPT, script.id).apply()
    }

    private val _ocrStatus = MutableStateFlow<String?>(null)

    /** Progress or the result of downloading a text recognition language, for the Settings screen. */
    val ocrStatus = _ocrStatus.asStateFlow()

    private var ocrJob: Job? = null

    /** Downloads the language data for the chosen writing system now, rather than the first time it is needed. */
    fun downloadOcrLanguage() {
        if (ocrJob?.isActive == true) return
        val script = Ocr.script
        ocrJob = viewModelScope.launch {
            _ocrStatus.value = "Checking…"
            try {
                Ocr.ensureReady(getApplication(), script) { _ocrStatus.value = it }
                _ocrStatus.value = "${script.label} text recognition is ready and works offline."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ocrStatus.value = e.message ?: "The download failed."
            }
        }
    }

    // ---- Document attachments (retrieval) -------------------------------------

    private val docStore = DocumentStore(app)
    private val _attachments = MutableStateFlow<List<Attachment>>(emptyList())
    val attachments = _attachments.asStateFlow()
    private var docIndex: Bm25Index? = null
    /** Meaning vectors for every passage of [docIndex], in the same order, once they have been made. */
    private var docVectors: List<FloatArray>? = null
    private var vectorJob: Job? = null

    private val _attachStatus = MutableStateFlow<String?>(null)

    /** A short progress line while a file is being read (a PDF, or text recognised from pages), or null. */
    val attachStatus = _attachStatus.asStateFlow()

    fun attach(uri: Uri) {
        if (_attachStatus.value != null) return
        viewModelScope.launch { attachNow(uri) }
    }

    /** Attaches a picked or shared file or photo and returns when it is done. */
    private suspend fun attachNow(uri: Uri) {
        val isImage = getApplication<Application>().contentResolver.getType(uri)?.startsWith("image/") == true
        // A model that can see sends the photo itself; otherwise its text is read and attached as a document.
        if (isImage && _visionReady.value) {
            attachImageNow(uri)
            return
        }
        _attachStatus.value = if (isImage) "Reading text in the photo…" else "Reading the file…"
        try {
            val doc = if (isImage) {
                val text = Ocr.recognize(getApplication(), uri) { _attachStatus.value = it }.trim()
                if (text.isEmpty()) throw IOException("No text was found in that photo, and the loaded model can't see images.")
                docStore.saveText("Photo text.txt", text).also { noteIncognitoDoc(it) }
            } else {
                docStore.import(uri) { _attachStatus.value = it }.also { noteIncognitoDoc(it) }
            }
            _attachments.update { it + doc }
            rebuildDocIndex()
            if (_messages.value.isNotEmpty()) saveCurrentChat()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _error.value = e.message ?: "Could not attach the file"
        } finally {
            _attachStatus.value = null
        }
    }

    /** Something shared into PocketLM: files go into a fresh chat, with a question ready to send. */
    fun receiveFiles(uris: List<Uri>) {
        val files = uris.take(ShareIntake.MAX_FILES)
        if (files.isEmpty()) return
        viewModelScope.launch {
            startFreshChat()
            val resolver = getApplication<Application>().contentResolver
            for (uri in files) attachNow(uri)
            _incomingText.value = ShareIntake.suggestedQuestion(ShareIntake.kindOf(resolver.getType(files.first())))
        }
    }

    /**
     * What was on the screen when the assistant gesture was used: a fresh chat gets the screen's text as a document and
     * the screenshot as a photo (or, if the model can't see, the words read from it), with a question ready to send.
     */
    fun receiveScreen(text: String?, screenshotPath: String?) {
        viewModelScope.launch {
            startFreshChat()
            val shot = screenshotPath?.let(::File)?.takeIf { it.isFile }
            try {
                val words = text?.trim().orEmpty()
                if (words.isNotEmpty()) {
                    val doc = docStore.saveText("Screen text", words).also { noteIncognitoDoc(it) }
                    _attachments.update { it + doc }
                    rebuildDocIndex()
                }
                if (shot != null) {
                    val uri = Uri.fromFile(shot)
                    if (_visionReady.value) {
                        attachImageNow(uri)
                    } else if (words.isEmpty()) {
                        // Nothing came as text and the model can't see: read the words out of the screenshot.
                        _attachStatus.value = "Reading text in the screenshot…"
                        val read = Ocr.recognize(getApplication(), uri) { _attachStatus.value = it }.trim()
                        if (read.isNotEmpty()) {
                            val doc = docStore.saveText("Screen text", read).also { noteIncognitoDoc(it) }
                            _attachments.update { it + doc }
                            rebuildDocIndex()
                        }
                    }
                }
                if (words.isEmpty() && shot == null) {
                    _error.value = "The phone did not share the screen. In its assistant settings, allow PocketLM to use text and a screenshot from the screen."
                }
                _incomingText.value = "What is on my screen? Summarise it."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = e.message ?: "Could not read the screen"
            } finally {
                _attachStatus.value = null
                shot?.delete()
            }
        }
    }

    /** A link shared into PocketLM: its page is read into a fresh chat. */
    fun receiveLink(url: String) {
        viewModelScope.launch {
            startFreshChat()
            attachWebPageNow(url)
            _incomingText.value = ShareIntake.suggestedQuestion(ShareIntake.Kind.Page)
        }
    }

    /** A new chat for something shared in, so it does not get mixed into whatever was open. */
    private suspend fun startFreshChat() {
        if (_messages.value.isEmpty() && _attachments.value.isEmpty() && _pendingImages.value.isEmpty()) return
        val model = _loadedModel.value
        if (model != null && !_loading.value) {
            load(model)
            _loading.first { !it }
        } else if (model == null) {
            clearConversation()
        }
    }

    /** Remembers a document made in an incognito chat, so it is deleted when that chat is left. */
    private fun noteIncognitoDoc(doc: Attachment) {
        if (_incognito.value) incognitoLedger.add(IncognitoLedger.doc(doc.id))
    }

    fun removeAttachment(doc: Attachment) {
        viewModelScope.launch {
            _attachments.update { list -> list.filterNot { it.id == doc.id } }
            docStore.delete(doc.id)
            rebuildDocIndex()
            if (_messages.value.isNotEmpty()) saveCurrentChat()
        }
    }

    private suspend fun rebuildDocIndex() {
        val docs = allDocuments().mapNotNull { a -> docStore.read(a.id)?.let { a.name to it } }
        vectorJob?.cancel()
        docVectors = null
        docIndex = if (docs.isEmpty()) null else withContext(Dispatchers.Default) { Bm25Index.build(docs) }
        if (docIndex == null) {
            _indexStatus.value = null
            releaseEmbedder()
        } else {
            startVectorIndexing()
        }
    }

    // ---- Searching documents by meaning ----------------------------------------------

    private val embedder = AiChat.getEmbeddingEngine(app)
    private val _embeddingFile = MutableStateFlow(prefs.getString(KEY_EMBED_MODEL, "") ?: "")

    /** File name of the embedding model that documents are searched with by meaning, or empty for keywords only. */
    val embeddingFile = _embeddingFile.asStateFlow()

    /** Downloaded models that make embeddings (as opposed to chat models). */
    val embeddingModels: StateFlow<List<LocalModel>> =
        combine(_models, _modelInfo) { models, info -> models.filter { EmbeddingModels.isEmbedding(info[it.path]?.architecture, it.fileName) } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _indexStatus = MutableStateFlow<String?>(null)

    /** Progress while documents are read for meaning search, or null. */
    val indexStatus = _indexStatus.asStateFlow()

    private val _embeddingNote = MutableStateFlow<String?>(null)

    /** The result of trying the chosen embedding model, shown in Settings. */
    val embeddingNote = _embeddingNote.asStateFlow()

    private fun selectedEmbeddingModel(): LocalModel? =
        _embeddingFile.value.takeIf { it.isNotEmpty() }?.let { file -> _models.value.firstOrNull { it.fileName == file } }

    /** Chooses the embedding model for document search; an empty name goes back to keyword search only. */
    fun setEmbeddingModel(fileName: String) {
        _embeddingFile.value = fileName
        prefs.edit().putString(KEY_EMBED_MODEL, fileName).apply()
        _embeddingNote.value = null
        vectorJob?.cancel()
        docVectors = null
        viewModelScope.launch {
            val model = selectedEmbeddingModel()
            if (model == null) {
                releaseEmbedder()
                return@launch
            }
            // Try it now, so a model that does not work is found out here and not in the middle of a question.
            _embeddingNote.value = "Trying ${model.fileName.removeSuffix(".gguf")}…"
            _embeddingNote.value = try {
                ensureEmbedder(model)
                "Ready: ${embedder.dimensions}-number vectors"
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _embeddingFile.value = ""
                prefs.edit().putString(KEY_EMBED_MODEL, "").apply()
                e.message ?: "That model could not be used."
            }
            if (docIndex != null) startVectorIndexing()
            // The notes folder is read again with the new model, so its passages match by meaning too.
            if (prefs.getString(KEY_NOTES_FOLDER, null) != null) updateNotes()
        }
    }

    private suspend fun ensureEmbedder(model: LocalModel) {
        if (embedder.loadedPath != model.path) embedder.load(model.path, threads = 0, maxTokens = EMBED_MAX_TOKENS)
    }

    private fun releaseEmbedder() {
        vectorJob?.cancel()
        docVectors = null
        viewModelScope.launch { runCatching { embedder.unload() } }
    }

    /** Reads every passage with the embedding model in the background; keyword search works meanwhile. */
    private fun startVectorIndexing() {
        vectorJob?.cancel()
        docVectors = null
        val model = selectedEmbeddingModel() ?: return
        val index = docIndex ?: return
        val attachments = allDocuments()
        vectorJob = viewModelScope.launch {
            try {
                ensureEmbedder(model)
                val prefixes = EmbeddingModels.prefixes(model.fileName)
                val file = File(model.path)
                val total = index.chunkList.size
                val all = ArrayList<FloatArray>(total)
                for (attachment in attachments) {
                    val text = docStore.read(attachment.id) ?: continue
                    val pieces = Chunker.split(text)
                    val key = EmbeddingFile.key(model.fileName, file.length(), file.lastModified(), prefixes, text)
                    val saved = docStore.readVectors(attachment.id)?.let { EmbeddingFile.read(it, key, pieces.size) }
                    if (saved != null) {
                        all += saved
                        continue
                    }
                    val made = ArrayList<FloatArray>(pieces.size)
                    for (piece in pieces) {
                        ensureActive()
                        _indexStatus.value = "Reading your documents for meaning search… ${all.size + made.size} of $total"
                        made += embedder.embed(prefixes.passage + piece)
                    }
                    docStore.writeVectors(attachment.id, EmbeddingFile.write(key, made))
                    all += made
                }
                if (docIndex === index && all.size == total) docVectors = all
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.note("Meaning search is off: ${e.message}")
                _error.value = "Searching by meaning is off for now: ${e.message ?: e::class.simpleName}"
            } finally {
                _indexStatus.value = null
            }
        }
    }

    /** The question as a vector, or null if meaning search is not ready (the keyword search then works alone). */
    private suspend fun questionVector(question: String, notesHaveMeaning: Boolean = false): FloatArray? {
        val model = selectedEmbeddingModel() ?: return null
        if (docVectors == null && !notesHaveMeaning) return null
        return try {
            withTimeout(EMBED_QUESTION_TIMEOUT_MS) {
                ensureEmbedder(model)
                embedder.embed(EmbeddingModels.prefixes(model.fileName).query + question.take(2_000))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /** Wraps [question] with the most relevant excerpts from the attached documents, if any. */
    private suspend fun withDocumentContext(question: String): String {
        val index = docIndex
        val notes = if (_useNotes.value && _notesStatus.value.folderName != null) notesSearcher() else null
        if (index == null && notes == null) return question
        val contextSize = engine.contextUsage().total.takeIf { it > 0 } ?: _generationParams.value.contextSize
        val budget = contextSize / 4 * 3 // ~3 chars per token, a quarter of the context
        val share = if (index != null && notes != null) budget / 2 else budget
        val vector = questionVector(question, notes?.hasMeaning == true)
        val fromDocuments = index?.let { DocumentSearch(it, docVectors).search(question, vector, share) }.orEmpty()
        val fromNotes = notes?.search(question, vector, share).orEmpty()
        return NotesRules.excerptsPrompt(question, fromDocuments, fromNotes)
    }

    // ---- Ask my notes: a folder of notes searched from any chat ---------------------------

    /** What Settings shows about the notes folder. */
    data class NotesStatus(
        val folderName: String? = null,
        val files: Int = 0,
        val passages: Int = 0,
        val meaning: Boolean = false,
        val updatedAt: Long = 0,
        /** Progress while the folder is being read, or null. */
        val working: String? = null,
        val problem: String? = null,
    )

    private val knowledge = KnowledgeStore(File(app.filesDir, "knowledge"))
    private val notesScanner = NotesScanner(app)

    private val _notesStatus = MutableStateFlow(
        NotesStatus(
            folderName = prefs.getString(KEY_NOTES_NAME, null),
            files = if (prefs.getString(KEY_NOTES_FOLDER, null) != null) knowledge.manifest().size else 0,
            passages = prefs.getInt(KEY_NOTES_PASSAGES, 0),
            meaning = prefs.getBoolean(KEY_NOTES_MEANING, false),
            updatedAt = prefs.getLong(KEY_NOTES_UPDATED, 0),
        ),
    )
    val notesStatus = _notesStatus.asStateFlow()

    private val _notesDefault = MutableStateFlow(prefs.getBoolean(KEY_NOTES_DEFAULT, true))

    /** Whether new chats search the notes folder from the start. */
    val notesDefault = _notesDefault.asStateFlow()

    fun setNotesDefault(on: Boolean) {
        _notesDefault.value = on
        prefs.edit().putBoolean(KEY_NOTES_DEFAULT, on).apply()
    }

    private val _useNotes = MutableStateFlow(_notesDefault.value)

    /** Whether the chat on screen searches the notes folder. */
    val useNotes = _useNotes.asStateFlow()

    fun setUseNotes(on: Boolean) {
        _useNotes.value = on
        if (_messages.value.isNotEmpty()) saveCurrentChat()
    }

    private var notesJob: Job? = null
    private var notesSearch: DocumentSearch? = null

    /** Remembers the folder the user picked and reads it. */
    fun chooseNotesFolder(uri: Uri) {
        notesJob?.cancel()
        prefs.getString(KEY_NOTES_FOLDER, null)?.let { old -> if (old != uri.toString()) NotesScanner.releasePermission(getApplication(), Uri.parse(old)) }
        knowledge.clear()
        notesSearch = null
        viewModelScope.launch {
            val name = withContext(Dispatchers.IO) { FolderBackend.displayName(getApplication(), uri) }
            prefs.edit().putString(KEY_NOTES_FOLDER, uri.toString()).putString(KEY_NOTES_NAME, name)
                .putInt(KEY_NOTES_PASSAGES, 0).putBoolean(KEY_NOTES_MEANING, false).putLong(KEY_NOTES_UPDATED, 0).apply()
            _notesStatus.value = NotesStatus(folderName = name)
            updateNotes()
        }
    }

    /** Stops using the notes folder and erases what PocketLM saved about it. The folder itself is not touched. */
    fun forgetNotesFolder() {
        notesJob?.cancel()
        prefs.getString(KEY_NOTES_FOLDER, null)?.let { NotesScanner.releasePermission(getApplication(), Uri.parse(it)) }
        prefs.edit().remove(KEY_NOTES_FOLDER).remove(KEY_NOTES_NAME).remove(KEY_NOTES_PASSAGES).remove(KEY_NOTES_MEANING)
            .remove(KEY_NOTES_UPDATED).apply()
        notesSearch = null
        viewModelScope.launch(Dispatchers.IO) { knowledge.clear() }
        _notesStatus.value = NotesStatus()
    }

    /** Looks through the folder again, reading only files that are new or changed, then (if a search model is chosen) their meaning. */
    fun updateNotes() {
        val tree = prefs.getString(KEY_NOTES_FOLDER, null)?.let(Uri::parse) ?: return
        notesJob?.cancel()
        notesJob = viewModelScope.launch {
            try {
                _notesStatus.update { it.copy(working = "Looking through the folder…", problem = null) }
                val scan = withContext(Dispatchers.IO) { notesScanner.scan(tree) }
                val known = withContext(Dispatchers.IO) { knowledge.manifest() }
                val plan = NotesRules.plan(known, scan.files.map { it.stamp })
                val byPath = scan.files.associateBy { it.stamp.path }
                val manifest = LinkedHashMap(known)
                withContext(Dispatchers.IO) { plan.drop.forEach { knowledge.delete(it); manifest.remove(it) } }

                var used = plan.keep.sumOf { known[it]?.size ?: 0L }
                var truncated = false
                plan.read.forEachIndexed { done, stamp ->
                    ensureActive()
                    _notesStatus.update { it.copy(working = "Reading notes… ${done + 1} of ${plan.read.size}") }
                    val text = withContext(Dispatchers.IO) { runCatching { notesScanner.read(byPath.getValue(stamp.path)) }.getOrNull() }
                    // A file that cannot be read is kept as empty, so it is not tried again until it changes.
                    val keep = text != null && used + text.length <= NotesRules.MAX_TOTAL_CHARS
                    if (text != null && !keep) truncated = true
                    if (keep) used += text!!.length
                    withContext(Dispatchers.IO) { knowledge.writeText(stamp.path, if (keep) text!! else "") }
                    manifest[stamp.path] = stamp
                }
                withContext(Dispatchers.IO) { knowledge.saveManifest(manifest.values) }
                notesSearch = null

                val docs = withContext(Dispatchers.IO) { knowledge.documents().filter { it.second.isNotBlank() } }
                val passages = withContext(Dispatchers.Default) { docs.sumOf { Chunker.split(it.second).size } }
                val meaning = indexNotesMeaning(docs)
                val now = System.currentTimeMillis()
                prefs.edit().putInt(KEY_NOTES_PASSAGES, passages).putBoolean(KEY_NOTES_MEANING, meaning).putLong(KEY_NOTES_UPDATED, now).apply()
                notesSearch = null
                _notesStatus.update {
                    it.copy(
                        files = docs.size, passages = passages, meaning = meaning, updatedAt = now, working = null,
                        problem = when {
                            truncated || scan.tooMany -> "The folder is larger than PocketLM reads (about 3,000 files or 12 million characters); the rest was left out."
                            docs.isEmpty() -> "No text, Markdown, HTML or PDF files were found in that folder."
                            else -> null
                        },
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.note("Notes folder: ${e.message}")
                _notesStatus.update { it.copy(working = null, problem = e.message ?: "The folder could not be read.") }
            }
        }
    }

    /** Reads every note with the embedding model (when one is chosen) so questions match by meaning too. True if all are done. */
    private suspend fun indexNotesMeaning(docs: List<Pair<String, String>>): Boolean {
        val model = selectedEmbeddingModel() ?: return false
        return try {
            ensureEmbedder(model)
            val prefixes = EmbeddingModels.prefixes(model.fileName)
            val file = File(model.path)
            val pending = ArrayList<Triple<String, List<String>, String>>()
            for ((path, text) in docs) {
                val pieces = Chunker.split(text)
                val key = EmbeddingFile.key(model.fileName, file.length(), file.lastModified(), prefixes, text)
                val saved = knowledge.readVectors(path)?.let { EmbeddingFile.read(it, key, pieces.size) }
                if (saved == null) pending += Triple(path, pieces, key)
            }
            val total = pending.sumOf { it.second.size }
            var done = 0
            for ((path, pieces, key) in pending) {
                val vectors = ArrayList<FloatArray>(pieces.size)
                for (piece in pieces) {
                    currentCoroutineContext().ensureActive()
                    _notesStatus.update { it.copy(working = "Reading notes for meaning search… ${done++} of $total") }
                    vectors += embedder.embed(prefixes.passage + piece)
                }
                withContext(Dispatchers.IO) { knowledge.writeVectors(path, EmbeddingFile.write(key, vectors)) }
            }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.note("Notes meaning search: ${e.message}")
            false
        }
    }

    /** The notes folder as a search, built from the saved copy the first time it is needed after a change. */
    private suspend fun notesSearcher(): DocumentSearch? {
        notesSearch?.let { return it }
        val model = selectedEmbeddingModel()
        val built = withContext(Dispatchers.Default) {
            val docs = knowledge.documents().filter { it.second.isNotBlank() }
            if (docs.isEmpty()) return@withContext null
            val keyword = Bm25Index.build(docs)
            var vectors: List<FloatArray>? = null
            if (model != null) {
                val file = File(model.path)
                val prefixes = EmbeddingModels.prefixes(model.fileName)
                val all = ArrayList<FloatArray>()
                for ((path, text) in docs) {
                    val pieces = Chunker.split(text)
                    val key = EmbeddingFile.key(model.fileName, file.length(), file.lastModified(), prefixes, text)
                    val saved = knowledge.readVectors(path)?.let { EmbeddingFile.read(it, key, pieces.size) }
                    if (saved == null) { all.clear(); break }
                    all += saved
                }
                if (all.size == keyword.chunkList.size && all.isNotEmpty()) vectors = all
            }
            DocumentSearch(keyword, vectors)
        }
        notesSearch = built
        return built
    }

    // ---- Chat history --------------------------------------------------------

    private val chatStore = ChatStore(app)
    private val _openChatId = MutableStateFlow<String?>(null)

    /** Id of the chat on screen once it has been started or saved; used to mark it in the list beside the chat. */
    val openChatId = _openChatId.asStateFlow()

    private var currentChatId: String? by kotlin.properties.Delegates.observable(null) { _, _, new -> _openChatId.value = new }
    private var pendingContext: String? = null

    private val _chats = MutableStateFlow<List<SavedChat>>(emptyList())
    val chats = _chats.asStateFlow()

    fun refreshChats() {
        viewModelScope.launch { _chats.value = chatStore.list() }
    }

    private val _incognito = MutableStateFlow(false)

    /** True while the chat on screen is incognito: nothing about it is saved, synced or listed in History. */
    val incognito = _incognito.asStateFlow()

    private val incognitoLedger = IncognitoLedger(File(app.filesDir, "incognito.txt"))

    /** Starts a new chat that is kept nowhere: it is erased, with its attachments, when you leave it. */
    fun startIncognitoChat() {
        val model = _loadedModel.value
        if (model == null) {
            _error.value = "Load a model first."
            return
        }
        load(model, incognito = true)
    }

    /** Erases what an incognito chat left on disk. The conversation itself only ever existed in memory. */
    private fun discardIncognito() {
        if (!_incognito.value) return
        _incognito.value = false
        incognitoLedger.purge(File(getApplication<Application>().filesDir, "docs"), File(getApplication<Application>().filesDir, "images"))
    }

    private fun clearConversation() {
        discardIncognito()
        if (_messages.value.isEmpty()) {
            // Nothing was saved for this chat, so its attachments would be orphaned.
            _attachments.value.forEach { a -> viewModelScope.launch { docStore.delete(a.id) } }
        }
        removePendingImage()
        _cutOffId.value = null
        _chatPresetId.value = null
        _jsonMode.value = false
        _thinking.value = true
        _useNotes.value = _notesDefault.value
        _jsonSchema.value = null
        _promptRecord.value = null
        contextStale = false
        _attachments.value = emptyList()
        _chatProjectId.value = null
        docIndex = null
        vectorJob?.cancel()
        docVectors = null
        _indexStatus.value = null
        viewModelScope.launch { runCatching { embedder.unload() } }
        currentChatId = null
        pendingContext = null
        _messages.value = emptyList()
    }

    private fun stored(m: ChatMessage) =
        StoredMessage(m.text, m.isUser, m.stats, m.imagePath, m.summary, m.others, m.position, m.starred, moreImages = m.moreImages)

    private fun saveCurrentChat() {
        if (_incognito.value) return
        val id = currentChatId ?: return
        val msgs = _messages.value.filter { it.isUser || it.text.isNotEmpty() }
        val firstUser = msgs.firstOrNull { it.isUser }?.text ?: return
        val existing = _chats.value.firstOrNull { it.id == id }
        val chat = SavedChat(
            id = id,
            title = existing?.takeIf { it.customTitle }?.title ?: firstUser.lineSequence().first().take(48),
            updatedAt = System.currentTimeMillis(),
            pinned = existing?.pinned ?: false,
            customTitle = existing?.customTitle ?: false,
            presetId = _chatPresetId.value,
            jsonMode = _jsonMode.value,
            thinking = _thinking.value,
            useNotes = _useNotes.value.takeIf { it != _notesDefault.value },
            jsonSchema = _jsonSchema.value,
            tags = existing?.tags ?: emptyList(),
            projectId = _chatProjectId.value,
            modelFile = _loadedModel.value?.fileName,
            messages = msgs.map(::stored),
            attachments = _attachments.value,
        )
        viewModelScope.launch {
            chatStore.save(chat)
            refreshChats()
            scheduleSync()
        }
    }

    /** Reopens a saved chat, reloading its model (or the current one) to get a clean context. */
    private val _scrollTarget = MutableStateFlow<Int?>(null)

    /** A message the chat screen should scroll to once it is showing (set when a starred message is opened). */
    val scrollTarget = _scrollTarget.asStateFlow()

    fun consumeScrollTarget() {
        _scrollTarget.value = null
    }

    /** Stars or un-stars a message, so it can be found in the list of starred messages. */
    fun toggleStar(messageId: String) {
        _messages.update { list -> list.map { if (it.id == messageId) it.copy(starred = !it.starred) else it } }
        if (!_generating.value) saveCurrentChat()
    }

    fun openChat(chat: SavedChat, scrollToMessage: Int? = null) {
        if (_loading.value) return
        _scrollTarget.value = scrollToMessage
        stopGeneration()
        discardIncognito()
        currentChatId = chat.id
        _messages.value = chat.messages.map {
            ChatMessage(
                it.text, it.isUser, stats = it.stats, imagePath = it.imagePath, moreImages = it.moreImages,
                summary = it.summary, others = it.others, position = it.position, starred = it.starred,
            )
        }
        pendingContext = Replay.build(turns())
        _attachments.value = chat.attachments
        _chatPresetId.value = chat.presetId
        _jsonMode.value = chat.jsonMode
        _thinking.value = chat.thinking
        _useNotes.value = chat.useNotes ?: _notesDefault.value
        _jsonSchema.value = chat.jsonSchema
        _chatProjectId.value = chat.projectId?.takeIf { id -> _projects.value.any { it.id == id } }
        contextStale = false
        viewModelScope.launch { rebuildDocIndex() }

        val model = _models.value.firstOrNull { it.fileName == chat.modelFile } ?: _loadedModel.value
        if (model != null) {
            load(model, resetChat = false, restore = true)
        } else {
            _error.value = "Load a model to continue this chat"
        }
    }

    /** The open chat as Markdown, or null while it is empty. */
    fun currentChatMarkdown(): String? {
        val msgs = _messages.value.filter { it.isUser || it.text.isNotEmpty() }
        val first = msgs.firstOrNull { it.isUser }?.text ?: return null
        val title = _chats.value.firstOrNull { it.id == currentChatId }?.title
            ?: first.lineSequence().first().take(48)
        return ChatExport.toMarkdown(
            title,
            msgs.map(::stored),
            _loadedModel.value?.fileName,
            System.currentTimeMillis(),
        )
    }

    fun markdownFor(chat: SavedChat): String =
        ChatExport.toMarkdown(chat.title, chat.messages, chat.modelFile, chat.updatedAt)

    /** Writes [chat] as a Markdown file to a location the user picked. */
    fun saveChatTo(uri: Uri, chat: SavedChat) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openOutputStream(uri)?.use {
                        it.write(markdownFor(chat).toByteArray())
                    } ?: throw java.io.IOException("Cannot write to that location")
                }
                _error.value = "Chat saved"
            } catch (e: Exception) {
                _error.value = "Could not save the chat: ${e.message}"
            }
        }
    }

    fun renameChat(chat: SavedChat, title: String) {
        val name = title.trim().ifEmpty { return }
        viewModelScope.launch {
            chatStore.save(chat.copy(title = name.take(80), customTitle = true, updatedAt = System.currentTimeMillis()))
            refreshChats()
            scheduleSync()
        }
    }

    private val _importing = MutableStateFlow<String?>(null)

    /** Progress text while chats are being imported from a ChatGPT or Claude export, or null. */
    val importProgress = _importing.asStateFlow()

    /** Reads a ChatGPT or Claude data export (the .zip, or its conversations.json) and adds its conversations as chats. */
    fun importChats(uri: Uri) {
        if (_importing.value != null) return
        viewModelScope.launch {
            _importing.value = "Reading the file…"
            try {
                val existing = chatStore.list().associate { it.id to it.updatedAt }
                val summary = withContext(Dispatchers.IO) {
                    var stored = 0
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use { stream ->
                        ChatImporter.read(stream) { chat ->
                            if (ChatImporter.alreadyUpToDate(existing[chat.id], chat)) {
                                false
                            } else {
                                chatStore.saveNow(chat.toSavedChat())
                                if (++stored % 10 == 0) _importing.value = "Imported $stored chats…"
                                true
                            }
                        }
                    } ?: throw IOException("Cannot open that file")
                }
                refreshChats()
                scheduleSync()
                _error.value = summary.message()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _error.value = "Import failed: ${e.message ?: e::class.simpleName}"
            } finally {
                _importing.value = null
            }
        }
    }

    /** Replaces the labels on a chat. */
    fun setTags(chat: SavedChat, tags: List<String>) {
        val clean = ChatTags.clean(tags)
        if (clean == chat.tags) return
        viewModelScope.launch {
            chatStore.save(chat.copy(tags = clean, updatedAt = System.currentTimeMillis()))
            refreshChats()
            scheduleSync()
        }
    }

    fun togglePin(chat: SavedChat) {
        viewModelScope.launch {
            chatStore.save(chat.copy(pinned = !chat.pinned, updatedAt = System.currentTimeMillis()))
            refreshChats()
            scheduleSync()
        }
    }

    fun deleteChat(chat: SavedChat) {
        viewModelScope.launch {
            rememberDeletion(chat)
            chatStore.delete(chat.id)
            stateCache.delete(chat.id)
            chat.attachments.forEach { docStore.delete(it.id) }
            chat.messages.flatMap { it.images }.forEach { imageStore.delete(it) }
            if (currentChatId == chat.id) {
                clearConversation()
                _loadedModel.value?.let { load(it) }
            }
            refreshChats()
            scheduleSync()
        }
    }

    fun stopGeneration() {
        generationJob?.cancel()
        generationJob = null
    }

    private suspend fun awaitIdleEngine() {
        engine.state.first {
            it is InferenceEngine.State.Initialized || it is InferenceEngine.State.Error
        }
    }

    init {
        // If the app was closed in the middle of an incognito chat, erase what that chat left behind.
        incognitoLedger.purge(File(app.filesDir, "docs"), File(app.filesDir, "images"))
        if (prefs.getString(KEY_NOTES_FOLDER, null) != null) {
            // Keep the notes up to date; only files that changed are read again.
            viewModelScope.launch {
                delay(NOTES_START_DELAY_MS)
                updateNotes()
            }
        }
        refreshModels()
        refreshChats()
        refreshAdapters()
        pruneAdapters()
        syncRunner.listener = syncListener
        syncRunner.updateBackgroundJob()
        publishSyncStatus()
        if (syncConfig.configured && syncConfig.autoSync) {
            // Give the app a moment to finish starting before the network is used.
            viewModelScope.launch {
                delay(SYNC_START_DELAY_MS)
                runSync(manual = false)
            }
        }
        // If the app ended while it was trying a GPU, do not try again until the user says so.
        if (gpuGuard.recoverFromCrash()) {
            _gpuDisabled.value = true
            _error.value = "PocketLM closed while trying to use the GPU, so the GPU is switched off. " +
                "You can allow it again in a model's settings."
        }
        // Every message shown to the user is also written to the log, so a bug report includes it.
        viewModelScope.launch {
            _error.collect { message -> message?.let { AppLog.note("Shown to the user: $it") } }
        }
    }

    init {
        viewModelScope.launch {
            while (true) {
                delay(IDLE_CHECK_MS)
                sleepIfIdle()
            }
        }
        ApiGateway.backend = apiBackend
        if (_apiEnabled.value) ApiService.start(getApplication())
        startDeviceMonitoring()
        watchThrottle()
        viewModelScope.launch {
            center.events.collect { event ->
                when (event) {
                    is DownloadCenter.Event.Completed -> refreshModels()
                    is DownloadCenter.Event.Failed -> _error.value = "Download failed: ${event.message}"
                }
            }
        }
    }

    override fun onCleared() {
        discardIncognito()
        if (syncRunner.listener === syncListener) syncRunner.listener = null
        vectorJob?.cancel()
        voiceListener?.stop()
        CoroutineScope(Dispatchers.IO).launch { runCatching { embedder.unload() } }
        recorder?.stop()
        ReplyGateway.stopRequested = null
        if (ReplyGateway.onNotificationReply === notificationReply) ReplyGateway.onNotificationReply = null
        ReplyService.stop(getApplication())
        if (ApiGateway.backend === apiBackend) ApiGateway.backend = null
        stopDeviceMonitoring()
        speaker?.let { runCatching { it.shutdown() } }
        speaker = null
        stopGeneration()
        super.onCleared()
    }

    private companion object {
        const val KEY_THEME = "theme_mode"
        const val KEY_PRESETS = SettingKeys.PRESETS
        const val KEY_PROJECTOR = "projector_for_"
        const val KEY_PRESET_ID = SettingKeys.PRESET_ID
        const val KEY_CTX = "gen_context_size"
        const val KEY_THREADS = "gen_threads"
        const val KEY_TEMP = SettingKeys.TEMP
        const val KEY_TOP_P = SettingKeys.TOP_P
        const val KEY_TOP_K = SettingKeys.TOP_K
        const val KEY_MIN_P = SettingKeys.MIN_P
        const val KEY_REPEAT = SettingKeys.REPEAT
        const val KEY_SEED = SettingKeys.SEED
        const val KEY_STOP = SettingKeys.STOP
        const val KEY_PROMPTS = SettingKeys.PROMPTS
        const val KEY_BENCH = "benchmarks"
        const val KEY_LOCK = "app_lock"
        const val KEY_READ_ALOUD = SettingKeys.READ_ALOUD
        const val KEY_API_ENABLED = "api_enabled"
        const val KEY_BG_REPLIES = "background_replies"
        const val KEY_TOOLS = SettingKeys.TOOLS
        const val KEY_IDLE_MINUTES = "idle_minutes"
        const val KEY_AUTO_THROTTLE = "auto_throttle"
        const val KEY_EMBED_MODEL = "embedding_model"
        const val KEY_CACHE_BITS = "cache_bits"
        const val KEY_TEXT_SCALE = "text_scale"
        const val NOTES_START_DELAY_MS = 8_000L
        const val KEY_NOTES_FOLDER = "notes_folder"
        const val KEY_NOTES_NAME = "notes_folder_name"
        const val KEY_NOTES_PASSAGES = "notes_passages"
        const val KEY_NOTES_MEANING = "notes_meaning"
        const val KEY_NOTES_UPDATED = "notes_updated"
        const val KEY_NOTES_DEFAULT = "notes_default"
        const val VOICE_PAUSE_MS = 500L
        const val EMBED_MAX_TOKENS = 512
        const val EMBED_QUESTION_TIMEOUT_MS = 10_000L
        const val KEY_MAX_REPLY = SettingKeys.MAX_REPLY
        const val MAX_IMAGES = 4
        const val KEY_OCR_SCRIPT = "ocr_script"
        const val KEY_SPEECH_MODEL = "speech_model"
        const val SPEECH_KEEP_MS = 120_000L
        const val KEY_CATALOG_UPDATES = "catalog_updates"
        const val KEY_MODEL_SETTINGS = "model_settings"
        const val KEY_NOTES = SettingKeys.NOTES
        const val KEY_SAVE_STATES = SettingKeys.SAVE_STATES
        const val KEY_SEARCH_SERVER = "search_server"
        const val KEY_SEARCH_PROVIDER = "search_provider"
        const val KEY_BRAVE_KEY = "search_brave_key"
        const val KEY_HUB = "hub_source"
        const val KEY_MIRROR = "hub_mirror_url"
        const val MAX_BENCH_RESULTS = 20
        const val IDLE_CHECK_MS = 30_000L
        const val MAX_INCOMING_CHARS = 8000
        val DEFAULT_PARAMS = GenerationParams()
        const val MAX_REPLAY_CHARS = 4000
        const val MIN_PASSPHRASE = 8
        const val SYNC_DEBOUNCE_MS = 20_000L
        const val SYNC_SOON_MS = 3_000L
        const val SYNC_START_DELAY_MS = 5_000L
    }
}
