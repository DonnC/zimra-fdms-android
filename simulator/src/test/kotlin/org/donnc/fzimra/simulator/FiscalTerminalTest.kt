package org.donnc.fzimra.simulator

import org.donnc.fzimra.core.DeviceCertificate
import org.donnc.fzimra.core.DeviceIdentity
import org.donnc.fzimra.core.DeviceKey
import org.donnc.fzimra.core.DeviceKeyStore
import org.donnc.fzimra.core.FdmsObserver
import org.donnc.fzimra.core.FiscalTerminal
import org.donnc.fzimra.core.FiscalValueException
import org.donnc.fzimra.core.IssueResult
import org.donnc.fzimra.core.IssuedReceipt
import org.donnc.fzimra.core.Payment
import org.donnc.fzimra.core.ReceiptLine
import org.donnc.fzimra.core.ReceiptResult
import org.donnc.fzimra.core.ReceiptSyncStatus
import org.donnc.fzimra.core.ReceiptType
import org.donnc.fzimra.core.SignatureAlgorithm
import org.donnc.fzimra.core.SyncOutcome
import org.donnc.fzimra.core.TerminalStatus
import org.donnc.fzimra.core.TerminalStore
import org.donnc.fzimra.core.ZimbabweClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.security.cert.X509Certificate
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class FiscalTerminalTest {
    private class MovableClock(var instant: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = instant
    }

    /** A software device key that holds its certificate. Tests only. */
    private class MemoryKey : DeviceKey {
        private val signer = TestSigner()
        override val publicKey = signer.publicKey
        override val algorithm: SignatureAlgorithm = signer.algorithm
        override fun sign(data: ByteArray): ByteArray = signer.sign(data)
        override var certificate: X509Certificate? = null
        override fun installCertificate(certificatePem: String, identity: DeviceIdentity) {
            val chain = DeviceCertificate.parse(certificatePem)
            DeviceCertificate.check(chain.first(), publicKey, identity)
            certificate = chain.first()
        }
    }

    private class MemoryKeyStore : DeviceKeyStore {
        var key: MemoryKey? = null
        override fun load(): DeviceKey? = key
        override fun create(): DeviceKey = MemoryKey().also { key = it }
        override fun delete() {
            key = null
        }
    }

    private class MemoryTerminalStore : TerminalStore {
        var text: String? = null
        override fun load(): String? = text
        override fun save(text: String) {
            this.text = text
        }
        override fun delete() {
            text = null
        }
    }

    private val identity = DeviceIdentity(7300, "SIM-TERM", "fzimra-test", "1.0")
    private val wall = MovableClock(Instant.parse("2026-10-17T06:30:00Z"))
    private val simulator = FdmsSimulator(SimulatorOptions(closeValidation = Duration.ofHours(1), certificateValidity = Duration.ofDays(365))) {
        java.time.LocalDateTime.ofInstant(wall.instant(), ZoneId.of("Africa/Harare"))
    }
    private val gateway = SimulatorGateway(simulator)
    private val keys = MemoryKeyStore()
    private val states = MemoryStateStore()
    private val records = MemoryRecordStore()
    private val store = MemoryTerminalStore()

    private fun terminal(observer: FdmsObserver? = null, renewBefore: Duration = Duration.ofDays(30)) = FiscalTerminal(
        identity, gateway, keys, states, records, store, { TestSealer() }, ZimbabweClock(base = wall), observer = observer, renewBefore = renewBefore,
    )

    private fun FiscalTerminal.sellBread(quantity: Int = 2): IssuedReceipt {
        wall.instant = wall.instant.plusSeconds(60)
        val result = sell(listOf(ReceiptLine.sale("Bread", quantity, BigDecimal("1.15"), taxes.standard, "19059000")))
        return (result as? IssueResult.Issued)?.receipt ?: error("refused: $result")
    }

    @Test
    fun registeringIsOneCall() {
        val terminal = terminal()
        assertFalse(terminal.isRegistered)
        assertEquals("Simulated Taxpayer (Pvt) Ltd", terminal.checkTaxpayer("ABCD1234").taxpayerName)
        val status = terminal.register("ABCD1234")
        assertTrue(status.isRegistered)
        assertEquals("Simulator", status.gateway)
        assertEquals("2000000000", status.configuration!!.taxpayerTin)
        assertTrue(status.certificateExpiry!!.isAfter(Instant.now()))
        assertEquals(1, terminal.taxes.standard.taxId)
        assertEquals(2, terminal.taxes.zeroRated.taxId)
        assertEquals(3, terminal.taxes.exempt.taxId)
        assertEquals(1, terminal.taxes.at(15).taxId)
        assertEquals(3, terminal.taxes.at(null).taxId)
        assertThrows(FiscalValueException::class.java) { terminal.taxes.at(7) }
        assertEquals("registering again changes nothing", status.certificateExpiry, terminal.register("ABCD1234").certificateExpiry)
    }

    @Test
    fun aSaleIsOneCallAndReachesZimra() {
        val terminal = terminal().apply { register("ABCD1234") }
        val receipt = terminal.sellBread()
        assertEquals("INV-7300-000001", receipt.receipt.invoiceNo)
        assertEquals(1, terminal.status().pendingReceipts)
        assertEquals(SyncOutcome.COMPLETE, terminal.sync().outcome)
        assertTrue(terminal.status().isUpToDate)
        assertEquals(ReceiptSyncStatus.SUBMITTED, terminal.receipts().single().status)
        assertEquals("INV-7300-000002", terminal.sellBread().receipt.invoiceNo)
        assertEquals(emptyList<SimulatedFinding>(), simulator.device(7300)!!.days.single().receipts.flatMap { it.findings })
    }

    @Test
    fun theTerminalPicksUpWhereItLeftOff() {
        terminal().apply { register("ABCD1234"); sellBread() }
        val restarted = terminal()
        assertTrue("no registration needed after a restart", restarted.isRegistered)
        assertEquals("INV-7300-000002", restarted.sellBread().receipt.invoiceNo)
        assertEquals(2, restarted.status().pendingReceipts)
        assertEquals(SyncOutcome.COMPLETE, restarted.sync().outcome)
    }

    @Test
    fun refundsAreOneCall() {
        val terminal = terminal().apply { register("ABCD1234") }
        val sale = terminal.sellBread(quantity = 3)
        terminal.sync()
        wall.instant = wall.instant.plusSeconds(60)
        val full = terminal.refund(sale, "Returned") as IssueResult.Issued
        assertEquals(ReceiptType.CREDIT_NOTE, full.receipt.receipt.type)
        assertEquals("CN-7300-000001", full.receipt.receipt.invoiceNo)
        assertEquals(sale.receipt.total.negate(), full.receipt.receipt.total)
        assertEquals("nothing left to refund", true, terminal.refund(sale, "Again") is IssueResult.Refused)

        val second = terminal.sellBread(quantity = 3)
        wall.instant = wall.instant.plusSeconds(60)
        val partial = terminal.refund(second, "One stale", lines = listOf(ReceiptLine.sale("Bread", 1, BigDecimal("1.15"), terminal.taxes.standard, "19059000")))
        assertEquals(BigDecimal("-1.15"), (partial as IssueResult.Issued).receipt.receipt.total)
        assertEquals(SyncOutcome.COMPLETE, terminal.sync().outcome)
        assertTrue("the simulator accepted the notes as they are", simulator.device(7300)!!.days.single().receipts.all { it.findings.isEmpty() })
    }

    @Test
    fun closingTheDayIsOneCall() {
        val terminal = terminal().apply { register("ABCD1234") }
        terminal.sellBread()
        assertTrue(terminal.closeDay())
        assertEquals(SyncOutcome.WAITING, terminal.sync().outcome)
        simulator.settle()
        assertEquals(1, terminal.sync().closedDays.size)
        assertEquals(null, terminal.status().day)
        assertEquals(2, terminal.sellBread().fiscalDayNo)
    }

    @Test
    fun listenersHearEveryChange() {
        val terminal = terminal().apply { register("ABCD1234") }
        val heard = mutableListOf<TerminalStatus>()
        var work = 0
        terminal.addListener { heard += it }
        terminal.addWorkListener { work++ }
        terminal.sellBread()
        assertEquals(1, work)
        assertEquals(1, heard.last().pendingReceipts)
        terminal.sync()
        assertEquals(0, heard.last().pendingReceipts)
        assertEquals(SyncOutcome.COMPLETE, heard.last().lastSync!!.outcome)
        terminal.closeDay()
        assertEquals(2, work)
    }

    @Test
    fun theUserIsWarnedBeforeTheDayEnds() {
        val terminal = terminal().apply { register("ABCD1234") }
        val sale = terminal.sellBread()
        val status = terminal.status()
        assertEquals(sale.date.plusHours(24), status.dayEndsAt)
        assertFalse(status.isDayEndingSoon)
        wall.instant = wall.instant.plus(Duration.ofHours(22).plusMinutes(1))
        assertTrue(terminal.status().isDayEndingSoon)
    }

    @Test
    fun upkeepPingsAndRenewsTheCertificate() {
        val exchanges = mutableListOf<String>()
        val terminal = terminal(observer = { request, _, _, _ -> exchanges += request.url.substringAfterLast('/') }, renewBefore = Duration.ofDays(400))
        terminal.register("ABCD1234")
        val before = keys.key!!.certificate!!
        terminal.maintain()
        assertTrue(exchanges.contains("Ping"))
        assertTrue(exchanges.contains("IssueCertificate"))
        assertNotEquals("a new certificate", before.serialNumber, keys.key!!.certificate!!.serialNumber)
        exchanges.clear()
        terminal.maintain()
        assertFalse("pings only at ZIMRA's interval", exchanges.contains("Ping"))
        terminal.sellBread()
        assertEquals("the renewed certificate works", SyncOutcome.COMPLETE, terminal.sync().outcome)
    }

    @Test
    fun aPreviewPricesWithoutIssuing() {
        val terminal = terminal().apply { register("ABCD1234") }
        val preview = terminal.preview(listOf(ReceiptLine.sale("Bread", 2, BigDecimal("1.15"), terminal.taxes.standard, "19059000")), listOf(Payment.cash(BigDecimal("5"))))
        val prepared = (preview as ReceiptResult.Prepared).receipt
        assertEquals(BigDecimal("2.30"), prepared.total)
        assertEquals(BigDecimal("2.70"), prepared.change)
        assertEquals(0, terminal.status().pendingReceipts)
    }

    @Test
    fun registeringAgainContinuesZimrasNumbering() {
        val first = terminal().apply { register("ABCD1234") }
        repeat(3) { first.sellBread() }
        first.closeDay()
        first.sync()
        simulator.settle()
        first.sync()
        // The same ZIMRA device on a new phone: new key, empty storage.
        val second = FiscalTerminal(
            identity, gateway, MemoryKeyStore(), MemoryStateStore(), MemoryRecordStore(), MemoryTerminalStore(), { TestSealer() }, ZimbabweClock(base = wall),
        )
        second.register("ABCD1234")
        val next = second.sellBread()
        assertEquals(4 to 2, next.globalNo to next.fiscalDayNo)
        assertEquals(SyncOutcome.COMPLETE, second.sync().outcome)
    }

    @Test
    fun unregisteringProtectsUnsentReceipts() {
        val terminal = terminal().apply { register("ABCD1234") }
        terminal.sellBread()
        assertThrows(IllegalStateException::class.java) { terminal.unregister() }
        terminal.unregister(discardUnsentReceipts = true)
        assertFalse(terminal.isRegistered)
        assertEquals(null, keys.key)
        assertEquals(null, store.text)
        assertThrows(IllegalStateException::class.java) { terminal.taxes }
    }
}
