package com.pocketlm.app

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.arm.aichat.AiChat
import com.arm.aichat.GenerationParams
import com.arm.aichat.InferenceEngine
import com.arm.aichat.isModelLoaded
import com.pocketlm.app.data.Attachment
import com.pocketlm.app.data.Bm25Index
import com.pocketlm.app.data.CatalogModel
import com.pocketlm.app.data.ChatStore
import com.pocketlm.app.data.DocumentStore
import com.pocketlm.app.data.GenStats
import com.pocketlm.app.data.HfClient
import com.pocketlm.app.data.HfFile
import com.pocketlm.app.data.HfModel
import com.pocketlm.app.data.SavedChat
import com.pocketlm.app.data.StoredMessage
import com.pocketlm.app.data.LocalModel
import com.pocketlm.app.data.ModelInfo
import com.pocketlm.app.data.ModelStore
import com.pocketlm.app.data.Preset
import com.pocketlm.app.data.Presets
import com.pocketlm.app.ui.ThemeMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

data class ChatMessage(
    val text: String,
    val isUser: Boolean,
    val id: String = UUID.randomUUID().toString(),
    val stats: GenStats? = null,
)

data class SearchState(
    val query: String = "",
    val loading: Boolean = false,
    val searched: Boolean = false,
    val results: List<HfModel> = emptyList(),
)

/** Files of one repository: null [files] while still loading. */
data class RepoFiles(val files: List<HfFile>? = null, val error: String? = null)

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

    private val _downloads = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val downloads = _downloads.asStateFlow()

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
            .apply()
    }

    private val _customPresets = MutableStateFlow(Presets.fromJson(prefs.getString(KEY_PRESETS, null)))
    private val _selectedPresetId = MutableStateFlow(prefs.getString(KEY_PRESET_ID, null) ?: Presets.DEFAULT_ID)
    val selectedPresetId = _selectedPresetId.asStateFlow()

    /** Built-in presets followed by the user's own. */
    val presets: StateFlow<List<Preset>> =
        _customPresets.map { Presets.builtIn + it }
            .stateIn(viewModelScope, SharingStarted.Eagerly, Presets.builtIn)

    private val selectedPrompt: String
        get() = presets.value.firstOrNull { it.id == _selectedPresetId.value }?.prompt
            ?: Presets.builtIn.first().prompt

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
    private val downloadJobs = mutableMapOf<String, Job>()

    fun dismissError() {
        _error.value = null
    }

    private val _modelInfo = MutableStateFlow<Map<String, ModelInfo>>(emptyMap())

    /** GGUF header details keyed by model path; filled in after the file list loads. */
    val modelInfo = _modelInfo.asStateFlow()

    fun refreshModels() {
        viewModelScope.launch {
            val list = store.list()
            _models.value = list
            list.filter { it.path !in _modelInfo.value }.forEach { m ->
                ModelInfo.read(m.path)?.let { info -> _modelInfo.update { it + (m.path to info) } }
            }
        }
    }

    // ---- Model management -------------------------------------------------

    fun download(model: CatalogModel) = startDownload(model.url, model.fileName)

    fun downloadFromUrl(url: String) {
        val name = url.substringBefore('?').substringAfterLast('/')
        if (!name.endsWith(".gguf")) {
            _error.value = "The link must point to a .gguf file"
            return
        }
        startDownload(url, name)
    }

    private fun startDownload(url: String, fileName: String) {
        if (downloadJobs[fileName]?.isActive == true) return
        _downloads.update { it + (fileName to DownloadState(null)) }
        downloadJobs[fileName] = viewModelScope.launch {
            try {
                store.download(url, fileName) { done, total ->
                    val p = if (total > 0) done.toFloat() / total else null
                    _downloads.update { it + (fileName to DownloadState(p)) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = "Download failed: ${e.message}"
            } finally {
                _downloads.update { it - fileName }
                refreshModels()
            }
        }
    }

    // ---- Hugging Face search ----------------------------------------------------

    private val _search = MutableStateFlow(SearchState())
    val search = _search.asStateFlow()

    private val _repoFiles = MutableStateFlow<Map<String, RepoFiles>>(emptyMap())
    val repoFiles = _repoFiles.asStateFlow()

    private var searchJob: Job? = null

    fun searchHub(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        searchJob?.cancel()
        _search.value = SearchState(query = q, loading = true)
        searchJob = viewModelScope.launch {
            try {
                val results = HfClient.search(q)
                _search.value = SearchState(query = q, searched = true, results = results)
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
        _repoFiles.update { it + (repoId to RepoFiles()) }
        viewModelScope.launch {
            try {
                val files = HfClient.files(repoId)
                _repoFiles.update { it + (repoId to RepoFiles(files = files)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _repoFiles.update { it + (repoId to RepoFiles(error = e.message ?: "Could not load files")) }
            }
        }
    }

    fun downloadFromHub(repoId: String, file: HfFile) =
        startDownload(HfClient.downloadUrl(repoId, file.path), file.fileName)

    fun cancelDownload(fileName: String) {
        downloadJobs[fileName]?.cancel()
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
        if (_loading.value) return
        viewModelScope.launch {
            _loading.value = true
            try {
                loadIntoEngine(model)
                if (resetChat) clearConversation()
            } catch (e: Exception) {
                _loadedModel.value = null
                _error.value = "Could not load ${model.fileName}: ${e.message ?: e::class.simpleName}"
            } finally {
                _loading.value = false
            }
        }
    }

    private suspend fun loadIntoEngine(model: LocalModel) {
        stopGeneration()
        if (engine.state.value.isModelLoaded) engine.cleanUp()
        awaitIdleEngine()
        engine.loadModel(model.path, _generationParams.value)
        engine.setSystemPrompt(selectedPrompt)
        _loadedModel.value = model
    }

    fun unload() {
        stopGeneration()
        if (engine.state.value.isModelLoaded) engine.cleanUp()
        _loadedModel.value = null
        clearConversation()
    }

    /** Starts a fresh chat. The native context can only be reset by reloading the model. */
    fun newChat() {
        _loadedModel.value?.let { load(it) }
    }

    fun send(text: String) {
        val prompt = text.trim()
        if (prompt.isEmpty() || _generating.value || _loadedModel.value == null) return

        if (currentChatId == null) currentChatId = UUID.randomUUID().toString()
        _messages.update { it + ChatMessage(prompt, true) }
        startGeneration(prompt)
    }

    /** Re-asks the last user message. The engine can't undo a reply, so its context is rebuilt first. */
    fun regenerate() {
        if (_generating.value || _loading.value) return
        val model = _loadedModel.value ?: return
        val msgs = _messages.value
        if (msgs.size < 2 || msgs.last().isUser || !msgs[msgs.lastIndex - 1].isUser) return
        val prompt = msgs[msgs.lastIndex - 1].text
        val prior = msgs.dropLast(2)

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
            _messages.value = prior + ChatMessage(prompt, true)
            pendingContext = replayText(prior.map { it.isUser to it.text })
            startGeneration(prompt)
        }
    }

    private fun replayText(items: List<Pair<Boolean, String>>): String? =
        items.takeIf { it.isNotEmpty() }
            ?.joinToString("\n") { (isUser, text) -> (if (isUser) "User: " else "Assistant: ") + text }
            ?.takeLast(MAX_REPLAY_CHARS)

    /** Streams a reply to [prompt], which must already be the last message in the list. */
    private fun startGeneration(prompt: String) {
        // After reopening a saved chat the engine has no memory of it, so replay it once.
        val question = withDocumentContext(prompt)
        val enginePrompt = pendingContext?.let {
            "Here is our earlier conversation:\n$it\n\nContinue from there. My new message: $question"
        } ?: question
        pendingContext = null

        _messages.update { it + ChatMessage("", false) }
        _generating.value = true
        generationJob = viewModelScope.launch {
            val start = SystemClock.elapsedRealtime()
            var firstAt = 0L
            var count = 0
            try {
                engine.sendUserPrompt(enginePrompt).collect { token ->
                    if (count == 0) firstAt = SystemClock.elapsedRealtime()
                    count++
                    _messages.update { list ->
                        list.dropLast(1) + list.last().copy(text = list.last().text + token)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = "Generation failed: ${e.message ?: e::class.simpleName}"
            } finally {
                _generating.value = false
                if (count > 0) {
                    val genMs = (SystemClock.elapsedRealtime() - firstAt).coerceAtLeast(1)
                    val stats = GenStats(
                        tokens = count,
                        tokensPerSecond = if (count > 1) (count - 1) * 1000f / genMs else null,
                        firstTokenMs = firstAt - start,
                    )
                    _messages.update { list -> list.dropLast(1) + list.last().copy(stats = stats) }
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
        val chat = SavedChat(
            id = id,
            title = firstUser.lineSequence().first().take(48),
            updatedAt = System.currentTimeMillis(),
            modelFile = _loadedModel.value?.fileName,
            messages = msgs.map { StoredMessage(it.text, it.isUser, it.stats) },
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
        _messages.value = chat.messages.map { ChatMessage(it.text, it.isUser, stats = it.stats) }
        pendingContext = replayText(chat.messages.map { it.isUser to it.text })
        _attachments.value = chat.attachments
        viewModelScope.launch { rebuildDocIndex() }

        val model = _models.value.firstOrNull { it.fileName == chat.modelFile } ?: _loadedModel.value
        if (model != null) {
            load(model, resetChat = false)
        } else {
            _error.value = "Load a model to continue this chat"
        }
    }

    fun deleteChat(chat: SavedChat) {
        viewModelScope.launch {
            chatStore.delete(chat.id)
            chat.attachments.forEach { docStore.delete(it.id) }
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

    override fun onCleared() {
        stopGeneration()
        super.onCleared()
    }

    private companion object {
        const val KEY_THEME = "theme_mode"
        const val KEY_PRESETS = "custom_presets"
        const val KEY_PRESET_ID = "selected_preset"
        const val KEY_CTX = "gen_context_size"
        const val KEY_THREADS = "gen_threads"
        const val KEY_TEMP = "gen_temperature"
        const val KEY_TOP_P = "gen_top_p"
        val DEFAULT_PARAMS = GenerationParams()
        const val MAX_REPLAY_CHARS = 4000
    }
}
