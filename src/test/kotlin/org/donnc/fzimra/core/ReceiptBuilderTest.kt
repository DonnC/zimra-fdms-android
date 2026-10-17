package org.donnc.fzimra.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/** Computation: everything fzimra works out for the developer. */
class ReceiptBuilderTest {
    @Test
    fun computesLinesTaxesTotalAndPayments() {
        val receipt = prepared(
            invoice(
                lines = listOf(
                    bread(quantity = 2, price = d("1.15")),
                    ReceiptLine.discount("Loyalty discount", d("0.30"), STANDARD, "19059000"),
                ),
            ),
        )
        assertEquals(listOf(1, 2), receipt.lines.map { it.lineNo })
        assertEquals(listOf(d("2.30"), d("-0.30")), receipt.lines.map { it.total })
        assertEquals(d("1.15"), receipt.lines[0].unitPrice)
        assertEquals(d("-0.30"), receipt.lines[1].unitPrice)
        assertEquals(d("15"), receipt.lines[0].taxPercent)
        assertEquals(listOf(ReceiptTax(STANDARD, d("15"), d("0.26"), d("2.00"))), receipt.taxes)
        assertEquals(d("2.00"), receipt.total)
        assertEquals(d("0.26"), receipt.totalTax)
        assertEquals(listOf(PreparedPayment(MoneyType.CASH, d("2.00"))), receipt.payments)
        assertEquals(d("0.00"), receipt.change)
        assertEquals("USD", receipt.currency)
    }

    @Test
    fun taxIsWorkedOutPerTaxNotPerLine() {
        // 0.30 and 0.20 at 15% inclusive: per line 0.04 + 0.03 = 0.07; on the group 0.50 → 0.07 too,
        // but three 0.10 lines show the difference: per line 3 × 0.01 = 0.03, on the group 0.30 → 0.04.
        val lines = List(3) { ReceiptLine.saleWithTotal("Sweet", 1, d("0.10"), STANDARD, "17049000") }
        assertEquals(d("0.04"), prepared(invoice(lines)).taxes.single().taxAmount)
    }

    @Test
    fun roundsLineTotalsAndTaxHalfToEven() {
        // 3 × 0.125 = 0.375 → 0.38 (7 is odd, so the tie goes up to 8); 1 × 0.125 = 0.125 → 0.12.
        val receipt = prepared(
            invoice(
                listOf(
                    ReceiptLine.sale("A", 3, d("0.125"), ZERO_RATED, "10063000"),
                    ReceiptLine.sale("B", 1, d("0.125"), ZERO_RATED, "10063000"),
                ),
            ),
        )
        assertEquals(listOf(d("0.38"), d("0.12")), receipt.lines.map { it.total })
    }

    @Test
    fun mixedTaxesGetOneRowEachOrderedByTaxId() {
        val receipt = prepared(
            invoice(
                listOf(
                    ReceiptLine.sale("Tuition", 1, 10, EXEMPT, "49019900"),
                    ReceiptLine.sale("Maize meal", 1, 10, ZERO_RATED, "11022029"),
                    ReceiptLine.sale("Laptop", 1, 10, STANDARD, "84713000"),
                ),
            ),
        )
        assertEquals(
            listOf(
                ReceiptTax(STANDARD, d("15"), d("1.30"), d("10.00")),
                ReceiptTax(ZERO_RATED, BigDecimal.ZERO, d("0.00"), d("10.00")),
                ReceiptTax(EXEMPT, null, d("0.00"), d("10.00")),
            ),
            receipt.taxes,
        )
        assertEquals(d("30.00"), receipt.total)
    }

    @Test
    fun taxExclusivePricesAddTaxOnTop() {
        val receipt = prepared(
            invoice(listOf(ReceiptLine.saleWithTotal("Service", 1, 100, STANDARD, "99831100")), pricesIncludeTax = false),
        )
        assertEquals(listOf(ReceiptTax(STANDARD, d("15"), d("15.00"), d("115.00"))), receipt.taxes)
        assertEquals(d("115.00"), receipt.total)
        assertEquals(d("115.00"), receipt.payments.single().amount)
        assertTrue(!receipt.pricesIncludeTax)
    }

    @Test
    fun creditNoteSignsAreAppliedAutomatically() {
        val receipt = prepared(
            invoice(
                lines = listOf(bread(quantity = 1, price = d("1.15"))),
                type = ReceiptType.CREDIT_NOTE,
                notes = "Returned stale",
                original = OriginalReceipt.ById(9871),
            ),
        )
        assertEquals(d("-1.15"), receipt.lines.single().total)
        assertEquals(d("-1.15"), receipt.lines.single().unitPrice)
        assertEquals(listOf(ReceiptTax(STANDARD, d("15"), d("-0.15"), d("-1.15"))), receipt.taxes)
        assertEquals(d("-1.15"), receipt.total)
        assertEquals(listOf(PreparedPayment(MoneyType.CASH, d("-1.15"))), receipt.payments)
        assertEquals("Returned stale", receipt.notes)
    }

    @Test
    fun debitNoteStaysPositive() {
        val receipt = prepared(
            invoice(
                lines = listOf(ReceiptLine.saleWithTotal("Price correction", 1, d("5.00"), STANDARD, "19059000")),
                type = ReceiptType.DEBIT_NOTE,
                notes = "Under-charged",
                original = OriginalReceipt.ByDevice(deviceId = 321, receiptGlobalNo = 1004, fiscalDayNo = 12),
            ),
        )
        assertEquals(d("5.00"), receipt.total)
    }

    @Test
    fun unitPriceIsReportedOnlyWhenExact() {
        val receipt = prepared(
            invoice(
                listOf(
                    ReceiptLine.saleWithTotal("Three for ten", 3, d("10.00"), STANDARD, "19059000"),
                    ReceiptLine.saleWithTotal("Two", 2, d("5.00"), STANDARD, "19059000"),
                    ReceiptLine.sale("Fuel", d("37.5"), d("1.24"), STANDARD, "27101900"),
                ),
            ),
        )
        assertNull(receipt.lines[0].unitPrice) // 10.00 / 3 has no exact price
        assertEquals(d("2.50"), receipt.lines[1].unitPrice)
        assertEquals(d("46.50"), receipt.lines[2].total)
        assertEquals(d("1.24"), receipt.lines[2].unitPrice)
        assertEquals(d("37.5"), receipt.lines[2].quantity)
    }

    @Test
    fun remainderPaymentTakesWhatIsLeft() {
        val receipt = prepared(invoice(listOf(bread(2, d("10.00"))), payments = listOf(Payment.card(5), Payment.cash())))
        assertEquals(
            listOf(PreparedPayment(MoneyType.CARD, d("5.00")), PreparedPayment(MoneyType.CASH, d("15.00"))),
            receipt.payments,
        )
    }

    @Test
    fun cashHandedOverBeyondTheTotalIsChange() {
        val receipt = prepared(invoice(listOf(bread(1, d("45.20"))), payments = listOf(Payment.cash(50))))
        assertEquals(listOf(PreparedPayment(MoneyType.CASH, d("45.20"))), receipt.payments)
        assertEquals(d("4.80"), receipt.change)
    }

    @Test
    fun changeComesOutOfCashWhenPaymentsAreMixed() {
        val receipt = prepared(
            invoice(listOf(bread(1, d("45.20"))), payments = listOf(Payment.card(20), Payment.cash(30))),
        )
        assertEquals(
            listOf(PreparedPayment(MoneyType.CARD, d("20.00")), PreparedPayment(MoneyType.CASH, d("25.20"))),
            receipt.payments,
        )
        assertEquals(d("4.80"), receipt.change)
    }

    @Test
    fun aFullyDiscountedSaleHasAZeroPayment() {
        val receipt = prepared(
            invoice(listOf(bread(1, d("5.00")), ReceiptLine.discount("Voucher", d("5.00"), STANDARD, "19059000"))),
        )
        assertEquals(d("0.00"), receipt.total)
        assertEquals(listOf(PreparedPayment(MoneyType.CASH, d("0.00"))), receipt.payments)
    }

    @Test
    fun nonVatPayerNeedsNoHsCodeAndChargesNoVat() {
        val receipt = prepared(invoice(listOf(ReceiptLine.sale("Maize", 1, 7, ZERO_RATED))), NON_VAT_PAYER)
        assertNull(receipt.lines.single().hsCode)
        assertEquals(d("0.00"), receipt.totalTax)
    }

    @Test
    fun textInputsAreTrimmed() {
        val receipt = prepared(
            invoice(
                listOf(ReceiptLine.sale("  Bread  ", 1, 1, STANDARD, " 19059000 ")),
                invoiceNo = " INV-9 ",
                currency = "usd",
                buyer = Buyer(" Mbare Hardware ", " 2000456781 "),
            ),
        )
        assertEquals("Bread", receipt.lines.single().name)
        assertEquals("19059000", receipt.lines.single().hsCode)
        assertEquals("INV-9", receipt.invoiceNo)
        assertEquals("USD", receipt.currency)
        assertEquals(Buyer("Mbare Hardware", "2000456781"), receipt.buyer)
    }
}
