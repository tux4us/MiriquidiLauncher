// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.tux4us.miriquidilauncher.R
import io.github.tux4us.miriquidilauncher.data.AppEntry
import io.github.tux4us.miriquidilauncher.data.AppGroup
import io.github.tux4us.miriquidilauncher.data.AppGroupCodec

/**
 * Scope "App-Gruppen": eigene Gruppen wie "Messenger" oder "Finanzen" mit beliebigen Apps. Antippen einer
 * Gruppe klappt sie auf (es ist höchstens eine offen), langes Drücken auf den Kopf öffnet das Menü der Gruppe
 * (umbenennen, verschieben, löschen). Langes Drücken auf eine App verschiebt sie oder nimmt sie aus der Gruppe.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppGroupsScreen(
    viewModel: LauncherViewModel,
    apps: List<AppEntry>,
    groups: List<AppGroup>,
    onLaunch: (AppEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expandedId by rememberSaveable { mutableStateOf<String?>(null) }
    var creating by rememberSaveable { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<AppGroup?>(null) }
    var deleting by remember { mutableStateOf<AppGroup?>(null) }
    var pickingFor by remember { mutableStateOf<AppGroup?>(null) }
    var menuFor by remember { mutableStateOf<String?>(null) }
    val byKey = remember(apps) { apps.associateBy { it.key } }

    ScopePage(title = stringResource(R.string.scope_app_groups), modifier = modifier) {
        LazyColumn(
            contentPadding = PaddingValues(bottom = ScopeBottomClearance),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (groups.isEmpty()) {
                item(key = "empty") { ScopeEmptyHint(stringResource(R.string.groups_empty)) }
            }
            items(groups, key = { it.id }) { group ->
                val expanded = expandedId == group.id
                // Gespeicherte Apps, die nicht mehr installiert sind, werden nicht angezeigt.
                val members = group.appKeys.mapNotNull { byKey[it] }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .glass(MaterialTheme.shapes.large),
                ) {
                    Box {
                        val toggleLabel = stringResource(if (expanded) R.string.group_collapse else R.string.group_expand)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = { expandedId = if (expanded) null else group.id },
                                    onLongClick = { menuFor = group.id },
                                )
                                .semantics { contentDescription = "${group.name}, $toggleLabel" }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_folder),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(28.dp),
                            )
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = group.name,
                                    color = WeatherText,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = stringResource(R.string.group_apps_count, members.size),
                                    color = WeatherTextMuted,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            Icon(
                                painter = painterResource(if (expanded) R.drawable.ic_arrow_up else R.drawable.ic_arrow_down),
                                contentDescription = null,
                                tint = WeatherTextMuted,
                            )
                        }
                        GroupMenu(
                            expanded = menuFor == group.id,
                            onDismiss = { menuFor = null },
                            onRename = { renaming = group },
                            onEarlier = { viewModel.moveGroup(group.id, -1) },
                            onLater = { viewModel.moveGroup(group.id, 1) },
                            onDelete = { deleting = group },
                        )
                    }
                    if (expanded) {
                        Column(Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp)) {
                            if (members.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.group_empty),
                                    color = WeatherTextMuted,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                                )
                            }
                            members.chunked(SCOPE_GRID_COLUMNS).forEach { rowApps ->
                                Row(Modifier.fillMaxWidth()) {
                                    rowApps.forEach { entry ->
                                        AppCell(
                                            entry = entry,
                                            viewModel = viewModel,
                                            onClick = { onLaunch(entry) },
                                            modifier = Modifier.weight(1f),
                                            menu = { open, dismiss ->
                                                ReorderMenu(
                                                    expanded = open,
                                                    onDismiss = dismiss,
                                                    onEarlier = { viewModel.moveAppInGroup(group.id, entry, -1) },
                                                    onLater = { viewModel.moveAppInGroup(group.id, entry, 1) },
                                                    onRemove = { viewModel.toggleAppInGroup(group.id, entry) },
                                                )
                                            },
                                        )
                                    }
                                    // Leere Plätze, damit die letzte Zeile dieselbe Zellenbreite behält.
                                    repeat(SCOPE_GRID_COLUMNS - rowApps.size) { Spacer(Modifier.weight(1f)) }
                                }
                            }
                            TextButton(onClick = { pickingFor = group }) {
                                Text(stringResource(R.string.group_add_apps))
                            }
                        }
                    }
                }
            }
            item(key = "create") {
                TextButton(onClick = { creating = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.group_create))
                }
            }
        }
    }

    if (creating) {
        GroupNameDialog(
            title = stringResource(R.string.group_create_title),
            initial = "",
            check = { AppGroupCodec.checkName(groups, it) },
            onConfirm = { name ->
                creating = false
                viewModel.createGroup(name)
            },
            onDismiss = { creating = false },
        )
    }
    renaming?.let { group ->
        GroupNameDialog(
            title = stringResource(R.string.group_rename_title),
            initial = group.name,
            check = { AppGroupCodec.checkName(groups, it, exceptId = group.id) },
            onConfirm = { name ->
                renaming = null
                viewModel.renameGroup(group.id, name)
            },
            onDismiss = { renaming = null },
        )
    }
    deleting?.let { group ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.group_delete_title, group.name)) },
            text = { Text(stringResource(R.string.group_delete_text)) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    if (expandedId == group.id) expandedId = null
                    viewModel.deleteGroup(group.id)
                }) { Text(stringResource(R.string.group_delete)) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.scope_cancel)) } },
        )
    }
    // Die Gruppe wird bei jeder Änderung aus der Liste neu gelesen, damit die Haken aktuell bleiben.
    pickingFor?.let { picked ->
        val current = groups.firstOrNull { it.id == picked.id }
        if (current == null) {
            pickingFor = null
        } else {
            AppPickerDialog(
                title = stringResource(R.string.group_pick_title, current.name),
                apps = apps,
                selectedKeys = current.appKeys.toSet(),
                viewModel = viewModel,
                onToggle = { entry -> viewModel.toggleAppInGroup(current.id, entry) },
                onDismiss = { pickingFor = null },
            )
        }
    }
}

@Composable
private fun GroupMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onEarlier: () -> Unit,
    onLater: () -> Unit,
    onDelete: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.group_rename)) },
            onClick = {
                onDismiss()
                onRename()
            },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.group_move_earlier)) },
            onClick = {
                onDismiss()
                onEarlier()
            },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.group_move_later)) },
            onClick = {
                onDismiss()
                onLater()
            },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.group_delete)) },
            onClick = {
                onDismiss()
                onDelete()
            },
        )
    }
}
