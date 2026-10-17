package org.donnc.fzimra.core

import java.security.MessageDigest
import java.util.Base64

/**
 * Makes the tamper-evident seals fzimra puts on stored device state and
 * receipt records.
 *
 * A seal is a keyed tag, such as an HMAC-SHA256, over the stored data. Only
 * the holder of the key can make a valid one, so data edited or swapped
 * outside fzimra no longer matches its seal and is refused. The key must be
 * kept where the app cannot read it; on Android, use `KeystoreSealer`.
 *
 * [seal] must be deterministic: the same data always gives the same tag.
 */
public interface Sealer {
    /** The tag over [data]. */
    public fun seal(data: ByteArray): ByteArray
}

/** Stored fiscal data failed fzimra's integrity checks; it was not used. */
public class FiscalIntegrityException(
    /** What was found, one entry per problem. */
    public val problems: List<String>,
) : IllegalStateException("stored fiscal data failed its integrity checks: " + problems.joinToString("; "))

/** Seals snapshots and records, and checks their seals. */
internal class Seals(private val sealer: Sealer) {
    fun seal(snapshot: FiscalSnapshot): FiscalSnapshot =
        snapshot.copy(seal = tag(SNAPSHOT, StoreCodec.encodeSnapshot(snapshot.copy(seal = null))))

    fun seal(record: ReceiptRecord): ReceiptRecord =
        record.copy(seal = tag(RECORD, StoreCodec.encodeRecord(record.copy(seal = null))))

    fun isSealed(snapshot: FiscalSnapshot): Boolean =
        matches(snapshot.seal, tag(SNAPSHOT, StoreCodec.encodeSnapshot(snapshot.copy(seal = null))))

    fun isSealed(record: ReceiptRecord): Boolean =
        matches(record.seal, tag(RECORD, StoreCodec.encodeRecord(record.copy(seal = null))))

    // The kind is part of the sealed data, so a record's seal never passes for a snapshot's.
    private fun tag(kind: String, text: String): String =
        Base64.getEncoder().encodeToString(sealer.seal((kind + text).toByteArray(Charsets.UTF_8)))

    private fun matches(seal: String?, expected: String): Boolean =
        seal != null && MessageDigest.isEqual(seal.toByteArray(Charsets.US_ASCII), expected.toByteArray(Charsets.US_ASCII))

    private companion object {
        const val SNAPSHOT = "fzimra.snapshot\n"
        const val RECORD = "fzimra.record\n"
    }
}
