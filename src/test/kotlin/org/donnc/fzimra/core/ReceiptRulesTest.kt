package org.donnc.fzimra.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Rules: every check fzimra makes before a receipt can be issued, one case each. */
class ReceiptRulesTest {
    private fun creditNote(lines: List<ReceiptLine> = listOf(bread(1)), notes: String? = "Returned", original: OriginalReceipt? = OriginalReceipt.ById(1)) =
        invoice(lines, type = ReceiptType.CREDIT_NOTE, notes = notes, original = original)

    @Test fun currencyMustBeAnIsoCode() = assertRefused(invoice(listOf(bread()), currency = "US$"), "RCPT010", "currency")

    @Test fun invoiceNumberIsRequired() = assertRefused(invoice(listOf(bread()), invoiceNo = "  "), null, "invoiceNo")

    @Test fun invoiceNumberIsAtMost50Characters() =
        assertRefused(invoice(listOf(bread()), invoiceNo = "X".repeat(51)), null, "invoiceNo")

    @Test fun atLeastOneLine() = assertRefused(invoice(emptyList()), "RCPT016", "lines")

    @Test fun atLeastOnePayment() = assertRefused(invoice(listOf(bread()), payments = emptyList()), "RCPT018", "payments")

    @Test fun creditNoteNeedsAReason() = assertRefused(creditNote(notes = " "), "RCPT034", "notes")

    @Test fun notesAreAtMost500Characters() =
        assertRefused(creditNote(notes = "x".repeat(501)), null, "notes")

    @Test fun creditNoteNeedsTheOriginal() = assertRefused(creditNote(original = null), "RCPT015", "original")

    @Test fun invoiceCannotReferToAnotherReceipt() =
        assertRefused(invoice(listOf(bread()), original = OriginalReceipt.ById(5)), "RCPT029", "original")

    @Test fun originalByDeviceNeedsAllThreeNumbers() =
        assertRefused(creditNote(original = OriginalReceipt.ByDevice(321, 0, 12)), "RCPT015", "original")

    @Test fun originalByIdMustBePositive() =
        assertRefused(creditNote(original = OriginalReceipt.ById(0)), "RCPT015", "original.receiptId")

    @Test fun unknownTaxIsRefused() =
        assertRefused(invoice(listOf(ReceiptLine.sale("X", 1, 1, 99, "19059000"))), "RCPT025", "lines[0].taxId")

    @Test fun taxMustBeInForce() {
        val context = ReceiptContext(
            listOf(TaxDefinition(STANDARD, "New rate", d("15.5"), validFrom = LocalDate.of(2027, 1, 1))),
            isVatPayer = true,
        )
        assertRefused(invoice(listOf(bread())), "RCPT025", "lines[0].taxId", context)
    }

    @Test fun taxThatEndedIsRefused() {
        val context = ReceiptContext(
            listOf(TaxDefinition(STANDARD, "Old rate", d("14.5"), validTill = LocalDate.of(2026, 1, 31))),
            isVatPayer = true,
        )
        assertRefused(invoice(listOf(bread())), "RCPT025", "lines[0].taxId", context)
    }

    @Test fun quantityMustBePositive() = assertRefused(invoice(listOf(bread(quantity = 0))), "RCPT023", "lines[0].quantity")

    @Test fun quantityHasAtMostSixDecimals() =
        assertRefused(invoice(listOf(bread(quantity = d("1.0000001")))), null, "lines[0].quantity")

    @Test fun unitPriceMustBePositive() = assertRefused(invoice(listOf(bread(price = d("-1")))), null, "lines[0].unitPrice")

    @Test fun unitPriceHasAtMostSixDecimals() {
        // 0.1 + 0.2 in floating point: the developer gets told instead of a silently wrong price.
        assertRefused(invoice(listOf(bread(price = 0.1 + 0.2))), null, "lines[0].unitPrice")
    }

    @Test fun lineTotalIsWholeCents() =
        assertRefused(invoice(listOf(ReceiptLine.saleWithTotal("X", 1, d("1.005"), STANDARD, "19059000"))), null, "lines[0].total")

    @Test fun lineTotalMustBePositive() =
        assertRefused(invoice(listOf(ReceiptLine.saleWithTotal("X", 1, d("-1"), STANDARD, "19059000"))), null, "lines[0].total")

    @Test fun saleLineMustHaveAValue() = assertRefused(invoice(listOf(bread(price = 0))), "RCPT022", "lines[0].total")

    @Test fun discountsOnlyOnInvoices() = assertRefused(
        creditNote(listOf(bread(1), ReceiptLine.discount("D", 1, STANDARD, "19059000"))), "RCPT022", "lines[1].type",
    )

    @Test fun discountMustHaveAValue() =
        assertRefused(invoice(listOf(bread(), ReceiptLine.discount("D", 0, STANDARD, "19059000"))), null, "lines[1].amount")

    @Test fun nameIsRequired() =
        assertRefused(invoice(listOf(ReceiptLine.sale(" ", 1, 1, STANDARD, "19059000"))), null, "lines[0].name")

    @Test fun nameIsAtMost200Characters() =
        assertRefused(invoice(listOf(ReceiptLine.sale("n".repeat(201), 1, 1, STANDARD, "19059000"))), null, "lines[0].name")

    @Test fun nonVatPayerCannotChargeVat() =
        assertRefused(invoice(listOf(bread())), "RCPT021", "lines[0].taxId", NON_VAT_PAYER)

    @Test fun vatPayerNeedsAnHsCode() =
        assertRefused(invoice(listOf(ReceiptLine.sale("X", 1, 1, STANDARD))), "RCPT047", "lines[0].hsCode")

    @Test fun hsCodeIsDigitsOnly() =
        assertRefused(invoice(listOf(ReceiptLine.sale("X", 1, 1, STANDARD, "1905-90"))), "RCPT048", "lines[0].hsCode")

    @Test fun hsCodeHasFourOrEightDigits() =
        assertRefused(invoice(listOf(ReceiptLine.sale("X", 1, 1, STANDARD, "190590"))), "RCPT048", "lines[0].hsCode")

    @Test fun fourDigitHsCodeIsFineForStandardRated() {
        prepared(invoice(listOf(ReceiptLine.sale("X", 1, 1, STANDARD, "1905"))))
    }

    @Test fun zeroRatedLineOfVatPayerNeedsEightDigits() =
        assertRefused(invoice(listOf(ReceiptLine.sale("X", 1, 1, ZERO_RATED, "1102"))), "RCPT048", "lines[0].hsCode")

    @Test fun exemptLineOfVatPayerNeedsEightDigits() =
        assertRefused(invoice(listOf(ReceiptLine.sale("X", 1, 1, EXEMPT, "4901"))), "RCPT048", "lines[0].hsCode")

    @Test fun fourDigitsAreFineForNonVatPayer() {
        prepared(invoice(listOf(ReceiptLine.sale("X", 1, 1, ZERO_RATED, "1102"))), NON_VAT_PAYER)
    }

    @Test fun discountsMayNotExceedSales() = assertRefused(
        invoice(listOf(bread(1, 1), ReceiptLine.discount("D", 2, STANDARD, "19059000"))), "RCPT040", "lines",
    )

    @Test fun paymentsMustCoverTheTotal() =
        assertRefused(invoice(listOf(bread(1, 10)), payments = listOf(Payment.card(9))), "RCPT039", "payments")

    @Test fun overpaymentWithoutCashIsRefused() =
        assertRefused(invoice(listOf(bread(1, 10)), payments = listOf(Payment.card(11))), "RCPT039", "payments")

    @Test fun overpaymentBeyondTheCashIsRefused() = assertRefused(
        invoice(listOf(bread(1, 10)), payments = listOf(Payment.card(10), Payment.cash(3), Payment.mobileWallet(1))),
        "RCPT039", "payments",
    )

    @Test fun creditNoteRefundsCannotExceedTheTotal() =
        assertRefused(refund(listOf(Payment.cash(5))), "RCPT039", "payments")

    @Test fun onlyOnePaymentTakesTheRest() =
        assertRefused(invoice(listOf(bread()), payments = listOf(Payment.cash(), Payment.card())), null, "payments")

    @Test fun paymentsArePositive() =
        assertRefused(invoice(listOf(bread()), payments = listOf(Payment.cash(-1))), null, "payments[0].amount")

    @Test fun paymentsAreWholeCents() =
        assertRefused(invoice(listOf(bread()), payments = listOf(Payment.cash(d("2.305")))), null, "payments[0].amount")

    @Test fun buyerNeedsARegisteredName() =
        assertRefused(invoice(listOf(bread()), buyer = Buyer(" ", "2000456781")), "RCPT043", "buyer.registerName")

    @Test fun buyerTinIsTenDigits() =
        assertRefused(invoice(listOf(bread()), buyer = Buyer("Mbare Hardware", "12345")), "RCPT043", "buyer.tin")

    @Test fun buyerVatNumberIsNineDigits() = assertRefused(
        invoice(listOf(bread()), buyer = Buyer("Mbare Hardware", "2000456781", vatNumber = "2200")), null, "buyer.vatNumber",
    )

    @Test fun buyerNamesAreAtMost250Characters() =
        assertRefused(invoice(listOf(bread()), buyer = Buyer("b".repeat(251), "2000456781")), null, "buyer")

    @Test
    fun everyProblemIsReportedAtOnce() {
        val found = problems(
            invoice(
                lines = listOf(ReceiptLine.sale("X", 0, 1, 99), ReceiptLine.sale("Y", 1, 1, STANDARD, "12")),
                payments = emptyList(),
                currency = "XX",
                invoiceNo = "",
            ),
        )
        val fields = found.map { it.field }.toSet()
        assertTrue(found.toString(), fields.containsAll(listOf("currency", "invoiceNo", "payments", "lines[0].taxId", "lines[0].quantity", "lines[1].hsCode")))
        found.forEach { assertTrue(it.message.isNotBlank()) }
    }

    @Test
    fun aValidCreditNoteHasNoProblems() {
        val receipt = prepared(refund(listOf(Payment.remainder(MoneyType.BANK_TRANSFER))))
        assertEquals(MoneyType.BANK_TRANSFER, receipt.payments.single().moneyType)
    }

    private fun refund(payments: List<Payment>) =
        invoice(listOf(bread(1)), payments = payments, type = ReceiptType.CREDIT_NOTE, notes = "R", original = OriginalReceipt.ById(1))
}
