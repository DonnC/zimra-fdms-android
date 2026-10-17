package org.donnc.fzimra.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.Duration
import java.time.Instant

class TextStateStoreContractTest : FiscalStateStoreContract() {
    override fun newStore(): FiscalStateStore = TextStateStore(InMemoryTextStorage())
}

class TextRecordStoreContractTest : ReceiptRecordStoreContract() {
    override fun newStore(): ReceiptRecordStore = TextRecordStore(InMemoryTextStorage())
}

class TextStateStoreTest {
    @Test
    fun aFailedWriteLeavesThePreviousSnapshot() {
        val storage = InMemoryTextStorage()
        val store = TextStateStore(storage)
        val first = sampleSnapshot(version = 3)
        store.write(first)
        storage.failWrites = true
        assertThrows(FiscalStoreException::class.java) { store.write(sampleSnapshot(version = 4)) }
        assertEquals(first, store.read(7001))
    }

    @Test
    fun aSnapshotIsOneWrite() {
        val storage = InMemoryTextStorage()
        TextStateStore(storage).write(sampleSnapshot())
        assertEquals(1, storage.writes)
        assertEquals(1, storage.keys().size)
    }
}

class TextRecordStoreRetentionTest {
    private var now = Instant.parse("2026-10-17T12:00:00Z")
    private val storage = InMemoryTextStorage()
    private val store = TextRecordStore(storage, Duration.ofDays(7)) { now }

    private fun daysAgo(days: Long): Instant = now.minus(Duration.ofDays(days))

    @Test
    fun acceptedReceiptsOlderThanTheRetentionAreRemoved() {
        store.save(sampleRecord(1, ReceiptSyncStatus.SUBMITTED, daysAgo(8)))
        now = now.plus(Duration.ofHours(2))
        store.save(sampleRecord(2, ReceiptSyncStatus.SUBMITTED, now))
        assertNull(store.find(7001, 4, 1))
        assertNotNull(store.find(7001, 4, 2))
    }

    @Test
    fun recentAcceptedReceiptsAreKept() {
        store.save(sampleRecord(1, ReceiptSyncStatus.SUBMITTED, daysAgo(6)))
        now = now.plus(Duration.ofHours(2))
        store.save(sampleRecord(2, ReceiptSyncStatus.SUBMITTED, now))
        assertNotNull(store.find(7001, 4, 1))
    }

    @Test
    fun pendingAndRejectedReceiptsAreNeverRemoved() {
        store.save(sampleRecord(1, ReceiptSyncStatus.PENDING, daysAgo(30)))
        store.save(sampleRecord(2, ReceiptSyncStatus.REJECTED, daysAgo(30)))
        now = now.plus(Duration.ofHours(2))
        store.save(sampleRecord(3, ReceiptSyncStatus.SUBMITTED, now))
        assertEquals(listOf(1, 2, 3), store.list(7001).map { it.receipt.globalNo })
    }

    @Test
    fun theRecordBeingSavedIsNeverRemovedEvenIfOld() {
        store.save(sampleRecord(1, ReceiptSyncStatus.SUBMITTED, daysAgo(10)))
        assertNotNull(store.find(7001, 4, 1))
    }

    @Test
    fun pruningRunsAtMostOnceAnHour() {
        store.save(sampleRecord(1, ReceiptSyncStatus.SUBMITTED, now)) // prunes now
        now = now.plus(Duration.ofMinutes(10))
        storage.write(mapOf("fzimra.record.7001.0000000004.0000000009" to StoreCodec.encodeRecord(sampleRecord(9, ReceiptSyncStatus.SUBMITTED, daysAgo(9)))))
        store.save(sampleRecord(2, ReceiptSyncStatus.SUBMITTED, now)) // within the hour: no pruning
        assertNotNull(store.find(7001, 4, 9))
        now = now.plus(Duration.ofHours(1))
        store.save(sampleRecord(3, ReceiptSyncStatus.SUBMITTED, now))
        assertNull(store.find(7001, 4, 9))
    }
}
