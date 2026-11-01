// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.ui

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.tux4us.miriquidilauncher.R
import io.github.tux4us.miriquidilauncher.files.OpKind
import io.github.tux4us.miriquidilauncher.gallery.Album
import io.github.tux4us.miriquidilauncher.gallery.GalleryIntents
import io.github.tux4us.miriquidilauncher.gallery.GalleryLogic
import io.github.tux4us.miriquidilauncher.gallery.GalleryNotice
import io.github.tux4us.miriquidilauncher.gallery.GalleryNoticeKind
import io.github.tux4us.miriquidilauncher.gallery.GalleryRepository
import io.github.tux4us.miriquidilauncher.gallery.GalleryTab
import io.github.tux4us.miriquidilauncher.gallery.GalleryThumbnails
import io.github.tux4us.miriquidilauncher.gallery.GalleryUiState
import io.github.tux4us.miriquidilauncher.gallery.GalleryViewModel
import io.github.tux4us.miriquidilauncher.gallery.MediaEntry
import io.github.tux4us.miriquidilauncher.gallery.MediaFilter
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

private val GalleryError = Color(0xFFFFB4B4)
internal val GalleryDialogColor = Color(0xFF141A22)

/** Dialoge, die der Bildschirm selbst verwaltet. [entries] sind die betroffenen Medien. */
internal sealed interface GalleryDialog {
    data class Rename(val entry: MediaEntry) : GalleryDialog
    data class PickAlbum(val entries: List<MediaEntry>, val move: Boolean) : GalleryDialog
    data class NewAlbum(val entries: List<MediaEntry>, val move: Boolean) : GalleryDialog
    data object PurgeSelected : GalleryDialog
}

/**
 * Galerie: Fotos und Videos aus der Mediathek des Geräts als Raster nach Tagen, Alben, Favoriten, Suche,
 * Vollansicht mit Zoom, Diashow, Teilen, Verschieben/Kopieren zwischen Alben und eigener Papierkorb.
 * [active] ist `true`, solange die Seite sichtbar ist.
 */
@Composable
fun GalleryScreen(viewModel: GalleryViewModel, active: Boolean, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var dialog by remember { mutableStateOf<GalleryDialog?>(null) }

    LaunchedEffect(active) { if (active) viewModel.onActive() }
    DisposableEffect(lifecycle, active) {
        val observer = LifecycleEventObserver { _, event ->
            if (active && event == Lifecycle.Event.ON_RESUME) viewModel.onActive()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { viewModel.onActive() }
    val grantMedia = { permissionLauncher.launch(GalleryRepository.permissions()) }
    // Systemdialog der Mediathek („Erlauben?“) für Papierkorb, Löschen, Umbenennen und Verschieben.
    val consentRequest by viewModel.consentRequest.collectAsStateWithLifecycle()
    val consentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        viewModel.onConsentResult(result.resultCode == Activity.RESULT_OK)
    }
    LaunchedEffect(consentRequest) {
        consentRequest?.let {
            viewModel.consentLaunched()
            consentLauncher.launch(IntentSenderRequest.Builder(it).build())
        }
    }

    val canGoBack = state.viewerIndex != null || state.selection.isNotEmpty() || state.trashSelection.isNotEmpty() ||
        state.searchQuery != null || state.tab != GalleryTab.ALL
    BackHandler(enabled = active && state.mediaAccess == true && canGoBack) { viewModel.back() }

    ScopePage(title = stringResource(R.string.scope_gallery), modifier = modifier) {
        Column(Modifier.fillMaxSize()) {
            state.notice?.let { GalleryNoticeBanner(it, viewModel::consumeNotice) }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (state.mediaAccess) {
                    null -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = WeatherText)
                    false -> GalleryPermissionCard(grantMedia)
                    true -> GalleryContent(state, viewModel, context) { dialog = it }
                }
            }
            Spacer(Modifier.height(ScopeBottomClearance))
        }
    }

    GalleryDialogs(state, dialog, viewModel) { dialog = it }
    if (state.viewerIndex != null) GalleryViewer(state, viewModel, context) { dialog = it }
}

// region Berechtigung und Hinweise --------------------------------------------------------------

@Composable
private fun GalleryPermissionCard(onGrantMedia: () -> Unit) {
    GlassCard(stringResource(R.string.scope_gallery)) {
        Text(stringResource(R.string.gallery_permission_text), color = WeatherTextMuted, style = MaterialTheme.typography.bodyMedium)
        PodcastButton(stringResource(R.string.gallery_permission_grant), onGrantMedia)
    }
}

@Composable
private fun GalleryNoticeBanner(notice: GalleryNotice, onDismiss: () -> Unit) {
    val failed = notice.kind != GalleryNoticeKind.DONE && notice.kind != GalleryNoticeKind.CANCELLED
    Row(
        Modifier.fillMaxWidth().padding(bottom = 8.dp).glass(RoundedCornerShape(14.dp)).padding(start = 14.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(galleryNoticeText(notice), color = if (failed) GalleryError else WeatherText, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        PodcastTextButton(stringResource(R.string.gallery_notice_dismiss), onDismiss)
    }
}

@Composable
internal fun galleryNoticeText(n: GalleryNotice): String = when (n.kind) {
    GalleryNoticeKind.DONE -> when (n.op) {
        OpKind.COPY -> stringResource(R.string.gallery_done_copy, n.count)
        OpKind.MOVE -> stringResource(R.string.gallery_done_move, n.count)
        OpKind.DELETE -> stringResource(R.string.gallery_done_delete, n.count)
        OpKind.TRASH -> stringResource(R.string.gallery_done_trash, n.count)
        OpKind.RESTORE -> stringResource(R.string.gallery_done_restore, n.count)
        else -> stringResource(R.string.gallery_done_generic)
    }
    GalleryNoticeKind.PARTLY_FAILED -> stringResource(R.string.gallery_notice_partly, n.count, n.other)
    GalleryNoticeKind.CANCELLED -> stringResource(R.string.gallery_notice_cancelled)
    GalleryNoticeKind.NEEDS_ANDROID_11 -> stringResource(R.string.gallery_notice_needs_android11)
    GalleryNoticeKind.NAME_INVALID -> stringResource(R.string.gallery_notice_name_invalid)
    GalleryNoticeKind.NAME_EXISTS -> stringResource(R.string.gallery_notice_name_exists)
    GalleryNoticeKind.RENAME_FAILED -> stringResource(R.string.gallery_notice_rename_failed)
    GalleryNoticeKind.NO_APP -> stringResource(R.string.gallery_notice_no_app)
    GalleryNoticeKind.NO_PATH -> stringResource(R.string.gallery_notice_no_path)
}

// endregion

// region Inhalt ---------------------------------------------------------------------------------

@Composable
private fun GalleryContent(
    state: GalleryUiState,
    viewModel: GalleryViewModel,
    context: Context,
    onDialog: (GalleryDialog) -> Unit,
) {
    val selecting = state.selection.isNotEmpty()
    Column(Modifier.fillMaxSize()) {
        TabBar(state, viewModel)
        Spacer(Modifier.height(6.dp))
        when {
            state.tab == GalleryTab.TRASH -> Unit
            selecting -> SelectionBar(state, viewModel, context, onDialog)
            else -> ActionBar(state, viewModel)
        }
        if (state.searchQuery != null && state.tab != GalleryTab.TRASH) SearchField(state, viewModel)
        Spacer(Modifier.height(4.dp))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                state.tab == GalleryTab.TRASH -> TrashPage(state, viewModel, onDialog)
                state.loading && state.items.isEmpty() -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = WeatherText)
                state.tab == GalleryTab.ALBUMS && state.albumOpen == null -> AlbumsGrid(state, viewModel)
                state.visible.isEmpty() -> ScopeEmptyHint(
                    stringResource(
                        when {
                            state.searchQuery?.isNotBlank() == true -> R.string.gallery_empty_search
                            state.tab == GalleryTab.FAVORITES -> R.string.gallery_empty_favorites
                            else -> R.string.gallery_empty
                        },
                    ),
                )
                else -> MediaGrid(state, viewModel)
            }
        }
    }
}

@Composable
private fun TabBar(state: GalleryUiState, viewModel: GalleryViewModel) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SegmentChip(stringResource(R.string.gallery_tab_all), state.tab == GalleryTab.ALL) { viewModel.setTab(GalleryTab.ALL) }
        SegmentChip(stringResource(R.string.gallery_tab_albums), state.tab == GalleryTab.ALBUMS) { viewModel.setTab(GalleryTab.ALBUMS) }
        SegmentChip(stringResource(R.string.gallery_tab_favorites), state.tab == GalleryTab.FAVORITES) { viewModel.setTab(GalleryTab.FAVORITES) }
        SegmentChip(stringResource(R.string.gallery_tab_trash), state.tab == GalleryTab.TRASH) { viewModel.setTab(GalleryTab.TRASH) }
    }
}

@Composable
private fun ActionBar(state: GalleryUiState, viewModel: GalleryViewModel) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (state.tab == GalleryTab.ALBUMS && state.albumOpen != null) {
            PodcastTextButton(stringResource(R.string.gallery_albums_back)) { viewModel.back() }
        }
        if (!(state.tab == GalleryTab.ALBUMS && state.albumOpen == null)) {
            MediaFilter.entries.forEach { f ->
                val label = when (f) {
                    MediaFilter.ALL -> R.string.gallery_filter_all
                    MediaFilter.PHOTOS -> R.string.gallery_filter_photos
                    MediaFilter.VIDEOS -> R.string.gallery_filter_videos
                }
                PodcastTextButton((if (state.filter == f) "● " else "○ ") + stringResource(label)) { viewModel.setFilter(f) }
            }
            PodcastTextButton(stringResource(R.string.gallery_search), viewModel::openSearch)
            PodcastTextButton(stringResource(if (state.ascending) R.string.gallery_sort_oldest else R.string.gallery_sort_newest), viewModel::toggleSort)
        }
        PodcastTextButton((if (state.showHidden) "☑ " else "☐ ") + stringResource(R.string.gallery_show_hidden), viewModel::toggleShowHidden)
        PodcastTextButton(stringResource(R.string.gallery_reload), viewModel::reload)
    }
}

@Composable
private fun SelectionBar(state: GalleryUiState, viewModel: GalleryViewModel, context: Context, onDialog: (GalleryDialog) -> Unit) {
    val selected = viewModel.selectedEntries()
    val allFavorite = selected.isNotEmpty() && selected.all { viewModel.isFavorite(it) }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.gallery_selected, state.selection.size),
            color = WeatherText,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        PodcastTextButton(stringResource(R.string.gallery_select_all), viewModel::selectAll)
        PodcastTextButton(stringResource(if (allFavorite) R.string.gallery_unfavorite else R.string.gallery_favorite)) {
            viewModel.setFavorite(selected, !allFavorite)
        }
        PodcastTextButton(stringResource(R.string.gallery_share)) {
            if (!GalleryIntents.share(context, selected)) viewModel.reportNoApp()
        }
        PodcastTextButton(stringResource(R.string.gallery_move)) { onDialog(GalleryDialog.PickAlbum(selected, move = true)) }
        PodcastTextButton(stringResource(R.string.gallery_copy)) { onDialog(GalleryDialog.PickAlbum(selected, move = false)) }
        PodcastTextButton(stringResource(R.string.gallery_trash)) { viewModel.trash(selected) }
        PodcastTextButton(stringResource(R.string.gallery_cancel), viewModel::clearSelection)
    }
}

@Composable
private fun SearchField(state: GalleryUiState, viewModel: GalleryViewModel) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = state.searchQuery.orEmpty(),
            onValueChange = viewModel::setQuery,
            singleLine = true,
            label = { Text(stringResource(R.string.gallery_search_hint)) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {}),
            colors = filesFieldColors(),
            modifier = Modifier.weight(1f),
        )
        PodcastTextButton(stringResource(R.string.gallery_search_close), viewModel::closeSearch)
    }
}

// endregion

// region Raster ---------------------------------------------------------------------------------

@Composable
private fun MediaGrid(state: GalleryUiState, viewModel: GalleryViewModel) {
    val locale = remember { Locale.getDefault() }
    val formatter = remember(locale) { DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale) }
    val selecting = state.selection.isNotEmpty()
    LazyVerticalGrid(
        columns = GridCells.Adaptive(96.dp),
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        state.sections.forEach { section ->
            item(key = "h${section.day}", span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    section.day.format(formatter),
                    color = WeatherText,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(start = 4.dp, top = 10.dp, bottom = 4.dp),
                )
            }
            items(section.items, key = { it.key }) { entry ->
                MediaThumb(
                    entry = entry,
                    selected = entry.key in state.selection,
                    selecting = selecting,
                    favorite = entry.path.isNotEmpty() && entry.path in state.favorites,
                    onClick = { if (selecting) viewModel.toggleSelect(entry.key) else viewModel.openViewer(entry.key) },
                    onLongClick = { viewModel.toggleSelect(entry.key) },
                )
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(8.dp)) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaThumb(
    entry: MediaEntry,
    selected: Boolean,
    selecting: Boolean,
    favorite: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val appContext = LocalContext.current.applicationContext
    val bitmap by produceState<Bitmap?>(null, entry.key) { value = GalleryThumbnails.load(appContext, entry) }
    Box(
        Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0x22FFFFFF))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        bitmap?.let { Image(it.asImageBitmap(), contentDescription = entry.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        if (selected) Box(Modifier.fillMaxSize().background(Color(0x66000000)))
        if (entry.isVideo) {
            Text(
                "▶ " + GalleryLogic.formatDuration(entry.durationMs),
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.BottomEnd).padding(3.dp).background(Color(0x99000000), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
        if (favorite) {
            Text("★", color = Color.White, modifier = Modifier.align(Alignment.BottomStart).padding(start = 5.dp, bottom = 2.dp))
        }
        if (selecting) {
            Checkbox(
                checked = selected,
                onCheckedChange = null,
                colors = CheckboxDefaults.colors(checkedColor = WeatherText, uncheckedColor = Color.White, checkmarkColor = Color.Black),
                modifier = Modifier.align(Alignment.TopStart).padding(4.dp),
            )
        }
    }
}

// endregion

// region Alben ----------------------------------------------------------------------------------

@Composable
private fun AlbumsGrid(state: GalleryUiState, viewModel: GalleryViewModel) {
    if (state.albums.isEmpty()) {
        ScopeEmptyHint(stringResource(R.string.gallery_empty))
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(140.dp),
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(state.albums, key = { it.bucketId }) { album ->
            AlbumCard(album, hidden = album.bucketId in state.hiddenBuckets, onOpen = { viewModel.openAlbum(album.bucketId) }, onToggleHidden = { viewModel.toggleAlbumHidden(album.bucketId) })
        }
        item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(8.dp)) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AlbumCard(album: Album, hidden: Boolean, onOpen: () -> Unit, onToggleHidden: () -> Unit) {
    val appContext = LocalContext.current.applicationContext
    val bitmap by produceState<Bitmap?>(null, album.cover.key) { value = GalleryThumbnails.load(appContext, album.cover) }
    var menu by remember { mutableStateOf(false) }
    Box {
        Column(
            Modifier.fillMaxWidth().glass(RoundedCornerShape(14.dp)).combinedClickable(onClick = onOpen, onLongClick = { menu = true }).padding(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(10.dp)).background(Color(0x22FFFFFF))) {
                bitmap?.let { Image(it.asImageBitmap(), contentDescription = album.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            }
            Text(album.name, color = WeatherText, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val counts = buildList {
                if (album.photos > 0) add(stringResource(R.string.gallery_count_photos, album.photos))
                if (album.videos > 0) add(stringResource(R.string.gallery_count_videos, album.videos))
            }.joinToString(" · ")
            Text(
                counts + if (hidden) " · " + stringResource(R.string.gallery_album_hidden_mark) else "",
                color = WeatherTextMuted,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(if (hidden) R.string.gallery_album_unhide else R.string.gallery_album_hide)) },
                onClick = { menu = false; onToggleHidden() },
            )
        }
    }
}

// endregion

// region Papierkorb -----------------------------------------------------------------------------

@Composable
private fun TrashPage(state: GalleryUiState, viewModel: GalleryViewModel, onDialog: (GalleryDialog) -> Unit) {
    if (!state.canManage) {
        GlassCard(stringResource(R.string.gallery_tab_trash)) {
            Text(stringResource(R.string.gallery_trash_needs_android11), color = WeatherTextMuted, style = MaterialTheme.typography.bodyMedium)
        }
        return
    }
    val locale = remember { Locale.getDefault() }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            if (state.trashItems.isNotEmpty()) {
                val scopeLabel = if (state.trashSelection.isEmpty()) R.string.gallery_trash_restore_all else R.string.gallery_trash_restore_selected
                PodcastTextButton(stringResource(scopeLabel), viewModel::restoreTrash)
                PodcastTextButton(stringResource(R.string.gallery_trash_purge)) { onDialog(GalleryDialog.PurgeSelected) }
            }
        }
        Text(
            stringResource(R.string.gallery_trash_info, GalleryLogic.TRASH_RETENTION_DAYS),
            color = WeatherTextMuted,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
        )
        if (state.trashItems.isEmpty()) {
            ScopeEmptyHint(stringResource(R.string.gallery_trash_none))
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(96.dp),
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                items(state.trashItems, key = { it.key }) { item ->
                    TrashThumb(item, selected = item.key in state.trashSelection, locale = locale) { viewModel.toggleTrashSelect(item) }
                }
                item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(8.dp)) }
            }
        }
    }
}

@Composable
private fun TrashThumb(item: MediaEntry, selected: Boolean, locale: Locale, onClick: () -> Unit) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(null, item.key) { value = GalleryThumbnails.load(context, item) }
    Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(6.dp)).background(Color(0x22FFFFFF)).clickable(onClick = onClick)) {
        bitmap?.let { Image(it.asImageBitmap(), contentDescription = item.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        if (selected) Box(Modifier.fillMaxSize().background(Color(0x66000000)))
        if (item.expiresMillis > 0) {
            Text(
                formatDateTime(item.expiresMillis, locale),
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                modifier = Modifier.align(Alignment.BottomStart).padding(3.dp).background(Color(0x99000000), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
        Checkbox(
            checked = selected,
            onCheckedChange = null,
            colors = CheckboxDefaults.colors(checkedColor = WeatherText, uncheckedColor = Color.White, checkmarkColor = Color.Black),
            modifier = Modifier.align(Alignment.TopStart).padding(4.dp),
        )
    }
}

// endregion

// region Dialoge --------------------------------------------------------------------------------

@Composable
private fun GalleryDialogButton(text: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, colors = ButtonDefaults.textButtonColors(contentColor = WeatherText)) { Text(text) }
}

@Composable
internal fun GalleryDialogs(state: GalleryUiState, dialog: GalleryDialog?, viewModel: GalleryViewModel, onChange: (GalleryDialog?) -> Unit) {
    val context = LocalContext.current
    val onDismiss = { onChange(null) }
    when (dialog) {
        null -> Unit
        is GalleryDialog.Rename -> {
            var text by remember(dialog) { mutableStateOf(dialog.entry.name) }
            AlertDialog(
                onDismissRequest = onDismiss,
                containerColor = GalleryDialogColor,
                titleContentColor = WeatherText,
                title = { Text(stringResource(R.string.gallery_rename)) },
                text = {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        singleLine = true,
                        label = { Text(stringResource(R.string.gallery_rename_label)) },
                        colors = filesFieldColors(),
                    )
                },
                confirmButton = { GalleryDialogButton(stringResource(R.string.gallery_rename)) { if (text.isNotBlank()) { viewModel.rename(dialog.entry, text); onDismiss() } } },
                dismissButton = { GalleryDialogButton(stringResource(R.string.gallery_cancel), onDismiss) },
            )
        }
        is GalleryDialog.PickAlbum -> {
            val targets = remember(state.albums) { GalleryLogic.targetAlbums(GalleryLogic.albums(state.items, emptySet(), true)) }
            AlertDialog(
                onDismissRequest = onDismiss,
                containerColor = GalleryDialogColor,
                titleContentColor = WeatherText,
                title = { Text(stringResource(if (dialog.move) R.string.gallery_move_to else R.string.gallery_copy_to)) },
                text = {
                    LazyColumn(Modifier.fillMaxWidth()) {
                        item {
                            Text(
                                stringResource(R.string.gallery_album_new),
                                color = WeatherText,
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.fillMaxWidth().clickable { onChange(GalleryDialog.NewAlbum(dialog.entries, dialog.move)) }.padding(vertical = 12.dp),
                            )
                        }
                        items(targets, key = { it.bucketId }) { album ->
                            Column(
                                Modifier.fillMaxWidth().clickable {
                                    if (dialog.move) viewModel.moveTo(dialog.entries, album.dir) else viewModel.copyTo(dialog.entries, album.dir)
                                    onDismiss()
                                }.padding(vertical = 8.dp),
                            ) {
                                Text(album.name, color = WeatherText, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(album.dir, color = WeatherTextMuted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = { GalleryDialogButton(stringResource(R.string.gallery_cancel), onDismiss) },
            )
        }
        is GalleryDialog.NewAlbum -> {
            var text by remember(dialog) { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = onDismiss,
                containerColor = GalleryDialogColor,
                titleContentColor = WeatherText,
                title = { Text(stringResource(R.string.gallery_album_new)) },
                text = {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        singleLine = true,
                        label = { Text(stringResource(R.string.gallery_album_name)) },
                        colors = filesFieldColors(),
                    )
                },
                confirmButton = {
                    GalleryDialogButton(stringResource(if (dialog.move) R.string.gallery_move else R.string.gallery_copy)) {
                        if (text.isNotBlank()) { viewModel.createAlbumAndTransfer(text, dialog.entries, dialog.move); onDismiss() }
                    }
                },
                dismissButton = { GalleryDialogButton(stringResource(R.string.gallery_cancel), onDismiss) },
            )
        }
        GalleryDialog.PurgeSelected -> {
            val count = if (state.trashSelection.isEmpty()) state.trashItems.size else state.trashSelection.size
            AlertDialog(
                onDismissRequest = onDismiss,
                containerColor = GalleryDialogColor,
                titleContentColor = WeatherText,
                textContentColor = WeatherTextMuted,
                title = { Text(stringResource(R.string.gallery_trash_purge)) },
                text = { Text(stringResource(R.string.gallery_trash_purge_confirm, count)) },
                confirmButton = { GalleryDialogButton(stringResource(R.string.gallery_trash_purge)) { viewModel.purgeTrash(); onDismiss() } },
                dismissButton = { GalleryDialogButton(stringResource(R.string.gallery_cancel), onDismiss) },
            )
        }
    }

    state.operation?.let { op ->
        AlertDialog(
            onDismissRequest = {},
            containerColor = GalleryDialogColor,
            titleContentColor = WeatherText,
            textContentColor = WeatherTextMuted,
            title = { Text(stringResource(galleryOpLabel(op.kind))) },
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
                        Text(Formatter.formatFileSize(context, p.doneBytes) + " / " + Formatter.formatFileSize(context, p.totalBytes), style = MaterialTheme.typography.bodySmall)
                    } else {
                        LinearProgressIndicator(color = WeatherText, trackColor = Color(0x44FFFFFF), modifier = Modifier.fillMaxWidth())
                    }
                    p?.currentName?.let { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = { GalleryDialogButton(stringResource(R.string.gallery_cancel), viewModel::cancelOperation) },
        )
    }
}

private fun galleryOpLabel(kind: OpKind): Int = when (kind) {
    OpKind.COPY -> R.string.gallery_op_copy
    OpKind.MOVE -> R.string.gallery_op_move
    OpKind.TRASH -> R.string.gallery_op_trash
    OpKind.RESTORE -> R.string.gallery_op_restore
    else -> R.string.gallery_op_delete
}

// endregion
