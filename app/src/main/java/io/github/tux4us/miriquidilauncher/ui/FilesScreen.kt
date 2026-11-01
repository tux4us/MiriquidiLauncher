// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.tux4us.miriquidilauncher.R
import io.github.tux4us.miriquidilauncher.files.FileEntry
import io.github.tux4us.miriquidilauncher.files.FileIntents
import io.github.tux4us.miriquidilauncher.files.FileKind
import io.github.tux4us.miriquidilauncher.files.FilePaths
import io.github.tux4us.miriquidilauncher.files.FileTrash
import io.github.tux4us.miriquidilauncher.files.FileTypes
import io.github.tux4us.miriquidilauncher.files.FilesNotice
import io.github.tux4us.miriquidilauncher.files.FilesNoticeKind
import io.github.tux4us.miriquidilauncher.files.FilesUiState
import io.github.tux4us.miriquidilauncher.files.FilesViewModel
import io.github.tux4us.miriquidilauncher.files.OpKind
import io.github.tux4us.miriquidilauncher.files.SortField
import io.github.tux4us.miriquidilauncher.files.SortOrder
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

private val FilesError = Color(0xFFFFB4B4)

/** Dialoge, die der Bildschirm selbst verwaltet (Texteingabe und Bestätigungen). */
internal sealed interface FilesDialog {
    data object NewFolder : FilesDialog
    data object NewFile : FilesDialog
    data class Rename(val entry: FileEntry) : FilesDialog
    data object Zip : FilesDialog
    data object Delete : FilesDialog
    data class ZipEntryChoice(val entry: FileEntry) : FilesDialog
}

/**
 * Dateimanager: Ordneransicht mit Auswahl, Kopieren, Verschieben, Löschen (mit Papierkorb), Umbenennen, ZIP,
 * Suche, Eigenschaften, Öffnen und Teilen. Braucht den Zugriff auf alle Dateien. [active] ist `true`, solange
 * die Seite sichtbar ist.
 */
@Composable
fun FilesScreen(viewModel: FilesViewModel, active: Boolean, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var dialog by remember { mutableStateOf<FilesDialog?>(null) }

    LaunchedEffect(active) { if (active) viewModel.onActive() }
    // Nach der Rückkehr aus den Systemeinstellungen wird der Zugriff neu geprüft.
    DisposableEffect(lifecycle, active) {
        val observer = LifecycleEventObserver { _, event ->
            if (active && event == Lifecycle.Event.ON_RESUME) viewModel.onActive()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val legacyPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { viewModel.onActive() }
    val grant = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            openAllFilesSettings(context)
        } else {
            legacyPermission.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE))
        }
    }
    val canGoBack = state.properties != null || state.selection.isNotEmpty() || state.searchQuery != null || state.trashOpen ||
        FilePaths.parentWithin(state.path, state.root) != null
    BackHandler(enabled = active && state.access == true && canGoBack) { viewModel.back() }

    ScopePage(title = stringResource(R.string.scope_files), modifier = modifier) {
        Column(Modifier.fillMaxSize()) {
            state.notice?.let { FilesNoticeBanner(it, viewModel::dismissNotice) }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    state.access == null -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = WeatherText)
                    state.access == false -> PermissionCard(grant)
                    state.trashOpen -> TrashView(state, viewModel)
                    else -> Browser(state, viewModel, context, onDialog = { dialog = it })
                }
            }
            Spacer(Modifier.height(ScopeBottomClearance))
        }
    }

    FilesDialogs(state = state, dialog = dialog, viewModel = viewModel, context = context, onDismiss = { dialog = null })
}

// region Berechtigung und Hinweise --------------------------------------------------------------

@Composable
private fun PermissionCard(onGrant: () -> Unit) {
    GlassCard(stringResource(R.string.scope_files)) {
        Text(stringResource(R.string.files_permission_text), color = WeatherTextMuted, style = MaterialTheme.typography.bodyMedium)
        PodcastButton(stringResource(R.string.files_permission_grant), onGrant)
    }
}

@Composable
private fun FilesNoticeBanner(notice: FilesNotice, onDismiss: () -> Unit) {
    val text = noticeText(notice)
    val failed = notice.kind != FilesNoticeKind.DONE && notice.kind != FilesNoticeKind.CANCELLED && notice.kind != FilesNoticeKind.SEARCH_LIMIT
    Row(
        Modifier.fillMaxWidth().padding(bottom = 8.dp).glass(RoundedCornerShape(14.dp)).padding(start = 14.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = if (failed) FilesError else WeatherText, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        PodcastTextButton(stringResource(R.string.files_notice_dismiss), onDismiss)
    }
}

@Composable
private fun noticeText(n: FilesNotice): String = when (n.kind) {
    FilesNoticeKind.DONE -> when (n.op) {
        OpKind.COPY -> if (n.other > 0) stringResource(R.string.files_done_copy_skipped, n.count, n.other) else stringResource(R.string.files_done_copy, n.count)
        OpKind.MOVE -> stringResource(R.string.files_done_move, n.count)
        OpKind.DELETE -> stringResource(R.string.files_done_delete, n.count)
        OpKind.TRASH -> stringResource(R.string.files_done_trash, n.count)
        OpKind.RESTORE -> stringResource(R.string.files_done_restore, n.count)
        OpKind.ZIP -> stringResource(R.string.files_done_zip, n.count)
        OpKind.UNZIP -> if (n.other > 0) stringResource(R.string.files_done_unzip_skipped, n.count, n.other) else stringResource(R.string.files_done_unzip, n.count)
        OpKind.EMPTY_TRASH -> stringResource(R.string.files_done_empty_trash, n.count)
        null -> ""
    }
    FilesNoticeKind.PARTLY_FAILED -> stringResource(R.string.files_notice_partly, n.count, n.other, n.detail)
    FilesNoticeKind.CANCELLED -> stringResource(R.string.files_notice_cancelled)
    FilesNoticeKind.ERROR_LISTING -> stringResource(R.string.files_list_error)
    FilesNoticeKind.NAME_INVALID -> stringResource(R.string.files_notice_name_invalid)
    FilesNoticeKind.NAME_EXISTS -> stringResource(R.string.files_notice_name_exists)
    FilesNoticeKind.RENAME_FAILED -> stringResource(R.string.files_notice_rename_failed)
    FilesNoticeKind.CREATE_FAILED -> stringResource(R.string.files_notice_create_failed)
    FilesNoticeKind.NOT_IN_VOLUME -> stringResource(R.string.files_notice_not_in_volume)
    FilesNoticeKind.NO_APP -> stringResource(R.string.files_notice_no_app)
    FilesNoticeKind.APK_UNSUPPORTED -> stringResource(R.string.files_notice_apk)
    FilesNoticeKind.ZIP_ENCRYPTED -> stringResource(R.string.files_notice_zip_encrypted)
    FilesNoticeKind.ZIP_LIMIT -> stringResource(R.string.files_notice_zip_limit)
    FilesNoticeKind.ZIP_FAILED -> stringResource(R.string.files_notice_zip_failed)
    FilesNoticeKind.SEARCH_LIMIT -> stringResource(R.string.files_notice_search_limit, n.count)
}

// endregion

// region Ordneransicht --------------------------------------------------------------------------

@Composable
private fun Browser(state: FilesUiState, viewModel: FilesViewModel, context: Context, onDialog: (FilesDialog) -> Unit) {
    val locale = remember { Locale.getDefault() }
    val searching = state.searchQuery != null
    val shown = if (searching) state.searchResults else state.entries
    val selecting = state.selection.isNotEmpty()

    val activate: (FileEntry) -> Unit = { entry ->
        when {
            selecting -> viewModel.toggleSelect(entry.path)
            entry.isDirectory -> viewModel.open(entry.path)
            entry.kind == FileKind.APK -> viewModel.report(FilesNoticeKind.APK_UNSUPPORTED)
            FileTypes.isZip(entry.name) -> onDialog(FilesDialog.ZipEntryChoice(entry))
            else -> if (!FileIntents.open(context, entry.file)) viewModel.report(FilesNoticeKind.NO_APP)
        }
    }
    val longPress: (FileEntry) -> Unit = { entry ->
        if (searching) viewModel.showProperties(entry) else viewModel.toggleSelect(entry.path)
    }

    Column(Modifier.fillMaxSize()) {
        LocationBar(state, viewModel)
        Spacer(Modifier.height(4.dp))
        if (selecting) {
            SelectionBar(state, viewModel, context, onDialog)
        } else {
            ActionBar(state, viewModel, onDialog)
        }
        if (searching) SearchField(state, viewModel)
        Spacer(Modifier.height(4.dp))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                state.loading && shown.isEmpty() && !searching -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = WeatherText)
                state.listError && !searching -> ScopeEmptyHint(stringResource(R.string.files_list_error))
                state.searching -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = WeatherText)
                shown.isEmpty() -> ScopeEmptyHint(
                    stringResource(if (searching) R.string.files_search_empty else R.string.files_empty_folder),
                )
                else -> LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(shown, key = { it.path }) { entry ->
                        EntryRow(
                            entry = entry,
                            selected = entry.path in state.selection,
                            selecting = selecting,
                            locale = locale,
                            context = context,
                            onClick = { activate(entry) },
                            onLongClick = { longPress(entry) },
                        )
                    }
                    item { Spacer(Modifier.height(8.dp)) }
                }
            }
        }
    }
}

/** Speicher, häufige Ordner und Pfadleiste. */
@Composable
private fun LocationBar(state: FilesUiState, viewModel: FilesViewModel) {
    val internal = stringResource(R.string.files_volume_internal)
    val removable = stringResource(R.string.files_volume_removable, 1)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            state.volumes.forEachIndexed { index, volume ->
                val label = if (volume.primary) internal else if (state.volumes.count { !it.primary } > 1) stringResource(R.string.files_volume_removable, index) else removable
                SegmentChip(label, selected = state.root == volume.path && state.path == volume.path) { viewModel.selectVolume(volume) }
            }
            state.quickAccess.forEach { path ->
                SegmentChip(File(path).name, selected = state.path == path) { viewModel.open(path) }
            }
        }
        val rootLabel = state.volumes.firstOrNull { it.path == state.root }?.let { if (it.primary) internal else removable } ?: internal
        val crumbs = remember(state.path, state.root, rootLabel) { FilePaths.breadcrumbs(state.path, state.root, rootLabel) }
        val scroll = rememberScrollState()
        LaunchedEffect(state.path) { scroll.scrollTo(scroll.maxValue) }
        Row(Modifier.fillMaxWidth().horizontalScroll(scroll), verticalAlignment = Alignment.CenterVertically) {
            crumbs.forEachIndexed { index, crumb ->
                if (index > 0) Text("›", color = WeatherTextMuted, modifier = Modifier.padding(horizontal = 4.dp))
                Text(
                    crumb.name,
                    color = if (index == crumbs.lastIndex) WeatherText else WeatherTextMuted,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    modifier = Modifier.clickable { viewModel.open(crumb.path) }.padding(vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun ActionBar(state: FilesUiState, viewModel: FilesViewModel, onDialog: (FilesDialog) -> Unit) {
    var sortMenu by remember { mutableStateOf(false) }
    var newMenu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (FilePaths.parentWithin(state.path, state.root) != null) PodcastTextButton(stringResource(R.string.files_up), viewModel::up)
        PodcastTextButton(stringResource(R.string.files_search), viewModel::openSearch)
        Box {
            PodcastTextButton(stringResource(R.string.files_sort)) { sortMenu = true }
            DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                val fields = listOf(
                    SortField.NAME to R.string.files_sort_name,
                    SortField.SIZE to R.string.files_sort_size,
                    SortField.DATE to R.string.files_sort_date,
                    SortField.TYPE to R.string.files_sort_type,
                )
                fields.forEach { (field, label) ->
                    DropdownMenuItem(
                        text = { Text((if (state.sort.field == field) "● " else "○ ") + stringResource(label)) },
                        onClick = {
                            viewModel.setSort(SortOrder(field, state.sort.ascending))
                            sortMenu = false
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text((if (state.sort.ascending) "☑ " else "☐ ") + stringResource(R.string.files_sort_ascending)) },
                    onClick = {
                        viewModel.setSort(state.sort.copy(ascending = !state.sort.ascending))
                        sortMenu = false
                    },
                )
            }
        }
        PodcastTextButton((if (state.showHidden) "☑ " else "☐ ") + stringResource(R.string.files_hidden), viewModel::toggleHidden)
        Box {
            PodcastTextButton(stringResource(R.string.files_new)) { newMenu = true }
            DropdownMenu(expanded = newMenu, onDismissRequest = { newMenu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.files_new_folder)) }, onClick = { newMenu = false; onDialog(FilesDialog.NewFolder) })
                DropdownMenuItem(text = { Text(stringResource(R.string.files_new_file)) }, onClick = { newMenu = false; onDialog(FilesDialog.NewFile) })
            }
        }
        state.clipboard?.let { clip ->
            PodcastButton(stringResource(R.string.files_paste, clip.paths.size), viewModel::paste)
            PodcastTextButton(stringResource(R.string.files_paste_cancel), viewModel::clearClipboard)
        }
        PodcastTextButton(stringResource(R.string.files_trash), viewModel::openTrash)
    }
}

@Composable
private fun SelectionBar(state: FilesUiState, viewModel: FilesViewModel, context: Context, onDialog: (FilesDialog) -> Unit) {
    val single = state.selection.singleOrNull()?.let { path -> state.entries.firstOrNull { it.path == path } }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.files_selected, state.selection.size),
            color = WeatherText,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        PodcastTextButton(stringResource(R.string.files_select_all), viewModel::selectAll)
        PodcastTextButton(stringResource(R.string.files_copy), viewModel::copySelection)
        PodcastTextButton(stringResource(R.string.files_cut), viewModel::cutSelection)
        PodcastTextButton(stringResource(R.string.files_delete)) { onDialog(FilesDialog.Delete) }
        if (single != null) {
            PodcastTextButton(stringResource(R.string.files_rename)) { onDialog(FilesDialog.Rename(single)) }
            PodcastTextButton(stringResource(R.string.files_properties)) { viewModel.showProperties(single) }
        }
        PodcastTextButton(stringResource(R.string.files_zip)) { onDialog(FilesDialog.Zip) }
        PodcastTextButton(stringResource(R.string.files_share)) {
            val files = state.selection.map(::File)
            if (!FileIntents.share(context, files)) viewModel.report(FilesNoticeKind.NO_APP)
        }
        PodcastTextButton(stringResource(R.string.files_cancel), viewModel::clearSelection)
    }
}

@Composable
private fun SearchField(state: FilesUiState, viewModel: FilesViewModel) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = state.searchQuery.orEmpty(),
            onValueChange = viewModel::search,
            singleLine = true,
            label = { Text(stringResource(R.string.files_search_hint)) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { viewModel.search(state.searchQuery.orEmpty()) }),
            colors = filesFieldColors(),
            modifier = Modifier.weight(1f),
        )
        PodcastTextButton(stringResource(R.string.files_search_close), viewModel::closeSearch)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EntryRow(
    entry: FileEntry,
    selected: Boolean,
    selecting: Boolean,
    locale: Locale,
    context: Context,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val meta = buildList {
        if (!entry.isDirectory) add(Formatter.formatFileSize(context, entry.sizeBytes))
        formatDateTime(entry.modifiedMillis, locale).takeIf { it.isNotEmpty() }?.let { add(it) }
    }.joinToString(" · ")
    Row(
        Modifier.fillMaxWidth().glass(RoundedCornerShape(16.dp)).combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (selecting) {
            Checkbox(
                checked = selected,
                onCheckedChange = null,
                colors = CheckboxDefaults.colors(checkedColor = WeatherText, uncheckedColor = WeatherTextMuted, checkmarkColor = Color.Black),
            )
        }
        Icon(painterResource(iconFor(entry.kind)), contentDescription = null, tint = WeatherText, modifier = Modifier.size(28.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.name, color = WeatherText, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (meta.isNotEmpty()) Text(meta, color = WeatherTextMuted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun iconFor(kind: FileKind): Int = when (kind) {
    FileKind.FOLDER -> R.drawable.ic_folder
    FileKind.IMAGE -> R.drawable.ic_image
    FileKind.VIDEO -> R.drawable.ic_video
    FileKind.AUDIO -> R.drawable.ic_audio
    else -> R.drawable.ic_file
}

internal fun openAllFilesSettings(context: Context) {
    try {
        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
        context.startActivity(intent)
    } catch (_: Exception) {
        try {
            val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
            context.startActivity(intent)
        } catch (_: Exception) {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
            context.startActivity(intent)
        }
    }
}

// endregion
