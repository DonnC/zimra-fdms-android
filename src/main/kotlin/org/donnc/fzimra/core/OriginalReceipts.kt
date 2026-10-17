package org.donnc.fzimra.core

import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * The details of the receipt a credit or debit note corrects, for when the
 * device has no record of it (for example an older sale kept only on your
 * server).
 *
 * @property total the original receipt's total, as issued.
 * @property taxes the original receipt's tax table.
 * @property previousCredits total of earlier credit notes against the
 *   original, as a positive amount.
 * @property previousDebits total of earlier debit notes against the original.
 */
public data class OriginalReceiptDetails(
    val currency: String,
    val date: LocalDateTime,
    val total: BigDecimal,
    val taxes: List<ReceiptTax>,
    val previousCredits: BigDecimal = BigDecimal.ZERO,
    val previousDebits: BigDecimal = BigDecimal.ZERO,
)

/** The checks a credit or debit note must pass against its original receipt. */
internal object OriginalReceiptRules {
    private const val MAX_AGE_MONTHS = 12L

    fun check(note: PreparedReceipt, original: OriginalReceiptDetails, today: LocalDate): List<ReceiptProblem> {
        val problems = mutableListOf<ReceiptProblem>()
        if (!note.currency.equals(original.currency, ignoreCase = true)) {
            problems += ReceiptProblem(
                "RCPT042", "currency",
                "a credit or debit note must use the original receipt's currency (${original.currency.uppercase()})",
            )
        }
        if (original.date.toLocalDate().isBefore(today.minusMonths(MAX_AGE_MONTHS))) {
            problems += ReceiptProblem(
                "RCPT033", "original",
                "the original receipt is more than $MAX_AGE_MONTHS months old and can no longer be credited or debited",
            )
        }
        val originalTaxes = original.taxes.map { taxKey(it.taxId, it.taxPercent) }.toSet()
        note.taxes.filter { taxKey(it.taxId, it.taxPercent) !in originalTaxes }.forEach {
            problems += ReceiptProblem(
                "RCPT036", "lines",
                "tax ${it.taxId} was not on the original receipt; a note may only use the original's taxes",
            )
        }
        if (note.type == ReceiptType.CREDIT_NOTE) {
            val remaining = original.total.abs().add(original.previousDebits).subtract(original.previousCredits)
            if (note.total.abs() > remaining) {
                problems += ReceiptProblem(
                    "RCPT035", "lines",
                    "this credit note (${note.total.abs()}) exceeds what is left to credit on the original receipt " +
                        "(${FiscalMoney.roundToCents(remaining.max(BigDecimal.ZERO))})",
                )
            }
        }
        return problems
    }

    private fun taxKey(taxId: Int, percent: BigDecimal?) = taxId to percent?.stripTrailingZeros()
}
