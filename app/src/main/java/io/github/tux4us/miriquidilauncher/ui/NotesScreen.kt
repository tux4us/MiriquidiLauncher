// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.tux4us.miriquidilauncher.R
import io.github.tux4us.miriquidilauncher.files.FileTrash
import io.github.tux4us.miriquidilauncher.files.TrashItem
import io.github.tux4us.miriquidilauncher.notes.EditorUi
import io.github.tux4us.miriquidilauncher.notes.MdBlock
import io.github.tux4us.miriquidilauncher.notes.MdSpan
import io.github.tux4us.miriquidilauncher.notes.Note
import io.github.tux4us.miriquidilauncher.notes.NoteSort
import io.github.tux4us.miriquidilauncher.notes.NotesLogic
import io.github.tux4us.miriquidilauncher.notes.NotesMarkdown
import io.github.tux4us.miriquidilauncher.notes.NotesNotice
import io.github.tux4us.miriquidilauncher.notes.NotesNoticeKind
import io.github.tux4us.miriquidilauncher.notes.NotesTab
import io.github.tux4us.miriquidilauncher.notes.NotesUiState
import io.github.tux4us.miriquidilauncher.notes.NotesViewModel
import java.util.Locale

private val NotesError = Color(0xFFFFB4B4)
private val NotesDialogColor = Color(0xFF141A22)
private val NotesLink = Color(0xFF9CC8FF)

private sealed interface NotesDialog {
    data object Login : NotesDialog
    data object Account : NotesDialog
    data object Category : NotesDialog
    data object Purge : NotesDialog
}

/**
 * Notizen: Markdown-Dateien in Documents/MiriquidiNotes (Unterordner = Kategorien) mit Suche, Favoriten, Vorschau,
 * Papierkorb und Abgleich mit Nextcloud Notes. Braucht den Zugriff auf alle Dateien. [active] ist `true`, solange
 * die Seite sichtbar ist.
 */
@Composable
fun NotesScreen(viewModel: NotesViewModel, active: Boolean, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var dialog by remember { mutableStateOf<NotesDialog?>(null) }

    LaunchedEffect(active) { if (active) viewModel.onActive() }
    DisposableEffect(lifecycle, active) {
        val observer = LifecycleEventObserver { _, event ->
            if (active && event == Lifecycle.Event.ON_RESUME) viewModel.onActive()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val grant = { viewModel.onActive() }
    val canGoBack = state.editor != null || state.selection.isNotEmpty() || state.trashSelection.isNotEmpty() ||
        state.query != null || state.category != null || state.tab != NotesTab.ALL
    BackHandler(enabled = active && state.access == true && canGoBack) { viewModel.back() }

    ScopePage(title = stringResource(R.string.scope_notes), modifier = modifier) {
        Column(Modifier.fillMaxSize()) {
            state.notice?.let { NoticeBanner(it, viewModel::dismissNotice) }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    state.access == null -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = WeatherText)
                    state.access == false -> GlassCard(stringResource(R.string.scope_notes)) {
                        Text(stringResource(R.string.notes_permission_text), color = WeatherTextMuted, style = MaterialTheme.typography.bodyMedium)
                        PodcastButton(stringResource(R.string.notes_permission_grant), grant)
                    }
                    state.editor != null -> NoteEditor(state, state.editor!!, viewModel, context) { dialog = it }
                    else -> NotesList(state, viewModel) { dialog = it }
                }
            }
            Spacer(Modifier.height(ScopeBottomClearance))
        }
    }

    when (dialog) {
        NotesDialog.Login -> LoginDialog(viewModel) { dialog = null }
        NotesDialog.Account -> AccountDialog(state, viewModel) { dialog = it }
        NotesDialog.Category -> CategoryDialog(state, viewModel) { dialog = null }
        NotesDialog.Purge -> {
            val count = if (state.trashSelection.isEmpty()) state.trash.size else state.trashSelection.size
            AlertDialog(
                onDismissRequest = { dialog = null },
                containerColor = NotesDialogColor,
                titleContentColor = WeatherText,
                textContentColor = WeatherTextMuted,
                title = { Text(stringResource(R.string.notes_trash_purge)) },
                text = { Text(stringResource(R.string.notes_trash_purge_confirm, count)) },
                confirmButton = { DialogTextButton(stringResource(R.string.notes_trash_purge)) { viewModel.purgeTrash(); dialog = null } },
                dismissButton = { DialogTextButton(stringResource(R.string.notes_cancel)) { dialog = null } },
            )
        }
        null -> Unit
    }
}

@Composable
private fun DialogTextButton(text: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, colors = ButtonDefaults.textButtonColors(contentColor = WeatherText)) { Text(text) }
}

// region Hinweise -------------------------------------------------------------------------------

@Composable
private fun NoticeBanner(notice: NotesNotice, onDismiss: () -> Unit) {
    val failed = when (notice.kind) {
        NotesNoticeKind.SYNC_DONE -> (notice.summary?.failed ?: 0) > 0
        NotesNoticeKind.TRASHED, NotesNoticeKind.RESTORED, NotesNoticeKind.PURGED -> false
        else -> true
    }
    Row(
        Modifier.fillMaxWidth().padding(bottom = 8.dp).glass(RoundedCornerShape(14.dp)).padding(start = 14.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(noticeText(notice), color = if (failed) NotesError else WeatherText, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        PodcastTextButton(stringResource(R.string.notes_notice_dismiss), onDismiss)
    }
}

@Composable
private fun noticeText(n: NotesNotice): String = when (n.kind) {
    NotesNoticeKind.SYNC_DONE -> {
        val s = n.summary
        if (s == null) {
            ""
        } else {
            stringResource(R.string.notes_sync_done, s.downloaded, s.uploaded, s.deleted) +
                (if (s.conflicts > 0) " " + stringResource(R.string.notes_sync_conflicts, s.conflicts) else "") +
                (if (s.failed > 0) " " + stringResource(R.string.notes_sync_failed_count, s.failed) else "")
        }
    }
    NotesNoticeKind.SYNC_AUTH -> stringResource(R.string.notes_sync_auth)
    NotesNoticeKind.SYNC_NOT_FOUND -> stringResource(R.string.notes_sync_not_found)
    NotesNoticeKind.SYNC_NETWORK -> stringResource(R.string.notes_sync_network)
    NotesNoticeKind.LOGIN_INVALID_URL -> stringResource(R.string.notes_login_invalid_url)
    NotesNoticeKind.LOGIN_NOT_HTTPS -> stringResource(R.string.notes_login_not_https)
    NotesNoticeKind.LOGIN_AUTH -> stringResource(R.string.notes_login_auth)
    NotesNoticeKind.LOGIN_NOT_FOUND -> stringResource(R.string.notes_login_not_found)
    NotesNoticeKind.LOGIN_NETWORK -> stringResource(R.string.notes_login_network)
    NotesNoticeKind.LOGIN_KEYSTORE -> stringResource(R.string.notes_login_keystore)
    NotesNoticeKind.FEED_ACCOUNT_MISSING -> stringResource(R.string.notes_feed_account_missing)
    NotesNoticeKind.SAVE_FAILED -> stringResource(R.string.notes_save_failed)
    NotesNoticeKind.MOVE_FAILED -> stringResource(R.string.notes_move_failed)
    NotesNoticeKind.TRASHED -> stringResource(R.string.notes_done_trash, n.count)
    NotesNoticeKind.RESTORED -> stringResource(R.string.notes_done_restore, n.count)
    NotesNoticeKind.PURGED -> stringResource(R.string.notes_done_purge, n.count)
    NotesNoticeKind.PARTLY_FAILED -> stringResource(R.string.notes_partly_failed, n.count, n.other)
    NotesNoticeKind.NO_APP -> stringResource(R.string.notes_no_app)
}

// endregion

// region Liste ----------------------------------------------------------------------------------

@Composable
private fun NotesList(state: NotesUiState, viewModel: NotesViewModel, onDialog: (NotesDialog) -> Unit) {
    val selecting = state.selection.isNotEmpty()
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SegmentChip(stringResource(R.string.notes_tab_all), state.tab == NotesTab.ALL) { viewModel.setTab(NotesTab.ALL) }
            SegmentChip(stringResource(R.string.notes_tab_favorites), state.tab == NotesTab.FAVORITES) { viewModel.setTab(NotesTab.FAVORITES) }
            SegmentChip(stringResource(R.string.notes_tab_trash), state.tab == NotesTab.TRASH) { viewModel.setTab(NotesTab.TRASH) }
        }
        Spacer(Modifier.height(6.dp))
        if (state.tab == NotesTab.TRASH) {
            TrashList(state, viewModel, onDialog)
            return@Column
        }
        if (selecting) SelectionBar(state, viewModel) else ActionBar(state, viewModel, onDialog)
        if (state.query != null) SearchField(state, viewModel)
        if (state.categories.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SegmentChip(stringResource(R.string.notes_category_all), state.category == null) { viewModel.setCategory(null) }
                state.categories.forEach { (name, count) ->
                    SegmentChip("$name ($count)", state.category == name) { viewModel.setCategory(name) }
                }
            }
        }
        if (state.syncing) LinearProgressIndicator(color = WeatherText, trackColor = Color(0x44FFFFFF), modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
        Spacer(Modifier.height(4.dp))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                state.loading && state.notes.isEmpty() -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = WeatherText)
                state.visible.isEmpty() -> ScopeEmptyHint(
                    stringResource(
                        when {
                            state.query?.isNotBlank() == true -> R.string.notes_empty_search
                            state.tab == NotesTab.FAVORITES -> R.string.notes_empty_favorites
                            else -> R.string.notes_empty
                        },
                    ),
                )
                else -> {
                    val locale = remember { Locale.getDefault() }
                    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(state.visible, key = { it.path }) { note ->
                            NoteRow(
                                note = note,
                                selected = note.path in state.selection,
                                selecting = selecting,
                                locale = locale,
                                onClick = { if (selecting) viewModel.toggleSelect(note.path) else viewModel.openNote(note) },
                                onLongClick = { viewModel.toggleSelect(note.path) },
                            )
                        }
                        item { Spacer(Modifier.height(8.dp)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionBar(state: NotesUiState, viewModel: NotesViewModel, onDialog: (NotesDialog) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PodcastButton(stringResource(R.string.notes_new), viewModel::newNote)
        // Nextcloud direkt neben "Neu", damit der Eintrag ohne Scrollen sichtbar ist.
        if (state.account != null) {
            PodcastTextButton(stringResource(R.string.notes_sync), viewModel::sync)
            PodcastTextButton(stringResource(R.string.notes_nextcloud)) { onDialog(NotesDialog.Account) }
        } else {
            PodcastTextButton(stringResource(R.string.notes_nextcloud_connect)) { onDialog(NotesDialog.Login) }
        }
        PodcastTextButton(stringResource(R.string.notes_search), viewModel::openSearch)
        val arrow = if (state.ascending) " ↑" else " ↓"
        PodcastTextButton(stringResource(R.string.notes_sort_modified) + if (state.sort == NoteSort.MODIFIED) arrow else "") { viewModel.setSort(NoteSort.MODIFIED) }
        PodcastTextButton(stringResource(R.string.notes_sort_title) + if (state.sort == NoteSort.TITLE) arrow else "") { viewModel.setSort(NoteSort.TITLE) }
    }
}

@Composable
private fun SelectionBar(state: NotesUiState, viewModel: NotesViewModel) {
    val selected = state.notes.filter { it.path in state.selection }
    val allFavorite = selected.isNotEmpty() && selected.all { it.favorite }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.notes_selected, state.selection.size), color = WeatherText, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 8.dp))
        PodcastTextButton(stringResource(if (allFavorite) R.string.notes_unfavorite else R.string.notes_favorite)) {
            viewModel.setFavorites(state.selection.toList(), !allFavorite)
            viewModel.clearSelection()
        }
        PodcastTextButton(stringResource(R.string.notes_trash), viewModel::trashSelected)
        PodcastTextButton(stringResource(R.string.notes_cancel), viewModel::clearSelection)
    }
}

@Composable
private fun SearchField(state: NotesUiState, viewModel: NotesViewModel) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = state.query.orEmpty(),
            onValueChange = viewModel::setQuery,
            singleLine = true,
            label = { Text(stringResource(R.string.notes_search_hint)) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {}),
            colors = filesFieldColors(),
            modifier = Modifier.weight(1f),
        )
        PodcastTextButton(stringResource(R.string.notes_search_close), viewModel::closeSearch)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NoteRow(note: Note, selected: Boolean, selecting: Boolean, locale: Locale, onClick: () -> Unit, onLongClick: () -> Unit) {
    val preview = remember(note.content) { NotesLogic.preview(note.content) }
    Row(
        Modifier.fillMaxWidth().glass(RoundedCornerShape(16.dp)).combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (selecting) {
            Checkbox(checked = selected, onCheckedChange = null, colors = CheckboxDefaults.colors(checkedColor = WeatherText, uncheckedColor = WeatherTextMuted, checkmarkColor = Color.Black))
        }
        Column(Modifier.weight(1f)) {
            Text(
                (if (note.favorite) "★ " else "") + note.title,
                color = WeatherText,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (preview.isNotEmpty()) Text(preview, color = WeatherTextMuted, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val meta = listOfNotNull(note.category.takeIf { it.isNotEmpty() }, formatDateTime(note.modifiedMillis, locale).takeIf { it.isNotEmpty() }).joinToString(" · ")
            if (meta.isNotEmpty()) Text(meta, color = WeatherTextMuted, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// endregion

// region Papierkorb -----------------------------------------------------------------------------

@Composable
private fun TrashList(state: NotesUiState, viewModel: NotesViewModel, onDialog: (NotesDialog) -> Unit) {
    val locale = remember { Locale.getDefault() }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            if (state.trash.isNotEmpty()) {
                PodcastTextButton(stringResource(if (state.trashSelection.isEmpty()) R.string.notes_trash_restore_all else R.string.notes_trash_restore_selected), viewModel::restoreTrash)
                PodcastTextButton(stringResource(R.string.notes_trash_purge)) { onDialog(NotesDialog.Purge) }
            }
        }
        Text(
            stringResource(R.string.notes_trash_info, FileTrash.RETENTION_DAYS),
            color = WeatherTextMuted,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
        )
        if (state.trash.isEmpty()) {
            ScopeEmptyHint(stringResource(R.string.notes_trash_none))
        } else {
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(state.trash, key = { it.item.absolutePath }) { item -> TrashRow(item, item.item.absolutePath in state.trashSelection, locale) { viewModel.toggleTrashSelect(item) } }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }
    }
}

@Composable
private fun TrashRow(item: TrashItem, selected: Boolean, locale: Locale, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().glass(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Checkbox(checked = selected, onCheckedChange = null, colors = CheckboxDefaults.colors(checkedColor = WeatherText, uncheckedColor = WeatherTextMuted, checkmarkColor = Color.Black))
        Column(Modifier.weight(1f)) {
            Text(item.displayName, color = WeatherText, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(formatDateTime(item.deletedAtMillis, locale), color = WeatherTextMuted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

// endregion

// region Editor ---------------------------------------------------------------------------------

@Composable
private fun NoteEditor(state: NotesUiState, editor: EditorUi, viewModel: NotesViewModel, context: Context, onDialog: (NotesDialog) -> Unit) {
    val note = state.notes.firstOrNull { it.path == editor.path }
    val title = NotesLogic.titleOf(editor.content).ifEmpty { stringResource(R.string.notes_untitled) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            PodcastTextButton(stringResource(R.string.notes_back), viewModel::closeEditor)
            PodcastTextButton(stringResource(if (editor.preview) R.string.notes_edit else R.string.notes_preview)) { viewModel.setPreview(!editor.preview) }
            if (editor.path != null) {
                val favorite = note?.favorite == true
                PodcastTextButton(if (favorite) "★" else "☆") { viewModel.toggleFavorite(editor.path) }
            }
            PodcastTextButton(stringResource(R.string.notes_category)) { onDialog(NotesDialog.Category) }
            PodcastTextButton(stringResource(R.string.notes_share)) { if (!shareText(context, title, editor.content)) viewModel.notice(NotesNotice(NotesNoticeKind.NO_APP)) }
            if (editor.path != null) PodcastTextButton(stringResource(R.string.notes_trash), viewModel::trashEditor)
        }
        Text(
            title,
            color = WeatherText,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
        )
        val status = buildString {
            if (editor.category.isNotEmpty()) append(editor.category).append(" · ")
            append(stringResource(if (editor.dirty) R.string.notes_unsaved else R.string.notes_saved))
        }
        Text(status, color = WeatherTextMuted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 4.dp))
        Spacer(Modifier.height(6.dp))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (editor.preview) {
                MarkdownPreview(editor.content, viewModel::toggleCheck)
            } else {
                OutlinedTextField(
                    value = editor.content,
                    onValueChange = viewModel::editContent,
                    placeholder = { Text(stringResource(R.string.notes_placeholder)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    colors = filesFieldColors(),
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

private fun shareText(context: Context, subject: String, text: String): Boolean = try {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, subject).putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
} catch (e: android.content.ActivityNotFoundException) {
    false
}

@Composable
private fun MarkdownPreview(content: String, onToggleCheck: (Int) -> Unit) {
    val blocks = remember(content) { NotesMarkdown.parse(content) }
    LazyColumn(Modifier.fillMaxSize().glass(RoundedCornerShape(16.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(blocks.size) { i ->
            when (val block = blocks[i]) {
                is MdBlock.Heading -> Text(
                    styled(block.spans),
                    color = WeatherText,
                    fontWeight = FontWeight.Bold,
                    fontSize = when (block.level) { 1 -> 24.sp; 2 -> 21.sp; 3 -> 18.sp; else -> 16.sp },
                )
                is MdBlock.Paragraph -> Text(styled(block.spans), color = WeatherText, style = MaterialTheme.typography.bodyMedium)
                is MdBlock.Bullet -> Row(Modifier.padding(start = (block.indent * 16).dp)) {
                    Text("•  ", color = WeatherText)
                    Text(styled(block.spans), color = WeatherText, style = MaterialTheme.typography.bodyMedium)
                }
                is MdBlock.Numbered -> Row {
                    Text(block.number + ".  ", color = WeatherText)
                    Text(styled(block.spans), color = WeatherText, style = MaterialTheme.typography.bodyMedium)
                }
                is MdBlock.Check -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = block.checked,
                        onCheckedChange = { onToggleCheck(block.lineIndex) },
                        colors = CheckboxDefaults.colors(checkedColor = WeatherText, uncheckedColor = WeatherTextMuted, checkmarkColor = Color.Black),
                    )
                    Text(
                        styled(block.spans),
                        color = if (block.checked) WeatherTextMuted else WeatherText,
                        style = MaterialTheme.typography.bodyMedium,
                        textDecoration = if (block.checked) TextDecoration.LineThrough else null,
                    )
                }
                is MdBlock.Quote -> Text(
                    styled(block.spans),
                    color = WeatherTextMuted,
                    fontStyle = FontStyle.Italic,
                    modifier = Modifier.fillMaxWidth().background(Color(0x22FFFFFF), RoundedCornerShape(8.dp)).padding(8.dp),
                )
                is MdBlock.Code -> Text(
                    block.text,
                    color = WeatherText,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    modifier = Modifier.fillMaxWidth().background(Color(0x33000000), RoundedCornerShape(8.dp)).padding(8.dp),
                )
                MdBlock.Rule -> Box(Modifier.fillMaxWidth().height(1.dp).background(WeatherTextMuted))
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

private fun styled(spans: List<MdSpan>): AnnotatedString = buildAnnotatedString {
    for (span in spans) {
        val style = SpanStyle(
            fontWeight = if (span.bold) FontWeight.Bold else null,
            fontStyle = if (span.italic) FontStyle.Italic else null,
            fontFamily = if (span.code) FontFamily.Monospace else null,
            background = if (span.code) Color(0x33000000) else Color.Unspecified,
        )
        if (span.link != null) {
            withLink(LinkAnnotation.Url(span.link, TextLinkStyles(SpanStyle(color = NotesLink, textDecoration = TextDecoration.Underline)))) {
                withStyle(style) { append(span.text) }
            }
        } else {
            withStyle(style) { append(span.text) }
        }
    }
}

// endregion

// region Dialoge --------------------------------------------------------------------------------

@Composable
private fun LoginDialog(viewModel: NotesViewModel, onDismiss: () -> Unit) {
    var server by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = NotesDialogColor,
        titleContentColor = WeatherText,
        textContentColor = WeatherTextMuted,
        title = { Text(stringResource(R.string.notes_nextcloud_connect)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.notes_login_hint), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(server, { server = it }, singleLine = true, label = { Text(stringResource(R.string.notes_login_server)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), colors = filesFieldColors())
                OutlinedTextField(user, { user = it }, singleLine = true, label = { Text(stringResource(R.string.notes_login_user)) }, colors = filesFieldColors())
                OutlinedTextField(
                    password,
                    { password = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.notes_login_password)) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    colors = filesFieldColors(),
                )
                PodcastTextButton(stringResource(R.string.notes_login_from_feed)) { viewModel.loginWithFeedAccount(); onDismiss() }
            }
        },
        confirmButton = { DialogTextButton(stringResource(R.string.notes_login_action)) { viewModel.login(server, user, password); onDismiss() } },
        dismissButton = { DialogTextButton(stringResource(R.string.notes_cancel), onDismiss) },
    )
}

@Composable
private fun AccountDialog(state: NotesUiState, viewModel: NotesViewModel, onChange: (NotesDialog?) -> Unit) {
    val account = state.account
    val locale = remember { Locale.getDefault() }
    AlertDialog(
        onDismissRequest = { onChange(null) },
        containerColor = NotesDialogColor,
        titleContentColor = WeatherText,
        textContentColor = WeatherTextMuted,
        title = { Text(stringResource(R.string.notes_nextcloud)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (account != null) {
                    Text(account.server, color = WeatherText)
                    Text(account.user, color = WeatherText)
                }
                val last = formatDateTime(state.lastSyncMillis, locale)
                Text(stringResource(R.string.notes_last_sync, last.ifEmpty { stringResource(R.string.notes_never) }), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.notes_account_hint), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { DialogTextButton(stringResource(R.string.notes_sync)) { viewModel.sync(); onChange(null) } },
        dismissButton = {
            Row {
                DialogTextButton(stringResource(R.string.notes_logout)) { viewModel.logout(); onChange(null) }
                DialogTextButton(stringResource(R.string.notes_close)) { onChange(null) }
            }
        },
    )
}

@Composable
private fun CategoryDialog(state: NotesUiState, viewModel: NotesViewModel, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(state.editor?.category.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = NotesDialogColor,
        titleContentColor = WeatherText,
        textContentColor = WeatherTextMuted,
        title = { Text(stringResource(R.string.notes_category)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    text,
                    { text = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.notes_category_hint)) },
                    colors = filesFieldColors(),
                )
                state.categories.forEach { (name, _) ->
                    Text(name, color = WeatherText, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth().clickable { text = name }.padding(vertical = 6.dp))
                }
            }
        },
        confirmButton = { DialogTextButton(stringResource(R.string.notes_apply)) { viewModel.setEditorCategory(text); onDismiss() } },
        dismissButton = { DialogTextButton(stringResource(R.string.notes_cancel), onDismiss) },
    )
}

// endregion
