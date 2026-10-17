package org.donnc.fzimra.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.math.BigInteger
import java.util.concurrent.atomic.AtomicInteger

/** How lines are built from the host app's own numbers and item models. */
class ReceiptLineTest {
    @Test
    fun acceptsTheNumberTypesAppsUse() {
        assertEquals(d("2"), ReceiptLine.sale("A", 2, 1, STANDARD).quantity)
        assertEquals(d("2"), ReceiptLine.sale("A", 2L, 1, STANDARD).quantity)
        assertEquals(d("2"), ReceiptLine.sale("A", BigInteger.TWO, 1, STANDARD).quantity)
        assertEquals(d("19.99"), ReceiptLine.sale("A", 1, 19.99, STANDARD).unitPrice)
        assertEquals(d("19.99"), ReceiptLine.sale("A", 1, 19.99f, STANDARD).unitPrice)
        assertEquals(d("0.1"), ReceiptLine.sale("A", 0.1, 1, STANDARD).quantity)
    }

    @Test
    fun refusesNumbersThatCannotBeExact() {
        assertThrows(FiscalValueException::class.java) { ReceiptLine.sale("A", Double.NaN, 1, STANDARD) }
        assertThrows(FiscalValueException::class.java) { ReceiptLine.sale("A", 1, Double.POSITIVE_INFINITY, STANDARD) }
        assertThrows(FiscalValueException::class.java) { ReceiptLine.sale("A", 1, Float.NaN, STANDARD) }
        assertThrows(FiscalValueException::class.java) { ReceiptLine.sale("A", AtomicInteger(1), 1, STANDARD) }
        assertThrows(FiscalValueException::class.java) { Payment.cash(Double.NaN) }
    }

    /** Stand-in for a developer's own model: fzimra never asks them to change it. */
    private data class CartItem(val title: String, val qty: Double, val priceCents: Long, val taxClass: String, val hs: String?)

    @Test
    fun mapsTheAppsOwnItemModel() {
        val taxIds = mapOf("A" to STANDARD, "Z" to ZERO_RATED)
        val mapper = ReceiptLineMapper<CartItem> { item ->
            ReceiptLine.sale(item.title, item.qty, FiscalMoney.fromCents(item.priceCents), taxIds.getValue(item.taxClass), item.hs)
        }
        val cart = listOf(
            CartItem("Bread", 2.0, 115, "A", "19059000"),
            CartItem("Maize meal 10kg", 1.0, 780, "Z", "11022029"),
        )
        val receipt = prepared(invoice(cart.toReceiptLines(mapper)))
        assertEquals(listOf("Bread", "Maize meal 10kg"), receipt.lines.map { it.name })
        assertEquals(listOf(d("2.30"), d("7.80")), receipt.lines.map { it.total })
        assertEquals(d("10.10"), receipt.total)
    }

    @Test
    fun contextRefusesDuplicateTaxIds() {
        assertThrows(FiscalValueException::class.java) {
            ReceiptContext(listOf(TaxDefinition(1, "A", d("15")), TaxDefinition(1, "B", d("0"))), isVatPayer = true)
        }
    }

    @Test
    fun taxDefinitionsAreValidated() {
        assertThrows(FiscalValueException::class.java) { TaxDefinition(0, "A", d("15")) }
        assertThrows(FiscalValueException::class.java) { TaxDefinition(1, "A", d("15.125")) }
    }

    @Test
    fun exactUnitPrice() {
        assertEquals(d("2.50"), ReceiptBuilder.exactUnitPrice(d("5.00"), d("2")))
        assertEquals(d("1.24"), ReceiptBuilder.exactUnitPrice(d("46.50"), d("37.5")))
        assertEquals(null, ReceiptBuilder.exactUnitPrice(d("10.00"), d("3")))
        assertEquals(d("-1.15"), ReceiptBuilder.exactUnitPrice(d("-1.15"), d("1")))
    }
}
