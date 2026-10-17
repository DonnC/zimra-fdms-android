package org.donnc.fzimra.example.fiscal

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.donnc.fzimra.android.Fzimra
import org.donnc.fzimra.android.ZimraGateway
import org.donnc.fzimra.core.Buyer
import org.donnc.fzimra.core.DeviceIdentity
import org.donnc.fzimra.core.FiscalTerminal
import org.donnc.fzimra.core.Gateway
import org.donnc.fzimra.core.IssueResult
import org.donnc.fzimra.core.Payment
import org.donnc.fzimra.core.ReceiptRecord
import org.donnc.fzimra.core.ReceiptResult
import org.donnc.fzimra.core.SyncReport
import org.donnc.fzimra.core.TaxpayerInformation
import org.donnc.fzimra.core.TerminalStatus
import org.donnc.fzimra.core.toReceiptLines
import org.donnc.fzimra.example.settings.DeviceSettings
import org.donnc.fzimra.example.settings.Mode
import org.donnc.fzimra.example.settings.SettingsRepository
import org.donnc.fzimra.simulator.FdmsSimulator
import org.donnc.fzimra.simulator.SimulatedDevice
import org.donnc.fzimra.simulator.SimulatorGateway
import java.math.BigDecimal

/** What the screens show. */
data class EngineState(
    val mode: Mode = Mode.SIMULATOR,
    val device: DeviceSettings = DeviceSettings.SIMULATED,
    val status: TerminalStatus? = null,
    val receipts: List<ReceiptRecord> = emptyList(),
    val isSyncing: Boolean = false,
    val simulated: SimulatedDevice? = null,
) {
    val isReady: Boolean get() = status?.isRegistered == true
}

/** A sale as the till rings it up, in the shop's own terms. */
data class Sale(
    val lines: List<CartLine>,
    val currency: String,
    val payment: PaymentChoice,
    val cashTendered: BigDecimal?,
    val buyer: Buyer?,
)

enum class PaymentChoice(val label: String) { CASH("Cash"), CARD("Card"), MOBILE("Mobile money") }

/**
 * The example's whole use of fzimra: pick the gateway for the selected mode,
 * get its [FiscalTerminal], and turn the terminal's status into screen state.
 * Everything fiscal is the terminal's job.
 */
class FiscalEngine(
    private val context: Context,
    private val settings: SettingsRepository,
    val traffic: TrafficLog,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow(EngineState())
    val state: StateFlow<EngineState> = mutableState

    @Volatile var terminal: FiscalTerminal? = null
        private set
    private val listening = mutableSetOf<FiscalTerminal>()

    init {
        scope.launch {
            settings.settings.collect { s -> open(s.mode, s.device()) }
        }
        // Simulator controls (a portal close, say) change what the screens show too.
        simulator.addChangeListener(::refreshSoon)
    }

    /** The built-in simulator: its faults, and what it has received. */
    val simulator: FdmsSimulator get() = SimulatorGateway.shared(context).simulator

    // -- the five things a till does ----------------------------------------------------

    suspend fun checkTaxpayer(activationKey: String): TaxpayerInformation = io { require().checkTaxpayer(activationKey) }

    suspend fun register(activationKey: String) = io { require().register(activationKey); refresh() }

    fun preview(sale: Sale): ReceiptResult? = terminal?.takeIf { it.isRegistered }?.let { t ->
        t.preview(sale.lines.toReceiptLines(Catalogue.mapper(t.taxes)), listOf(payment(sale)), sale.currency, sale.buyer)
    }

    suspend fun sell(sale: Sale): IssueResult = io {
        val t = require()
        t.sell(sale.lines.toReceiptLines(Catalogue.mapper(t.taxes)), listOf(payment(sale)), sale.currency, sale.buyer)
    }

    suspend fun refund(record: ReceiptRecord, reason: String): IssueResult = io { require().refund(record.receipt, reason) }

    suspend fun closeDay(): Boolean = io { require().closeDay().also { if (it) require().sync() } }

    suspend fun retryDayClose(): Boolean = io { require().retryDayClose() }

    suspend fun syncNow(): SyncReport = io {
        mutableState.update { it.copy(isSyncing = true) }
        try {
            require().sync()
        } finally {
            mutableState.update { it.copy(isSyncing = false) }
        }
    }

    // -- settings -------------------------------------------------------------------------

    suspend fun setMode(mode: Mode) = settings.setMode(mode)

    /** Saves [device] and switches to it at once, so the next request uses it. */
    suspend fun saveDevice(mode: Mode, device: DeviceSettings) {
        settings.setDevice(mode, device)
        if (state.value.mode == mode) io { open(mode, device) }
    }

    /** Takes the device off this phone. Unsent receipts are lost. */
    suspend fun unregister() = io {
        require().unregister(discardUnsentReceipts = true)
        if (state.value.mode == Mode.SIMULATOR) simulator.reset()
        refresh()
    }

    /** Re-reads the terminal into [state], off the main thread. */
    fun refreshSoon() {
        scope.launch { refresh() }
    }

    // -- plumbing ---------------------------------------------------------------------------

    private fun open(mode: Mode, device: DeviceSettings) {
        val terminal = if (device.isComplete) {
            Fzimra.terminal(context, DeviceIdentity(device.deviceId, device.serialNo, device.modelName, device.modelVersion), gateway(mode), observer = traffic)
        } else {
            null
        }
        this.terminal = terminal
        if (terminal != null && listening.add(terminal)) {
            terminal.addListener { if (this.terminal === terminal) refresh() }
        }
        mutableState.update { EngineState(mode = mode, device = device) }
        refresh()
    }

    private fun refresh() {
        val t = terminal
        val s = state.value
        mutableState.update {
            it.copy(
                status = t?.status(),
                receipts = runCatching { t?.receipts().orEmpty() }.getOrDefault(emptyList()),
                simulated = if (s.mode == Mode.SIMULATOR) simulator.device(s.device.deviceId) else null,
            )
        }
    }

    private fun gateway(mode: Mode): Gateway = when (mode) {
        Mode.SIMULATOR -> SimulatorGateway.shared(context)
        Mode.ZIMRA_TEST -> ZimraGateway.TEST
        Mode.ZIMRA_PRODUCTION -> ZimraGateway.PRODUCTION
    }

    private fun payment(sale: Sale): Payment = when (sale.payment) {
        PaymentChoice.CASH -> Payment.cash(sale.cashTendered)
        PaymentChoice.CARD -> Payment.card()
        PaymentChoice.MOBILE -> Payment.mobileWallet()
    }

    private fun require(): FiscalTerminal = terminal ?: error("Enter the device details first")

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }
}
