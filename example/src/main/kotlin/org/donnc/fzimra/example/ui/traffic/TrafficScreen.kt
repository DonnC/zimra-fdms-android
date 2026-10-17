package org.donnc.fzimra.example.ui.traffic

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.donnc.fzimra.example.fiscal.Exchange
import org.donnc.fzimra.example.fiscal.FiscalEngine
import org.donnc.fzimra.example.ui.components.EmptyState
import org.donnc.fzimra.example.ui.components.StatusPill
import org.donnc.fzimra.example.ui.components.Tone
import org.donnc.fzimra.example.ui.theme.CodeStyle
import org.json.JSONArray
import org.json.JSONObject
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrafficScreen(engine: FiscalEngine) {
    val exchanges by engine.traffic.exchanges.collectAsStateWithLifecycle()
    val state by engine.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Traffic") },
            actions = { if (exchanges.isNotEmpty()) TextButton(onClick = engine.traffic::clear) { Text("Clear") } },
        )
        if (exchanges.isEmpty()) {
            EmptyState("📡", "Nothing sent yet", "Every request to ${state.mode.title} and its answer appears here, newest first.")
        } else {
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(exchanges, key = { it.id }) { ExchangeRow(it) }
            }
        }
    }
}

private val TIME = DateTimeFormatter.ofPattern("HH:mm:ss")

@Composable
private fun ExchangeRow(exchange: Exchange) {
    var expanded by remember { mutableStateOf(false) }
    val (label, tone) = when {
        exchange.error != null -> "No answer" to Tone.BAD
        exchange.status in 200..299 -> "${exchange.status}" to Tone.GOOD
        exchange.status != null && exchange.status >= 500 -> "${exchange.status}" to Tone.WARNING
        else -> "${exchange.status}" to Tone.BAD
    }
    val findings = remember(exchange.response) { findingCount(exchange.response) }
    Card(
        onClick = { expanded = !expanded },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        modifier = Modifier.animateContentSize(),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(exchange.method, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(44.dp))
                Text(exchange.endpoint, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (findings > 0) {
                    StatusPill("$findings finding${if (findings == 1) "" else "s"}", Tone.WARNING)
                    Spacer(Modifier.width(6.dp))
                }
                StatusPill(label, tone)
            }
            Text(
                "${exchange.at.format(TIME)} · ${exchange.millis} ms" + (exchange.error?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (expanded) {
                exchange.request?.let { Json("Request", it) }
                exchange.response?.takeIf { it.isNotBlank() }?.let { Json("Answer", it) }
            }
        }
    }
}

@Composable
private fun Json(title: String, text: String) {
    Text(title, style = MaterialTheme.typography.labelLarge)
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.small) {
        SelectionContainer {
            Text(pretty(text), style = CodeStyle, modifier = Modifier.horizontalScroll(rememberScrollState()).padding(10.dp))
        }
    }
}

private fun pretty(text: String): String = runCatching {
    val trimmed = text.trim()
    if (trimmed.startsWith("[")) JSONArray(trimmed).toString(2) else JSONObject(trimmed).toString(2)
}.getOrDefault(text).replace("\\/", "/")

private fun findingCount(response: String?): Int = runCatching {
    JSONObject(response ?: return 0).optJSONArray("validationErrors")?.length() ?: 0
}.getOrDefault(0)
