package org.donnc.fzimra.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class FiscalSyncTest {
    private var now = LocalDateTime.of(2026, 10, 17, 8, 30, 0)
    private val clock = FiscalClock { now }
    private val signer = SoftwareSigner.ecP256()
    private val stateStorage = InMemoryTextStorage()
    private val recordStorage = InMemoryTextStorage()
    private val configuration = DeviceConfiguration(7001, "https://fdmstest.zimra.co.zw", TAXES, isVatPayer = true, dayMaxHours = 24)
    private val zimra = FakeFdms(7001, signer.publicKey)
    private val identity = DeviceIdentity(7001, "SN", "fzimra-test", "1.0")

    private fun device(numbering: GlobalNumbering = GlobalNumbering()) = FiscalDevice(
        configuration, signer, TextStateStore(stateStorage), TextRecordStore(recordStorage), SoftwareSealer(), clock,
        numbering = numbering,
    )

    private fun client(transport: FdmsTransport = zimra) =
        FdmsClient(identity, transport, "https://fdms.test", retries = 0).also { it.sleep = {} }

    private fun sync(device: FiscalDevice, transport: FdmsTransport = zimra) = FiscalSync(device, client(transport)).run()

    private fun FiscalDevice.sell(price: String = "10.00"): IssuedReceipt {
        now = now.plusMinutes(1)
        return (issue(invoice(listOf(bread(1, d(price))))) as IssueResult.Issued).receipt
    }

    private fun endpoints() = zimra.requests.map { it.url.substringAfterLast('/').substringBefore('?') }

    @Test
    fun nothingToDoMeansNoRequests() {
        val device = device()
        assertEquals(SyncOutcome.COMPLETE, sync(device).outcome)
        device.startFrom(0, 0)
        assertEquals(SyncOutcome.COMPLETE, sync(device).outcome)
        assertTrue(zimra.requests.isEmpty())
    }

    @Test
    fun opensTheDayAndSubmitsEveryReceiptInOrder() {
        val device = device()
        val receipts = (1..3).map { device.sell() }
        val report = sync(device)

        assertEquals(SyncOutcome.COMPLETE, report.outcome)
        assertEquals(listOf(1), report.openedDays)
        assertEquals(listOf(1, 2, 3), report.accepted.map { it.receipt.globalNo })
        assertEquals(0, report.pendingReceipts)
        assertTrue(device.pendingReceipts().isEmpty())
        assertEquals("ZIMRA got them in order", listOf(1, 2, 3), zimra.days.single().receipts.map { it.globalNo })
        assertEquals(receipts.first().date, zimra.days.single().opened)
        assertEquals(
            listOf("GetStatus", "GetConfig", "GetStatus", "OpenDay", "SubmitReceipt", "GetServerCertificate", "SubmitReceipt", "SubmitReceipt"),
            endpoints(),
        )

        val day = device.state()!!.currentDay!!
        assertTrue(day.zimraOpened)
        assertEquals(FiscalDayStatus.OPENED, day.status)
        receipts.forEach { receipt ->
            val record = device.records.find(7001, 1, receipt.globalNo)!!
            assertEquals(ReceiptSyncStatus.SUBMITTED, record.status)
            assertTrue(record.zimraReceiptId!! >= 9_000)
            assertTrue("ZIMRA's signature checks out", record.zimraSignatureVerified)
            assertEquals(zimra.thumbprint, record.zimraSignature!!.certificateThumbprint)
        }
        val requests = zimra.requests.size
        assertEquals("nothing more to send", SyncOutcome.COMPLETE, sync(device).outcome)
        assertEquals("only ZIMRA's status is checked", listOf("GetStatus"), endpoints().drop(requests))
    }

    @Test
    fun receiptsKeepFlowingIntoAnOpenDay() {
        val device = device()
        device.sell()
        sync(device)
        device.sell()
        val requestsBefore = zimra.requests.size
        val report = sync(device)
        assertEquals(listOf(2), report.accepted.map { it.receipt.globalNo })
        assertTrue("no second opening", report.openedDays.isEmpty())
        assertEquals(listOf("GetStatus", "SubmitReceipt"), endpoints().drop(requestsBefore))
    }

    @Test
    fun offlineLosesNothingAndResumes() {
        val device = device()
        repeat(2) { device.sell() }
        zimra.offlineFor = 1
        val first = device.pendingReceipts().first()
        val offline = sync(device)
        assertEquals(SyncOutcome.OFFLINE, offline.outcome)
        assertTrue(offline.error is FdmsNetworkException)
        assertEquals(2, offline.pendingReceipts)
        assertEquals(first.date, offline.oldestPendingDate)
        assertEquals(first.date, device.oldestUnsentReceiptDate())

        val report = sync(device)
        assertEquals(SyncOutcome.COMPLETE, report.outcome)
        assertEquals(2, zimra.days.single().receipts.size)
        assertEquals(null, report.oldestPendingDate)
        assertEquals(null, device.oldestUnsentReceiptDate())
    }

    @Test
    fun aLostAnswerIsResentWithoutADuplicate() {
        val device = device()
        device.sell()
        sync(device) // opens the day and submits
        device.sell()
        val transport = FdmsTransport { request ->
            val answer = zimra.execute(request)
            if (request.url.endsWith("SubmitReceipt") && zimra.days.single().receipts.size == 2 && !lost) {
                lost = true
                throw FdmsNetworkException("answer lost")
            }
            answer
        }
        assertEquals(SyncOutcome.OFFLINE, sync(device, transport).outcome)
        assertEquals(1, device.pendingReceipts().size)
        val report = sync(device)
        assertEquals(SyncOutcome.COMPLETE, report.outcome)
        assertEquals("ZIMRA kept one copy", 2, zimra.days.single().receipts.size)
        assertEquals(zimra.days.single().receipts[1].receiptId, report.accepted.single().zimraReceiptId)
    }

    private var lost = false

    @Test
    fun aLostOpeningIsRecognisedFromZimrasStatus() {
        val device = device()
        device.sell()
        val transport = FdmsTransport { request ->
            val answer = zimra.execute(request)
            if (request.url.endsWith("OpenDay")) throw FdmsNetworkException("answer lost")
            answer
        }
        assertEquals(SyncOutcome.OFFLINE, sync(device, transport).outcome)
        assertFalse(device.state()!!.currentDay!!.zimraOpened)
        val report = sync(device)
        assertEquals(SyncOutcome.COMPLETE, report.outcome)
        assertEquals("opened once", 1, zimra.requests.count { it.url.endsWith("OpenDay") })
        assertEquals(1, zimra.days.single().receipts.size)
    }

    @Test
    fun aDayIsClosedVerifiedAndTheNextOneOpened() {
        val device = device()
        repeat(2) { device.sell() }
        device.issue(invoice(listOf(bread(1, d("5.00"))), type = ReceiptType.CREDIT_NOTE, notes = "Returned", original = OriginalReceipt.ByDevice(7001, 1, 1)))
        assertTrue(device.requestDayClose())

        val closing = sync(device)
        assertEquals(SyncOutcome.WAITING, closing.outcome)
        assertEquals(3, closing.accepted.size)
        assertEquals(FiscalDayStatus.CLOSE_INITIATED, device.state()!!.currentDay!!.status)
        assertTrue(device.state()!!.currentDay!!.closeSignature != null)

        assertEquals("ZIMRA still validating", SyncOutcome.WAITING, sync(device).outcome)
        zimra.settle()
        val closed = sync(device)
        assertEquals(SyncOutcome.COMPLETE, closed.outcome)
        val day = closed.closedDays.single()
        assertEquals(1, day.day.fiscalDayNo)
        assertEquals("Auto", day.reconciliationMode)
        assertTrue("ZIMRA's signature on the day checks out", day.zimraSignatureVerified)
        assertEquals(FiscalDayStatus.CLOSED, device.state()!!.currentDay!!.status)

        val next = device.sell()
        assertEquals(2, next.fiscalDayNo)
        val report = sync(device)
        assertEquals(listOf(2), report.openedDays)
        assertEquals(listOf(4), report.accepted.map { it.receipt.globalNo })
    }

    @Test
    fun daysQueuedOfflineAreWorkedThroughInOrder() {
        val device = device()
        device.sell()
        assertTrue(device.requestDayClose())
        device.sell() // day 2, while day 1 was never sent
        assertTrue(device.requestDayClose())
        device.sell() // day 3

        assertEquals(SyncOutcome.WAITING, sync(device).outcome)
        assertEquals("day 1 sent and closing", 1, zimra.days.size)
        zimra.settle()
        assertEquals(SyncOutcome.WAITING, sync(device).outcome)
        assertEquals(2, zimra.days.size)
        zimra.settle()
        assertEquals(SyncOutcome.COMPLETE, sync(device).outcome)
        assertEquals(listOf(1, 2, 3), zimra.days.map { it.number })
        assertEquals(listOf(1, 1, 1), zimra.days.map { it.receipts.size })
        assertTrue(device.state()!!.closingDays.isEmpty())
    }

    @Test
    fun aRestartedNumberingIsAcceptedByZimra() {
        val device = device(GlobalNumbering(GlobalNumberReset.EVERY_FISCAL_DAY))
        repeat(2) { device.sell() }
        assertTrue(device.requestDayClose())
        sync(device)
        zimra.settle()
        sync(device)
        assertEquals(1, device.sell().globalNo)
        assertEquals(SyncOutcome.COMPLETE, sync(device).outcome)
        assertEquals(listOf(1), zimra.days[1].receipts.map { it.globalNo })
    }

    @Test
    fun greyOrRedReceiptsBlockTheCloseUntilClosedInThePortal() {
        val device = device()
        repeat(2) { device.sell() }
        zimra.findings[2] = "RCPT025" to "Red"
        assertTrue(device.requestDayClose())
        val blocked = sync(device)
        assertEquals(SyncOutcome.BLOCKED, blocked.outcome)
        assertTrue(blocked.problems.single(), "portal" in blocked.problems.single())
        assertEquals(1, device.state()!!.currentDay!!.blockingReceipts)
        assertEquals("Red", device.records.find(7001, 1, 2)!!.validations.single().colour)
        assertFalse(zimra.requests.any { it.url.endsWith("CloseDay") })

        zimra.closeInPortal()
        val closed = sync(device)
        assertEquals(SyncOutcome.COMPLETE, closed.outcome)
        assertEquals("Manual", closed.closedDays.single().reconciliationMode)
        assertTrue(closed.closedDays.single().zimraSignatureVerified)
    }

    @Test
    fun aRefusedCloseCanBeRetried() {
        val device = device()
        device.sell()
        assertTrue(device.requestDayClose())
        sync(device)
        zimra.refuseNextClose = "CountersMismatch"
        zimra.settle()
        val refused = sync(device)
        assertEquals(SyncOutcome.BLOCKED, refused.outcome)
        assertTrue(refused.problems.single(), "CountersMismatch" in refused.problems.single())
        val day = device.state()!!.currentDay!!
        assertEquals(FiscalDayStatus.CLOSE_FAILED, day.status)
        assertEquals("CountersMismatch", day.closingErrorCode)
        assertEquals("stays blocked until retried", SyncOutcome.BLOCKED, sync(device).outcome)

        assertTrue(device.retryDayClose(1))
        assertFalse(device.retryDayClose(1))
        assertEquals(SyncOutcome.WAITING, sync(device).outcome)
        zimra.settle()
        assertEquals(1, sync(device).closedDays.size)
    }

    @Test
    fun aDayClosedInThePortalIsVerifiedWithZimrasCounters() {
        val device = device()
        device.sell()
        sync(device)
        zimra.portalCounters = listOf(FiscalCounter(FiscalCounterType.SALE_BY_TAX, "USD", d("9.00"), STANDARD, d("15")))
        zimra.closeInPortal()
        assertTrue(device.requestDayClose())
        val closed = sync(device).closedDays.single()
        assertEquals("Manual", closed.reconciliationMode)
        assertTrue(closed.zimraSignatureVerified)
    }

    @Test
    fun aDayClosedInThePortalWhileOpenIsClosedOnTheDevice() {
        val device = device()
        device.sell()
        sync(device)
        zimra.closeInPortal()
        device.sell()
        val report = sync(device)
        assertEquals(SyncOutcome.BLOCKED, report.outcome)
        assertTrue(report.problems.single(), "can no longer accept" in report.problems.single())

        val fresh = FiscalDevice(configuration, signer, TextStateStore(InMemoryTextStorage()), TextRecordStore(InMemoryTextStorage()), SoftwareSealer(), clock)
        val other = FakeFdms(7001, signer.publicKey)
        fresh.sell()
        FiscalSync(fresh, client(other)).run()
        other.closeInPortal()
        val closed = FiscalSync(fresh, client(other)).run()
        assertEquals(SyncOutcome.COMPLETE, closed.outcome)
        assertEquals(1, closed.closedDays.size)
        assertEquals("the next sale opens a new day", 2, fresh.sell().fiscalDayNo)
        assertEquals(listOf(2), FiscalSync(fresh, client(other)).run().openedDays)
    }

    @Test
    fun aSignatureWithAWrongHashIsNotVerified() {
        val device = device()
        device.sell()
        zimra.corruptSignatureHash = true
        val report = sync(device)
        assertEquals(SyncOutcome.COMPLETE, report.outcome)
        assertFalse("accepted, but not verified", report.accepted.single().zimraSignatureVerified)
    }

    @Test
    fun aLostCloseAnswerIsRecognised() {
        val device = device()
        device.sell()
        assertTrue(device.requestDayClose())
        val transport = FdmsTransport { request ->
            val answer = zimra.execute(request)
            if (request.url.endsWith("CloseDay")) throw FdmsNetworkException("answer lost")
            answer
        }
        assertEquals(SyncOutcome.OFFLINE, sync(device, transport).outcome)
        assertEquals(FiscalDayStatus.CLOSE_REQUESTED, device.state()!!.currentDay!!.status)
        assertEquals("ZIMRA says a close is in progress", SyncOutcome.WAITING, sync(device).outcome)
        assertEquals(FiscalDayStatus.CLOSE_INITIATED, device.state()!!.currentDay!!.status)
        zimra.settle()
        assertEquals(1, sync(device).closedDays.size)
    }

    @Test
    fun aRefusedCloseReportedAsAReopenedDayIsRecognised() {
        val device = device()
        device.sell()
        assertTrue(device.requestDayClose())
        sync(device)
        zimra.refuseAsReopened = true
        zimra.refuseNextClose = "BadCertificateSignature"
        zimra.settle()
        assertEquals(SyncOutcome.BLOCKED, sync(device).outcome)
        assertEquals("BadCertificateSignature", device.state()!!.currentDay!!.closingErrorCode)
    }

    @Test
    fun aDayZimraDoesNotExpectIsNotOpened() {
        val device = device()
        device.startFrom(0, 1) // the device believes day 1 happened; ZIMRA has no day
        device.sell()
        val report = sync(device)
        assertEquals(SyncOutcome.BLOCKED, report.outcome)
        assertTrue(report.problems.single(), "expects fiscal day 1" in report.problems.single())
        assertFalse(zimra.requests.any { it.url.endsWith("OpenDay") })
    }

    @Test
    fun aRefusedReceiptStopsTheQueue() {
        val device = device()
        repeat(3) { device.sell() }
        zimra.refuse[2] = "RCPT02"
        val report = sync(device)
        assertEquals(SyncOutcome.BLOCKED, report.outcome)
        assertTrue(report.problems.single(), "1/2" in report.problems.single() && "RCPT02" in report.problems.single())
        assertEquals(listOf(1), zimra.days.single().receipts.map { it.globalNo })
        assertEquals(listOf(2, 3), device.pendingReceipts().map { it.globalNo })
        assertEquals(ReceiptSyncStatus.REJECTED, device.records.find(7001, 1, 2)!!.status)
        assertEquals(ReceiptSyncStatus.PENDING, device.records.find(7001, 1, 3)!!.status)

        val again = sync(device) // once the cause is fixed at ZIMRA, the same receipt goes through
        assertEquals(SyncOutcome.COMPLETE, again.outcome)
        assertEquals(ReceiptSyncStatus.SUBMITTED, device.records.find(7001, 1, 2)!!.status)
    }

    @Test
    fun aStateZimraDisagreesWithSendsNothing() {
        val device = device()
        repeat(3) { device.sell() }
        sync(device)
        val older = stateStorage.keys().associateWith { stateStorage.read(it) }
        repeat(2) { device.sell() }
        sync(device)
        stateStorage.write(older)
        val restored = device()
        restored.sell()
        val before = zimra.requests.size
        val report = sync(restored)
        assertEquals(SyncOutcome.BLOCKED, report.outcome)
        assertTrue(report.problems.toString(), report.problems.any { "older" in it })
        assertEquals(listOf("GetStatus"), endpoints().drop(before))
    }

    @Test
    fun zimrasConfigurationIsAppliedBeforeADayOpens() {
        val device = device()
        device.sell()
        zimra.taxes = """[{"taxID":1,"taxPercent":15,"taxName":"Standard rated 15%","taxValidFrom":"2024-01-01"},
            {"taxID":4,"taxPercent":5,"taxName":"Reduced 5%","taxValidFrom":"2026-10-01"}]"""
        val report = sync(device)
        assertEquals(listOf(1, 4), report.configuration!!.taxes.map { it.taxId })
        assertEquals(listOf(1, 4), device.configuration.taxes.map { it.taxId })
    }

    @Test
    fun aChangedVatRegistrationClosesTheDay() {
        val device = device()
        device.sell()
        sync(device)
        device.sell()
        assertTrue(device.requestDayClose())
        sync(device)
        zimra.settle()
        sync(device)
        device.sell() // day 2
        zimra.isVatPayer = false
        sync(device) // opening day 2 fetches the configuration
        assertFalse(device.configuration.isVatPayer)
        val day = device.state()!!.currentDay!!
        assertEquals(DayCloseReason.VAT_REGISTRATION_CHANGED, day.closeReason)
    }

    @Test
    fun issuingIsNotHeldUpBySync() {
        val device = device()
        device.sell()
        val inside = CountDownLatch(1)
        val release = CountDownLatch(1)
        val slow = FdmsTransport { request ->
            if (request.url.endsWith("SubmitReceipt")) {
                inside.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
            zimra.execute(request)
        }
        val pool = Executors.newSingleThreadExecutor()
        try {
            val running = pool.submit<SyncReport> { sync(device, slow) }
            assertTrue(inside.await(5, TimeUnit.SECONDS))
            assertEquals("a sale goes through mid-sync", 2, device.sell().globalNo)
            assertEquals("a second sync does not run alongside", SyncOutcome.BUSY, sync(device).outcome)
            release.countDown()
            assertEquals(SyncOutcome.COMPLETE, running.get(5, TimeUnit.SECONDS).outcome)
        } finally {
            pool.shutdownNow()
        }
        assertEquals(listOf(1, 2), zimra.days.single().receipts.map { it.globalNo })
        assertTrue(device.pendingReceipts().isEmpty())
    }

    @Test
    fun theTimeLimitIsRespected() {
        val device = device()
        repeat(3) { device.sell() }
        val report = FiscalSync(device, client()).run(java.time.Duration.ZERO)
        assertEquals(SyncOutcome.WAITING, report.outcome)
        assertTrue(report.pendingReceipts > 0)
        assertEquals(SyncOutcome.COMPLETE, sync(device).outcome)
    }

    @Test
    fun theClientMustBeForTheSameDevice() {
        val other = FdmsClient(identity.copy(deviceId = 7002), zimra, "https://fdms.test")
        val error = runCatching { FiscalSync(device(), other) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }
}
