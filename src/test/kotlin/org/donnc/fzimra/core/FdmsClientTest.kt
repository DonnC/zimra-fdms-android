package org.donnc.fzimra.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

class FdmsClientTest {
    private val identity = DeviceIdentity(321, "SN-001", "fzimra-test", "1.0")

    /** Answers from a queue and records every request. */
    private class Recording(vararg answers: () -> FdmsResponse) : FdmsTransport {
        val requests = mutableListOf<FdmsRequest>()
        private val queue = ArrayDeque(answers.toList())
        override fun execute(request: FdmsRequest): FdmsResponse {
            requests += request
            return queue.removeFirst()()
        }
    }

    private fun ok(body: String, headers: Map<String, String> = emptyMap()): () -> FdmsResponse = { FdmsResponse(200, headers, body) }
    private fun status(code: Int, body: String = ""): () -> FdmsResponse = { FdmsResponse(code, emptyMap(), body) }
    private val offline: () -> FdmsResponse = { throw FdmsNetworkException("no route") }

    private fun client(transport: FdmsTransport, clock: ZimbabweClock? = null, retries: Int = 2) =
        FdmsClient(identity, transport, "https://fdms.example/", clock, retries).also { it.sleep = {} }

    private val branchJson = """
        "deviceBranchName":"Harare Branch",
        "deviceBranchAddress":{"province":"Harare","city":"Harare","street":"Samora Machel","houseNo":"1","district":"CBD"},
        "deviceBranchContacts":{"phoneNo":"0242000000","email":"till@example.com"}
    """

    @Test
    fun verifiesTheTaxpayerWithoutTheDeviceCertificate() {
        val transport = Recording(
            ok("""{"operationID":"op-1","taxPayerName":"Acme","taxPayerTIN":"2000000000","vatNumber":"220000000",$branchJson}"""),
        )
        val info = client(transport).verifyTaxpayerInformation("ABCD1234")
        val request = transport.requests.single()
        assertEquals("POST", request.method)
        assertEquals("https://fdms.example/Public/v1/321/VerifyTaxpayerInformation", request.url)
        assertFalse(request.deviceAuthenticated)
        assertEquals("""{"activationKey":"ABCD1234","deviceSerialNo":"SN-001"}""", request.body)
        assertEquals("application/json", request.headers["Content-Type"])
        assertEquals("fzimra-test", request.headers["DeviceModelName"])
        assertEquals("1.0", request.headers["DeviceModelVersionNo"])
        assertEquals("1.0", request.headers["DeviceModelVersion"])
        assertEquals(
            TaxpayerInformation(
                "op-1", "Acme", "2000000000", "220000000",
                BranchDetails(
                    "Harare Branch",
                    Address("Harare", "Harare", "Samora Machel", "1", "CBD"),
                    Contacts("0242000000", "till@example.com"),
                ),
            ),
            info,
        )
    }

    @Test
    fun registersAndRenewsCertificates() {
        val transport = Recording(ok("""{"operationID":"op-2","certificate":"PEM-1"}"""), ok("""{"certificate":"PEM-2"}"""))
        val client = client(transport)
        assertEquals(IssuedCertificate("op-2", "PEM-1"), client.registerDevice("abcd1234", "CSR"))
        assertEquals(IssuedCertificate(null, "PEM-2"), client.issueCertificate("CSR-2"))
        val (register, renew) = transport.requests
        assertEquals("https://fdms.example/Public/v1/321/RegisterDevice", register.url)
        assertEquals("""{"activationKey":"abcd1234","certificateRequest":"CSR"}""", register.body)
        assertFalse(register.deviceAuthenticated)
        assertEquals("https://fdms.example/Device/v1/321/IssueCertificate", renew.url)
        assertEquals("""{"certificateRequest":"CSR-2"}""", renew.body)
        assertTrue(renew.deviceAuthenticated)
    }

    @Test
    fun readsTheConfiguration() {
        val transport = Recording(
            ok(
                """{"operationID":"op-3","taxPayerName":"Acme","taxPayerTIN":"2000000000","vatNumber":"220000000",
                "deviceSerialNo":"SN-001",$branchJson,"deviceOperatingMode":"Online","taxPayerDayMaxHrs":24,
                "taxpayerDayEndNotificationHrs":2,"certificateValidTill":"2027-03-01T00:00:00","qrUrl":"https://fdmstest.zimra.co.zw",
                "applicableTaxes":[
                  {"taxID":1,"taxPercent":15.00,"taxName":"Standard rated 15%","taxValidFrom":"2024-01-01"},
                  {"taxID":2,"taxPercent":0,"taxName":"Zero rated 0%","taxValidFrom":"2024-01-01T00:00:00","taxValidTill":"2030-12-31"},
                  {"taxID":3,"taxName":"Exempt","taxValidFrom":"2024-01-01"}]}""",
            ),
        )
        val config = client(transport).getConfig()
        val request = transport.requests.single()
        assertEquals("GET", request.method)
        assertEquals("https://fdms.example/Device/v1/321/GetConfig", request.url)
        assertNull(request.body)
        assertNull(request.headers["Content-Type"])
        assertTrue(request.deviceAuthenticated)

        assertEquals(OperatingMode.ONLINE, config.operatingMode)
        assertEquals(24, config.dayMaxHours)
        assertEquals(2, config.dayEndNotificationHours)
        assertEquals(LocalDate.of(2027, 3, 1), config.certificateValidTill)
        assertEquals(
            listOf(
                TaxDefinition(1, "Standard rated 15%", d("15.00"), LocalDate.of(2024, 1, 1)),
                TaxDefinition(2, "Zero rated 0%", d("0"), LocalDate.of(2024, 1, 1), LocalDate.of(2030, 12, 31)),
                TaxDefinition(3, "Exempt", null, LocalDate.of(2024, 1, 1)),
            ),
            config.taxes,
        )
        assertTrue(config.isVatPayer)
        val device = config.toDeviceConfiguration(321)
        assertEquals(DeviceConfiguration(321, "https://fdmstest.zimra.co.zw", config.taxes, isVatPayer = true, dayMaxHours = 24), device)
    }

    @Test
    fun aTaxpayerWithoutAVatNumberIsNotAVatPayer() {
        val transport = Recording(
            ok(
                """{"taxPayerName":"Acme","taxPayerTIN":"2000000000","deviceBranchName":"B","deviceOperatingMode":"Offline",
                "taxPayerDayMaxHrs":24,"taxpayerDayEndNotificationHrs":2,"qrUrl":"https://q","applicableTaxes":[]}""",
            ),
        )
        val config = client(transport).getConfig()
        assertFalse(config.isVatPayer)
        assertNull(config.vatNumber)
        assertEquals(OperatingMode.OFFLINE, config.operatingMode)
    }

    @Test
    fun readsTheStatus() {
        val transport = Recording(
            ok("""{"operationID":"op-4","fiscalDayStatus":"FiscalDayClosed","lastFiscalDayNo":45,"lastReceiptGlobalNo":451,"fiscalDayClosed":"2026-10-16T21:00:05"}"""),
            ok("""{"fiscalDayStatus":"FiscalDayCloseFailed","lastFiscalDayNo":46,"fiscalDayClosingErrorCode":"CountersMismatch"}"""),
            ok("""{"fiscalDayStatus":"FiscalDayClosed"}"""),
        )
        val client = client(transport)
        assertEquals(
            ZimraStatus("op-4", ZimraDayStatus.CLOSED, 45, 451, null, LocalDateTime.of(2026, 10, 16, 21, 0, 5)),
            client.getStatus(),
        )
        assertEquals(ZimraStatus(null, ZimraDayStatus.CLOSE_FAILED, 46, null, "CountersMismatch", null), client.getStatus())
        assertEquals("a new device", ZimraStatus(null, ZimraDayStatus.CLOSED, null, null, null, null), client.getStatus())
        assertEquals("https://fdms.example/Device/v1/321/GetStatus", transport.requests.first().url)
    }

    @Test
    fun pingsAndFetchesTheServerCertificate() {
        val transport = Recording(
            ok("""{"operationID":"op-5","reportingFrequency":5}"""),
            ok("""{"certificate":["PEM-A","PEM-B"],"certificateValidTill":"2027-01-01T00:00:00"}"""),
        )
        val client = client(transport)
        assertEquals(5, client.ping())
        assertEquals(
            ServerCertificate(listOf("PEM-A", "PEM-B"), Instant.parse("2027-01-01T00:00:00Z")),
            client.getServerCertificate("AB CD"),
        )
        val (ping, certificate) = transport.requests
        assertEquals("POST", ping.method)
        assertNull(ping.body)
        assertEquals("https://fdms.example/Device/v1/321/Ping", ping.url)
        assertEquals("https://fdms.example/Public/v1/GetServerCertificate?thumbprint=AB+CD", certificate.url)
        assertFalse(certificate.deviceAuthenticated)
    }

    private val issuedNote = IssuedReceipt(
        deviceId = 321, fiscalDayNo = 4, receiptCounter = 2, globalNo = 77, date = LocalDateTime.of(2026, 10, 17, 10, 5, 9),
        receipt = prepared(
            invoice(
                listOf(
                    ReceiptLine.sale("Bread", 2, d("10.00"), STANDARD, hsCode = "19059000"),
                    ReceiptLine.sale("Book", 1, d("3.00"), EXEMPT, hsCode = "49019900"),
                ),
                type = ReceiptType.CREDIT_NOTE, notes = "Returned", original = OriginalReceipt.ById(9001),
                buyer = Buyer("Acme Ltd", "2000000001", vatNumber = "220000001", address = Address(city = "Harare")),
                pricesIncludeTax = false, invoiceNo = "CN-7",
            ),
        ),
        previousReceiptHash = "prev=", signature = DeviceSignature("hash=", "sig="), qrCode = "q", verificationCode = "v",
    )

    @Test
    fun submitsReceiptsInZimrasForm() {
        val transport = Recording(
            ok("""{"operationID":"op","receiptID":9876543210,"serverDate":"2026-10-17T10:05:11",
                "receiptServerSignature":{"hash":"h","signature":"s","certificateThumbprint":"AB"},
                "validationErrors":[{"validationErrorCode":"RCPT025","validationErrorColor":"Yellow","validationErrorDescription":"d"}]}"""),
        )
        val accepted = client(transport).submitReceipt(issuedNote)
        assertEquals(
            AcceptedReceipt("op", 9876543210, LocalDateTime.of(2026, 10, 17, 10, 5, 11), ZimraSignature("h", "s", "AB"), listOf(ReceiptFinding("RCPT025", "Yellow", "d"))),
            accepted,
        )
        val request = transport.requests.single()
        assertEquals("https://fdms.example/Device/v1/321/SubmitReceipt", request.url)
        assertTrue(request.deviceAuthenticated)
        assertEquals(
            """{"receipt":{"receiptType":"CreditNote","receiptCurrency":"USD","receiptCounter":2,"receiptGlobalNo":77,"invoiceNo":"CN-7",""" +
                """"buyerData":{"buyerRegisterName":"Acme Ltd","vatNumber":"220000001","buyerTIN":"2000000001","buyerAddress":{"city":"Harare"}},""" +
                """"receiptNotes":"Returned","receiptDate":"2026-10-17T10:05:09","creditDebitNote":{"receiptID":9001},""" +
                """"receiptLinesTaxInclusive":false,"receiptLines":[""" +
                """{"receiptLineType":"Sale","receiptLineNo":1,"receiptLineHSCode":"19059000","receiptLineName":"Bread","receiptLinePrice":-10.00,"receiptLineQuantity":2,"receiptLineTotal":-20.00,"taxPercent":15,"taxID":1},""" +
                """{"receiptLineType":"Sale","receiptLineNo":2,"receiptLineHSCode":"49019900","receiptLineName":"Book","receiptLinePrice":-3.00,"receiptLineQuantity":1,"receiptLineTotal":-3.00,"taxID":3}],""" +
                """"receiptTaxes":[{"taxPercent":15,"taxID":1,"taxAmount":-3.00,"salesAmountWithTax":-23.00},{"taxID":3,"taxAmount":0.00,"salesAmountWithTax":-3.00}],""" +
                """"receiptPayments":[{"moneyTypeCode":"Cash","paymentAmount":-26.00}],"receiptTotal":-26.00,"receiptPrintForm":"Receipt48",""" +
                """"receiptDeviceSignature":{"hash":"hash=","signature":"sig="}}}""",
            request.body,
        )
    }

    @Test
    fun opensAndClosesDaysInZimrasForm() {
        val transport = Recording(ok("""{"operationID":"op-o","fiscalDayNo":5}"""), ok("""{"operationID":"op-c"}"""))
        val client = client(transport)
        assertEquals(OpenedDay("op-o", 5), client.openDay(5, LocalDateTime.of(2026, 10, 17, 7, 59, 1)))
        val counters = listOf(
            FiscalCounter(FiscalCounterType.BALANCE_BY_MONEY_TYPE, "usd", d("23.00"), moneyType = MoneyType.CASH),
            FiscalCounter(FiscalCounterType.SALE_BY_TAX, "USD", d("20.00"), STANDARD, d("15")),
            FiscalCounter(FiscalCounterType.SALE_BY_TAX, "USD", d("3.00"), EXEMPT, null),
            FiscalCounter(FiscalCounterType.SALE_TAX_BY_TAX, "USD", d("0.00"), STANDARD, d("15")),
        )
        assertEquals("op-c", client.closeDay(5, counters, DeviceSignature("h=", "s="), 3))
        val (open, close) = transport.requests
        assertEquals("https://fdms.example/Device/v1/321/OpenDay", open.url)
        assertEquals("""{"fiscalDayOpened":"2026-10-17T07:59:01","fiscalDayNo":5}""", open.body)
        assertEquals("https://fdms.example/Device/v1/321/CloseDay", close.url)
        assertEquals(
            """{"fiscalDayNo":5,"fiscalDayCounters":[""" +
                """{"fiscalCounterType":"SaleByTax","fiscalCounterCurrency":"USD","fiscalCounterTaxID":1,"fiscalCounterTaxPercent":15,"fiscalCounterValue":20.00},""" +
                """{"fiscalCounterType":"SaleByTax","fiscalCounterCurrency":"USD","fiscalCounterTaxID":3,"fiscalCounterValue":3.00},""" +
                """{"fiscalCounterType":"BalanceByMoneyType","fiscalCounterCurrency":"USD","fiscalCounterMoneyType":"Cash","fiscalCounterValue":23.00}],""" +
                """"fiscalDayDeviceSignature":{"hash":"h=","signature":"s="},"receiptCounter":3}""",
            close.body,
        )
    }

    @Test
    fun readsZimrasDayCloseDetails() {
        val transport = Recording(
            ok("""{"fiscalDayStatus":"FiscalDayClosed","lastFiscalDayNo":5,"fiscalDayClosed":"2026-10-17T22:00:00",
                "fiscalDayReconciliationMode":"Manual","fiscalDayServerSignature":{"hash":"h","signature":"s","certificateThumbprint":"AB"},
                "fiscalDayCounter":[{"fiscalCounterType":"SaleByTax","fiscalCounterCurrency":"USD","fiscalCounterTaxID":1,"fiscalCounterTaxPercent":15.00,"fiscalCounterValue":20.00}]}"""),
        )
        val status = client(transport).getStatus()
        assertEquals("Manual", status.reconciliationMode)
        assertEquals(ZimraSignature("h", "s", "AB"), status.serverSignature)
        assertEquals(listOf(FiscalCounter(FiscalCounterType.SALE_BY_TAX, "USD", d("20.00"), 1, d("15.00"))), status.counters)
    }

    @Test
    fun zimraErrorsCarryTheirCodeAndOperation() {
        val transport = Recording(
            status(422, """{"type":"t","title":"Unprocessable","status":422,"errorCode":"DEV01","operationID":"op-9"}"""),
        )
        val error = assertThrows(FdmsException::class.java) { client(transport).getConfig() }
        assertEquals(422, error.status)
        assertEquals("DEV01", error.errorCode)
        assertEquals("op-9", error.operationId)
        assertFalse(error.isRetryable)
        assertFalse(error.isAuthenticationFailure)
        assertEquals("ZIMRA answered HTTP 422 DEV01: Unprocessable", error.message)
        assertEquals("Device not found or not active", FdmsErrors.message("dev01"))
        assertNull(FdmsErrors.message("NOPE"))
        assertEquals("refused requests are not retried", 1, transport.requests.size)
    }

    @Test
    fun aRefusedCertificateIsAnAuthenticationFailure() {
        val error = assertThrows(FdmsException::class.java) { client(Recording(status(401))).getStatus() }
        assertTrue(error.isAuthenticationFailure)
        assertEquals("ZIMRA answered HTTP 401", error.message)
    }

    @Test
    fun readsAndPingsAreRetriedAfterServerAndNetworkErrors() {
        val waits = mutableListOf<Duration>()
        val transport = Recording(status(500), offline, ok("""{"fiscalDayStatus":"FiscalDayOpened","lastFiscalDayNo":3}"""))
        val client = client(transport).also { it.sleep = { d -> waits += d } }
        assertEquals(ZimraDayStatus.OPENED, client.getStatus().dayStatus)
        assertEquals(3, transport.requests.size)
        assertEquals(listOf(Duration.ofMillis(500), Duration.ofMillis(1000)), waits)

        val pings = Recording(status(503), ok("""{"reportingFrequency":5}"""))
        assertEquals(5, client(pings).ping())
    }

    @Test
    fun retriesStopAtTheLimit() {
        val transport = Recording(offline, offline, offline)
        assertThrows(FdmsNetworkException::class.java) { client(transport).getConfig() }
        assertEquals(3, transport.requests.size)
        assertThrows(FdmsException::class.java) { client(Recording(status(500)), retries = 0).getConfig() }
    }

    @Test
    fun requestsThatChangeSomethingAreNeverRepeated() {
        val register = Recording(status(502))
        assertThrows(FdmsException::class.java) { client(register).registerDevice("ABCD1234", "CSR") }
        assertEquals(1, register.requests.size)
        val renew = Recording(offline)
        assertThrows(FdmsNetworkException::class.java) { client(renew).issueCertificate("CSR") }
        assertEquals(1, renew.requests.size)
    }

    @Test
    fun unreadableAnswersAreProtocolErrors() {
        assertThrows(FdmsProtocolException::class.java) { client(Recording(ok("not json"))).getStatus() }
        assertThrows(FdmsProtocolException::class.java) { client(Recording(ok("""{"fiscalDayStatus":"Sleeping"}"""))).getStatus() }
        assertThrows(FdmsProtocolException::class.java) { client(Recording(ok("""{"operationID":"x"}"""))).ping() }
        assertThrows(FdmsProtocolException::class.java) {
            client(Recording(ok("""{"fiscalDayStatus":"FiscalDayClosed","fiscalDayClosed":"yesterday"}"""))).getStatus()
        }
        val html = assertThrows(FdmsException::class.java) { client(Recording(status(502, "<html>Bad gateway</html>")), retries = 0).getStatus() }
        assertEquals("ZIMRA answered HTTP 502: <html>Bad gateway</html>", html.message)
    }

    @Test
    fun theClockFollowsZimra() {
        val base = Clock.fixed(Instant.parse("2026-10-17T07:00:00Z"), ZoneOffset.UTC)
        val clock = ZimbabweClock(base = base)
        val ahead = mapOf("date" to "Sat, 17 Oct 2026 07:00:30 GMT")
        client(Recording(ok("""{"reportingFrequency":5}""", ahead)), clock).ping()
        assertEquals(30L, clock.offset.seconds)
        assertEquals(LocalDateTime.of(2026, 10, 17, 9, 0, 30), clock.now())

        val oneSecondOff = mapOf("date" to "Sat, 17 Oct 2026 07:00:31 GMT")
        client(Recording(ok("""{"reportingFrequency":5}""", oneSecondOff)), clock).ping()
        assertEquals("small differences are ignored", 30L, clock.offset.seconds)

        client(Recording(ok("""{"reportingFrequency":5}""", mapOf("date" to "garbage"))), clock).ping()
        assertEquals(30L, clock.offset.seconds)
    }

    @Test
    fun onlyHttpsGatewaysAndValidIdentitiesAreAccepted() {
        assertThrows(FiscalValueException::class.java) { FdmsClient(identity, Recording(), "http://fdms.example") }
        assertThrows(FiscalValueException::class.java) { DeviceIdentity(0, "SN", "m", "v") }
        assertThrows(FiscalValueException::class.java) { DeviceIdentity(1, "S".repeat(21), "m", "v") }
        assertEquals("https://fdmsapi.zimra.co.zw", FdmsEnvironment.PRODUCTION.baseUrl)
        assertEquals("https://fdmsapitest.zimra.co.zw", FdmsEnvironment.TEST.baseUrl)
    }
}
