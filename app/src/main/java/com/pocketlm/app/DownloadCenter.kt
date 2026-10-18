package com.pocketlm.app

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
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
import kotlin.coroutines.coroutineContext

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

    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private var watching: ConnectivityManager.NetworkCallback? = null

    /** True when the user asked for downloads on Wi-Fi only (or any other connection that is not billed by use). */
    private val wifiOnly get() = prefs.getBoolean(KEY_WIFI_ONLY, false)

    /** Stops the downloads in progress, keeping what has arrived, when the connection turns into mobile data. */
    private fun watchConnection() {
        if (watching != null) return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                if (wifiOnly && !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) pauseForMobileData()
            }
        }
        watching = callback
        runCatching { connectivity.registerDefaultNetworkCallback(callback) }
    }

    private fun stopWatching() {
        watching?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        watching = null
    }

    private fun pauseForMobileData() {
        for ((fileName, job) in jobs.entries.toList()) {
            if (!job.isActive) continue
            job.cancel()
            _events.tryEmit(Event.Failed(fileName, PAUSED_FOR_MOBILE_DATA))
        }
    }

    fun start(url: String, fileName: String) {
        val previous = jobs[fileName]
        if (previous?.isActive == true) return
        if (wifiOnly && connectivity.isActiveNetworkMetered) {
            _events.tryEmit(Event.Failed(fileName, NEEDS_WIFI))
            return
        }
        watchConnection()
        _downloads.update { it + (fileName to DownloadState(null)) }
        // If the system refuses a foreground service the download still runs, just without the notification.
        runCatching { ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java)) }

        jobs[fileName] = scope.launch {
            val self = coroutineContext[Job]
            // A cancelled download may still be unwinding; wait so two jobs never write the same file.
            previous?.join()
            try {
                store.download(url, fileName) { done, total ->
                    if (jobs[fileName] === self) {
                        val progress = if (total > 0) done.toFloat() / total else null
                        _downloads.update { it + (fileName to DownloadState(progress)) }
                    }
                }
                _events.tryEmit(Event.Completed(fileName))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.tryEmit(Event.Failed(fileName, e.message ?: e::class.simpleName.orEmpty()))
            } finally {
                // Only clear the state if a newer download of this file has not taken over.
                if (jobs.remove(fileName, self)) _downloads.update { it - fileName }
                if (jobs.values.none { it.isActive && it !== self }) stopWatching()
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
        const val KEY_WIFI_ONLY = "downloads_wifi_only"
        const val NEEDS_WIFI = "Downloads are set to Wi-Fi only and this connection is mobile data. Connect to Wi-Fi, or turn that setting off."
        const val PAUSED_FOR_MOBILE_DATA = "Paused because the connection changed to mobile data. Start the download again on Wi-Fi to carry on where it stopped."

        @Volatile
        private var instance: DownloadCenter? = null

        fun get(context: Context): DownloadCenter =
            instance ?: synchronized(this) {
                instance ?: DownloadCenter(context.applicationContext).also { instance = it }
            }
    }
}
