package com.bepinex.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.bepinex.android.R
import com.bepinex.android.remote.ModSettingsClient
import com.bepinex.android.remote.RemotePanelIpc
import com.bepinex.android.ui.settingsmod.ModSettingsViewModel
import com.bepinex.android.ui.settingsmod.SchemaSettingsUi
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModRemoteScreen(
    client: ModSettingsClient? = null,
) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val viewModel = remember(client) {
        client?.let { ModSettingsViewModel(it, scope) }
    }

    LaunchedEffect(viewModel?.snackbar) {
        val msg = viewModel?.snackbar
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearSnackbar()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.mod_settings_title)) }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            val statusText = when {
                viewModel == null -> stringResource(R.string.mod_settings_disconnected)
                viewModel.usingFileFallback -> stringResource(R.string.mod_settings_file_fallback)
                else -> viewModel.status
            }
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodyMedium,
                color = if (viewModel?.usingFileFallback == true || viewModel?.schema == null) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            val schema = viewModel?.schema
            if (viewModel != null && schema != null && !viewModel.usingFileFallback) {
                SchemaSettingsUi(
                    schema = schema,
                    values = viewModel.values,
                    viewModel = viewModel,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                FileFallbackWhitelist(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp)
                )
            }
        }
    }
}

@Composable
private fun FileFallbackWhitelist(modifier: Modifier = Modifier) {
    val toggles = remember {
        mutableStateMapOf<String, Boolean>().apply {
            for (key in RemotePanelIpc.WHITELIST_KEYS) put(key, false)
        }
    }
    var note by remember { mutableStateOf("Waiting for mod…") }
    var stale by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        while (true) {
            val state = RemotePanelIpc.readState()
            for ((k, v) in state.keys) {
                toggles[k] = v
            }
            stale = RemotePanelIpc.isStateStale(state)
            note = when {
                state.note.isNotBlank() -> state.note
                stale -> "Mod not connected (state stale)"
                else -> "Connected (file)"
            }
            delay(500)
        }
    }

    Column(modifier = modifier) {
        Text(
            text = note,
            style = MaterialTheme.typography.bodySmall,
            color = if (stale) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        LazyColumn(
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(RemotePanelIpc.WHITELIST_KEYS, key = { it }) { key ->
                val checked = toggles[key] == true
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = labelFor(key),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = checked,
                        onCheckedChange = { next ->
                            toggles[key] = next
                            RemotePanelIpc.writeSetCommand(key, next)
                        }
                    )
                }
            }
        }
    }
}

private fun labelFor(key: String): String = when (key) {
    RemotePanelIpc.KEY_AUTO_FISHING -> "Auto fishing"
    RemotePanelIpc.KEY_INSECT_NET -> "Insect net"
    RemotePanelIpc.KEY_AURA_FARM -> "Aura farm"
    RemotePanelIpc.KEY_AUTO_SELL -> "Auto sell"
    RemotePanelIpc.KEY_NOCLIP -> "Noclip"
    RemotePanelIpc.KEY_ANTI_AFK -> "Anti AFK"
    RemotePanelIpc.KEY_BIRD_PHOTO -> "Bird photo"
    else -> key
}
