package org.donnc.fzimra.core

import java.math.BigDecimal

/**
 * Fiscal-day counters.
 *
 * Each receipt adds its sales amount (including tax) and its tax amount per
 * tax to the counters of its type, and each payment to the balance of its
 * money type. Credit note amounts are negative, so they reduce their counters.
 */
public object FiscalCounters {
    /** The counters [receipt] contributes to its fiscal day. */
    public fun of(receipt: PreparedReceipt): List<FiscalCounter> {
        val (byTax, taxByTax) = when (receipt.type) {
            ReceiptType.FISCAL_INVOICE -> FiscalCounterType.SALE_BY_TAX to FiscalCounterType.SALE_TAX_BY_TAX
            ReceiptType.CREDIT_NOTE -> FiscalCounterType.CREDIT_NOTE_BY_TAX to FiscalCounterType.CREDIT_NOTE_TAX_BY_TAX
            ReceiptType.DEBIT_NOTE -> FiscalCounterType.DEBIT_NOTE_BY_TAX to FiscalCounterType.DEBIT_NOTE_TAX_BY_TAX
        }
        val counters = mutableListOf<FiscalCounter>()
        for (tax in receipt.taxes) {
            counters += FiscalCounter(byTax, receipt.currency, tax.salesAmountWithTax, tax.taxId, tax.taxPercent)
            counters += FiscalCounter(taxByTax, receipt.currency, tax.taxAmount, tax.taxId, tax.taxPercent)
        }
        for (payment in receipt.payments) {
            counters += FiscalCounter(
                FiscalCounterType.BALANCE_BY_MONEY_TYPE, receipt.currency, payment.amount,
                moneyType = payment.moneyType,
            )
        }
        return merge(counters)
    }

    /**
     * Adds up counters that have the same type, currency, tax and money type,
     * keeping the order in which each first appears.
     */
    public fun merge(vararg counters: List<FiscalCounter>): List<FiscalCounter> {
        val sums = LinkedHashMap<List<Any?>, FiscalCounter>()
        for (counter in counters.asList().flatten()) {
            val key = listOf(
                counter.type, counter.currency.uppercase(java.util.Locale.ROOT), counter.taxId,
                counter.taxPercent?.stripTrailingZeros(), counter.moneyType,
            )
            val existing = sums[key]
            sums[key] = existing?.copy(value = existing.value.add(counter.value)) ?: counter
        }
        return sums.values.map { it.copy(value = FiscalMoney.roundToCents(it.value)) }
    }

    /** The total of [counters] of [type]; handy for reports. */
    public fun total(counters: List<FiscalCounter>, type: FiscalCounterType): BigDecimal =
        counters.filter { it.type == type }.fold(BigDecimal.ZERO) { sum, c -> sum.add(c.value) }
}
