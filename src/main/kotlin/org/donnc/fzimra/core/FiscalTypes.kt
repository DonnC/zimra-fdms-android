package org.donnc.fzimra.core

import java.math.BigDecimal

/** Receipt types. [specName] is the name used by FDMS. */
public enum class ReceiptType(public val specName: String) {
    FISCAL_INVOICE("FiscalInvoice"),
    CREDIT_NOTE("CreditNote"),
    DEBIT_NOTE("DebitNote"),
}

/**
 * Money types, in ZIMRA's order. That order, not alphabetical order, sorts
 * balance counters when a fiscal day is signed.
 */
public enum class MoneyType(public val specName: String) {
    CASH("Cash"),
    CARD("Card"),
    MOBILE_WALLET("MobileWallet"),
    COUPON("Coupon"),
    CREDIT("Credit"),
    BANK_TRANSFER("BankTransfer"),
    OTHER("Other"),
}

/**
 * Fiscal counter types, in ZIMRA's order, which sorts counters when a fiscal
 * day is signed.
 */
public enum class FiscalCounterType(public val specName: String, public val isByTax: Boolean) {
    SALE_BY_TAX("SaleByTax", true),
    SALE_TAX_BY_TAX("SaleTaxByTax", true),
    CREDIT_NOTE_BY_TAX("CreditNoteByTax", true),
    CREDIT_NOTE_TAX_BY_TAX("CreditNoteTaxByTax", true),
    DEBIT_NOTE_BY_TAX("DebitNoteByTax", true),
    DEBIT_NOTE_TAX_BY_TAX("DebitNoteTaxByTax", true),
    BALANCE_BY_MONEY_TYPE("BalanceByMoneyType", false),
}

/**
 * One row of a receipt's tax table, as it enters the receipt signature.
 *
 * The optional `taxCode` is deliberately not modelled. The specification
 * includes it in the signed string while receipts verified on ZIMRA's live
 * test environment did not; leaving it out of both the receipt and the
 * signature makes the two readings identical.
 *
 * @property taxId ZIMRA tax ID, as returned by getConfig.
 * @property taxPercent tax percent, or `null` for an exempt tax.
 * @property taxAmount tax amount, in the receipt currency.
 * @property salesAmountWithTax sales amount including tax, in the receipt currency.
 */
public data class ReceiptTax(
    val taxId: Int,
    val taxPercent: BigDecimal?,
    val taxAmount: BigDecimal,
    val salesAmountWithTax: BigDecimal,
) {
    init {
        if (taxId <= 0) throw FiscalValueException("taxID must be positive, got $taxId")
    }
}

/**
 * One fiscal-day counter.
 *
 * By-tax counters carry a [taxId] (and a [taxPercent], or `null` when exempt).
 * Balance counters carry a [moneyType]. A counter with a value of zero is
 * neither sent nor signed.
 */
public data class FiscalCounter(
    val type: FiscalCounterType,
    val currency: String,
    val value: BigDecimal,
    val taxId: Int? = null,
    val taxPercent: BigDecimal? = null,
    val moneyType: MoneyType? = null,
) {
    init {
        SignatureStrings.currencyCode(currency)
        if (type.isByTax) {
            if (taxId == null || taxId <= 0) throw FiscalValueException("${type.specName} needs a positive taxID")
            if (moneyType != null) throw FiscalValueException("${type.specName} cannot have a money type")
        } else {
            if (moneyType == null) throw FiscalValueException("${type.specName} needs a money type")
            if (taxId != null || taxPercent != null) {
                throw FiscalValueException("${type.specName} cannot have a tax")
            }
        }
    }
}
