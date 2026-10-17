package org.donnc.fzimra.android

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.os.PersistableBundle
import org.donnc.fzimra.core.FiscalSync
import org.donnc.fzimra.core.FiscalTerminal
import org.donnc.fzimra.core.NextSync
import org.donnc.fzimra.core.SyncOutcome
import org.donnc.fzimra.core.SyncReport
import org.donnc.fzimra.core.SyncSchedule
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Builds the `FiscalSync` for a device, for background runs. */
public fun interface FiscalSyncProvider {
    /**
     * The sync for [deviceId], or `null` if this app no longer has the device.
     * Called on a background thread.
     */
    public fun syncFor(context: Context, deviceId: Int): FiscalSync?
}

/**
 * Syncs fiscal devices with ZIMRA in the background, so receipts reach ZIMRA
 * as soon as the network allows, even when the app is not open.
 *
 * Runs use the platform's `JobScheduler`: they need a network connection,
 * survive restarts of the phone, and wait longer after each failure while
 * ZIMRA cannot be reached. A run starts right after each sale or close
 * request on a device made with `Fzimra.device`, and follows up as
 * [SyncSchedule] says: shortly while ZIMRA validates a close, slowly while a
 * person must act, and not at all once nothing is waiting.
 *
 * Install it once, in `Application.onCreate`, so background runs can build
 * the device and client even when the app was not running:
 *
 * ```kotlin
 * FzimraBackground.install { context, deviceId -> FiscalSync(myDevice(context, deviceId), myClient(context, deviceId)) }
 * ```
 */
public object FzimraBackground {
    @Volatile
    private var provider: FiscalSyncProvider? = null

    @Volatile
    private var onReport: ((Int, SyncReport) -> Unit)? = null

    @Volatile
    internal var schedule: SyncSchedule = SyncSchedule()
        private set

    @Volatile
    private var jobIdBase: Int = DEFAULT_JOB_ID_BASE

    private val terminals = ConcurrentHashMap<Int, FiscalTerminal>()
    private val running = ConcurrentHashMap<Int, AtomicBoolean>()

    /** A terminal made with `Fzimra.terminal` syncs itself; no provider is needed for it. */
    internal fun attach(deviceId: Int, terminal: FiscalTerminal) {
        terminals[deviceId] = terminal
    }
    private val rerun = ConcurrentHashMap<Int, AtomicBoolean>()

    /**
     * Enables background sync.
     *
     * @param schedule how runs follow up on each outcome.
     * @param jobIdBase the first `JobScheduler` job ID fzimra may use; change it
     *   only if your app's own jobs use IDs from 0x46000000 to 0x46FFFFFF.
     * @param onReport called after each background run, on its thread: for
     *   example to send accepted receipts to your server, or to tell a
     *   supervisor about a [SyncOutcome.BLOCKED] run.
     */
    public fun install(
        schedule: SyncSchedule = SyncSchedule(),
        jobIdBase: Int = DEFAULT_JOB_ID_BASE,
        onReport: ((deviceId: Int, report: SyncReport) -> Unit)? = null,
        provider: FiscalSyncProvider,
    ) {
        this.schedule = schedule
        this.jobIdBase = jobIdBase
        this.onReport = onReport
        this.provider = provider
    }

    /** Whether [install] was called in this process. */
    public val isInstalled: Boolean get() = provider != null || terminals.isNotEmpty()

    /**
     * Asks for a background run for [deviceId] after [delay] once a network is
     * available. If a run is already in progress, another follows it.
     */
    public fun requestSync(context: Context, deviceId: Int, delay: Duration = Duration.ZERO) {
        if (running[deviceId]?.get() == true) {
            rerun.computeIfAbsent(deviceId) { AtomicBoolean() }.set(true)
            return
        }
        enqueue(context.applicationContext, deviceId, delay)
    }

    /** Stops background runs for [deviceId]. */
    public fun cancel(context: Context, deviceId: Int) {
        scheduler(context).cancel(jobId(deviceId))
    }

    internal fun jobId(deviceId: Int): Int = jobIdBase + (deviceId and 0x00FFFFFF)

    private fun enqueue(context: Context, deviceId: Int, delay: Duration) {
        val job = JobInfo.Builder(jobId(deviceId), ComponentName(context, FzimraSyncService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setMinimumLatency(delay.toMillis().coerceAtLeast(0))
            .setBackoffCriteria(BACKOFF.toMillis(), JobInfo.BACKOFF_POLICY_EXPONENTIAL)
            .setPersisted(true)
            .setExtras(PersistableBundle().apply { putInt(EXTRA_DEVICE_ID, deviceId) })
            .build()
        scheduler(context).schedule(job)
    }

    /** What a background run decided. */
    internal data class Decision(val report: SyncReport?, val retryWithBackoff: Boolean, val followUp: Duration?)

    /** A job for [deviceId] started; requests until [finished] are run after it. */
    internal fun started(deviceId: Int) {
        running.computeIfAbsent(deviceId) { AtomicBoolean() }.set(true)
    }

    /**
     * Runs one sync for [deviceId] now, on the calling thread, and decides the
     * follow-up. The caller finishes its job before scheduling the follow-up,
     * since scheduling a job's own ID stops it.
     */
    internal fun run(context: Context, deviceId: Int): Decision {
        val terminal = terminals[deviceId]
        val report = when {
            terminal != null -> terminal.sync()
            // Started before the app created its terminal (or installed a provider): try again shortly.
            else -> provider?.syncFor(context, deviceId)?.run(RUN_LIMIT) ?: return Decision(null, retryWithBackoff = true, followUp = null)
        }
        try {
            onReport?.invoke(deviceId, report)
        } catch (e: RuntimeException) {
            // the app's handler must not stop background sync
        }
        return decide(report, rerunRequested = rerun[deviceId]?.getAndSet(false) == true)
    }

    internal fun decide(report: SyncReport, rerunRequested: Boolean): Decision {
        if (rerunRequested && report.outcome != SyncOutcome.OFFLINE) return Decision(report, false, Duration.ZERO)
        return when (val next = schedule.after(report)) {
            NextSync.WhenWorkArrives -> Decision(report, false, null)
            NextSync.WhenOnline -> Decision(report, true, null)
            is NextSync.After -> Decision(report, false, next.delay)
        }
    }

    /** The job for [deviceId] finished: schedules [decision]'s follow-up, if any. */
    internal fun finished(context: Context, deviceId: Int, decision: Decision) {
        running[deviceId]?.set(false)
        val followUp = decision.followUp ?: if (rerun[deviceId]?.getAndSet(false) == true && !decision.retryWithBackoff) Duration.ZERO else null
        if (followUp != null) enqueue(context, deviceId, followUp)
    }

    private fun scheduler(context: Context) = context.getSystemService(JobScheduler::class.java)

    internal const val EXTRA_DEVICE_ID = "org.donnc.fzimra.deviceId"
    private const val DEFAULT_JOB_ID_BASE = 0x46000000
    private val BACKOFF: Duration = Duration.ofSeconds(30)

    /** Well within the time the platform gives a job. */
    private val RUN_LIMIT: Duration = Duration.ofMinutes(8)
}
