package org.donnc.fzimra.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class IntegrityTest {
    private var now = LocalDateTime.of(2026, 10, 17, 8, 30, 0)
    private val clock = FiscalClock { now }
    private val signer = SoftwareSigner.ecP256()
    private val stateStorage = InMemoryTextStorage()
    private val recordStorage = InMemoryTextStorage()
    private val configuration = DeviceConfiguration(7001, "https://fdmstest.zimra.co.zw", TAXES, isVatPayer = true, dayMaxHours = 24)

    private fun device(sealer: Sealer = SoftwareSealer(), withSigner: Signer = signer) =
        FiscalDevice(configuration, withSigner, TextStateStore(stateStorage), TextRecordStore(recordStorage), sealer, clock)

    private fun FiscalDevice.sell(price: String = "10.00"): IssuedReceipt {
        now = now.plusMinutes(1)
        return (issue(invoice(listOf(bread(1, d(price))))) as IssueResult.Issued).receipt
    }

    private val stateKey get() = stateStorage.keys().single()

    private fun editStoredState(edit: (String) -> String) {
        stateStorage.write(mapOf(stateKey to edit(stateStorage.read(stateKey)!!)))
    }

    private fun stored(): FiscalSnapshot = StoreCodec.decodeSnapshot(stateStorage.read(stateKey)!!)

    private fun problemsOf(snapshot: FiscalSnapshot, key: Signer = signer) =
        IntegrityCheck.problems(snapshot, 7001, key.publicKey)

    private fun assertRefusesEverything(device: FiscalDevice) {
        assertThrows(FiscalIntegrityException::class.java) { device.state() }
        assertThrows(FiscalIntegrityException::class.java) { device.pendingReceipts() }
        assertThrows(FiscalIntegrityException::class.java) { device.requestDayClose() }
        assertThrows(FiscalIntegrityException::class.java) { device.issue(invoice(listOf(bread(1, d("1.00"))))) }
    }

    @Test
    fun genuineStateIsSealedAndPassesEveryCheck() {
        val device = device()
        repeat(3) { device.sell() }
        assertTrue(stored().seal != null)
        assertTrue(problemsOf(stored()).isEmpty())
        assertEquals(4, device().sell().globalNo) // a new instance runs the full check and continues
    }

    @Test
    fun anEditedStateIsRefusedAndNothingIsIssued() {
        val device = device()
        device.sell()
        editStoredState { it.replace("\"lastGlobalNo\":1", "\"lastGlobalNo\":0") }
        val before = stateStorage.read(stateKey)
        assertRefusesEverything(device)
        assertRefusesEverything(device())
        assertEquals("nothing was written", before, stateStorage.read(stateKey))
    }

    @Test
    fun anUnsealedOrForeignSealedStateIsRefused() {
        device().sell()
        TextStateStore(stateStorage).write(stored().copy(seal = null))
        assertRefusesEverything(device())

        TextStateStore(stateStorage).write(Seals(SoftwareSealer(ByteArray(32) { 7 })).seal(stored()))
        assertRefusesEverything(device())
    }

    @Test
    fun aDifferentSealingKeyCannotReadTheState() {
        device().sell()
        assertRefusesEverything(device(SoftwareSealer(ByteArray(32) { 9 })))
    }

    @Test
    fun aRecordSealDoesNotPassForASnapshot() {
        val device = device()
        val receipt = device.sell()
        val recordSeal = TextRecordStore(recordStorage).find(7001, receipt.fiscalDayNo, receipt.globalNo)!!.seal
        TextStateStore(stateStorage).write(stored().copy(seal = recordSeal))
        assertRefusesEverything(device())
    }

    @Test
    fun receiptsAreCheckedEvenUnderAValidSeal() {
        val device = device()
        repeat(3) { device.sell() }
        val snapshot = stored()
        val (first, second, third) = snapshot.outbox

        val reTotalled = second.copy(receipt = second.receipt.copy(total = d("99.00")))
        val withEditedReceipt = sealed(snapshot.copy(outbox = listOf(first, reTotalled, third)))
        assertEquals(listOf("receipt 1/2 does not match its hash"), problemsOf(withEditedReceipt))
        TextStateStore(stateStorage).write(withEditedReceipt)
        val refused = assertThrows(FiscalIntegrityException::class.java) { device().state() }
        assertEquals(listOf("receipt 1/2 does not match its hash"), refused.problems)
    }

    @Test
    fun aReceiptSignedByAnotherKeyIsFound() {
        val device = device()
        device.sell()
        assertEquals(listOf("receipt 1/1 is not signed by this device's key"), problemsOf(stored(), SoftwareSigner.ecP256()))
    }

    @Test
    fun aMissingReceiptBreaksTheChain() {
        val device = device()
        repeat(3) { device.sell() }
        val snapshot = stored()
        val problems = problemsOf(snapshot.copy(outbox = listOf(snapshot.outbox[0], snapshot.outbox[2])))
        assertTrue(problems.toString(), "receipt 1/3 breaks the day's receipt counter" in problems)
        assertTrue("receipt 1/3 is not chained to the previous receipt" in problems)
        assertTrue("receipt 1/3 breaks the global numbering" in problems)
    }

    @Test
    fun aMissingFiscalDayBreaksTheGlobalNumbering() {
        val device = device()
        device.sell()
        assertTrue(device.requestDayClose())
        device.sell()
        assertTrue(device.requestDayClose())
        device.sell()
        val snapshot = stored()
        assertEquals(listOf(1, 2, 3), snapshot.outbox.map { it.fiscalDayNo })
        val withoutDayTwo = snapshot.copy(outbox = listOf(snapshot.outbox[0], snapshot.outbox[2]))
        assertEquals(listOf("receipt 3/3 breaks the global numbering"), problemsOf(withoutDayTwo))
    }

    @Test
    fun theStateMustAgreeWithItsReceipts() {
        val device = device()
        repeat(2) { device.sell() }
        val snapshot = stored()
        val state = snapshot.state
        val day = state.currentDay!!
        assertEquals(
            listOf("the device's last global number does not match its last receipt"),
            problemsOf(snapshot.copy(state = state.copy(lastGlobalNo = 7))),
        )
        assertEquals(
            listOf("fiscal day 1 does not end with its last receipt"),
            problemsOf(snapshot.copy(state = state.copy(currentDay = day.copy(receiptCounter = 1)))),
        )
        assertEquals(
            listOf("the device's last receipt date does not match its last receipt"),
            problemsOf(snapshot.copy(state = state.copy(lastReceiptDate = now.minusDays(1)))),
        )
        assertEquals(
            listOf("the state belongs to device 9, not 7001"),
            problemsOf(snapshot.copy(state = state.copy(deviceId = 9))),
        )
    }

    @Test
    fun receiptsAcrossDaysAndARestartPass() {
        val device = FiscalDevice(
            configuration, signer, TextStateStore(stateStorage), TextRecordStore(recordStorage), SoftwareSealer(), clock,
            numbering = GlobalNumbering(GlobalNumberReset.EVERY_FISCAL_DAY),
        )
        device.sell()
        device.sell()
        assertTrue(device.requestDayClose())
        device.sell()
        assertEquals(listOf(1, 2, 1), stored().outbox.map { it.globalNo })
        assertTrue(problemsOf(stored()).isEmpty())
    }

    @Test
    fun anEditedRecordIsRefusedAndNotUsedForNotes() {
        val device = device()
        val sale = device.sell()
        val key = recordStorage.keys().single()
        recordStorage.write(mapOf(key to recordStorage.read(key)!!.replace("\"total\":\"10.00\"", "\"total\":\"1000.00\"")))

        assertThrows(FiscalIntegrityException::class.java) { device.records.list(7001) }
        assertThrows(FiscalIntegrityException::class.java) { device.records.find(7001, 1, 1) }

        val note = device.issue(
            invoice(
                listOf(bread(1, d("500.00"))), type = ReceiptType.CREDIT_NOTE, notes = "Returned",
                original = OriginalReceipt.ByDevice(7001, sale.globalNo, sale.fiscalDayNo),
            ),
        ) as IssueResult.Issued
        assertTrue(note.warnings.toString(), note.warnings.any { "integrity" in it.message })
    }

    @Test
    fun startFromIsSealed() {
        device().startFrom(500, 30)
        assertTrue(stored().seal != null)
        assertEquals(501, device().sell().globalNo)
    }

    @Test
    fun sealsAreStableAndKindSeparated() {
        val seals = Seals(SoftwareSealer())
        val snapshot = sampleSnapshot().copy(seal = null)
        assertEquals(seals.seal(snapshot), seals.seal(snapshot))
        assertTrue(seals.isSealed(seals.seal(snapshot)))
        assertFalse(seals.isSealed(snapshot))
        val record = sampleRecord(1).copy(seal = null)
        assertTrue(seals.isSealed(seals.seal(record)))
        assertFalse(seals.isSealed(seals.seal(record).copy(status = ReceiptSyncStatus.REJECTED)))
    }
}
