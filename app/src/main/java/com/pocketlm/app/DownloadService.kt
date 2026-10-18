package com.pocketlm.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Keeps the app alive and shows a progress notification while models download. */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val center = DownloadCenter.get(this)
        if (intent?.action == ACTION_CANCEL_ALL) center.cancelAll()

        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Model downloads", NotificationManager.IMPORTANCE_LOW)
        )
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification(center.downloads.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )

        if (watcher == null) {
            watcher = scope.launch {
                center.downloads.collect { downloads ->
                    if (downloads.isEmpty()) stopSelf() else manager.notify(NOTIFICATION_ID, notification(downloads))
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun notification(downloads: Map<String, DownloadState>) = run {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val cancel = PendingIntent.getService(
            this, 1,
            Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL_ALL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val known = downloads.values.mapNotNull { it.progress }
        val percent = if (known.isEmpty()) 0 else (known.average() * 100).toInt()
        val title = if (downloads.size == 1) "Downloading ${downloads.keys.first().removeSuffix(".gguf")}"
        else "Downloading ${downloads.size} files"

        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(title)
            .setContentText(if (known.isEmpty()) "Starting…" else "$percent%")
            .setProgress(100, percent, known.isEmpty())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Cancel", cancel)
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val CHANNEL_ID = "downloads"
        const val NOTIFICATION_ID = 1
        const val ACTION_CANCEL_ALL = "com.pocketlm.app.CANCEL_DOWNLOADS"
    }
}
