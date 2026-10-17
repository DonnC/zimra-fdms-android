package org.donnc.fzimra.example.ui.receipts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.donnc.fzimra.core.IssueResult
import org.donnc.fzimra.core.ReceiptRecord
import org.donnc.fzimra.core.ReceiptSyncStatus
import org.donnc.fzimra.core.ReceiptType
import org.donnc.fzimra.example.fiscal.FiscalEngine
import org.donnc.fzimra.example.ui.components.Banner
import org.donnc.fzimra.example.ui.components.BannerKind
import org.donnc.fzimra.example.ui.components.EmptyState
import org.donnc.fzimra.example.ui.components.ReceiptView
import org.donnc.fzimra.example.ui.components.StatusPill
import org.donnc.fzimra.example.ui.components.Tone
import org.donnc.fzimra.example.ui.components.dateTime
import org.donnc.fzimra.example.ui.components.money
import org.donnc.fzimra.example.ui.components.pill
import org.donnc.fzimra.example.ui.friendly

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiptsScreen(engine: FiscalEngine, onSetup: () -> Unit) {
    val state by engine.state.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf<ReceiptRecord?>(null) }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Receipts") })
        when {
            !state.isReady -> EmptyState("🧾", "No device yet", "Set up the device to start issuing receipts.", "Go to setup", onSetup)
            state.receipts.isEmpty() -> EmptyState("🛒", "No receipts yet", "Receipts you issue appear here, with where they stand with ZIMRA.")
            else -> LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    Text(
                        "fzimra keeps records of accepted receipts for 7 days; send them to your own server to keep them longer.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(state.receipts, key = { "${it.receipt.fiscalDayNo}-${it.receipt.globalNo}" }) { record ->
                    ReceiptRow(record) { open = record }
                }
            }
        }
    }

    open?.let { record ->
        val current = state.receipts.firstOrNull { it.receipt.fiscalDayNo == record.receipt.fiscalDayNo && it.receipt.globalNo == record.receipt.globalNo } ?: record
        ReceiptDetail(engine, current, onClose = { open = null })
    }
}

@Composable
private fun ReceiptRow(record: ReceiptRecord, onClick: () -> Unit) {
    val r = record.receipt.receipt
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (r.type == ReceiptType.CREDIT_NOTE) "↩️" else "🧾", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(r.invoiceNo, style = MaterialTheme.typography.titleSmall)
                Text(
                    "${dateTime(record.receipt.date)} · day ${record.receipt.fiscalDayNo} · #${record.receipt.globalNo}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val (text, tone) = record.status.pill()
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatusPill(text, tone)
                    if (record.validations.any { it.colour.equals("Red", true) || it.colour.equals("Grey", true) }) StatusPill("Flagged", Tone.BAD)
                }
            }
            Text(money(r.total, r.currency), style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun ReceiptDetail(engine: FiscalEngine, record: ReceiptRecord, onClose: () -> Unit) {
    val state by engine.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var askReason by remember { mutableStateOf(false) }
    var reason by remember { mutableStateOf("Goods returned") }
    var outcome by remember { mutableStateOf<String?>(null) }
    val refundable = record.receipt.receipt.type == ReceiptType.FISCAL_INVOICE && record.status != ReceiptSyncStatus.REJECTED

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth(0.94f).padding(vertical = 24.dp)) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ReceiptView(record.receipt, state.configuration, record)
                outcome?.let { Banner(it, kind = BannerKind.INFO) }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (refundable) OutlinedButton(onClick = { askReason = true }, modifier = Modifier.weight(1f)) { Text("Refund") }
                    Button(onClick = onClose, modifier = Modifier.weight(1f)) { Text("Close") }
                }
            }
        }
    }

    if (askReason) {
        AlertDialog(
            onDismissRequest = { askReason = false },
            title = { Text("Refund ${record.receipt.receipt.invoiceNo}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("A credit note returns the full amount. ZIMRA requires a reason.")
                    OutlinedTextField(reason, { reason = it.take(200) }, label = { Text("Reason") }, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(enabled = reason.isNotBlank(), onClick = {
                    askReason = false
                    scope.launch {
                        outcome = try {
                            when (val result = engine.refund(record, reason.trim())) {
                                is IssueResult.Issued -> "Credit note ${result.receipt.receipt.invoiceNo} issued."
                                is IssueResult.Refused -> result.problems.joinToString("\n") { it.message }
                            }
                        } catch (e: Exception) {
                            e.friendly()
                        }
                    }
                }) { Text("Issue credit note") }
            },
            dismissButton = { TextButton(onClick = { askReason = false }) { Text("Cancel") } },
        )
    }
}
