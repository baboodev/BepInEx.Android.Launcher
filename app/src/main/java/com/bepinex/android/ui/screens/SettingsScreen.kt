package com.bepinex.android.ui.screens

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Animation
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ColorLens
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PictureInPictureAlt
import androidx.compose.material.icons.outlined.ScreenRotation
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.bepinex.android.R
import com.bepinex.android.auth.XdGoogleWebAuth
import com.bepinex.android.settings.AppSettings
import com.bepinex.android.ui.components.BugtopiaTitle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Appearance, in-game, account, and maintenance settings for Heartopia. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    themeMode: AppSettings.ThemeMode,
    language: AppSettings.Language,
    dynamicColor: Boolean,
    animationDisabled: Boolean,
    packageName: String,
    blockUnityKill: Boolean,
    useUnstrippedLibUnity: Boolean,
    onThemeChanged: (AppSettings.ThemeMode) -> Unit,
    onLanguageChanged: (AppSettings.Language) -> Unit,
    onDynamicColorChanged: (Boolean) -> Unit,
    onAnimationDisabledChanged: (Boolean) -> Unit,
    onBlockUnityKillChanged: (Boolean) -> Unit,
    onUseUnstrippedLibUnityChanged: (Boolean) -> Unit,
    onClearBepInEx: () -> Unit,
    onClearDotnet: () -> Unit,
    onClearLibUnity: () -> Unit,
    onCopyGameResources: () -> Unit,
    onGoogleWebLogin: () -> Unit,
    isLanguageIncompleteShown: Boolean,
    onLanguageIncompleteShown: () -> Unit
) {
    var showThemeDialog by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var showAnimationRestartDialog by remember { mutableStateOf(false) }
    var pendingAnimationValue by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var floatingLogEnabled by remember {
        mutableStateOf(AppSettings.isFloatingModMenuEnabled(context))
    }
    var pipOnMinimize by remember {
        mutableStateOf(AppSettings.isPipOnMinimizeEnabled(context))
    }
    var rotateToModRemote by remember {
        mutableStateOf(AppSettings.isRotateToModRemoteEnabled(context))
    }
    var confirmAction by remember { mutableStateOf<String?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { BugtopiaTitle(title = stringResource(R.string.settings_title)) },
                windowInsets = WindowInsets.safeDrawing.only(
                    WindowInsetsSides.Horizontal + WindowInsetsSides.Top
                ),
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)
        ) {
            item { SettingsSectionHeader(stringResource(R.string.settings_section_appearance)) }
            item {
                SettingListItem(
                    title = stringResource(R.string.settings_theme),
                    summary = themeLabel(themeMode),
                    icon = { Icon(Icons.Outlined.Palette, contentDescription = null) },
                    onClick = { showThemeDialog = true }
                )
            }
            item {
                SettingListItem(
                    title = stringResource(R.string.settings_language),
                    summary = languageLabel(language),
                    icon = { Icon(Icons.Outlined.Language, contentDescription = null) },
                    onClick = { showLanguageDialog = true }
                )
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                item {
                    SettingListItem(
                        title = stringResource(R.string.settings_dynamic_color),
                        summary = stringResource(R.string.settings_dynamic_color_desc),
                        icon = { Icon(Icons.Outlined.ColorLens, contentDescription = null) },
                        trailing = {
                            Switch(
                                checked = dynamicColor,
                                onCheckedChange = { checked -> onDynamicColorChanged(checked) }
                            )
                        },
                        onClick = { onDynamicColorChanged(!dynamicColor) }
                    )
                }
            }
            item {
                SettingListItem(
                    title = stringResource(R.string.settings_animation_disabled),
                    summary = stringResource(R.string.settings_animation_disabled_desc),
                    icon = { Icon(Icons.Outlined.Animation, contentDescription = null) },
                    trailing = {
                        Switch(
                            checked = animationDisabled,
                            onCheckedChange = { checked ->
                                pendingAnimationValue = checked
                                showAnimationRestartDialog = true
                            }
                        )
                    },
                    onClick = {
                        pendingAnimationValue = !animationDisabled
                        showAnimationRestartDialog = true
                    }
                )
            }

            item { SettingsSectionHeader(stringResource(R.string.settings_section_ingame)) }
            item {
                SettingListItem(
                    title = stringResource(R.string.settings_block_unity_kill),
                    summary = stringResource(R.string.settings_block_unity_kill_desc),
                    icon = { Icon(Icons.Outlined.Shield, contentDescription = null) },
                    trailing = {
                        Switch(
                            checked = blockUnityKill,
                            onCheckedChange = onBlockUnityKillChanged
                        )
                    },
                    onClick = { onBlockUnityKillChanged(!blockUnityKill) }
                )
            }
            item {
                SettingListItem(
                    title = stringResource(R.string.settings_unstripped_libunity),
                    summary = stringResource(R.string.settings_unstripped_libunity_desc),
                    icon = { Icon(Icons.Outlined.Code, contentDescription = null) },
                    trailing = {
                        Switch(
                            checked = useUnstrippedLibUnity,
                            onCheckedChange = onUseUnstrippedLibUnityChanged
                        )
                    },
                    onClick = { onUseUnstrippedLibUnityChanged(!useUnstrippedLibUnity) }
                )
            }
            item {
                SettingListItem(
                    title = stringResource(R.string.settings_floating_log),
                    summary = stringResource(R.string.settings_floating_log_desc),
                    icon = { Icon(Icons.Outlined.Terminal, contentDescription = null) },
                    trailing = {
                        Switch(
                            checked = floatingLogEnabled,
                            onCheckedChange = { checked ->
                                floatingLogEnabled = checked
                                AppSettings.setFloatingModMenuEnabled(context, checked)
                            }
                        )
                    },
                    onClick = {
                        val next = !floatingLogEnabled
                        floatingLogEnabled = next
                        AppSettings.setFloatingModMenuEnabled(context, next)
                    }
                )
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                item {
                    SettingListItem(
                        title = stringResource(R.string.settings_pip_on_minimize),
                        summary = stringResource(R.string.settings_pip_on_minimize_desc),
                        icon = { Icon(Icons.Outlined.PictureInPictureAlt, contentDescription = null) },
                        trailing = {
                            Switch(
                                checked = pipOnMinimize,
                                onCheckedChange = { checked ->
                                    pipOnMinimize = checked
                                    AppSettings.setPipOnMinimizeEnabled(context, checked)
                                }
                            )
                        },
                        onClick = {
                            val next = !pipOnMinimize
                            pipOnMinimize = next
                            AppSettings.setPipOnMinimizeEnabled(context, next)
                        }
                    )
                }
                item {
                    SettingListItem(
                        title = stringResource(R.string.settings_rotate_to_mod_remote),
                        summary = stringResource(R.string.settings_rotate_to_mod_remote_desc),
                        icon = { Icon(Icons.Outlined.ScreenRotation, contentDescription = null) },
                        trailing = {
                            Switch(
                                checked = rotateToModRemote,
                                onCheckedChange = { checked ->
                                    rotateToModRemote = checked
                                    AppSettings.setRotateToModRemoteEnabled(context, checked)
                                }
                            )
                        },
                        onClick = {
                            val next = !rotateToModRemote
                            rotateToModRemote = next
                            AppSettings.setRotateToModRemoteEnabled(context, next)
                        }
                    )
                }
            }

            if (XdGoogleWebAuth.isHeartopia(packageName)) {
                item { SettingsSectionHeader(stringResource(R.string.settings_section_account)) }
                item {
                    SettingListItem(
                        title = stringResource(R.string.settings_google_web_login),
                        summary = stringResource(R.string.settings_google_web_login_desc),
                        icon = { Icon(Icons.AutoMirrored.Outlined.Login, contentDescription = null) },
                        onClick = onGoogleWebLogin
                    )
                }
            }

            item { SettingsSectionHeader(stringResource(R.string.settings_section_maintenance)) }
            item {
                SettingListItem(
                    title = stringResource(R.string.settings_clear_bepinex),
                    summary = stringResource(R.string.settings_clear_bepinex_desc),
                    icon = { Icon(Icons.Outlined.DeleteForever, contentDescription = null) },
                    onClick = { confirmAction = "bepinex" }
                )
            }
            item {
                SettingListItem(
                    title = stringResource(R.string.settings_clear_dotnet),
                    summary = stringResource(R.string.settings_clear_dotnet_desc),
                    icon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
                    onClick = { confirmAction = "dotnet" }
                )
            }
            item {
                SettingListItem(
                    title = stringResource(R.string.settings_clear_libunity),
                    summary = stringResource(R.string.settings_clear_libunity_desc),
                    icon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
                    onClick = { confirmAction = "libunity" }
                )
            }
            item {
                SettingListItem(
                    title = stringResource(R.string.settings_copy_resources),
                    summary = stringResource(R.string.settings_copy_resources_desc),
                    icon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                    onClick = { confirmAction = "copy" }
                )
            }
        }
    }

    if (showThemeDialog) {
        SelectionDialog(
            title = stringResource(R.string.settings_theme),
            selected = themeMode,
            options = listOf(
                AppSettings.ThemeMode.DARK to stringResource(R.string.theme_dark),
                AppSettings.ThemeMode.LIGHT to stringResource(R.string.theme_light),
                AppSettings.ThemeMode.SYSTEM to stringResource(R.string.theme_system)
            ),
            onDismiss = { showThemeDialog = false },
            onSelected = { onThemeChanged(it); showThemeDialog = false }
        )
    }

    if (showLanguageDialog) {
        SelectionDialog(
            title = stringResource(R.string.settings_language),
            selected = language,
            options = listOf(
                AppSettings.Language.SYSTEM to stringResource(R.string.lang_system),
                AppSettings.Language.ENGLISH to stringResource(R.string.lang_english),
                AppSettings.Language.CHINESE to stringResource(R.string.lang_chinese),
                AppSettings.Language.CHINESE_TW to stringResource(R.string.lang_chinese_tw)
            ),
            onDismiss = { showLanguageDialog = false },
            onSelected = {
                onLanguageChanged(it)
                showLanguageDialog = false
            }
        )
    }

    if (showAnimationRestartDialog) {
        AlertDialog(
            onDismissRequest = { showAnimationRestartDialog = false },
            title = { Text(stringResource(R.string.animation_restart_title)) },
            text = { Text(stringResource(R.string.animation_restart_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showAnimationRestartDialog = false
                    onAnimationDisabledChanged(pendingAnimationValue)
                    scope.launch {
                        delay(300)
                        android.os.Process.killProcess(android.os.Process.myPid())
                    }
                }) {
                    Text(stringResource(R.string.animation_restart_now))
                }
            },
            dismissButton = {
                TextButton(onClick = { showAnimationRestartDialog = false }) {
                    Text(stringResource(R.string.confirm_cancel))
                }
            }
        )
    }

    confirmAction?.let { action ->
        val (title, message, onConfirm) = when (action) {
            "bepinex" -> Triple(
                stringResource(R.string.confirm_clear_bepinex_title),
                stringResource(R.string.confirm_clear_bepinex_msg),
                { onClearBepInEx(); confirmAction = null }
            )
            "dotnet" -> Triple(
                stringResource(R.string.confirm_clear_dotnet_title),
                stringResource(R.string.confirm_clear_dotnet_msg),
                { onClearDotnet(); confirmAction = null }
            )
            "copy" -> Triple(
                stringResource(R.string.confirm_copy_resources_title),
                stringResource(R.string.confirm_copy_resources_msg),
                { onCopyGameResources(); confirmAction = null }
            )
            "libunity" -> Triple(
                stringResource(R.string.confirm_clear_libunity_title),
                stringResource(R.string.confirm_clear_libunity_msg),
                { onClearLibUnity(); confirmAction = null }
            )
            else -> Triple("", "", { confirmAction = null })
        }
        AlertDialog(
            onDismissRequest = { confirmAction = null },
            title = { Text(title) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = onConfirm) {
                    Text(stringResource(R.string.confirm_yes), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmAction = null }) {
                    Text(stringResource(R.string.confirm_no))
                }
            }
        )
    }
}

@Composable
private fun themeLabel(mode: AppSettings.ThemeMode) = when (mode) {
    AppSettings.ThemeMode.DARK -> stringResource(R.string.theme_dark)
    AppSettings.ThemeMode.LIGHT -> stringResource(R.string.theme_light)
    AppSettings.ThemeMode.SYSTEM -> stringResource(R.string.theme_system)
}

@Composable
private fun languageLabel(language: AppSettings.Language) = when (language) {
    AppSettings.Language.SYSTEM -> stringResource(R.string.lang_system)
    AppSettings.Language.ENGLISH -> stringResource(R.string.lang_english)
    AppSettings.Language.CHINESE -> stringResource(R.string.lang_chinese)
    AppSettings.Language.CHINESE_TW -> stringResource(R.string.lang_chinese_tw)
}

@Composable
private fun SettingsSectionHeader(text: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp)) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    }
}

@Composable
private fun SettingListItem(
    title: String,
    summary: String? = null,
    icon: @Composable () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = summary?.let { { Text(it) } },

        leadingContent = icon,

        trailingContent = trailing,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    )
}

@Composable
private fun <T> SelectionDialog(
    title: String,
    selected: T,
    options: List<Pair<T, String>>,
    onDismiss: () -> Unit,
    onSelected: (T) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                options.forEach { (value, label) ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { onSelected(value) }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = value == selected, onClick = { onSelected(value) })
                        Spacer(Modifier.width(8.dp))
                        Text(label)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.confirm_cancel)) }
        }
    )
}
