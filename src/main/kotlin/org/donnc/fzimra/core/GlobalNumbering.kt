package org.donnc.fzimra.core

/**
 * When the global receipt number starts again at 1.
 *
 * ZIMRA lets a device restart its global numbering only with the first
 * receipt of a new fiscal day, so a restart only ever happens there.
 */
public enum class GlobalNumberReset {
    /**
     * Numbers continue across fiscal days. Once the last global number has
     * passed 90% of the limit, the next new fiscal day starts again at 1. If
     * the limit is reached during a day, the day is rolled over so the restart
     * can happen. The device never stops issuing. The default.
     */
    NEAR_LIMIT,

    /** Every new fiscal day starts again at 1. */
    EVERY_FISCAL_DAY,

    /** Numbers never start again. Once the limit is reached, receipts are refused. */
    NEVER,
}

/**
 * How the device numbers receipts globally.
 *
 * @property reset when the global number starts again at 1.
 * @property limit the highest global number the device issues. The default is
 *   the largest the receipt format allows; set a lower value if ZIMRA limits it.
 */
public data class GlobalNumbering(
    val reset: GlobalNumberReset = GlobalNumberReset.NEAR_LIMIT,
    val limit: Int = Int.MAX_VALUE,
) {
    init {
        if (limit < 1) throw FiscalValueException("the global number limit must be at least 1, got $limit")
    }

    /** Whether a new fiscal day should start again at 1, after [lastGlobalNo]. */
    internal fun restartsAt(lastGlobalNo: Int): Boolean = when (reset) {
        GlobalNumberReset.EVERY_FISCAL_DAY -> lastGlobalNo > 0
        GlobalNumberReset.NEAR_LIMIT -> lastGlobalNo.toLong() * 10 > limit.toLong() * 9
        GlobalNumberReset.NEVER -> false
    }
}
