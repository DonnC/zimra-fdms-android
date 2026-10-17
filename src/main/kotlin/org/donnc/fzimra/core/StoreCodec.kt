package org.donnc.fzimra.core

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDateTime

/**
 * Converts fiscal state and receipt records to and from JSON for fzimra's own
 * stores. Every stored document carries a `format` number so later versions
 * can read what earlier ones wrote.
 */
internal object StoreCodec {
    const val FORMAT = 1

    fun encodeSnapshot(snapshot: FiscalSnapshot): String = Json.write(
        obj(
            "format" to num(FORMAT),
            "version" to num(snapshot.version),
            "state" to state(snapshot.state),
            "outbox" to JsonValue.Arr(snapshot.outbox.map(::issued)),
        ),
    )

    fun decodeSnapshot(text: String): FiscalSnapshot = decode(text) {
        FiscalSnapshot(long("version"), state(obj("state")), list("outbox").map { issued(it.asObj()) })
    }

    fun encodeRecord(record: ReceiptRecord): String = Json.write(
        obj(
            "format" to num(FORMAT),
            "receipt" to issued(record.receipt),
            "status" to str(record.status.name),
            "zimraReceiptId" to record.zimraReceiptId?.let(::num),
            "serverDate" to record.serverDate?.let { str(it.toString()) },
            "validations" to JsonValue.Arr(
                record.validations.map { obj("code" to str(it.code), "colour" to opt(it.colour), "message" to opt(it.message)) },
            ),
            "updatedAt" to str(record.updatedAt.toString()),
        ),
    )

    fun decodeRecord(text: String): ReceiptRecord = decode(text) {
        ReceiptRecord(
            receipt = issued(obj("receipt")),
            status = enumValueOf(text("status")),
            zimraReceiptId = optLong("zimraReceiptId"),
            serverDate = optText("serverDate")?.let(LocalDateTime::parse),
            validations = list("validations").map {
                val v = it.asObj()
                ZimraValidation(v.text("code"), v.optText("colour"), v.optText("message"))
            },
            updatedAt = Instant.parse(text("updatedAt")),
        )
    }

    // -- encoding ---------------------------------------------------------------

    private fun state(s: DeviceState) = obj(
        "deviceId" to num(s.deviceId),
        "lastGlobalNo" to num(s.lastGlobalNo),
        "lastReceiptDate" to s.lastReceiptDate?.let { str(it.toString()) },
        "lastFiscalDayNo" to num(s.lastFiscalDayNo),
        "currentDay" to s.currentDay?.let(::day),
        "closingDays" to JsonValue.Arr(s.closingDays.map(::day)),
    )

    private fun day(d: FiscalDayState) = obj(
        "fiscalDayNo" to num(d.fiscalDayNo),
        "openedAt" to str(d.openedAt.toString()),
        "status" to str(d.status.name),
        "receiptCounter" to num(d.receiptCounter),
        "lastReceiptHash" to opt(d.lastReceiptHash),
        "counters" to JsonValue.Arr(d.counters.map(::counter)),
        "closeReason" to opt(d.closeReason?.name),
    )

    private fun counter(c: FiscalCounter) = obj(
        "type" to str(c.type.name),
        "currency" to str(c.currency),
        "value" to dec(c.value),
        "taxId" to c.taxId?.let(::num),
        "taxPercent" to c.taxPercent?.let(::dec),
        "moneyType" to c.moneyType?.let { str(it.name) },
    )

    private fun issued(r: IssuedReceipt) = obj(
        "deviceId" to num(r.deviceId),
        "fiscalDayNo" to num(r.fiscalDayNo),
        "receiptCounter" to num(r.receiptCounter),
        "globalNo" to num(r.globalNo),
        "date" to str(r.date.toString()),
        "receipt" to prepared(r.receipt),
        "previousReceiptHash" to opt(r.previousReceiptHash),
        "hash" to str(r.signature.hash),
        "signature" to str(r.signature.signature),
        "qrCode" to str(r.qrCode),
        "verificationCode" to str(r.verificationCode),
    )

    private fun prepared(p: PreparedReceipt) = obj(
        "type" to str(p.type.name),
        "currency" to str(p.currency),
        "invoiceNo" to str(p.invoiceNo),
        "pricesIncludeTax" to JsonValue.Bool(p.pricesIncludeTax),
        "printForm" to str(p.printForm.name),
        "lines" to JsonValue.Arr(p.lines.map(::line)),
        "taxes" to JsonValue.Arr(p.taxes.map(::tax)),
        "payments" to JsonValue.Arr(p.payments.map { obj("moneyType" to str(it.moneyType.name), "amount" to dec(it.amount)) }),
        "total" to dec(p.total),
        "totalTax" to dec(p.totalTax),
        "change" to dec(p.change),
        "buyer" to p.buyer?.let(::buyer),
        "notes" to opt(p.notes),
        "original" to p.original?.let(::original),
    )

    private fun line(l: PreparedLine) = obj(
        "lineNo" to num(l.lineNo),
        "type" to str(l.type.name),
        "name" to str(l.name),
        "quantity" to dec(l.quantity),
        "unitPrice" to l.unitPrice?.let(::dec),
        "total" to dec(l.total),
        "taxId" to num(l.taxId),
        "taxPercent" to l.taxPercent?.let(::dec),
        "hsCode" to opt(l.hsCode),
    )

    private fun tax(t: ReceiptTax) = obj(
        "taxId" to num(t.taxId),
        "taxPercent" to t.taxPercent?.let(::dec),
        "taxAmount" to dec(t.taxAmount),
        "salesAmountWithTax" to dec(t.salesAmountWithTax),
    )

    private fun buyer(b: Buyer) = obj(
        "registerName" to str(b.registerName),
        "tin" to str(b.tin),
        "vatNumber" to opt(b.vatNumber),
        "tradeName" to opt(b.tradeName),
        "contacts" to b.contacts?.let { obj("phoneNo" to opt(it.phoneNo), "email" to opt(it.email)) },
        "address" to b.address?.let {
            obj(
                "province" to opt(it.province), "city" to opt(it.city), "street" to opt(it.street),
                "houseNo" to opt(it.houseNo), "district" to opt(it.district),
            )
        },
    )

    private fun original(o: OriginalReceipt) = when (o) {
        is OriginalReceipt.ById -> obj("kind" to str("id"), "receiptId" to num(o.receiptId))
        is OriginalReceipt.ByDevice -> obj(
            "kind" to str("device"),
            "deviceId" to num(o.deviceId),
            "receiptGlobalNo" to num(o.receiptGlobalNo),
            "fiscalDayNo" to num(o.fiscalDayNo),
        )
    }

    private fun obj(vararg fields: Pair<String, JsonValue?>) =
        JsonValue.Obj(fields.associate { (key, value) -> key to (value ?: JsonValue.Null) })

    private fun str(value: String) = JsonValue.Str(value)
    private fun opt(value: String?) = value?.let(::str)
    private fun num(value: Number) = JsonValue.Num(value.toString())
    private fun dec(value: BigDecimal) = JsonValue.Str(value.toPlainString())

    // -- decoding ---------------------------------------------------------------

    private fun <T> decode(text: String, read: JsonValue.Obj.() -> T): T {
        val root = Json.parse(text).asObj()
        val format = root.int("format")
        if (format != FORMAT) throw FiscalStoreException("stored data has format $format; this version reads format $FORMAT")
        return try {
            root.read()
        } catch (e: FiscalStoreException) {
            throw e
        } catch (e: RuntimeException) {
            throw FiscalStoreException("stored data is incomplete or invalid: ${e.message}", e)
        }
    }

    private fun state(o: JsonValue.Obj) = DeviceState(
        deviceId = o.int("deviceId"),
        lastGlobalNo = o.int("lastGlobalNo"),
        lastReceiptDate = o.optText("lastReceiptDate")?.let(LocalDateTime::parse),
        lastFiscalDayNo = o.int("lastFiscalDayNo"),
        currentDay = o.optObj("currentDay")?.let(::day),
        closingDays = o.list("closingDays").map { day(it.asObj()) },
    )

    private fun day(o: JsonValue.Obj) = FiscalDayState(
        fiscalDayNo = o.int("fiscalDayNo"),
        openedAt = LocalDateTime.parse(o.text("openedAt")),
        status = enumValueOf(o.text("status")),
        receiptCounter = o.int("receiptCounter"),
        lastReceiptHash = o.optText("lastReceiptHash"),
        counters = o.list("counters").map { counter(it.asObj()) },
        closeReason = o.optText("closeReason")?.let { enumValueOf<DayCloseReason>(it) },
    )

    private fun counter(o: JsonValue.Obj) = FiscalCounter(
        type = enumValueOf(o.text("type")),
        currency = o.text("currency"),
        value = o.dec("value"),
        taxId = o.optInt("taxId"),
        taxPercent = o.optDec("taxPercent"),
        moneyType = o.optText("moneyType")?.let { enumValueOf<MoneyType>(it) },
    )

    private fun issued(o: JsonValue.Obj) = IssuedReceipt(
        deviceId = o.int("deviceId"),
        fiscalDayNo = o.int("fiscalDayNo"),
        receiptCounter = o.int("receiptCounter"),
        globalNo = o.int("globalNo"),
        date = LocalDateTime.parse(o.text("date")),
        receipt = prepared(o.obj("receipt")),
        previousReceiptHash = o.optText("previousReceiptHash"),
        signature = DeviceSignature(o.text("hash"), o.text("signature")),
        qrCode = o.text("qrCode"),
        verificationCode = o.text("verificationCode"),
    )

    private fun prepared(o: JsonValue.Obj) = PreparedReceipt(
        type = enumValueOf(o.text("type")),
        currency = o.text("currency"),
        invoiceNo = o.text("invoiceNo"),
        pricesIncludeTax = o.bool("pricesIncludeTax"),
        printForm = enumValueOf(o.text("printForm")),
        lines = o.list("lines").map { line(it.asObj()) },
        taxes = o.list("taxes").map {
            val t = it.asObj()
            ReceiptTax(t.int("taxId"), t.optDec("taxPercent"), t.dec("taxAmount"), t.dec("salesAmountWithTax"))
        },
        payments = o.list("payments").map {
            val p = it.asObj()
            PreparedPayment(enumValueOf(p.text("moneyType")), p.dec("amount"))
        },
        total = o.dec("total"),
        totalTax = o.dec("totalTax"),
        change = o.dec("change"),
        buyer = o.optObj("buyer")?.let(::buyer),
        notes = o.optText("notes"),
        original = o.optObj("original")?.let(::original),
    )

    private fun line(o: JsonValue.Obj) = PreparedLine(
        lineNo = o.int("lineNo"),
        type = enumValueOf(o.text("type")),
        name = o.text("name"),
        quantity = o.dec("quantity"),
        unitPrice = o.optDec("unitPrice"),
        total = o.dec("total"),
        taxId = o.int("taxId"),
        taxPercent = o.optDec("taxPercent"),
        hsCode = o.optText("hsCode"),
    )

    private fun buyer(o: JsonValue.Obj) = Buyer(
        registerName = o.text("registerName"),
        tin = o.text("tin"),
        vatNumber = o.optText("vatNumber"),
        tradeName = o.optText("tradeName"),
        contacts = o.optObj("contacts")?.let { BuyerContacts(it.optText("phoneNo"), it.optText("email")) },
        address = o.optObj("address")?.let {
            BuyerAddress(it.optText("province"), it.optText("city"), it.optText("street"), it.optText("houseNo"), it.optText("district"))
        },
    )

    private fun original(o: JsonValue.Obj): OriginalReceipt = when (val kind = o.text("kind")) {
        "id" -> OriginalReceipt.ById(o.long("receiptId"))
        "device" -> OriginalReceipt.ByDevice(o.int("deviceId"), o.int("receiptGlobalNo"), o.int("fiscalDayNo"))
        else -> throw FiscalStoreException("unknown original receipt kind '$kind'")
    }

    private fun JsonValue.asObj() = this as? JsonValue.Obj ?: throw FiscalStoreException("expected an object")
    private fun JsonValue.Obj.field(key: String) = fields[key]?.takeUnless { it == JsonValue.Null }
    private fun JsonValue.Obj.required(key: String) = field(key) ?: throw FiscalStoreException("missing '$key'")
    private fun JsonValue.Obj.obj(key: String) = required(key).asObj()
    private fun JsonValue.Obj.optObj(key: String) = field(key)?.asObj()
    private fun JsonValue.Obj.text(key: String) = (required(key) as? JsonValue.Str)?.value ?: throw FiscalStoreException("'$key' is not text")
    private fun JsonValue.Obj.optText(key: String) = field(key)?.let { (it as? JsonValue.Str)?.value ?: throw FiscalStoreException("'$key' is not text") }
    private fun JsonValue.Obj.numberText(key: String) = (required(key) as? JsonValue.Num)?.text ?: throw FiscalStoreException("'$key' is not a number")
    private fun JsonValue.Obj.int(key: String) = numberText(key).toInt()
    private fun JsonValue.Obj.long(key: String) = numberText(key).toLong()
    private fun JsonValue.Obj.optInt(key: String) = field(key)?.let { int(key) }
    private fun JsonValue.Obj.optLong(key: String) = field(key)?.let { long(key) }
    private fun JsonValue.Obj.dec(key: String) = BigDecimal(text(key))
    private fun JsonValue.Obj.optDec(key: String) = optText(key)?.let(::BigDecimal)
    private fun JsonValue.Obj.bool(key: String) = (required(key) as? JsonValue.Bool)?.value ?: throw FiscalStoreException("'$key' is not true or false")
    private fun JsonValue.Obj.list(key: String) = (required(key) as? JsonValue.Arr)?.items ?: throw FiscalStoreException("'$key' is not a list")
}
