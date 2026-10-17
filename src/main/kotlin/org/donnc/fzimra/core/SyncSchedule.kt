package org.donnc.fzimra.core

import java.time.Duration

/** When to sync again after a run. */
public sealed class NextSync {
    /** No follow-up: the next new work starts a run. */
    public object WhenWorkArrives : NextSync()

    /** Again after [delay], whether or not new work arrives. */
    public data class After(val delay: Duration) : NextSync()

    /** Again once the network is back, waiting longer after each failure. */
    public object WhenOnline : NextSync()
}

/**
 * How background sync follows up on each [SyncOutcome].
 *
 * @property waiting how soon to look again while ZIMRA validates a close.
 * @property blocked how soon to look again while a person must act, so a fix
 *   made elsewhere (for example in ZIMRA's portal) is noticed.
 * @property busy how soon to try again when another run was in progress.
 * @property openDay how often to check ZIMRA while a fiscal day is open with
 *   it, to notice a close in ZIMRA's portal; `null` not to.
 */
public data class SyncSchedule(
    val waiting: Duration = Duration.ofSeconds(30),
    val blocked: Duration = Duration.ofMinutes(30),
    val busy: Duration = Duration.ofSeconds(30),
    val openDay: Duration? = null,
) {
    /** What to do after a run that ended with [report]. */
    public fun after(report: SyncReport): NextSync = when (report.outcome) {
        SyncOutcome.COMPLETE -> openDay?.let { NextSync.After(it) } ?: NextSync.WhenWorkArrives
        SyncOutcome.WAITING -> NextSync.After(waiting)
        SyncOutcome.OFFLINE -> NextSync.WhenOnline
        SyncOutcome.BLOCKED -> NextSync.After(blocked)
        SyncOutcome.BUSY -> NextSync.After(busy)
    }
}
