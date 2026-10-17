package org.donnc.fzimra.core

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

/**
 * Prepares receipts: works out every amount ZIMRA needs and checks the result
 * against every rule that can be checked on the device.
 *
 * A receipt ZIMRA would refuse or flag can never be withdrawn once it is
 * issued, so problems are found here, before anything is numbered or signed.
 */
public object ReceiptBuilder {
    private const val MAX_NAME = 200
    private const val MAX_INVOICE_NO = 50
    private const val MAX_NOTES = 500
    private const val MAX_BUYER_NAME = 250
    private const val MAX_DECIMALS = 6
    private val HUNDRED = BigDecimal(100)
    private val DIGITS = Regex("^\\d+$")
    private val TIN = Regex("^\\d{10}$")
    private val VAT_NUMBER = Regex("^\\d{9}$")

    /**
     * Prepares [request] for issuing.
     *
     * @param context the taxpayer's taxes and VAT status.
     * @param date the date the receipt will be issued, used to check that its
     *   taxes are in force.
     * @return [ReceiptResult.Prepared], or [ReceiptResult.Refused] with every
     *   problem found.
     */
    public fun prepare(
        request: ReceiptRequest,
        context: ReceiptContext,
        date: LocalDate = LocalDate.now(),
    ): ReceiptResult {
        val problems = mutableListOf<ReceiptProblem>()
        fun problem(code: String?, field: String, message: String) {
            problems += ReceiptProblem(code, field, message)
        }

        val isCredit = request.type == ReceiptType.CREDIT_NOTE
        val isNote = request.type != ReceiptType.FISCAL_INVOICE

        val currency = try {
            SignatureStrings.currencyCode(request.currency)
        } catch (e: FiscalValueException) {
            problem("RCPT010", "currency", "'${request.currency}' is not an ISO 4217 currency code")
            null
        }
        val invoiceNo = request.invoiceNo.trim()
        if (invoiceNo.isEmpty() || invoiceNo.length > MAX_INVOICE_NO) {
            problem(null, "invoiceNo", "invoiceNo is required and at most $MAX_INVOICE_NO characters")
        }
        val notes = request.notes?.trim()?.ifEmpty { null }
        if (isNote && notes == null) {
            problem("RCPT034", "notes", "a reason is required for credit and debit notes")
        }
        if (notes != null && notes.length > MAX_NOTES) {
            problem(null, "notes", "notes are limited to $MAX_NOTES characters")
        }
        checkOriginal(request, isNote, ::problem)
        request.buyer?.let { checkBuyer(it, ::problem) }

        if (request.lines.isEmpty()) problem("RCPT016", "lines", "at least one line is required")
        if (request.payments.isEmpty()) problem("RCPT018", "payments", "at least one payment is required")

        val taxes = context.taxes.associateBy { it.taxId }
        val lines = request.lines.mapIndexedNotNull { index, line ->
            prepareLine(index, line, request, context, taxes[line.taxId], date, ::problem)
        }
        if (problems.isNotEmpty() || lines.size != request.lines.size || currency == null) {
            return ReceiptResult.Refused(problems)
        }

        val taxTable = taxTable(lines, request.pricesIncludeTax)
        val total = taxTable.fold(BigDecimal.ZERO) { sum, tax -> sum.add(tax.salesAmountWithTax) }
        val totalTax = taxTable.fold(BigDecimal.ZERO) { sum, tax -> sum.add(tax.taxAmount) }
        if (isCredit && total.signum() > 0) {
            problem("RCPT040", "lines", "a credit note total must be zero or negative")
        }
        if (!isCredit && total.signum() < 0) {
            problem("RCPT040", "lines", "the discounts exceed the sales: the total must be zero or positive")
        }

        val settled = settlePayments(request.payments, total, isCredit, ::problem)
        if (problems.isNotEmpty() || settled == null) return ReceiptResult.Refused(problems)

        return ReceiptResult.Prepared(
            PreparedReceipt(
                type = request.type,
                currency = currency,
                invoiceNo = invoiceNo,
                pricesIncludeTax = request.pricesIncludeTax,
                printForm = request.printForm,
                lines = lines,
                taxes = taxTable,
                payments = settled.payments,
                total = total,
                totalTax = totalTax,
                change = settled.change,
                buyer = request.buyer?.let { it.copy(registerName = it.registerName.trim(), tin = it.tin.trim()) },
                notes = notes,
                original = request.original,
            ),
        )
    }

    private fun prepareLine(
        index: Int,
        line: ReceiptLine,
        request: ReceiptRequest,
        context: ReceiptContext,
        tax: TaxDefinition?,
        date: LocalDate,
        problem: (String?, String, String) -> Unit,
    ): PreparedLine? {
        val at = "lines[$index]"
        var failed = false
        fun fail(code: String?, field: String, message: String) {
            problem(code, "$at.$field", message)
            failed = true
        }

        val name = line.name.trim()
        if (name.isEmpty()) fail(null, "name", "a name is required")
        if (name.length > MAX_NAME) fail(null, "name", "names are limited to $MAX_NAME characters")

        if (tax == null) {
            fail("RCPT025", "taxId", "tax ${line.taxId} is not in the device's tax configuration")
        } else if ((tax.validFrom != null && date < tax.validFrom) || (tax.validTill != null && date > tax.validTill)) {
            fail("RCPT025", "taxId", "tax ${line.taxId} (${tax.name}) is not in force on $date")
        }

        val quantityOk = when {
            line.quantity.signum() <= 0 -> {
                fail("RCPT023", "quantity", "quantity must be greater than zero")
                false
            }
            decimalPlaces(line.quantity) > MAX_DECIMALS -> {
                fail(null, "quantity", "quantity ${line.quantity} has more than $MAX_DECIMALS decimal places")
                false
            }
            else -> true
        }

        // A value is only worked out from a valid quantity, so one mistake is reported once.
        val magnitude: BigDecimal? = when {
            !quantityOk -> null
            line.unitPrice != null -> {
                if (line.unitPrice.signum() < 0) {
                    fail(null, "unitPrice", "pass a positive unit price; fzimra applies credit note signs")
                    null
                } else if (decimalPlaces(line.unitPrice) > MAX_DECIMALS) {
                    fail(null, "unitPrice", "unit price ${line.unitPrice} has more than $MAX_DECIMALS decimal places")
                    null
                } else {
                    FiscalMoney.roundToCents(line.quantity.multiply(line.unitPrice))
                }
            }
            else -> {
                val total = checkNotNull(line.total)
                val field = if (line.type == ReceiptLineType.DISCOUNT) "amount" else "total"
                if (total.signum() < 0) {
                    fail(null, field, "pass a positive $field; fzimra applies the signs ZIMRA requires")
                    null
                } else if (decimalPlaces(total) > 2) {
                    fail(null, field, "$field $total has a fraction of a cent")
                    null
                } else {
                    total
                }
            }
        }

        when (line.type) {
            ReceiptLineType.SALE -> if (magnitude != null && magnitude.signum() == 0) {
                fail("RCPT022", "total", "a sale line must have a value; leave free items off the receipt")
            }
            ReceiptLineType.DISCOUNT -> {
                if (request.type != ReceiptType.FISCAL_INVOICE) {
                    fail("RCPT022", "type", "discount lines are only allowed on fiscal invoices")
                }
                if (magnitude != null && magnitude.signum() == 0) fail(null, "amount", "a discount must have a value")
            }
        }

        val percent = tax?.percent
        if (percent != null && percent.signum() > 0 && !context.isVatPayer) {
            fail("RCPT021", "taxId", "a taxpayer without a VAT number cannot charge VAT")
        }
        val hsCode = line.hsCode?.trim()?.ifEmpty { null }
        hsCodeProblem(hsCode, percent, context.isVatPayer)?.let { (code, message) -> fail(code, "hsCode", message) }

        if (failed || magnitude == null || tax == null) return null

        val negative = request.type == ReceiptType.CREDIT_NOTE || line.type == ReceiptLineType.DISCOUNT
        val total = FiscalMoney.roundToCents(if (negative) magnitude.negate() else magnitude)
        val quantity = line.quantity.stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it }
        return PreparedLine(
            lineNo = index + 1,
            type = line.type,
            name = name,
            quantity = quantity,
            unitPrice = exactUnitPrice(total, quantity),
            total = total,
            taxId = tax.taxId,
            taxPercent = percent,
            hsCode = hsCode,
        )
    }

    /**
     * The unit price to report, or `null` when no price of at most 6 decimal
     * places times [quantity] gives [total] exactly. ZIMRA refuses a line whose
     * total is not price × quantity, and the price is optional.
     */
    internal fun exactUnitPrice(total: BigDecimal, quantity: BigDecimal): BigDecimal? {
        if (quantity.signum() <= 0) return null
        if (quantity.compareTo(BigDecimal.ONE) == 0) return total
        val price = total.divide(quantity, MAX_DECIMALS, FiscalMoney.ROUNDING)
        if (price.multiply(quantity).compareTo(total) != 0) return null
        val trimmed = price.stripTrailingZeros()
        return if (trimmed.scale() < 2) trimmed.setScale(2) else trimmed
    }

    /**
     * HS code rules: required for VAT payers; 4 or 8 digits, and 8 digits for a
     * VAT payer's zero-rated or exempt line.
     */
    internal fun hsCodeProblem(hsCode: String?, percent: BigDecimal?, isVatPayer: Boolean): Pair<String, String>? {
        if (hsCode == null) {
            return if (isVatPayer) "RCPT047" to "an HS code is required for a VAT-registered taxpayer" else null
        }
        if (!DIGITS.matches(hsCode)) return "RCPT048" to "HS code '$hsCode' must contain digits only"
        val eightOnly = isVatPayer && (percent == null || percent.signum() == 0)
        return when {
            eightOnly && hsCode.length != 8 -> "RCPT048" to "a zero-rated or exempt line needs an 8-digit HS code"
            hsCode.length != 4 && hsCode.length != 8 -> "RCPT048" to "HS code '$hsCode' must have 4 or 8 digits"
            else -> null
        }
    }

    /**
     * The tax table: one row per tax, with the tax worked out on the tax's
     * total (not line by line) and rounded half to even.
     *
     * With tax-inclusive lines, tax = total × p ÷ (100 + p) and the sales
     * amount is the total. With tax-exclusive lines, tax = total × p ÷ 100 and
     * the sales amount is total + tax.
     */
    internal fun taxTable(lines: List<PreparedLine>, pricesIncludeTax: Boolean): List<ReceiptTax> =
        lines.groupBy { it.taxId to it.taxPercent }.entries.sortedBy { it.key.first }.map { (key, group) ->
            val (taxId, percent) = key
            val gross = group.fold(BigDecimal.ZERO) { sum, line -> sum.add(line.total) }
            val rate = percent ?: BigDecimal.ZERO
            val tax = when {
                rate.signum() == 0 -> BigDecimal.ZERO.setScale(2)
                pricesIncludeTax -> gross.multiply(rate).divide(HUNDRED.add(rate), 2, FiscalMoney.ROUNDING)
                else -> gross.multiply(rate).divide(HUNDRED, 2, FiscalMoney.ROUNDING)
            }
            val sales = if (pricesIncludeTax) gross else gross.add(tax)
            ReceiptTax(taxId, percent, tax, FiscalMoney.roundToCents(sales))
        }

    private class Settled(val payments: List<PreparedPayment>, val change: BigDecimal)

    private fun settlePayments(
        payments: List<Payment>,
        total: BigDecimal,
        isCredit: Boolean,
        problem: (String?, String, String) -> Unit,
    ): Settled? {
        var valid = true
        payments.forEachIndexed { i, payment ->
            val amount = payment.amount ?: return@forEachIndexed
            if (amount.signum() < 0) {
                problem(null, "payments[$i].amount", "pass a positive amount; fzimra applies credit note signs")
                valid = false
            } else if (decimalPlaces(amount) > 2) {
                problem(null, "payments[$i].amount", "payment $amount has a fraction of a cent")
                valid = false
            }
        }
        if (payments.count { it.amount == null } > 1) {
            problem(null, "payments", "only one payment can take the rest of the total")
            valid = false
        }
        if (!valid) return null

        val target = total.abs()
        val given = payments.fold(BigDecimal.ZERO) { sum, p -> sum.add(p.amount ?: BigDecimal.ZERO) }
        val amounts = payments.map { it.amount }.toMutableList()
        val rest = target.subtract(given)
        val remainderIndex = payments.indexOfFirst { it.amount == null }
        if (remainderIndex >= 0) amounts[remainderIndex] = rest.max(BigDecimal.ZERO)

        var change = BigDecimal.ZERO
        var excess = amounts.fold(BigDecimal.ZERO) { sum, a -> sum.add(a!!) }.subtract(target)
        if (excess.signum() > 0) {
            val cash = payments.indices.filter { payments[it].moneyType == MoneyType.CASH }
            if (isCredit || cash.isEmpty()) {
                problem("RCPT039", "payments", "the payments exceed the total by $excess")
                return null
            }
            for (i in cash.reversed()) {
                val taken = amounts[i]!!.min(excess)
                amounts[i] = amounts[i]!!.subtract(taken)
                change = change.add(taken)
                excess = excess.subtract(taken)
                if (excess.signum() == 0) break
            }
            if (excess.signum() > 0) {
                problem("RCPT039", "payments", "the payments exceed the total by $excess, more than the cash given")
                return null
            }
        }
        val paid = amounts.fold(BigDecimal.ZERO) { sum, a -> sum.add(a!!) }
        if (paid.compareTo(target) != 0) {
            problem(
                "RCPT039", "payments",
                "the payments (${FiscalMoney.roundToCents(paid)}) do not cover the total " +
                    "(${FiscalMoney.roundToCents(target)}); add a payment or use Payment.remainder",
            )
            return null
        }

        val prepared = payments.indices
            .map { PreparedPayment(payments[it].moneyType, FiscalMoney.roundToCents(amounts[it]!!)) }
            .filter { it.amount.signum() != 0 }
            .ifEmpty { listOf(PreparedPayment(payments.first().moneyType, BigDecimal.ZERO.setScale(2))) }
            .map { if (isCredit) it.copy(amount = it.amount.negate()) else it }
        return Settled(prepared, FiscalMoney.roundToCents(change))
    }

    private fun checkOriginal(request: ReceiptRequest, isNote: Boolean, problem: (String?, String, String) -> Unit) {
        val original = request.original
        if (isNote && original == null) {
            problem("RCPT015", "original", "credit and debit notes must identify the original receipt")
        }
        if (!isNote && original != null) {
            problem("RCPT029", "original", "a fiscal invoice cannot refer to another receipt")
        }
        when (original) {
            is OriginalReceipt.ById -> if (original.receiptId <= 0) {
                problem("RCPT015", "original.receiptId", "the original receipt ID must be positive")
            }
            is OriginalReceipt.ByDevice -> if (original.deviceId <= 0 || original.receiptGlobalNo <= 0 || original.fiscalDayNo <= 0) {
                problem("RCPT015", "original", "deviceID, receiptGlobalNo and fiscalDayNo must all be positive")
            }
            null -> Unit
        }
    }

    private fun checkBuyer(buyer: Buyer, problem: (String?, String, String) -> Unit) {
        val name = buyer.registerName.trim()
        val tin = buyer.tin.trim()
        if (name.isEmpty()) problem("RCPT043", "buyer.registerName", "the buyer's registered name is required")
        if (!TIN.matches(tin)) problem("RCPT043", "buyer.tin", "the buyer's TIN must be 10 digits")
        if (buyer.vatNumber != null && !VAT_NUMBER.matches(buyer.vatNumber.trim())) {
            problem(null, "buyer.vatNumber", "the buyer's VAT number must be 9 digits")
        }
        if (name.length > MAX_BUYER_NAME || (buyer.tradeName?.length ?: 0) > MAX_BUYER_NAME) {
            problem(null, "buyer", "buyer names are limited to $MAX_BUYER_NAME characters")
        }
    }
}
