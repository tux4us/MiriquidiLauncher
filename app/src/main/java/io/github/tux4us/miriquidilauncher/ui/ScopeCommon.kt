// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.tux4us.miriquidilauncher.R
import io.github.tux4us.miriquidilauncher.data.AppEntry
import io.github.tux4us.miriquidilauncher.data.GroupNameCheck

/** Abdunklung der Scope-Seiten, damit weiße Schrift auf jedem Hintergrundbild lesbar bleibt. */
internal val ScopePageScrim = Color(0x66000000)

/** Platz unten, damit der Home-Button (56 dp + Rand) keine Inhalte verdeckt. */
internal val ScopeBottomClearance = 88.dp

/**
 * Gemeinsames Gerüst der Scope-Seiten mit Titel; der Inhalt füllt den Rest. Schrift ist weiß wie auf den
 * übrigen Scope-Seiten.
 */
@Composable
internal fun ScopePage(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalContentColor provides WeatherText) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .background(ScopePageScrim)
                .systemBarsPadding()
                .padding(horizontal = 16.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = WeatherText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 16.dp, bottom = 12.dp, start = 4.dp),
            )
            Box(Modifier.weight(1f).fillMaxWidth()) { content() }
        }
    }
}

/** Hinweistext in der Mitte einer leeren Seite. */
@Composable
internal fun ScopeEmptyHint(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = WeatherTextMuted,
        style = MaterialTheme.typography.bodyLarge,
        textAlign = TextAlign.Center,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp),
    )
}

/**
 * Eine App als Zelle (Symbol und Name). Antippen startet die App, langes Drücken öffnet [menu].
 * [menu] erhält `expanded` und die Schließen-Aktion und zeichnet ein `DropdownMenu`.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AppCell(
    entry: AppEntry,
    viewModel: LauncherViewModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    menu: (@Composable (expanded: Boolean, dismiss: () -> Unit) -> Unit)? = null,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box(modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = if (menu != null) ({ menuOpen = true }) else null)
                .padding(horizontal = 4.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AppIcon(entry, viewModel, Modifier.size(52.dp))
            Spacer(Modifier.height(6.dp))
            Text(
                text = entry.label,
                color = WeatherText,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 2,
                minLines = 2,
                textAlign = TextAlign.Center,
                overflow = TextOverflow.Ellipsis,
            )
        }
        menu?.invoke(menuOpen) { menuOpen = false }
    }
}

/** Die üblichen Einträge zum Umsortieren und Entfernen in einem Kontextmenü. */
@Composable
internal fun ReorderMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onEarlier: () -> Unit,
    onLater: () -> Unit,
    onRemove: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.scope_move_earlier)) },
            onClick = {
                onDismiss()
                onEarlier()
            },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.scope_move_later)) },
            onClick = {
                onDismiss()
                onLater()
            },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.scope_remove)) },
            onClick = {
                onDismiss()
                onRemove()
            },
        )
    }
}

/**
 * Dialog mit Suchfeld und Liste aller Apps zum Ankreuzen. [selectedKeys] sind die bereits gewählten Apps;
 * jede Änderung wird sofort über [onToggle] weitergegeben.
 */
@Composable
internal fun AppPickerDialog(
    title: String,
    apps: List<AppEntry>,
    selectedKeys: Set<String>,
    viewModel: LauncherViewModel,
    onToggle: (AppEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    var filter by remember { mutableStateOf("") }
    val shown = remember(apps, filter) {
        val needle = filter.trim()
        if (needle.isEmpty()) apps else apps.filter { it.label.contains(needle, ignoreCase = true) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                OutlinedTextField(
                    value = filter,
                    onValueChange = { filter = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.scope_search_apps)) },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                if (shown.isEmpty()) {
                    Text(stringResource(R.string.scope_no_apps_found), modifier = Modifier.padding(vertical = 12.dp))
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().height(320.dp),
                        contentPadding = PaddingValues(vertical = 4.dp),
                    ) {
                        items(shown, key = { it.key }) { entry ->
                            val checked = entry.key in selectedKeys
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onToggle(entry) }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                AppIcon(entry, viewModel, Modifier.size(36.dp))
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    text = entry.label,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                // Das Ankreuzfeld nimmt keine eigenen Berührungen an; die ganze Zeile schaltet um.
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = null,
                                    colors = CheckboxDefaults.colors(),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.scope_done)) } },
    )
}

/** Eingabedialog für den Namen einer Gruppe. Prüft Länge, Doppelte und Höchstzahl mit `AppGroupCodec`. */
@Composable
internal fun GroupNameDialog(
    title: String,
    initial: String,
    check: (String) -> GroupNameCheck,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    val result = check(text)
    val error = when (result) {
        GroupNameCheck.Taken -> stringResource(R.string.group_name_taken)
        GroupNameCheck.LimitReached -> stringResource(R.string.group_limit_reached)
        // Ein leeres Feld ist kein Fehler, solange noch nichts eingegeben wurde.
        GroupNameCheck.Blank ->
            if (text.isNotEmpty()) stringResource(R.string.group_name_blank) else null
        is GroupNameCheck.Ok -> null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text(stringResource(R.string.group_name_hint)) },
                isError = error != null,
                supportingText = error?.let { message -> { Text(message) } },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                enabled = result is GroupNameCheck.Ok,
                onClick = { (result as? GroupNameCheck.Ok)?.let { onConfirm(it.name) } },
            ) { Text(stringResource(R.string.group_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.scope_cancel)) } },
    )
}
