package com.pocketlm.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.pocketlm.app.data.SpeechText

/** Lets the notification's Stop button reach the chat that is writing the reply. */
object ReplyGateway {
    @Volatile
    var stopRequested: (() -> Unit)? = null

    /** Sends a message typed into the "reply ready" notification. Returns false if the chat can't take it right now. */
    @Volatile
    var onNotificationReply: ((String) -> Boolean)? = null
}

/** Receives what you type into the "reply ready" notification. */
class ReplyActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(ReplyNotifier.KEY_REPLY)?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        val accepted = ReplyGateway.onNotificationReply?.invoke(text) == true
        ReplyNotifier.afterReply(context, text, accepted)
    }
}

/**
 * Keeps the app running while a reply is being written, so it carries on when you switch apps or turn
 * the screen off. Shows a notification with a Stop button.
 */
class ReplyService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            ReplyGateway.stopRequested?.invoke()
            return START_NOT_STICKY
        }

        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_WORKING, "Writing a reply", NotificationManager.IMPORTANCE_LOW)
        )
        ServiceCompat.startForeground(this, NOTIFICATION_ID, workingNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)

        if (wakeLock == null) {
            // Without this the CPU can go to sleep with the screen off and the reply would stall.
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PocketLM:reply")
                .apply { acquire(MAX_RUN_MS) }
        }
        return START_NOT_STICKY
    }

    private fun workingNotification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(
            this, 3, Intent(this, ReplyService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_WORKING)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle("PocketLM is writing a reply")
            .setContentText("Tap to open the chat")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .build()
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    companion object {
        private const val ACTION_STOP = "com.pocketlm.app.STOP_REPLY"
        private const val CHANNEL_WORKING = "reply-working"
        private const val NOTIFICATION_ID = 3
        private const val MAX_RUN_MS = 30 * 60 * 1000L

        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, ReplyService::class.java)) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ReplyService::class.java))
        }
    }
}

/** Tells you a reply is ready when the app is not on screen. */
object ReplyNotifier {
    private const val CHANNEL_DONE = "reply-done"
    private const val NOTIFICATION_ID = 4

    /** A short plain-text preview of a reply for the notification. */
    fun preview(markdown: String, maxChars: Int = 140): String {
        val plain = SpeechText.clean(markdown).replace(Regex("""\s+"""), " ").trim()
        return if (plain.length <= maxChars) plain else plain.take(maxChars - 1).trimEnd() + "…"
    }

    const val KEY_REPLY = "reply_text"

    private fun replyAction(context: Context): NotificationCompat.Action {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val send = PendingIntent.getBroadcast(context, 5, Intent(context, ReplyActionReceiver::class.java), flags)
        return NotificationCompat.Action.Builder(0, "Reply", send)
            .addRemoteInput(RemoteInput.Builder(KEY_REPLY).setLabel("Reply to PocketLM").build())
            .setAllowGeneratedReplies(false)
            .build()
    }

    /**
     * Updates the notification after a reply was typed into it. If the chat took it, the notification says the answer
     * is being written; if not (the app is closed or locked), tapping it opens PocketLM with the text in the box.
     */
    fun afterReply(context: Context, text: String, accepted: Boolean) {
        val allowed = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!allowed) return
        val open = PendingIntent.getActivity(
            context, 6,
            Intent(context, MainActivity::class.java).setAction(IntentActions.ASK).putExtra(Intent.EXTRA_TEXT, text),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(if (accepted) "PocketLM is writing the reply" else "Open PocketLM to send your reply")
            .setContentText(preview(text))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
        if (!accepted) builder.setContentIntent(open)
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build()) }
    }

    fun replyReady(context: Context, chatTitle: String, reply: String) {
        val allowed = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!allowed) return

        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_DONE, "Replies", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(chatTitle.ifBlank { "PocketLM replied" })
            .setContentText(preview(reply))
            .setStyle(NotificationCompat.BigTextStyle().bigText(preview(reply, 400)))
            .setAutoCancel(true)
            .setContentIntent(open)
            .addAction(replyAction(context))
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification) }
    }
}
