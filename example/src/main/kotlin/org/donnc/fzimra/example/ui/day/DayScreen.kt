package org.donnc.fzimra.example.ui.day

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.donnc.fzimra.core.DayCloseReason
import org.donnc.fzimra.core.FiscalCounterType
import org.donnc.fzimra.core.FiscalDayState
import org.donnc.fzimra.core.FiscalDayStatus
import org.donnc.fzimra.core.SyncOutcome
import org.donnc.fzimra.example.fiscal.EngineState
import org.donnc.fzimra.example.fiscal.FiscalEngine
import org.donnc.fzimra.example.settings.Mode
import org.donnc.fzimra.example.ui.components.Banner
import org.donnc.fzimra.example.ui.components.BannerKind
import org.donnc.fzimra.example.ui.components.EmptyState
import org.donnc.fzimra.example.ui.components.InfoRow
import org.donnc.fzimra.example.ui.components.SectionCard
import org.donnc.fzimra.example.ui.components.StatusPill
import org.donnc.fzimra.example.ui.components.Tone
import org.donnc.fzimra.example.ui.components.ago
import org.donnc.fzimra.example.ui.components.colourTone
import org.donnc.fzimra.example.ui.components.dateTime
import org.donnc.fzimra.example.ui.components.money
import org.donnc.fzimra.example.ui.friendly

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DayScreen(engine: FiscalEngine, onSetup: () -> Unit) {
    val state by engine.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var confirmClose by remember { mutableStateOf(false) }

    fun act(block: suspend () -> String?) {
        scope.launch { message = try { block() } catch (e: Exception) { e.friendly() } }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Fiscal day") })
        if (!state.isReady) {
            EmptyState("📅", "No device yet", "Set up the device to open fiscal days.", "Go to setup", onSetup)
            return@Column
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            message?.let { Banner(it, kind = BannerKind.INFO, action = { TextButton(onClick = { message = null }) { Text("Dismiss") } }) }
            CurrentDay(state, onClose = { confirmClose = true }, onRetry = { act { if (engine.retryDayClose()) { engine.syncNow(); "The close was sent again." } else null } })
            state.status?.closingDays?.forEach { ClosingDay(it) }
            SyncCard(state) { act { "Sync: " + describe(engine.syncNow().outcome) } }
            state.status?.day?.let { Counters(it) }
            if (state.mode == Mode.SIMULATOR) SimulatorControls(state, engine)
        }
    }

    if (confirmClose) {
        AlertDialog(
            onDismissRequest = { confirmClose = false },
            title = { Text("Close fiscal day ${state.status?.day?.fiscalDayNo}?") },
            text = { Text("ZIMRA closes the day once every receipt has reached it, and the Z report is final. The next sale opens a new day.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClose = false
                    act { if (engine.closeDay()) "The day's close was sent to ZIMRA." else "There is no open day to close." }
                }) { Text("Close day") }
            },
            dismissButton = { TextButton(onClick = { confirmClose = false }) { Text("Not yet") } },
        )
    }
}

private fun describe(outcome: SyncOutcome) = when (outcome) {
    SyncOutcome.COMPLETE -> "everything is with ZIMRA."
    SyncOutcome.WAITING -> "waiting for ZIMRA to validate the day's close."
    SyncOutcome.OFFLINE -> "ZIMRA could not be reached; it will try again when online."
    SyncOutcome.BLOCKED -> "needs attention (see below)."
    SyncOutcome.BUSY -> "a sync is already running."
}

private fun dayStatus(day: FiscalDayState): Pair<String, Tone> = when (day.status) {
    FiscalDayStatus.OPEN_PENDING -> (if (day.receiptCounter == 0) "Opens with the first sale" else "Open on this device") to Tone.WAITING
    FiscalDayStatus.OPENED -> "Open with ZIMRA" to Tone.GOOD
    FiscalDayStatus.CLOSE_REQUESTED -> "Closing" to Tone.WAITING
    FiscalDayStatus.CLOSE_INITIATED -> "ZIMRA is validating the close" to Tone.WAITING
    FiscalDayStatus.CLOSE_FAILED -> "Close refused" to Tone.BAD
    FiscalDayStatus.CLOSED -> "Closed" to Tone.NEUTRAL
}

@Composable
private fun CurrentDay(state: EngineState, onClose: () -> Unit, onRetry: () -> Unit) {
    val status = state.status
    val day = status?.day
    if (day == null) {
        SectionCard("Fiscal day") { Text("The next sale opens a new fiscal day.") }
        return
    }
    val (text, tone) = dayStatus(day)
    SectionCard("Fiscal day ${day.fiscalDayNo}", action = { StatusPill(text, tone) }) {
        InfoRow("Opened", dateTime(day.openedAt))
        InfoRow("Receipts", day.receiptCounter.toString())
        InfoRow("Last global number", status.lastGlobalNo.toString())
        status.dayEndsAt?.let { InfoRow("Must close by", dateTime(it)) }
        if (status.isDayEndingSoon) Banner("The day is ending", "ZIMRA's maximum day length is near: close the day.", BannerKind.WARNING)
        day.closeReason?.let { InfoRow("Closing because", reason(it)) }
        day.closingErrorCode?.let { Banner("ZIMRA refused the close: $it", "Fix the cause, then send the close again.", BannerKind.ERROR) }
        if (day.blockingReceipts > 0) {
            Banner("${day.blockingReceipts} receipt(s) flagged Grey or Red", "ZIMRA does not let the device close this day; it must be closed in ZIMRA's portal.", BannerKind.ERROR)
        }
        when (day.status) {
            FiscalDayStatus.OPENED, FiscalDayStatus.OPEN_PENDING ->
                if (day.receiptCounter > 0) Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Close fiscal day") }
            FiscalDayStatus.CLOSE_FAILED -> Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text("Send the close again") }
            else -> {}
        }
    }
}

private fun reason(reason: DayCloseReason) = when (reason) {
    DayCloseReason.REQUESTED -> "requested here"
    DayCloseReason.MAX_DAY_LENGTH -> "maximum day length"
    DayCloseReason.GLOBAL_NUMBER_LIMIT -> "global number limit reached"
    DayCloseReason.VAT_REGISTRATION_CHANGED -> "VAT registration changed"
}

@Composable
private fun ClosingDay(day: FiscalDayState) {
    val (text, tone) = dayStatus(day)
    SectionCard("Fiscal day ${day.fiscalDayNo}", action = { StatusPill(text, tone) }) {
        InfoRow("Opened", dateTime(day.openedAt))
        InfoRow("Receipts", day.receiptCounter.toString())
        day.closeReason?.let { InfoRow("Closing because", reason(it)) }
        day.closingErrorCode?.let { InfoRow("ZIMRA's error", it) }
    }
}

@Composable
private fun SyncCard(state: EngineState, onSync: () -> Unit) {
    val status = state.status ?: return
    val now = java.time.LocalDateTime.now(java.time.ZoneId.of("Africa/Harare"))
    SectionCard("With ZIMRA") {
        InfoRow("Waiting to send", "${status.pendingReceipts} receipt${if (status.pendingReceipts == 1) "" else "s"}")
        status.oldestPendingDate?.let { InfoRow("Oldest waiting", "issued ${ago(it, now)}") }
        status.problems.forEach { Banner("Needs attention", it, BannerKind.ERROR) }
        status.lastSync?.let { report ->
            InfoRow("Last sync", describe(report.outcome))
            report.closedDays.forEach { closed ->
                Banner(
                    "Fiscal day ${closed.day.fiscalDayNo} closed by ZIMRA",
                    "${closed.reconciliationMode ?: "Auto"} close · ZIMRA's signature " + if (closed.zimraSignatureVerified) "verified" else "not verified",
                    BannerKind.INFO,
                )
            }
        }
        FilledTonalButton(onClick = onSync, enabled = !state.isSyncing, modifier = Modifier.fillMaxWidth()) {
            if (state.isSyncing) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
            }
            Text("Sync now")
        }
        Text(
            "Receipts are sent in the background as soon as the network allows. Sync now only makes it immediate.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Counters(day: FiscalDayState) {
    if (day.counters.isEmpty()) return
    SectionCard("Fiscal counters") {
        day.counters.sortedBy { it.type.ordinal }.forEach { c ->
            val label = when (c.type) {
                FiscalCounterType.SALE_BY_TAX -> "Sales"
                FiscalCounterType.SALE_TAX_BY_TAX -> "Sales tax"
                FiscalCounterType.CREDIT_NOTE_BY_TAX -> "Credit notes"
                FiscalCounterType.CREDIT_NOTE_TAX_BY_TAX -> "Credit note tax"
                FiscalCounterType.DEBIT_NOTE_BY_TAX -> "Debit notes"
                FiscalCounterType.DEBIT_NOTE_TAX_BY_TAX -> "Debit note tax"
                FiscalCounterType.BALANCE_BY_MONEY_TYPE -> "Takings"
            }
            val detail = c.moneyType?.specName ?: c.taxPercent?.let { "${it.stripTrailingZeros().toPlainString()}%" } ?: "exempt"
            InfoRow("$label · $detail", money(c.value, c.currency))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SimulatorControls(state: EngineState, engine: FiscalEngine) {
    val faults = engine.simulator.faults
    var offline by remember { mutableStateOf(faults.offline) }
    var armed by remember { mutableIntStateOf(0) }
    val simulated = state.simulated

    SectionCard("Simulator") {
        Text("Make the simulated ZIMRA misbehave, and watch fzimra cope.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Offline", style = MaterialTheme.typography.titleSmall)
                Text("Sales continue; receipts wait in the queue.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(offline, { offline = it; faults.offline = it })
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { faults.dropAfterSubmit++; armed++ }) { Text("Lose next answer") }
            OutlinedButton(onClick = { faults.serverErrors++; armed++ }) { Text("Server error") }
            OutlinedButton(onClick = { faults.flagNextReceipt = "RCPT025" to "Red"; armed++ }) { Text("Flag next receipt Red") }
            OutlinedButton(onClick = { faults.refuseNextClose = "CountersMismatch"; armed++ }) { Text("Refuse next close") }
            OutlinedButton(onClick = { engine.simulator.closeInPortal(state.device.deviceId) }) { Text("Close day in portal") }
        }
        if (armed > 0) {
            Text(
                "Armed: " + listOfNotNull(
                    faults.dropAfterSubmit.takeIf { it > 0 }?.let { "$it lost answer(s)" },
                    faults.serverErrors.takeIf { it > 0 }?.let { "$it server error(s)" },
                    faults.flagNextReceipt?.let { "flag ${it.second}" },
                    faults.refuseNextClose?.let { "refuse close" },
                ).ifEmpty { listOf("nothing pending") }.joinToString(", "),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        simulated?.let { d ->
            InfoRow("Simulator's day status", d.dayStatus.removePrefix("FiscalDay"))
            d.closingErrorCode?.let { InfoRow("Closing error", it) }
            d.days.lastOrNull()?.let { day ->
                InfoRow("Receipts it received in day ${day.fiscalDayNo}", day.receipts.size.toString())
                day.receipts.flatMap { r -> r.findings.map { f -> r.globalNo to f } }.takeLast(5).forEach { (no, f) ->
                    StatusPill("#$no ${f.code} ${f.colour}", colourTone(f.colour))
                }
            }
        }
    }
}
