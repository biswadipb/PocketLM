package com.pocketlm.app.data

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit

/** One earlier end of the app, as Android reports it. */
data class ExitRecord(val timeMs: Long, val reason: Int, val description: String?, val rssKb: Long)

/** Picks and formats lines for the log viewer. Pure so it can be tested. */
object LogFormat {

    private val ourTags = setOf("ai-chat", "InferenceEngineImpl", "PocketLM", "AiChat", "PocketViewModel", "DownloadCenter", "ApiServer")

    /** logcat's threadtime format: "MM-DD HH:MM:SS.mmm  PID  TID L TAG: message". Returns the level letter and tag. */
    private val line = Regex("""^\d\d-\d\d \d\d:\d\d:\d\d\.\d+\s+\d+\s+\d+\s+([VDIWEF])\s+(.+?)\s*:\s""")

    /** Warnings and errors from anything, and everything from PocketLM's own parts; chatter from the system is left out. */
    fun keep(logLine: String): Boolean {
        val m = line.find(logLine) ?: return false
        val level = m.groupValues[1]
        val tag = m.groupValues[2]
        return level in "WEF" || (level in "ID" && tag in ourTags)
    }

    fun filter(lines: List<String>, max: Int = 600): List<String> = lines.filter(::keep).takeLast(max)

    fun exitReasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_ANR -> "App not responding"
        ApplicationExitInfo.REASON_CRASH -> "Crash"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "Native crash"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "A service it depends on died"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "Used too many resources"
        ApplicationExitInfo.REASON_EXIT_SELF -> "Closed itself"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "Failed to start"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "Closed by Android: low memory"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "Permission changed"
        ApplicationExitInfo.REASON_SIGNALED -> "Killed by a signal"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "Closed by you"
        ApplicationExitInfo.REASON_USER_STOPPED -> "Stopped by you"
        ApplicationExitInfo.REASON_OTHER -> "Other"
        else -> "Reason $reason"
    }

    fun formatExit(record: ExitRecord, dateFormat: DateFormat): String = buildString {
        append(dateFormat.format(Date(record.timeMs))).append("  ").append(exitReasonName(record.reason))
        if (record.rssKb > 0) append("  (using ").append(record.rssKb / 1024).append(" MB)")
        record.description?.takeIf { it.isNotBlank() }?.let { append("\n    ").append(it) }
    }
}

/**
 * Keeps what is needed to understand a problem: crashes are written to a file as they happen, and the log
 * viewer reads this app's own log lines, the reasons Android gave for earlier exits, and device details.
 */
object AppLog {
    private const val TAG = "PocketLM"
    private const val CRASH_FILE = "last_crash.txt"
    private const val MAX_CRASH_CHARS = 20_000

    private var dir: File? = null

    /** Call once when the app starts. */
    fun install(context: Context) {
        dir = File(context.filesDir, "logs").also { it.mkdirs() }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                File(dir, CRASH_FILE).writeText(
                    ("Crashed at ${Date()} on thread ${thread.name}\n" + Log.getStackTraceString(error)).take(MAX_CRASH_CHARS),
                )
            }
            previous?.uncaughtException(thread, error)
        }
        Log.i(TAG, "PocketLM started")
    }

    /** The error that ended the app the last time it crashed, if it did. */
    fun lastCrash(): String? = dir?.let { File(it, CRASH_FILE) }?.takeIf { it.isFile }?.readText()

    fun clearCrash() {
        dir?.let { File(it, CRASH_FILE).delete() }
    }

    /** Records something worth finding in a bug report. */
    fun note(message: String) {
        Log.w(TAG, message)
    }

    /** This app's recent log lines (warnings, errors and its own messages), oldest first. */
    fun recentLines(): List<String> = try {
        val process = ProcessBuilder("logcat", "-d", "-v", "threadtime", "-t", "4000", "--pid=${Process.myPid()}")
            .redirectErrorStream(true)
            .start()
        val lines = process.inputStream.bufferedReader().readLines()
        if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroy()
        LogFormat.filter(lines)
    } catch (e: Exception) {
        listOf("(The log could not be read: ${e.message})")
    }

    /** Why the app ended in the past: a crash, or Android closing it because memory ran low. */
    fun exits(context: Context): List<ExitRecord> = try {
        context.getSystemService(ActivityManager::class.java)
            .getHistoricalProcessExitReasons(context.packageName, 0, 8)
            .map { ExitRecord(it.timestamp, it.reason, it.description, it.rss) }
    } catch (e: Exception) {
        emptyList()
    }

    /** Everything above plus device details, as one text to copy into a bug report. */
    fun report(context: Context, appDetails: String): String = buildString {
        val dates = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM)
        append("PocketLM report, ").append(dates.format(Date())).append("\n")
        append(deviceInfo(context)).append("\n")
        if (appDetails.isNotBlank()) append(appDetails.trim()).append("\n")

        val exits = exits(context)
        append("\nEARLIER EXITS\n")
        append(if (exits.isEmpty()) "(none recorded)\n" else exits.joinToString("\n") { LogFormat.formatExit(it, dates) } + "\n")

        lastCrash()?.let { append("\nLAST CRASH\n").append(it.trim()).append("\n") }

        append("\nRECENT LOG\n")
        val lines = recentLines()
        append(if (lines.isEmpty()) "(nothing to show)\n" else lines.joinToString("\n") + "\n")
    }

    fun deviceInfo(context: Context): String {
        val memory = ActivityManager.MemoryInfo().also { context.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "?"
        return "App ${version}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), " +
            "${Build.MANUFACTURER} ${Build.MODEL}, ${Build.SUPPORTED_ABIS.firstOrNull() ?: "?"}, " +
            "${memory.totalMem / (1024 * 1024)} MB RAM (${memory.availMem / (1024 * 1024)} MB free)"
    }
}
