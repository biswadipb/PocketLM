package com.pocketlm.app

import android.app.job.JobParameters
import android.app.job.JobService
import com.pocketlm.app.data.SyncOutcome
import com.pocketlm.app.data.SyncRunner

/**
 * Checks for changes from other devices while the app is closed. Android starts it now and then (see
 * [SyncRunner.updateBackgroundJob]) when the phone is on a suitable network and the battery is not low.
 */
class SyncJobService : JobService() {

    @Volatile
    private var stopped = false
    private var worker: Thread? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val runner = SyncRunner.get(applicationContext)
        if (!runner.config.configured || !runner.config.autoSync) return false
        stopped = false
        worker = Thread {
            val outcome = runCatching { runner.run(manual = false) { stopped } }.getOrNull()
            // The next period tries again, so a failure needs no special retry.
            jobFinished(params, outcome is SyncOutcome.Busy)
        }.also { it.start() }
        return true
    }

    /** Android wants the job to stop (the network went away, or time ran out); the sync saves its progress as it goes. */
    override fun onStopJob(params: JobParameters): Boolean {
        stopped = true
        return false
    }
}
