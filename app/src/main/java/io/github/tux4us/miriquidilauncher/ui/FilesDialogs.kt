// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.ui

import android.content.Context
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.tux4us.miriquidilauncher.R
import io.github.tux4us.miriquidilauncher.files.FileIntents
import io.github.tux4us.miriquidilauncher.files.FileKind
import io.github.tux4us.miriquidilauncher.files.FileTrash
import io.github.tux4us.miriquidilauncher.files.FileTypes
import io.github.tux4us.miriquidilauncher.files.FilesNoticeKind
import io.github.tux4us.miriquidilauncher.files.FilesUiState
import io.github.tux4us.miriquidilauncher.files.FilesViewModel
import io.github.tux4us.miriquidilauncher.files.OpKind
import io.github.tux4us.miriquidilauncher.files.PropertiesUi
import io.github.tux4us.miriquidilauncher.files.TrashItem
import java.util.Locale

private val DialogColor = Color(0xFF141A22)


@Composable
private fun DialogButton(text: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, colors = ButtonDefaults.textButtonColors(contentColor = WeatherText)) { Text(text) }
}

/** Alle Dialoge des Dateimanagers: Texteingaben, Bestätigungen, Fortschritt und Eigenschaften. */
@Composable
internal fun FilesDialogs(
    state: FilesUiState,
    dialog: FilesDialog?,
    viewModel: FilesViewModel,
    context: Context,
    onDismiss: () -> Unit,
) {
    when (dialog) {
        null -> Unit
        FilesDialog.NewFolder -> NameDialog(
            title = stringResource(R.string.files_new_folder),
            label = stringResource(R.string.files_name_label),
            initial = "",
            confirm = stringResource(R.string.files_create),
            onConfirm = { viewModel.createFolder(it); onDismiss() },
            onDismiss = onDismiss,
        )
        FilesDialog.NewFile -> NameDialog(
            title = stringResource(R.string.files_new_file),
            label = stringResource(R.string.files_name_label),
            initial = "",
            confirm = stringResource(R.string.files_create),
            onConfirm = { viewModel.createFile(it); onDismiss() },
            onDismiss = onDismiss,
        )
        is FilesDialog.Rename -> NameDialog(
            title = stringResource(R.string.files_rename),
            label = stringResource(R.string.files_name_label),
            initial = dialog.entry.name,
            confirm = stringResource(R.string.files_rename_action),
            onConfirm = { viewModel.rename(dialog.entry.path, it); onDismiss() },
            onDismiss = onDismiss,
        )
        FilesDialog.Zip -> NameDialog(
            title = stringResource(R.string.files_zip),
            label = stringResource(R.string.files_zip_name),
            initial = state.selection.singleOrNull()?.let { java.io.File(it).nameWithoutExtension } ?: "Archiv",
            confirm = stringResource(R.string.files_create),
            onConfirm = { viewModel.zipSelection(it); onDismiss() },
            onDismiss = onDismiss,
        )
        FilesDialog.Delete -> AlertDialog(
            onDismissRequest = onDismiss,
            containerColor = DialogColor,
            titleContentColor = WeatherText,
            textContentColor = WeatherTextMuted,
            title = { Text(stringResource(R.string.files_delete_title, state.selection.size)) },
            text = { Text(stringResource(R.string.files_delete_hint, FileTrash.RETENTION_DAYS)) },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    DialogButton(stringResource(R.string.files_delete_trash)) { viewModel.deleteSelection(permanent = false); onDismiss() }
                    DialogButton(stringResource(R.string.files_delete_permanent)) { viewModel.deleteSelection(permanent = true); onDismiss() }
                    DialogButton(stringResource(R.string.files_cancel), onDismiss)
                }
            },
        )
        is FilesDialog.ZipEntryChoice -> AlertDialog(
            onDismissRequest = onDismiss,
            containerColor = DialogColor,
            titleContentColor = WeatherText,
            title = { Text(dialog.entry.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    DialogButton(stringResource(R.string.files_extract)) { viewModel.unzip(dialog.entry.file); onDismiss() }
                    DialogButton(stringResource(R.string.files_open_other)) {
                        if (!FileIntents.open(context, dialog.entry.file)) viewModel.report(FilesNoticeKind.NO_APP)
                        onDismiss()
                    }
                    DialogButton(stringResource(R.string.files_cancel), onDismiss)
                }
            },
        )
    }

    state.operation?.let { op ->
        AlertDialog(
            onDismissRequest = {},
            containerColor = DialogColor,
            titleContentColor = WeatherText,
            textContentColor = WeatherTextMuted,
            title = { Text(stringResource(opLabel(op.kind))) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val p = op.progress
                    if (p != null && p.totalBytes > 0) {
                        LinearProgressIndicator(
                            progress = { (p.doneBytes.toFloat() / p.totalBytes).coerceIn(0f, 1f) },
                            color = WeatherText,
                            trackColor = Color(0x44FFFFFF),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            Formatter.formatFileSize(context, p.doneBytes) + " / " + Formatter.formatFileSize(context, p.totalBytes),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        LinearProgressIndicator(color = WeatherText, trackColor = Color(0x44FFFFFF), modifier = Modifier.fillMaxWidth())
                    }
                    p?.currentName?.let { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = { DialogButton(stringResource(R.string.files_cancel), viewModel::cancelOperation) },
        )
    }

    state.properties?.let { PropertiesDialog(it, context, viewModel::closeProperties) }
}

private fun opLabel(kind: OpKind): Int = when (kind) {
    OpKind.COPY -> R.string.files_op_copy
    OpKind.MOVE -> R.string.files_op_move
    OpKind.DELETE -> R.string.files_op_delete
    OpKind.TRASH -> R.string.files_op_trash
    OpKind.RESTORE -> R.string.files_op_restore
    OpKind.ZIP -> R.string.files_op_zip
    OpKind.UNZIP -> R.string.files_op_unzip
    OpKind.EMPTY_TRASH -> R.string.files_op_empty_trash
}

@Composable
private fun NameDialog(
    title: String,
    label: String,
    initial: String,
    confirm: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DialogColor,
        titleContentColor = WeatherText,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text(label) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                colors = filesFieldColors(),
            )
        },
        confirmButton = { DialogButton(confirm) { if (text.isNotBlank()) onConfirm(text) } },
        dismissButton = { DialogButton(stringResource(R.string.files_cancel), onDismiss) },
    )
}

@Composable
private fun PropertiesDialog(properties: PropertiesUi, context: Context, onDismiss: () -> Unit) {
    val entry = properties.entry
    val locale = remember { Locale.getDefault() }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DialogColor,
        titleContentColor = WeatherText,
        textContentColor = WeatherText,
        title = { Text(entry.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ValueRow(stringResource(R.string.files_prop_path), entry.path.substringBeforeLast('/', "/"))
                ValueRow(
                    stringResource(R.string.files_prop_type),
                    if (entry.isDirectory) stringResource(R.string.files_kind_folder) else FileTypes.mimeOf(entry.name),
                )
                val size = properties.size
                ValueRow(
                    stringResource(R.string.files_prop_size),
                    when {
                        size != null -> Formatter.formatFileSize(context, size.bytes)
                        properties.loading -> stringResource(R.string.files_prop_calculating)
                        else -> "–"
                    },
                )
                if (entry.kind == FileKind.FOLDER && size != null) {
                    ValueRow(stringResource(R.string.files_prop_contents), stringResource(R.string.files_prop_contents_value, size.files, size.folders))
                }
                ValueRow(stringResource(R.string.files_prop_modified), formatDateTime(entry.modifiedMillis, locale))
            }
        },
        confirmButton = { DialogButton(stringResource(R.string.files_close), onDismiss) },
    )
}

// region Papierkorb -----------------------------------------------------------------------------

@Composable
internal fun TrashView(state: FilesUiState, viewModel: FilesViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val locale = remember { Locale.getDefault() }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            PodcastTextButton(stringResource(R.string.files_trash_back), viewModel::closeTrash)
            if (state.trashItems.isNotEmpty()) PodcastTextButton(stringResource(R.string.files_trash_empty), viewModel::emptyTrash)
        }
        Text(
            stringResource(R.string.files_trash_info, FileTrash.RETENTION_DAYS),
            color = WeatherTextMuted,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
        )
        Spacer(Modifier.height(4.dp))
        if (state.trashItems.isEmpty()) {
            ScopeEmptyHint(stringResource(R.string.files_trash_none))
        } else {
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(state.trashItems, key = { it.item.path }) { item -> TrashRow(item, context, locale, viewModel) }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }
    }
}

@Composable
private fun TrashRow(item: TrashItem, context: Context, locale: Locale, viewModel: FilesViewModel) {
    Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(16.dp)).padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text(item.displayName, color = WeatherText, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        val from = stringResource(R.string.files_trash_from, item.originalPath.substringBeforeLast('/', "/"))
        Text(
            from + " · " + formatDateTime(item.deletedAtMillis, locale),
            color = WeatherTextMuted,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            PodcastTextButton(stringResource(R.string.files_trash_restore)) { viewModel.restore(item) }
            PodcastTextButton(stringResource(R.string.files_trash_delete)) { viewModel.purge(item) }
        }
    }
}

// endregion
