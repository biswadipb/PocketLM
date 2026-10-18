package com.pocketlm.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.pocketlm.app.data.ApiBackend
import com.pocketlm.app.data.ApiException
import com.pocketlm.app.data.ApiServer
import com.pocketlm.app.data.ChatRequest
import com.pocketlm.app.data.LocalNetwork
import com.pocketlm.app.data.TlsStore
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the API server is doing right now, shown in Settings. */
sealed interface ApiStatus {
    data object Stopped : ApiStatus
    data class Running(val port: Int, val https: Boolean = false) : ApiStatus
    data class Failed(val message: String) : ApiStatus
}

/** Connects the server (which lives in a service) to the app's chat engine (which lives in the view model). */
object ApiGateway {
    @Volatile
    var backend: ApiBackend? = null

    private val _status = MutableStateFlow<ApiStatus>(ApiStatus.Stopped)
    val status: StateFlow<ApiStatus> = _status.asStateFlow()

    internal fun set(status: ApiStatus) {
        _status.value = status
    }
}

/** Runs the OpenAI-compatible server in the foreground so it keeps working with the screen off. */
class ApiService : Service() {

    private var server: ApiServer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "API server", NotificationManager.IMPORTANCE_LOW))

        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val port = prefs.getInt(KEY_PORT, DEFAULT_PORT)
        val https = prefs.getBoolean(KEY_HTTPS, false)
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification(port, https), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )

        server?.stop()
        val tls = if (https) {
            try {
                TlsStore.serverContext(TlsStore(File(filesDir, TLS_DIR)).identity(LocalNetwork.addresses()))
            } catch (e: Exception) {
                ApiGateway.set(ApiStatus.Failed("Could not set up HTTPS: ${e.message}"))
                stopSelf()
                return START_NOT_STICKY
            }
        } else null
        server = ApiServer(
            port = port,
            apiKey = { getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_API_KEY, null) },
            backend = { ApiGateway.backend ?: NotReady },
            tls = tls,
        )
        try {
            server?.start()
            ApiGateway.set(ApiStatus.Running(port, https))
        } catch (e: Exception) {
            ApiGateway.set(ApiStatus.Failed("Could not start on port $port: ${e.message}"))
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun notification(port: Int, https: Boolean): android.app.Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(
            this, 2, Intent(this, ApiService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val address = LocalNetwork.addresses().firstOrNull()?.let { "${if (https) "https" else "http"}://$it:$port/v1" } ?: "port $port"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle("PocketLM API server is on")
            .setContentText(address)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .build()
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        ApiGateway.set(ApiStatus.Stopped)
        super.onDestroy()
    }

    /** Answers while the app has not registered a backend yet. */
    private object NotReady : ApiBackend {
        override fun modelNames() = emptyList<String>()
        override suspend fun complete(request: ChatRequest, onToken: (String) -> Unit): Int =
            throw ApiException(503, "PocketLM is not ready. Open the app and load a model.")
    }

    companion object {
        const val PREFS = "settings"
        const val KEY_PORT = "api_port"
        const val KEY_API_KEY = "api_key"
        const val KEY_HTTPS = "api_https"
        const val TLS_DIR = "tls"
        const val DEFAULT_PORT = 8080
        private const val ACTION_STOP = "com.pocketlm.app.STOP_API"
        private const val CHANNEL_ID = "api"
        private const val NOTIFICATION_ID = 2

        fun start(context: Context) {
            runCatching { androidx.core.content.ContextCompat.startForegroundService(context, Intent(context, ApiService::class.java)) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ApiService::class.java))
        }
    }
}
