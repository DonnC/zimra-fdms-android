package org.donnc.fzimra.core

/** A store could not read or write fiscal data. */
public class FiscalStoreException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

/**
 * Keeps a device's [FiscalSnapshot]: its numbering state and the receipts ZIMRA
 * has not yet accepted.
 *
 * This is what a fiscal device must never lose. Implementations must:
 *
 * - **write atomically**: after [write], [read] returns either the new
 *   snapshot or the previous one, never a mix, even if the process dies or
 *   the device loses power during the write;
 * - **write durably**: return from [write] only once the snapshot is on
 *   persistent storage;
 * - **never discard** a snapshot, or receipts in its outbox;
 * - **never roll back**: do not restore a snapshot from a backup or another
 *   device. An older snapshot makes the device reuse receipt numbers ZIMRA has
 *   already received. Keep the data out of cloud and device backups.
 *
 * fzimra calls the store from one thread at a time per device.
 *
 * When the app does not provide a store, fzimra keeps the snapshot itself.
 */
public interface FiscalStateStore {
    /** The last snapshot written for [deviceId], or `null` if there is none. */
    public fun read(deviceId: Int): FiscalSnapshot?

    /**
     * Replaces the snapshot of [FiscalSnapshot.state]'s device with [snapshot].
     *
     * @throws FiscalStoreException if the snapshot could not be written; the
     *   previous snapshot must then still be readable.
     */
    public fun write(snapshot: FiscalSnapshot)
}

/** Read-only access to the records of issued receipts. */
public interface ReceiptRecords {
    /** The record of [deviceId]'s receipt number [globalNo], if kept. */
    public fun find(deviceId: Int, globalNo: Int): ReceiptRecord?

    /** The records kept for [deviceId], in issuing order. */
    public fun list(deviceId: Int): List<ReceiptRecord>
}

/**
 * Keeps records of issued receipts and their standing with ZIMRA, for display
 * and lookup.
 *
 * Records are a history: receipts ZIMRA has not yet accepted are also kept in
 * the [FiscalStateStore], which is what fzimra relies on to submit them.
 *
 * When the app does not provide a store, fzimra keeps the records itself for a
 * short time.
 */
public interface ReceiptRecordStore : ReceiptRecords {
    /** Saves [record], replacing any record for the same device and global number. */
    public fun save(record: ReceiptRecord)
}
