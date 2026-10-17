package org.donnc.fzimra.core

import java.math.BigDecimal
import java.security.MessageDigest
import java.time.LocalDateTime

/**
 * An in-memory Fiscal Device Gateway for one device, following ZIMRA's rules
 * closely enough to catch mistakes: it checks every receipt's signature and
 * sequence from the JSON it receives, keeps its own counters, checks the
 * close against them, and signs what it accepts. Tests only.
 */
internal class FakeFdms(
    private val deviceId: Int,
    private val devicePublicKey: java.security.PublicKey,
    pki: TestPki = TestPki(),
) : FdmsTransport {
    private val zimraKeys = TestPki.ecKeys()
    private val zimraCertificate = pki.deviceCertificate(zimraKeys.public, "ZIMRA FDMS Signing")
    private val zimraSigner = SoftwareSigner(zimraKeys, SignatureAlgorithm.ECDSA_P256_SHA256)
    val thumbprint: String = MessageDigest.getInstance("SHA-1").digest(zimraCertificate.encoded).joinToString("") { "%02X".format(it) }

    class Day(val number: Int, val opened: LocalDateTime) {
        val receipts = mutableListOf<Received>()
        var status = ZimraDayStatus.OPENED
        var closedAt: LocalDateTime? = null
        var mode: String? = null
        var deviceSignature: String? = null
        var closingError: String? = null
        var pendingClose: Pair<List<FiscalCounter>, String>? = null
    }

    data class Received(val globalNo: Int, val counter: Int, val hash: String, val signature: String, val receiptId: Long, val serverDate: LocalDateTime, val body: JsonValue.Obj)

    val days = mutableListOf<Day>()
    val requests = mutableListOf<FdmsRequest>()
    var isVatPayer = true
    var taxes: String = """[{"taxID":1,"taxPercent":15,"taxName":"Standard rated 15%","taxValidFrom":"2024-01-01"},
        {"taxID":2,"taxPercent":0,"taxName":"Zero rated 0%","taxValidFrom":"2024-01-01"},
        {"taxID":3,"taxName":"Exempt","taxValidFrom":"2024-01-01"}]"""
    var serverTime: LocalDateTime = LocalDateTime.of(2026, 10, 17, 9, 0, 0)

    /** Calls to fail before reaching the fake, as if offline. */
    var offlineFor = 0

    /** Findings to attach to receipts, by global number. */
    val findings = mutableMapOf<Int, Pair<String, String>>()

    /** Error codes to refuse receipts with, by global number. */
    val refuse = mutableMapOf<Int, String>()

    /** Error code the next close validation fails with. */
    var refuseNextClose: String? = null

    /** Report a refused close as the day opened again with an error code, as some gateways do. */
    var refuseAsReopened = false

    /** Send a wrong hash with ZIMRA's signatures. */
    var corruptSignatureHash = false

    /** Counters a ZIMRA officer set when closing a day in the portal. */
    var portalCounters: List<FiscalCounter>? = null

    private var nextReceiptId = 9_000L

    private val lastDay get() = days.lastOrNull()

    override fun execute(request: FdmsRequest): FdmsResponse {
        requests += request
        if (offlineFor > 0) {
            offlineFor--
            throw FdmsNetworkException("offline")
        }
        return handle(request)
    }

    /** ZIMRA validates pending closes, as it does a little after closeDay. */
    fun settle() {
        days.filter { it.status == ZimraDayStatus.CLOSE_INITIATED }.forEach { day ->
            val (counters, signature) = day.pendingClose!!
            val error = refuseNextClose?.also { refuseNextClose = null } ?: when {
                counters.map(::normal).toSet() != counters(day).map(::normal).toSet() -> "CountersMismatch"
                !FiscalSignature.verify(devicePublicKey, SignatureStrings.fiscalDay(deviceId, day.number, day.opened.toLocalDate(), counters(day)), signature) -> "BadCertificateSignature"
                else -> null
            }
            if (error != null) {
                day.status = if (refuseAsReopened) ZimraDayStatus.OPENED else ZimraDayStatus.CLOSE_FAILED
                day.closingError = error
            } else {
                day.status = ZimraDayStatus.CLOSED
                day.closedAt = serverTime
                day.mode = "Auto"
                day.deviceSignature = signature
            }
        }
    }

    /** A ZIMRA officer closes the open day in the portal. */
    fun closeInPortal() {
        val day = lastDay!!
        day.status = ZimraDayStatus.CLOSED
        day.closedAt = serverTime
        day.mode = "Manual"
    }

    private fun handle(request: FdmsRequest): FdmsResponse {
        val path = request.url.substringAfter("/v1/")
        val endpoint = path.substringAfterLast('/').substringBefore('?')
        val body = request.body?.let { Json.parse(it) as JsonValue.Obj }
        return when (endpoint) {
            "GetConfig" -> ok(
                """{"operationID":"op","taxPayerName":"Acme","taxPayerTIN":"2000000000",${if (isVatPayer) "\"vatNumber\":\"220000000\"," else ""}
                "deviceSerialNo":"SN","deviceBranchName":"Harare","deviceOperatingMode":"Online","taxPayerDayMaxHrs":24,
                "taxpayerDayEndNotificationHrs":2,"qrUrl":"https://fdmstest.zimra.co.zw","applicableTaxes":$taxes}""",
            )
            "GetStatus" -> ok(status())
            "OpenDay" -> openDay(body!!)
            "SubmitReceipt" -> submit(body!!.fields["receipt"] as JsonValue.Obj)
            "CloseDay" -> closeDay(body!!)
            "GetServerCertificate" -> ok("""{"certificate":[${Json.write(JsonValue.Str(TestPki.pem(zimraCertificate)))}],"certificateValidTill":"2030-01-01T00:00:00"}""")
            "Ping" -> ok("""{"reportingFrequency":5}""")
            else -> problem(404, null)
        }
    }

    private fun status(): String {
        val day = lastDay ?: return """{"fiscalDayStatus":"FiscalDayClosed"}"""
        val last = days.flatMap { it.receipts }.maxByOrNull { it.serverDate }?.globalNo
        val fields = mutableListOf(""""fiscalDayStatus":"${day.status.zimraName}"""", """"lastFiscalDayNo":${day.number}""")
        if (last != null) fields += """"lastReceiptGlobalNo":$last"""
        day.closingError?.let { fields += """"fiscalDayClosingErrorCode":"$it"""" }
        if (day.status == ZimraDayStatus.CLOSED) {
            val closedAt = day.closedAt!!
            fields += """"fiscalDayClosed":"${SignatureStrings.formatDateTime(closedAt)}""""
            fields += """"fiscalDayReconciliationMode":"${day.mode}""""
            val canonical = SignatureStrings.fiscalDayServer(deviceId, day.number, day.opened.toLocalDate(), closedAt, day.mode!!, counters(day), day.deviceSignature)
            fields += """"fiscalDayServerSignature":${signed(canonical)}"""
            if (day.mode == "Manual") fields += """"fiscalDayCounters":${Json.write(FdmsReceiptWire.counters(counters(day)))}"""
        }
        return "{" + fields.joinToString(",") + "}"
    }

    private fun openDay(body: JsonValue.Obj): FdmsResponse {
        val previous = lastDay
        if (previous != null && previous.status != ZimraDayStatus.CLOSED) return problem(422, "FISC01")
        val number = (body.fields["fiscalDayNo"] as JsonValue.Num).text.toInt()
        if (number != (previous?.number ?: 0) + 1) return problem(422, "FISC01")
        val opened = LocalDateTime.parse((body.fields["fiscalDayOpened"] as JsonValue.Str).value)
        days += Day(number, opened)
        return ok("""{"operationID":"op-open","fiscalDayNo":$number}""")
    }

    private fun submit(r: JsonValue.Obj): FdmsResponse {
        val day = lastDay
        if (day == null || day.status != ZimraDayStatus.OPENED) return problem(422, "RCPT01")
        val globalNo = r.int("receiptGlobalNo")
        val hash = (r.fields["receiptDeviceSignature"] as JsonValue.Obj).text("hash")
        val signature = (r.fields["receiptDeviceSignature"] as JsonValue.Obj).text("signature")
        days.flatMap { it.receipts }.firstOrNull { it.globalNo == globalNo && it.hash == hash }?.let { return accepted(it) }
        refuse.remove(globalNo)?.let { return problem(422, it) }

        val previous = day.receipts.lastOrNull()
        val allPrevious = days.flatMap { it.receipts }.lastOrNull()
        val counter = r.int("receiptCounter")
        require(counter == (previous?.counter ?: 0) + 1) { "RCPT011: counter $counter after ${previous?.counter}" }
        require(globalNo == (allPrevious?.globalNo ?: 0) + 1 || (globalNo == 1 && previous == null)) { "RCPT012: global $globalNo" }
        val date = LocalDateTime.parse(r.text("receiptDate"))
        val taxes = (r.fields["receiptTaxes"] as JsonValue.Arr).items.map {
            val t = it as JsonValue.Obj
            ReceiptTax(t.int("taxID"), t.optDec("taxPercent"), t.dec("taxAmount"), t.dec("salesAmountWithTax"))
        }
        val canonical = SignatureStrings.receipt(
            deviceId, ReceiptType.entries.first { it.specName == r.text("receiptType") }, r.text("receiptCurrency"),
            globalNo, date, r.dec("receiptTotal"), taxes, previous?.hash,
        )
        require(Sha256.base64(canonical) == hash) { "the receipt's hash does not match its content" }
        require(FiscalSignature.verify(devicePublicKey, canonical, signature)) { "RCPT020: bad device signature" }

        serverTime = serverTime.plusSeconds(1)
        val received = Received(globalNo, counter, hash, signature, nextReceiptId++, serverTime, r)
        day.receipts += received
        return accepted(received)
    }

    private fun accepted(r: Received): FdmsResponse {
        val canonical = SignatureStrings.receiptServer(r.signature, r.receiptId, r.serverDate)
        val finding = findings[r.globalNo]?.let {
            ""","validationErrors":[{"validationErrorCode":"${it.first}","validationErrorColor":"${it.second}","validationErrorDescription":"test"}]"""
        }.orEmpty()
        return ok(
            """{"operationID":"op-r","receiptID":${r.receiptId},"serverDate":"${SignatureStrings.formatDateTime(r.serverDate)}",
            "receiptServerSignature":${signed(canonical)}$finding}""",
        )
    }

    private fun closeDay(body: JsonValue.Obj): FdmsResponse {
        val day = lastDay ?: return problem(422, "FISC04")
        if (day.status == ZimraDayStatus.CLOSE_INITIATED) return problem(422, "FISC03")
        if (day.status != ZimraDayStatus.OPENED && day.status != ZimraDayStatus.CLOSE_FAILED) return problem(422, "FISC04")
        require(body.int("fiscalDayNo") == day.number)
        require(body.int("receiptCounter") == day.receipts.size) { "receiptCounter ${body.int("receiptCounter")} for ${day.receipts.size} receipts" }
        val counters = (body.fields["fiscalDayCounters"] as JsonValue.Arr).items.map { counter(it as JsonValue.Obj) }
        val signature = (body.fields["fiscalDayDeviceSignature"] as JsonValue.Obj).text("signature")
        day.status = ZimraDayStatus.CLOSE_INITIATED
        day.closingError = null
        day.pendingClose = counters to signature
        return ok("""{"operationID":"op-close"}""")
    }

    /** The day's counters, as ZIMRA adds them up from the receipts it received. */
    fun counters(day: Day): List<FiscalCounter> {
        if (day.mode == "Manual") portalCounters?.let { return it.map(::normal) }
        val sums = linkedMapOf<Triple<FiscalCounterType, String, Pair<Int?, Any?>>, BigDecimal>()
        fun add(type: FiscalCounterType, currency: String, taxId: Int?, qualifier: Any?, value: BigDecimal) {
            val key = Triple(type, currency, taxId to qualifier)
            sums[key] = (sums[key] ?: BigDecimal.ZERO).add(value)
        }
        day.receipts.forEach { received ->
            val r = received.body
            val currency = r.text("receiptCurrency")
            val (sale, tax) = when (r.text("receiptType")) {
                "CreditNote" -> FiscalCounterType.CREDIT_NOTE_BY_TAX to FiscalCounterType.CREDIT_NOTE_TAX_BY_TAX
                "DebitNote" -> FiscalCounterType.DEBIT_NOTE_BY_TAX to FiscalCounterType.DEBIT_NOTE_TAX_BY_TAX
                else -> FiscalCounterType.SALE_BY_TAX to FiscalCounterType.SALE_TAX_BY_TAX
            }
            (r.fields["receiptTaxes"] as JsonValue.Arr).items.forEach {
                val t = it as JsonValue.Obj
                val percent = t.optDec("taxPercent")
                add(sale, currency, t.int("taxID"), percent, t.dec("salesAmountWithTax"))
                if (percent != null) add(tax, currency, t.int("taxID"), percent, t.dec("taxAmount"))
            }
            (r.fields["receiptPayments"] as JsonValue.Arr).items.forEach {
                val p = it as JsonValue.Obj
                add(FiscalCounterType.BALANCE_BY_MONEY_TYPE, currency, null, MoneyType.entries.first { m -> m.specName == p.text("moneyTypeCode") }, p.dec("paymentAmount"))
            }
        }
        return sums.filterValues { it.signum() != 0 }.map { (key, value) ->
            val (type, currency, q) = key
            if (type.isByTax) FiscalCounter(type, currency, value, q.first, q.second as BigDecimal?)
            else FiscalCounter(type, currency, value, moneyType = q.second as MoneyType)
        }.map(::normal)
    }

    private fun normal(c: FiscalCounter) = c.copy(value = c.value.setScale(2), taxPercent = c.taxPercent?.setScale(2))

    private fun counter(o: JsonValue.Obj): FiscalCounter {
        val type = FiscalCounterType.entries.first { it.specName == o.text("fiscalCounterType") }
        val c = FiscalCounter(
            type, o.text("fiscalCounterCurrency"), o.dec("fiscalCounterValue"),
            o.fields["fiscalCounterTaxID"]?.let { (it as JsonValue.Num).text.toInt() },
            o.optDec("fiscalCounterTaxPercent"),
            (o.fields["fiscalCounterMoneyType"] as JsonValue.Str?)?.let { m -> MoneyType.entries.first { it.specName == m.value } },
        )
        return normal(c)
    }

    private fun signed(canonical: String): String {
        val signature = FiscalSignature.sign(zimraSigner, canonical)
        val hash = if (corruptSignatureHash) Sha256.base64("something else") else signature.hash
        return """{"hash":"$hash","signature":"${signature.signature}","certificateThumbprint":"$thumbprint"}"""
    }

    private fun ok(body: String) = FdmsResponse(200, mapOf("date" to "Sat, 17 Oct 2026 07:00:00 GMT"), body)

    private fun problem(status: Int, code: String?) = FdmsResponse(
        status, emptyMap(), """{"type":"t","title":"refused","status":$status${code?.let { ",\"errorCode\":\"$it\"" }.orEmpty()}}""",
    )

    private fun JsonValue.Obj.text(key: String) = (fields[key] as JsonValue.Str).value
    private fun JsonValue.Obj.int(key: String) = (fields[key] as JsonValue.Num).text.toInt()
    private fun JsonValue.Obj.dec(key: String) = BigDecimal((fields[key] as JsonValue.Num).text)
    private fun JsonValue.Obj.optDec(key: String) = (fields[key] as JsonValue.Num?)?.let { BigDecimal(it.text) }
}
