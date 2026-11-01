// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.tux4us.miriquidilauncher.R
import io.github.tux4us.miriquidilauncher.backup.BackupManager
import io.github.tux4us.miriquidilauncher.calendar.AgendaViewModel

/** Dateiauswahl für den Import, die im Ordner Download/MiriquidiLauncher startet. */
private class OpenBackupDocument : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, input).apply {
            putExtra(DocumentsContract.EXTRA_INITIAL_URI, BackupManager.IMPORT_INITIAL_URI)
        }
}

/**
 * Einstellungsseite links neben dem Launcher: Reihenfolge der Schnellmenü-Favoriten, Backup und Sprache.
 * [onRestored] wird nach einem erfolgreichen Import aufgerufen.
 */
@Composable
fun SettingsScreen(
    launcherViewModel: LauncherViewModel,
    settingsViewModel: SettingsViewModel,
    agendaViewModel: AgendaViewModel,
    onRestored: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val apps by launcherViewModel.favorites.collectAsStateWithLifecycle()
    val contacts by launcherViewModel.favoriteContacts.collectAsStateWithLifecycle()
    val state by settingsViewModel.state.collectAsStateWithLifecycle()
    val agenda by agendaViewModel.state.collectAsStateWithLifecycle()
    val hiddenScopes by settingsViewModel.hiddenScopes.collectAsStateWithLifecycle()
    val scopeOrder by settingsViewModel.scopeOrder.collectAsStateWithLifecycle()
    var appsExpanded by rememberSaveable { mutableStateOf(false) }
    var contactsExpanded by rememberSaveable { mutableStateOf(false) }
    var pendingImport by remember { mutableStateOf<Uri?>(null) }
    var storageDenied by remember { mutableStateOf(false) }
    var showQrExport by remember { mutableStateOf(false) }
    var showQrImport by remember { mutableStateOf(false) }

    val importLauncher = rememberLauncherForActivityResult(OpenBackupDocument()) { uri ->
        if (uri != null) pendingImport = uri
    }
    val storageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) settingsViewModel.export() else storageDenied = true
    }
    val calendarLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        agendaViewModel.refresh()
    }
    val startExport = {
        storageDenied = false
        val needsPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        if (needsPermission) storageLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE) else settingsViewModel.export()
    }

    if (showQrExport) {
        BackupQrShowDialog(settingsViewModel, includeContacts = state.includeContacts, onDismiss = { showQrExport = false })
    }
    if (showQrImport) {
        BackupQrScanDialog(settingsViewModel, onRestored = onRestored, onDismiss = { showQrImport = false })
    }

    pendingImport?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            containerColor = Color(0xFF141A22),
            titleContentColor = WeatherText,
            textContentColor = WeatherText,
            title = { Text(stringResource(R.string.settings_backup_import_confirm_title)) },
            text = { Text(stringResource(R.string.settings_backup_import_confirm_text)) },
            confirmButton = {
                TextButton(
                    onClick = { pendingImport = null; settingsViewModel.import(uri, onRestored) },
                    colors = ButtonDefaults.textButtonColors(contentColor = WeatherText),
                ) { Text(stringResource(R.string.settings_backup_import_confirm_action)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingImport = null }, colors = ButtonDefaults.textButtonColors(contentColor = WeatherText)) {
                    Text(stringResource(R.string.weather_cancel))
                }
            },
        )
    }

    CompositionLocalProvider(LocalContentColor provides WeatherText) {
        LazyColumn(
            modifier = modifier.fillMaxSize().background(Color(0x66000000)),
            contentPadding = WindowInsets.safeDrawing.asPaddingValues(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    stringResource(R.string.settings_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            item {
                SectionCard(stringResource(R.string.settings_section_order)) {
                    Text(stringResource(R.string.settings_order_hint), style = MaterialTheme.typography.bodySmall, color = WeatherTextMuted)
                    CollapsibleHeader(
                        title = stringResource(R.string.settings_order_apps),
                        count = apps.size,
                        expanded = appsExpanded,
                        onToggle = { appsExpanded = !appsExpanded },
                    )
                    if (appsExpanded) {
                        if (apps.isEmpty()) {
                            Text(stringResource(R.string.settings_order_empty_apps), color = WeatherTextMuted)
                        }
                        apps.forEachIndexed { index, app ->
                            OrderRow(
                                label = app.label,
                                canMoveUp = index > 0,
                                canMoveDown = index < apps.lastIndex,
                                onUp = { launcherViewModel.moveFavoriteApp(app, -1) },
                                onDown = { launcherViewModel.moveFavoriteApp(app, 1) },
                            )
                        }
                    }
                    CollapsibleHeader(
                        title = stringResource(R.string.settings_order_contacts),
                        count = contacts.size,
                        expanded = contactsExpanded,
                        onToggle = { contactsExpanded = !contactsExpanded },
                    )
                    if (contactsExpanded) {
                        if (contacts.isEmpty()) {
                            Text(stringResource(R.string.settings_order_empty_contacts), color = WeatherTextMuted)
                        }
                        contacts.forEachIndexed { index, contact ->
                            OrderRow(
                                label = contact.name.ifBlank { contact.number },
                                canMoveUp = index > 0,
                                canMoveDown = index < contacts.lastIndex,
                                onUp = { launcherViewModel.moveFavoriteContact(contact, -1) },
                                onDown = { launcherViewModel.moveFavoriteContact(contact, 1) },
                            )
                        }
                    }
                }
            }
            item {
                SectionCard(stringResource(R.string.settings_section_home)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(role = Role.Checkbox) {
                                val enable = !agenda.enabled
                                agendaViewModel.setEnabled(enable)
                                if (enable && !agenda.permissionGranted) {
                                    calendarLauncher.launch(Manifest.permission.READ_CALENDAR)
                                }
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            // Der Klick der Zeile übernimmt die Bedienung (größere Trefferfläche, ein Fokusziel).
                            checked = agenda.enabled,
                            onCheckedChange = null,
                            colors = CheckboxDefaults.colors(
                                checkedColor = WeatherText,
                                checkmarkColor = Color(0xFF141A22),
                                uncheckedColor = WeatherText,
                            ),
                        )
                        Column(Modifier.weight(1f).padding(start = 8.dp)) {
                            Text(stringResource(R.string.settings_agenda_title))
                            Text(
                                stringResource(R.string.settings_agenda_text),
                                style = MaterialTheme.typography.bodySmall,
                                color = WeatherTextMuted,
                            )
                        }
                    }
                    if (agenda.enabled && !agenda.permissionGranted) {
                        Text(stringResource(R.string.settings_agenda_permission_missing), color = Color(0xFFFF9E9E))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlineAction(
                                stringResource(R.string.settings_agenda_permission_action), enabled = true,
                                onClick = { calendarLauncher.launch(Manifest.permission.READ_CALENDAR) },
                            )
                            OutlineAction(
                                stringResource(R.string.settings_agenda_permission_settings), enabled = true,
                                onClick = { openAppDetails(context) },
                            )
                        }
                    }
                }
            }
            item {
                SectionCard(stringResource(R.string.settings_section_scopes)) {
                    Text(
                        stringResource(R.string.settings_scopes_text),
                        style = MaterialTheme.typography.bodySmall,
                        color = WeatherTextMuted,
                    )
                    val ordered = scopeOrder.mapNotNull { name -> Scope.entries.firstOrNull { it.name == name } }
                    ordered.forEachIndexed { index, target ->
                        val shown = target.name !in hiddenScopes
                        val label = stringResource(target.label)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = target.canHide, role = Role.Checkbox) { settingsViewModel.setScopeVisible(target, !shown) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = shown,
                                onCheckedChange = null,
                                enabled = target.canHide,
                                colors = CheckboxDefaults.colors(
                                    checkedColor = WeatherText,
                                    checkmarkColor = Color(0xFF141A22),
                                    uncheckedColor = WeatherText,
                                    disabledCheckedColor = WeatherTextMuted,
                                    disabledUncheckedColor = WeatherTextMuted,
                                ),
                            )
                            Icon(
                                painter = painterResource(target.icon),
                                contentDescription = null,
                                modifier = Modifier.padding(start = 8.dp).size(20.dp),
                            )
                            Text(label, modifier = Modifier.padding(start = 8.dp).weight(1f))
                            IconButton(onClick = { settingsViewModel.moveScope(target, -1) }, enabled = index > 0) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_arrow_up),
                                    contentDescription = stringResource(R.string.settings_move_up, label),
                                    tint = if (index > 0) WeatherText else WeatherTextMuted,
                                )
                            }
                            IconButton(onClick = { settingsViewModel.moveScope(target, 1) }, enabled = index < ordered.lastIndex) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_arrow_down),
                                    contentDescription = stringResource(R.string.settings_move_down, label),
                                    tint = if (index < ordered.lastIndex) WeatherText else WeatherTextMuted,
                                )
                            }
                        }
                    }
                    OutlineAction(
                        stringResource(R.string.settings_scopes_reset_order), enabled = scopeOrder != ScopeNames,
                        onClick = settingsViewModel::resetScopeOrder,
                    )
                }
            }
            item {
                SectionCard(stringResource(R.string.settings_section_backup)) {
                    Text(stringResource(R.string.settings_backup_hint), style = MaterialTheme.typography.bodySmall, color = WeatherTextMuted)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.settings_backup_include_contacts))
                            if (state.includeContacts) {
                                Text(
                                    stringResource(R.string.settings_backup_contacts_warning),
                                    style = MaterialTheme.typography.bodySmall, color = WeatherTextMuted,
                                )
                            }
                        }
                        Switch(
                            checked = state.includeContacts,
                            onCheckedChange = settingsViewModel::setIncludeContacts,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color(0xFF141A22),
                                checkedTrackColor = WeatherText,
                                uncheckedThumbColor = WeatherText,
                                uncheckedTrackColor = Color(0x33FFFFFF),
                                uncheckedBorderColor = Color(0x99FFFFFF),
                            ),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlineAction(stringResource(R.string.settings_backup_export), enabled = !state.busy, onClick = startExport)
                        OutlineAction(
                            stringResource(R.string.settings_backup_import), enabled = !state.busy,
                            onClick = { importLauncher.launch(arrayOf("*/*")) },
                        )
                    }
                    Text(stringResource(R.string.settings_backup_import_note), style = MaterialTheme.typography.bodySmall, color = WeatherTextMuted)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlineAction(stringResource(R.string.settings_backup_qr_show), enabled = !state.busy, onClick = { showQrExport = true })
                        OutlineAction(stringResource(R.string.settings_backup_qr_scan), enabled = !state.busy, onClick = { showQrImport = true })
                    }
                    Text(stringResource(R.string.settings_backup_qr_note), style = MaterialTheme.typography.bodySmall, color = WeatherTextMuted)
                    if (storageDenied) Text(stringResource(R.string.settings_storage_denied), color = Color(0xFFFF9E9E))
                    state.message?.let { message ->
                        val isError = message is BackupMessage.ExportFailed || message is BackupMessage.InvalidFile ||
                            message is BackupMessage.ReadFailed
                        Text(backupMessageText(message), color = if (isError) Color(0xFFFF9E9E) else WeatherText)
                    }
                }
            }
            item {
                SectionCard(stringResource(R.string.settings_section_language)) {
                    Text(stringResource(R.string.settings_language_text))
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        OutlineAction(stringResource(R.string.settings_language_button), enabled = true, onClick = { openAppLanguageSettings(context) })
                    }
                }
            }
            item {
                SectionCard(stringResource(R.string.settings_section_about)) {
                    LicensesSection()
                }
            }
            item { Spacer(Modifier.size(8.dp)) }
        }
    }
}

@Composable
private fun backupMessageText(message: BackupMessage): String = when (message) {
    is BackupMessage.Exported -> stringResource(R.string.settings_backup_exported, message.path)
    BackupMessage.ExportFailed -> stringResource(R.string.settings_backup_export_failed)
    is BackupMessage.Imported -> {
        val s = message.summary
        val contacts = s.contacts?.let { stringResource(R.string.settings_backup_imported_contacts, it) }
            ?: stringResource(R.string.settings_backup_imported_no_contacts)
        val base = stringResource(R.string.settings_backup_imported, s.apps, contacts, s.places)
        listOfNotNull(
            s.notes?.let { stringResource(R.string.settings_backup_imported_notes, it) },
            s.podcasts?.let { stringResource(R.string.settings_backup_imported_podcasts, it) },
            s.markets?.let { stringResource(R.string.settings_backup_imported_markets, it) },
        ).fold(base) { text, part -> "$text $part" }
    }
    BackupMessage.InvalidFile -> stringResource(R.string.settings_backup_invalid)
    BackupMessage.ReadFailed -> stringResource(R.string.settings_backup_read_failed)
}

/** Kopfzeile einer einklappbaren Liste: Name mit Anzahl und Pfeil; die ganze Zeile ist antippbar. */
@Composable
private fun CollapsibleHeader(title: String, count: Int, expanded: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onToggle),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.settings_order_count, title, count),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f).padding(vertical = 12.dp),
        )
        Icon(
            painter = painterResource(if (expanded) R.drawable.ic_arrow_up else R.drawable.ic_arrow_down),
            contentDescription = stringResource(if (expanded) R.string.settings_collapse else R.string.settings_expand),
        )
    }
}

@Composable
private fun OrderRow(label: String, canMoveUp: Boolean, canMoveDown: Boolean, onUp: () -> Unit, onDown: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        IconButton(onClick = onUp, enabled = canMoveUp) {
            Icon(painterResource(R.drawable.ic_arrow_up), stringResource(R.string.settings_move_up, label))
        }
        IconButton(onClick = onDown, enabled = canMoveDown) {
            Icon(painterResource(R.drawable.ic_arrow_down), stringResource(R.string.settings_move_down, label))
        }
    }
}

@Composable
internal fun OutlineAction(text: String, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        border = BorderStroke(1.dp, Color(0x99FFFFFF)),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = WeatherText, disabledContentColor = Color(0x66FFFFFF)),
    ) { Text(text) }
}

private fun openAppDetails(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        // Gerät ohne diese Einstellungsseite: nichts zu tun.
    }
}

private fun openAppLanguageSettings(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val intent = Intent(Settings.ACTION_APP_LOCALE_SETTINGS, Uri.fromParts("package", context.packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        // Gerät ohne diese Einstellungsseite: nichts zu tun.
    }
}
