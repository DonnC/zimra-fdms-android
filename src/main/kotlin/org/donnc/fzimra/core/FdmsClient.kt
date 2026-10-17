package org.donnc.fzimra.core

import java.math.BigDecimal
import java.net.URLEncoder
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Talks to ZIMRA's Fiscal Device Gateway for one device.
 *
 * Calls block until ZIMRA answers (at most about 30 seconds each, plus
 * retries): make them off the main thread. Requests that only read, and
 * pings, are retried after network and server errors; requests that change
 * something at ZIMRA are not.
 *
 * @param identity how the device identifies itself.
 * @param transport how requests reach ZIMRA; usually an [HttpsTransport]
 *   holding the device key.
 * @param baseUrl the gateway; [FdmsEnvironment.TEST] or
 *   [FdmsEnvironment.PRODUCTION].
 * @param clock when given, kept in step with ZIMRA's clock from the time on
 *   each answer, so receipts are never dated ahead of ZIMRA.
 * @param retries how many times a read or ping is repeated after a failure.
 */
public class FdmsClient(
    public val identity: DeviceIdentity,
    private val transport: FdmsTransport,
    baseUrl: String = FdmsEnvironment.TEST.baseUrl,
    private val clock: ZimbabweClock? = null,
    private val retries: Int = 2,
) {
    private val baseUrl = baseUrl.trimEnd('/')
    internal var sleep: (Duration) -> Unit = { Thread.sleep(it.toMillis()) }

    init {
        if (!this.baseUrl.startsWith("https://")) throw FiscalValueException("the gateway must be reached over HTTPS")
        if (retries < 0) throw FiscalValueException("retries cannot be negative")
    }

    /** Who ZIMRA says the device belongs to; ask before [registerDevice]. */
    public fun verifyTaxpayerInformation(activationKey: String): TaxpayerInformation {
        val answer = call(
            "POST", public("VerifyTaxpayerInformation"),
            obj("activationKey" to str(activationKey), "deviceSerialNo" to str(identity.serialNo)),
            deviceAuthenticated = false, idempotent = true,
        )
        return FdmsWire.taxpayer(answer)
    }

    /**
     * Registers the device with ZIMRA and returns its certificate.
     *
     * @param certificateRequestPem the device's certificate request, from `DeviceCsr`.
     */
    public fun registerDevice(activationKey: String, certificateRequestPem: String): IssuedCertificate {
        val answer = call(
            "POST", public("RegisterDevice"),
            obj("activationKey" to str(activationKey), "certificateRequest" to str(certificateRequestPem)),
            deviceAuthenticated = false, idempotent = false,
        )
        return FdmsWire.certificate(answer)
    }

    /** A new device certificate, before the current one expires. */
    public fun issueCertificate(certificateRequestPem: String): IssuedCertificate {
        val answer = call(
            "POST", device("IssueCertificate"),
            obj("certificateRequest" to str(certificateRequestPem)),
            deviceAuthenticated = true, idempotent = false,
        )
        return FdmsWire.certificate(answer)
    }

    /** The device's configuration at ZIMRA. */
    public fun getConfig(): ZimraConfiguration =
        FdmsWire.configuration(call("GET", device("GetConfig"), null, deviceAuthenticated = true, idempotent = true))

    /** What ZIMRA knows of the device's fiscal days and receipts. */
    public fun getStatus(): ZimraStatus =
        FdmsWire.status(call("GET", device("GetStatus"), null, deviceAuthenticated = true, idempotent = true))

    /**
     * Reports the device as online.
     *
     * @return how often, in minutes, ZIMRA expects a ping.
     */
    public fun ping(): Int {
        val answer = call("POST", device("Ping"), null, deviceAuthenticated = true, idempotent = true)
        return FdmsWire.reportingFrequency(answer)
    }

    /**
     * ZIMRA's signing certificate and its chain.
     *
     * @param thumbprint the hex SHA-1 thumbprint of a particular certificate;
     *   the current one when `null`.
     */
    public fun getServerCertificate(thumbprint: String? = null): ServerCertificate {
        val query = thumbprint?.let { "?thumbprint=" + URLEncoder.encode(it, "UTF-8") }.orEmpty()
        val answer = call("GET", "/Public/v1/GetServerCertificate$query", null, deviceAuthenticated = false, idempotent = true)
        return FdmsWire.serverCertificate(answer)
    }

    /**
     * Tells ZIMRA that fiscal day [fiscalDayNo] opened at [openedAt] on the
     * device. ZIMRA accepts it only after the previous day is closed.
     */
    public fun openDay(fiscalDayNo: Int, openedAt: LocalDateTime): OpenedDay {
        val answer = call(
            "POST", device("OpenDay"),
            FdmsReceiptWire.obj(
                "fiscalDayOpened" to JsonValue.Str(SignatureStrings.formatDateTime(openedAt)),
                "fiscalDayNo" to FdmsReceiptWire.num(fiscalDayNo),
            ),
            deviceAuthenticated = true, idempotent = false,
        )
        return FdmsWire.openedDay(answer)
    }

    /**
     * Submits an issued receipt. Sending the same receipt again is safe: ZIMRA
     * answers with the receipt it already accepted.
     */
    public fun submitReceipt(receipt: IssuedReceipt): AcceptedReceipt {
        val answer = call(
            "POST", device("SubmitReceipt"),
            FdmsReceiptWire.obj("receipt" to FdmsReceiptWire.receipt(receipt)),
            deviceAuthenticated = true, idempotent = false,
        )
        return FdmsWire.acceptedReceipt(answer)
    }

    /**
     * Asks ZIMRA to close fiscal day [fiscalDayNo] with its [counters], signed
     * by the device. ZIMRA validates the day afterwards; follow with
     * [getStatus] for the outcome.
     *
     * @return ZIMRA's operation ID.
     */
    public fun closeDay(fiscalDayNo: Int, counters: List<FiscalCounter>, signature: DeviceSignature, receiptCounter: Int): String? {
        val answer = call(
            "POST", device("CloseDay"),
            FdmsReceiptWire.obj(
                "fiscalDayNo" to FdmsReceiptWire.num(fiscalDayNo),
                "fiscalDayCounters" to FdmsReceiptWire.counters(counters),
                "fiscalDayDeviceSignature" to FdmsReceiptWire.obj(
                    "hash" to JsonValue.Str(signature.hash),
                    "signature" to JsonValue.Str(signature.signature),
                ),
                "receiptCounter" to FdmsReceiptWire.num(receiptCounter),
            ),
            deviceAuthenticated = true, idempotent = false,
        )
        return FdmsWire.operationId(answer)
    }

    // -- plumbing ---------------------------------------------------------------

    private fun public(endpoint: String) = "/Public/v1/${identity.deviceId}/$endpoint"

    private fun device(endpoint: String) = "/Device/v1/${identity.deviceId}/$endpoint"

    private fun headers(withBody: Boolean): Map<String, String> = buildMap {
        put("Accept", "application/json")
        put("DeviceModelName", identity.modelName)
        // ZIMRA's documents name this header both ways; send both.
        put("DeviceModelVersionNo", identity.modelVersion)
        put("DeviceModelVersion", identity.modelVersion)
        if (withBody) put("Content-Type", "application/json")
    }

    private fun call(
        method: String,
        path: String,
        body: JsonValue.Obj?,
        deviceAuthenticated: Boolean,
        idempotent: Boolean,
    ): JsonValue.Obj {
        val text = body?.let(Json::write)
        val request = FdmsRequest(method, baseUrl + path, headers(text != null), text, deviceAuthenticated)
        val attempts = 1 + if (idempotent) retries else 0
        var attempt = 1
        while (true) {
            val started = System.nanoTime()
            val response = try {
                transport.execute(request)
            } catch (e: FdmsNetworkException) {
                if (attempt >= attempts) throw e
                sleep(backoff(attempt++))
                continue
            }
            noteServerTime(response, Duration.ofNanos(System.nanoTime() - started))
            if (response.status in 200..299) return FdmsWire.parse(response.body)
            val error = FdmsWire.error(response)
            if (error.isRetryable && attempt < attempts) {
                sleep(backoff(attempt++))
                continue
            }
            throw error
        }
    }

    private fun backoff(attempt: Int): Duration = Duration.ofMillis(500L shl (attempt - 1))

    private fun noteServerTime(response: FdmsResponse, roundTrip: Duration) {
        val clock = clock ?: return
        val date = response.headers["date"] ?: return
        val serverTime = try {
            ZonedDateTime.parse(date, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
        } catch (e: java.time.format.DateTimeParseException) {
            return
        }
        clock.adjustTo(serverTime, roundTrip)
    }

    private fun obj(vararg fields: Pair<String, JsonValue>) = JsonValue.Obj(linkedMapOf(*fields))

    private fun str(value: String) = JsonValue.Str(value)
}

/** Reads the gateway's JSON. */
internal object FdmsWire {
    fun parse(text: String): JsonValue.Obj {
        if (text.isBlank()) return JsonValue.Obj(emptyMap())
        return try {
            Json.parse(text) as? JsonValue.Obj ?: throw FdmsProtocolException("ZIMRA's answer is not a JSON object")
        } catch (e: FiscalStoreException) {
            throw FdmsProtocolException("ZIMRA's answer is not valid JSON", e)
        }
    }

    fun error(response: FdmsResponse): FdmsException {
        val problem = try {
            parse(response.body)
        } catch (e: FdmsProtocolException) {
            JsonValue.Obj(mapOf("detail" to JsonValue.Str(response.body.take(500))))
        }
        return FdmsException(
            status = response.status,
            errorCode = problem.optText("errorCode"),
            title = problem.optText("title"),
            detail = problem.optText("detail"),
            operationId = problem.optText("operationID") ?: response.headers["operationid"],
        )
    }

    fun taxpayer(o: JsonValue.Obj) = TaxpayerInformation(
        operationId = o.optText("operationID"),
        taxpayerName = o.text("taxPayerName"),
        taxpayerTin = o.text("taxPayerTIN"),
        vatNumber = o.optText("vatNumber")?.takeIf { it.isNotBlank() },
        branch = branch(o),
    )

    fun certificate(o: JsonValue.Obj) = IssuedCertificate(o.optText("operationID"), o.text("certificate"))

    fun configuration(o: JsonValue.Obj) = ZimraConfiguration(
        operationId = o.optText("operationID"),
        taxpayerName = o.text("taxPayerName"),
        taxpayerTin = o.text("taxPayerTIN"),
        vatNumber = o.optText("vatNumber")?.takeIf { it.isNotBlank() },
        serialNo = o.optText("deviceSerialNo"),
        branch = branch(o),
        operatingMode = enumByName(o.text("deviceOperatingMode"), OperatingMode.entries) { it.zimraName },
        dayMaxHours = o.int("taxPayerDayMaxHrs"),
        dayEndNotificationHours = o.int("taxpayerDayEndNotificationHrs"),
        taxes = o.list("applicableTaxes").map { tax(it.asObj()) },
        certificateValidTill = o.optText("certificateValidTill")?.let(::date),
        qrUrl = o.text("qrUrl"),
    )

    fun status(o: JsonValue.Obj) = ZimraStatus(
        operationId = o.optText("operationID"),
        dayStatus = enumByName(o.text("fiscalDayStatus"), ZimraDayStatus.entries) { it.zimraName },
        lastFiscalDayNo = o.optInt("lastFiscalDayNo"),
        lastReceiptGlobalNo = o.optInt("lastReceiptGlobalNo"),
        closingErrorCode = o.optText("fiscalDayClosingErrorCode"),
        dayClosed = o.optText("fiscalDayClosed")?.let(::dateTime),
        reconciliationMode = o.optText("fiscalDayReconciliationMode"),
        serverSignature = o.optObj("fiscalDayServerSignature")?.let(::signature),
        // ZIMRA's documents name the list both ways.
        counters = (o.list("fiscalDayCounters").ifEmpty { o.list("fiscalDayCounter") }).map { counter(it.asObj()) },
    )

    fun operationId(o: JsonValue.Obj): String? = o.optText("operationID")

    fun openedDay(o: JsonValue.Obj) = OpenedDay(o.optText("operationID"), o.int("fiscalDayNo"))

    fun acceptedReceipt(o: JsonValue.Obj) = AcceptedReceipt(
        operationId = o.optText("operationID"),
        receiptId = o.optText("receiptID")?.toLongOrNull() ?: throw FdmsProtocolException("ZIMRA's answer has no 'receiptID'"),
        serverDate = dateTime(o.text("serverDate")),
        signature = o.optObj("receiptServerSignature")?.let(::signature),
        findings = o.list("validationErrors").map {
            val v = it.asObj()
            ReceiptFinding(v.optText("validationErrorCode").orEmpty(), v.optText("validationErrorColor"), v.optText("validationErrorDescription"))
        },
    )

    private fun signature(o: JsonValue.Obj): ZimraSignature? {
        val signature = o.optText("signature") ?: return null
        return ZimraSignature(o.optText("hash"), signature, o.optText("certificateThumbprint"))
    }

    private fun counter(o: JsonValue.Obj): FiscalCounter = try {
        FiscalCounter(
            type = enumByName(o.text("fiscalCounterType"), FiscalCounterType.entries) { it.specName },
            currency = o.text("fiscalCounterCurrency"),
            value = o.optDecimal("fiscalCounterValue") ?: throw FdmsProtocolException("a counter has no value"),
            taxId = o.optInt("fiscalCounterTaxID"),
            taxPercent = o.optDecimal("fiscalCounterTaxPercent"),
            moneyType = o.optText("fiscalCounterMoneyType")?.let { name -> enumByName(name, MoneyType.entries) { it.specName } },
        )
    } catch (e: FiscalValueException) {
        throw FdmsProtocolException("ZIMRA sent an invalid counter: ${e.message}", e)
    }

    fun reportingFrequency(o: JsonValue.Obj): Int = o.int("reportingFrequency")

    fun serverCertificate(o: JsonValue.Obj) = ServerCertificate(
        chainPem = o.list("certificate").map { (it as? JsonValue.Str)?.value ?: throw FdmsProtocolException("'certificate' holds a non-text entry") },
        validTill = o.optText("certificateValidTill")?.let(::utcInstant),
    )

    private fun tax(o: JsonValue.Obj) = TaxDefinition(
        taxId = o.int("taxID"),
        name = o.text("taxName"),
        percent = o.optDecimal("taxPercent"),
        validFrom = (o.optText("taxValidFrom") ?: o.optText("validFrom"))?.let(::date),
        validTill = (o.optText("taxValidTill") ?: o.optText("validTill"))?.let(::date),
    )

    private fun branch(o: JsonValue.Obj) = BranchDetails(
        name = o.text("deviceBranchName"),
        address = o.optObj("deviceBranchAddress")?.let {
            Address(it.optText("province"), it.optText("city"), it.optText("street"), it.optText("houseNo"), it.optText("district"))
        },
        contacts = o.optObj("deviceBranchContacts")?.let { Contacts(it.optText("phoneNo"), it.optText("email")) },
    )

    private fun <E> enumByName(value: String, entries: List<E>, name: (E) -> String): E =
        entries.firstOrNull { name(it).equals(value, ignoreCase = true) }
            ?: throw FdmsProtocolException("ZIMRA sent an unknown value '$value'")

    // ZIMRA writes dates as "2026-10-17" or "2026-10-17T00:00:00".
    private fun date(text: String): LocalDate = parsing(text) { LocalDate.parse(text.take(10)) }

    private fun dateTime(text: String): LocalDateTime = parsing(text) { LocalDateTime.parse(text.take(19)) }

    private fun utcInstant(text: String): Instant = parsing(text) {
        when {
            text.length <= 10 -> LocalDate.parse(text).atStartOfDay().toInstant(ZoneOffset.UTC)
            text.endsWith("Z") || text.contains('+') || text.lastIndexOf('-') > 9 -> OffsetDateTime.parse(text).toInstant()
            else -> LocalDateTime.parse(text).toInstant(ZoneOffset.UTC)
        }
    }

    private fun <T> parsing(text: String, read: () -> T): T = try {
        read()
    } catch (e: java.time.format.DateTimeParseException) {
        throw FdmsProtocolException("ZIMRA sent an unreadable date '$text'", e)
    }

    // -- field access -----------------------------------------------------------

    private fun JsonValue.asObj() = this as? JsonValue.Obj ?: throw FdmsProtocolException("expected an object in ZIMRA's answer")
    private fun JsonValue.Obj.field(key: String) = fields[key]?.takeUnless { it == JsonValue.Null }
    private fun JsonValue.Obj.required(key: String) = field(key) ?: throw FdmsProtocolException("ZIMRA's answer has no '$key'")
    private fun JsonValue.Obj.optObj(key: String) = field(key)?.asObj()
    private fun JsonValue.Obj.text(key: String) = optText(key) ?: throw FdmsProtocolException("ZIMRA's answer has no '$key'")
    private fun JsonValue.Obj.optText(key: String): String? = when (val value = field(key)) {
        null -> null
        is JsonValue.Str -> value.value
        is JsonValue.Num -> value.text
        else -> throw FdmsProtocolException("'$key' in ZIMRA's answer is not text")
    }
    private fun JsonValue.Obj.int(key: String) = optInt(key) ?: throw FdmsProtocolException("ZIMRA's answer has no '$key'")
    private fun JsonValue.Obj.optInt(key: String): Int? = when (val value = field(key)) {
        null -> null
        is JsonValue.Num -> value.text.toIntOrNull() ?: throw FdmsProtocolException("'$key' in ZIMRA's answer is not a whole number")
        else -> throw FdmsProtocolException("'$key' in ZIMRA's answer is not a number")
    }
    private fun JsonValue.Obj.optDecimal(key: String): BigDecimal? = when (val value = field(key)) {
        null -> null
        is JsonValue.Num -> BigDecimal(value.text)
        is JsonValue.Str -> value.value.toBigDecimalOrNull() ?: throw FdmsProtocolException("'$key' in ZIMRA's answer is not a number")
        else -> throw FdmsProtocolException("'$key' in ZIMRA's answer is not a number")
    }
    private fun JsonValue.Obj.list(key: String): List<JsonValue> = when (val value = field(key)) {
        null -> emptyList()
        is JsonValue.Arr -> value.items
        else -> throw FdmsProtocolException("'$key' in ZIMRA's answer is not a list")
    }
}
