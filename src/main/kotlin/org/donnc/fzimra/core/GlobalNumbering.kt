package org.donnc.fzimra.core

/**
 * When the global receipt number starts again at 1.
 *
 * ZIMRA allows the global number to start again at 1 only with the first
 * receipt of a fiscal day; a restart mid-day is rejected. So fzimra only ever
 * restarts it when a new fiscal day begins. The number is an `Int` on the
 * wire, so after [Int.MAX_VALUE] a restart is the only way to keep issuing:
 * every policy restarts at the limit, closing the day first if the limit is
 * reached during it. There is no policy that stops issuing.
 */
public enum class GlobalNumberReset {
    /**
     * Numbers continue across fiscal days. Once the last global number has
     * passed 90% of the limit, the next new fiscal day starts again at 1, so
     * the restart normally lands on an ordinary day boundary. Only if the
     * limit is reached during a day is that day closed early. With the default
     * limit, numbering simply continues for the life of the device. The default.
     */
    NEAR_LIMIT,

    /** Every new fiscal day starts again at 1. */
    EVERY_FISCAL_DAY,
}

/**
 * How the device numbers receipts globally.
 *
 * @property reset when the global number starts again at 1.
 * @property limit the highest global number the device issues. The default is
 *   the largest the receipt format allows ([Int.MAX_VALUE]); set a lower value to
 *   restart earlier, for example to keep printed numbers short.
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
        GlobalNumberReset.EVERY_FISCAL_DAY -> lastGlobalNo > 0
    }
}
