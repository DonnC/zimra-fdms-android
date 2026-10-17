package org.donnc.fzimra.core

import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

/** How a sync run ended. */
public enum class SyncOutcome {
    /** Everything ZIMRA needs has been sent; nothing is waiting. */
    COMPLETE,

    /** Waiting on ZIMRA, for example while it validates a day's close. Run again soon. */
    WAITING,

    /** ZIMRA could not be reached or asked to try later. Run again when online. */
    OFFLINE,

    /** Something needs attention before sync can continue; see [SyncReport.problems]. */
    BLOCKED,

    /** Another sync for the same device was already running. */
    BUSY,
}

/**
 * A fiscal day ZIMRA confirmed as closed.
 *
 * @property reconciliationMode `Auto` when closed by the device, `Manual`
 *   when closed in ZIMRA's portal.
 * @property zimraSignatureVerified whether ZIMRA's signature on the day was
 *   checked against its signing certificate and found valid.
 */
public data class ClosedDay(
    val day: FiscalDayState,
    val closedAt: java.time.LocalDateTime?,
    val reconciliationMode: String?,
    val zimraSignature: ZimraSignature?,
    val zimraSignatureVerified: Boolean,
)

/**
 * What a sync run did.
 *
 * @property accepted records of the receipts ZIMRA accepted in this run.
 * @property openedDays fiscal days ZIMRA acknowledged as opened.
 * @property closedDays fiscal days ZIMRA confirmed as closed.
 * @property problems what needs attention, when [outcome] is [SyncOutcome.BLOCKED].
 * @property pendingReceipts receipts still waiting to be sent.
 * @property oldestPendingDate when the oldest of them was issued.
 * @property configuration ZIMRA's configuration, when it was fetched in this run.
 * @property error the failure, when [outcome] is [SyncOutcome.OFFLINE].
 */
public data class SyncReport(
    val outcome: SyncOutcome,
    val accepted: List<ReceiptRecord> = emptyList(),
    val openedDays: List<Int> = emptyList(),
    val closedDays: List<ClosedDay> = emptyList(),
    val problems: List<String> = emptyList(),
    val pendingReceipts: Int = 0,
    val oldestPendingDate: java.time.LocalDateTime? = null,
    val configuration: ZimraConfiguration? = null,
    val error: Exception? = null,
)

/**
 * Brings ZIMRA up to date with a [FiscalDevice]: opens its fiscal days with
 * ZIMRA, submits its receipts strictly in order, closes the days that are due
 * and follows each close until ZIMRA confirms or refuses it.
 *
 * Each [run] does as much as it safely can and stops at the first thing it
 * must wait for. It never holds up issuing: network calls are made without
 * the device's lock, and their results applied under it. Run it again
 * whenever there may be work: after a sale, when the network returns, and
 * periodically while [SyncReport.outcome] is not [SyncOutcome.COMPLETE].
 *
 * Before working with ZIMRA, each run compares the device state with ZIMRA's
 * ([FiscalDevice.compareWith]) and stops if they disagree. Before opening a
 * day it fetches ZIMRA's configuration and applies it to the device.
 *
 * Calls block: run it off the main thread.
 */
public class FiscalSync(
    private val device: FiscalDevice,
    private val client: FdmsClient,
    private val now: () -> Instant = Instant::now,
) {
    private val deviceId = device.configuration.deviceId

    init {
        require(client.identity.deviceId == deviceId) {
            "the client is for device ${client.identity.deviceId}, the fiscal device is $deviceId"
        }
    }

    /**
     * Syncs until done, blocked, waiting on ZIMRA or past [timeLimit].
     */
    public fun run(timeLimit: Duration = Duration.ofMinutes(5)): SyncReport {
        val lock = LOCKS.computeIfAbsent(deviceId) { ReentrantLock() }
        if (!lock.tryLock()) return SyncReport(SyncOutcome.BUSY)
        return try {
            Run(System.nanoTime() + timeLimit.toNanos()).execute()
        } finally {
            lock.unlock()
        }
    }

    private inner class Run(private val deadline: Long) {
        private val accepted = mutableListOf<ReceiptRecord>()
        private val opened = mutableListOf<Int>()
        private val closed = mutableListOf<ClosedDay>()
        private var configuration: ZimraConfiguration? = null
        private var status: ZimraStatus? = null

        fun execute(): SyncReport {
            var outcome: SyncOutcome
            var problems = emptyList<String>()
            var error: Exception? = null
            try {
                if (!hasWork()) return report(SyncOutcome.COMPLETE)
                val differences = device.compareWith(status())
                if (differences.isNotEmpty()) return report(SyncOutcome.BLOCKED, differences)
                while (true) {
                    val step = step()
                    if (step !is Step.Progress) {
                        outcome = step.outcome
                        problems = (step as? Step.Blocked)?.problems.orEmpty()
                        break
                    }
                    if (System.nanoTime() > deadline) {
                        outcome = SyncOutcome.WAITING
                        break
                    }
                }
            } catch (e: FdmsNetworkException) {
                outcome = SyncOutcome.OFFLINE
                error = e
            } catch (e: FdmsException) {
                if (e.isRetryable) {
                    outcome = SyncOutcome.OFFLINE
                    error = e
                } else {
                    outcome = SyncOutcome.BLOCKED
                    problems = listOf(describe(e))
                }
            } catch (e: FdmsProtocolException) {
                outcome = SyncOutcome.OFFLINE
                error = e
            } catch (e: FiscalIntegrityException) {
                outcome = SyncOutcome.BLOCKED
                problems = e.problems
            }
            return report(outcome, problems, error)
        }

        private fun report(outcome: SyncOutcome, problems: List<String> = emptyList(), error: Exception? = null) = SyncReport(
            outcome = outcome,
            accepted = accepted.toList(),
            openedDays = opened.toList(),
            closedDays = closed.toList(),
            problems = problems,
            pendingReceipts = runCatching { device.currentSnapshot()?.outbox?.size ?: 0 }.getOrDefault(0),
            oldestPendingDate = runCatching { device.oldestUnsentReceiptDate() }.getOrNull(),
            configuration = configuration,
            error = error,
        )

        private fun status(): ZimraStatus = status ?: client.getStatus().also { status = it }

        private fun freshStatus(): ZimraStatus = client.getStatus().also { status = it }

        /** Days still to deal with, earliest first. */
        private fun days(snapshot: FiscalSnapshot): List<FiscalDayState> =
            (snapshot.state.closingDays + listOfNotNull(snapshot.state.currentDay))
                .filter { it.status != FiscalDayStatus.CLOSED }
                .sortedBy { it.fiscalDayNo }

        private fun hasWork(): Boolean {
            val snapshot = device.currentSnapshot() ?: return false
            if (snapshot.outbox.isNotEmpty()) return true
            return days(snapshot).any { day ->
                when (day.status) {
                    FiscalDayStatus.OPEN_PENDING -> day.receiptCounter > 0 && !day.zimraOpened
                    // Open with ZIMRA: one status check notices a close in ZIMRA's portal.
                    FiscalDayStatus.OPENED -> day.zimraOpened
                    else -> true
                }
            }
        }

        private fun step(): Step {
            val snapshot = device.currentSnapshot() ?: return Step.Done
            val head = days(snapshot).firstOrNull() ?: return Step.Done

            if (head.status == FiscalDayStatus.CLOSE_INITIATED) return followClose(head)
            if (!head.zimraOpened) {
                if (head.receiptCounter == 0 && head.status == FiscalDayStatus.OPEN_PENDING) return Step.Done
                return open(head)
            }
            val next = snapshot.outbox.firstOrNull()
            val zimra = status()
            if (zimra.dayStatus == ZimraDayStatus.CLOSED && (zimra.lastFiscalDayNo ?: 0) >= head.fiscalDayNo) {
                // Closed at ZIMRA, for example in its portal, while the device still had it open.
                if (next != null && next.fiscalDayNo == head.fiscalDayNo) {
                    return Step.Blocked(
                        listOf(
                            "ZIMRA closed fiscal day ${head.fiscalDayNo} while receipts ${next.fiscalDayNo}/${next.globalNo} " +
                                "and later were still unsent; ZIMRA can no longer accept them into that day",
                        ),
                    )
                }
                return closedByZimra(head) ?: Step.Done
            }
            if (next != null && next.fiscalDayNo == head.fiscalDayNo) return submit(next)
            return when (head.status) {
                FiscalDayStatus.CLOSE_REQUESTED -> close(head)
                FiscalDayStatus.CLOSE_FAILED -> closedByZimra(head) ?: Step.Blocked(
                    listOf(
                        "ZIMRA refused to close fiscal day ${head.fiscalDayNo} (${head.closingErrorCode}): " +
                            "fix the cause, then call retryDayClose(${head.fiscalDayNo})",
                    ),
                )
                else -> Step.Done
            }
        }

        // -- opening ----------------------------------------------------------------

        private fun open(day: FiscalDayState): Step {
            val config = client.getConfig()
            configuration = config
            device.updateConfiguration(config.toDeviceConfiguration(deviceId))

            val status = freshStatus()
            val alreadyOpen = status.lastFiscalDayNo == day.fiscalDayNo &&
                (status.dayStatus == ZimraDayStatus.OPENED || status.dayStatus == ZimraDayStatus.CLOSE_FAILED)
            if (!alreadyOpen) {
                when (status.dayStatus) {
                    ZimraDayStatus.CLOSED -> {
                        val expected = (status.lastFiscalDayNo ?: 0) + 1
                        if (expected != day.fiscalDayNo) {
                            return Step.Blocked(
                                listOf("ZIMRA expects fiscal day $expected next, but the device's next day is ${day.fiscalDayNo}"),
                            )
                        }
                        client.openDay(day.fiscalDayNo, day.openedAt)
                    }
                    ZimraDayStatus.CLOSE_INITIATED -> return Step.Waiting
                    else -> return Step.Blocked(
                        listOf("ZIMRA has fiscal day ${status.lastFiscalDayNo} ${status.dayStatus.zimraName}; it must close before day ${day.fiscalDayNo} opens"),
                    )
                }
            }
            this.status = status.copy(dayStatus = ZimraDayStatus.OPENED, lastFiscalDayNo = day.fiscalDayNo, closingErrorCode = null)
            device.change { snapshot ->
                snapshot.copy(
                    state = snapshot.state.withDay(day.fiscalDayNo) {
                        it.copy(zimraOpened = true, status = if (it.status == FiscalDayStatus.OPEN_PENDING) FiscalDayStatus.OPENED else it.status)
                    },
                )
            }
            opened += day.fiscalDayNo
            return Step.Progress
        }

        // -- receipts ---------------------------------------------------------------

        private fun submit(receipt: IssuedReceipt): Step {
            val result = try {
                client.submitReceipt(receipt)
            } catch (e: FdmsException) {
                if (e.isRetryable) throw e
                saveRecord(receipt) {
                    it.copy(
                        status = ReceiptSyncStatus.REJECTED,
                        validations = listOf(ZimraValidation(e.errorCode ?: "HTTP${e.status}", null, e.message)),
                    )
                }
                return Step.Blocked(listOf("ZIMRA refused receipt ${receipt.fiscalDayNo}/${receipt.globalNo}: ${describe(e)}"))
            }
            val blocking = result.findings.any { it.colour.equals("Grey", true) || it.colour.equals("Red", true) }
            val applied = device.change { snapshot ->
                val head = snapshot.outbox.firstOrNull()
                if (head == null || head.signature.hash != receipt.signature.hash) return@change null // already applied
                snapshot.copy(
                    outbox = snapshot.outbox.drop(1),
                    state = if (blocking) {
                        snapshot.state.withDay(receipt.fiscalDayNo) { it.copy(blockingReceipts = it.blockingReceipts + 1) }
                    } else {
                        snapshot.state
                    },
                )
            }
            if (!applied) return Step.Progress
            val verified = result.signature?.let {
                verifies(SignatureStrings.receiptServer(receipt.signature.signature, result.receiptId, result.serverDate), it)
            } ?: false
            saveRecord(receipt) {
                it.copy(
                    status = ReceiptSyncStatus.SUBMITTED,
                    zimraReceiptId = result.receiptId,
                    serverDate = result.serverDate,
                    validations = result.findings.map { f -> ZimraValidation(f.code, f.colour, f.description) },
                    zimraSignature = result.signature,
                    zimraSignatureVerified = verified,
                )
            }?.let { accepted += it }
            return Step.Progress
        }

        /** Updates the receipt's record; a record that cannot be written is not fatal. */
        private fun saveRecord(receipt: IssuedReceipt, update: (ReceiptRecord) -> ReceiptRecord): ReceiptRecord? {
            val existing = try {
                device.records.find(deviceId, receipt.fiscalDayNo, receipt.globalNo)
            } catch (e: RuntimeException) {
                null
            }
            val record = update(existing ?: ReceiptRecord(receipt, ReceiptSyncStatus.PENDING, updatedAt = now()))
                .copy(updatedAt = now(), seal = null)
            return try {
                device.saveRecord(record)
                record
            } catch (e: RuntimeException) {
                record
            }
        }

        // -- closing ----------------------------------------------------------------

        private fun close(day: FiscalDayState): Step {
            closedByZimra(day)?.let { return it }
            if (day.blockingReceipts > 0) {
                return Step.Blocked(
                    listOf(
                        "fiscal day ${day.fiscalDayNo} has ${day.blockingReceipts} receipt(s) ZIMRA marked Grey or Red; " +
                            "ZIMRA does not let the device close it, so it must be closed in ZIMRA's portal",
                    ),
                )
            }
            val canonical = SignatureStrings.fiscalDay(deviceId, day.fiscalDayNo, day.openedAt.toLocalDate(), day.counters)
            val signature = device.sign(canonical)
            val status = try {
                client.closeDay(day.fiscalDayNo, day.counters, signature, day.receiptCounter)
                FiscalDayStatus.CLOSE_INITIATED
            } catch (e: FdmsException) {
                when {
                    e.isRetryable -> throw e
                    e.errorCode.equals("FISC03", true) -> FiscalDayStatus.CLOSE_INITIATED // a close is already in progress
                    else -> {
                        markFailed(day, e.errorCode ?: "HTTP${e.status}")
                        return Step.Blocked(listOf("ZIMRA refused to close fiscal day ${day.fiscalDayNo}: ${describe(e)}"))
                    }
                }
            }
            this.status = null
            device.change { snapshot ->
                snapshot.copy(state = snapshot.state.withDay(day.fiscalDayNo) { it.copy(status = status, closeSignature = signature, closingErrorCode = null) })
            }
            return Step.Progress
        }

        private fun followClose(day: FiscalDayState): Step {
            closedByZimra(day, fresh = true)?.let { return it }
            val status = status()
            val refused = status.lastFiscalDayNo == day.fiscalDayNo && (
                status.dayStatus == ZimraDayStatus.CLOSE_FAILED ||
                    (status.dayStatus == ZimraDayStatus.OPENED && status.closingErrorCode != null)
                )
            if (!refused) return Step.Waiting
            val code = status.closingErrorCode ?: "unknown"
            markFailed(day, code)
            return Step.Blocked(listOf("ZIMRA refused to close fiscal day ${day.fiscalDayNo}: $code"))
        }

        /** Finishes [day] if ZIMRA reports it closed (by the device or in its portal); `null` otherwise. */
        private fun closedByZimra(day: FiscalDayState, fresh: Boolean = false): Step? {
            val status = if (fresh) freshStatus() else status()
            if (status.dayStatus != ZimraDayStatus.CLOSED || (status.lastFiscalDayNo ?: 0) < day.fiscalDayNo) return null
            val mode = status.reconciliationMode
            val verified = status.serverSignature?.let { signature ->
                val closedAt = status.dayClosed ?: return@let false
                if (mode == null) return@let false
                val counters = if (mode.equals("Manual", true)) status.counters else day.counters
                verifies(
                    SignatureStrings.fiscalDayServer(
                        deviceId, day.fiscalDayNo, day.openedAt.toLocalDate(), closedAt, mode, counters, day.closeSignature?.signature,
                    ),
                    signature,
                )
            } ?: false
            device.change { snapshot ->
                val state = snapshot.state
                snapshot.copy(
                    state = state.copy(
                        closingDays = state.closingDays.filter { it.fiscalDayNo != day.fiscalDayNo },
                        currentDay = state.currentDay?.let {
                            if (it.fiscalDayNo == day.fiscalDayNo) it.copy(status = FiscalDayStatus.CLOSED) else it
                        },
                    ),
                )
            }
            closed += ClosedDay(day, status.dayClosed, mode, status.serverSignature, verified)
            return Step.Progress
        }

        private fun markFailed(day: FiscalDayState, code: String) {
            device.change { snapshot ->
                snapshot.copy(state = snapshot.state.withDay(day.fiscalDayNo) { it.copy(status = FiscalDayStatus.CLOSE_FAILED, closingErrorCode = code) })
            }
        }

        // -- ZIMRA's signatures -------------------------------------------------------

        private fun verifies(canonical: String, signature: ZimraSignature): Boolean = try {
            val certificate = certificateFor(signature.certificateThumbprint)
            certificate != null &&
                (signature.hash == null || signature.hash == Sha256.base64(canonical)) &&
                FiscalSignature.verify(certificate.publicKey, canonical, signature.signature)
        } catch (e: Exception) {
            false // unverified, not fatal: the receipt or day stands
        }

        private fun certificateFor(thumbprint: String?): X509Certificate? {
            if (thumbprint == null) return fetch(null).firstOrNull()
            CERTIFICATES[thumbprint.uppercase()]?.let { return it }
            return fetch(thumbprint).firstOrNull { matchesThumbprint(it, thumbprint) }
                ?.also { CERTIFICATES[thumbprint.uppercase()] = it }
        }

        private fun fetch(thumbprint: String?): List<X509Certificate> =
            client.getServerCertificate(thumbprint).chainPem.flatMap { DeviceCertificate.parse(it) }
    }

    private sealed class Step(val outcome: SyncOutcome) {
        object Progress : Step(SyncOutcome.COMPLETE)
        object Done : Step(SyncOutcome.COMPLETE)
        object Waiting : Step(SyncOutcome.WAITING)
        class Blocked(val problems: List<String>) : Step(SyncOutcome.BLOCKED)
    }

    private companion object {
        /** One sync at a time per device ID, for the whole process. */
        val LOCKS = ConcurrentHashMap<Int, ReentrantLock>()

        /** ZIMRA's signing certificates by thumbprint, which names exactly one certificate. */
        val CERTIFICATES = ConcurrentHashMap<String, X509Certificate>()

        fun describe(e: FdmsException): String =
            (e.message ?: "HTTP ${e.status}") + (e.operationId?.let { " (operation $it)" } ?: "")

        fun DeviceState.withDay(fiscalDayNo: Int, change: (FiscalDayState) -> FiscalDayState) = copy(
            currentDay = currentDay?.let { if (it.fiscalDayNo == fiscalDayNo) change(it) else it },
            closingDays = closingDays.map { if (it.fiscalDayNo == fiscalDayNo) change(it) else it },
        )

        fun matchesThumbprint(certificate: X509Certificate, thumbprint: String): Boolean {
            // SHA-1 is how ZIMRA names its certificate; it identifies, it does not protect.
            val digest = MessageDigest.getInstance("SHA-1").digest(certificate.encoded)
            val hex = digest.joinToString("") { "%02x".format(it) }
            val given = thumbprint.replace(":", "").replace(" ", "")
            return given.equals(hex, ignoreCase = true) || given == Base64.getEncoder().encodeToString(digest)
        }
    }
}
