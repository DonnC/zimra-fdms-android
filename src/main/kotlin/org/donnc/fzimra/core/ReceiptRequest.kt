package org.donnc.fzimra.core

import java.math.BigDecimal
import java.time.LocalDate

/** Receipt line types. */
public enum class ReceiptLineType(public val specName: String) {
    SALE("Sale"),
    DISCOUNT("Discount"),
}

/** The layout a receipt is printed in, reported to ZIMRA with the receipt. */
public enum class ReceiptPrintForm(public val specName: String) {
    /** Narrow receipt paper. */
    RECEIPT48("Receipt48"),

    /** A4 invoice. */
    INVOICE_A4("InvoiceA4"),
}

/**
 * One line of a sale, as the host app knows it.
 *
 * Pass positive numbers only. fzimra applies the signs ZIMRA requires: credit
 * note lines and discount lines become negative automatically.
 *
 * Numbers may be [Int], [Long], [BigDecimal], [Double] or [Float]. Floating
 * point values are read through their shortest decimal form, so `19.99` is
 * read as `19.99`. Quantities and unit prices may have up to 6 decimal places;
 * line totals and discount amounts are whole cents.
 *
 * Create lines with [sale], [saleWithTotal] or [discount]. To build lines from
 * your own item model, see [ReceiptLineMapper].
 */
public class ReceiptLine private constructor(
    /** Whether this is a sale or a discount line. */
    public val type: ReceiptLineType,
    /** The name shown for the line. */
    public val name: String,
    /** Quantity sold; 1 for discount lines. */
    public val quantity: BigDecimal,
    /** Unit price, when the line was given by unit price. */
    public val unitPrice: BigDecimal?,
    /** Line total, when the line was given by total (or for a discount). */
    public val total: BigDecimal?,
    /** ZIMRA tax ID of the line. */
    public val taxId: Int,
    /** HS code of the goods or service, if any. */
    public val hsCode: String?,
) {
    override fun toString(): String =
        "ReceiptLine(type=$type, name=$name, quantity=$quantity, unitPrice=$unitPrice, total=$total, " +
            "taxId=$taxId, hsCode=$hsCode)"

    public companion object {
        /**
         * A sale line priced per unit. The line total is quantity × unit price,
         * rounded to cents (half to even).
         */
        public fun sale(name: String, quantity: Number, unitPrice: Number, taxId: Int, hsCode: String? = null): ReceiptLine =
            ReceiptLine(
                ReceiptLineType.SALE, name, exactDecimal(quantity, "quantity"),
                exactDecimal(unitPrice, "unitPrice"), null, taxId, hsCode,
            )

        /** A sale line whose total the host app has already worked out, for example after its own discounts. */
        public fun saleWithTotal(name: String, quantity: Number, total: Number, taxId: Int, hsCode: String? = null): ReceiptLine =
            ReceiptLine(
                ReceiptLineType.SALE, name, exactDecimal(quantity, "quantity"),
                null, exactDecimal(total, "total"), taxId, hsCode,
            )

        /**
         * A discount line on a fiscal invoice. Pass the discount as a positive
         * amount; it is recorded as negative. The [taxId] is the tax of the
         * goods the discount applies to. VAT-registered taxpayers must give an
         * HS code for every line, discounts included.
         */
        public fun discount(name: String, amount: Number, taxId: Int, hsCode: String? = null): ReceiptLine =
            ReceiptLine(
                ReceiptLineType.DISCOUNT, name, BigDecimal.ONE,
                null, exactDecimal(amount, "amount"), taxId, hsCode,
            )
    }
}

/**
 * Turns an item from the host app's own model into a [ReceiptLine].
 *
 * Write one mapper for your item or product type and reuse it for every sale.
 * Your model does not need to change or depend on fzimra:
 *
 * ```kotlin
 * val mapper = ReceiptLineMapper<CartItem> { item ->
 *     ReceiptLine.sale(item.title, item.qty, item.price, taxId = taxIds.getValue(item.taxClass), hsCode = item.hs)
 * }
 * val lines = cart.items.toReceiptLines(mapper)
 * ```
 */
public fun interface ReceiptLineMapper<in T> {
    /** The receipt line for [item]. */
    public fun toReceiptLine(item: T): ReceiptLine
}

/** Maps every item to a [ReceiptLine] with [mapper], keeping their order. */
public fun <T> Iterable<T>.toReceiptLines(mapper: ReceiptLineMapper<T>): List<ReceiptLine> =
    map { mapper.toReceiptLine(it) }

/**
 * A payment towards the receipt total.
 *
 * Pass positive amounts only; credit note refunds become negative
 * automatically. A payment without an [amount] takes whatever is left of the
 * total once the other payments are counted; at most one payment may do so.
 *
 * Cash handed over may exceed the total: the excess is treated as change given
 * back, deducted from the cash payment and reported as
 * [PreparedReceipt.change].
 */
public class Payment private constructor(
    /** How the customer paid. */
    public val moneyType: MoneyType,
    /** The amount paid, or `null` for the rest of the total. */
    public val amount: BigDecimal?,
) {
    override fun toString(): String = "Payment(moneyType=$moneyType, amount=$amount)"

    public companion object {
        /** A payment of [amount] by [moneyType]. */
        public fun of(moneyType: MoneyType, amount: Number): Payment =
            Payment(moneyType, exactDecimal(amount, "payment amount"))

        /** The rest of the total, paid by [moneyType]. */
        public fun remainder(moneyType: MoneyType): Payment = Payment(moneyType, null)

        /** Cash: [amount] handed over, or the rest of the total when omitted. */
        public fun cash(amount: Number? = null): Payment =
            if (amount == null) remainder(MoneyType.CASH) else of(MoneyType.CASH, amount)

        /** Card: [amount], or the rest of the total when omitted. */
        public fun card(amount: Number? = null): Payment =
            if (amount == null) remainder(MoneyType.CARD) else of(MoneyType.CARD, amount)

        /** Mobile wallet: [amount], or the rest of the total when omitted. */
        public fun mobileWallet(amount: Number? = null): Payment =
            if (amount == null) remainder(MoneyType.MOBILE_WALLET) else of(MoneyType.MOBILE_WALLET, amount)
    }
}

/** The buyer's address. */
public data class BuyerAddress(
    val province: String? = null,
    val city: String? = null,
    val street: String? = null,
    val houseNo: String? = null,
    val district: String? = null,
)

/** The buyer's contact details. */
public data class BuyerContacts(
    val phoneNo: String? = null,
    val email: String? = null,
)

/**
 * A registered buyer. Leave the buyer out for walk-in customers: ZIMRA
 * requires both [registerName] and [tin] whenever a buyer is given.
 *
 * @property tin the buyer's 10-digit taxpayer number.
 * @property vatNumber the buyer's 9-digit VAT number, if registered for VAT.
 */
public data class Buyer(
    val registerName: String,
    val tin: String,
    val vatNumber: String? = null,
    val tradeName: String? = null,
    val contacts: BuyerContacts? = null,
    val address: BuyerAddress? = null,
)

/** The receipt a credit or debit note corrects. */
public sealed class OriginalReceipt {
    /** Identified by the receipt ID ZIMRA returned for it. */
    public data class ById(val receiptId: Long) : OriginalReceipt()

    /** Identified by the device that issued it, its global number and its fiscal day. */
    public data class ByDevice(val deviceId: Int, val receiptGlobalNo: Int, val fiscalDayNo: Int) : OriginalReceipt()
}

/**
 * A sale (or credit or debit note) as the host app describes it.
 *
 * Give only what the sale is: fzimra works out line totals, signs, the tax
 * table, the receipt total and payment amounts, and checks the result against
 * ZIMRA's rules in [ReceiptBuilder.prepare].
 *
 * @property invoiceNo the host app's own reference for the sale, unique per
 *   taxpayer, at most 50 characters.
 * @property pricesIncludeTax whether line prices and totals include tax.
 * @property notes a reason; mandatory for credit and debit notes.
 * @property original the receipt a credit or debit note corrects.
 */
public data class ReceiptRequest(
    val type: ReceiptType,
    val currency: String,
    val invoiceNo: String,
    val lines: List<ReceiptLine>,
    val payments: List<Payment>,
    val buyer: Buyer? = null,
    val notes: String? = null,
    val original: OriginalReceipt? = null,
    val pricesIncludeTax: Boolean = true,
    val printForm: ReceiptPrintForm = ReceiptPrintForm.RECEIPT48,
)

/**
 * A tax the device may apply, as listed in its ZIMRA configuration.
 *
 * @property percent the tax percent, or `null` for an exempt tax.
 * @property validFrom first day the tax applies, if limited.
 * @property validTill last day the tax applies, if limited.
 */
public data class TaxDefinition(
    val taxId: Int,
    val name: String,
    val percent: BigDecimal?,
    val validFrom: LocalDate? = null,
    val validTill: LocalDate? = null,
) {
    init {
        if (taxId <= 0) throw FiscalValueException("taxID must be positive, got $taxId")
        SignatureStrings.formatPercent(percent) // at most two decimal places
    }
}

/**
 * What fzimra needs to know about the taxpayer to prepare a receipt.
 *
 * @property taxes the taxes in the device's ZIMRA configuration.
 * @property isVatPayer whether the taxpayer is registered for VAT (has a VAT number).
 */
public data class ReceiptContext(
    val taxes: List<TaxDefinition>,
    val isVatPayer: Boolean,
) {
    init {
        val duplicates = taxes.groupBy { it.taxId }.filterValues { it.size > 1 }.keys
        if (duplicates.isNotEmpty()) throw FiscalValueException("tax IDs listed more than once: $duplicates")
    }
}
