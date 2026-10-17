package org.donnc.fzimra.simulator

import org.donnc.fzimra.core.Buyer
import org.donnc.fzimra.core.DeviceCsr
import org.donnc.fzimra.core.DeviceIdentity
import org.donnc.fzimra.core.FdmsException
import org.donnc.fzimra.core.FdmsNetworkException
import org.donnc.fzimra.core.FiscalSignature
import org.donnc.fzimra.core.FiscalSync
import org.donnc.fzimra.core.SignatureStrings
import org.donnc.fzimra.core.IssueResult
import org.donnc.fzimra.core.IssuedReceipt
import org.donnc.fzimra.core.OriginalReceipt
import org.donnc.fzimra.core.Payment
import org.donnc.fzimra.core.ReceiptLine
import org.donnc.fzimra.core.ReceiptRequest
import org.donnc.fzimra.core.ReceiptType
import org.donnc.fzimra.core.SyncOutcome
import org.donnc.fzimra.core.ZimraDayStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.math.BigDecimal
import java.time.Duration
import java.time.LocalDateTime

class FdmsSimulatorTest {
    @get:Rule
    val folder = TemporaryFolder()

    private var simulatedNow = LocalDateTime.of(2026, 10, 17, 9, 0)
    private fun simulator(store: SimulatorStore = SimulatorStore.InMemory()) =
        FdmsSimulator(SimulatorOptions(closeValidation = Duration.ofHours(1)), store) { simulatedNow }

    private fun TestDevice.sell(vararg lines: ReceiptLine, payments: List<Payment> = listOf(Payment.cash()), pricesIncludeTax: Boolean = true, buyer: Buyer? = null): IssuedReceipt {
        now = now.plusMinutes(1)
        val request = ReceiptRequest(ReceiptType.FISCAL_INVOICE, "USD", "INV-${now.minute}-${now.second}", lines.toList(), payments, buyer = buyer, pricesIncludeTax = pricesIncludeTax)
        return when (val result = device.issue(request)) {
            is IssueResult.Issued -> result.receipt
            is IssueResult.Refused -> error("refused: ${result.problems}")
        }
    }

    private val bread = ReceiptLine.sale("Bread", 2, BigDecimal("1.15"), 1, hsCode = "19059000")
    private val milk = ReceiptLine.sale("Milk", 1, BigDecimal("1.50"), 2, hsCode = "04011000")
    private val book = ReceiptLine.sale("Textbook", 1, BigDecimal("12.00"), 3, hsCode = "49019900")

    private fun findings(t: TestDevice) = t.simulator.device(t.identity.deviceId)!!.days.flatMap { it.receipts }.flatMap { it.findings }

    @Test
    fun receiptsFromFzimraPassEveryCheck() {
        val t = TestDevice(simulator()).apply { register() }
        t.sell(bread, milk, book)
        t.sell(bread, payments = listOf(Payment.card(BigDecimal("1.30")), Payment.cash()))
        t.sell(bread, milk, pricesIncludeTax = false)
        t.sell(book, buyer = Buyer("Acme Ltd", "2000000001", vatNumber = "220000001"))
        val sale = t.sell(bread)
        t.now = t.now.plusMinutes(1)
        t.device.issue(
            ReceiptRequest(
                ReceiptType.CREDIT_NOTE, "USD", "CN-1", listOf(ReceiptLine.sale("Bread", 1, BigDecimal("1.15"), 1, hsCode = "19059000")),
                listOf(Payment.cash()), notes = "Returned", original = OriginalReceipt.ByDevice(t.identity.deviceId, sale.globalNo, sale.fiscalDayNo),
            ),
        )
        val report = t.sync()
        assertEquals(SyncOutcome.COMPLETE, report.outcome)
        assertEquals(6, report.accepted.size)
        assertEquals("no findings on fzimra's receipts", emptyList<SimulatedFinding>(), findings(t))
        assertTrue("ZIMRA's signatures verify", report.accepted.all { it.zimraSignatureVerified })
    }

    @Test
    fun aFullDayClosesWithVerifiedSignatures() {
        val sim = simulator()
        val t = TestDevice(sim).apply { register() }
        t.sell(bread, milk)
        t.sell(book)
        assertTrue(t.device.requestDayClose())
        assertEquals(SyncOutcome.WAITING, t.sync().outcome)
        assertEquals(ZimraDayStatus.CLOSE_INITIATED.zimraName, sim.device(4242)!!.dayStatus)
        sim.settle()
        val closed = t.sync().closedDays.single()
        assertEquals("Auto", closed.reconciliationMode)
        assertTrue(closed.zimraSignatureVerified)
        assertEquals(ZimraDayStatus.CLOSED.zimraName, sim.device(4242)!!.dayStatus)
    }

    @Test
    fun aCloseIsValidatedAfterItsDelay() {
        val sim = FdmsSimulator(SimulatorOptions(closeValidation = Duration.ZERO)) { simulatedNow }
        val t = TestDevice(sim).apply { register() }
        t.sell(bread)
        assertTrue(t.device.requestDayClose())
        assertEquals("validated by the sync's next request", 1, t.sync().closedDays.size)
    }

    @Test
    fun theStatusReportsTheLatestDatedReceiptAsZimraDoes() {
        val sim = simulator()
        val t = TestDevice(sim).apply { register() }
        t.sell(bread)
        t.sell(bread)
        t.sync()
        val status = t.client.getStatus()
        assertEquals(2, status.lastReceiptGlobalNo)
        assertEquals(1, status.lastFiscalDayNo)
        assertEquals(ZimraDayStatus.OPENED, status.dayStatus)
    }

    @Test
    fun faultsAreSurvived() {
        val sim = simulator()
        val t = TestDevice(sim).apply { register() }
        t.sell(bread)
        sim.faults.offline = true
        assertEquals(SyncOutcome.OFFLINE, t.sync().outcome)
        sim.faults.offline = false
        sim.faults.serverErrors = 1
        assertEquals("a 503 is retried", SyncOutcome.COMPLETE, t.sync().outcome)
        t.sell(bread)
        sim.faults.dropAfterSubmit = 1
        assertEquals(SyncOutcome.OFFLINE, t.sync().outcome)
        assertEquals(SyncOutcome.COMPLETE, t.sync().outcome)
        assertEquals("the lost answer did not duplicate it", 2, sim.device(4242)!!.days.single().receipts.size)
        sim.faults.dropConnections = 1
        assertEquals("a dropped connection is retried", 5, t.client.ping())
    }

    @Test
    fun aRefusedCloseCanBeRetriedEitherWay() {
        for (reopened in listOf(false, true)) {
            val sim = simulator()
            val t = TestDevice(sim).apply { register() }
            t.sell(bread)
            assertTrue(t.device.requestDayClose())
            t.sync()
            sim.faults.refuseNextClose = "CountersMismatch"
            sim.faults.refuseCloseAsReopened = reopened
            sim.settle()
            assertEquals("$reopened", SyncOutcome.BLOCKED, t.sync().outcome)
            assertEquals("CountersMismatch", t.device.state()!!.currentDay!!.closingErrorCode)
            assertTrue(t.device.retryDayClose(1))
            t.sync()
            sim.settle()
            assertEquals(1, t.sync().closedDays.size)
        }
    }

    @Test
    fun aFlaggedReceiptBlocksTheCloseUntilThePortalClosesIt() {
        val sim = simulator()
        val t = TestDevice(sim).apply { register() }
        sim.faults.flagNextReceipt = "RCPT025" to "Red"
        t.sell(bread)
        assertTrue(t.device.requestDayClose())
        assertEquals(SyncOutcome.BLOCKED, t.sync().outcome)
        assertTrue(sim.closeInPortal(4242))
        val closed = t.sync().closedDays.single()
        assertEquals("Manual", closed.reconciliationMode)
        assertTrue(closed.zimraSignatureVerified)
    }

    @Test
    fun theStateSurvivesARestart() {
        val store = SimulatorStore.InFile(folder.root.resolve("simulator.json"))
        val first = simulator(store)
        val t = TestDevice(first).apply { register() }
        t.sell(bread)
        t.sync()

        val restarted = simulator(store)
        assertEquals(first.caCertificatePem, restarted.caCertificatePem)
        val client = restarted.client(t.identity, { t.certificate })
        assertEquals("the device's certificate is still recognised", 1, client.getStatus().lastReceiptGlobalNo)
        assertEquals(1, restarted.device(4242)!!.days.single().receipts.size)
        t.sell(bread)
        val resumed = FiscalSync(t.device, client).run()
        assertEquals(SyncOutcome.COMPLETE, resumed.outcome)
        assertEquals(2, restarted.device(4242)!!.days.single().receipts.size)
    }

    @Test
    fun onlyItsOwnDevicesWithTheirOwnCertificatesAreServed() {
        val sim = simulator()
        val t = TestDevice(sim).apply { register() }
        val stranger = TestDevice(sim, DeviceIdentity(5151, "SIM-OTHER", "m", "1")).apply { register() }
        val impostor = sim.client(t.identity, { stranger.certificate })
        assertTrue(assertThrows(FdmsException::class.java) { impostor.getConfig() }.isAuthenticationFailure)
        val anonymous = sim.client(t.identity, { null })
        assertTrue(assertThrows(FdmsException::class.java) { anonymous.getStatus() }.isAuthenticationFailure)
        val unknown = sim.client(DeviceIdentity(9999, "X", "m", "1"), { t.certificate })
        assertEquals("DEV01", assertThrows(FdmsException::class.java) { unknown.getStatus() }.errorCode)
    }

    @Test
    fun aCertificateFromAnotherAuthorityIsRefused() {
        val sim = simulator()
        val t = TestDevice(sim).apply { register() }
        val elsewhere = TestDevice(simulator(), t.identity).apply { register() } // same device, another gateway's certificate
        val client = sim.client(t.identity, { elsewhere.certificate })
        assertTrue(assertThrows(FdmsException::class.java) { client.getConfig() }.isAuthenticationFailure)
    }

    @Test
    fun aCloseWithCountersThatDisagreeIsRefused() {
        val sim = simulator()
        val t = TestDevice(sim).apply { register() }
        t.sell(bread)
        t.sync()
        val day = t.device.state()!!.currentDay!!
        val signature = FiscalSignature.sign(t.signer, SignatureStrings.fiscalDay(4242, 1, day.openedAt.toLocalDate(), day.counters))
        val wrong = day.counters.map { it.copy(value = it.value.add(BigDecimal.ONE)) }
        t.client.closeDay(1, wrong, signature, day.receiptCounter)
        sim.settle()
        assertEquals("CountersMismatch", sim.device(4242)!!.closingErrorCode)

        t.client.closeDay(1, day.counters, signature, day.receiptCounter + 1)
        sim.settle()
        assertEquals("the receipt count must agree too", "CountersMismatch", sim.device(4242)!!.closingErrorCode)

        t.client.closeDay(1, day.counters, signature, day.receiptCounter)
        sim.settle()
        assertEquals(ZimraDayStatus.CLOSED.zimraName, sim.device(4242)!!.dayStatus)
    }

    @Test
    fun registrationChecksTheKeyAndTheRequest() {
        val sim = FdmsSimulator(SimulatorOptions(activationKeys = listOf("GOOD1234"))) { simulatedNow }
        val t = TestDevice(sim)
        val csr = DeviceCsr.create(t.signer, t.identity.serialNo, t.identity.deviceId)
        assertEquals("DEV02", assertThrows(FdmsException::class.java) { t.client.verifyTaxpayerInformation("BAD12345") }.errorCode)
        assertEquals("Simulated Taxpayer (Pvt) Ltd", t.client.verifyTaxpayerInformation("good1234").taxpayerName)
        val wrongDevice = DeviceCsr.create(t.signer, t.identity.serialNo, 777)
        assertEquals("DEV03", assertThrows(FdmsException::class.java) { t.client.registerDevice("GOOD1234", wrongDevice) }.errorCode)
        assertTrue(t.client.registerDevice("GOOD1234", csr).certificatePem.contains("BEGIN CERTIFICATE"))
        assertTrue(sim.device(4242)!!.isRegistered)
    }

    @Test
    fun aCertificateCanBeRenewed() {
        val sim = simulator()
        val t = TestDevice(sim).apply { register() }
        val renewed = t.client.issueCertificate(DeviceCsr.create(t.signer, t.identity.serialNo, t.identity.deviceId))
        t.certificate = org.donnc.fzimra.core.DeviceCertificate.parse(renewed.certificatePem).first()
        assertEquals(5, t.client.ping())
    }

    @Test
    fun brokenReceiptsAreFlaggedAsZimraWould() {
        val sim = simulator()
        val t = TestDevice(sim).apply { register() }
        t.sell(bread)
        t.sync()
        val receipt = t.device.records.list(4242).single().receipt
        // Resent with an edited total: a new receipt as far as ZIMRA knows, and a broken one.
        val edited = receipt.copy(
            globalNo = 2, receiptCounter = 2,
            receipt = receipt.receipt.copy(total = BigDecimal("9.99")),
            signature = receipt.signature.copy(hash = "AAAA"),
        )
        val accepted = t.client.submitReceipt(edited)
        val codes = accepted.findings.map { it.code }.toSet()
        assertTrue(codes.toString(), codes.containsAll(setOf("RCPT020", "RCPT038", "RCPT039", "RCPT030")))
        assertTrue(accepted.findings.first { it.code == "RCPT020" }.colour == "Red")
    }

    @Test
    fun resetForgetsDevices() {
        val sim = simulator()
        TestDevice(sim).apply { register() }
        sim.reset()
        assertNull(sim.device(4242))
        assertFalse(sim.faults.offline)
    }

    @Test
    fun networkFaultsLookLikeNetworkFailures() {
        val sim = simulator()
        val t = TestDevice(sim).apply { register() }
        sim.faults.offline = true
        assertThrows(FdmsNetworkException::class.java) { sim.transport { t.certificate }.execute(org.donnc.fzimra.core.FdmsRequest("GET", FdmsSimulator.BASE_URL + "/Device/v1/4242/GetStatus", emptyMap(), null, true)) }
    }
}
