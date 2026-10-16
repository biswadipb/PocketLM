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
import com.arm.aichat.GenerationParams
import com.arm.aichat.InferenceEngine
import com.arm.aichat.isModelLoaded
import com.pocketlm.app.data.AppLog
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
import com.pocketlm.app.data.StatePolicy
import com.pocketlm.app.data.Summaries
import com.pocketlm.app.data.Turn
import com.pocketlm.app.data.Variants
import com.pocketlm.app.data.WebPage
import com.pocketlm.app.data.BackupManager
import com.arm.aichat.ContextUsage
import com.pocketlm.app.data.ApiBackend
import com.pocketlm.app.data.ApiException
import com.pocketlm.app.data.BenchResult
import com.pocketlm.app.data.ChatRequest
import com.pocketlm.app.data.Bm25Index
import com.pocketlm.app.data.CatalogModel
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
import com.pocketlm.app.data.Ocr
import com.pocketlm.app.data.Presets
import com.pocketlm.app.data.PromptLibrary
import com.pocketlm.app.data.SavedPrompt
import com.pocketlm.app.data.Speaker
import com.pocketlm.app.ui.ThemeMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
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
)

data class SearchState(
    val query: String = "",
    val loading: Boolean = false,
    val searched: Boolean = false,
    val results: List<HfModel> = emptyList(),
)

/** Files of one repository: null [files] while still loading. */
data class RepoFiles(val files: List<HfFile>? = null, val error: String? = null)

enum class ComparePhase { Waiting, Loading, Generating, Done, Failed }

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
        return (own?.params ?: _generationParams.value)
            .copy(chatTemplate = own?.chatTemplate.orEmpty(), draftModelPath = draft)
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
        )
    }

    /** Saves what the model remembers of the open chat, once a reply is finished. */
    private fun saveChatState() {
        if (!_saveStates.value) return
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
        val images = (chats.flatMap { c -> c.messages.mapNotNull { it.imagePath } } +
            _messages.value.mapNotNull { it.imagePath } + listOfNotNull(_pendingImage.value)).toSet()
        val documents = (chats.flatMap { c -> c.attachments.map { it.id } } + _attachments.value.map { it.id }).toSet()
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
                chatStore.delete(chat.id)
                chat.attachments.forEach { docStore.delete(it.id) }
                chat.messages.mapNotNull { it.imagePath }.forEach { imageStore.delete(it) }
            }
            stateCache.clear()
            clearConversation()
            _loadedModel.value?.let { load(it) }
            refreshChats()
            refreshStorage()
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
        override fun modelNames() = listOfNotNull(_loadedModel.value?.fileName?.removeSuffix(".gguf"))
        override suspend fun complete(request: ChatRequest, onToken: (String) -> Unit) = serveApiRequest(request, onToken)
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
            val system = request.messages.firstOrNull { it.role == "system" }?.content?.takeIf { it.isNotBlank() } ?: selectedPrompt
            val turns = request.messages.filter { it.role != "system" }
            val lastUser = turns.indexOfLast { it.role == "user" }
            val question = turns[lastUser].content
            if (question.isBlank()) throw ApiException(400, "The last user message is empty.")
            val earlier = Replay.build(turns.take(lastUser).map { Turn(it.role == "user", it.content) })
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
        KEY_THEME, KEY_CTX, KEY_THREADS, KEY_TEMP, KEY_TOP_P, KEY_TOP_K, KEY_MIN_P, KEY_REPEAT, KEY_SEED,
        KEY_STOP, KEY_PRESETS, KEY_PRESET_ID, KEY_PROMPTS, KEY_TOOLS, KEY_BG_REPLIES, KEY_READ_ALOUD, KEY_IDLE_MINUTES,
        KEY_MAX_REPLY, KEY_MODEL_SETTINGS, KEY_NOTES, KEY_SAVE_STATES,
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
                result.settings?.let { json ->
                    val editor = prefs.edit()
                    settingsApplied = SettingsBackup.apply(json, backupKeys) { key, value ->
                        when (value) {
                            is String -> editor.putString(key, value)
                            is Int -> editor.putInt(key, value)
                            is Float -> editor.putFloat(key, value)
                            is Boolean -> editor.putBoolean(key, value)
                        }
                    }
                    editor.apply()
                    reloadSettings()
                }
                refreshChats()
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

    /** Reads every backed-up setting from preferences again, after a restore changed them. */
    private fun reloadSettings() {
        _themeMode.value = ThemeMode.entries.firstOrNull { it.name == prefs.getString(KEY_THEME, null) } ?: ThemeMode.System
        _generationParams.value = GenerationParams(
            contextSize = prefs.getInt(KEY_CTX, DEFAULT_PARAMS.contextSize),
            threads = prefs.getInt(KEY_THREADS, DEFAULT_PARAMS.threads),
            temperature = prefs.getFloat(KEY_TEMP, DEFAULT_PARAMS.temperature),
            topP = prefs.getFloat(KEY_TOP_P, DEFAULT_PARAMS.topP),
            topK = prefs.getInt(KEY_TOP_K, DEFAULT_PARAMS.topK),
            minP = prefs.getFloat(KEY_MIN_P, DEFAULT_PARAMS.minP),
            repeatPenalty = prefs.getFloat(KEY_REPEAT, DEFAULT_PARAMS.repeatPenalty),
            seed = prefs.getInt(KEY_SEED, DEFAULT_PARAMS.seed),
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
        _modelSettings.value = ModelSettingsStore.fromJson(prefs.getString(KEY_MODEL_SETTINGS, null))
        _notes.value = MemoryNotes.fromJson(prefs.getString(KEY_NOTES, null))
        _saveStates.value = prefs.getBoolean(KEY_SAVE_STATES, true)
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

    fun speak(message: ChatMessage) {
        val voice = speaker ?: Speaker(
            getApplication(), onSpeaking = { _speakingId.value = it }, onProblem = { _error.value = it },
        ).also { speaker = it }
        voice.speak(message.id, Thinking.answerOnly(message.text))
    }

    fun stopSpeaking() {
        speaker?.stop()
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
    }

    fun deletePrompt(prompt: SavedPrompt) {
        if (prompt.builtIn) return
        _customPrompts.update { list -> list.filterNot { it.id == prompt.id } }
        prefs.edit().putString(KEY_PROMPTS, PromptLibrary.toJson(_customPrompts.value)).apply()
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
        get() = Tools.withInstructions(MemoryNotes.withNotes(selectedPrompt, _notes.value), _enabledTools.value)

    fun setChatPreset(id: String?) {
        _chatPresetId.value = id
        reloadKeepingChat()
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
    }

    fun deletePreset(preset: Preset) {
        if (preset.builtIn) return
        _customPresets.update { list -> list.filterNot { it.id == preset.id } }
        prefs.edit().putString(KEY_PRESETS, Presets.toJson(_customPresets.value)).apply()
        if (_selectedPresetId.value == preset.id) selectPreset(Presets.DEFAULT_ID)
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

    private val _pendingImage = MutableStateFlow<String?>(null)
    val pendingImage = _pendingImage.asStateFlow()

    private fun attachImage(uri: Uri) {
        if (!_visionReady.value) {
            _error.value = "This model can't read images. Load a vision model and pick its projector in Models."
            return
        }
        viewModelScope.launch {
            try {
                _pendingImage.value?.let { imageStore.delete(it) }
                _pendingImage.value = imageStore.import(uri)
            } catch (e: Exception) {
                _error.value = e.message ?: "Could not attach the image"
            }
        }
    }

    fun removePendingImage() {
        _pendingImage.value?.let { path -> viewModelScope.launch { imageStore.delete(path) } }
        _pendingImage.value = null
    }

    private val _modelInfo = MutableStateFlow<Map<String, ModelInfo>>(emptyMap())

    /** GGUF header details keyed by model path; filled in after the file list loads. */
    val modelInfo = _modelInfo.asStateFlow()

    fun refreshModels() {
        viewModelScope.launch {
            val list = store.list()
            _models.value = list
            _projectors.value = store.listProjectors()
            // A catalog vision model comes with its own projector: pair them automatically.
            ModelCatalog.models.forEach { c ->
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
                val results = hub.search(q)
                if (epoch == hubEpoch) _search.value = SearchState(query = q, searched = true, results = results)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _search.value = SearchState(query = q, searched = true)
                _error.value = "Search failed: ${e.message}"
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
            store.delete(model.fileName)
            refreshModels()
        }
    }

    // ---- Inference -----------------------------------------------------------

    /**
     * Loads [model] into the engine. With [resetChat] the conversation is cleared too; opening a
     * saved chat passes false so its messages stay on screen while the native context is rebuilt.
     */
    fun load(model: LocalModel, resetChat: Boolean = true, restore: Boolean = false) {
        if (_loading.value || _benchmarking.value || _apiBusy.value) return
        viewModelScope.launch {
            _loading.value = true
            try {
                if (resetChat) clearConversation()
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
        engine.loadModel(model.path, paramsFor(model))

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
        clearConversation()
    }

    /** Starts a fresh chat. The native context can only be reset by reloading the model. */
    fun newChat() {
        _loadedModel.value?.let { load(it) }
    }

    fun send(text: String) {
        val image = _pendingImage.value
        val prompt = text.trim().ifEmpty { if (image != null) "Describe this image." else "" }
        if (prompt.isEmpty() || _generating.value || _benchmarking.value || _apiBusy.value || _loadedModel.value == null) return

        stopSpeaking()
        if (currentChatId == null) currentChatId = UUID.randomUUID().toString()
        _pendingImage.value = null
        _messages.update { it + ChatMessage(prompt, true, imagePath = image) }
        touch()
        if (_sleeping.value || contextStale) {
            viewModelScope.launch { if (readyForMessage()) startGeneration(prompt, image) }
        } else {
            startGeneration(prompt, image)
        }
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
        val image = msgs[index].imagePath
        val prior = msgs.take(index)
        val discardedImages = msgs.drop(index + 1).mapNotNull { it.imagePath }

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
            _messages.value = prior + ChatMessage(prompt, true, imagePath = image)
            pendingContext = Replay.build(turns(prior))
            pendingVariants = versions
            startGeneration(prompt, image)
        }
    }

    /** The earlier versions of the reply that is about to be regenerated. */
    private var pendingVariants: OtherReplies? = null

    /** Streams a reply to [prompt], which must already be the last message in the list. */
    private fun startGeneration(prompt: String, imagePath: String? = null, continuing: Boolean = false) {
        _cutOffId.value = null
        val carried = pendingVariants
        pendingVariants = null
        // After reopening a saved chat the engine has no memory of it, so replay it once.
        val question = if (continuing) prompt else withDocumentContext(prompt)
        val replayed = pendingContext != null
        val enginePrompt = pendingContext?.let {
            "Here is our earlier conversation:\n$it\n\nContinue from there. My new message: $question"
        } ?: question
        pendingContext = null

        val firstTitle = if (continuing) "Request to continue" else "Your message" +
            (if (question != prompt) ", with excerpts from your documents" else "") +
            (if (replayed) ", with the earlier conversation replayed" else "")
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

            /** Streams one model turn into the reply bubble after [base]; returns what the model wrote. */
            suspend fun streamRound(prompt: String, image: String?, base: String, title: String): String {
                var written = ""
                var recorded = false
                val reply = if (image != null && _visionReady.value) {
                    engine.sendUserPromptWithImage(prompt, image, _maxReplyTokens.value)
                } else {
                    engine.sendUserPrompt(prompt, _maxReplyTokens.value)
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
                var image = imagePath
                var base = if (continuing) _messages.value.last().text else ""
                var toolRounds = 0
                while (true) {
                    val written = streamRound(prompt, image, base, if (toolRounds == 0) firstTitle else "Result of a tool the model used")
                    val call = if (tools.isEmpty()) null else Tools.parseCall(written)
                    if (call == null || toolRounds >= Tools.MAX_ROUNDS) break

                    val outcome = Tools.run(call, tools, searchServer = _searchServer.value)
                    val before = written.substringBefore(Tools.OPEN_TAG).trim()
                    base = listOf(base.trimEnd(), before, Tools.statusLine(call, outcome))
                        .filter { it.isNotEmpty() }.joinToString("\n\n") + "\n\n"
                    _messages.update { list -> list.dropLast(1) + list.last().copy(text = base) }
                    prompt = Tools.followUp(call, outcome)
                    image = null
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
                    if (_readAloud.value) _messages.value.lastOrNull()?.let(::speak)
                }
                saveCurrentChat()
                saveChatState()
                if (count > 0 && !appVisible && _backgroundReplies.value) {
                    val chatTitle = _chats.value.firstOrNull { it.id == currentChatId }?.title
                        ?: _messages.value.firstOrNull { it.isUser }?.text?.lineSequence()?.first()?.take(48).orEmpty()
                    ReplyNotifier.replyReady(getApplication(), chatTitle, Thinking.answerOnly(_messages.value.lastOrNull()?.text.orEmpty()))
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
            engine.draftStatus().takeIf { it.isNotEmpty() }?.let { append("\n").append(it) }
        }
    }

    // ---- Web pages as documents ------------------------------------------------------

    /** Fetches the page at [address] and attaches its text to the chat like a document. */
    fun attachWebPage(address: String) {
        if (_attachStatus.value != null) return
        val url = WebPage.normalise(address)
        if (url == null) {
            _error.value = "That doesn't look like a web address."
            return
        }
        viewModelScope.launch {
            _attachStatus.value = "Reading the page…"
            try {
                val page = withContext(Dispatchers.IO) { WebPage.fetch(url) }
                val name = page.title.replace(Regex("\\s+"), " ").take(48).ifBlank { "Web page" }
                val doc = docStore.saveText(name, "Source: $url\n\n${page.text}")
                _attachments.update { it + doc }
                rebuildDocIndex()
                if (_messages.value.isNotEmpty()) saveCurrentChat()
            } catch (e: Exception) {
                _error.value = e.message ?: "Could not read that page"
            } finally {
                _attachStatus.value = null
            }
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

    // ---- Document attachments (retrieval) -------------------------------------

    private val docStore = DocumentStore(app)
    private val _attachments = MutableStateFlow<List<Attachment>>(emptyList())
    val attachments = _attachments.asStateFlow()
    private var docIndex: Bm25Index? = null

    private val _attachStatus = MutableStateFlow<String?>(null)

    /** A short progress line while a file is being read (a PDF, or text recognised from pages), or null. */
    val attachStatus = _attachStatus.asStateFlow()

    fun attach(uri: Uri) {
        if (_attachStatus.value != null) return
        val isImage = getApplication<Application>().contentResolver.getType(uri)?.startsWith("image/") == true
        // A model that can see sends the photo itself; otherwise its text is read and attached as a document.
        if (isImage && _visionReady.value) {
            attachImage(uri)
            return
        }
        viewModelScope.launch {
            _attachStatus.value = if (isImage) "Reading text in the photo…" else "Reading the file…"
            try {
                val doc = if (isImage) {
                    val text = Ocr.recognize(getApplication(), uri).trim()
                    if (text.isEmpty()) throw IOException("No text was found in that photo, and the loaded model can't see images.")
                    docStore.saveText("Photo text.txt", text)
                } else {
                    docStore.import(uri) { _attachStatus.value = it }
                }
                _attachments.update { it + doc }
                rebuildDocIndex()
                if (_messages.value.isNotEmpty()) saveCurrentChat()
            } catch (e: Exception) {
                _error.value = e.message ?: "Could not attach the file"
            } finally {
                _attachStatus.value = null
            }
        }
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
        val docs = _attachments.value.mapNotNull { a -> docStore.read(a.id)?.let { a.name to it } }
        docIndex = if (docs.isEmpty()) null else withContext(Dispatchers.Default) { Bm25Index.build(docs) }
    }

    /** Wraps [question] with the most relevant excerpts from the attached documents, if any. */
    private fun withDocumentContext(question: String): String {
        val index = docIndex ?: return question
        val contextSize = engine.contextUsage().total.takeIf { it > 0 } ?: _generationParams.value.contextSize
        val budget = contextSize / 4 * 3 // ~3 chars per token, a quarter of the context
        val hits = index.search(question, budget)
        if (hits.isEmpty()) return question
        val excerpts = hits.joinToString("\n\n") { "[${it.docName}]\n${it.text}" }
        return "Answer using these excerpts from the user's attached documents. " +
            "If they don't contain the answer, say so.\n\nExcerpts:\n$excerpts\n\nQuestion: $question"
    }

    // ---- Chat history --------------------------------------------------------

    private val chatStore = ChatStore(app)
    private var currentChatId: String? = null
    private var pendingContext: String? = null

    private val _chats = MutableStateFlow<List<SavedChat>>(emptyList())
    val chats = _chats.asStateFlow()

    fun refreshChats() {
        viewModelScope.launch { _chats.value = chatStore.list() }
    }

    private fun clearConversation() {
        if (_messages.value.isEmpty()) {
            // Nothing was saved for this chat, so its attachments would be orphaned.
            _attachments.value.forEach { a -> viewModelScope.launch { docStore.delete(a.id) } }
        }
        removePendingImage()
        _cutOffId.value = null
        _chatPresetId.value = null
        _jsonMode.value = false
        _jsonSchema.value = null
        _promptRecord.value = null
        contextStale = false
        _attachments.value = emptyList()
        docIndex = null
        currentChatId = null
        pendingContext = null
        _messages.value = emptyList()
    }

    private fun stored(m: ChatMessage) =
        StoredMessage(m.text, m.isUser, m.stats, m.imagePath, m.summary, m.others, m.position)

    private fun saveCurrentChat() {
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
            jsonSchema = _jsonSchema.value,
            modelFile = _loadedModel.value?.fileName,
            messages = msgs.map(::stored),
            attachments = _attachments.value,
        )
        viewModelScope.launch {
            chatStore.save(chat)
            refreshChats()
        }
    }

    /** Reopens a saved chat, reloading its model (or the current one) to get a clean context. */
    fun openChat(chat: SavedChat) {
        if (_loading.value) return
        stopGeneration()
        currentChatId = chat.id
        _messages.value = chat.messages.map {
            ChatMessage(
                it.text, it.isUser, stats = it.stats, imagePath = it.imagePath,
                summary = it.summary, others = it.others, position = it.position,
            )
        }
        pendingContext = Replay.build(turns())
        _attachments.value = chat.attachments
        _chatPresetId.value = chat.presetId
        _jsonMode.value = chat.jsonMode
        _jsonSchema.value = chat.jsonSchema
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
            chatStore.save(chat.copy(title = name.take(80), customTitle = true))
            refreshChats()
        }
    }

    fun togglePin(chat: SavedChat) {
        viewModelScope.launch {
            chatStore.save(chat.copy(pinned = !chat.pinned))
            refreshChats()
        }
    }

    fun deleteChat(chat: SavedChat) {
        viewModelScope.launch {
            chatStore.delete(chat.id)
            stateCache.delete(chat.id)
            chat.attachments.forEach { docStore.delete(it.id) }
            chat.messages.mapNotNull { it.imagePath }.forEach { imageStore.delete(it) }
            if (currentChatId == chat.id) {
                clearConversation()
                _loadedModel.value?.let { load(it) }
            }
            refreshChats()
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
        refreshModels()
        refreshChats()
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
        ReplyGateway.stopRequested = null
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
        const val KEY_PRESETS = "custom_presets"
        const val KEY_PROJECTOR = "projector_for_"
        const val KEY_PRESET_ID = "selected_preset"
        const val KEY_CTX = "gen_context_size"
        const val KEY_THREADS = "gen_threads"
        const val KEY_TEMP = "gen_temperature"
        const val KEY_TOP_P = "gen_top_p"
        const val KEY_TOP_K = "gen_top_k"
        const val KEY_MIN_P = "gen_min_p"
        const val KEY_REPEAT = "gen_repeat_penalty"
        const val KEY_SEED = "gen_seed"
        const val KEY_STOP = "stop_words"
        const val KEY_PROMPTS = "saved_prompts"
        const val KEY_BENCH = "benchmarks"
        const val KEY_LOCK = "app_lock"
        const val KEY_READ_ALOUD = "read_aloud"
        const val KEY_API_ENABLED = "api_enabled"
        const val KEY_BG_REPLIES = "background_replies"
        const val KEY_TOOLS = "enabled_tools"
        const val KEY_IDLE_MINUTES = "idle_minutes"
        const val KEY_MAX_REPLY = "max_reply_tokens"
        const val KEY_MODEL_SETTINGS = "model_settings"
        const val KEY_NOTES = "memory_notes"
        const val KEY_SAVE_STATES = "save_chat_states"
        const val KEY_SEARCH_SERVER = "search_server"
        const val KEY_HUB = "hub_source"
        const val KEY_MIRROR = "hub_mirror_url"
        const val MAX_BENCH_RESULTS = 20
        const val IDLE_CHECK_MS = 30_000L
        const val MAX_INCOMING_CHARS = 8000
        val DEFAULT_PARAMS = GenerationParams()
        const val MAX_REPLAY_CHARS = 4000
    }
}
