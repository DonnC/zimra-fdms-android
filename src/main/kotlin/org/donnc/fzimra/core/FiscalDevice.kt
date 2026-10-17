package org.donnc.fzimra.core

import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap

/**
 * The device's configuration, as ZIMRA provides it.
 *
 * @property qrUrl the receipt verification URL printed in QR codes.
 * @property taxes the taxes the device may apply.
 * @property isVatPayer whether the taxpayer is registered for VAT.
 * @property dayMaxHours the longest a fiscal day may stay open, if limited.
 */
public data class DeviceConfiguration(
    val deviceId: Int,
    val qrUrl: String,
    val taxes: List<TaxDefinition>,
    val isVatPayer: Boolean,
    val dayMaxHours: Int? = null,
) {
    init {
        if (deviceId <= 0) throw FiscalValueException("deviceID must be positive, got $deviceId")
        if (qrUrl.isBlank()) throw FiscalValueException("qrUrl is blank")
        if (dayMaxHours != null && dayMaxHours <= 0) throw FiscalValueException("dayMaxHours must be positive")
    }

    /** What receipts are prepared against. */
    public val receiptContext: ReceiptContext get() = ReceiptContext(taxes, isVatPayer)
}

/** The outcome of [FiscalDevice.issue]. */
public sealed class IssueResult {
    /** The receipt was issued: numbered, signed and saved. */
    public data class Issued(val receipt: IssuedReceipt) : IssueResult()

    /** The receipt was not issued; nothing changed. [problems] lists every reason. */
    public data class Refused(val problems: List<ReceiptProblem>) : IssueResult()
}

/**
 * A ZIMRA fiscal device: issues receipts that are numbered, chained, dated,
 * signed and saved in one step.
 *
 * Everything that can be derived is filled in: the fiscal day (opened on the
 * first sale, and rolled over before ZIMRA's maximum day length), the receipt
 * counter and global number, the receipt date (never earlier than the
 * previous receipt), the previous receipt's hash, the signature, the QR code,
 * the verification code and the fiscal counters.
 *
 * Issuing is serialised per device ID across the whole process, and the saved
 * state is re-read for every receipt, so two instances for the same device
 * cannot issue the same number.
 *
 * @param signer the device key.
 * @param stateStore where the device state and unsent receipts are kept.
 * @param recordStore where receipt records are kept.
 * @param clock the time receipts are dated with.
 * @param dayCloseMargin how long before ZIMRA's maximum day length a new
 *   fiscal day is started.
 */
public class FiscalDevice(
    public val configuration: DeviceConfiguration,
    private val signer: Signer,
    private val stateStore: FiscalStateStore,
    private val recordStore: ReceiptRecordStore,
    private val clock: FiscalClock = ZimbabweClock(),
    private val dayCloseMargin: Duration = Duration.ofHours(1),
) {
    private val deviceId: Int get() = configuration.deviceId
    private val lock: Any get() = LOCKS.computeIfAbsent(deviceId) { Any() }

    /** Records of issued receipts, read-only. */
    public val records: ReceiptRecords get() = recordStore

    /** The device's current state, or `null` before its first receipt. */
    public fun state(): DeviceState? = stateStore.read(deviceId)?.state

    /** Receipts issued but not yet accepted by ZIMRA, in issuing order. */
    public fun pendingReceipts(): List<IssuedReceipt> = stateStore.read(deviceId)?.outbox.orEmpty()

    /**
     * Continues numbering after another system used this ZIMRA device. Only
     * allowed before the device's first receipt.
     *
     * @param lastGlobalNo the global number of the last receipt the device issued.
     * @param lastFiscalDayNo the number of the device's last fiscal day.
     * @throws IllegalStateException if this device has already issued receipts.
     */
    public fun startFrom(lastGlobalNo: Int, lastFiscalDayNo: Int) {
        if (lastGlobalNo < 0 || lastFiscalDayNo < 0) throw FiscalValueException("numbers cannot be negative")
        synchronized(lock) {
            if (stateStore.read(deviceId) != null) {
                throw IllegalStateException("device $deviceId already has fiscal state; numbering cannot be reset")
            }
            stateStore.write(FiscalSnapshot(1, DeviceState(deviceId, lastGlobalNo, null, lastFiscalDayNo, null), emptyList()))
        }
    }

    /**
     * Asks for the current fiscal day to be closed. The next receipt starts a
     * new fiscal day; the closing day is kept until ZIMRA confirms the close.
     *
     * @return `true` if a day was open and is now marked for closing.
     */
    public fun requestDayClose(): Boolean = synchronized(lock) {
        val snapshot = stateStore.read(deviceId) ?: return false
        val day = snapshot.state.currentDay ?: return false
        if (!day.status.acceptsReceipts()) return false
        if (day.isUnannouncedAndEmpty()) return false // nothing for ZIMRA to close
        stateStore.write(
            snapshot.copy(
                version = snapshot.version + 1,
                state = snapshot.state.copy(currentDay = day.copy(status = FiscalDayStatus.CLOSE_REQUESTED)),
            ),
        )
        true
    }

    /**
     * Prepares and issues [request].
     *
     * On success the receipt is numbered, signed, added to the outbox of
     * receipts to send to ZIMRA and recorded, all saved before this returns.
     * A refused request changes nothing.
     *
     * @throws FiscalSigningException if the device key cannot sign.
     * @throws FiscalStoreException if the device state cannot be saved; the
     *   receipt is then not issued and its number is not used.
     */
    public fun issue(request: ReceiptRequest): IssueResult = synchronized(lock) {
        val now = clock.now()
        val snapshot = stateStore.read(deviceId) ?: FiscalSnapshot(0, DeviceState(deviceId, 0, null, 0, null), emptyList())
        var state = snapshot.state

        val prepared = when (val result = ReceiptBuilder.prepare(request, configuration.receiptContext, now.toLocalDate())) {
            is ReceiptResult.Refused -> return IssueResult.Refused(result.problems)
            is ReceiptResult.Prepared -> result.receipt
        }

        state = dayForReceipt(state, now)
        val day = checkNotNull(state.currentDay)
        val date = receiptDate(now, state.lastReceiptDate, day.openedAt)
        val globalNo = state.lastGlobalNo + 1
        val counter = day.receiptCounter + 1

        val canonical = SignatureStrings.receipt(
            deviceId, prepared.type, prepared.currency, globalNo, date, prepared.total, prepared.taxes, day.lastReceiptHash,
        )
        val signature = FiscalSignature.sign(signer, canonical)
        val qrData = ReceiptQr.qrData(signature.signature)
        val issued = IssuedReceipt(
            deviceId = deviceId,
            fiscalDayNo = day.fiscalDayNo,
            receiptCounter = counter,
            globalNo = globalNo,
            date = date,
            receipt = prepared,
            previousReceiptHash = day.lastReceiptHash,
            signature = signature,
            qrCode = ReceiptQr.url(configuration.qrUrl, deviceId, date.toLocalDate(), globalNo, qrData),
            verificationCode = ReceiptQr.verificationCode(qrData),
        )

        val nextDay = day.copy(
            receiptCounter = counter,
            lastReceiptHash = signature.hash,
            counters = FiscalCounters.merge(day.counters, FiscalCounters.of(prepared)),
        )
        stateStore.write(
            FiscalSnapshot(
                version = snapshot.version + 1,
                state = state.copy(lastGlobalNo = globalNo, lastReceiptDate = date, currentDay = nextDay),
                outbox = snapshot.outbox + issued,
            ),
        )

        // The outbox is what fzimra relies on to submit the receipt; the record is
        // history. A failed record write must not undo an issued receipt.
        try {
            recordStore.save(ReceiptRecord(issued, ReceiptSyncStatus.PENDING, updatedAt = Instant.now()))
        } catch (e: RuntimeException) {
            // Kept in the outbox; the record is written again when ZIMRA's answer arrives.
        }
        IssueResult.Issued(issued)
    }

    /**
     * The state with a fiscal day that accepts receipts at [now]: the current
     * day, or a new one when there is none, the current day is closing, or it
     * would pass ZIMRA's maximum day length.
     */
    private fun dayForReceipt(state: DeviceState, now: LocalDateTime): DeviceState {
        val day = state.currentDay
        if (day != null && day.isUnannouncedAndEmpty()) {
            // Not yet known to ZIMRA and still empty: its opening is the first sale.
            return state.copy(currentDay = day.copy(openedAt = now))
        }
        if (day != null && day.status.acceptsReceipts() && !tooLong(day, now)) return state

        val closing = when {
            day == null -> state.closingDays
            day.status == FiscalDayStatus.CLOSED -> state.closingDays
            day.status.acceptsReceipts() -> state.closingDays + day.copy(status = FiscalDayStatus.CLOSE_REQUESTED)
            else -> state.closingDays + day
        }
        val number = state.lastFiscalDayNo + 1
        return state.copy(
            lastFiscalDayNo = number,
            currentDay = FiscalDayState(number, now, FiscalDayStatus.OPEN_PENDING, 0, null, emptyList()),
            closingDays = closing,
        )
    }

    private fun tooLong(day: FiscalDayState, now: LocalDateTime): Boolean {
        val maxHours = configuration.dayMaxHours ?: return false
        return !now.isBefore(day.openedAt.plusHours(maxHours.toLong()).minus(dayCloseMargin))
    }

    /** Not earlier than the day's opening, and always after the previous receipt. */
    private fun receiptDate(now: LocalDateTime, lastReceiptDate: LocalDateTime?, dayOpened: LocalDateTime): LocalDateTime {
        var date = if (now.isBefore(dayOpened)) dayOpened else now
        if (lastReceiptDate != null && !date.isAfter(lastReceiptDate)) date = lastReceiptDate.plusSeconds(1)
        return date
    }

    private fun FiscalDayStatus.acceptsReceipts() = this == FiscalDayStatus.OPEN_PENDING || this == FiscalDayStatus.OPENED

    private fun FiscalDayState.isUnannouncedAndEmpty() = status == FiscalDayStatus.OPEN_PENDING && receiptCounter == 0

    private companion object {
        /** One lock per device ID for the whole process. */
        val LOCKS = ConcurrentHashMap<Int, Any>()
    }
}
