package com.pocketlm.app

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.pocketlm.app.data.ModelStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Runs model downloads independently of any screen, so they carry on while the app is in the
 * background. While anything is downloading a foreground service shows a notification.
 */
class DownloadCenter private constructor(private val context: Context) {

    sealed interface Event {
        data class Completed(val fileName: String) : Event
        data class Failed(val fileName: String, val message: String) : Event
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store = ModelStore(context)
    private val jobs = ConcurrentHashMap<String, Job>()

    private val _downloads = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val downloads: StateFlow<Map<String, DownloadState>> = _downloads.asStateFlow()

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 16)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    fun start(url: String, fileName: String) {
        if (jobs[fileName]?.isActive == true) return
        _downloads.update { it + (fileName to DownloadState(null)) }
        // If the system refuses a foreground service the download still runs, just without the notification.
        runCatching { ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java)) }

        jobs[fileName] = scope.launch {
            try {
                store.download(url, fileName) { done, total ->
                    val progress = if (total > 0) done.toFloat() / total else null
                    _downloads.update { it + (fileName to DownloadState(progress)) }
                }
                _events.tryEmit(Event.Completed(fileName))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.tryEmit(Event.Failed(fileName, e.message ?: e::class.simpleName.orEmpty()))
            } finally {
                _downloads.update { it - fileName }
                jobs.remove(fileName)
            }
        }
    }

    fun cancel(fileName: String) {
        jobs[fileName]?.cancel()
    }

    fun cancelAll() {
        jobs.values.forEach { it.cancel() }
    }

    companion object {
        @Volatile
        private var instance: DownloadCenter? = null

        fun get(context: Context): DownloadCenter =
            instance ?: synchronized(this) {
                instance ?: DownloadCenter(context.applicationContext).also { instance = it }
            }
    }
}
