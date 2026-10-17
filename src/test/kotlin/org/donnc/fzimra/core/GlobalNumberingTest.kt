package org.donnc.fzimra.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class GlobalNumberingTest {
    private val start = LocalDateTime.of(2026, 10, 17, 8, 30, 0)
    private var now = start
    private val clock = FiscalClock { now }
    private var stateStorage = InMemoryTextStorage()
    private val configuration = DeviceConfiguration(7001, "https://fdmstest.zimra.co.zw", TAXES, isVatPayer = true, dayMaxHours = 24)

    private fun device(numbering: GlobalNumbering) = FiscalDevice(
        configuration, SoftwareSigner.ecP256(), TextStateStore(stateStorage), TextRecordStore(InMemoryTextStorage()), clock,
        numbering = numbering,
    )

    private fun FiscalDevice.sell(): IssuedReceipt {
        now = now.plusMinutes(1)
        return (issue(invoice(listOf(bread(1, d("1.00"))))) as IssueResult.Issued).receipt
    }

    private fun FiscalDevice.closeDay() = assertTrue(requestDayClose())

    @Test
    fun theDefaultContinuesAcrossDaysAndIsSafe() {
        assertEquals(GlobalNumbering(GlobalNumberReset.NEAR_LIMIT, Int.MAX_VALUE), GlobalNumbering())
        val device = device(GlobalNumbering())
        device.sell()
        device.closeDay()
        assertEquals(2, device.sell().globalNo)
    }

    @Test
    fun nearLimitRestartsInTheFirstNewDayPastNinetyPercent() {
        val device = device(GlobalNumbering(limit = 10))
        repeat(9) { device.sell() } // 9 of 10 is not past 90%
        device.closeDay()
        val tenth = device.sell()
        assertEquals(10 to 2, tenth.globalNo to tenth.fiscalDayNo)
        device.closeDay()
        val restarted = device.sell()
        assertEquals(1 to 3, restarted.globalNo to restarted.fiscalDayNo)
        assertEquals(1, restarted.receiptCounter)
    }

    @Test
    fun reachingTheLimitMidDayRollsTheDayOverAndRestarts() {
        val device = device(GlobalNumbering(limit = 3))
        repeat(3) { assertEquals(1, device.sell().fiscalDayNo) }
        val next = device.sell()
        assertEquals(1 to 2, next.globalNo to next.fiscalDayNo)
        val closing = device.state()!!.closingDays.single()
        assertEquals(1 to FiscalDayStatus.CLOSE_REQUESTED, closing.fiscalDayNo to closing.status)
    }

    @Test
    fun numbersContinueAcrossFiscalDays() {
        val device = device(GlobalNumbering())
        repeat(3) {
            device.sell()
            device.closeDay()
        }
        val next = device.sell()
        assertEquals(4 to 4, next.globalNo to next.fiscalDayNo)
    }

    @Test
    fun aRestartNeverHappensMidDay() {
        val device = device(GlobalNumbering(limit = 10))
        repeat(9) { device.sell() }
        assertEquals("past 90% but the day is still open", 10, device.sell().globalNo)
        assertEquals(1, device.state()!!.currentDay!!.fiscalDayNo)
    }

    @Test
    fun neverRefusesAtTheLimitWithoutUsingANumber() {
        val device = device(GlobalNumbering(GlobalNumberReset.NEVER, limit = 2))
        device.sell()
        device.closeDay()
        assertEquals(2, device.sell().globalNo)
        val refused = device.issue(invoice(listOf(bread(1, d("1.00"))))) as IssueResult.Refused
        assertEquals("globalNo", refused.problems.single().field)
        assertEquals(2, device.state()!!.lastGlobalNo)
        assertEquals(2, device.pendingReceipts().size)
    }

    @Test
    fun anEmptyUnannouncedDayRestartsWithoutAskingZimraToCloseIt() {
        val idle = FiscalDayState(5, start.minusHours(30), FiscalDayStatus.OPEN_PENDING, 0, null, emptyList())
        TextStateStore(stateStorage).write(FiscalSnapshot(1, DeviceState(7001, 3, null, 5, idle), emptyList()))
        val receipt = device(GlobalNumbering(limit = 3)).sell()
        assertEquals(1 to 5, receipt.globalNo to receipt.fiscalDayNo)
        assertTrue(device(GlobalNumbering()).state()!!.closingDays.isEmpty())
    }

    @Test
    fun startingNumbersMustFitTheLimit() {
        assertThrows(FiscalValueException::class.java) { GlobalNumbering(limit = 0) }
        assertThrows(FiscalValueException::class.java) { device(GlobalNumbering(limit = 100)).startFrom(101, 3) }
        device(GlobalNumbering(limit = 100)).startFrom(100, 3)
        val next = device(GlobalNumbering(limit = 100)).sell()
        assertEquals("at the limit the first receipt opens a new day at 1", 1 to 4, next.globalNo to next.fiscalDayNo)
    }

    @Test
    fun restartsAtFollowsThePolicy() {
        assertFalse(GlobalNumbering(limit = 100).restartsAt(90))
        assertTrue(GlobalNumbering(limit = 100).restartsAt(91))
        assertFalse("the default limit keeps numbers running", GlobalNumbering().restartsAt(1_900_000_000))
        assertFalse(GlobalNumbering(GlobalNumberReset.NEVER, 100).restartsAt(100))
        assertTrue("no overflow at the largest limit", GlobalNumbering().restartsAt(Int.MAX_VALUE))
    }
}
