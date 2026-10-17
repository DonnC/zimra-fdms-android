package org.donnc.fzimra.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Duration
import java.time.LocalDateTime

class BackgroundTriggersTest {
    private var now = LocalDateTime.of(2026, 10, 17, 8, 30, 0)
    private val configuration = DeviceConfiguration(7001, "https://fdmstest.zimra.co.zw", TAXES, isVatPayer = true, dayMaxHours = 24)
    private val device = FiscalDevice(
        configuration, SoftwareSigner.ecP256(), TextStateStore(InMemoryTextStorage()), TextRecordStore(InMemoryTextStorage()),
        SoftwareSealer(), { now },
    )
    private var calls = 0
    private val listener: () -> Unit = { calls++ }

    private fun sell() = device.issue(invoice(listOf(bread(1, d("1.00"))))).also { now = now.plusMinutes(1) }

    @Test
    fun newWorkIsAnnounced() {
        device.addWorkListener(listener)
        sell()
        assertEquals("a sale", 1, calls)
        device.issue(invoice(listOf(bread(1, d("1.00"))), payments = listOf(Payment.cash(d("0.50")))))
        assertEquals("a refused sale is not work", 1, calls)
        device.requestDayClose()
        assertEquals("a close request", 2, calls)
        device.requestDayClose()
        assertEquals("nothing new", 2, calls)
    }

    @Test
    fun aRetriedCloseAndAVatChangeAreAnnounced() {
        sell()
        device.addWorkListener(listener)
        device.updateConfiguration(configuration.copy(qrUrl = "https://elsewhere"))
        assertEquals("other changes are not work", 0, calls)
        device.updateConfiguration(configuration.copy(isVatPayer = false))
        assertEquals("the VAT change closes the day", 1, calls)
        assertEquals(false, device.retryDayClose(1))
        assertEquals(1, calls)
    }

    @Test
    fun aFailingListenerChangesNothing() {
        device.addWorkListener { throw IllegalStateException("app bug") }
        device.addWorkListener(listener)
        assertEquals(true, sell() is IssueResult.Issued)
        assertEquals(1, calls)
        device.removeWorkListener(listener)
        sell()
        assertEquals(1, calls)
    }

    @Test
    fun theScheduleFollowsEachOutcome() {
        val schedule = SyncSchedule()
        assertEquals(NextSync.WhenWorkArrives, schedule.after(SyncReport(SyncOutcome.COMPLETE)))
        assertEquals(NextSync.After(Duration.ofSeconds(30)), schedule.after(SyncReport(SyncOutcome.WAITING)))
        assertEquals(NextSync.WhenOnline, schedule.after(SyncReport(SyncOutcome.OFFLINE)))
        assertEquals(NextSync.After(Duration.ofMinutes(30)), schedule.after(SyncReport(SyncOutcome.BLOCKED)))
        assertEquals(NextSync.After(Duration.ofSeconds(30)), schedule.after(SyncReport(SyncOutcome.BUSY)))
        val watching = SyncSchedule(openDay = Duration.ofHours(1))
        assertEquals(NextSync.After(Duration.ofHours(1)), watching.after(SyncReport(SyncOutcome.COMPLETE)))
    }
}
