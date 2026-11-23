package com.bepinex.android.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.bepinex.android.R
import com.bepinex.android.remote.ModSettingsClient
import com.bepinex.android.ui.settingsmod.ModSettingsViewModel
import com.bepinex.android.ui.settingsmod.SchemaSettingsUi

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
                viewModel.schema == null -> viewModel.status.ifBlank {
                    stringResource(R.string.mod_settings_disconnected)
                }
                else -> viewModel.status
            }
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodyMedium,
                color = if (viewModel?.schema == null) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            val schema = viewModel?.schema
            if (viewModel != null && schema != null) {
                SchemaSettingsUi(
                    schema = schema,
                    values = viewModel.values,
                    viewModel = viewModel,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Text(
                    text = stringResource(R.string.mod_settings_disconnected),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }
    }
}
