package org.donnc.fzimra.core

import java.math.BigDecimal

/**
 * A receipt line with every value worked out, carrying the sign ZIMRA expects.
 *
 * @property lineNo position on the receipt, starting at 1.
 * @property unitPrice unit price to report, or `null` when no exact unit price
 *   reproduces [total] (the price is optional; an inexact one would be refused).
 * @property total line total in the receipt currency; negative on credit notes
 *   and for discounts.
 * @property taxPercent the line's tax percent, or `null` when exempt.
 */
public data class PreparedLine(
    val lineNo: Int,
    val type: ReceiptLineType,
    val name: String,
    val quantity: BigDecimal,
    val unitPrice: BigDecimal?,
    val total: BigDecimal,
    val taxId: Int,
    val taxPercent: BigDecimal?,
    val hsCode: String?,
)

/** A payment with its final amount; negative on credit notes. */
public data class PreparedPayment(
    val moneyType: MoneyType,
    val amount: BigDecimal,
)

/**
 * A receipt ready to be numbered and signed: every amount computed, every
 * sign applied, and every rule that can be checked on the device satisfied.
 *
 * It does not yet carry the device's identity, receipt number or date; those
 * are added when the receipt is issued.
 *
 * @property total receipt total, including tax.
 * @property totalTax tax included in [total].
 * @property change cash given back to the customer; not part of any payment.
 */
public data class PreparedReceipt(
    val type: ReceiptType,
    val currency: String,
    val invoiceNo: String,
    val pricesIncludeTax: Boolean,
    val printForm: ReceiptPrintForm,
    val lines: List<PreparedLine>,
    val taxes: List<ReceiptTax>,
    val payments: List<PreparedPayment>,
    val total: BigDecimal,
    val totalTax: BigDecimal,
    val change: BigDecimal,
    val buyer: Buyer?,
    val notes: String?,
    val original: OriginalReceipt?,
)

/**
 * Why a receipt cannot be issued.
 *
 * @property zimraCode the ZIMRA validation code the receipt would receive
 *   (for example `RCPT047`), or `null` for a problem with the request itself.
 * @property field where the problem is, for example `lines[2].hsCode`.
 * @property message a plain explanation for the developer or the user.
 */
public data class ReceiptProblem(
    val zimraCode: String?,
    val field: String,
    val message: String,
)

/** The outcome of [ReceiptBuilder.prepare]. */
public sealed class ReceiptResult {
    /** The receipt satisfies every rule that can be checked on the device. */
    public data class Prepared(val receipt: PreparedReceipt) : ReceiptResult()

    /** The receipt would be refused or flagged by ZIMRA; [problems] lists every reason. */
    public data class Refused(val problems: List<ReceiptProblem>) : ReceiptResult()
}
