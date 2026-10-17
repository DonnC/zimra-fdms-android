package org.donnc.fzimra.example.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.donnc.fzimra.core.IssuedReceipt
import org.donnc.fzimra.core.ReceiptRecord
import org.donnc.fzimra.core.ReceiptType
import org.donnc.fzimra.core.ZimraConfiguration

/**
 * A fiscal receipt as a till would print it, from fzimra's receipt model:
 * seller, numbers, lines, tax table, totals, the verification code and the
 * QR code.
 */
@Composable
fun ReceiptView(receipt: IssuedReceipt, configuration: ZimraConfiguration?, record: ReceiptRecord?) {
    val r = receipt.receipt
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                when (r.type) {
                    ReceiptType.FISCAL_INVOICE -> "FISCAL TAX INVOICE"
                    ReceiptType.CREDIT_NOTE -> "CREDIT NOTE"
                    ReceiptType.DEBIT_NOTE -> "DEBIT NOTE"
                },
                style = MaterialTheme.typography.titleMedium,
            )
            configuration?.let {
                Text(it.taxpayerName, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                Text("TIN ${it.taxpayerTin}" + (it.vatNumber?.let { v -> " · VAT $v" } ?: ""), style = MaterialTheme.typography.bodySmall)
                Text(it.branch.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        HorizontalDivider()
        InfoRow("Invoice", r.invoiceNo)
        InfoRow("Receipt", "${receipt.receiptCounter}/${receipt.globalNo} · day ${receipt.fiscalDayNo}")
        InfoRow("Date", dateTime(receipt.date))
        r.buyer?.let { InfoRow("Buyer", "${it.registerName}\nTIN ${it.tin}" + (it.vatNumber?.let { v -> " · VAT $v" } ?: "")) }
        r.notes?.let { InfoRow("Reason", it) }
        HorizontalDivider()
        r.lines.forEach { line ->
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(line.name, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "${line.quantity.stripTrailingZeros().toPlainString()} × " +
                            (line.unitPrice?.let { money(it.abs(), r.currency) } ?: "—") + " · HS ${line.hsCode ?: "—"}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(money(line.total, r.currency), style = MaterialTheme.typography.bodyMedium)
            }
        }
        HorizontalDivider()
        r.taxes.forEach { tax ->
            val name = configuration?.taxes?.firstOrNull { it.taxId == tax.taxId }?.name ?: "Tax ${tax.taxId}"
            InfoRow(name, "${money(tax.taxAmount, r.currency)} of ${money(tax.salesAmountWithTax, r.currency)}")
        }
        InfoRow("Total", money(r.total, r.currency), emphasise = true)
        r.payments.forEach { InfoRow(it.moneyType.specName, money(it.amount, r.currency)) }
        if (r.change.signum() != 0) InfoRow("Change", money(r.change, r.currency))
        HorizontalDivider()
        Column(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            QrCode(receipt.qrCode)
            Text("Verification code", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(receipt.verificationCode, style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace)
            Text("Verify at ${configuration?.qrUrl ?: "ZIMRA"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            record?.let {
                val (text, tone) = it.status.pill()
                StatusPill(text, tone)
                it.zimraReceiptId?.let { id -> Text("ZIMRA receipt ID $id", style = MaterialTheme.typography.bodySmall) }
                it.validations.forEach { v -> StatusPill("${v.code}: ${v.message ?: v.colour}", colourTone(v.colour)) }
            }
        }
    }
}
