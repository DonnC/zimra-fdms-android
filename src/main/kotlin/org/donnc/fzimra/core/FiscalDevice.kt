package org.donnc.fzimra.core

import java.math.BigDecimal
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
    /**
     * The receipt was issued: numbered, signed and saved.
     *
     * @property warnings checks that could not be made, for example a credit
     *   note whose original receipt the device has no record of.
     */
    public data class Issued(val receipt: IssuedReceipt, val warnings: List<ReceiptProblem> = emptyList()) : IssueResult()

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
 * Stored state and records are sealed with [sealer]. Every read checks the
 * seal, and the first read of each instance also checks every unsent receipt
 * against the device key and against the receipts around it. Data that fails
 * is never used: reads throw [FiscalIntegrityException].
 *
 * @param signer the device key.
 * @param stateStore where the device state and unsent receipts are kept.
 * @param recordStore where receipt records are kept.
 * @param sealer seals stored state and records; its key must stay with this device.
 * @param clock the time receipts are dated with.
 * @param dayCloseMargin how long before ZIMRA's maximum day length a new
 *   fiscal day is started.
 * @param numbering when the global receipt number starts again at 1.
 */
public class FiscalDevice(
    public val configuration: DeviceConfiguration,
    private val signer: Signer,
    private val stateStore: FiscalStateStore,
    private val recordStore: ReceiptRecordStore,
    sealer: Sealer,
    private val clock: FiscalClock = ZimbabweClock(),
    private val dayCloseMargin: Duration = Duration.ofHours(1),
    public val numbering: GlobalNumbering = GlobalNumbering(),
) {
    private val deviceId: Int get() = configuration.deviceId
    private val lock: Any get() = LOCKS.computeIfAbsent(deviceId) { Any() }
    private val seals = Seals(sealer)

    @Volatile
    private var receiptsChecked = false

    /**
     * Records of issued receipts, read-only.
     *
     * @throws FiscalIntegrityException from a read that meets a record whose
     *   seal does not match.
     */
    public val records: ReceiptRecords = object : ReceiptRecords {
        override fun find(deviceId: Int, fiscalDayNo: Int, globalNo: Int): ReceiptRecord? =
            recordStore.find(deviceId, fiscalDayNo, globalNo)?.also(::requireSealed)

        override fun list(deviceId: Int): List<ReceiptRecord> = recordStore.list(deviceId).onEach(::requireSealed)
    }

    /**
     * The device's current state, or `null` before its first receipt.
     *
     * @throws FiscalIntegrityException if the stored state fails its checks.
     */
    public fun state(): DeviceState? = read()?.state

    /**
     * Receipts issued but not yet accepted by ZIMRA, in issuing order.
     *
     * @throws FiscalIntegrityException if the stored state fails its checks.
     */
    public fun pendingReceipts(): List<IssuedReceipt> = read()?.outbox.orEmpty()

    /** The stored snapshot, checked; `null` if there is none. */
    private fun read(): FiscalSnapshot? {
        val snapshot = stateStore.read(deviceId) ?: return null
        if (!seals.isSealed(snapshot)) {
            throw FiscalIntegrityException(listOf("the device state was changed outside fzimra, or its seal is missing"))
        }
        if (!receiptsChecked) {
            val problems = IntegrityCheck.problems(snapshot, deviceId, signer.publicKey)
            if (problems.isNotEmpty()) throw FiscalIntegrityException(problems)
            receiptsChecked = true
        }
        return snapshot
    }

    private fun write(snapshot: FiscalSnapshot) = stateStore.write(seals.seal(snapshot))

    private fun requireSealed(record: ReceiptRecord) {
        if (!seals.isSealed(record)) {
            throw FiscalIntegrityException(
                listOf("the record of receipt ${record.receipt.fiscalDayNo}/${record.receipt.globalNo} was changed outside fzimra"),
            )
        }
    }

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
        if (lastGlobalNo > numbering.limit) throw FiscalValueException("lastGlobalNo is above the global number limit ${numbering.limit}")
        synchronized(lock) {
            if (stateStore.read(deviceId) != null) {
                throw IllegalStateException("device $deviceId already has fiscal state; numbering cannot be reset")
            }
            write(FiscalSnapshot(1, DeviceState(deviceId, lastGlobalNo, null, lastFiscalDayNo, null), emptyList()))
        }
    }

    /**
     * Asks for the current fiscal day to be closed. The next receipt starts a
     * new fiscal day; the closing day is kept until ZIMRA confirms the close.
     *
     * @return `true` if a day was open and is now marked for closing.
     */
    public fun requestDayClose(): Boolean = synchronized(lock) {
        val snapshot = read() ?: return false
        val day = snapshot.state.currentDay ?: return false
        if (!day.status.acceptsReceipts()) return false
        if (day.isUnannouncedAndEmpty()) return false // nothing for ZIMRA to close
        write(
            snapshot.copy(
                version = snapshot.version + 1,
                state = snapshot.state.copy(
                    currentDay = day.copy(status = FiscalDayStatus.CLOSE_REQUESTED, closeReason = DayCloseReason.REQUESTED),
                ),
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
     * @throws FiscalIntegrityException if the stored state fails its checks;
     *   nothing is issued.
     */
    public fun issue(request: ReceiptRequest): IssueResult = synchronized(lock) {
        val now = clock.now()
        val snapshot = read() ?: FiscalSnapshot(0, DeviceState(deviceId, 0, null, 0, null), emptyList())
        var state = snapshot.state

        val prepared = when (val result = ReceiptBuilder.prepare(request, configuration.receiptContext, now.toLocalDate())) {
            is ReceiptResult.Refused -> return IssueResult.Refused(result.problems)
            is ReceiptResult.Prepared -> result.receipt
        }

        val warnings = mutableListOf<ReceiptProblem>()
        if (prepared.original != null) {
            val history = try {
                records.list(deviceId)
            } catch (e: FiscalIntegrityException) {
                warnings += ReceiptProblem(
                    null, "original",
                    "this device's receipt records failed their integrity checks and were not used: ${e.message}",
                )
                emptyList()
            } catch (e: RuntimeException) {
                emptyList()
            }
            val original = originalDetails(prepared.original, request.originalDetails, history)
            if (original == null) {
                warnings += ReceiptProblem(
                    null, "original",
                    "the original receipt is not recorded on this device and no details were given: " +
                        "its currency, age, taxes and remaining credit were not checked",
                )
            } else {
                val problems = OriginalReceiptRules.check(prepared, original, now.toLocalDate())
                if (problems.isNotEmpty()) return IssueResult.Refused(problems)
            }
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
        write(
            FiscalSnapshot(
                version = snapshot.version + 1,
                state = state.copy(lastGlobalNo = globalNo, lastReceiptDate = date, currentDay = nextDay),
                outbox = snapshot.outbox + issued,
            ),
        )

        // The outbox is what fzimra relies on to submit the receipt; the record is
        // history. A failed record write must not undo an issued receipt.
        try {
            recordStore.save(seals.seal(ReceiptRecord(issued, ReceiptSyncStatus.PENDING, updatedAt = Instant.now())))
        } catch (e: RuntimeException) {
            // Kept in the outbox; the record is written again when ZIMRA's answer arrives.
        }
        IssueResult.Issued(issued, warnings)
    }

    /**
     * Details of [original]: from this device's records when it has them,
     * counting earlier credit and debit notes against it; otherwise [given].
     */
    private fun originalDetails(
        original: OriginalReceipt,
        given: OriginalReceiptDetails?,
        records: List<ReceiptRecord>,
    ): OriginalReceiptDetails? {
        val record = records.firstOrNull { refersTo(original, it) } ?: return given
        val notes = records.filter { other ->
            val reference = other.receipt.receipt.original
            reference != null && refersTo(reference, record)
        }
        fun total(type: ReceiptType) = notes.filter { it.receipt.receipt.type == type }
            .fold(BigDecimal.ZERO) { sum, note -> sum.add(note.receipt.receipt.total.abs()) }
        val issued = record.receipt
        return OriginalReceiptDetails(
            currency = issued.receipt.currency,
            date = issued.date,
            total = issued.receipt.total,
            taxes = issued.receipt.taxes,
            previousCredits = total(ReceiptType.CREDIT_NOTE),
            previousDebits = total(ReceiptType.DEBIT_NOTE),
        )
    }

    /** Whether [reference] identifies the receipt of [record]. */
    private fun refersTo(reference: OriginalReceipt, record: ReceiptRecord): Boolean = when (reference) {
        is OriginalReceipt.ById -> record.zimraReceiptId == reference.receiptId
        is OriginalReceipt.ByDevice -> reference.deviceId == record.receipt.deviceId &&
            reference.fiscalDayNo == record.receipt.fiscalDayNo &&
            reference.receiptGlobalNo == record.receipt.globalNo
    }

    /**
     * The state with a fiscal day that accepts receipts at [now]: the current
     * day, or a new one when there is none, the current day is closing, or it
     * would pass ZIMRA's maximum day length.
     */
    private fun dayForReceipt(state: DeviceState, now: LocalDateTime): DeviceState {
        val day = state.currentDay
        val atLimit = state.lastGlobalNo >= numbering.limit
        if (day != null && day.isUnannouncedAndEmpty()) {
            // Not yet known to ZIMRA and still empty: its opening is the first sale,
            // which is the first receipt of a new day, so numbering may restart here.
            val restart = atLimit || numbering.restartsAt(state.lastGlobalNo)
            return state.copy(
                lastGlobalNo = if (restart) 0 else state.lastGlobalNo,
                currentDay = day.copy(openedAt = now),
            )
        }
        // Reaching the limit rolls the day over: numbering may only restart in a new day.
        if (day != null && day.status.acceptsReceipts() && !tooLong(day, now) && !atLimit) return state

        val closing = when {
            day == null -> state.closingDays
            day.status == FiscalDayStatus.CLOSED -> state.closingDays
            day.status.acceptsReceipts() -> state.closingDays + day.copy(
                status = FiscalDayStatus.CLOSE_REQUESTED,
                closeReason = if (tooLong(day, now)) DayCloseReason.MAX_DAY_LENGTH else DayCloseReason.GLOBAL_NUMBER_LIMIT,
            )
            else -> state.closingDays + day
        }
        val number = state.lastFiscalDayNo + 1
        val restart = atLimit || numbering.restartsAt(state.lastGlobalNo)
        return state.copy(
            lastGlobalNo = if (restart) 0 else state.lastGlobalNo, // the new day's first receipt is number 1
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
