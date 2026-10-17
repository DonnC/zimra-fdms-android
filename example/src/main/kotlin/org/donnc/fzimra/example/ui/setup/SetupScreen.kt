package org.donnc.fzimra.example.ui.setup

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.donnc.fzimra.android.KeySecurityLevel
import org.donnc.fzimra.example.fiscal.EngineState
import org.donnc.fzimra.example.fiscal.FiscalEngine
import org.donnc.fzimra.example.settings.Mode
import org.donnc.fzimra.example.ui.components.Banner
import org.donnc.fzimra.example.ui.components.BannerKind
import org.donnc.fzimra.example.ui.components.InfoRow
import org.donnc.fzimra.example.ui.components.SectionCard
import org.donnc.fzimra.example.ui.components.StatusPill
import org.donnc.fzimra.example.ui.components.Tone
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(engine: FiscalEngine, onReady: () -> Unit) {
    val model: SetupViewModel = viewModel { SetupViewModel(engine) }
    val ui by model.ui.collectAsStateWithLifecycle()
    val state by engine.state.collectAsStateWithLifecycle()
    var confirmReset by remember { mutableStateOf(false) }

    // Reload the form only when the mode changes, not while it is being edited.
    LaunchedEffect(state.mode) { model.load(state.device) }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Device setup") })
        if (ui.busy != null) LinearProgressIndicator(Modifier.fillMaxWidth())
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ui.error?.let { Banner("That did not work", it, BannerKind.ERROR, action = { TextButton(onClick = model::dismissMessage) { Text("Dismiss") } }) }
            ui.message?.let {
                Banner(it, kind = BannerKind.INFO, action = {
                    Row {
                        if (state.isReady) TextButton(onClick = onReady) { Text("Start selling") }
                        TextButton(onClick = model::dismissMessage) { Text("Dismiss") }
                    }
                })
            }

            ModeChooser(state.mode, enabled = ui.busy == null, onSelect = model::selectMode)

            if (state.isRegistered) {
                RegisteredDevice(state, busy = ui.busy != null, onRefresh = model::refreshConfiguration, onRenew = model::renewCertificate, onReset = { confirmReset = true })
            } else if (state.mode == Mode.SIMULATOR) {
                SimulatorSetup(state, ui, onSetUp = model::setUpSimulator)
            } else {
                ZimraSetup(state.mode, ui, model)
            }

            IntegrationNote()
            ui.busy?.let { Text("$it…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Remove this device?") },
            text = {
                Text(
                    "This deletes the device key, fiscal state and receipt records from this phone." +
                        if (state.pendingReceipts > 0) " ${state.pendingReceipts} receipt(s) ZIMRA has not received will be lost." else "",
                )
            },
            confirmButton = { TextButton(onClick = { confirmReset = false; model.reset() }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Keep") } },
        )
    }
}

@Composable
private fun ModeChooser(selected: Mode, enabled: Boolean, onSelect: (Mode) -> Unit) {
    SectionCard("Where receipts go") {
        Mode.entries.forEach { mode ->
            val isSelected = mode == selected
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                border = if (isSelected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
                modifier = Modifier.fillMaxWidth().clickable(enabled = enabled && !isSelected) { onSelect(mode) },
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = isSelected, onClick = null)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(mode.title, style = MaterialTheme.typography.titleSmall)
                        Text(mode.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (selected == Mode.ZIMRA_PRODUCTION) {
            Banner("Production issues real tax invoices", "Use a production device and activation key from ZIMRA. Every receipt is reported to ZIMRA as a real sale.", BannerKind.WARNING)
        }
    }
}

@Composable
private fun SimulatorSetup(state: EngineState, ui: SetupUi, onSetUp: () -> Unit) {
    SectionCard("Simulated device") {
        Text(
            "The simulator follows ZIMRA's rules: it checks every receipt and signature, validates day closes, and signs what it accepts. " +
                "Open the Traffic tab to watch each exchange.",
            style = MaterialTheme.typography.bodyMedium,
        )
        InfoRow("Device ID", state.device.deviceId.toString())
        InfoRow("Serial number", state.device.serialNo)
        Button(onClick = onSetUp, enabled = ui.busy == null, modifier = Modifier.fillMaxWidth()) {
            if (ui.busy != null) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
            }
            Text("Set up simulated device")
        }
    }
}

@Composable
private fun ZimraSetup(mode: Mode, ui: SetupUi, model: SetupViewModel) {
    SectionCard("1 · The device ZIMRA issued") {
        Text("Enter the details exactly as registered with ZIMRA.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            ui.deviceIdText, { model.edit(ui.form, it) }, label = { Text("Device ID") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            ui.form.serialNo, { model.edit(ui.form.copy(serialNo = it.take(20)), ui.deviceIdText) }, label = { Text("Serial number") },
            singleLine = true, supportingText = { Text("Up to 20 characters") }, modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(ui.form.modelName, { model.edit(ui.form.copy(modelName = it), ui.deviceIdText) }, label = { Text("Model name") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(ui.form.modelVersion, { model.edit(ui.form.copy(modelVersion = it), ui.deviceIdText) }, label = { Text("Version") }, singleLine = true, modifier = Modifier.weight(0.6f))
        }
    }
    SectionCard("2 · Activation key") {
        OutlinedTextField(
            ui.activationKey, model::editKey, label = { Text("Activation key") }, singleLine = true,
            supportingText = { Text("8 characters, from ZIMRA's device portal") },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters), modifier = Modifier.fillMaxWidth(),
        )
        FilledTonalButton(
            onClick = { model.verify(mode) },
            enabled = ui.busy == null && ui.form.isComplete && ui.activationKey.length == 8,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Check with ZIMRA") }
    }
    ui.taxpayer?.let { taxpayer ->
        SectionCard("3 · Confirm the taxpayer") {
            Text("ZIMRA says this device belongs to:", style = MaterialTheme.typography.bodySmall)
            InfoRow("Taxpayer", taxpayer.taxpayerName, emphasise = true)
            InfoRow("TIN", taxpayer.taxpayerTin)
            InfoRow("VAT number", taxpayer.vatNumber ?: "Not registered for VAT")
            InfoRow("Branch", taxpayer.branch.name)
            taxpayer.branch.address?.let { a -> InfoRow("Address", listOfNotNull(a.houseNo, a.street, a.city).joinToString(", ")) }
            Button(onClick = { model.register() }, enabled = ui.busy == null, modifier = Modifier.fillMaxWidth()) { Text("This is us: register the device") }
        }
    }
}

@Composable
private fun RegisteredDevice(state: EngineState, busy: Boolean, onRefresh: () -> Unit, onRenew: () -> Unit, onReset: () -> Unit) {
    val configuration = state.configuration
    SectionCard("Device", action = { StatusPill("Registered", Tone.GOOD) }) {
        InfoRow("Mode", state.mode.title)
        InfoRow("Device ID", state.device.deviceId.toString())
        InfoRow("Serial number", state.device.serialNo)
        InfoRow("Model", "${state.device.modelName} ${state.device.modelVersion}")
        InfoRow(
            "Device key",
            when (state.keySecurity) {
                KeySecurityLevel.STRONGBOX -> "StrongBox secure chip"
                KeySecurityLevel.TRUSTED_ENVIRONMENT -> "Secure hardware (TEE)"
                KeySecurityLevel.SOFTWARE -> "Software only (emulator)"
                else -> "Android Keystore"
            },
        )
        state.certificateExpiry?.let { expiry ->
            val days = Duration.between(Instant.now(), expiry).toDays()
            InfoRow("Certificate", "valid till ${DateTimeFormatter.ofPattern("d MMM yyyy").format(expiry.atZone(ZoneId.systemDefault()))}")
            if (days < 30) Banner("The certificate expires in $days days", "ZIMRA refuses every request after it expires. Renew it now.", BannerKind.WARNING)
        }
    }
    configuration?.let { c ->
        SectionCard("Taxpayer") {
            InfoRow("Name", c.taxpayerName, emphasise = true)
            InfoRow("TIN", c.taxpayerTin)
            InfoRow("VAT number", c.vatNumber ?: "Not registered for VAT")
            InfoRow("Branch", c.branch.name)
            InfoRow("Fiscal day", "at most ${c.dayMaxHours} h")
        }
        SectionCard("Taxes from ZIMRA") {
            c.taxes.forEach { t -> InfoRow(t.name, t.percent?.let { "${it.stripTrailingZeros().toPlainString()}% · ID ${t.taxId}" } ?: "exempt · ID ${t.taxId}") }
        }
    }
    SectionCard("Maintenance") {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onRefresh, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Refresh config") }
            OutlinedButton(onClick = onRenew, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Renew certificate") }
        }
        TextButton(onClick = onReset, enabled = !busy) { Text("Remove device from this phone", color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun IntegrationNote() {
    SectionCard("Using fzimra in your POS") {
        Text(
            "Everything this app does with fzimra lives in one class, FiscalEngine. Copy it into your app, " +
                "map your own item model with ReceiptLineMapper, and install background sync in your Application. " +
                "The example's README walks through it.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
