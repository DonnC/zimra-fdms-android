package org.donnc.fzimra.core

import java.time.Clock
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** The time used to date receipts. */
public fun interface FiscalClock {
    /** The current local date and time, to the second. */
    public fun now(): LocalDateTime
}

/**
 * Zimbabwe local time, optionally corrected towards ZIMRA's clock.
 *
 * Receipts are dated in local time. Using [zone] rather than the phone's time
 * zone keeps receipt dates right on a phone set to the wrong zone; [offset]
 * corrects a phone whose clock is fast or slow.
 *
 * @param zone the time zone receipts are dated in; Zimbabwe by default.
 * @param base the underlying clock; the system clock by default.
 */
public class ZimbabweClock(
    private val zone: ZoneId = HARARE,
    private val base: Clock = Clock.systemUTC(),
) : FiscalClock {
    /** Added to the base clock; positive when the phone is behind ZIMRA. */
    @Volatile
    public var offset: Duration = Duration.ZERO

    override fun now(): LocalDateTime =
        LocalDateTime.ofInstant(base.instant().plus(offset), zone).truncatedTo(ChronoUnit.SECONDS)

    public companion object {
        /** Zimbabwe's time zone. */
        public val HARARE: ZoneId = ZoneId.of("Africa/Harare")
    }
}
