package org.donnc.fzimra.core

import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Where the terminal stands, for showing on screen.
 *
 * @property dayEndsAt when the current fiscal day must close by ZIMRA's
 *   maximum day length.
 * @property isDayEndingSoon ZIMRA requires warning the user from this point.
 * @property problems what needs a person: refused receipts or closes, a day
 *   that must be closed in ZIMRA's portal, data failing its checks.
 */
public data class TerminalStatus(
    val gateway: String,
    val isRegistered: Boolean,
    val configuration: ZimraConfiguration?,
    val day: FiscalDayState?,
    /** Earlier days still being closed with ZIMRA. */
    val closingDays: List<FiscalDayState>,
    val lastGlobalNo: Int,
    val pendingReceipts: Int,
    val oldestPendingDate: LocalDateTime?,
    val certificateExpiry: Instant?,
    val dayEndsAt: LocalDateTime?,
    val isDayEndingSoon: Boolean,
    val lastSync: SyncReport?,
    val problems: List<String>,
) {
    /** Everything issued has reached ZIMRA and nothing needs attention. */
    val isUpToDate: Boolean get() = pendingReceipts == 0 && problems.isEmpty()
}

/**
 * A fiscal terminal: everything a point of sale needs to fiscalise, in one
 * object. On Android, get one with `Fzimra.terminal(...)`.
 *
 * ```kotlin
 * terminal.register(activationKey)                       // once
 * terminal.sell(listOf(ReceiptLine.sale("Bread", 2, 1.15, terminal.taxes.standard, "19059000")))
 * terminal.refund(receipt, reason = "Damaged")
 * terminal.closeDay()
 * ```
 *
 * The terminal looks after the rest itself: the device key and certificate,
 * ZIMRA's configuration (kept for offline use and refreshed before each fiscal
 * day), continuing numbering the device already has at ZIMRA, invoice numbers,
 * sending receipts in order, opening and closing days with ZIMRA, and, through
 * [maintain], reporting online and renewing the certificate before it expires.
 *
 * Calls block on storage or the network: make them off the main thread.
 * [sell] and [refund] never wait for the network.
 *
 * The lower-level classes stay available: [device] issues receipts,
 * [client] talks to ZIMRA.
 */
public class FiscalTerminal(
    public val identity: DeviceIdentity,
    public val gateway: Gateway,
    private val keys: DeviceKeyStore,
    private val stateStore: FiscalStateStore,
    private val recordStore: ReceiptRecordStore,
    private val terminalStore: TerminalStore,
    private val sealer: (DeviceKey) -> Sealer,
    public val clock: ZimbabweClock = ZimbabweClock(),
    private val numbering: GlobalNumbering = GlobalNumbering(),
    private val observer: FdmsObserver? = null,
    /** How long before the certificate expires it is renewed. */
    private val renewBefore: Duration = Duration.ofDays(30),
) {
    private val lock = Any()
    private val listeners = CopyOnWriteArrayList<(TerminalStatus) -> Unit>()
    private val workListeners = CopyOnWriteArrayList<() -> Unit>()

    @Volatile private var key: DeviceKey? = keys.load()

    @Volatile private var data: TerminalData = terminalStore.load()?.let { runCatching { TerminalCodec.decode(it) }.getOrNull() } ?: TerminalData()

    @Volatile private var fiscal: FiscalDevice? = null

    @Volatile private var lastSync: SyncReport? = null

    @Volatile private var nextPing: Instant = Instant.MIN

    /** Talks to the gateway as this device. */
    public val client: FdmsClient = FdmsClient(identity, observed(TransportFor()), gateway.baseUrl, clock)

    init {
        openDevice()
    }

    /** Whether the device is registered and ready to issue receipts. */
    public val isRegistered: Boolean get() = key?.certificate != null && data.configuration != null

    /** The receipt-issuing device, once registered. */
    public val device: FiscalDevice get() = fiscal ?: throw IllegalStateException("register the terminal first")

    /** The taxpayer's taxes, from ZIMRA's configuration. */
    public val taxes: Taxes get() = Taxes(data.configuration?.taxes ?: throw IllegalStateException("register the terminal first"))

    // -- registration ---------------------------------------------------------------

    /** Who ZIMRA says the device belongs to. Show it before [register] if you like. */
    public fun checkTaxpayer(activationKey: String): TaxpayerInformation = client.verifyTaxpayerInformation(activationKey.trim())

    /**
     * Registers the device with ZIMRA: creates its key, gets its certificate,
     * fetches the configuration, and continues any numbering ZIMRA already has
     * for the device. Does nothing if already registered.
     */
    public fun register(activationKey: String): TerminalStatus = synchronized(lock) {
        if (isRegistered) return status()
        val key = this.key ?: keys.create().also { this.key = it }
        if (key.certificate == null) {
            val issued = client.registerDevice(activationKey.trim(), DeviceCsr.create(key, identity.serialNo, identity.deviceId))
            key.installCertificate(issued.certificatePem, identity)
        }
        saveConfiguration(client.getConfig())
        openDevice()
        val status = client.getStatus()
        if (device.state() == null && status.lastFiscalDayNo != null) device.startFrom(status)
        notifyListeners()
        status()
    }

    /**
     * Takes the device out of service on this phone: deletes its key, fiscal
     * state, records and configuration.
     *
     * @throws IllegalStateException if receipts have not reached ZIMRA yet,
     *   unless [discardUnsentReceipts].
     */
    public fun unregister(discardUnsentReceipts: Boolean = false): Unit = synchronized(lock) {
        val unsent = runCatching { fiscal?.pendingReceipts()?.size ?: 0 }.getOrDefault(0)
        check(unsent == 0 || discardUnsentReceipts) { "$unsent receipt(s) have not reached ZIMRA yet; sync them first" }
        keys.delete()
        terminalStore.delete()
        onUnregister?.invoke()
        key = null
        data = TerminalData()
        fiscal = null
        lastSync = null
        notifyListeners()
    }

    /** Set by the platform to remove its own stores on [unregister]. */
    @Volatile internal var onUnregister: (() -> Unit)? = null

    // -- selling --------------------------------------------------------------------

    /**
     * Sells [lines]: the receipt is numbered, signed and saved before this
     * returns, and sent to ZIMRA in the background.
     *
     * @param payments how it was paid; cash for the whole amount by default.
     *   Cash handed over beyond the total comes back as change.
     * @param invoiceNo your invoice number, or `null` for the terminal's own.
     */
    public fun sell(
        lines: List<ReceiptLine>,
        payments: List<Payment> = listOf(Payment.cash()),
        currency: String = "USD",
        buyer: Buyer? = null,
        invoiceNo: String? = null,
        pricesIncludeTax: Boolean = true,
    ): IssueResult = issue(
        ReceiptRequest(
            type = ReceiptType.FISCAL_INVOICE,
            currency = currency,
            invoiceNo = invoiceNo ?: nextNumber(credit = false),
            lines = lines,
            payments = payments,
            buyer = buyer,
            pricesIncludeTax = pricesIncludeTax,
        ),
    )

    /**
     * Refunds [receipt] with a credit note: everything on it, or only [lines].
     * The money goes back the way it was paid, unless [payments] says otherwise.
     */
    public fun refund(
        receipt: IssuedReceipt,
        reason: String,
        lines: List<ReceiptLine>? = null,
        payments: List<Payment>? = null,
        invoiceNo: String? = null,
    ): IssueResult {
        val original = receipt.receipt
        val record = runCatching { device.records.find(receipt.deviceId, receipt.fiscalDayNo, receipt.globalNo) }.getOrNull()
        return issue(
            ReceiptRequest(
                type = ReceiptType.CREDIT_NOTE,
                currency = original.currency,
                invoiceNo = invoiceNo ?: nextNumber(credit = true),
                lines = lines ?: original.lines.map { ReceiptLine.saleWithTotal(it.name, it.quantity, it.total.abs(), it.taxId, it.hsCode) },
                payments = payments ?: listOf(Payment.remainder(original.payments.first().moneyType)),
                buyer = original.buyer,
                notes = reason,
                original = record?.zimraReceiptId?.let { OriginalReceipt.ById(it) }
                    ?: OriginalReceipt.ByDevice(receipt.deviceId, receipt.globalNo, receipt.fiscalDayNo),
                pricesIncludeTax = original.pricesIncludeTax,
            ),
        )
    }

    /** Issues any receipt, credit or debit note you build yourself. */
    public fun issue(request: ReceiptRequest): IssueResult = device.issue(request)

    /** What [sell] would make of [lines], priced and checked, without issuing anything. */
    public fun preview(
        lines: List<ReceiptLine>,
        payments: List<Payment> = listOf(Payment.cash()),
        currency: String = "USD",
        buyer: Buyer? = null,
        pricesIncludeTax: Boolean = true,
    ): ReceiptResult = ReceiptBuilder.prepare(
        ReceiptRequest(ReceiptType.FISCAL_INVOICE, currency, "PREVIEW", lines, payments, buyer = buyer, pricesIncludeTax = pricesIncludeTax),
        device.configuration.receiptContext,
        clock.now().toLocalDate(),
    )

    /** Receipts issued on this device, newest first, with where they stand with ZIMRA. */
    public fun receipts(): List<ReceiptRecord> = fiscal?.records?.list(identity.deviceId)?.asReversed().orEmpty()

    // -- fiscal days and sync -------------------------------------------------------------

    /** Closes the current fiscal day. The close reaches ZIMRA with the next sync. */
    public fun closeDay(): Boolean = (fiscal?.requestDayClose() ?: false).also { if (it) notifyListeners() }

    /** Sends the close again after ZIMRA refused it and the cause is fixed. */
    public fun retryDayClose(): Boolean {
        val failed = fiscal?.state()?.let { s -> (s.closingDays + listOfNotNull(s.currentDay)).firstOrNull { it.status == FiscalDayStatus.CLOSE_FAILED } }
            ?: return false
        return fiscal!!.retryDayClose(failed.fiscalDayNo).also { if (it) notifyListeners() }
    }

    /** Brings ZIMRA up to date now. Background sync does this by itself; call it to make it immediate. */
    public fun sync(): SyncReport {
        val device = fiscal ?: return SyncReport(SyncOutcome.COMPLETE)
        val report = FiscalSync(device, client).run()
        lastSync = report
        report.configuration?.let(::saveConfiguration)
        notifyListeners()
        return report
    }

    /**
     * Routine upkeep, safe to call often (the Android terminal calls it every
     * few minutes while the app runs): reports the device online at the
     * interval ZIMRA asks for, and renews the certificate before it expires.
     */
    public fun maintain() {
        val key = key ?: return
        if (!isRegistered) return
        val now = Instant.now()
        if (now.isAfter(nextPing)) {
            val minutes = runCatching { client.ping() }.getOrNull()
            nextPing = now.plus(Duration.ofMinutes((minutes ?: 5).coerceAtLeast(1).toLong()))
        }
        val expiry = key.certificate?.notAfter?.toInstant() ?: return
        if (Duration.between(now, expiry) < renewBefore) {
            runCatching {
                val renewed = client.issueCertificate(DeviceCsr.create(key, identity.serialNo, identity.deviceId))
                key.installCertificate(renewed.certificatePem, identity)
                notifyListeners()
            }
        }
    }

    // -- status -----------------------------------------------------------------------

    /** Where the terminal stands now. */
    public fun status(): TerminalStatus {
        val configuration = data.configuration
        val device = fiscal
        var problems = lastSync?.problems.orEmpty()
        val state = try {
            device?.state()
        } catch (e: FiscalIntegrityException) {
            problems = problems + e.problems
            null
        }
        val day = state?.currentDay?.takeIf { it.status != FiscalDayStatus.CLOSED }
        val endsAt = day?.let { d -> configuration?.let { d.openedAt.plusHours(it.dayMaxHours.toLong()) } }
        val warnFrom = endsAt?.minusHours(configuration?.dayEndNotificationHours?.toLong() ?: 0)
        return TerminalStatus(
            gateway = gateway.name,
            isRegistered = isRegistered,
            configuration = configuration,
            day = day,
            closingDays = state?.closingDays.orEmpty(),
            lastGlobalNo = state?.lastGlobalNo ?: 0,
            pendingReceipts = runCatching { device?.pendingReceipts()?.size ?: 0 }.getOrDefault(0),
            oldestPendingDate = runCatching { device?.oldestUnsentReceiptDate() }.getOrNull(),
            certificateExpiry = key?.certificate?.notAfter?.toInstant(),
            dayEndsAt = endsAt,
            isDayEndingSoon = warnFrom != null && !clock.now().isBefore(warnFrom),
            lastSync = lastSync,
            problems = problems,
        )
    }

    /**
     * Calls [listener] with the new [status] after every change: a sale, a
     * sync, a close, a registration. Called on the thread that made the change.
     */
    public fun addListener(listener: (TerminalStatus) -> Unit) {
        listeners += listener
    }

    public fun removeListener(listener: (TerminalStatus) -> Unit) {
        listeners -= listener
    }

    /** Called whenever there is new work for ZIMRA; the Android terminal starts a background sync. */
    public fun addWorkListener(listener: () -> Unit) {
        workListeners += listener
    }

    /** Records a sync made elsewhere (for example a background run) in [status]. */
    public fun recordSync(report: SyncReport) {
        lastSync = report
        report.configuration?.let(::saveConfiguration)
        notifyListeners()
    }

    // -- plumbing -----------------------------------------------------------------------

    private fun notifyListeners() {
        if (listeners.isEmpty()) return
        val status = status()
        listeners.forEach { runCatching { it(status) } }
    }

    private fun openDevice() {
        val key = key ?: return
        val configuration = data.configuration ?: return
        if (key.certificate == null) return
        val existing = fiscal
        if (existing != null) {
            existing.updateConfiguration(configuration.toDeviceConfiguration(identity.deviceId))
            return
        }
        fiscal = FiscalDevice(
            configuration.toDeviceConfiguration(identity.deviceId), key, stateStore, recordStore, sealer(key), clock, numbering = numbering,
        ).also { device ->
            device.addWorkListener {
                workListeners.forEach { runCatching { it() } }
                notifyListeners()
            }
        }
    }

    private fun saveConfiguration(configuration: ZimraConfiguration) = synchronized(lock) {
        data = data.copy(configuration = configuration)
        terminalStore.save(TerminalCodec.encode(data))
        fiscal?.updateConfiguration(configuration.toDeviceConfiguration(identity.deviceId))
    }

    private fun nextNumber(credit: Boolean): String = synchronized(lock) {
        val number = if (credit) data.nextCreditNoteNo else data.nextInvoiceNo
        data = if (credit) data.copy(nextCreditNoteNo = number + 1) else data.copy(nextInvoiceNo = number + 1)
        terminalStore.save(TerminalCodec.encode(data))
        // ZIMRA requires invoice numbers unique per taxpayer: the device ID keeps tills apart.
        (if (credit) "CN-" else "INV-") + identity.deviceId + "-" + number.toString().padStart(6, '0')
    }

    /** The gateway's transport for the current key, looked up per request so a new certificate is used at once. */
    private inner class TransportFor : FdmsTransport {
        override fun execute(request: FdmsRequest): FdmsResponse = gateway.transport(key).execute(request)
    }

    private fun observed(transport: FdmsTransport): FdmsTransport {
        val observer = observer ?: return transport
        return FdmsTransport { request ->
            val started = System.nanoTime()
            try {
                transport.execute(request).also { observer.onExchange(request, it, null, (System.nanoTime() - started) / 1_000_000) }
            } catch (e: FdmsNetworkException) {
                observer.onExchange(request, null, e, (System.nanoTime() - started) / 1_000_000)
                throw e
            }
        }
    }
}
