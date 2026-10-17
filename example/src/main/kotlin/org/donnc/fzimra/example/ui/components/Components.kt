package org.donnc.fzimra.example.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import androidx.core.graphics.set
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import org.donnc.fzimra.core.ReceiptSyncStatus
import org.donnc.fzimra.example.ui.theme.LocalStatusColors
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

// -- formatting -------------------------------------------------------------------

fun money(amount: BigDecimal, currency: String): String {
    val symbol = when (currency.uppercase()) {
        "USD" -> "US$"
        "ZWG" -> "ZWG "
        else -> currency.uppercase() + " "
    }
    val value = amount.setScale(2, RoundingMode.HALF_EVEN)
    return (if (value.signum() < 0) "−" else "") + symbol + "%,.2f".format(value.abs())
}

private val DATE_TIME = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm")
private val TIME = DateTimeFormatter.ofPattern("HH:mm:ss")

fun dateTime(value: LocalDateTime): String = value.format(DATE_TIME)

fun time(value: LocalDateTime): String = value.format(TIME)

/** "just now", "5 min ago", "3 h ago", "2 days ago". */
fun ago(value: LocalDateTime, now: LocalDateTime): String {
    val d = Duration.between(value, now)
    return when {
        d.seconds < 60 -> "just now"
        d.toMinutes() < 60 -> "${d.toMinutes()} min ago"
        d.toHours() < 48 -> "${d.toHours()} h ago"
        else -> "${d.toDays()} days ago"
    }
}

fun hoursAndMinutes(d: Duration): String {
    val minutes = d.toMinutes().coerceAtLeast(0)
    return if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min"
}

// -- status ---------------------------------------------------------------------

enum class Tone { GOOD, WAITING, WARNING, BAD, NEUTRAL }

@Composable
fun toneColor(tone: Tone): Color {
    val c = LocalStatusColors.current
    return when (tone) {
        Tone.GOOD -> c.good
        Tone.WAITING -> c.waiting
        Tone.WARNING -> c.warning
        Tone.BAD -> c.bad
        Tone.NEUTRAL -> c.neutral
    }
}

@Composable
fun StatusPill(text: String, tone: Tone, modifier: Modifier = Modifier) {
    val color = toneColor(tone)
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

fun ReceiptSyncStatus.pill(): Pair<String, Tone> = when (this) {
    ReceiptSyncStatus.PENDING -> "Waiting for ZIMRA" to Tone.WAITING
    ReceiptSyncStatus.SUBMITTED -> "Accepted by ZIMRA" to Tone.GOOD
    ReceiptSyncStatus.REJECTED -> "Refused by ZIMRA" to Tone.BAD
}

fun colourTone(colour: String?): Tone = when (colour?.lowercase()) {
    "red", "grey" -> Tone.BAD
    "yellow" -> Tone.WARNING
    else -> Tone.NEUTRAL
}

// -- layout ---------------------------------------------------------------------

@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                action?.invoke()
            }
            content()
        }
    }
}

@Composable
fun InfoRow(label: String, value: String, modifier: Modifier = Modifier, emphasise: Boolean = false) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.45f),
        )
        Text(
            value, style = if (emphasise) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.End, modifier = Modifier.weight(0.55f),
        )
    }
}

enum class BannerKind { INFO, WARNING, ERROR }

@Composable
fun Banner(title: String, text: String? = null, kind: BannerKind = BannerKind.INFO, action: (@Composable () -> Unit)? = null) {
    val tone = when (kind) {
        BannerKind.INFO -> Tone.WAITING
        BannerKind.WARNING -> Tone.WARNING
        BannerKind.ERROR -> Tone.BAD
    }
    val color = toneColor(tone)
    Surface(color = color.copy(alpha = 0.10f), shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            Box(Modifier.padding(top = 6.dp).size(8.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                if (text != null) Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                action?.invoke()
            }
        }
    }
}

@Composable
fun EmptyState(emoji: String, title: String, text: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(emoji, style = MaterialTheme.typography.displayMedium)
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(24.dp))
            Button(onClick = onAction) { Text(action) }
        }
    }
}

// -- QR codes ---------------------------------------------------------------------

/** [content] as a QR code. Drawing it is the app's job; fzimra gives the text. */
@Composable
fun QrCode(content: String, size: Dp = 168.dp) {
    val bitmap = remember(content) { qrBitmap(content) }
    Surface(color = Color.White, shape = MaterialTheme.shapes.small) {
        Image(
            bitmap, contentDescription = null, filterQuality = FilterQuality.None,
            modifier = Modifier.padding(8.dp).size(size).semantics { contentDescription = "Receipt QR code" },
        )
    }
}

private fun qrBitmap(content: String): ImageBitmap {
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 0))
    val bitmap = createBitmap(matrix.width, matrix.height)
    for (x in 0 until matrix.width) for (y in 0 until matrix.height) {
        bitmap[x, y] = if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
    }
    return bitmap.asImageBitmap()
}

@Composable
fun Heading(text: String, modifier: Modifier = Modifier) {
    Text(
        text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold, modifier = modifier.padding(top = 8.dp, bottom = 2.dp),
    )
}
