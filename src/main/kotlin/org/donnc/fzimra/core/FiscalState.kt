package org.donnc.fzimra.core

import java.time.Instant
import java.time.LocalDateTime

/** Where a fiscal day is in its life. */
public enum class FiscalDayStatus {
    /** Opened on the device; ZIMRA has not yet acknowledged the opening. */
    OPEN_PENDING,

    /** Opened and acknowledged by ZIMRA. */
    OPENED,

    /** Closing was requested; waits until every receipt is accepted. */
    CLOSE_REQUESTED,

    /** The close was sent; ZIMRA is validating it. */
    CLOSE_INITIATED,

    /** ZIMRA refused the close; it can be retried. */
    CLOSE_FAILED,

    /** Closed by ZIMRA. */
    CLOSED,
}

/**
 * The state of one fiscal day.
 *
 * @property receiptCounter number of receipts issued in the day.
 * @property lastReceiptHash hash of the day's last receipt; `null` before the
 *   first receipt of the day.
 * @property counters the day's fiscal counters so far.
 */
public data class FiscalDayState(
    val fiscalDayNo: Int,
    val openedAt: LocalDateTime,
    val status: FiscalDayStatus,
    val receiptCounter: Int,
    val lastReceiptHash: String?,
    val counters: List<FiscalCounter>,
)

/**
 * The numbering state of a fiscal device.
 *
 * @property lastGlobalNo global number of the last receipt issued; 0 before
 *   the first receipt.
 * @property lastReceiptDate date of the last receipt issued; later receipts
 *   must be dated after it.
 * @property lastFiscalDayNo number of the most recent fiscal day; 0 before the
 *   first day.
 * @property currentDay the current fiscal day, if one exists.
 */
public data class DeviceState(
    val deviceId: Int,
    val lastGlobalNo: Int,
    val lastReceiptDate: LocalDateTime?,
    val lastFiscalDayNo: Int,
    val currentDay: FiscalDayState?,
)

/**
 * Everything a device must never lose: its numbering state and the receipts
 * ZIMRA has not yet accepted. Saved and restored as one unit.
 *
 * @property version increases by one with every change; lets a store and the
 *   SDK detect a lost or repeated write.
 * @property outbox issued receipts not yet accepted by ZIMRA, in issuing order.
 */
public data class FiscalSnapshot(
    val version: Long,
    val state: DeviceState,
    val outbox: List<IssuedReceipt>,
)

/**
 * A receipt as issued: numbered, dated, chained and signed. It never changes
 * after it is issued.
 *
 * @property previousReceiptHash hash of the previous receipt in the same
 *   fiscal day; `null` for the day's first receipt.
 * @property qrCode the content of the receipt's QR code.
 * @property verificationCode the code printed for manual verification.
 */
public data class IssuedReceipt(
    val deviceId: Int,
    val fiscalDayNo: Int,
    val receiptCounter: Int,
    val globalNo: Int,
    val date: LocalDateTime,
    val receipt: PreparedReceipt,
    val previousReceiptHash: String?,
    val signature: DeviceSignature,
    val qrCode: String,
    val verificationCode: String,
)

/** Where an issued receipt stands with ZIMRA. */
public enum class ReceiptSyncStatus {
    /** Issued; not yet accepted by ZIMRA. */
    PENDING,

    /** Accepted by ZIMRA. */
    SUBMITTED,

    /** Refused by ZIMRA; needs attention. */
    REJECTED,
}

/**
 * A finding ZIMRA reported for an accepted receipt.
 *
 * @property colour ZIMRA's severity colour, for example `Yellow`, `Grey` or `Red`.
 */
public data class ZimraValidation(
    val code: String,
    val colour: String?,
    val message: String?,
)

/**
 * The record of an issued receipt and its standing with ZIMRA.
 *
 * @property zimraReceiptId the receipt ID ZIMRA assigned, once accepted.
 * @property serverDate when ZIMRA received the receipt.
 * @property updatedAt when this record last changed.
 */
public data class ReceiptRecord(
    val receipt: IssuedReceipt,
    val status: ReceiptSyncStatus,
    val zimraReceiptId: Long? = null,
    val serverDate: LocalDateTime? = null,
    val validations: List<ZimraValidation> = emptyList(),
    val updatedAt: Instant,
)
