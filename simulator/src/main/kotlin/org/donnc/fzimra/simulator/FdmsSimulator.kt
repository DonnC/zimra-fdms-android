package org.donnc.fzimra.simulator

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.donnc.fzimra.core.DeviceIdentity
import org.donnc.fzimra.core.FdmsClient
import org.donnc.fzimra.core.FdmsNetworkException
import org.donnc.fzimra.core.FdmsRequest
import org.donnc.fzimra.core.FdmsResponse
import org.donnc.fzimra.core.FdmsTransport
import org.donnc.fzimra.core.ZimbabweClock
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.Signature
import java.security.cert.X509Certificate
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/** A finding the simulator reported on a receipt. */
public data class SimulatedFinding(val code: String, val colour: String, val description: String)

/** A receipt the simulator received. */
public data class SimulatedReceipt(
    val globalNo: Int,
    val receiptCounter: Int,
    val receiptId: Long,
    val type: String,
    val currency: String,
    val total: BigDecimal,
    val date: LocalDateTime,
    val serverDate: LocalDateTime,
    val findings: List<SimulatedFinding>,
)

/**
 * A fiscal day as the simulator holds it.
 *
 * @property reconciliationMode `Auto` when the device closed it, `Manual`
 *   when it was closed in the portal; `null` while open.
 */
public data class SimulatedDay(
    val fiscalDayNo: Int,
    val opened: LocalDateTime,
    val closed: LocalDateTime?,
    val reconciliationMode: String?,
    val receipts: List<SimulatedReceipt>,
)

/** A device as the simulator holds it. */
public data class SimulatedDevice(
    val deviceId: Int,
    val serialNo: String,
    val isRegistered: Boolean,
    /** As ZIMRA names it, for example `FiscalDayOpened`. */
    val dayStatus: String,
    val closingErrorCode: String?,
    val days: List<SimulatedDay>,
)

/**
 * A ZIMRA Fiscal Device Gateway in your app or tests: register devices, open
 * and close fiscal days and submit receipts without a ZIMRA account, and see
 * what ZIMRA would say.
 *
 * It follows ZIMRA's rules where a device can break them: certificate
 * requests and certificates (a device request must present a certificate the
 * simulator issued to that device, as mutual TLS ensures), receipt sequence,
 * chain and signature, dates, totals, taxes, signs, HS codes, credit and debit
 * notes, the day's counters and the device's signature on its close, which is
 * validated a little later, as ZIMRA does. Like ZIMRA, it reports its status
 * with the number of the latest-dated receipt, not the highest; answers a
 * resent receipt with the one it already has; and signs what it accepts.
 * [faults] injects the failures a device must survive.
 *
 * It is a development aid, not ZIMRA: test against ZIMRA's test environment
 * before going live.
 *
 * ```kotlin
 * val simulator = FdmsSimulator()
 * val client = simulator.client(identity, clientCertificate = { installedCertificate })
 * ```
 *
 * @param store where its state is kept; in memory unless given.
 * @param clock its local time, Zimbabwe time by default.
 */
public class FdmsSimulator(
    public val options: SimulatorOptions = SimulatorOptions(),
    private val store: SimulatorStore = SimulatorStore.InMemory(),
    private val clock: () -> LocalDateTime = { LocalDateTime.now(HARARE).truncatedTo(ChronoUnit.SECONDS) },
) {
    /** Failures to inject. */
    public val faults: SimulatorFaults = SimulatorFaults()

    private var ca: SimulatorCa
    private var nextReceiptId: Long
    private val devices = linkedMapOf<Int, SimDevice>()
    private var operation = 0
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    init {
        val saved = store.load()?.let { runCatching { SimulatorCodec.read(it) }.getOrNull() }
        if (saved != null) {
            ca = saved.ca
            nextReceiptId = saved.nextReceiptId
            saved.devices.forEach { devices[it.deviceId] = it }
        } else {
            ca = SimulatorCa.create()
            nextReceiptId = 1_000
            save()
        }
    }

    /** The certificate of the simulator's authority, in PEM form. */
    public val caCertificatePem: String get() = ca.pem

    /**
     * A transport to the simulator. [clientCertificate] stands in for mutual
     * TLS: it is the certificate the device presents, `null` before it has one.
     */
    public fun transport(clientCertificate: () -> X509Certificate?): FdmsTransport = FdmsTransport { request ->
        handle(request, if (request.deviceAuthenticated) clientCertificate() else null)
    }

    /** A client for [identity] talking to the simulator. */
    public fun client(identity: DeviceIdentity, clientCertificate: () -> X509Certificate?, clock: ZimbabweClock? = null): FdmsClient =
        FdmsClient(identity, transport(clientCertificate), BASE_URL, clock)

    /** Calls [listener] after every change to the simulator's state. */
    public fun addChangeListener(listener: () -> Unit) {
        listeners += listener
    }

    public fun removeChangeListener(listener: () -> Unit) {
        listeners -= listener
    }

    /** What the simulator holds for [deviceId], or `null` if it never registered. */
    @Synchronized
    public fun device(deviceId: Int): SimulatedDevice? = devices[deviceId]?.let { d ->
        SimulatedDevice(
            d.deviceId, d.serialNo, d.certificatePem != null, d.status, d.closingErrorCode,
            d.days.map { day ->
                SimulatedDay(
                    day.number, LocalDateTime.parse(day.opened), day.closed?.let(LocalDateTime::parse), day.mode,
                    day.receipts.map { r ->
                        SimulatedReceipt(
                            r.globalNo, r.body.int("receiptCounter"), r.receiptId, r.body.text("receiptType"), r.body.text("receiptCurrency"),
                            BigDecimal(r.body.text("receiptTotal")), LocalDateTime.parse(r.date), LocalDateTime.parse(r.serverDate),
                            r.findings.map { SimulatedFinding(it.text("validationErrorCode"), it.text("validationErrorColor"), it.text("validationErrorDescription")) },
                        )
                    },
                )
            },
        )
    }

    /** Validates a close that is waiting, without waiting for [SimulatorOptions.closeValidation]. */
    @Synchronized
    public fun settle() {
        devices.values.forEach { settle(it, force = true) }
        changed()
    }

    /**
     * A ZIMRA officer closes [deviceId]'s open fiscal day in the portal, as is
     * done for a day the device may not close itself.
     *
     * @return `false` if no day was open.
     */
    @Synchronized
    public fun closeInPortal(deviceId: Int): Boolean {
        val device = devices[deviceId] ?: return false
        val day = device.days.lastOrNull() ?: return false
        if (device.status == FISCAL_DAY_CLOSED) return false
        device.status = FISCAL_DAY_CLOSED
        device.closingErrorCode = null
        device.pendingClose = null
        day.closed = format(clock())
        day.mode = "Manual"
        day.serverSignature = ca.sign(
            "${device.deviceId}${day.number}${day.opened.take(10)}${day.closed}MANUAL${counterString(day.counters)}",
        )
        changed()
        return true
    }

    /** Forgets every device, as a new ZIMRA account would. */
    @Synchronized
    public fun reset() {
        devices.clear()
        faults.clear()
        changed()
    }

    // -- plumbing -----------------------------------------------------------------

    @Synchronized
    private fun handle(request: FdmsRequest, certificate: X509Certificate?): FdmsResponse {
        if (faults.offline) throw FdmsNetworkException("the simulator is offline")
        if (faults.dropConnections > 0) {
            faults.dropConnections--
            throw FdmsNetworkException("connection reset (simulated)")
        }
        val operationId = "SIM" + (++operation).toString().padStart(6, '0')
        val headers = mapOf(
            "content-type" to "application/json",
            // The same clock that dates receipts, so a client correcting its clock from this stays consistent.
            "date" to DateTimeFormatter.RFC_1123_DATE_TIME.format(clock().atZone(HARARE).withZoneSameInstant(ZoneOffset.UTC).plus(faults.dateSkew)),
            "operationid" to operationId,
        )
        if (faults.serverErrors > 0) {
            faults.serverErrors--
            return FdmsResponse(503, headers, """{"title":"Service Unavailable","status":503}""")
        }
        devices.values.forEach { settle(it, force = false) }
        val path = request.url.substringAfter("://").substringAfter('/').substringBefore('?').split('/').filter { it.isNotEmpty() }
        val body = request.body?.takeIf { it.isNotBlank() }?.let { Json.parseToJsonElement(it).jsonObject }
        val (answer, drop) = try {
            route(request.method, path, request.url, body, certificate, operationId)
        } catch (e: GatewayError) {
            changed()
            val problem = obj(
                "type" to JsonPrimitive("about:blank"), "title" to JsonPrimitive(e.code ?: "Error"),
                "status" to JsonPrimitive(e.status), "detail" to JsonPrimitive(e.message ?: ""),
            ).let { if (e.code != null) JsonObject(it + ("errorCode" to JsonPrimitive(e.code))) else it }
            return FdmsResponse(e.status, headers, problem.toString())
        }
        changed()
        if (drop) throw FdmsNetworkException("the connection dropped after the receipt was received (simulated)")
        return FdmsResponse(200, headers, answer.toString())
    }

    private fun route(method: String, path: List<String>, url: String, body: JsonObject?, certificate: X509Certificate?, op: String): Pair<JsonObject, Boolean> {
        if (path.size >= 3 && path[0] == "Public" && path[1] == "v1") {
            if (path[2] == "GetServerCertificate" && method == "GET") {
                return obj(
                    "certificate" to JsonArray(listOf(JsonPrimitive(ca.pem))),
                    "certificateValidTill" to JsonPrimitive("2036-01-01T00:00:00"),
                ) to false
            }
            val deviceId = path[2].toIntOrNull() ?: throw GatewayError(404, "NotFound", url)
            return when (path.getOrNull(3) to method) {
                "VerifyTaxpayerInformation" to "POST" -> verifyTaxpayer(body!!, op) to false
                "RegisterDevice" to "POST" -> register(deviceId, body!!, op) to false
                else -> throw GatewayError(404, "NotFound", url)
            }
        }
        if (path.size < 4 || path[0] != "Device" || path[1] != "v1") throw GatewayError(404, "NotFound", url)
        val deviceId = path[2].toIntOrNull() ?: throw GatewayError(404, "NotFound", url)
        val device = devices[deviceId] ?: throw GatewayError(422, "DEV01", "Device $deviceId is not registered")
        val presented = ca.authenticate(certificate)
        val cn = presented.subjectX500Principal.getName(javax.security.auth.x500.X500Principal.RFC2253)
            .split(",").firstOrNull { it.startsWith("CN=") }?.removePrefix("CN=")?.replace("\\", "")
        if (cn != device.commonName) throw GatewayError(401, "Unauthorized", "Certificate CN $cn does not match device $deviceId")
        val answer = when (path[3] to method) {
            "GetConfig" to "GET" -> config(device, op)
            "GetStatus" to "GET" -> status(device, op)
            "Ping" to "POST" -> obj("operationID" to JsonPrimitive(op), "reportingFrequency" to JsonPrimitive(5))
            "OpenDay" to "POST" -> openDay(device, body!!, op)
            "SubmitReceipt" to "POST" -> submit(device, body!!.obj("receipt"), presented, op)
            "CloseDay" to "POST" -> closeDay(device, body!!, op)
            "IssueCertificate" to "POST" -> issue(device, body!!, op)
            else -> throw GatewayError(404, "NotFound", url)
        }
        if (path[3] == "SubmitReceipt" && faults.dropAfterSubmit > 0) {
            faults.dropAfterSubmit--
            return answer to true
        }
        return answer to false
    }

    // -- public endpoints -----------------------------------------------------------

    private fun checkKey(key: String) {
        val accepted = if (options.activationKeys.isEmpty()) key.length == 8 else options.activationKeys.any { it.equals(key, ignoreCase = true) }
        if (!accepted) throw GatewayError(422, "DEV02", "Activation key is incorrect")
    }

    private fun branch(): List<Pair<String, JsonElement>> = listOfNotNull(
        "taxPayerName" to JsonPrimitive(options.taxpayerName),
        "taxPayerTIN" to JsonPrimitive(options.taxpayerTin),
        options.vatNumber?.let { "vatNumber" to JsonPrimitive(it) },
        "deviceBranchName" to JsonPrimitive(options.branchName),
        "deviceBranchAddress" to obj(
            "province" to JsonPrimitive("Harare"), "city" to JsonPrimitive("Harare"),
            "street" to JsonPrimitive("Samora Machel Ave"), "houseNo" to JsonPrimitive("1"),
        ),
        "deviceBranchContacts" to obj("phoneNo" to JsonPrimitive("0242000000"), "email" to JsonPrimitive("simulator@example.invalid")),
    )

    private fun verifyTaxpayer(body: JsonObject, op: String): JsonObject {
        checkKey(body.optText("activationKey").orEmpty())
        return JsonObject(linkedMapOf("operationID" to JsonPrimitive(op)) + branch())
    }

    private fun register(deviceId: Int, body: JsonObject, op: String): JsonObject {
        checkKey(body.optText("activationKey").orEmpty())
        val (certificate, cn) = try {
            ca.issueFromCsr(body.text("certificateRequest"), options.certificateValidity)
        } catch (e: IllegalArgumentException) {
            throw GatewayError(422, "DEV03", e.message ?: "Certificate request is invalid")
        }
        val suffix = "-" + deviceId.toString().padStart(10, '0')
        if (!cn.startsWith("ZIMRA-") || !cn.endsWith(suffix)) {
            throw GatewayError(422, "DEV03", "CSR CN $cn is not ZIMRA-<serial>$suffix")
        }
        val device = devices.getOrPut(deviceId) { SimDevice(deviceId, cn.removePrefix("ZIMRA-").removeSuffix(suffix)) }
        device.certificatePem = SimulatorCa.pem(certificate)
        return obj("operationID" to JsonPrimitive(op), "certificate" to JsonPrimitive(device.certificatePem!!))
    }

    // -- device endpoints ---------------------------------------------------------

    private fun issue(device: SimDevice, body: JsonObject, op: String): JsonObject {
        val (certificate, _) = try {
            ca.issueFromCsr(body.text("certificateRequest"), options.certificateValidity)
        } catch (e: IllegalArgumentException) {
            throw GatewayError(422, "DEV03", e.message ?: "Certificate request is invalid")
        }
        device.certificatePem = SimulatorCa.pem(certificate)
        return obj("operationID" to JsonPrimitive(op), "certificate" to JsonPrimitive(device.certificatePem!!))
    }

    private fun config(device: SimDevice, op: String): JsonObject {
        val validTill = device.certificatePem?.let { SimulatorCa.certificate(it).notAfter.toInstant().atZone(HARARE).toLocalDate().toString() }
        return JsonObject(
            linkedMapOf<String, JsonElement>("operationID" to JsonPrimitive(op)) + branch() + listOfNotNull(
                "deviceSerialNo" to JsonPrimitive(device.serialNo),
                "deviceOperatingMode" to JsonPrimitive("Online"),
                "taxPayerDayMaxHrs" to JsonPrimitive(options.dayMaxHours),
                "taxpayerDayEndNotificationHrs" to JsonPrimitive(options.dayEndNotificationHours),
                "applicableTaxes" to JsonArray(
                    options.taxes.map { t ->
                        JsonObject(
                            linkedMapOf<String, JsonElement>("taxID" to JsonPrimitive(t.taxId), "taxName" to JsonPrimitive(t.name), "taxValidFrom" to JsonPrimitive("2024-01-01")) +
                                (t.percent?.let { mapOf("taxPercent" to JsonPrimitive(it)) } ?: emptyMap()),
                        )
                    },
                ),
                validTill?.let { "certificateValidTill" to JsonPrimitive(it) },
                "qrUrl" to JsonPrimitive(options.qrUrl),
            ),
        )
    }

    private fun status(device: SimDevice, op: String): JsonObject {
        val receipts = device.days.flatMap { it.receipts }
        val latest = receipts.maxByOrNull { it.date } // as ZIMRA does: the latest-dated, not the highest number
        val day = device.days.lastOrNull()
        val fields = linkedMapOf<String, JsonElement>(
            "operationID" to JsonPrimitive(op),
            "fiscalDayStatus" to JsonPrimitive(device.status),
        )
        latest?.let { fields["lastReceiptGlobalNo"] = JsonPrimitive(it.globalNo) }
        day?.let { fields["lastFiscalDayNo"] = JsonPrimitive(it.number) }
        device.closingErrorCode?.let { fields["fiscalDayClosingErrorCode"] = JsonPrimitive(it) }
        if (day != null && device.status == FISCAL_DAY_CLOSED && day.closed != null) {
            fields["fiscalDayClosed"] = JsonPrimitive(day.closed!!)
            fields["fiscalDayReconciliationMode"] = JsonPrimitive(day.mode ?: "Auto")
            day.serverSignature?.let { fields["fiscalDayServerSignature"] = it }
            if (day.mode == "Manual") fields["fiscalDayCounters"] = countersJson(day.counters)
        }
        return JsonObject(fields)
    }

    private fun openDay(device: SimDevice, body: JsonObject, op: String): JsonObject {
        if (device.status != FISCAL_DAY_CLOSED) throw GatewayError(422, "FISC01", "Open day is not allowed")
        val last = device.days.lastOrNull()?.number ?: 0
        val number = body.optInt("fiscalDayNo") ?: (last + 1)
        if (number != last + 1) throw GatewayError(422, "FISC01", "fiscalDayNo must be ${last + 1}")
        device.days += SimDay(number, body.text("fiscalDayOpened"))
        device.status = FISCAL_DAY_OPENED
        device.closingErrorCode = null
        return obj("operationID" to JsonPrimitive(op), "fiscalDayNo" to JsonPrimitive(number))
    }

    private fun submit(device: SimDevice, r: JsonObject, certificate: X509Certificate, op: String): JsonObject {
        if (device.status != FISCAL_DAY_OPENED && device.status != FISCAL_DAY_CLOSE_FAILED) {
            throw GatewayError(422, "RCPT01", "Submitting receipt is not allowed. Fiscal day is closed or fiscal day close initiated")
        }
        for (field in REQUIRED) if (field !in r) throw GatewayError(422, "RCPT02", "$field is missing")
        val day = device.days.last()
        val all = device.days.flatMap { it.receipts }
        val globalNo = r.int("receiptGlobalNo")
        val hash = r.obj("receiptDeviceSignature").text("hash")
        all.firstOrNull { it.globalNo == globalNo && it.hash == hash }?.let { return answer(it, op) } // the same receipt again

        val findings = mutableListOf<JsonObject>()
        fun finding(code: String, colour: String, text: String) {
            findings += obj("validationErrorCode" to JsonPrimitive(code), "validationErrorColor" to JsonPrimitive(colour), "validationErrorDescription" to JsonPrimitive(text))
        }

        val previous = day.receipts.lastOrNull()
        // The global number follows the device's previous receipt, or starts again at 1 with the first receipt of a day.
        val last = all.lastOrNull()?.globalNo ?: 0
        val counter = r.int("receiptCounter")
        if (counter != day.receipts.size + 1) finding("RCPT011", if (counter > day.receipts.size + 1) "Grey" else "Red", "Receipt counter is not sequential.")
        if (globalNo != last + 1 && !(globalNo == 1 && day.receipts.isEmpty())) {
            finding("RCPT012", if (globalNo > last + 1) "Grey" else "Red", "Receipt global number is not sequential.")
        }
        val date = r.text("receiptDate")
        if (previous != null && date <= previous.date) finding("RCPT030", "Red", "Invoice date is earlier than previously submitted receipt date")
        if (date < day.opened) finding("RCPT014", "Yellow", "Receipt date is earlier than fiscal day opening date")
        if (date > format(clock().plus(options.futureTolerance))) finding("RCPT031", "Yellow", "Invoice is submitted with the future date")
        if (date > format(LocalDateTime.parse(day.opened).plusHours(options.dayMaxHours.toLong()))) {
            finding("RCPT041", "Yellow", "Invoice is issued after fiscal day end")
        }

        // The device's signature over ZIMRA's receipt string, chained to the previous receipt.
        val taxes = r.getValue("receiptTaxes").jsonArray.map { it.jsonObject }
        val canonical = buildString {
            append(device.deviceId)
            append(r.text("receiptType").uppercase(Locale.ROOT))
            append(r.text("receiptCurrency").uppercase(Locale.ROOT))
            append(globalNo)
            append(date)
            append(cents(r.text("receiptTotal")))
            taxes.sortedWith(compareBy({ it.int("taxID") }, { it.optText("taxCode").orEmpty() })).forEach {
                append(it.optText("taxCode").orEmpty())
                append(percent(it.optText("taxPercent")).orEmpty())
                append(cents(it.text("taxAmount")))
                append(cents(it.text("salesAmountWithTax")))
            }
            previous?.let { append(it.hash) }
        }
        val signature = r.obj("receiptDeviceSignature")
        val signedByDevice = signature.text("hash") == SimulatorCa.sha256(canonical) && verifies(certificate, canonical, signature.text("signature"))
        if (!signedByDevice) finding("RCPT020", "Red", "Invoice signature is not valid")

        // Totals, taxes, signs and HS codes.
        val lines = r.getValue("receiptLines").jsonArray.map { it.jsonObject }
        val payments = r.getValue("receiptPayments").jsonArray.map { it.jsonObject }
        val type = r.text("receiptType")
        val credit = type == "CreditNote"
        val total = cents(r.text("receiptTotal"))
        if (lines.isEmpty()) finding("RCPT016", "Red", "No receipt lines provided")
        if (taxes.isEmpty()) finding("RCPT017", "Red", "Taxes information is not provided")
        if (payments.isEmpty()) finding("RCPT018", "Red", "Payment information is not provided")
        val inclusive = r.getValue("receiptLinesTaxInclusive").jsonPrimitive.content == "true"
        val lineSum = lines.sumOf { cents(it.text("receiptLineTotal")) }
        val taxSum = taxes.sumOf { cents(it.text("taxAmount")) }
        // Tax-exclusive lines: the total adds the tax on top of the lines.
        if (total != (if (inclusive) lineSum else lineSum + taxSum)) {
            finding("RCPT019", "Red", "Invoice total amount is not equal to sum of all invoice lines")
        }
        if (total != taxes.sumOf { cents(it.text("salesAmountWithTax")) }) {
            finding("RCPT038", "Red", "Invoice total amount is not equal to sum of sales amount including tax in tax table")
        }
        if (total != payments.sumOf { cents(it.text("paymentAmount")) }) finding("RCPT039", "Red", "Invoice total amount is not equal to sum of all payment amounts")
        if ((credit && total > 0) || (!credit && total < 0)) finding("RCPT040", "Red", "Invoice total amount has the wrong sign")
        payments.forEach {
            val amount = cents(it.text("paymentAmount"))
            if ((credit && amount > 0) || (!credit && amount < 0)) finding("RCPT028", "Red", "Payment amount has the wrong sign")
        }
        val known = options.taxes.associate { it.taxId to percent(it.percent?.toPlainString()) }
        val groups = linkedMapOf<Pair<Int, String?>, Long>()
        lines.forEach { l ->
            val quantity = BigDecimal(l.text("receiptLineQuantity"))
            val lineTotal = cents(l.text("receiptLineTotal"))
            if (quantity.signum() <= 0) finding("RCPT023", "Red", "Invoice line quantity, must be positive")
            l.optText("receiptLinePrice")?.let { price ->
                if (cents(BigDecimal(price).multiply(quantity).toPlainString()) != lineTotal) {
                    finding("RCPT024", "Red", "Invoice line total is not equal to unit price * quantity")
                }
            }
            val saleSignOk = if (credit) lineTotal < 0 else lineTotal > 0
            if (l.text("receiptLineType") == "Sale" && !saleSignOk) finding("RCPT022", "Red", "Invoice sales line price has the wrong sign")
            val taxId = l.int("taxID")
            val linePercent = percent(l.optText("taxPercent"))
            if (taxId !in known || known[taxId] != linePercent) finding("RCPT025", "Red", "Invalid tax is used")
            val vatRate = l.optText("taxPercent")?.let(::BigDecimal)?.signum() == 1
            if (options.vatNumber == null && vatRate) finding("RCPT021", "Red", "VAT tax is used in invoice while taxpayer is not VAT taxpayer")
            if (options.vatNumber != null) {
                val hs = l.optText("receiptLineHSCode")
                val allowed = if (vatRate) setOf(4, 8) else setOf(8)
                when {
                    hs.isNullOrEmpty() -> finding("RCPT047", "Red", "HS code must be sent if taxpayer is a VAT payer")
                    hs.length !in allowed -> finding("RCPT048", "Red", "HS code length is invalid")
                }
            }
            groups.merge(taxId to linePercent, lineTotal, Long::plus)
        }
        taxes.forEach { t ->
            val key = t.int("taxID") to percent(t.optText("taxPercent"))
            val gross = groups[key] ?: 0L
            val rate = t.optText("taxPercent")?.let(::BigDecimal) ?: BigDecimal.ZERO
            // Tax-inclusive lines carry the tax in their total; tax-exclusive lines have it added on top.
            val divisor = if (inclusive) BigDecimal(100).add(rate) else BigDecimal(100)
            val expected = if (rate.signum() == 0) 0L else BigDecimal(gross).multiply(rate).divide(divisor, 0, RoundingMode.HALF_EVEN).toLong()
            val salesWithTax = cents(t.text("salesAmountWithTax"))
            val expectedSales = if (inclusive) gross else gross + expected
            if (cents(t.text("taxAmount")) != expected) finding("RCPT026", "Red", "Incorrectly calculated tax amount")
            if (salesWithTax != expectedSales) finding("RCPT027", "Red", "Incorrectly calculated total sales amount (including tax)")
        }
        if (type == "CreditNote" || type == "DebitNote") {
            if (r.optText("receiptNotes").isNullOrBlank()) finding("RCPT034", "Red", "Note for credit/debit note is not provided")
            val reference = r["creditDebitNote"]?.takeIf { it !is JsonNull }?.jsonObject
            if (reference == null) {
                finding("RCPT015", "Red", "Credited/debited invoice data is not provided")
            } else {
                val original = device.days.firstNotNullOfOrNull { d ->
                    d.receipts.firstOrNull { x ->
                        x.receiptId == reference.optText("receiptID")?.toLongOrNull() ||
                            (x.globalNo == reference.optInt("receiptGlobalNo") && d.number == reference.optInt("fiscalDayNo") && reference.optInt("deviceID") == device.deviceId)
                    }
                }
                if (original == null) {
                    finding("RCPT032", "Red", "Credit / debit note refers to non-existing invoice")
                } else if (original.body.text("receiptCurrency") != r.text("receiptCurrency")) {
                    finding("RCPT042", "Red", "Credit/debit note uses other currency than is used in the original invoice")
                }
            }
        } else if (r["creditDebitNote"]?.let { it !is JsonNull } == true) {
            finding("RCPT029", "Red", "Credited/debited invoice information provided for regular invoice")
        }
        r["buyerData"]?.takeIf { it !is JsonNull }?.jsonObject?.let { buyer ->
            if (buyer.optText("buyerRegisterName").isNullOrBlank() || buyer.optText("buyerTIN").isNullOrBlank()) {
                finding("RCPT043", "Red", "Mandatory buyer data fields are not provided")
            }
        }
        faults.flagNextReceipt?.let { (code, colour) ->
            finding(code, colour, "Flagged by the simulator's faults")
            faults.flagNextReceipt = null
        }

        val stored = SimReceipt(r, nextReceiptId++, findings, format(clock()))
        day.receipts += stored
        count(day, r)
        return answer(stored, op)
    }

    private fun answer(stored: SimReceipt, op: String): JsonObject = obj(
        "operationID" to JsonPrimitive(op),
        "receiptID" to JsonPrimitive(stored.receiptId),
        "serverDate" to JsonPrimitive(stored.serverDate),
        // ZIMRA signs receiptDeviceSignature ‖ receiptID ‖ serverDate.
        "receiptServerSignature" to ca.sign(stored.body.obj("receiptDeviceSignature").text("signature") + stored.receiptId + stored.serverDate),
        "validationErrors" to JsonArray(stored.findings),
    )

    /** Fiscal counters: amounts are added as sent; credit notes are already negative. */
    private fun count(day: SimDay, r: JsonObject) {
        val (byTax, taxByTax) = when (r.text("receiptType")) {
            "CreditNote" -> "CreditNoteByTax" to "CreditNoteTaxByTax"
            "DebitNote" -> "DebitNoteByTax" to "DebitNoteTaxByTax"
            else -> "SaleByTax" to "SaleTaxByTax"
        }
        val currency = r.text("receiptCurrency").uppercase(Locale.ROOT)
        r.getValue("receiptTaxes").jsonArray.map { it.jsonObject }.forEach { t ->
            val p = percent(t.optText("taxPercent"))
            day.counters.merge(CounterKey(byTax, currency, t.int("taxID"), p, null), cents(t.text("salesAmountWithTax")), Long::plus)
            day.counters.merge(CounterKey(taxByTax, currency, t.int("taxID"), p, null), cents(t.text("taxAmount")), Long::plus)
        }
        r.getValue("receiptPayments").jsonArray.map { it.jsonObject }.forEach { p ->
            day.counters.merge(CounterKey("BalanceByMoneyType", currency, null, null, p.text("moneyTypeCode")), cents(p.text("paymentAmount")), Long::plus)
        }
    }

    private fun sortedCounters(counters: Map<CounterKey, Long>): List<Pair<CounterKey, Long>> =
        counters.entries.filter { it.value != 0L }.map { it.key to it.value }.sortedWith(
            compareBy(
                { COUNTER_ORDER.indexOf(it.first.type) },
                { it.first.currency },
                { it.first.taxId ?: -1 },
                { it.first.moneyType?.let(MONEY_TYPES::indexOf) ?: -1 },
            ),
        )

    private fun counterString(counters: Map<CounterKey, Long>): String = sortedCounters(counters).joinToString("") { (k, v) ->
        k.type.uppercase(Locale.ROOT) + k.currency + (if (k.type == "BalanceByMoneyType") k.moneyType!!.uppercase(Locale.ROOT) else k.percent.orEmpty()) + v
    }

    private fun countersJson(counters: Map<CounterKey, Long>) = JsonArray(
        sortedCounters(counters).map { (k, v) ->
            JsonObject(
                linkedMapOf<String, JsonElement>("fiscalCounterType" to JsonPrimitive(k.type), "fiscalCounterCurrency" to JsonPrimitive(k.currency)) +
                    listOfNotNull(
                        k.taxId?.let { "fiscalCounterTaxID" to JsonPrimitive(it) },
                        k.percent?.let { "fiscalCounterTaxPercent" to JsonPrimitive(BigDecimal(it)) },
                        k.moneyType?.let { "fiscalCounterMoneyType" to JsonPrimitive(it) },
                        "fiscalCounterValue" to JsonPrimitive(BigDecimal.valueOf(v, 2)),
                    ),
            )
        },
    )

    private fun closeDay(device: SimDevice, body: JsonObject, op: String): JsonObject {
        if (device.status != FISCAL_DAY_OPENED && device.status != FISCAL_DAY_CLOSE_FAILED) {
            throw GatewayError(422, if (device.status == FISCAL_DAY_CLOSE_INITIATED) "FISC03" else "FISC04", "Closing day is not allowed")
        }
        val day = device.days.last()
        if (body.int("fiscalDayNo") != day.number) throw GatewayError(422, "FISC04", "fiscalDayNo is not the open day")
        if (day.receipts.any { r -> r.findings.any { it.text("validationErrorColor") in BLOCKING } }) {
            throw GatewayError(422, "ReceiptsWithValidationErrors", "Fiscal day holds Grey or Red receipts")
        }
        device.status = FISCAL_DAY_CLOSE_INITIATED
        device.closingErrorCode = null
        device.pendingClose = body
        device.closeRequestedAt = System.currentTimeMillis()
        return obj("operationID" to JsonPrimitive(op))
    }

    /** Validates a waiting close once [SimulatorOptions.closeValidation] has passed, or now if [force]. */
    private fun settle(device: SimDevice, force: Boolean) {
        val body = device.pendingClose ?: return
        if (!force && System.currentTimeMillis() - device.closeRequestedAt < options.closeValidation.toMillis()) return
        device.pendingClose = null
        val day = device.days.last()
        val canonical = "${device.deviceId}${day.number}${day.opened.take(10)}${counterString(day.counters)}"
        val signature = body.obj("fiscalDayDeviceSignature")
        val certificate = SimulatorCa.certificate(device.certificatePem!!)
        val signedByDevice = signature.text("hash") == SimulatorCa.sha256(canonical) && verifies(certificate, canonical, signature.text("signature"))
        val sent = body.getValue("fiscalDayCounters").jsonArray.map { it.jsonObject }.associate { c ->
            CounterKey(
                c.text("fiscalCounterType"), c.text("fiscalCounterCurrency").uppercase(Locale.ROOT), c.optInt("fiscalCounterTaxID"),
                percent(c.optText("fiscalCounterTaxPercent")), c.optText("fiscalCounterMoneyType"),
            ) to cents(c.text("fiscalCounterValue"))
        }
        val ours = day.counters.filterValues { it != 0L }
        val refusal = faults.refuseNextClose?.also { faults.refuseNextClose = null }
            ?: when {
                !signedByDevice -> "BadCertificateSignature"
                body.int("receiptCounter") != day.receipts.size || sent != ours -> "CountersMismatch"
                else -> null
            }
        if (refusal != null) {
            device.closingErrorCode = refusal
            device.status = if (faults.refuseCloseAsReopened) FISCAL_DAY_OPENED else FISCAL_DAY_CLOSE_FAILED
            return
        }
        device.status = FISCAL_DAY_CLOSED
        day.closed = format(clock())
        day.mode = "Auto"
        day.deviceSignature = signature.text("signature")
        // ZIMRA signs deviceID ‖ fiscalDayNo ‖ fiscalDayDate ‖ fiscalDayUpdated ‖ mode ‖ counters ‖ device signature.
        day.serverSignature = ca.sign("${device.deviceId}${day.number}${day.opened.take(10)}${day.closed}AUTO${counterString(day.counters)}${day.deviceSignature}")
    }

    private fun verifies(certificate: X509Certificate, canonical: String, signatureBase64: String): Boolean = try {
        Signature.getInstance(if (certificate.publicKey.algorithm == "RSA") "SHA256withRSA" else "SHA256withECDSA").run {
            initVerify(certificate.publicKey)
            update(canonical.toByteArray(Charsets.UTF_8))
            verify(Base64.getDecoder().decode(signatureBase64))
        }
    } catch (e: Exception) {
        false
    }

    private fun changed() {
        save()
        listeners.forEach { runCatching { it() } }
    }

    private fun save() {
        runCatching { store.save(SimulatorCodec.write(ca, nextReceiptId, devices.values)) }
    }

    public companion object {
        /** The address clients use for the simulator. Requests never leave the app. */
        public const val BASE_URL: String = "https://simulator.fzimra.invalid"

        private val HARARE: ZoneId = ZoneId.of("Africa/Harare")
        private val REQUIRED = listOf(
            "receiptType", "receiptCurrency", "receiptCounter", "receiptGlobalNo", "invoiceNo", "receiptDate",
            "receiptLines", "receiptTaxes", "receiptPayments", "receiptTotal", "receiptDeviceSignature",
        )
        private val BLOCKING = setOf("Grey", "Red")
        private val COUNTER_ORDER = listOf(
            "SaleByTax", "SaleTaxByTax", "CreditNoteByTax", "CreditNoteTaxByTax", "DebitNoteByTax", "DebitNoteTaxByTax", "BalanceByMoneyType",
        )
        private val MONEY_TYPES = listOf("Cash", "Card", "MobileWallet", "Coupon", "Credit", "BankTransfer", "Other")

        private val DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

        private fun format(time: LocalDateTime): String = time.format(DATE_TIME)

        private fun cents(amount: String): Long = BigDecimal(amount).movePointRight(2).setScale(0, RoundingMode.HALF_EVEN).toLong()

        private fun percent(value: String?): String? = value?.let { BigDecimal(it).setScale(2, RoundingMode.HALF_EVEN).toPlainString() }
    }
}
