package org.donnc.fzimra.core

import java.time.Duration
import java.time.Instant

/** Text storage behind fzimra's own stores: on Android, private no-backup files. */
internal interface TextStorage {
    fun read(key: String): String?

    fun keys(): Set<String>

    /**
     * Applies [changes] (a `null` value removes the key) and returns only once
     * they are on persistent storage.
     *
     * @throws FiscalStoreException if the changes could not be written.
     */
    fun write(changes: Map<String, String?>)
}

/**
 * fzimra's own [FiscalStateStore]: one JSON snapshot per device, written in a
 * single atomic update. Never pruned.
 */
internal class TextStateStore(private val storage: TextStorage) : FiscalStateStore {
    override fun read(deviceId: Int): FiscalSnapshot? = storage.read(key(deviceId))?.let(StoreCodec::decodeSnapshot)

    override fun write(snapshot: FiscalSnapshot) {
        storage.write(mapOf(key(snapshot.state.deviceId) to StoreCodec.encodeSnapshot(snapshot)))
    }

    private fun key(deviceId: Int) = "fzimra.state.$deviceId"
}

/**
 * fzimra's own [ReceiptRecordStore]: one JSON document per receipt.
 *
 * Records of receipts accepted by ZIMRA are removed once they are older than
 * [retention]. Records of receipts that are pending or rejected are kept,
 * whatever their age.
 */
internal class TextRecordStore(
    private val storage: TextStorage,
    private val retention: Duration = DEFAULT_RETENTION,
    private val now: () -> Instant = Instant::now,
) : ReceiptRecordStore {
    private var lastPrune: Instant? = null

    override fun save(record: ReceiptRecord) {
        val changes = LinkedHashMap<String, String?>()
        changes[key(record.receipt.deviceId, record.receipt.fiscalDayNo, record.receipt.globalNo)] = StoreCodec.encodeRecord(record)
        val current = now()
        if (lastPrune == null || Duration.between(lastPrune, current) >= PRUNE_INTERVAL) {
            expiredKeys(current).forEach { if (it !in changes) changes[it] = null }
            lastPrune = current
        }
        storage.write(changes)
    }

    override fun find(deviceId: Int, fiscalDayNo: Int, globalNo: Int): ReceiptRecord? =
        storage.read(key(deviceId, fiscalDayNo, globalNo))?.let(StoreCodec::decodeRecord)

    override fun list(deviceId: Int): List<ReceiptRecord> {
        val prefix = "$PREFIX$deviceId."
        return storage.keys().filter { it.startsWith(prefix) }.sorted()
            .mapNotNull { storage.read(it)?.let(StoreCodec::decodeRecord) }
    }

    private fun expiredKeys(current: Instant): List<String> {
        val cutoff = current.minus(retention)
        return storage.keys().filter { it.startsWith(PREFIX) }.filter { key ->
            val record = storage.read(key)?.let { runCatching { StoreCodec.decodeRecord(it) }.getOrNull() }
            record != null && record.status == ReceiptSyncStatus.SUBMITTED && record.updatedAt.isBefore(cutoff)
        }
    }

    private fun key(deviceId: Int, fiscalDayNo: Int, globalNo: Int) =
        "$PREFIX$deviceId.${fiscalDayNo.toString().padStart(10, '0')}.${globalNo.toString().padStart(10, '0')}"

    companion object {
        val DEFAULT_RETENTION: Duration = Duration.ofDays(7)
        private val PRUNE_INTERVAL: Duration = Duration.ofHours(1)
        private const val PREFIX = "fzimra.record."
    }
}
