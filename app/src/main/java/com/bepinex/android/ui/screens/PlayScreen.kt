package com.bepinex.android.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Announcement
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.bepinex.android.BepInExLog
import com.bepinex.android.ui.components.BugtopiaTitle
import com.bepinex.android.GameDetector
import com.bepinex.android.HeartopiaProfiles
import com.bepinex.android.HeartopiaReadiness
import com.bepinex.android.R
import com.bepinex.android.auth.XdGoogleWebAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Heartopia home: install status, launch, modpacks, readiness, and save profiles.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayScreen(
    game: GameDetector.DetectedGame?,
    isScanning: Boolean,
    isFrameworkReady: Boolean,
    isExtracting: Boolean,
    extractionStatus: String,
    activeModpackName: String?,
    isSwitchingModpack: Boolean,
    showIncompleteBanner: Boolean,
    onRescan: () -> Unit,
    onLaunch: () -> Unit,
    onExtractFramework: () -> Unit,
    onExportLogs: () -> Unit,
    onShowAnnouncement: () -> Unit,
    modpackSection: @Composable () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    var report by remember { mutableStateOf<HeartopiaReadiness.Report?>(null) }
    var profiles by remember { mutableStateOf<HeartopiaProfiles.Snapshot?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var profileName by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<String?>(null) }

    fun reload() {
        val appContext = context.applicationContext
        val modpack = activeModpackName
        scope.launch(Dispatchers.IO) {
            val nextReport = HeartopiaReadiness.inspect(appContext, modpack)
            val nextProfiles = HeartopiaProfiles.list(appContext)
            withContext(Dispatchers.Main) {
                report = nextReport
                profiles = nextProfiles
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) reload()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(activeModpackName, isExtracting, isFrameworkReady, game?.versionName) {
        reload()
    }

    fun runIo(label: String, block: suspend () -> String) {
        if (busy != null) return
        busy = label
        val appContext = context.applicationContext
        scope.launch(Dispatchers.IO) {
            val message = try {
                block()
            } catch (error: HeartopiaProfiles.ProfileException) {
                error.message ?: appContext.getString(R.string.profiles_failed, error.javaClass.simpleName)
            } catch (error: Exception) {
                BepInExLog.e("Play action failed", error)
                appContext.getString(R.string.profiles_failed, error.message ?: error.javaClass.simpleName)
            }
            withContext(Dispatchers.Main) {
                busy = null
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                reload()
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    BugtopiaTitle(
                        title = stringResource(R.string.play_title),
                        subtitle = stringResource(R.string.play_subtitle)
                    )
                },
                windowInsets = WindowInsets.safeDrawing.only(
                    WindowInsetsSides.Horizontal + WindowInsetsSides.Top
                ),
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                actions = {
                    IconButton(onClick = onShowAnnouncement) {
                        Icon(Icons.Outlined.Announcement, stringResource(R.string.update_announcement))
                    }
                    IconButton(onClick = onRescan, enabled = !isScanning) {
                        Icon(Icons.Filled.Refresh, stringResource(R.string.rescan))
                    }
                    IconButton(onClick = onExportLogs) {
                        Icon(Icons.Filled.Share, stringResource(R.string.crash_export_logs))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (showIncompleteBanner) {
                Text(
                    text = stringResource(R.string.lang_incomplete_message),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            StatusCard(
                game = game,
                isScanning = isScanning,
                isExtracting = isExtracting,
                extractionStatus = extractionStatus,
                activeModpackName = activeModpackName,
                isSwitchingModpack = isSwitchingModpack,
                onLaunch = onLaunch,
                onRescan = onRescan
            )
            modpackSection()
            ReadinessCard(
                    report = report,
                    busy = busy,
                    isExtracting = isExtracting,
                    onExtract = onExtractFramework,
                    onDownloadUnity = {
                        runIo(context.getString(R.string.ready_unity_download)) {
                            HeartopiaReadiness.downloadUnity(context.applicationContext) { }.message
                        }
                    },
                    onRegenInterop = {
                        runIo(context.getString(R.string.ready_interop_regen)) {
                            val result = HeartopiaReadiness.regenInterop(context.applicationContext)
                            result.message
                        }
                    }
                )
            ProfilesCard(
                    snapshot = profiles,
                    name = profileName,
                    busy = busy != null,
                    onNameChange = { profileName = it },
                    onCreate = {
                        val requested = profileName
                        runIo(context.getString(R.string.profiles_create)) {
                            val message = HeartopiaProfiles.create(context.applicationContext, requested)
                            withContext(Dispatchers.Main) { profileName = "" }
                            message
                        }
                    },
                    onSwitch = { name ->
                        runIo(context.getString(R.string.profiles_switch)) {
                            HeartopiaProfiles.switch(context.applicationContext, name)
                        }
                    },
                    onDelete = { pendingDelete = it }
                )
        }
    }

    pendingDelete?.let { name ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.profiles_delete_title)) },
            text = { Text(stringResource(R.string.profiles_delete_message, name)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    runIo(context.getString(R.string.profiles_delete)) {
                        HeartopiaProfiles.delete(context.applicationContext, name)
                    }
                }) {
                    Text(stringResource(R.string.profiles_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.confirm_cancel))
                }
            }
        )
    }
}

@Composable
private fun StatusCard(
    game: GameDetector.DetectedGame?,
    isScanning: Boolean,
    isExtracting: Boolean,
    extractionStatus: String,
    activeModpackName: String?,
    isSwitchingModpack: Boolean,
    onLaunch: () -> Unit,
    onRescan: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = game?.label ?: stringResource(R.string.play_subtitle),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = XdGoogleWebAuth.HEARTOPIA_PACKAGE,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (isScanning && game == null) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.scanning))
            } else if (game == null) {
                Text(stringResource(R.string.play_not_installed), fontWeight = FontWeight.Medium)
                Text(
                    stringResource(R.string.play_not_installed_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(onClick = onRescan) {
                    Text(stringResource(R.string.scan_again))
                }
            } else {
                Text(stringResource(R.string.play_version, game.versionName))
                val unity = game.unityVersion
                if (!unity.isNullOrEmpty()) {
                    Text(
                        unity,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    if (activeModpackName.isNullOrEmpty()) {
                        stringResource(R.string.play_modpack_vanilla)
                    } else {
                        stringResource(R.string.play_modpack, activeModpackName)
                    }
                )
                if (isExtracting) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(extractionStatus.ifEmpty { stringResource(R.string.extracting) })
                }
                if (isSwitchingModpack) {
                    Text(stringResource(R.string.modpack_switching))
                }
                Button(
                    onClick = onLaunch,
                    enabled = !isExtracting && !isSwitchingModpack
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.launch))
                }
            }
        }
    }
}

@Composable
fun PlayModSection(
    version: String?,
    fileName: String?,
    isBusy: Boolean,
    onPickFile: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.play_mod_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResource(R.string.play_mod_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            val status = when {
                !version.isNullOrBlank() -> stringResource(R.string.play_mod_version, version)
                !fileName.isNullOrBlank() -> fileName
                else -> stringResource(R.string.play_mod_none)
            }
            Text(text = status, fontWeight = FontWeight.Medium)
            if (isBusy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Button(onClick = onPickFile, enabled = !isBusy) {
                Text(stringResource(R.string.play_mod_pick))
            }
        }
    }
}

@Composable
private fun ReadinessCard(
    report: HeartopiaReadiness.Report?,
    busy: String?,
    isExtracting: Boolean,
    onExtract: () -> Unit,
    onDownloadUnity: () -> Unit,
    onRegenInterop: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(R.string.play_checklist),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            if (report == null) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
            } else {
                report.items.forEach { item ->
                    ReadinessRow(
                        item = item,
                        enabled = busy == null && !isExtracting,
                        onAction = {
                            when (item.action) {
                                HeartopiaReadiness.Action.EXTRACT -> onExtract()
                                HeartopiaReadiness.Action.DOWNLOAD_UNITY -> onDownloadUnity()
                                HeartopiaReadiness.Action.REGEN_INTEROP -> onRegenInterop()
                                null -> Unit
                            }
                        }
                    )
                }
            }
            if (busy != null) {
                Text(stringResource(R.string.play_working) + " " + busy)
            }
        }
    }
}

@Composable
private fun ReadinessRow(
    item: HeartopiaReadiness.Item,
    enabled: Boolean,
    onAction: () -> Unit
) {
    val tint = when (item.tone) {
        HeartopiaReadiness.Tone.READY -> MaterialTheme.colorScheme.primary
        HeartopiaReadiness.Tone.MISSING -> MaterialTheme.colorScheme.error
        HeartopiaReadiness.Tone.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val icon = when (item.tone) {
        HeartopiaReadiness.Tone.READY -> Icons.Filled.CheckCircle
        HeartopiaReadiness.Tone.MISSING -> Icons.Filled.Warning
        HeartopiaReadiness.Tone.INFO -> Icons.Filled.Info
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, fontWeight = FontWeight.Medium)
            Text(
                item.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        val action = item.action
        if (action != null) {
            val label = when (action) {
                HeartopiaReadiness.Action.EXTRACT -> stringResource(R.string.ready_extract)
                HeartopiaReadiness.Action.DOWNLOAD_UNITY -> stringResource(R.string.ready_unity_download)
                HeartopiaReadiness.Action.REGEN_INTEROP -> stringResource(R.string.ready_interop_regen)
            }
            FilledTonalButton(onClick = onAction, enabled = enabled) {
                Text(label)
            }
        }
    }
}

@Composable
private fun ProfilesCard(
    snapshot: HeartopiaProfiles.Snapshot?,
    name: String,
    busy: Boolean,
    onNameChange: (String) -> Unit,
    onCreate: () -> Unit,
    onSwitch: (String) -> Unit,
    onDelete: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.profiles_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                stringResource(R.string.profiles_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (snapshot == null) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                return@Column
            }
            if (snapshot.gameRunning) {
                Text(
                    stringResource(R.string.profiles_game_running),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (snapshot.unsavedLive) {
                Text(
                    stringResource(R.string.profiles_unsaved),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (snapshot.names.isEmpty()) {
                Text(stringResource(R.string.profiles_empty))
            }
            snapshot.names.forEach { profile ->
                val active = profile.equals(snapshot.active, ignoreCase = true)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(profile, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (active) {
                            Text(
                                stringResource(R.string.profiles_active),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    if (!active) {
                        TextButton(
                            onClick = { onSwitch(profile) },
                            enabled = !busy && !snapshot.gameRunning
                        ) {
                            Text(stringResource(R.string.profiles_switch))
                        }
                        TextButton(onClick = { onDelete(profile) }, enabled = !busy) {
                            Text(stringResource(R.string.profiles_delete))
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = name,
                onValueChange = onNameChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(stringResource(R.string.profiles_name_hint)) }
            )
            Button(onClick = onCreate, enabled = !busy && name.isNotBlank()) {
                Text(stringResource(R.string.profiles_create))
            }
        }
    }
}
