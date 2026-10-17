package org.donnc.fzimra.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.GeneralSecurityException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class FiscalDeviceTest {
    private class TestClock(var now: LocalDateTime) : FiscalClock {
        override fun now(): LocalDateTime = now
    }

    private val start = LocalDateTime.of(2026, 10, 17, 8, 30, 0)
    private val clock = TestClock(start)
    private val signer = SoftwareSigner.ecP256()
    private val stateStorage = InMemoryTextStorage()
    private val recordStorage = InMemoryTextStorage()
    private val configuration = DeviceConfiguration(7001, "https://fdmstest.zimra.co.zw", TAXES, isVatPayer = true, dayMaxHours = 24)

    private fun device(config: DeviceConfiguration = configuration, withSigner: Signer = signer) =
        FiscalDevice(config, withSigner, TextStateStore(stateStorage), TextRecordStore(recordStorage), SoftwareSealer(), clock)

    private fun sale(price: String = "10.00") = invoice(listOf(bread(1, d(price))))

    private fun FiscalDevice.sell(price: String = "10.00"): IssuedReceipt =
        when (val result = issue(sale(price))) {
            is IssueResult.Issued -> result.receipt
            is IssueResult.Refused -> error("refused: ${result.problems}")
        }

    private fun canonicalOf(r: IssuedReceipt) = SignatureStrings.receipt(
        r.deviceId, r.receipt.type, r.receipt.currency, r.globalNo, r.date, r.receipt.total, r.receipt.taxes, r.previousReceiptHash,
    )

    @Test
    fun firstReceiptOpensDayOneAndIsSignedAndSaved() {
        val device = device()
        val receipt = device.sell()
        assertEquals(1, receipt.globalNo)
        assertEquals(1, receipt.receiptCounter)
        assertEquals(1, receipt.fiscalDayNo)
        assertNull(receipt.previousReceiptHash)
        assertEquals(start, receipt.date)

        val canonical = canonicalOf(receipt)
        assertEquals(Sha256.base64(canonical), receipt.signature.hash)
        assertTrue(FiscalSignature.verify(signer.publicKey, canonical, receipt.signature.signature))
        val qrData = ReceiptQr.qrData(receipt.signature.signature)
        assertEquals(ReceiptQr.verificationCode(qrData), receipt.verificationCode)
        assertEquals("https://fdmstest.zimra.co.zw/0000007001" + "17102026" + "0000000001" + qrData, receipt.qrCode)

        val state = device.state()!!
        assertEquals(1, state.lastGlobalNo)
        assertEquals(start, state.lastReceiptDate)
        assertEquals(FiscalDayStatus.OPEN_PENDING, state.currentDay!!.status)
        assertEquals(start, state.currentDay!!.openedAt)
        assertEquals(FiscalCounters.of(receipt.receipt), state.currentDay!!.counters)
        assertEquals(listOf(receipt), device.pendingReceipts())
        assertEquals(ReceiptSyncStatus.PENDING, device.records.find(7001, 1, 1)!!.status)
    }

    @Test
    fun receiptsInADayAreCountedAndChained() {
        val device = device()
        val first = device.sell("10.00")
        clock.now = start.plusMinutes(5)
        val second = device.sell("5.00")
        assertEquals(2, second.globalNo)
        assertEquals(2, second.receiptCounter)
        assertEquals(first.signature.hash, second.previousReceiptHash)
        assertTrue(FiscalSignature.verify(signer.publicKey, canonicalOf(second), second.signature.signature))
        val day = device.state()!!.currentDay!!
        assertEquals(second.signature.hash, day.lastReceiptHash)
        assertEquals(d("15.00"), FiscalCounters.total(day.counters, FiscalCounterType.SALE_BY_TAX))
    }

    @Test
    fun receiptDatesNeverGoBackwards() {
        val device = device()
        val first = device.sell()
        val sameSecond = device.sell()
        assertEquals(first.date.plusSeconds(1), sameSecond.date)
        clock.now = start.minusMinutes(10) // the phone's clock jumped back
        val afterJumpBack = device.sell()
        assertEquals(sameSecond.date.plusSeconds(1), afterJumpBack.date)
    }

    @Test
    fun aRefusedRequestChangesNothing() {
        val device = device()
        val refused = device.issue(invoice(listOf(ReceiptLine.sale("X", 1, 1, 99))))
        assertTrue(refused is IssueResult.Refused)
        assertNull(device.state())
        device.sell()
        val version = TextStateStore(stateStorage).read(7001)!!.version
        device.issue(invoice(emptyList()))
        assertEquals(version, TextStateStore(stateStorage).read(7001)!!.version)
        assertEquals(2, device.sell().globalNo)
    }

    @Test
    fun aFailedSaveUsesNoNumber() {
        val device = device()
        device.sell()
        stateStorage.failWrites = true
        assertThrows(FiscalStoreException::class.java) { device.sell() }
        stateStorage.failWrites = false
        assertEquals(2, device.sell().globalNo)
        assertEquals(listOf(1, 2), device.pendingReceipts().map { it.globalNo })
    }

    @Test
    fun aSignerFailureUsesNoNumber() {
        val failing = object : Signer by signer {
            override fun sign(data: ByteArray): ByteArray = throw GeneralSecurityException("key invalidated")
        }
        assertThrows(FiscalSigningException::class.java) { device(withSigner = failing).sell() }
        assertNull(device().state())
        assertEquals(1, device().sell().globalNo)
    }

    @Test
    fun aFailedRecordWriteStillIssuesTheReceipt() {
        val device = device()
        recordStorage.failWrites = true
        val receipt = device.sell()
        assertEquals(listOf(receipt), device.pendingReceipts())
        recordStorage.failWrites = false
        assertNull(device.records.find(7001, 1, 1))
    }

    @Test
    fun aCloseRequestStartsANewDayAtTheNextSale() {
        val device = device()
        device.sell()
        device.sell()
        assertTrue(device.requestDayClose())
        assertFalse("already closing", device.requestDayClose())
        clock.now = start.plusHours(2)
        val next = device.sell()
        assertEquals(2, next.fiscalDayNo)
        assertEquals(1, next.receiptCounter)
        assertEquals(3, next.globalNo)
        assertNull("first receipt of a day", next.previousReceiptHash)

        val state = device.state()!!
        assertEquals(2, state.currentDay!!.fiscalDayNo)
        val closing = state.closingDays.single()
        assertEquals(1, closing.fiscalDayNo)
        assertEquals(FiscalDayStatus.CLOSE_REQUESTED, closing.status)
        assertEquals(DayCloseReason.REQUESTED, closing.closeReason)
        assertEquals(2, closing.receiptCounter)
        assertEquals(d("20.00"), FiscalCounters.total(closing.counters, FiscalCounterType.SALE_BY_TAX))
    }

    @Test
    fun nothingToCloseBeforeTheFirstReceipt() {
        assertFalse(device().requestDayClose())
    }

    @Test
    fun theDayRollsOverBeforeItsMaximumLength() {
        val device = device()
        device.sell()
        clock.now = start.plusHours(22).plusMinutes(59)
        assertEquals(1, device.sell().fiscalDayNo)
        clock.now = start.plusHours(23)
        val next = device.sell()
        assertEquals(2, next.fiscalDayNo)
        assertEquals(FiscalDayStatus.CLOSE_REQUESTED, device.state()!!.closingDays.single().status)
        assertEquals(DayCloseReason.MAX_DAY_LENGTH, device.state()!!.closingDays.single().closeReason)
        assertNull("an open day has no close reason", device.state()!!.currentDay!!.closeReason)
    }

    @Test
    fun withoutAMaximumLengthTheDayDoesNotRollOver() {
        val device = device(configuration.copy(dayMaxHours = null))
        device.sell()
        clock.now = start.plusHours(100)
        assertEquals(1, device.sell().fiscalDayNo)
    }

    @Test
    fun anEmptyDayNotYetKnownToZimraOpensAtTheFirstSale() {
        val idleDay = FiscalDayState(5, start.minusHours(30), FiscalDayStatus.OPEN_PENDING, 0, null, emptyList())
        TextStateStore(stateStorage).write(sealed(FiscalSnapshot(1, DeviceState(7001, 40, null, 5, idleDay), emptyList())))
        val device = device()
        assertFalse("nothing for ZIMRA to close", device.requestDayClose())
        val receipt = device.sell()
        assertEquals(5, receipt.fiscalDayNo)
        assertEquals(start, device.state()!!.currentDay!!.openedAt)
        assertTrue(device.state()!!.closingDays.isEmpty())
    }

    @Test
    fun numberingCanContinueFromAnotherSystem() {
        val device = device()
        device.startFrom(lastGlobalNo = 500, lastFiscalDayNo = 30)
        val receipt = device.sell()
        assertEquals(501, receipt.globalNo)
        assertEquals(31, receipt.fiscalDayNo)
        assertThrows(IllegalStateException::class.java) { device.startFrom(0, 0) }
        assertThrows(FiscalValueException::class.java) { device().startFrom(-1, 0) }
    }

    @Test
    fun numberingSurvivesARestart() {
        device().sell()
        assertEquals(2, device().sell().globalNo)
    }

    @Test
    fun twoInstancesForOneDeviceShareTheSequence() {
        val a = device()
        val b = device()
        val numbers = (1..6).map { if (it % 2 == 0) a.sell().globalNo else b.sell().globalNo }
        assertEquals((1..6).toList(), numbers)
    }

    @Test
    fun concurrentSalesGetUniqueContiguousChainedNumbers() {
        val device = device()
        val pool = Executors.newFixedThreadPool(8)
        try {
            val tasks = List(200) { Callable { device.sell() } }
            pool.invokeAll(tasks).map { it.get() }
        } finally {
            pool.shutdown()
        }
        val receipts = device.pendingReceipts().sortedBy { it.globalNo }
        assertEquals((1..200).toList(), receipts.map { it.globalNo })
        assertEquals((1..200).toList(), receipts.map { it.receiptCounter })
        receipts.zipWithNext().forEach { (previous, current) ->
            assertEquals(previous.signature.hash, current.previousReceiptHash)
            assertTrue(current.date.isAfter(previous.date))
        }
        receipts.forEach { assertTrue(FiscalSignature.verify(signer.publicKey, canonicalOf(it), it.signature.signature)) }
    }

    @Test
    fun creditNotesAreIssuedWithTheirSigns() {
        val device = device()
        val note = device.issue(
            invoice(listOf(bread(1, d("10.00"))), type = ReceiptType.CREDIT_NOTE, notes = "Returned", original = OriginalReceipt.ById(1)),
        ) as IssueResult.Issued
        assertEquals(d("-10.00"), note.receipt.receipt.total)
        val counters = device.state()!!.currentDay!!.counters
        assertEquals(d("-10.00"), FiscalCounters.total(counters, FiscalCounterType.CREDIT_NOTE_BY_TAX))
    }

    private fun creditNote(amount: String, original: OriginalReceipt, details: OriginalReceiptDetails? = null, currency: String = "USD") =
        invoice(
            listOf(bread(1, d(amount))), type = ReceiptType.CREDIT_NOTE, notes = "Returned", original = original, currency = currency,
        ).copy(originalDetails = details)

    @Test
    fun aNoteWithoutARecordOrDetailsIsIssuedWithAWarning() {
        val note = device().issue(creditNote("10.00", OriginalReceipt.ById(1))) as IssueResult.Issued
        assertEquals("original", note.warnings.single().field)
        assertNull(note.warnings.single().zimraCode)
        assertTrue("a sale has no warnings", (device().issue(sale()) as IssueResult.Issued).warnings.isEmpty())
    }

    @Test
    fun notesAreCheckedAgainstTheRecordedOriginal() {
        val device = device()
        val sale = device.sell("10.00")
        val original = OriginalReceipt.ByDevice(7001, sale.globalNo, sale.fiscalDayNo)

        val first = device.issue(creditNote("6.00", original)) as IssueResult.Issued
        assertTrue(first.warnings.isEmpty())
        val tooMuch = device.issue(creditNote("4.01", original)) as IssueResult.Refused
        assertEquals(listOf("RCPT035"), tooMuch.problems.map { it.zimraCode })
        assertEquals("a refused note uses no number", 2, device.state()!!.lastGlobalNo)
        assertTrue(device.issue(creditNote("4.00", original)) is IssueResult.Issued)
        assertEquals(listOf("RCPT035"), (device.issue(creditNote("0.01", original)) as IssueResult.Refused).problems.map { it.zimraCode })

        val otherCurrency = device.issue(creditNote("1.00", original, currency = "ZWG")) as IssueResult.Refused
        assertTrue("RCPT042" in otherCurrency.problems.map { it.zimraCode })
    }

    @Test
    fun aRecordedOriginalIsFoundByItsZimraId() {
        val device = device()
        val sale = device.sell("10.00")
        val records = TextRecordStore(recordStorage)
        val record = records.find(7001, sale.fiscalDayNo, sale.globalNo)!!
        records.save(Seals(SoftwareSealer()).seal(record.copy(status = ReceiptSyncStatus.SUBMITTED, zimraReceiptId = 555L)))

        val refused = device.issue(creditNote("10.01", OriginalReceipt.ById(555))) as IssueResult.Refused
        assertEquals(listOf("RCPT035"), refused.problems.map { it.zimraCode })
        assertTrue((device.issue(creditNote("10.00", OriginalReceipt.ById(555))) as IssueResult.Issued).warnings.isEmpty())
    }

    @Test
    fun givenDetailsAreUsedWhenTheOriginalIsNotRecorded() {
        val details = OriginalReceiptDetails("USD", start.minusMonths(2), d("8.00"), listOf(ReceiptTax(STANDARD, d("15"), d("1.04"), d("8.00"))))
        val device = device()
        val refused = device.issue(creditNote("8.50", OriginalReceipt.ById(77), details)) as IssueResult.Refused
        assertEquals(listOf("RCPT035"), refused.problems.map { it.zimraCode })
        val issued = device.issue(creditNote("8.00", OriginalReceipt.ById(77), details)) as IssueResult.Issued
        assertTrue(issued.warnings.isEmpty())
        val stale = device.issue(creditNote("1.00", OriginalReceipt.ById(78), details.copy(date = start.minusMonths(13)))) as IssueResult.Refused
        assertEquals(listOf("RCPT033"), stale.problems.map { it.zimraCode })
    }

    @Test
    fun aRecordedOriginalWinsOverGivenDetails() {
        val device = device()
        val sale = device.sell("10.00")
        val wrong = OriginalReceiptDetails("USD", start, d("100.00"), sale.receipt.taxes)
        val refused = device.issue(creditNote("10.01", OriginalReceipt.ByDevice(7001, sale.globalNo, sale.fiscalDayNo), wrong))
        assertTrue(refused is IssueResult.Refused)
    }

    @Test
    fun zimbabweClockUsesHarareTimeAndAnOffset() {
        val base = Clock.fixed(Instant.parse("2026-10-17T07:00:00.750Z"), ZoneOffset.UTC)
        val clock = ZimbabweClock(base = base)
        assertEquals(LocalDateTime.of(2026, 10, 17, 9, 0, 0), clock.now())
        clock.offset = Duration.ofSeconds(90)
        assertEquals(LocalDateTime.of(2026, 10, 17, 9, 1, 30), clock.now())
        assertEquals(LocalDateTime.of(2026, 10, 17, 7, 0), ZimbabweClock(ZoneId.of("UTC"), base).now())
        assertEquals("17102026", DateTimeFormatter.ofPattern("ddMMyyyy").format(clock.now()))
    }

    @Test
    fun configurationIsValidated() {
        assertThrows(FiscalValueException::class.java) { configuration.copy(deviceId = 0) }
        assertThrows(FiscalValueException::class.java) { configuration.copy(qrUrl = " ") }
        assertThrows(FiscalValueException::class.java) { configuration.copy(dayMaxHours = 0) }
    }
}
