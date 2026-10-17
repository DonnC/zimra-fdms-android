package org.donnc.fzimra.core

/**
 * When the global receipt number starts again at 1.
 *
 * The global number counts every receipt since the device was activated and
 * continues across fiscal days. ZIMRA allows it to start again at 1 only once
 * it becomes very big, and only with the first receipt of a new fiscal day,
 * so a restart only ever happens there. Restarting every fiscal day is not
 * offered: it would turn the global number into a second daily counter.
 */
public enum class GlobalNumberReset {
    /**
     * Numbers continue across fiscal days. Once the last global number has
     * passed 90% of the limit, the next new fiscal day starts again at 1. If
     * the limit is reached during a day, the day is rolled over so the restart
     * can happen. The device never stops issuing. With the default limit,
     * numbering simply continues. The default.
     */
    NEAR_LIMIT,

    /** Numbers never start again. Once the limit is reached, receipts are refused. */
    NEVER,
}

/**
 * How the device numbers receipts globally.
 *
 * @property reset when the global number starts again at 1.
 * @property limit the highest global number the device issues. The default is
 *   the largest the receipt format allows; set a lower value to restart earlier.
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
        GlobalNumberReset.NEAR_LIMIT -> lastGlobalNo.toLong() * 10 > limit.toLong() * 9
        GlobalNumberReset.NEVER -> false
    }
}
