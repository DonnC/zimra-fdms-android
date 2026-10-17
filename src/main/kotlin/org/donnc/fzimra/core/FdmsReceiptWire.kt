package org.donnc.fzimra.core

import java.math.BigDecimal

/**
 * Writes receipts and fiscal day counters in the gateway's JSON. Optional
 * fields are left out rather than sent empty; an exempt tax has no percent.
 */
internal object FdmsReceiptWire {
    fun receipt(r: IssuedReceipt): JsonValue.Obj {
        val p = r.receipt
        return obj(
            "receiptType" to str(p.type.specName),
            "receiptCurrency" to str(p.currency.uppercase()),
            "receiptCounter" to num(r.receiptCounter),
            "receiptGlobalNo" to num(r.globalNo),
            "invoiceNo" to str(p.invoiceNo),
            "buyerData" to p.buyer?.let(::buyer),
            "receiptNotes" to p.notes?.let(::str),
            "receiptDate" to str(SignatureStrings.formatDateTime(r.date)),
            "creditDebitNote" to p.original?.let(::original),
            "receiptLinesTaxInclusive" to JsonValue.Bool(p.pricesIncludeTax),
            "receiptLines" to JsonValue.Arr(p.lines.map(::line)),
            "receiptTaxes" to JsonValue.Arr(p.taxes.map(::tax)),
            "receiptPayments" to JsonValue.Arr(
                p.payments.map { obj("moneyTypeCode" to str(it.moneyType.specName), "paymentAmount" to dec(it.amount)) },
            ),
            "receiptTotal" to dec(p.total),
            "receiptPrintForm" to str(p.printForm.specName),
            "receiptDeviceSignature" to obj("hash" to str(r.signature.hash), "signature" to str(r.signature.signature)),
        )
    }

    /** Non-zero [counters] in signing order. */
    fun counters(counters: List<FiscalCounter>): JsonValue.Arr = JsonValue.Arr(
        SignatureStrings.sortedCounters(counters).map { c ->
            obj(
                "fiscalCounterType" to str(c.type.specName),
                "fiscalCounterCurrency" to str(c.currency.uppercase()),
                "fiscalCounterTaxID" to c.taxId?.let(::num),
                "fiscalCounterTaxPercent" to c.taxPercent?.let(::dec),
                "fiscalCounterMoneyType" to c.moneyType?.let { str(it.specName) },
                "fiscalCounterValue" to dec(c.value),
            )
        },
    )

    private fun line(l: PreparedLine) = obj(
        "receiptLineType" to str(l.type.specName),
        "receiptLineNo" to num(l.lineNo),
        "receiptLineHSCode" to l.hsCode?.let(::str),
        "receiptLineName" to str(l.name),
        "receiptLinePrice" to l.unitPrice?.let(::dec),
        "receiptLineQuantity" to dec(l.quantity),
        "receiptLineTotal" to dec(l.total),
        "taxPercent" to l.taxPercent?.let(::dec),
        "taxID" to num(l.taxId),
    )

    private fun tax(t: ReceiptTax) = obj(
        "taxPercent" to t.taxPercent?.let(::dec),
        "taxID" to num(t.taxId),
        "taxAmount" to dec(t.taxAmount),
        "salesAmountWithTax" to dec(t.salesAmountWithTax),
    )

    private fun buyer(b: Buyer) = obj(
        "buyerRegisterName" to str(b.registerName),
        "buyerTradeName" to b.tradeName?.let(::str),
        "vatNumber" to b.vatNumber?.let(::str),
        "buyerTIN" to str(b.tin),
        "buyerContacts" to b.contacts?.let { obj("phoneNo" to it.phoneNo?.let(::str), "email" to it.email?.let(::str)) }?.takeIf { it.fields.isNotEmpty() },
        "buyerAddress" to b.address?.let {
            obj(
                "province" to it.province?.let(::str), "city" to it.city?.let(::str), "street" to it.street?.let(::str),
                "houseNo" to it.houseNo?.let(::str), "district" to it.district?.let(::str),
            )
        }?.takeIf { it.fields.isNotEmpty() },
    )

    private fun original(o: OriginalReceipt) = when (o) {
        is OriginalReceipt.ById -> obj("receiptID" to num(o.receiptId))
        is OriginalReceipt.ByDevice -> obj(
            "deviceID" to num(o.deviceId),
            "receiptGlobalNo" to num(o.receiptGlobalNo),
            "fiscalDayNo" to num(o.fiscalDayNo),
        )
    }

    /** Leaves out absent fields. */
    fun obj(vararg fields: Pair<String, JsonValue?>): JsonValue.Obj =
        JsonValue.Obj(LinkedHashMap<String, JsonValue>().apply { fields.forEach { (k, v) -> if (v != null) put(k, v) } })

    fun str(value: String) = JsonValue.Str(value)
    fun num(value: Number) = JsonValue.Num(value.toString())
    fun dec(value: BigDecimal) = JsonValue.Num(value.toPlainString())
}
