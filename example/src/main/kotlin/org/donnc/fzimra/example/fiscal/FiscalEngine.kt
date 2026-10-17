package org.donnc.fzimra.example.fiscal

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.donnc.fzimra.android.Fzimra
import org.donnc.fzimra.android.FzimraBackground
import org.donnc.fzimra.android.KeySecurityLevel
import org.donnc.fzimra.android.KeystoreSigner
import org.donnc.fzimra.android.KeystoreUnavailableException
import org.donnc.fzimra.core.Buyer
import org.donnc.fzimra.core.DeviceCsr
import org.donnc.fzimra.core.DeviceIdentity
import org.donnc.fzimra.core.DeviceState
import org.donnc.fzimra.core.FdmsClient
import org.donnc.fzimra.core.FiscalDevice
import org.donnc.fzimra.core.FiscalIntegrityException
import org.donnc.fzimra.core.FiscalSync
import org.donnc.fzimra.core.HttpsTransport
import org.donnc.fzimra.core.IssueResult
import org.donnc.fzimra.core.OriginalReceipt
import org.donnc.fzimra.core.Payment
import org.donnc.fzimra.core.ReceiptContext
import org.donnc.fzimra.core.ReceiptLine
import org.donnc.fzimra.core.ReceiptRecord
import org.donnc.fzimra.core.ReceiptRequest
import org.donnc.fzimra.core.ReceiptType
import org.donnc.fzimra.core.SyncReport
import org.donnc.fzimra.core.TaxpayerInformation
import org.donnc.fzimra.core.ZimbabweClock
import org.donnc.fzimra.core.ZimraConfiguration
import org.donnc.fzimra.example.settings.DeviceSettings
import org.donnc.fzimra.example.settings.Mode
import org.donnc.fzimra.example.settings.SettingsRepository
import org.donnc.fzimra.simulator.FdmsSimulator
import org.donnc.fzimra.simulator.SimulatedDevice
import org.donnc.fzimra.simulator.SimulatorStore
import java.io.File
import java.time.Instant
import java.time.LocalDateTime

/** Everything the screens show, in one place. */
data class EngineState(
    val mode: Mode = Mode.SIMULATOR,
    val device: DeviceSettings = DeviceSettings.SIMULATED,
    val isRegistered: Boolean = false,
    val configuration: ZimraConfiguration? = null,
    val keySecurity: KeySecurityLevel? = null,
    val certificateExpiry: Instant? = null,
    val state: DeviceState? = null,
    val pendingReceipts: Int = 0,
    val oldestUnsent: LocalDateTime? = null,
    val receipts: List<ReceiptRecord> = emptyList(),
    val lastSync: SyncReport? = null,
    val lastSyncAt: LocalDateTime? = null,
    val isSyncing: Boolean = false,
    val integrityProblems: List<String> = emptyList(),
    val simulated: SimulatedDevice? = null,
) {
    val isReady: Boolean get() = isRegistered && configuration != null
}

/** A sale as the till rings it up. */
data class Sale(
    val lines: List<CartLine>,
    val currency: String,
    val payment: PaymentChoice,
    val cashTendered: java.math.BigDecimal?,
    val buyer: Buyer?,
)

enum class PaymentChoice(val label: String) { CASH("Cash"), CARD("Card"), MOBILE("Mobile money") }

/**
 * The example's whole integration with fzimra, in one class: what a POS
 * would write once. It builds, for the selected mode,
 *
 * - the device key ([KeystoreSigner]), created at registration;
 * - a client for ZIMRA, or for the built-in [FdmsSimulator];
 * - the [FiscalDevice] that issues receipts, with fzimra's own storage and
 *   background sync;
 *
 * and turns their state into [state] for the screens. Every call to fzimra is
 * made off the main thread.
 */
class FiscalEngine(
    private val context: Context,
    private val settings: SettingsRepository,
    val traffic: TrafficLog,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val configurations = ConfigurationStore(context)
    private val mutableState = MutableStateFlow(EngineState())
    val state: StateFlow<EngineState> = mutableState

    /** Receipt dates follow ZIMRA's clock once it has answered. */
    val clock = ZimbabweClock()

    /** The built-in gateway, kept with the app's own storage. */
    val simulator: FdmsSimulator by lazy {
        FdmsSimulator(store = SimulatorStore.InFile(File(context.noBackupFilesDir, "fzimra-simulator.json")))
            .also { it.addChangeListener(::refreshSoon) }
    }

    private class Session(
        val mode: Mode,
        val device: DeviceSettings,
        val signer: KeystoreSigner?,
        val client: FdmsClient,
        val fiscal: FiscalDevice?,
    )

    @Volatile private var session: Session? = null

    init {
        scope.launch {
            settings.settings.collect { s ->
                val current = session
                if (current == null || current.mode != s.mode || current.device != s.device()) {
                    session = open(s.mode, s.device())
                    mutableState.update { EngineState(mode = s.mode, device = s.device()) }
                    refresh()
                }
            }
        }
    }

    // -- setting up ---------------------------------------------------------------

    suspend fun setMode(mode: Mode) = settings.setMode(mode)

    /** Saves [device] and switches to it at once, so the next request uses it. */
    suspend fun saveDevice(mode: Mode, device: DeviceSettings) {
        settings.setDevice(mode, device)
        io {
            if (state.value.mode == mode) {
                session = open(mode, device)
                mutableState.update { EngineState(mode = mode, device = device) }
                refresh()
            }
        }
    }

    /** Who ZIMRA says the device belongs to; ask before [register]. */
    suspend fun verifyTaxpayer(activationKey: String): TaxpayerInformation = io {
        current().client.verifyTaxpayerInformation(activationKey.trim())
    }

    /**
     * Registers the device: creates its key in the Keystore, sends ZIMRA a
     * certificate request, installs the certificate, fetches the configuration
     * and continues any numbering ZIMRA already has for the device.
     */
    suspend fun register(activationKey: String) = io {
        val s = current()
        val signer = createSigner(alias(s.mode, s.device.deviceId))
        val draft = Session(s.mode, s.device, signer, client(s.mode, s.device, signer), null)
        val issued = draft.client.registerDevice(activationKey.trim(), DeviceCsr.create(signer, s.device.serialNo, s.device.deviceId))
        signer.installCertificate(issued.certificatePem, identity(s.device))
        val configuration = draft.client.getConfig()
        configurations.save(configKey(s.mode, s.device), configuration)
        val fiscal = fiscalDevice(s.device, configuration, signer)
        val status = draft.client.getStatus()
        if (fiscal.state() == null && status.lastFiscalDayNo != null) fiscal.startFrom(status)
        session = Session(s.mode, s.device, signer, draft.client, fiscal)
        refresh()
    }

    /** Fetches ZIMRA's configuration again and applies it. */
    suspend fun refreshConfiguration() = io {
        val s = current()
        val fiscal = s.fiscal ?: return@io
        val configuration = s.client.getConfig()
        configurations.save(configKey(s.mode, s.device), configuration)
        fiscal.updateConfiguration(configuration.toDeviceConfiguration(s.device.deviceId))
        refresh()
    }

    /** A new device certificate, before the current one expires. */
    suspend fun renewCertificate() = io {
        val s = current()
        val signer = s.signer ?: return@io
        val renewed = s.client.issueCertificate(DeviceCsr.create(signer, s.device.serialNo, s.device.deviceId))
        signer.installCertificate(renewed.certificatePem, identity(s.device))
        refresh()
    }

    /**
     * Removes this device from the app: its fiscal state, receipt records, keys
     * and configuration. Unsent receipts are lost.
     */
    suspend fun resetDevice() = io {
        val s = current()
        Fzimra.deleteDeviceData(context, s.device.deviceId, discardUnsentReceipts = true)
        KeystoreSigner.delete(alias(s.mode, s.device.deviceId))
        configurations.delete(configKey(s.mode, s.device))
        if (s.mode == Mode.SIMULATOR) simulator.reset()
        session = open(s.mode, s.device)
        mutableState.update { EngineState(mode = s.mode, device = s.device) }
        refresh()
    }

    // -- selling --------------------------------------------------------------------

    /** The receipt [sale] would make, priced by fzimra, without issuing it. */
    fun preview(sale: Sale) = state.value.configuration?.let { configuration ->
        org.donnc.fzimra.core.ReceiptBuilder.prepare(
            request(sale, configuration, invoiceNo = "PREVIEW"),
            ReceiptContext(configuration.taxes, configuration.isVatPayer),
            clock.now().toLocalDate(),
        )
    }

    /** Issues [sale]: numbered, signed and saved before this returns; sent to ZIMRA in the background. */
    suspend fun sell(sale: Sale): IssueResult = io {
        val s = current()
        val fiscal = s.fiscal ?: error("the device is not registered")
        val configuration = state.value.configuration ?: error("no configuration")
        fiscal.issue(request(sale, configuration, nextInvoiceNo())).also { refresh() }
    }

    /** A credit note returning everything on [record]'s receipt. */
    suspend fun refund(record: ReceiptRecord, reason: String): IssueResult = io {
        val s = current()
        val fiscal = s.fiscal ?: error("the device is not registered")
        val original = record.receipt
        val lines = original.receipt.lines.map { l ->
            ReceiptLine.saleWithTotal(l.name, l.quantity, l.total.abs(), l.taxId, l.hsCode)
        }
        val request = ReceiptRequest(
            type = ReceiptType.CREDIT_NOTE,
            currency = original.receipt.currency,
            invoiceNo = "CN-" + nextInvoiceNo(),
            lines = lines,
            payments = listOf(Payment.remainder(original.receipt.payments.first().moneyType)),
            buyer = original.receipt.buyer,
            notes = reason,
            original = record.zimraReceiptId?.let { OriginalReceipt.ById(it) }
                ?: OriginalReceipt.ByDevice(original.deviceId, original.globalNo, original.fiscalDayNo),
            pricesIncludeTax = original.receipt.pricesIncludeTax,
        )
        fiscal.issue(request).also { refresh() }
    }

    // -- fiscal days and sync -----------------------------------------------------------

    suspend fun requestDayClose(): Boolean = io { (current().fiscal?.requestDayClose() ?: false).also { refresh() } }

    suspend fun retryDayClose(fiscalDayNo: Int): Boolean = io { (current().fiscal?.retryDayClose(fiscalDayNo) ?: false).also { refresh() } }

    /** Syncs now, in the foreground. */
    suspend fun syncNow(): SyncReport? = io {
        val sync = syncFor(current().device.deviceId) ?: return@io null
        mutableState.update { it.copy(isSyncing = true) }
        try {
            sync.run().also { record(it) }
        } finally {
            mutableState.update { it.copy(isSyncing = false) }
        }
    }

    /**
     * For background runs ([FzimraBackground]). The platform may start the app
     * just to sync, before the engine has opened its session: open it here.
     */
    fun syncFor(deviceId: Int): FiscalSync? {
        val s = session ?: synchronized(this) {
            session ?: kotlinx.coroutines.runBlocking { settings.current() }.let { open(it.mode, it.device()) }.also { session = it }
        }
        val fiscal = s.fiscal ?: return null
        if (s.device.deviceId != deviceId) return null
        return FiscalSync(fiscal, s.client)
    }

    /** A background run finished. */
    fun onBackgroundReport(deviceId: Int, report: SyncReport) {
        if (session?.device?.deviceId == deviceId) record(report)
    }

    /** Pings ZIMRA at the interval it asks for, while the app is in use. */
    suspend fun pingWhileOpen() {
        while (true) {
            val minutes = runCatching { io { current().takeIf { it.fiscal != null }?.client?.ping() } }.getOrNull() ?: 5
            delay(minutes.coerceAtLeast(1) * 60_000L)
        }
    }

    // -- plumbing -------------------------------------------------------------------

    private fun record(report: SyncReport) {
        mutableState.update { it.copy(lastSync = report, lastSyncAt = clock.now()) }
        refresh()
    }

    /** [refresh], off the main thread. */
    fun refreshSoon() {
        scope.launch { refresh() }
    }

    /** Reads the device again into [state]. */
    fun refresh() {
        val s = session ?: return
        val configuration = configurations.load(configKey(s.mode, s.device))
        val fiscal = s.fiscal
        var problems = emptyList<String>()
        val deviceState = fiscal?.let {
            try {
                it.state()
            } catch (e: FiscalIntegrityException) {
                problems = e.problems
                null
            }
        }
        mutableState.update {
            it.copy(
                mode = s.mode,
                device = s.device,
                isRegistered = s.signer?.hasZimraCertificate == true,
                configuration = configuration,
                keySecurity = s.signer?.let { signer -> runCatching { signer.securityLevel }.getOrNull() },
                certificateExpiry = s.signer?.zimraCertificateExpiry,
                state = deviceState,
                pendingReceipts = runCatching { fiscal?.pendingReceipts()?.size ?: 0 }.getOrDefault(0),
                oldestUnsent = runCatching { fiscal?.oldestUnsentReceiptDate() }.getOrNull(),
                receipts = runCatching { fiscal?.records?.list(s.device.deviceId).orEmpty().reversed() }.getOrDefault(emptyList()),
                integrityProblems = problems,
                simulated = if (s.mode == Mode.SIMULATOR) simulator.device(s.device.deviceId) else null,
            )
        }
    }

    private fun current(): Session = session ?: error("the engine is starting")

    /** The session for [mode] and [device], using what is already registered on this phone. */
    private fun open(mode: Mode, device: DeviceSettings): Session {
        val signer = if (device.isComplete) KeystoreSigner.load(alias(mode, device.deviceId)) else null
        val client = client(mode, device, signer)
        val configuration = configurations.load(configKey(mode, device))
        val fiscal = if (signer?.hasZimraCertificate == true && configuration != null) fiscalDevice(device, configuration, signer) else null
        return Session(mode, device, signer, client, fiscal)
    }

    private fun fiscalDevice(device: DeviceSettings, configuration: ZimraConfiguration, signer: KeystoreSigner): FiscalDevice =
        Fzimra.device(context, configuration.toDeviceConfiguration(device.deviceId), signer, clock = clock).also {
            it.addWorkListener(::refreshSoon)
        }

    private fun client(mode: Mode, device: DeviceSettings, signer: KeystoreSigner?): FdmsClient {
        val identity = identity(device)
        val transport = when (mode) {
            // The simulator stands in for mutual TLS: it is shown the installed certificate.
            Mode.SIMULATOR -> simulator.transport { signer?.takeIf { it.hasZimraCertificate }?.certificateChain?.firstOrNull() }
            else -> HttpsTransport(deviceKeys = signer?.keyManager())
        }
        val baseUrl = mode.environment?.baseUrl ?: FdmsSimulator.BASE_URL
        return FdmsClient(identity, traffic.around(transport), baseUrl, clock)
    }

    private fun createSigner(alias: String): KeystoreSigner = try {
        KeystoreSigner.getOrCreate(alias)
    } catch (e: KeystoreUnavailableException) {
        // Emulators hold keys in software only; a real till keeps the hardware requirement.
        KeystoreSigner.getOrCreate(alias, requireHardware = false)
    }

    private fun request(sale: Sale, configuration: ZimraConfiguration, invoiceNo: String): ReceiptRequest {
        val lines = sale.lines.map(Catalogue.mapper(Catalogue.taxIds(configuration.taxes))::toReceiptLine)
        val payment = when (sale.payment) {
            PaymentChoice.CASH -> Payment.cash(sale.cashTendered)
            PaymentChoice.CARD -> Payment.card()
            PaymentChoice.MOBILE -> Payment.mobileWallet()
        }
        return ReceiptRequest(
            type = ReceiptType.FISCAL_INVOICE,
            currency = sale.currency,
            invoiceNo = invoiceNo,
            lines = lines,
            payments = listOf(payment),
            buyer = sale.buyer,
        )
    }

    private fun nextInvoiceNo(): String {
        val prefs = context.getSharedPreferences("example-invoices", Context.MODE_PRIVATE)
        val next = prefs.getInt("next", 1)
        prefs.edit { putInt("next", next + 1) }
        return "INV-" + next.toString().padStart(6, '0')
    }

    private fun identity(device: DeviceSettings) = DeviceIdentity(device.deviceId, device.serialNo, device.modelName, device.modelVersion)

    private fun alias(mode: Mode, deviceId: Int) = "fzimra-example-${mode.name.lowercase()}-$deviceId"

    private fun configKey(mode: Mode, device: DeviceSettings) = "${mode.name.lowercase()}-${device.deviceId}"

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }
}
