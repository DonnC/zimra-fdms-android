package org.donnc.fzimra.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What every [FiscalStateStore] must do. Run it against an implementation by
 * extending this class and returning a fresh, empty store from [newStore].
 */
abstract class FiscalStateStoreContract {
    abstract fun newStore(): FiscalStateStore

    @Test
    fun emptyStoreHasNoSnapshot() {
        assertNull(newStore().read(7001))
    }

    @Test
    fun returnsTheSnapshotWrittenExactly() {
        val store = newStore()
        val snapshot = sampleSnapshot()
        store.write(snapshot)
        assertEquals(snapshot, store.read(7001))
    }

    @Test
    fun aNewWriteReplacesThePreviousSnapshot() {
        val store = newStore()
        store.write(sampleSnapshot(version = 3))
        val next = sampleSnapshot(version = 4).let { it.copy(outbox = it.outbox.drop(1)) }
        store.write(next)
        assertEquals(next, store.read(7001))
    }

    @Test
    fun devicesAreKeptApart() {
        val store = newStore()
        store.write(sampleSnapshot(deviceId = 1))
        store.write(sampleSnapshot(version = 9, deviceId = 2))
        assertEquals(3L, store.read(1)?.version)
        assertEquals(9L, store.read(2)?.version)
    }

    @Test
    fun anEmptyOutboxAndNoDayAreKept() {
        val store = newStore()
        val snapshot = sampleSnapshot().let { it.copy(outbox = emptyList(), state = it.state.copy(currentDay = null, lastReceiptDate = null)) }
        store.write(snapshot)
        assertEquals(snapshot, store.read(7001))
    }
}

/**
 * What every [ReceiptRecordStore] must do. Extend and return a fresh, empty
 * store from [newStore].
 */
abstract class ReceiptRecordStoreContract {
    abstract fun newStore(): ReceiptRecordStore

    @Test
    fun findsASavedRecordExactly() {
        val store = newStore()
        val record = sampleRecord(1)
        store.save(record)
        assertEquals(record, store.find(7001, 4, 1))
    }

    @Test
    fun unknownRecordsAreNull() {
        assertNull(newStore().find(7001, 4, 1))
    }

    @Test
    fun savingAgainReplacesTheRecord() {
        val store = newStore()
        store.save(sampleRecord(1, ReceiptSyncStatus.PENDING))
        val accepted = sampleRecord(1, ReceiptSyncStatus.SUBMITTED)
        store.save(accepted)
        assertEquals(accepted, store.find(7001, 4, 1))
        assertEquals(1, store.list(7001).size)
    }

    @Test
    fun listsInIssuingOrder() {
        val store = newStore()
        listOf(12, 3, 100, 1).forEach { store.save(sampleRecord(it)) }
        assertEquals(listOf(1, 3, 12, 100), store.list(7001).map { it.receipt.globalNo })
    }

    @Test
    fun devicesAreKeptApart() {
        val store = newStore()
        store.save(sampleRecord(1, deviceId = 1))
        store.save(sampleRecord(1, deviceId = 2))
        store.save(sampleRecord(2, deviceId = 2))
        assertEquals(1, store.list(1).size)
        assertEquals(2, store.list(2).size)
        assertNull(store.find(1, 4, 2))
    }
}
