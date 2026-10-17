package org.donnc.fzimra.example.fiscal

import org.donnc.fzimra.core.ReceiptLine
import org.donnc.fzimra.core.ReceiptLineMapper
import org.donnc.fzimra.core.TaxDefinition
import java.math.BigDecimal

/** How a product is taxed, in the shop's own terms. */
enum class TaxClass(val label: String) {
    STANDARD("VAT"),
    ZERO_RATED("Zero rated"),
    EXEMPT("Exempt"),
}

/**
 * A product in the shop's own model. It knows nothing of fzimra: the
 * [mapper] below turns cart lines into receipt lines.
 */
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

    /**
     * ZIMRA's tax for each [TaxClass], found in the device's configuration by
     * rate. ZIMRA assigns its own tax IDs, so they are never hard-coded.
     */
    fun taxIds(taxes: List<TaxDefinition>): Map<TaxClass, Int> = buildMap {
        taxes.filter { it.percent != null && it.percent!!.signum() > 0 }.maxByOrNull { it.percent!! }?.let { put(TaxClass.STANDARD, it.taxId) }
        taxes.firstOrNull { it.percent != null && it.percent!!.signum() == 0 }?.let { put(TaxClass.ZERO_RATED, it.taxId) }
        taxes.firstOrNull { it.percent == null }?.let { put(TaxClass.EXEMPT, it.taxId) }
    }

    /** The shop's cart lines as receipt lines. */
    fun mapper(taxIds: Map<TaxClass, Int>): ReceiptLineMapper<CartLine> = ReceiptLineMapper { line ->
        val taxId = taxIds[line.product.taxClass] ?: error("ZIMRA has no ${line.product.taxClass.label} tax for this device")
        ReceiptLine.sale(line.product.name, line.quantity, line.product.price, taxId, line.product.hsCode)
    }
}
