package org.donnc.fzimra.core

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * A value cannot be represented exactly the way ZIMRA requires, for example an
 * amount with a fraction of a cent where whole cents are needed.
 */
public class FiscalValueException(message: String) : IllegalArgumentException(message)

/**
 * Exact money arithmetic for fiscal receipts.
 *
 * ZIMRA signs amounts in cents (spec §13.2.1) and validates totals to the cent
 * (RCPT019, RCPT026, RCPT038, RCPT039). Amounts are therefore [BigDecimal],
 * never floating point, and every rounding uses [ROUNDING].
 */
public object FiscalMoney {
    /**
     * Banker's rounding (half to even), the rounding of .NET's
     * `Math.Round(decimal)`. FDMS is a .NET service, so amounts rounded here
     * agree with it on exact half-cent ties.
     */
    public val ROUNDING: RoundingMode = RoundingMode.HALF_EVEN

    /** Rounds [amount] to cents with [ROUNDING]. */
    public fun roundToCents(amount: BigDecimal): BigDecimal = amount.setScale(2, ROUNDING)

    /**
     * Whole cents of [amount], which must already be a whole number of cents.
     *
     * An amount with a fraction of a cent is refused rather than rounded:
     * reaching the signing step with one is a bug in the caller.
     *
     * @throws FiscalValueException if [amount] has a fraction of a cent or does
     *   not fit in a [Long].
     */
    public fun toCents(amount: BigDecimal, field: String = "amount"): Long {
        val cents = try {
            amount.setScale(2, RoundingMode.UNNECESSARY).movePointRight(2)
        } catch (e: ArithmeticException) {
            throw FiscalValueException("$field: $amount has a fraction of a cent")
        }
        return try {
            cents.longValueExact()
        } catch (e: ArithmeticException) {
            throw FiscalValueException("$field: $amount is too large")
        }
    }

    /** The amount of [cents], with two decimal places. */
    public fun fromCents(cents: Long): BigDecimal = BigDecimal.valueOf(cents, 2)

    /**
     * Splits [total], a whole number of cents, across [weights] so that the
     * parts add up to [total] exactly.
     *
     * Uses the largest-remainder method on whole cents; ties go to the earlier
     * weight. All parts carry the sign of [total]. When every weight is zero,
     * the whole total goes to the first part.
     *
     * @throws FiscalValueException if [total] has a fraction of a cent.
     */
    public fun splitProportionally(total: BigDecimal, weights: List<BigDecimal>): List<BigDecimal> {
        if (weights.isEmpty()) return emptyList()
        val signedCents = toCents(total, "total")
        val sign = if (signedCents < 0) -1L else 1L
        val totalCents = BigDecimal.valueOf(Math.abs(signedCents))
        val absWeights = weights.map { it.abs() }
        val weightSum = absWeights.fold(BigDecimal.ZERO, BigDecimal::add)

        val parts = LongArray(weights.size)
        if (weightSum.signum() == 0) {
            parts[0] = totalCents.longValueExact()
        } else {
            val exact = absWeights.map { totalCents.multiply(it).divide(weightSum, 20, RoundingMode.HALF_EVEN) }
            exact.forEachIndexed { i, share -> parts[i] = share.setScale(0, RoundingMode.FLOOR).longValueExact() }
            var remainder = totalCents.longValueExact() - parts.sum()
            val byRemainder = exact.indices.sortedWith(
                compareByDescending<Int> { exact[it].subtract(BigDecimal.valueOf(parts[it])) }.thenBy { it },
            )
            for (i in byRemainder) {
                if (remainder == 0L) break
                parts[i] += 1
                remainder -= 1
            }
        }
        return parts.map { fromCents(sign * it) }
    }
}
