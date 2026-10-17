package org.donnc.fzimra.core

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import java.math.BigDecimal
import java.time.LocalDate

internal fun d(value: String): BigDecimal = BigDecimal(value)

internal const val STANDARD = 1
internal const val ZERO_RATED = 2
internal const val EXEMPT = 3

internal val TAXES = listOf(
    TaxDefinition(STANDARD, "Standard rated 15%", d("15")),
    TaxDefinition(ZERO_RATED, "Zero rated 0%", BigDecimal.ZERO),
    TaxDefinition(EXEMPT, "Exempt", null),
)

internal val VAT_PAYER = ReceiptContext(TAXES, isVatPayer = true)
internal val NON_VAT_PAYER = ReceiptContext(TAXES, isVatPayer = false)
internal val TODAY: LocalDate = LocalDate.of(2026, 10, 17)

internal fun invoice(
    lines: List<ReceiptLine>,
    payments: List<Payment> = listOf(Payment.cash()),
    type: ReceiptType = ReceiptType.FISCAL_INVOICE,
    notes: String? = null,
    original: OriginalReceipt? = null,
    buyer: Buyer? = null,
    pricesIncludeTax: Boolean = true,
    currency: String = "USD",
    invoiceNo: String = "INV-1",
): ReceiptRequest = ReceiptRequest(
    type = type,
    currency = currency,
    invoiceNo = invoiceNo,
    lines = lines,
    payments = payments,
    buyer = buyer,
    notes = notes,
    original = original,
    pricesIncludeTax = pricesIncludeTax,
)

internal fun prepared(request: ReceiptRequest, context: ReceiptContext = VAT_PAYER): PreparedReceipt =
    when (val result = ReceiptBuilder.prepare(request, context, TODAY)) {
        is ReceiptResult.Prepared -> result.receipt
        is ReceiptResult.Refused -> fail("expected a prepared receipt, got ${result.problems}").let { error("unreachable") }
    }

internal fun problems(request: ReceiptRequest, context: ReceiptContext = VAT_PAYER): List<ReceiptProblem> =
    when (val result = ReceiptBuilder.prepare(request, context, TODAY)) {
        is ReceiptResult.Prepared -> fail("expected problems, got ${result.receipt}").let { error("unreachable") }
        is ReceiptResult.Refused -> result.problems
    }

/** Asserts that [request] is refused with exactly one problem, with [code] at [field]. */
internal fun assertRefused(request: ReceiptRequest, code: String?, field: String, context: ReceiptContext = VAT_PAYER) {
    val found = problems(request, context)
    assertEquals("problems: $found", listOf(code to field), found.map { it.zimraCode to it.field })
}

internal fun bread(quantity: Number = 2, price: Number = "1.15".toBigDecimal()): ReceiptLine =
    ReceiptLine.sale("Bread", quantity, price, STANDARD, "19059000")
