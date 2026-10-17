package org.donnc.fzimra.example.ui.sell

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.donnc.fzimra.core.ReceiptResult
import org.donnc.fzimra.example.fiscal.Catalogue
import org.donnc.fzimra.example.fiscal.EngineState
import org.donnc.fzimra.example.fiscal.FiscalEngine
import org.donnc.fzimra.example.fiscal.PaymentChoice
import org.donnc.fzimra.example.fiscal.Product
import org.donnc.fzimra.example.ui.components.Banner
import org.donnc.fzimra.example.ui.components.BannerKind
import org.donnc.fzimra.example.ui.components.EmptyState
import org.donnc.fzimra.example.ui.components.InfoRow
import org.donnc.fzimra.example.ui.components.ReceiptView
import org.donnc.fzimra.example.ui.components.StatusPill
import org.donnc.fzimra.example.ui.components.Tone
import org.donnc.fzimra.example.ui.components.hoursAndMinutes
import org.donnc.fzimra.example.ui.components.money
import java.time.Duration

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SellScreen(engine: FiscalEngine, onSetup: () -> Unit) {
    val state by engine.state.collectAsStateWithLifecycle()
    if (!state.isReady) {
        EmptyState("🧾", "Set up the device first", "Register the device with the simulator or with ZIMRA, then come back to sell.", "Go to setup", onSetup)
        return
    }
    val model: SellViewModel = viewModel { SellViewModel(engine) }
    val ui by model.ui.collectAsStateWithLifecycle()
    var checkout by remember { mutableStateOf(false) }
    // The sheet stays open while issuing, so a refusal is shown where the sale was made.
    LaunchedEffect(ui.issued) { if (ui.issued != null) checkout = false }
    val taxes = remember(state.status?.configuration) { engine.terminal?.taxes }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(state.status?.configuration?.branch?.name ?: "Till") },
            actions = { SyncChip(state); Spacer(Modifier.width(12.dp)) },
        )
        DayNotice(state)
        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.weight(1f),
        ) {
            items(Catalogue.products, key = { it.sku }) { product ->
                ProductTile(product, ui.quantityOf(product), available = taxes != null && Catalogue.tax(taxes, product.taxClass) != null, currency = ui.currency, onAdd = { model.add(product) })
            }
        }
        AnimatedVisibility(ui.cart.isNotEmpty()) {
            CartBar(ui, onClear = model::clear, onCheckout = { checkout = true })
        }
    }

    if (checkout && ui.cart.isNotEmpty()) {
        ModalBottomSheet(onDismissRequest = { checkout = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Checkout(ui, model)
        }
    }

    ui.issued?.let { receipt ->
        val record = state.receipts.firstOrNull { it.receipt.fiscalDayNo == receipt.fiscalDayNo && it.receipt.globalNo == receipt.globalNo }
        Dialog(onDismissRequest = model::dismissReceipt, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth(0.94f).padding(vertical = 24.dp)) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Receipt issued", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Signed on this device and saved. It goes to ZIMRA as soon as the network allows.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    ui.warnings.forEach { Banner("Not checked", it.message, BannerKind.WARNING) }
                    ReceiptView(receipt, state.status?.configuration, record)
                    Button(onClick = model::dismissReceipt, modifier = Modifier.fillMaxWidth()) { Text("New sale") }
                }
            }
        }
    }
}

@Composable
private fun ProductTile(product: Product, quantity: Int, available: Boolean, currency: String, onAdd: () -> Unit) {
    Card(
        onClick = onAdd, enabled = available,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Box {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(product.emoji, style = MaterialTheme.typography.headlineMedium)
                Text(product.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(money(product.price, currency), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
                Text(if (available) product.taxClass.label else "No ${product.taxClass.label} tax", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (quantity > 0) {
                Badge(Modifier.align(Alignment.TopEnd).padding(10.dp)) { Text("$quantity", Modifier.padding(horizontal = 4.dp)) }
            }
        }
    }
}

@Composable
private fun CartBar(ui: SellUi, onClear: () -> Unit, onCheckout: () -> Unit) {
    val total = (ui.preview as? ReceiptResult.Prepared)?.receipt?.total
    Surface(tonalElevation = 3.dp, shadowElevation = 8.dp) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${ui.itemCount} item${if (ui.itemCount == 1) "" else "s"}", style = MaterialTheme.typography.labelLarge)
                Text(total?.let { money(it, ui.currency) } ?: "—", style = MaterialTheme.typography.headlineSmall)
            }
            TextButton(onClick = onClear) { Text("Clear") }
            Button(onClick = onCheckout) { Text("Checkout") }
        }
    }
}

@Composable
private fun Checkout(ui: SellUi, model: SellViewModel) {
    val prepared = (ui.preview as? ReceiptResult.Prepared)?.receipt
    val problems = (ui.preview as? ReceiptResult.Refused)?.problems.orEmpty()
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Checkout", style = MaterialTheme.typography.titleLarge)
        ui.cart.forEach { line ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(line.product.emoji)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(line.product.name, style = MaterialTheme.typography.bodyMedium)
                    Text(money(line.product.price, ui.currency) + " each", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Stepper(line.quantity, onMinus = { model.remove(line.product) }, onPlus = { model.add(line.product) })
            }
        }
        HorizontalDivider()
        Text("Currency", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("USD", "ZWG").forEach { FilterChip(selected = ui.currency == it, onClick = { model.currency(it) }, label = { Text(it) }) }
        }
        Text("Payment", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PaymentChoice.entries.forEach { FilterChip(selected = ui.payment == it, onClick = { model.payment(it) }, label = { Text(it.label) }) }
        }
        if (ui.payment == PaymentChoice.CASH) {
            OutlinedTextField(
                ui.cashTendered, model::cashTendered, label = { Text("Cash received (optional)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Business customer", style = MaterialTheme.typography.labelLarge)
                Text("Print the buyer's name and TIN", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(ui.businessCustomer, model::businessCustomer)
        }
        if (ui.businessCustomer) {
            OutlinedTextField(ui.buyerName, { model.buyer(it, ui.buyerTin, ui.buyerVat) }, label = { Text("Registered name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(ui.buyerTin, { model.buyer(ui.buyerName, it, ui.buyerVat) }, label = { Text("TIN (10)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                OutlinedTextField(ui.buyerVat, { model.buyer(ui.buyerName, ui.buyerTin, it) }, label = { Text("VAT (9)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
            }
        }
        HorizontalDivider()
        prepared?.let { r ->
            InfoRow("Tax included", money(r.totalTax, r.currency))
            InfoRow("Total", money(r.total, r.currency), emphasise = true)
            if (r.change.signum() > 0) InfoRow("Change", money(r.change, r.currency))
        }
        problems.forEach { Banner(it.message, it.zimraCode?.let { code -> "ZIMRA would flag this as $code" }, BannerKind.WARNING) }
        ui.error?.let { Banner("Not issued", it, BannerKind.ERROR) }
        Button(
            onClick = model::charge,
            enabled = prepared != null && !ui.issuing,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            if (ui.issuing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else Text("Charge " + (prepared?.let { money(it.total, it.currency) } ?: ""))
        }
    }
}

@Composable
private fun Stepper(quantity: Int, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = onMinus, shape = CircleShape, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(34.dp)) { Text("−") }
        Text("$quantity", style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(36.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        OutlinedButton(onClick = onPlus, shape = CircleShape, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(34.dp)) { Text("+") }
    }
}

/** How the receipts stand with ZIMRA, at a glance. */
@Composable
fun SyncChip(state: EngineState) {
    val status = state.status
    when {
        state.isSyncing -> StatusPill("Sending…", Tone.WAITING)
        status == null -> {}
        status.problems.isNotEmpty() -> StatusPill("Needs attention", Tone.BAD)
        status.pendingReceipts > 0 -> StatusPill("${status.pendingReceipts} waiting", Tone.WARNING)
        else -> StatusPill("All sent", Tone.GOOD)
    }
}

/** ZIMRA requires warning the cashier before the fiscal day's maximum length. */
@Composable
private fun DayNotice(state: EngineState) {
    val status = state.status ?: return
    val day = status.day ?: return
    val endsAt = status.dayEndsAt ?: return
    if (status.isDayEndingSoon) {
        val left = Duration.between(java.time.LocalDateTime.now(java.time.ZoneId.of("Africa/Harare")), endsAt)
        Box(Modifier.padding(horizontal = 16.dp)) {
            Banner(
                "Fiscal day ${day.fiscalDayNo} ends in ${hoursAndMinutes(left)}",
                "Close it from the Fiscal day tab; sales after that go into a new day automatically.",
                BannerKind.WARNING,
            )
        }
    }
}
