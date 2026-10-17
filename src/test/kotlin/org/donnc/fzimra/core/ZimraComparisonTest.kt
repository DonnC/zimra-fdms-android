package org.donnc.fzimra.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class ZimraComparisonTest {
    private var now = LocalDateTime.of(2026, 10, 17, 8, 30, 0)
    private val clock = FiscalClock { now }
    private val signer = SoftwareSigner.ecP256()
    private val stateStorage = InMemoryTextStorage()
    private val configuration = DeviceConfiguration(7001, "https://fdmstest.zimra.co.zw", TAXES, isVatPayer = true, dayMaxHours = 24)

    private fun device(numbering: GlobalNumbering = GlobalNumbering()) = FiscalDevice(
        configuration, signer, TextStateStore(stateStorage), TextRecordStore(InMemoryTextStorage()), SoftwareSealer(), clock,
        numbering = numbering,
    )

    private fun FiscalDevice.sell(): IssuedReceipt {
        now = now.plusMinutes(1)
        return (issue(invoice(listOf(bread(1, d("1.00"))))) as IssueResult.Issued).receipt
    }

    private fun zimra(status: ZimraDayStatus, day: Int?, globalNo: Int?) = ZimraStatus(null, status, day, globalNo, null, null)

    @Test
    fun aNewDeviceAgreesWithANewZimraDevice() {
        assertTrue(device().compareWith(zimra(ZimraDayStatus.CLOSED, null, null)).isEmpty())
    }

    @Test
    fun aDeviceWithoutStateMustContinueFromZimra() {
        val problems = device().compareWith(zimra(ZimraDayStatus.CLOSED, 45, 451))
        assertEquals(1, problems.size)
        assertTrue(problems.single(), "startFrom" in problems.single())
    }

    @Test
    fun startFromZimraContinuesItsNumbering() {
        val device = device()
        device.startFrom(zimra(ZimraDayStatus.CLOSED, 45, 451))
        val next = device.sell()
        assertEquals(452 to 46, next.globalNo to next.fiscalDayNo)
        assertTrue(device.compareWith(zimra(ZimraDayStatus.CLOSED, 45, 451)).isEmpty())
    }

    @Test
    fun aKnownHigherPreviousNumberWins() {
        val device = device()
        device.startFrom(zimra(ZimraDayStatus.CLOSED, 45, 451), previousLastGlobalNo = 460)
        assertEquals(461, device.sell().globalNo)
    }

    @Test
    fun startFromRefusesWhileZimraHasADayOpen() {
        listOf(ZimraDayStatus.OPENED, ZimraDayStatus.CLOSE_INITIATED, ZimraDayStatus.CLOSE_FAILED).forEach {
            assertThrows("$it", IllegalStateException::class.java) { device().startFrom(zimra(it, 45, 451)) }
        }
    }

    @Test
    fun aDeviceAheadOfZimraIsConsistent() {
        val device = device()
        repeat(3) { device.sell() }
        assertTrue("day not yet announced", device.compareWith(zimra(ZimraDayStatus.CLOSED, null, null)).isEmpty())
        assertTrue("day open, receipts pending", device.compareWith(zimra(ZimraDayStatus.OPENED, 1, null)).isEmpty())
        assertTrue("some receipts received", device.compareWith(zimra(ZimraDayStatus.OPENED, 1, 2)).isEmpty())
        assertTrue("all received", device.compareWith(zimra(ZimraDayStatus.OPENED, 1, 3)).isEmpty())
    }

    @Test
    fun anOlderCopyOfTheStateIsCaughtByItsReceiptNumber() {
        val device = device()
        repeat(3) { device.sell() }
        val older = stateStorage.keys().associateWith { stateStorage.read(it) }
        repeat(2) { device.sell() }
        stateStorage.write(older) // a genuine, sealed, but older snapshot
        val restored = device()
        assertEquals(3, restored.state()!!.lastGlobalNo)
        val problems = restored.compareWith(zimra(ZimraDayStatus.OPENED, 1, 5))
        assertEquals(1, problems.size)
        assertTrue(problems.single(), "older" in problems.single())
    }

    @Test
    fun anOlderCopyIsCaughtByItsReceiptNumberOnALaterDay() {
        val device = device()
        device.sell()
        assertTrue(device.requestDayClose())
        repeat(2) { device.sell() } // day 2: global numbers 2 and 3, continuing
        val older = stateStorage.keys().associateWith { stateStorage.read(it) }
        device.sell()
        stateStorage.write(older)
        assertEquals(1, device().compareWith(zimra(ZimraDayStatus.OPENED, 2, 4)).size)
        assertTrue(device().compareWith(zimra(ZimraDayStatus.OPENED, 2, 3)).isEmpty())
    }

    @Test
    fun anOlderCopyOfTheStateIsCaughtByItsFiscalDay() {
        val device = device()
        device.sell()
        val older = stateStorage.keys().associateWith { stateStorage.read(it) }
        assertTrue(device.requestDayClose())
        device.sell()
        stateStorage.write(older)
        val problems = device().compareWith(zimra(ZimraDayStatus.OPENED, 2, 2))
        assertTrue(problems.toString(), problems.any { "fiscal day 2" in it && "ends at day 1" in it })
    }

    @Test
    fun aDayZimraStillHasOpenMustBeTracked() {
        val day3 = FiscalDayState(3, now, FiscalDayStatus.OPENED, 0, null, emptyList())
        TextStateStore(stateStorage).write(sealed(FiscalSnapshot(1, DeviceState(7001, 40, null, 3, day3), emptyList())))
        val problems = device().compareWith(zimra(ZimraDayStatus.OPENED, 2, 40))
        assertEquals(listOf("ZIMRA still has fiscal day 2 FiscalDayOpened, but this device no longer tracks it"), problems)
        assertTrue("a closed day need not be tracked", device().compareWith(zimra(ZimraDayStatus.CLOSED, 2, 40)).isEmpty())
    }

    @Test
    fun aClosingDayIsStillTracked() {
        val device = device()
        device.sell()
        assertTrue(device.requestDayClose())
        device.sell()
        assertTrue(device.compareWith(zimra(ZimraDayStatus.CLOSE_INITIATED, 1, 1)).isEmpty())
    }

    @Test
    fun aRestartedNumberingIsNotMistakenForAnOlderState() {
        val device = device(GlobalNumbering(GlobalNumberReset.EVERY_FISCAL_DAY))
        repeat(3) { device.sell() }
        assertTrue(device.requestDayClose())
        assertEquals(1, device.sell().globalNo)
        // ZIMRA's last receipt is still day 1's number 3; day 2 is open with its receipt pending.
        assertTrue(device.compareWith(zimra(ZimraDayStatus.OPENED, 2, 3)).isEmpty())
    }
}
