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
import com.pocketlm.app.data.Attachment
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
import com.pocketlm.app.data.StopWords
import com.pocketlm.app.data.StorageManager
import com.pocketlm.app.data.StorageReport
import com.pocketlm.app.data.StoredMessage
import com.pocketlm.app.data.LocalModel
import com.pocketlm.app.data.ImageStore
import com.pocketlm.app.data.JsonGrammar
import com.pocketlm.app.data.ModelCatalog
import com.pocketlm.app.data.ModelHub
import com.pocketlm.app.data.ModelInfo
import com.pocketlm.app.data.ModelStore
import com.pocketlm.app.data.Preset
import com.pocketlm.app.data.Presets
import com.pocketlm.app.data.PromptLibrary
import com.pocketlm.app.data.SavedPrompt
import com.pocketlm.app.data.Speaker
import com.pocketlm.app.ui.ThemeMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
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
                            loadIntoEngine(previous)
                            pendingContext = replayText(_messages.value.map { it.isUser to it.text })
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
        try {
            val system = request.messages.firstOrNull { it.role == "system" }?.content?.takeIf { it.isNotBlank() } ?: selectedPrompt
            val turns = request.messages.filter { it.role != "system" }
            val lastUser = turns.indexOfLast { it.role == "user" }
            val question = turns[lastUser].content
            if (question.isBlank()) throw ApiException(400, "The last user message is empty.")
            val earlier = replayText(turns.take(lastUser).map { (it.role == "user") to it.content })
            val prompt = if (earlier == null) question
            else "Here is our earlier conversation:\n$earlier\n\nContinue from there. My new message: $question"

            if (_jsonMode.value) engine.setOutputGrammar("")
            engine.resetConversation(system)
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
                    engine.resetConversation(selectedPrompt)
                    if (_jsonMode.value) engine.setOutputGrammar(JsonGrammar.gbnf)
                    pendingContext = replayText(_messages.value.map { it.isUser to it.text })
                } catch (e: Exception) {
                    _error.value = "Could not restore the chat after an API request: ${e.message}"
                }
                _apiBusy.value = false
                apiMutex.unlock()
            }
        }
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
        voice.speak(message.id, message.text)
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
        get() = presets.value.firstOrNull { it.id == (_chatPresetId.value ?: _selectedPresetId.value) }?.prompt
            ?: Presets.builtIn.first().prompt

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
        if (_loadedModel.value == null || _generating.value || _loading.value) return
        viewModelScope.launch {
            try {
                engine.setOutputGrammar(if (on) JsonGrammar.gbnf else "")
            } catch (e: Exception) {
                _error.value = "Could not change JSON mode: ${e.message}"
            }
        }
    }

    /** Reloads the engine (for a new model or prompt) and replays the chat on the next message. */
    private fun reloadKeepingChat(model: LocalModel? = _loadedModel.value) {
        if (model == null || _generating.value || _loading.value || _benchmarking.value || _apiBusy.value) return
        pendingContext = replayText(_messages.value.map { it.isUser to it.text })
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
    fun load(model: LocalModel, resetChat: Boolean = true) {
        if (_loading.value || _benchmarking.value || _apiBusy.value) return
        viewModelScope.launch {
            _loading.value = true
            try {
                if (resetChat) clearConversation()
                loadIntoEngine(model)
            } catch (e: Exception) {
                _loadedModel.value = null
                _error.value = "Could not load ${model.fileName}: ${e.message ?: e::class.simpleName}"
            } finally {
                _loading.value = false
            }
        }
    }

    private suspend fun loadIntoEngine(model: LocalModel, applyJson: Boolean = true) {
        stopGeneration()
        if (engine.state.value.isModelLoaded) engine.cleanUp()
        awaitIdleEngine()
        engine.loadModel(model.path, _generationParams.value)
        engine.setSystemPrompt(selectedPrompt)
        _loadedModel.value = model

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
        if (applyJson && _jsonMode.value) engine.setOutputGrammar(JsonGrammar.gbnf)
    }

    fun unload() {
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
        startGeneration(prompt, image)
    }

    /** Re-asks the last user message. The engine can't undo a reply, so its context is rebuilt first. */
    fun regenerate() {
        val msgs = _messages.value
        if (msgs.size < 2 || msgs.last().isUser || !msgs[msgs.lastIndex - 1].isUser) return
        rerunFrom(msgs.lastIndex - 1, msgs[msgs.lastIndex - 1].text)
    }

    /** Replaces one of your messages with [newText] and answers again, dropping everything after it. */
    fun editMessage(id: String, newText: String) {
        val text = newText.trim()
        val index = _messages.value.indexOfFirst { it.id == id && it.isUser }
        if (index < 0 || text.isEmpty()) return
        rerunFrom(index, text)
    }

    /** Rebuilds the engine context from the messages before [index], then answers [prompt] again. */
    private fun rerunFrom(index: Int, prompt: String) {
        if (_generating.value || _loading.value) return
        val model = _loadedModel.value ?: return
        val msgs = _messages.value
        val image = msgs[index].imagePath
        val prior = msgs.take(index)
        val discardedImages = msgs.drop(index + 1).mapNotNull { it.imagePath }

        viewModelScope.launch {
            _loading.value = true
            try {
                loadIntoEngine(model)
            } catch (e: Exception) {
                _loadedModel.value = null
                _error.value = "Could not reload ${model.fileName}: ${e.message ?: e::class.simpleName}"
                return@launch
            } finally {
                _loading.value = false
            }
            discardedImages.forEach { imageStore.delete(it) }
            _messages.value = prior + ChatMessage(prompt, true, imagePath = image)
            pendingContext = replayText(prior.map { it.isUser to it.text })
            startGeneration(prompt, image)
        }
    }

    private fun replayText(items: List<Pair<Boolean, String>>): String? =
        items.takeIf { it.isNotEmpty() }
            ?.joinToString("\n") { (isUser, text) -> (if (isUser) "User: " else "Assistant: ") + text }
            ?.takeLast(MAX_REPLAY_CHARS)

    /** Streams a reply to [prompt], which must already be the last message in the list. */
    private fun startGeneration(prompt: String, imagePath: String? = null) {
        // After reopening a saved chat the engine has no memory of it, so replay it once.
        val question = withDocumentContext(prompt)
        val enginePrompt = pendingContext?.let {
            "Here is our earlier conversation:\n$it\n\nContinue from there. My new message: $question"
        } ?: question
        pendingContext = null

        _messages.update { it + ChatMessage("", false) }
        _generating.value = true
        val stops = StopWords.parse(_stopWords.value)
        generationJob = viewModelScope.launch {
            val start = SystemClock.elapsedRealtime()
            var firstAt = 0L
            var count = 0
            try {
                val reply = if (imagePath != null && _visionReady.value) {
                    engine.sendUserPromptWithImage(enginePrompt, imagePath)
                } else {
                    engine.sendUserPrompt(enginePrompt)
                }
                reply.collect { token ->
                    if (count == 0) firstAt = SystemClock.elapsedRealtime()
                    count++
                    var hitStopWord = false
                    _messages.update { list ->
                        val text = list.last().text + token
                        val cut = StopWords.cutIndex(text, stops)
                        hitStopWord = cut >= 0
                        val shown = if (cut >= 0) text.substring(0, cut).trimEnd() else text
                        list.dropLast(1) + list.last().copy(text = shown)
                    }
                    if (hitStopWord) throw StopWordHit()
                }
            } catch (e: StopWordHit) {
                // A stop word ended the reply: that is a normal finish.
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = "Generation failed: ${e.message ?: e::class.simpleName}"
            } finally {
                _generating.value = false
                if (count == 0) {
                    // Nothing was written (an error, or stopped before the first word): drop the empty bubble.
                    _messages.update { list ->
                        if (list.lastOrNull()?.let { !it.isUser && it.text.isEmpty() } == true) list.dropLast(1) else list
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
            }
        }
    }

    // ---- Document attachments (retrieval) -------------------------------------

    private val docStore = DocumentStore(app)
    private val _attachments = MutableStateFlow<List<Attachment>>(emptyList())
    val attachments = _attachments.asStateFlow()
    private var docIndex: Bm25Index? = null

    fun attach(uri: Uri) {
        if (getApplication<Application>().contentResolver.getType(uri)?.startsWith("image/") == true) {
            attachImage(uri)
            return
        }
        viewModelScope.launch {
            try {
                val doc = docStore.import(uri)
                _attachments.update { it + doc }
                rebuildDocIndex()
                if (_messages.value.isNotEmpty()) saveCurrentChat()
            } catch (e: Exception) {
                _error.value = e.message ?: "Could not attach the file"
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
        val budget = _generationParams.value.contextSize / 4 * 3 // ~3 chars per token, a quarter of the context
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
        _chatPresetId.value = null
        _jsonMode.value = false
        _attachments.value = emptyList()
        docIndex = null
        currentChatId = null
        pendingContext = null
        _messages.value = emptyList()
    }

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
            modelFile = _loadedModel.value?.fileName,
            messages = msgs.map { StoredMessage(it.text, it.isUser, it.stats, it.imagePath) },
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
        _messages.value = chat.messages.map { ChatMessage(it.text, it.isUser, stats = it.stats, imagePath = it.imagePath) }
        pendingContext = replayText(chat.messages.map { it.isUser to it.text })
        _attachments.value = chat.attachments
        _chatPresetId.value = chat.presetId
        _jsonMode.value = chat.jsonMode
        viewModelScope.launch { rebuildDocIndex() }

        val model = _models.value.firstOrNull { it.fileName == chat.modelFile } ?: _loadedModel.value
        if (model != null) {
            load(model, resetChat = false)
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
            msgs.map { StoredMessage(it.text, it.isUser, it.stats, it.imagePath) },
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
    }

    init {
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
        const val KEY_HUB = "hub_source"
        const val KEY_MIRROR = "hub_mirror_url"
        const val MAX_BENCH_RESULTS = 20
        const val MAX_INCOMING_CHARS = 8000
        val DEFAULT_PARAMS = GenerationParams()
        const val MAX_REPLAY_CHARS = 4000
    }
}
