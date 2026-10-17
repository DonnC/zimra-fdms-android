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

/** Why a fiscal day is being closed. */
public enum class DayCloseReason {
    /** The app asked for it with `FiscalDevice.requestDayClose()`. */
    REQUESTED,

    /** The day was about to pass ZIMRA's maximum fiscal day length. */
    MAX_DAY_LENGTH,

    /**
     * The global receipt number reached its limit during the day. Numbering
     * may only start again at 1 in a new fiscal day, so the day was closed to
     * keep issuing. Worth telling the user: a Z report appears mid-trading.
     */
    GLOBAL_NUMBER_LIMIT,

    /**
     * ZIMRA reported a change in the taxpayer's VAT registration. ZIMRA
     * requires the day to be closed and a new one opened before receipts
     * reflect it.
     */
    VAT_REGISTRATION_CHANGED,
}

/**
 * The state of one fiscal day.
 *
 * @property receiptCounter number of receipts issued in the day.
 * @property lastReceiptHash hash of the day's last receipt; `null` before the
 *   first receipt of the day.
 * @property counters the day's fiscal counters so far.
 * @property closeReason why the day is being closed; `null` while it is open.
 * @property zimraOpened whether ZIMRA has acknowledged the day's opening.
 * @property blockingReceipts receipts ZIMRA marked Grey or Red. ZIMRA does not
 *   let the device close a day that has any; it must then be closed in ZIMRA's
 *   portal.
 * @property closeSignature the device's signature on the day's close, once sent.
 * @property closingErrorCode why ZIMRA refused the last close, if it did.
 */
public data class FiscalDayState(
    val fiscalDayNo: Int,
    val openedAt: LocalDateTime,
    val status: FiscalDayStatus,
    val receiptCounter: Int,
    val lastReceiptHash: String?,
    val counters: List<FiscalCounter>,
    val closeReason: DayCloseReason? = null,
    val zimraOpened: Boolean = false,
    val blockingReceipts: Int = 0,
    val closeSignature: DeviceSignature? = null,
    val closingErrorCode: String? = null,
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
 * @property closingDays earlier fiscal days whose close ZIMRA has not yet
 *   confirmed, oldest first. Their counters are needed to close them.
 */
public data class DeviceState(
    val deviceId: Int,
    val lastGlobalNo: Int,
    val lastReceiptDate: LocalDateTime?,
    val lastFiscalDayNo: Int,
    val currentDay: FiscalDayState?,
    val closingDays: List<FiscalDayState> = emptyList(),
)

/**
 * Everything a device must never lose: its numbering state and the receipts
 * ZIMRA has not yet accepted. Saved and restored as one unit.
 *
 * @property version increases by one with every change; lets a store and the
 *   SDK detect a lost or repeated write.
 * @property outbox issued receipts not yet accepted by ZIMRA, in issuing order.
 * @property seal fzimra's tamper-evident seal over the rest of the snapshot.
 *   Stores keep it as given; fzimra refuses a snapshot whose seal does not match.
 */
public data class FiscalSnapshot(
    val version: Long,
    val state: DeviceState,
    val outbox: List<IssuedReceipt>,
    val seal: String? = null,
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
 * @property zimraSignature ZIMRA's signature on the accepted receipt.
 * @property zimraSignatureVerified whether [zimraSignature] was checked
 *   against ZIMRA's signing certificate and found valid.
 * @property seal fzimra's tamper-evident seal over the rest of the record.
 *   Stores keep it as given; fzimra refuses a record whose seal does not match.
 */
public data class ReceiptRecord(
    val receipt: IssuedReceipt,
    val status: ReceiptSyncStatus,
    val zimraReceiptId: Long? = null,
    val serverDate: LocalDateTime? = null,
    val validations: List<ZimraValidation> = emptyList(),
    val updatedAt: Instant,
    val zimraSignature: ZimraSignature? = null,
    val zimraSignatureVerified: Boolean = false,
    val seal: String? = null,
)
