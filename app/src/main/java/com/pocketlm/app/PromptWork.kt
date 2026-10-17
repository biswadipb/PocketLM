package com.pocketlm.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.pocketlm.app.data.Automation
import com.pocketlm.app.data.AppLog
import com.pocketlm.app.data.ChatStore
import com.pocketlm.app.data.PromptJob
import com.pocketlm.app.data.PromptQueue
import com.pocketlm.app.data.ReplyTarget
import com.pocketlm.app.data.SavedChat
import com.pocketlm.app.data.ScheduledPrompt
import com.pocketlm.app.data.Schedules
import com.pocketlm.app.data.SourceRef
import com.pocketlm.app.data.StoredMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.ZoneId
import java.util.UUID

/** The person's scheduled prompts, shared by the settings screen and the background job. */
class ScheduleStore private constructor(private val prefs: SharedPreferences) {
    private val _all = MutableStateFlow(Schedules.fromJson(prefs.getString(KEY, null)))
    val all: StateFlow<List<ScheduledPrompt>> = _all.asStateFlow()

    @Synchronized
    fun update(change: (List<ScheduledPrompt>) -> List<ScheduledPrompt>) {
        val next = change(_all.value).take(Schedules.MAX_SCHEDULES)
        _all.value = next
        prefs.edit().putString(KEY, Schedules.toJson(next)).apply()
    }

    /** Reads the list again, after a backup was restored. */
    @Synchronized
    fun reload() {
        _all.value = Schedules.fromJson(prefs.getString(KEY, null))
    }

    fun find(id: String): ScheduledPrompt? = _all.value.firstOrNull { it.id == id }

    fun markRan(id: String, at: Long, note: String) = update { list ->
        list.map { if (it.id == id) it.copy(lastRun = at, lastNote = note) else it }
    }

    companion object {
        const val KEY = "scheduled_prompts"

        @Volatile
        private var instance: ScheduleStore? = null

        fun get(context: Context): ScheduleStore = instance ?: synchronized(this) {
            instance ?: ScheduleStore(context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)).also { instance = it }
        }
    }
}

/** Asks Android to run [PromptJobService]: soon (for a prompt another app sent), or at the next scheduled time. */
object PromptScheduler {
    private const val JOB_NOW = 4801
    private const val JOB_TICK = 4802

    private fun component(context: Context) = ComponentName(context, PromptJobService::class.java)

    /** Runs the waiting prompts as soon as Android allows. */
    fun kick(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        // Urgent jobs exist from Android 12; Android 11 only has the ordinary kind below.
        if (Build.VERSION.SDK_INT >= 31) {
            val expedited = JobInfo.Builder(JOB_NOW, component(context)).setExpedited(true).build()
            if (scheduler.schedule(expedited) == JobScheduler.RESULT_SUCCESS) return
        }
        // The quota for urgent jobs may be used up; an ordinary job still runs soon.
        scheduler.schedule(JobInfo.Builder(JOB_NOW, component(context)).setRequiresBatteryNotLow(true).build())
    }

    /** Queues the job for the earliest scheduled prompt, or cancels it when none is switched on. */
    fun reschedule(context: Context, now: Long = System.currentTimeMillis()) {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        val next = Schedules.nextWanted(ScheduleStore.get(context).all.value, ZoneId.systemDefault())
        if (next == null) {
            scheduler.cancel(JOB_TICK)
            return
        }
        val wait = (next - now).coerceAtLeast(0)
        scheduler.schedule(
            JobInfo.Builder(JOB_TICK, component(context))
                .setMinimumLatency(wait)
                .setOverrideDeadline(wait + Schedules.WINDOW_MS)
                .setRequiresBatteryNotLow(true)
                .setPersisted(true)
                .build(),
        )
    }
}

/** Runs the waiting prompts one after another, and the scheduled ones whose time has come. */
class PromptWorker(private val context: Context) {

    private val store = ScheduleStore.get(context)
    private val queue = PromptQueue(File(context.filesDir, "automation"))
    private val runner = PromptRunner(context)

    suspend fun drain() = drainLock.withLock {
        val now = System.currentTimeMillis()
        for (schedule in Schedules.due(store.all.value, now, ZoneId.systemDefault())) {
            if (!queue.hasSchedule(schedule.id)) queue.add(jobFor(schedule, now))
        }
        while (true) {
            val job = queue.peek() ?: break
            val tries = queue.countAttempt(job.id)
            val schedule = job.scheduleId?.let(store::find)
            val result = if (tries > PromptQueue.MAX_ATTEMPTS) {
                PromptResult.Failed("It was interrupted ${PromptQueue.MAX_ATTEMPTS} times, so it was given up on.")
            } else {
                runner.run(job, lastRun = schedule?.lastRun ?: 0)
            }
            PromptDelivery.deliver(context, job, result)
            queue.remove(job.id)
            if (schedule != null) store.markRan(schedule.id, System.currentTimeMillis(), PromptDelivery.summary(result))
        }
        PromptScheduler.reschedule(context)
    }

    companion object {
        private val drainLock = Mutex()

        /** The prompt to run for [schedule]. */
        fun jobFor(schedule: ScheduledPrompt, now: Long) = PromptJob(
            id = UUID.randomUUID().toString(),
            kind = PromptJob.Kind.Scheduled,
            prompt = schedule.prompt,
            model = schedule.model,
            maxTokens = SCHEDULED_TOKENS,
            title = schedule.title,
            notify = true,
            saveChat = true,
            notes = schedule.notes,
            scheduleId = schedule.id,
            createdAt = now,
        )

        private const val SCHEDULED_TOKENS = 700
    }
}

/** Gets the answer of a prompt to where it should go: the app that asked, a notification, and History. */
object PromptDelivery {
    private const val CHANNEL = "prompts"

    fun summary(result: PromptResult): String = when (result) {
        is PromptResult.Done -> "Done"
        is PromptResult.Skipped -> result.message
        is PromptResult.Failed -> "Failed: ${result.message}"
    }

    fun deliver(context: Context, job: PromptJob, result: PromptResult) {
        job.target?.let { sendReply(context, it, result) }

        var chatId: String? = null
        if (result is PromptResult.Done && job.saveChat) {
            chatId = runCatching { saveChat(context, job, result) }.onFailure { AppLog.note("Could not save the chat: ${it.message}") }.getOrNull()
        }
        if (job.notify) notify(context, job, result, chatId)
    }

    /** The answer, or why there is none, sent only to the app that asked. */
    fun sendReply(context: Context, target: ReplyTarget, result: PromptResult) {
        val intent = Intent(target.action).setPackage(target.packageName)
        target.requestId?.let { intent.putExtra(Automation.EXTRA_REQUEST_ID, it) }
        when (result) {
            is PromptResult.Done -> {
                intent.putExtra(Automation.EXTRA_REPLY, result.text)
                result.model?.let { intent.putExtra(Automation.EXTRA_MODEL, it) }
            }
            is PromptResult.Skipped -> intent.putExtra(Automation.EXTRA_ERROR, result.message)
            is PromptResult.Failed -> intent.putExtra(Automation.EXTRA_ERROR, result.message)
        }
        runCatching { context.sendBroadcast(intent) }
    }

    /** For a request that was refused before it could run. */
    fun sendRefusal(context: Context, target: ReplyTarget, why: String) = sendReply(context, target, PromptResult.Failed(why))

    private fun saveChat(context: Context, job: PromptJob, done: PromptResult.Done): String {
        val id = UUID.randomUUID().toString()
        val label = if (job.kind == PromptJob.Kind.Scheduled) "scheduled" else "automation"
        ChatStore(context).saveNow(
            SavedChat(
                id = id,
                title = job.title.ifBlank { job.prompt.lineSequence().first().take(48) },
                updatedAt = System.currentTimeMillis(),
                modelFile = done.model?.let { "$it.gguf" },
                messages = listOf(
                    StoredMessage(job.prompt, true),
                    StoredMessage(done.text, false, sources = done.sources),
                ),
                customTitle = true,
                tags = listOf(label),
            ),
        )
        return id
    }

    private fun notify(context: Context, job: PromptJob, result: PromptResult, chatId: String?) {
        val allowed = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!allowed) return
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Scheduled prompts and automation", NotificationManager.IMPORTANCE_DEFAULT),
        )
        val text = when (result) {
            is PromptResult.Done -> result.text
            is PromptResult.Skipped -> result.message
            is PromptResult.Failed -> "Could not run: ${result.message}"
        }
        val id = NOTIFICATION_BASE + (job.id.hashCode() and 0xFFFF)
        val open = PendingIntent.getActivity(
            context, id,
            Intent(context, MainActivity::class.java).apply {
                if (chatId != null) {
                    action = IntentActions.OPEN_CHAT
                    putExtra(IntentActions.EXTRA_CHAT_ID, chatId)
                }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(job.title.ifBlank { "Piko" })
            .setContentText(ReplyNotifier.preview(text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(ReplyNotifier.preview(text, 600)))
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
    }

    private const val NOTIFICATION_BASE = 1000
}

/** Runs the waiting prompts while the app is closed, for as long as Android allows. */
class PromptJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var work: Job? = null

    @Volatile
    private var stopped = false

    override fun onStartJob(params: JobParameters): Boolean {
        stopped = false
        work = scope.launch {
            try {
                PromptWorker(applicationContext).drain()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.note("Background prompts: ${e.message ?: e::class.simpleName}")
            } finally {
                if (!stopped) jobFinished(params, false)
            }
        }
        return true
    }

    /** Android wants the job to stop; what is left in the queue is tried again later. */
    override fun onStopJob(params: JobParameters): Boolean {
        stopped = true
        work?.cancel()
        return PromptQueue(File(filesDir, "automation")).size() > 0
    }
}

/**
 * Other apps (Tasker, MacroDroid and the like) send a prompt to PocketLM and get the answer back. It only works when the
 * person switched it on in Settings, and only for senders that know the secret shown there; everything else is ignored.
 */
class AutomationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Automation.ACTION_ASK) return
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val extras = try {
            intent.extras
        } catch (e: RuntimeException) {
            null // a sender put something in the intent that cannot be read here
        } ?: return
        when (val parsed = Automation.parse(
            BundleExtras(extras), prefs.getBoolean(KEY_ENABLED, false), prefs.getString(KEY_TOKEN, null),
            System.currentTimeMillis(), { UUID.randomUUID().toString() },
        )) {
            is Automation.Parsed.Ignored -> AppLog.note("Automation request ignored: ${parsed.why}")
            is Automation.Parsed.Refused -> parsed.target?.let { PromptDelivery.sendRefusal(context, it, parsed.why) }
            is Automation.Parsed.Accepted -> {
                if (PromptQueue(File(context.filesDir, "automation")).add(parsed.job)) {
                    PromptScheduler.kick(context)
                } else {
                    parsed.job.target?.let { PromptDelivery.sendRefusal(context, it, "PocketLM already has ${PromptQueue.MAX_PENDING} prompts waiting.") }
                }
            }
        }
    }

    /** Tasker and friends often send numbers and yes/no values as text, so both forms are read. */
    private class BundleExtras(private val bundle: Bundle) : Automation.Extras {
        @Suppress("DEPRECATION")
        private fun raw(name: String): Any? = try {
            bundle.get(name)
        } catch (e: RuntimeException) {
            null
        }

        override fun string(name: String): String? = when (val v = raw(name)) {
            is String -> v
            is CharSequence -> v.toString()
            else -> null
        }

        override fun int(name: String): Int? = when (val v = raw(name)) {
            is Int -> v
            is Long -> v.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
            is String -> v.trim().toIntOrNull()
            else -> null
        }

        override fun bool(name: String): Boolean? = when (val v = raw(name)) {
            is Boolean -> v
            is String -> when (v.trim().lowercase()) {
                "true", "1", "yes" -> true
                "false", "0", "no" -> false
                else -> null
            }
            else -> null
        }
    }

    companion object {
        const val KEY_ENABLED = "automation_enabled"
        const val KEY_TOKEN = "automation_token"
    }
}
