package com.bepinex.android.ui.settingsmod

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SchemaSettingsUi(
    schema: SchemaDoc,
    values: Map<String, ValueEntry>,
    viewModel: ModSettingsViewModel,
    modifier: Modifier = Modifier,
) {
    var selectedTabId by remember(schema) {
        mutableStateOf(schema.tabs.firstOrNull()?.id.orEmpty())
    }
    val selectedTab = schema.tabs.firstOrNull { it.id == selectedTabId } ?: schema.tabs.firstOrNull()
    var selectedSubId by remember(selectedTabId) {
        mutableStateOf(selectedTab?.subs?.firstOrNull()?.id)
    }

    Row(modifier = modifier.fillMaxSize()) {
        NavigationRail(
            modifier = Modifier.fillMaxHeight()
        ) {
            schema.tabs.forEach { tab ->
                NavigationRailItem(
                    selected = tab.id == selectedTabId,
                    onClick = {
                        selectedTabId = tab.id
                        selectedSubId = tab.subs.firstOrNull()?.id
                    },
                    icon = { Text(tab.label.take(1), style = MaterialTheme.typography.labelSmall) },
                    label = {
                        Text(
                            tab.label,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 2,
                        )
                    }
                )
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(horizontal = 12.dp)
        ) {
            val subs = selectedTab?.subs.orEmpty()
            if (subs.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScrollChips(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    subs.forEach { sub ->
                        FilterChip(
                            selected = sub.id == selectedSubId,
                            onClick = { selectedSubId = sub.id },
                            label = { Text(sub.label) }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            val rows = schema.controls
                .filter { it.tab == selectedTabId }
                .filter { selectedSubId == null || it.sub == null || it.sub == selectedSubId }
                .sortedBy { it.order }

            LazyColumn(
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(rows, key = { it.key }) { control ->
                    ControlRow(control, values[control.key], viewModel)
                }
            }
        }
    }
}

@Composable
private fun Modifier.horizontalScrollChips(): Modifier = this // chips wrap via Row; ok on portrait

@Composable
private fun ControlRow(
    control: SchemaControl,
    entry: ValueEntry?,
    viewModel: ModSettingsViewModel,
) {
    when (control.kind) {
        "header" -> {
            Text(
                text = control.label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
            )
        }
        "note" -> {
            Text(
                text = control.label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        "bool" -> {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                LabelWithRisk(control, entry, Modifier.weight(1f))
                Switch(
                    checked = ModSettingsJson.boolValue(entry),
                    onCheckedChange = { viewModel.setBool(control.key, it) }
                )
            }
        }
        "float" -> {
            val min = control.min ?: 0f
            val max = control.max ?: 1f
            var local by remember(control.key, entry?.value) {
                mutableFloatStateOf(ModSettingsJson.floatValue(entry, min).coerceIn(min, max))
            }
            Column(modifier = Modifier.fillMaxWidth()) {
                LabelWithRisk(control, entry)
                Text(
                    text = String.format("%.2f", local),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = local,
                    onValueChange = {
                        local = it
                        viewModel.setFloatDebounced(control.key, it, min, max)
                    },
                    valueRange = min..max
                )
            }
        }
        "int" -> {
            val min = (control.min ?: 0f).toInt()
            val max = (control.max ?: 100f).toInt()
            val current = ModSettingsJson.intValue(entry, min).coerceIn(min, max)
            var local by remember(control.key, entry?.value) { mutableFloatStateOf(current.toFloat()) }
            Column(modifier = Modifier.fillMaxWidth()) {
                LabelWithRisk(control, entry)
                Text(
                    text = local.toInt().toString(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = local,
                    onValueChange = {
                        local = it
                        viewModel.setInt(control.key, it.toInt().coerceIn(min, max))
                    },
                    valueRange = min.toFloat()..max.toFloat(),
                    steps = ((max - min).coerceAtLeast(1) - 1).coerceAtLeast(0)
                )
            }
        }
        "enum" -> {
            var expanded by remember { mutableStateOf(false) }
            val current = ModSettingsJson.stringValue(entry)
            val label = control.options?.firstOrNull { it.id == current }?.label ?: current
            Column {
                LabelWithRisk(control, entry)
                TextButton(onClick = { expanded = true }) {
                    Text(if (label.isBlank()) "Select…" else label)
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    control.options.orEmpty().forEach { opt ->
                        DropdownMenuItem(
                            text = { Text(opt.label) },
                            onClick = {
                                expanded = false
                                viewModel.setEnum(control.key, opt.id)
                            }
                        )
                    }
                }
            }
        }
        "action" -> {
            Button(
                onClick = { viewModel.invokeAction(control.key) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(control.label)
            }
        }
        "list_pick" -> {
            ListPickRow(control, entry, viewModel)
        }
        else -> {
            Text(
                text = "Unsupported: ${control.kind} (${control.label})",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun LabelWithRisk(
    control: SchemaControl,
    entry: ValueEntry?,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = control.label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f, fill = false)
        )
        val showRisk = control.risk == "always" ||
            (control.risk == "conditional" && entry?.riskActive == true)
        if (showRisk) {
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "!",
                color = MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun ListPickRow(
    control: SchemaControl,
    entry: ValueEntry?,
    viewModel: ModSettingsViewModel,
) {
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var items by remember { mutableStateOf<List<LiveListItem>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    val selected = ModSettingsJson.stringValue(entry)

    Column(modifier = Modifier.fillMaxWidth()) {
        LabelWithRisk(control, entry)
        TextButton(onClick = {
            open = true
            scope.launch {
                val listId = control.listId.orEmpty()
                items = if (listId.isNotBlank()) viewModel.fetchLiveList(listId) else emptyList()
            }
        }) {
            Text(if (selected.isBlank()) "Select…" else selected)
        }
    }

    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(control.label) },
            text = {
                Column {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("Search") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    val filtered = items.filter {
                        query.isBlank() ||
                            it.label.contains(query, ignoreCase = true) ||
                            it.id.contains(query, ignoreCase = true)
                    }
                    Column(
                        modifier = Modifier
                            .height(280.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        if (filtered.isEmpty()) {
                            Text(
                                "No items (world may not be ready)",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        filtered.forEach { item ->
                            Text(
                                text = item.label,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        viewModel.setListPick(control.key, item.id)
                                        open = false
                                    }
                                    .padding(vertical = 10.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { open = false }) { Text("Close") }
            }
        )
    }
}
