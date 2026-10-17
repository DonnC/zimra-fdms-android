package org.donnc.fzimra.android

import org.donnc.fzimra.core.SyncOutcome
import org.donnc.fzimra.core.SyncReport
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Duration

class FzimraBackgroundDecisionTest {
    private fun decide(outcome: SyncOutcome, rerun: Boolean = false) =
        FzimraBackground.decide(SyncReport(outcome), rerun).let { it.retryWithBackoff to it.followUp }

    @Test
    fun eachOutcomeHasItsFollowUp() {
        assertEquals(false to null, decide(SyncOutcome.COMPLETE))
        assertEquals(false to Duration.ofSeconds(30), decide(SyncOutcome.WAITING))
        assertEquals("offline: the platform retries once the network is back", true to null, decide(SyncOutcome.OFFLINE))
        assertEquals(false to Duration.ofMinutes(30), decide(SyncOutcome.BLOCKED))
        assertEquals(false to Duration.ofSeconds(30), decide(SyncOutcome.BUSY))
    }

    @Test
    fun workArrivingDuringARunIsSyncedRightAfter() {
        assertEquals(false to Duration.ZERO, decide(SyncOutcome.COMPLETE, rerun = true))
        assertEquals(false to Duration.ZERO, decide(SyncOutcome.BLOCKED, rerun = true))
        assertEquals("offline still waits for the network", true to null, decide(SyncOutcome.OFFLINE, rerun = true))
    }
}
