package com.pocketlm.app

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import com.arm.aichat.isModelLoaded
import com.pocketlm.app.data.CatalogModel
import com.pocketlm.app.data.LocalModel
import com.pocketlm.app.data.ModelStore
import com.pocketlm.app.ui.ThemeMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class ChatMessage(
    val text: String,
    val isUser: Boolean,
    val id: String = UUID.randomUUID().toString(),
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

    private var generationJob: Job? = null
    private val downloadJobs = mutableMapOf<String, Job>()

    init {
        refreshModels()
    }

    fun dismissError() {
        _error.value = null
    }

    fun refreshModels() {
        viewModelScope.launch { _models.value = store.list() }
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

    fun load(model: LocalModel) {
        if (_loading.value) return
        viewModelScope.launch {
            _loading.value = true
            try {
                stopGeneration()
                if (engine.state.value.isModelLoaded) engine.cleanUp()
                awaitIdleEngine()
                engine.loadModel(model.path)
                engine.setSystemPrompt(SYSTEM_PROMPT)
                _loadedModel.value = model
                _messages.value = emptyList()
            } catch (e: Exception) {
                _loadedModel.value = null
                _error.value = "Could not load ${model.fileName}: ${e.message ?: e::class.simpleName}"
            } finally {
                _loading.value = false
            }
        }
    }

    fun unload() {
        stopGeneration()
        if (engine.state.value.isModelLoaded) engine.cleanUp()
        _loadedModel.value = null
        _messages.value = emptyList()
    }

    /** Clears the conversation. The native context can only be reset by reloading the model. */
    fun newChat() {
        _loadedModel.value?.let(::load)
    }

    fun send(text: String) {
        val prompt = text.trim()
        if (prompt.isEmpty() || _generating.value || _loadedModel.value == null) return

        _messages.update { it + ChatMessage(prompt, true) + ChatMessage("", false) }
        _generating.value = true
        generationJob = viewModelScope.launch {
            try {
                engine.sendUserPrompt(prompt).collect { token ->
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
            }
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

    override fun onCleared() {
        stopGeneration()
        super.onCleared()
    }

    private companion object {
        const val KEY_THEME = "theme_mode"
        const val SYSTEM_PROMPT = "You are PocketLM, a helpful assistant running entirely on the user's phone."
    }
}
