package org.donnc.fzimra.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime

class OriginalReceiptRulesTest {
    private val standard15 = ReceiptTax(STANDARD, d("15"), d("1.30"), d("10.00"))
    private val original = OriginalReceiptDetails("USD", LocalDateTime.of(2026, 9, 1, 10, 0), d("10.00"), listOf(standard15))

    private fun note(
        amount: String = "4.00",
        type: ReceiptType = ReceiptType.CREDIT_NOTE,
        taxId: Int = STANDARD,
        currency: String = "USD",
    ) = prepared(
        invoice(
            listOf(ReceiptLine.sale("Bread", 1, d(amount), taxId, hsCode = "19059000")),
            type = type, notes = "Returned", original = OriginalReceipt.ById(1), currency = currency,
        ),
    )

    private fun codes(note: PreparedReceipt, details: OriginalReceiptDetails = original) =
        OriginalReceiptRules.check(note, details, TODAY).map { it.zimraCode }

    @Test
    fun aNoteWithinTheOriginalPasses() {
        assertEquals(emptyList<String>(), codes(note()))
        assertEquals(emptyList<String>(), codes(note("10.00")))
        assertEquals("currency compares case-insensitively", emptyList<String>(), codes(note(), original.copy(currency = "usd")))
    }

    @Test
    fun theCurrencyMustMatch() {
        assertEquals(listOf("RCPT042"), codes(note(currency = "ZWG")))
    }

    @Test
    fun theOriginalMustBeAtMostTwelveMonthsOld() {
        assertEquals(emptyList<String>(), codes(note(), original.copy(date = TODAY.minusMonths(12).atTime(0, 0))))
        assertEquals(listOf("RCPT033"), codes(note(), original.copy(date = TODAY.minusMonths(12).minusDays(1).atTime(23, 59))))
    }

    @Test
    fun onlyTheOriginalsTaxesMayBeUsed() {
        assertEquals(listOf("RCPT036"), codes(note(taxId = ZERO_RATED)))
        assertEquals(
            "same tax ID at another rate is another tax",
            listOf("RCPT036"),
            codes(note(), original.copy(taxes = listOf(standard15.copy(taxPercent = d("14.5"))))),
        )
        assertEquals("15 and 15.00 are the same rate", emptyList<String>(), codes(note(), original.copy(taxes = listOf(standard15.copy(taxPercent = d("15.00"))))))
    }

    @Test
    fun creditsCannotExceedWhatIsLeft() {
        assertEquals(listOf("RCPT035"), codes(note("10.01")))
        assertEquals(emptyList<String>(), codes(note("6.00"), original.copy(previousCredits = d("4.00"))))
        assertEquals(listOf("RCPT035"), codes(note("6.01"), original.copy(previousCredits = d("4.00"))))
        assertEquals("debits add to what can be credited", emptyList<String>(), codes(note("12.00"), original.copy(previousDebits = d("2.00"))))
        assertEquals(listOf("RCPT035"), codes(note("0.01"), original.copy(previousCredits = d("10.00"))))
    }

    @Test
    fun debitNotesHaveNoCreditLimit() {
        assertEquals(emptyList<String>(), codes(note("500.00", type = ReceiptType.DEBIT_NOTE)))
    }

    @Test
    fun problemsAreReportedTogether() {
        val stale = original.copy(date = LocalDateTime.of(2025, 1, 1, 0, 0), currency = "ZWG")
        assertEquals(listOf("RCPT042", "RCPT033", "RCPT035"), codes(note("20.00"), stale))
    }
}
