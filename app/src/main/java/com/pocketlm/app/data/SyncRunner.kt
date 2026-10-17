package com.pocketlm.app.data

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.pocketlm.app.SyncJobService
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

sealed interface SyncOutcome {
    data object NotConfigured : SyncOutcome
    /** An automatic sync that was left for later, for example because only Wi-Fi is allowed and there is none. */
    data class Skipped(val reason: String) : SyncOutcome
    /** Another sync is already running. */
    data object Busy : SyncOutcome
    data class Done(val report: SyncReport) : SyncOutcome
    data class Failed(val message: String, val passphraseChanged: Boolean = false) : SyncOutcome
}

/** What the open app wants to know while a sync runs, whether the app or the background job started it. */
interface SyncListener {
    fun onStarted() {}
    fun onProgress(text: String) {}
    fun onFinished(outcome: SyncOutcome) {}

    /** Settings were changed by a sync; screens should read them again. */
    fun onSettingsApplied() {}

    /** Chats that are open on screen and must not be replaced under the user. */
    fun openChatIds(): Set<String> = emptySet()
}

/**
 * Runs syncs for the whole app, one at a time, from the open app or from the background job. It owns everything a
 * sync needs, so a sync can happen with no screen at all.
 */
class SyncRunner private constructor(private val app: Context) {

    val config = SyncConfigStore(app)
    val state = SyncStateStore(File(app.filesDir, "sync_state.json"))

    @Volatile
    var listener: SyncListener? = null

    private val busy = AtomicBoolean(false)
    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val stateCache = ChatStateCache(app)

    val running: Boolean get() = busy.get()

    /** Syncs now. Blocks; call it off the main thread. [manual] syncs ignore the Wi-Fi-only choice. */
    fun run(manual: Boolean, cancelled: () -> Boolean = { false }): SyncOutcome {
        if (!config.configured) return SyncOutcome.NotConfigured
        if (!manual && config.wifiOnly && onMeteredOrOffline()) return SyncOutcome.Skipped("Waiting for an unmetered network")
        if (!busy.compareAndSet(false, true)) return SyncOutcome.Busy
        listener?.onStarted()
        val outcome = try {
            runLocked(cancelled)
        } finally {
            busy.set(false)
        }
        listener?.onFinished(outcome)
        return outcome
    }

    /**
     * Changes the passphrase of the sync location. [onStep] reports progress. Fails if the current passphrase is not
     * right, or if a sync is under way or the sync before the change did not succeed.
     */
    fun changePassphrase(current: String, new: String, repeat: String, cancelled: () -> Boolean = { false }): SyncOutcome {
        val connection = config.load() ?: return SyncOutcome.Failed("Sync needs to be set up again on this phone.")
        if (SyncCrypto.keyFor(connection.header, current.toCharArray()) == null) {
            return SyncOutcome.Failed("The current passphrase is not right.")
        }
        if (new.length < MIN_PASSPHRASE) return SyncOutcome.Failed("Choose a passphrase of at least $MIN_PASSPHRASE characters.")
        if (new != repeat) return SyncOutcome.Failed("The two new passphrases are not the same.")
        if (new == current) return SyncOutcome.Failed("The new passphrase is the same as the current one.")
        if (!busy.compareAndSet(false, true)) return SyncOutcome.Busy
        listener?.onStarted()
        val outcome: SyncOutcome = try {
            // Everything must be on this phone before the old files are removed.
            when (val before = runLocked(cancelled)) {
                is SyncOutcome.Done -> try {
                    listener?.onProgress("Locking everything with the new passphrase")
                    val backend = connection.target.open(app)
                    SyncRotation(File(app.filesDir, "chats"), backend, connection.key, state)
                        .rotate(new.toCharArray()) { header, key -> config.save(connection.target, header, key) }
                    config.recordResult(null, null)
                    runLocked(cancelled)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    fail("Could not change the passphrase: ${e.message ?: e::class.simpleName}")
                }
                is SyncOutcome.Failed -> SyncOutcome.Failed("Sync before the change did not work: ${before.message}")
                else -> before
            }
        } finally {
            busy.set(false)
        }
        listener?.onFinished(outcome)
        return outcome
    }

    private fun runLocked(cancelled: () -> Boolean): SyncOutcome {
        val connection = config.load() ?: return fail("Sync needs to be set up again on this phone.")
        val listener = listener
        val options = SyncOptions(
            settings = if (config.syncSettings) settingsHandle() else null,
            busyChatIds = listener?.openChatIds().orEmpty(),
            onChatChanged = { id -> stateCache.deleteNow(id) },
            onProgress = { text -> this.listener?.onProgress(text) },
            cancelled = cancelled,
        )
        return try {
            val files = app.filesDir
            val report = SyncEngine(
                File(files, "chats"), File(files, "images"), File(files, "docs"),
                connection.target.open(app), connection.header, connection.key, state,
            ).run(options)
            config.recordResult(report.summary(), null)
            report.warnings.forEach { AppLog.note("Sync: $it") }
            SyncOutcome.Done(report)
        } catch (e: CancellationException) {
            fail("Sync was stopped")
        } catch (e: SyncPassphraseChangedException) {
            fail(e.message!!, passphraseChanged = true)
        } catch (e: Exception) {
            fail(e.message ?: e::class.simpleName ?: "Unknown error")
        }
    }

    private fun fail(message: String, passphraseChanged: Boolean = false): SyncOutcome {
        config.recordResult(null, message, passphraseChanged)
        AppLog.note("Sync failed: $message")
        return SyncOutcome.Failed(message, passphraseChanged)
    }

    /** The synced settings, read from and written to the app's preferences. */
    private fun settingsHandle() = object : SyncSettings {
        override fun fingerprint() = SettingsSync.fingerprint(prefs.all, SettingKeys.SYNCED)

        override fun changedAt(): Long {
            val file = File(app.dataDir, "shared_prefs/settings.xml")
            return file.lastModified().takeIf { it > 0 } ?: System.currentTimeMillis()
        }

        override fun export() = SettingsBackup.toJson(prefs.all, SettingKeys.SYNCED).toString()

        override fun apply(json: String) {
            val editor = prefs.edit()
            SettingsBackup.apply(JSONObject(json), SettingKeys.SYNCED) { key, value ->
                when (value) {
                    is String -> editor.putString(key, value)
                    is Int -> editor.putInt(key, value)
                    is Float -> editor.putFloat(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                }
            }
            editor.apply()
            listener?.onSettingsApplied()
        }
    }

    private fun onMeteredOrOffline(): Boolean {
        val manager = app.getSystemService(ConnectivityManager::class.java)
        val caps = manager.getNetworkCapabilities(manager.activeNetwork) ?: return true
        return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    /** Makes the system's background job match the sync choices; leaves it alone if it already does. */
    fun updateBackgroundJob() {
        val scheduler = app.getSystemService(JobScheduler::class.java)
        val queued = scheduler.getPendingJob(JOB_ID)?.let {
            SyncJobSpec(it.intervalMillis, it.networkType == JobInfo.NETWORK_TYPE_UNMETERED)
        }
        val wanted = SyncSchedule.wanted(config.configured, config.autoSync, config.backgroundMinutes, config.wifiOnly)
        when (val change = SyncSchedule.change(queued, wanted)) {
            SyncSchedule.Change.None -> {}
            SyncSchedule.Change.Cancel -> scheduler.cancel(JOB_ID)
            is SyncSchedule.Change.Schedule -> scheduler.schedule(
                JobInfo.Builder(JOB_ID, ComponentName(app, SyncJobService::class.java))
                    .setPeriodic(change.spec.periodMs)
                    .setRequiredNetworkType(if (change.spec.unmetered) JobInfo.NETWORK_TYPE_UNMETERED else JobInfo.NETWORK_TYPE_ANY)
                    .setRequiresBatteryNotLow(true)
                    .setPersisted(true)
                    .build(),
            )
        }
    }

    companion object {
        const val MIN_PASSPHRASE = 8
        private const val JOB_ID = 4711

        @Volatile
        private var instance: SyncRunner? = null

        fun get(context: Context): SyncRunner =
            instance ?: synchronized(this) { instance ?: SyncRunner(context.applicationContext).also { instance = it } }
    }
}
