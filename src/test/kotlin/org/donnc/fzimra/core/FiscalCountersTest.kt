package org.donnc.fzimra.core

import org.junit.Assert.assertEquals
import org.junit.Test

class FiscalCountersTest {
    private val sale = prepared(
        invoice(
            lines = listOf(
                ReceiptLine.sale("Laptop", 1, d("115.00"), STANDARD, "84713000"),
                ReceiptLine.sale("Tuition", 1, d("20.00"), EXEMPT, "49019900"),
            ),
            payments = listOf(Payment.card(100), Payment.cash()),
        ),
    )

    @Test
    fun invoiceAddsSalesTaxAndBalances() {
        assertEquals(
            listOf(
                FiscalCounter(FiscalCounterType.SALE_BY_TAX, "USD", d("115.00"), STANDARD, d("15")),
                FiscalCounter(FiscalCounterType.SALE_TAX_BY_TAX, "USD", d("15.00"), STANDARD, d("15")),
                FiscalCounter(FiscalCounterType.SALE_BY_TAX, "USD", d("20.00"), EXEMPT, null),
                FiscalCounter(FiscalCounterType.SALE_TAX_BY_TAX, "USD", d("0.00"), EXEMPT, null),
                FiscalCounter(FiscalCounterType.BALANCE_BY_MONEY_TYPE, "USD", d("100.00"), moneyType = MoneyType.CARD),
                FiscalCounter(FiscalCounterType.BALANCE_BY_MONEY_TYPE, "USD", d("35.00"), moneyType = MoneyType.CASH),
            ),
            FiscalCounters.of(sale),
        )
    }

    @Test
    fun creditNoteReducesItsOwnCounters() {
        val refund = prepared(
            invoice(
                lines = listOf(ReceiptLine.sale("Laptop", 1, d("115.00"), STANDARD, "84713000")),
                type = ReceiptType.CREDIT_NOTE, notes = "Faulty", original = OriginalReceipt.ById(1),
            ),
        )
        assertEquals(
            listOf(
                FiscalCounter(FiscalCounterType.CREDIT_NOTE_BY_TAX, "USD", d("-115.00"), STANDARD, d("15")),
                FiscalCounter(FiscalCounterType.CREDIT_NOTE_TAX_BY_TAX, "USD", d("-15.00"), STANDARD, d("15")),
                FiscalCounter(FiscalCounterType.BALANCE_BY_MONEY_TYPE, "USD", d("-115.00"), moneyType = MoneyType.CASH),
            ),
            FiscalCounters.of(refund),
        )
    }

    @Test
    fun debitNoteHasItsOwnCounters() {
        val note = prepared(
            invoice(
                lines = listOf(ReceiptLine.saleWithTotal("Correction", 1, d("11.50"), STANDARD, "84713000")),
                type = ReceiptType.DEBIT_NOTE, notes = "Under-charged", original = OriginalReceipt.ById(1),
            ),
        )
        assertEquals(
            setOf(FiscalCounterType.DEBIT_NOTE_BY_TAX, FiscalCounterType.DEBIT_NOTE_TAX_BY_TAX, FiscalCounterType.BALANCE_BY_MONEY_TYPE),
            FiscalCounters.of(note).map { it.type }.toSet(),
        )
    }

    @Test
    fun mergeAddsMatchingCountersAcrossReceipts() {
        val day = FiscalCounters.merge(FiscalCounters.of(sale), FiscalCounters.of(sale))
        assertEquals(d("270.00"), FiscalCounters.total(day, FiscalCounterType.SALE_BY_TAX))
        assertEquals(d("70.00"), day.single { it.moneyType == MoneyType.CASH }.value)
        assertEquals(6, day.size)
    }

    @Test
    fun mergeTreatsEqualPercentsWrittenDifferentlyAsOne() {
        val a = FiscalCounter(FiscalCounterType.SALE_BY_TAX, "USD", d("1.00"), 1, d("15"))
        val b = FiscalCounter(FiscalCounterType.SALE_BY_TAX, "usd", d("2.00"), 1, d("15.00"))
        assertEquals(listOf(a.copy(value = d("3.00"))), FiscalCounters.merge(listOf(a, b)))
    }

    @Test
    fun countersFeedTheFiscalDaySignature() {
        // Zero counters (exempt tax) are left out when signed.
        assertEquals(
            "SALEBYTAXUSD15.0011500SALEBYTAXUSD2000SALETAXBYTAXUSD15.001500" +
                "BALANCEBYMONEYTYPEUSDCASH3500BALANCEBYMONEYTYPEUSDCARD10000",
            SignatureStrings.counters(FiscalCounters.of(sale)),
        )
    }
}
