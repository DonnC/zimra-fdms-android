package org.donnc.fzimra.example.fiscal

import org.donnc.fzimra.core.ReceiptLine
import org.donnc.fzimra.core.ReceiptLineMapper
import org.donnc.fzimra.core.TaxDefinition
import org.donnc.fzimra.core.Taxes
import java.math.BigDecimal

/** How a product is taxed, in the shop's own terms. */
enum class TaxClass(val label: String) {
    STANDARD("VAT"),
    ZERO_RATED("Zero rated"),
    EXEMPT("Exempt"),
}

/** A product in the shop's own model. It knows nothing of fzimra. */
data class Product(
    val sku: String,
    val name: String,
    val price: BigDecimal,
    val taxClass: TaxClass,
    val hsCode: String,
    val emoji: String,
)

data class CartLine(val product: Product, val quantity: Int)

object Catalogue {
    val products = listOf(
        Product("BRD-700", "Bread 700g", BigDecimal("1.15"), TaxClass.STANDARD, "19059000", "🍞"),
        Product("OIL-2L", "Cooking oil 2L", BigDecimal("4.80"), TaxClass.STANDARD, "15121100", "🫗"),
        Product("SUG-2KG", "Sugar 2kg", BigDecimal("2.60"), TaxClass.STANDARD, "17019900", "🧂"),
        Product("SOAP-1", "Bath soap", BigDecimal("0.95"), TaxClass.STANDARD, "34011100", "🧼"),
        Product("MLK-1L", "Fresh milk 1L", BigDecimal("1.50"), TaxClass.ZERO_RATED, "04011000", "🥛"),
        Product("MEAL-10", "Maize meal 10kg", BigDecimal("6.50"), TaxClass.ZERO_RATED, "11022000", "🌽"),
        Product("BOOK-G7", "Grade 7 textbook", BigDecimal("12.00"), TaxClass.EXEMPT, "49019900", "📘"),
        Product("PILL-30", "Paracetamol 30s", BigDecimal("2.20"), TaxClass.EXEMPT, "30049000", "💊"),
    )

    /** ZIMRA's tax for the shop's tax class, from the terminal's configuration. */
    fun tax(taxes: Taxes, taxClass: TaxClass): TaxDefinition? = runCatching {
        when (taxClass) {
            TaxClass.STANDARD -> taxes.standard
            TaxClass.ZERO_RATED -> taxes.zeroRated
            TaxClass.EXEMPT -> taxes.exempt
        }
    }.getOrNull()

    /** The only bridge between the shop's cart and fzimra. */
    fun mapper(taxes: Taxes): ReceiptLineMapper<CartLine> = ReceiptLineMapper { line ->
        val tax = tax(taxes, line.product.taxClass) ?: error("ZIMRA has no ${line.product.taxClass.label} tax for this taxpayer")
        ReceiptLine.sale(line.product.name, line.quantity, line.product.price, tax, line.product.hsCode)
    }
}
