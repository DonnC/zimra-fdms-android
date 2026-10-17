package org.donnc.fzimra.android

import android.app.job.JobParameters
import android.app.job.JobService
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Runs background syncs scheduled by [FzimraBackground]. Declared in fzimra's
 * manifest; apps do not start it themselves.
 */
public class FzimraSyncService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        val deviceId = params.extras.getInt(FzimraBackground.EXTRA_DEVICE_ID, -1)
        if (deviceId <= 0) return false
        FzimraBackground.started(deviceId)
        EXECUTOR.execute {
            val decision = try {
                FzimraBackground.run(applicationContext, deviceId)
            } catch (e: RuntimeException) {
                // an unexpected failure: try again later rather than drop the queue
                FzimraBackground.Decision(null, retryWithBackoff = true, followUp = null)
            }
            jobFinished(params, decision.retryWithBackoff)
            FzimraBackground.finished(applicationContext, deviceId, decision)
        }
        return true
    }

    /** The platform stopped the job (for example, the network went away): run it again later. */
    override fun onStopJob(params: JobParameters): Boolean = true

    private companion object {
        /** One background run at a time in the process. */
        val EXECUTOR: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "fzimra-sync").apply { isDaemon = true }
        }
    }
}
