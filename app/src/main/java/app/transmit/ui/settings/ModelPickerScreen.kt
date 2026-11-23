package app.transmit.ui.settings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.transmit.ai.Effort
import app.transmit.ai.ModelInfo
import app.transmit.ai.Price
import app.transmit.ai.Pricing
import app.transmit.ai.usd
import app.transmit.data.ModelSlot
import app.transmit.ui.theme.LocalTransmitColors

/** Typical tokens per call, used to show an intuitive per-message price. */
private fun perCall(slot: ModelSlot) = if (slot == ModelSlot.TRANSLATE) 350L to 80L else 900L to 600L

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ModelPickerScreen(vm: SettingsViewModel, slot: ModelSlot, onBack: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val state by vm.models.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var editPrice by remember { mutableStateOf<ModelInfo?>(null) }
    LaunchedEffect(settings?.provider) { if (settings != null) vm.loadModels() }
    val s = settings ?: return
    val selected = s.model(slot)
    val effort = s.effort(slot)
    val selectedInfo = state.models.firstOrNull { it.id == selected }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (slot == ModelSlot.TRANSLATE) "Translation model" else "Tutor model") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = { IconButton(onClick = { vm.loadModels(refresh = true) }, enabled = !state.loading) { Icon(Icons.Default.Refresh, "Refresh") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            // Effort: only the levels the chosen model supports (plus provider default).
            val efforts = listOf(Effort.DEFAULT) + (selectedInfo?.efforts ?: emptyList())
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Speed, "Effort", tint = MaterialTheme.colorScheme.primary)
                efforts.distinct().forEach { e ->
                    FilterChip(selected = e == effort, onClick = { vm.setEffort(slot, e) }, label = { Text(e.label) })
                }
            }
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, null) },
                placeholder = { Text("Search models") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
            // Unpriced calls count as $0, so budgets never stop them.
            val selectedPrice = selected?.let { s.priceOverride(s.provider, it) ?: selectedInfo?.price ?: Pricing.builtIn(s.provider, it) }
            if (selected != null && selectedPrice == null && !state.loading && state.models.isNotEmpty()) {
                Text(
                    "No price is known for this model, so what it spends doesn't count toward your budgets. Long-press it in the list to set its price.",
                    color = LocalTransmitColors.current.warn, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            val q = query.trim().lowercase()
            val list = state.models.filter { q.isEmpty() || it.id.lowercase().contains(q) || it.name.lowercase().contains(q) }
            LazyColumn(Modifier.fillMaxSize()) {
                if (q.isNotEmpty() && state.models.none { it.id.equals(q, ignoreCase = true) }) {
                    item("custom") {
                        Row(
                            Modifier.fillMaxWidth().combinedClickable(onClick = { vm.setModel(slot, query.trim()) }).padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Default.Edit, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(16.dp))
                            Text("Use “${query.trim()}”", style = MaterialTheme.typography.bodyLarge)
                        }
                        HorizontalDivider()
                    }
                }
                items(list, key = { it.id }) { m ->
                    val price = s.priceOverride(s.provider, m.id) ?: m.price ?: Pricing.builtIn(s.provider, m.id)
                    ModelRowItem(
                        m, price, selected = m.id == selected, slot = slot,
                        onClick = { vm.setModel(slot, m.id) },
                        onLongClick = { editPrice = m },
                    )
                }
            }
        }
    }

    editPrice?.let { m ->
        PriceDialog(
            model = m.id,
            current = s.priceOverride(s.provider, m.id) ?: m.price ?: Pricing.builtIn(s.provider, m.id),
            onSave = { vm.setPriceOverride(m.id, it); editPrice = null },
            onDismiss = { editPrice = null },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ModelRowItem(m: ModelInfo, price: Price?, selected: Boolean, slot: ModelSlot, onClick: () -> Unit, onLongClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(24.dp)) {
            if (selected) Icon(Icons.Default.Check, "Selected", tint = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(m.name, style = MaterialTheme.typography.bodyLarge, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
            if (m.name != m.id) Text(m.id, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (m.efforts.isNotEmpty()) {
                Text(m.efforts.joinToString(" · ") { it.label }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
        }
        if (price != null) {
            val (inT, outT) = perCall(slot)
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "${if (price.estimated) "≈" else ""}${perMessage(price.cost(inT, outT))}",
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "${fmt(price.inputPerM)} / ${fmt(price.outputPerM)}",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Text("No price", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}

/** Per-message costs are usually fractions of a cent, so show those in cents (2 significant digits). */
private fun perMessage(v: Double): String =
    if (v >= 0.01) usd(v)
    else java.math.BigDecimal(v * 100).round(java.math.MathContext(2)).stripTrailingZeros().toPlainString() + "¢"

private fun fmt(v: Double) = if (v >= 1 || v == 0.0) "$%.2f".format(v).removeSuffix(".00") else "$%.3f".format(v).trimEnd('0')

@Composable
private fun PriceDialog(model: String, current: Price?, onSave: (Price?) -> Unit, onDismiss: () -> Unit) {
    var input by remember { mutableStateOf(current?.inputPerM?.toString().orEmpty()) }
    var output by remember { mutableStateOf(current?.outputPerM?.toString().orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(model) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("USD per million tokens", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(input, { input = it }, label = { Text("Input") }, prefix = { Text("$") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                OutlinedTextField(output, { output = it }, label = { Text("Output") }, prefix = { Text("$") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val i = input.toDoubleOrNull()
                val o = output.toDoubleOrNull()
                if (i != null && o != null) onSave(Price(i, o))
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { onSave(null) }) { Text("Reset") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
