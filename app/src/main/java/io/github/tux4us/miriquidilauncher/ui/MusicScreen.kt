// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.tux4us.miriquidilauncher.R
import io.github.tux4us.miriquidilauncher.music.LibraryMode
import io.github.tux4us.miriquidilauncher.music.MusicLibrary
import io.github.tux4us.miriquidilauncher.music.MusicLogic
import io.github.tux4us.miriquidilauncher.music.MusicNotice
import io.github.tux4us.miriquidilauncher.music.MusicNoticeKind
import io.github.tux4us.miriquidilauncher.music.MusicTab
import io.github.tux4us.miriquidilauncher.music.MusicUiState
import io.github.tux4us.miriquidilauncher.music.MusicViewModel
import io.github.tux4us.miriquidilauncher.music.Track

private val MusicError = Color(0xFFFFB4B4)

/** Dialoge der Musik-Scope; der Zustand liegt in [MusicScreen], die Tabs lösen sie über einen Rückruf aus. */
internal sealed interface MusicDialog {
    data object NewList : MusicDialog
    data class RenameList(val id: String, val name: String) : MusicDialog
    data class DeleteList(val id: String, val name: String) : MusicDialog
    data class PickList(val tracks: List<Track>) : MusicDialog
    data object AddStation : MusicDialog
    data class RenameStation(val id: String, val name: String) : MusicDialog
}

/**
 * Musik-Seite: Bibliothek (Titel, Alben, Interpreten), Radio, Listen und Equalizer, darunter die Mini-Leiste des Players.
 * Ein Tipp auf die Leiste öffnet die große Player-Ansicht. [active] ist `true`, solange die Seite sichtbar ist.
 */
@Composable
fun MusicScreen(viewModel: MusicViewModel, active: Boolean, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var dialog by remember { mutableStateOf<MusicDialog?>(null) }

    LaunchedEffect(active) { if (active) viewModel.onActive() }
    // Zurück schließt zuerst Player-Ansicht und Detailseiten; erst danach geht es zur Startseite.
    BackHandler(
        enabled = active && (state.playerOpen || state.openAlbum != null || state.openArtist != null || state.openPlaylist != null || state.showFavorites),
    ) { viewModel.back() }

    val audioPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { viewModel.refreshLibrary() }
    // Ab Android 13 ist die Benachrichtigung des Wiedergabedienstes eine Berechtigung; sie wird einmal angefragt.
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    var askedNotification by rememberSaveable { mutableStateOf(false) }
    val askNotification = {
        if (Build.VERSION.SDK_INT >= 33 && !askedNotification &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askedNotification = true
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    val play: (List<Track>, Int) -> Unit = { tracks, index ->
        askNotification()
        viewModel.toggleTrack(tracks, index)
    }
    val playShuffled: (List<Track>) -> Unit = { tracks ->
        askNotification()
        viewModel.playShuffled(tracks)
    }
    val playStation: (io.github.tux4us.miriquidilauncher.music.Station) -> Unit = { station ->
        askNotification()
        viewModel.playStation(station)
    }

    ScopePage(title = stringResource(R.string.scope_music), modifier = modifier) {
        if (state.playerOpen && state.player.active) {
            NowPlayingView(state, viewModel, onOpenEqualizer = {
                viewModel.selectTab(MusicTab.EQUALIZER)
                viewModel.closePlayer()
            })
        } else {
            Column(Modifier.fillMaxSize()) {
                state.notice?.let { MusicNoticeBanner(it, viewModel::dismissNotice) }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (!state.loaded) {
                        CircularProgressIndicator(Modifier.align(Alignment.Center), color = WeatherText)
                    } else {
                        Column(Modifier.fillMaxSize()) {
                            MusicTabRow(state.tab, viewModel::selectTab)
                            Spacer(Modifier.height(8.dp))
                            when (state.tab) {
                                MusicTab.LIBRARY -> LibraryTab(state, viewModel, play, playShuffled, onGrant = { audioPermission.launch(MusicLibrary.permission()) }) { dialog = it }
                                MusicTab.RADIO -> RadioTab(state, viewModel, playStation) { dialog = it }
                                MusicTab.LISTS -> ListsTab(state, viewModel, play, playShuffled) { dialog = it }
                                MusicTab.EQUALIZER -> EqualizerTab(state, viewModel)
                            }
                        }
                    }
                }
                MiniPlayer(state, viewModel)
                Spacer(Modifier.height(ScopeBottomClearance))
            }
        }
    }

    MusicDialogs(dialog, state, viewModel) { dialog = null }
}

// region Gemeinsame Bausteine ------------------------------------------------------------------

@Composable
private fun MusicTabRow(selected: MusicTab, onSelect: (MusicTab) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SegmentChip(stringResource(R.string.music_tab_library), selected == MusicTab.LIBRARY) { onSelect(MusicTab.LIBRARY) }
        SegmentChip(stringResource(R.string.music_tab_radio), selected == MusicTab.RADIO) { onSelect(MusicTab.RADIO) }
        SegmentChip(stringResource(R.string.music_tab_lists), selected == MusicTab.LISTS) { onSelect(MusicTab.LISTS) }
        SegmentChip(stringResource(R.string.music_tab_eq), selected == MusicTab.EQUALIZER) { onSelect(MusicTab.EQUALIZER) }
    }
}

@Composable
private fun MusicNoticeBanner(notice: MusicNotice, onDismiss: () -> Unit) {
    val text = when (notice.kind) {
        MusicNoticeKind.NETWORK -> stringResource(R.string.music_notice_network)
        MusicNoticeKind.LIMIT -> stringResource(R.string.music_notice_limit)
        MusicNoticeKind.PLAYBACK_FAILED -> stringResource(R.string.music_notice_playback_failed)
        MusicNoticeKind.HTTP_BLOCKED -> stringResource(R.string.music_notice_http_blocked)
        MusicNoticeKind.INVALID_URL -> stringResource(R.string.music_notice_invalid_url)
        MusicNoticeKind.STATION_SAVED -> stringResource(R.string.music_notice_station_saved)
        MusicNoticeKind.PLAYLIST_NAME_TAKEN -> stringResource(R.string.music_notice_name_taken)
        MusicNoticeKind.ADDED_TO_PLAYLIST -> stringResource(R.string.music_notice_added_to_list, notice.a)
        MusicNoticeKind.QUEUED_NEXT -> stringResource(R.string.music_notice_queued_next)
        MusicNoticeKind.QUEUED_END -> stringResource(R.string.music_notice_queued_end)
        MusicNoticeKind.STATIONS_IMPORTED -> stringResource(R.string.music_notice_stations_imported, notice.a, notice.b)
        MusicNoticeKind.STATIONS_EXPORTED -> stringResource(R.string.music_notice_stations_exported, notice.a)
        MusicNoticeKind.IMPORT_FAILED -> stringResource(R.string.music_notice_import_failed)
        MusicNoticeKind.EXPORT_FAILED -> stringResource(R.string.music_notice_export_failed)
    }
    val failed = notice.kind in setOf(
        MusicNoticeKind.NETWORK, MusicNoticeKind.LIMIT, MusicNoticeKind.PLAYBACK_FAILED, MusicNoticeKind.HTTP_BLOCKED,
        MusicNoticeKind.INVALID_URL, MusicNoticeKind.PLAYLIST_NAME_TAKEN, MusicNoticeKind.IMPORT_FAILED, MusicNoticeKind.EXPORT_FAILED,
    )
    Row(
        Modifier.fillMaxWidth().padding(bottom = 8.dp).glass(RoundedCornerShape(14.dp)).padding(start = 14.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = if (failed) MusicError else WeatherText, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        TextButton(onClick = onDismiss, colors = ButtonDefaults.textButtonColors(contentColor = WeatherText)) {
            Text(stringResource(R.string.music_notice_dismiss))
        }
    }
}

@Composable
internal fun musicFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = WeatherText,
    unfocusedTextColor = WeatherText,
    focusedBorderColor = WeatherText,
    unfocusedBorderColor = WeatherTextMuted,
    focusedLabelColor = WeatherText,
    unfocusedLabelColor = WeatherTextMuted,
    cursorColor = WeatherText,
)

/** Hinweis, wenn die Mediathek nicht gelesen werden darf. */
@Composable
private fun PermissionCard(onGrant: () -> Unit) {
    GlassCard(stringResource(R.string.scope_music)) {
        Text(stringResource(R.string.music_permission_text), color = WeatherTextMuted, style = MaterialTheme.typography.bodyMedium)
        PodcastButton(stringResource(R.string.music_permission_grant), onGrant)
    }
}

/** Zeile eines Liedes: Cover, Titel, Interpret, Dauer und Menü. [highlight] markiert das laufende Lied. */
@Composable
private fun TrackRow(
    track: Track,
    highlight: Boolean,
    favorite: Boolean,
    onClick: () -> Unit,
    menu: @Composable (dismiss: () -> Unit) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(horizontal = 4.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MusicCover(track, null, false, 128, Modifier.size(48.dp), corner = 8.dp)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(
                track.title,
                color = if (highlight) MusicAccent else WeatherText,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val artist = track.artist.ifEmpty { stringResource(R.string.music_unknown_artist) }
            Text(
                listOf(artist, track.album).filter { it.isNotEmpty() }.joinToString(" · "),
                color = WeatherTextMuted,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (favorite) {
            Icon(painterResource(R.drawable.ic_favorite), null, tint = MusicAccent, modifier = Modifier.size(16.dp))
            Spacer(Modifier.size(6.dp))
        }
        Text(MusicLogic.formatTime(track.durationMs), color = WeatherTextMuted, style = MaterialTheme.typography.labelMedium)
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(painterResource(R.drawable.ic_more_vert), stringResource(R.string.music_more), tint = WeatherTextMuted)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) { menu { menuOpen = false } }
        }
    }
}

/** Zusätzliche Menüpunkte eines Liedes innerhalb einer Wiedergabeliste. */
private class ListActions(val onRemove: () -> Unit, val onUp: () -> Unit, val onDown: () -> Unit)

@Composable
private fun TrackMenu(
    track: Track,
    state: MusicUiState,
    viewModel: MusicViewModel,
    dismiss: () -> Unit,
    onDialog: (MusicDialog) -> Unit,
    listActions: ListActions?,
) {
    val favorite = track.id in state.data.favoriteTracks
    DropdownMenuItem(text = { Text(stringResource(R.string.music_menu_play_next)) }, onClick = { dismiss(); viewModel.enqueue(track, next = true) })
    DropdownMenuItem(text = { Text(stringResource(R.string.music_menu_queue)) }, onClick = { dismiss(); viewModel.enqueue(track, next = false) })
    DropdownMenuItem(
        text = { Text(stringResource(if (favorite) R.string.music_menu_favorite_remove else R.string.music_menu_favorite_add)) },
        onClick = { dismiss(); viewModel.toggleFavorite(track) },
    )
    DropdownMenuItem(text = { Text(stringResource(R.string.music_menu_add_to_list)) }, onClick = { dismiss(); onDialog(MusicDialog.PickList(listOf(track))) })
    if (listActions != null) {
        DropdownMenuItem(text = { Text(stringResource(R.string.music_menu_move_up)) }, onClick = { dismiss(); listActions.onUp() })
        DropdownMenuItem(text = { Text(stringResource(R.string.music_menu_move_down)) }, onClick = { dismiss(); listActions.onDown() })
        DropdownMenuItem(text = { Text(stringResource(R.string.music_menu_remove_from_list)) }, onClick = { dismiss(); listActions.onRemove() })
    }
}

/** Seite mit Liedern (Album, Interpret, Favoriten, Wiedergabeliste): Kopf mit Zurück, Abspielen und Zufall, darunter die Liste. */
@Composable
private fun TrackListPage(
    title: String,
    subtitle: String,
    tracks: List<Track>,
    state: MusicUiState,
    viewModel: MusicViewModel,
    play: (List<Track>, Int) -> Unit,
    playShuffled: (List<Track>) -> Unit,
    onDialog: (MusicDialog) -> Unit,
    headerActions: @Composable () -> Unit = {},
    listActions: ((Track) -> ListActions)? = null,
) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { viewModel.back() }) {
                Icon(painterResource(R.drawable.ic_chevron_left), stringResource(R.string.music_back), tint = WeatherText)
            }
            Column(Modifier.weight(1f)) {
                Text(title, color = WeatherText, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle.isNotEmpty()) Text(subtitle, color = WeatherTextMuted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            headerActions()
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PodcastButton(stringResource(R.string.music_play_all), { play(tracks, 0) }, enabled = tracks.isNotEmpty())
            PodcastTextButton(stringResource(R.string.music_shuffle_play)) { if (tracks.isNotEmpty()) playShuffled(tracks) }
        }
        if (tracks.isEmpty()) {
            ScopeEmptyHint(stringResource(R.string.music_empty_list))
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(tracks.size, key = { tracks[it].id }) { index ->
                    val track = tracks[index]
                    TrackRow(
                        track = track,
                        highlight = state.player.track?.id == track.id,
                        favorite = track.id in state.data.favoriteTracks,
                        onClick = { play(tracks, index) },
                        menu = { dismiss -> TrackMenu(track, state, viewModel, dismiss, onDialog, listActions?.invoke(track)) },
                    )
                }
            }
        }
    }
}

// endregion

// region Bibliothek ----------------------------------------------------------------------------

@Composable
private fun LibraryTab(
    state: MusicUiState,
    viewModel: MusicViewModel,
    play: (List<Track>, Int) -> Unit,
    playShuffled: (List<Track>) -> Unit,
    onGrant: () -> Unit,
    onDialog: (MusicDialog) -> Unit,
) {
    if (!state.hasAccess) {
        PermissionCard(onGrant)
        return
    }
    val albumId = state.openAlbum
    val artist = state.openArtist
    when {
        albumId != null -> {
            val tracks = remember(state.tracks, albumId) { MusicLogic.tracksOfAlbum(state.tracks, albumId) }
            val album = tracks.firstOrNull()
            TrackListPage(album?.album.orEmpty(), album?.artist.orEmpty(), tracks, state, viewModel, play, playShuffled, onDialog)
        }
        artist != null -> {
            val tracks = remember(state.tracks, artist) { MusicLogic.tracksOfArtist(state.tracks, artist) }
            TrackListPage(artist, "", tracks, state, viewModel, play, playShuffled, onDialog)
        }
        else -> LibraryOverview(state, viewModel, play, onDialog)
    }
}

@Composable
private fun LibraryOverview(state: MusicUiState, viewModel: MusicViewModel, play: (List<Track>, Int) -> Unit, onDialog: (MusicDialog) -> Unit) {
    val filtered = remember(state.tracks, state.query) { MusicLogic.filter(state.tracks, state.query) }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = state.query,
            onValueChange = viewModel::setQuery,
            singleLine = true,
            label = { Text(stringResource(R.string.music_search_hint)) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { }),
            colors = musicFieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SegmentChip(stringResource(R.string.music_mode_tracks), state.libraryMode == LibraryMode.TRACKS) { viewModel.setLibraryMode(LibraryMode.TRACKS) }
            SegmentChip(stringResource(R.string.music_mode_albums), state.libraryMode == LibraryMode.ALBUMS) { viewModel.setLibraryMode(LibraryMode.ALBUMS) }
            SegmentChip(stringResource(R.string.music_mode_artists), state.libraryMode == LibraryMode.ARTISTS) { viewModel.setLibraryMode(LibraryMode.ARTISTS) }
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                state.libraryLoading && state.tracks.isEmpty() ->
                    CircularProgressIndicator(Modifier.align(Alignment.Center), color = WeatherText)
                state.tracks.isEmpty() -> ScopeEmptyHint(stringResource(R.string.music_empty_library))
                filtered.isEmpty() -> ScopeEmptyHint(stringResource(R.string.music_empty_search))
                else -> when (state.libraryMode) {
                    LibraryMode.TRACKS -> LazyColumn(Modifier.fillMaxSize()) {
                        items(filtered.size, key = { filtered[it].id }) { index ->
                            val track = filtered[index]
                            TrackRow(
                                track = track,
                                highlight = state.player.track?.id == track.id,
                                favorite = track.id in state.data.favoriteTracks,
                                onClick = { play(filtered, index) },
                                menu = { dismiss -> TrackMenu(track, state, viewModel, dismiss, onDialog, null) },
                            )
                        }
                    }
                    LibraryMode.ALBUMS -> AlbumList(filtered, viewModel)
                    LibraryMode.ARTISTS -> ArtistList(filtered, viewModel)
                }
            }
        }
    }
}

@Composable
private fun AlbumList(tracks: List<Track>, viewModel: MusicViewModel) {
    val albums = remember(tracks) { MusicLogic.albums(tracks) }
    val firstTrack = remember(tracks) { tracks.groupBy { it.albumId }.mapValues { it.value.first() } }
    if (albums.isEmpty()) {
        ScopeEmptyHint(stringResource(R.string.music_empty_search))
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(albums, key = { it.id }) { album ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { viewModel.openAlbum(album.id) }.padding(horizontal = 4.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MusicCover(firstTrack[album.id], null, false, 192, Modifier.size(56.dp), corner = 10.dp)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(album.title, color = WeatherText, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        album.artist.ifEmpty { stringResource(R.string.music_various_artists) },
                        color = WeatherTextMuted,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(stringResource(R.string.music_track_count, album.trackCount), color = WeatherTextMuted, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun ArtistList(tracks: List<Track>, viewModel: MusicViewModel) {
    val artists = remember(tracks) { MusicLogic.artists(tracks) }
    if (artists.isEmpty()) {
        ScopeEmptyHint(stringResource(R.string.music_empty_search))
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(artists, key = { it.name }) { artist ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { viewModel.openArtist(artist.name) }.padding(horizontal = 4.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(24.dp)).background(Color(0x22FFFFFF)), contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.ic_person), null, tint = WeatherTextMuted)
                }
                Text(
                    artist.name,
                    color = WeatherText,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                )
                Text(stringResource(R.string.music_track_count, artist.trackCount), color = WeatherTextMuted, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

// endregion

// region Listen --------------------------------------------------------------------------------

@Composable
private fun ListsTab(
    state: MusicUiState,
    viewModel: MusicViewModel,
    play: (List<Track>, Int) -> Unit,
    playShuffled: (List<Track>) -> Unit,
    onDialog: (MusicDialog) -> Unit,
) {
    val playlist = state.openPlaylist?.let { id -> state.data.playlists.firstOrNull { it.id == id } }
    when {
        state.showFavorites -> {
            val tracks = remember(state.data.favoriteTracks, state.tracks) { MusicLogic.resolve(state.data.favoriteTracks, state.tracks) }
            TrackListPage(stringResource(R.string.music_favorites), "", tracks, state, viewModel, play, playShuffled, onDialog)
        }
        playlist != null -> {
            val tracks = remember(playlist.trackIds, state.tracks) { MusicLogic.resolve(playlist.trackIds, state.tracks) }
            TrackListPage(
                title = playlist.name,
                subtitle = stringResource(R.string.music_track_count, tracks.size),
                tracks = tracks,
                state = state,
                viewModel = viewModel,
                play = play,
                playShuffled = playShuffled,
                onDialog = onDialog,
                headerActions = {
                    PodcastTextButton(stringResource(R.string.music_rename)) { onDialog(MusicDialog.RenameList(playlist.id, playlist.name)) }
                    PodcastTextButton(stringResource(R.string.music_delete)) { onDialog(MusicDialog.DeleteList(playlist.id, playlist.name)) }
                },
                listActions = { track ->
                    ListActions(
                        onRemove = { viewModel.removeFromPlaylist(playlist.id, track.id) },
                        onUp = { viewModel.moveInPlaylist(playlist.id, track.id, -1) },
                        onDown = { viewModel.moveInPlaylist(playlist.id, track.id, 1) },
                    )
                },
            )
        }
        else -> LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item(key = "favorites") {
                ListRow(R.drawable.ic_favorite, stringResource(R.string.music_favorites), stringResource(R.string.music_track_count, state.data.favoriteTracks.size)) {
                    viewModel.openFavorites()
                }
            }
            items(state.data.playlists, key = { it.id }) { list ->
                ListRow(R.drawable.ic_queue_music, list.name, stringResource(R.string.music_track_count, list.trackIds.size)) { viewModel.openPlaylist(list.id) }
            }
            item(key = "new") {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    PodcastButton(stringResource(R.string.music_new_list), { onDialog(MusicDialog.NewList) })
                }
            }
            if (state.data.playlists.isEmpty()) item(key = "empty") { ScopeEmptyHint(stringResource(R.string.music_empty_lists)) }
        }
    }
}

@Composable
private fun ListRow(icon: Int, title: String, count: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().glass(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(icon), null, tint = if (icon == R.drawable.ic_favorite) MusicAccent else WeatherText)
        Text(title, color = WeatherText, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 12.dp))
        Text(count, color = WeatherTextMuted, style = MaterialTheme.typography.labelMedium)
    }
}

// endregion

// region Dialoge -------------------------------------------------------------------------------

@Composable
private fun MusicDialogs(dialog: MusicDialog?, state: MusicUiState, viewModel: MusicViewModel, dismiss: () -> Unit) {
    when (dialog) {
        null -> Unit
        MusicDialog.NewList -> TextInputDialog(
            title = stringResource(R.string.music_new_list),
            label = stringResource(R.string.music_list_name),
            initial = "",
            confirm = stringResource(R.string.music_create),
            onDismiss = dismiss,
        ) { name ->
            if (viewModel.createPlaylist(name) != null) dismiss()
        }
        is MusicDialog.RenameList -> TextInputDialog(
            title = stringResource(R.string.music_rename),
            label = stringResource(R.string.music_list_name),
            initial = dialog.name,
            confirm = stringResource(R.string.music_save),
            onDismiss = dismiss,
        ) { name ->
            viewModel.renamePlaylist(dialog.id, name)
            dismiss()
        }
        is MusicDialog.DeleteList -> AlertDialog(
            onDismissRequest = dismiss,
            containerColor = Color(0xFF141A22),
            titleContentColor = WeatherText,
            textContentColor = WeatherText,
            title = { Text(stringResource(R.string.music_delete_list_title, dialog.name)) },
            text = { Text(stringResource(R.string.music_delete_list_text)) },
            confirmButton = {
                TextButton(onClick = { viewModel.deletePlaylist(dialog.id); dismiss() }, colors = ButtonDefaults.textButtonColors(contentColor = WeatherText)) {
                    Text(stringResource(R.string.music_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = dismiss, colors = ButtonDefaults.textButtonColors(contentColor = WeatherText)) { Text(stringResource(R.string.music_cancel)) }
            },
        )
        is MusicDialog.PickList -> PlaylistPickerDialog(dialog.tracks, state, viewModel, dismiss)
        MusicDialog.AddStation -> AddStationDialog(viewModel, dismiss)
        is MusicDialog.RenameStation -> TextInputDialog(
            title = stringResource(R.string.music_rename),
            label = stringResource(R.string.music_radio_name),
            initial = dialog.name,
            confirm = stringResource(R.string.music_save),
            onDismiss = dismiss,
        ) { name ->
            viewModel.renameStation(dialog.id, name)
            dismiss()
        }
    }
}

/** Einzeiliges Eingabefeld im Dialog; [onConfirm] erhält den Text und entscheidet selbst, ob der Dialog schließt. */
@Composable
private fun TextInputDialog(
    title: String,
    label: String,
    initial: String,
    confirm: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF141A22),
        titleContentColor = WeatherText,
        textContentColor = WeatherText,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(MusicLogic.MAX_NAME) },
                singleLine = true,
                label = { Text(label) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (text.isNotBlank()) onConfirm(text) }),
                colors = musicFieldColors(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank(), colors = ButtonDefaults.textButtonColors(contentColor = WeatherText)) { Text(confirm) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, colors = ButtonDefaults.textButtonColors(contentColor = WeatherText)) { Text(stringResource(R.string.music_cancel)) }
        },
    )
}

/** Auswahl der Liste, zu der Lieder hinzugefügt werden, mit der Möglichkeit, direkt eine neue anzulegen. */
@Composable
private fun PlaylistPickerDialog(tracks: List<Track>, state: MusicUiState, viewModel: MusicViewModel, dismiss: () -> Unit) {
    var newName by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = dismiss,
        containerColor = Color(0xFF141A22),
        titleContentColor = WeatherText,
        textContentColor = WeatherText,
        title = { Text(stringResource(R.string.music_choose_list)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                state.data.playlists.forEach { list ->
                    Text(
                        list.name,
                        color = WeatherText,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable {
                            viewModel.addToPlaylist(list.id, tracks)
                            dismiss()
                        }.padding(vertical = 10.dp, horizontal = 6.dp),
                    )
                }
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it.take(MusicLogic.MAX_NAME) },
                    singleLine = true,
                    label = { Text(stringResource(R.string.music_new_list)) },
                    colors = musicFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (viewModel.createPlaylist(newName, tracks.map(Track::id)) != null) dismiss() },
                enabled = newName.isNotBlank(),
                colors = ButtonDefaults.textButtonColors(contentColor = WeatherText),
            ) { Text(stringResource(R.string.music_create)) }
        },
        dismissButton = {
            TextButton(onClick = dismiss, colors = ButtonDefaults.textButtonColors(contentColor = WeatherText)) { Text(stringResource(R.string.music_cancel)) }
        },
    )
}

/** Eigenen Sender per Stream-Adresse hinzufügen (ohne Verzeichnis, ohne Zustimmung zur Sendersuche nötig). */
@Composable
private fun AddStationDialog(viewModel: MusicViewModel, dismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = dismiss,
        containerColor = Color(0xFF141A22),
        titleContentColor = WeatherText,
        textContentColor = WeatherText,
        title = { Text(stringResource(R.string.music_radio_add)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(MusicLogic.MAX_NAME) },
                    singleLine = true,
                    label = { Text(stringResource(R.string.music_radio_name)) },
                    colors = musicFieldColors(),
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it.take(2048) },
                    singleLine = true,
                    label = { Text(stringResource(R.string.music_radio_url)) },
                    keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri, imeAction = ImeAction.Done),
                    colors = musicFieldColors(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (viewModel.addManualStation(name, url)) dismiss() },
                enabled = url.isNotBlank(),
                colors = ButtonDefaults.textButtonColors(contentColor = WeatherText),
            ) { Text(stringResource(R.string.music_save)) }
        },
        dismissButton = {
            TextButton(onClick = dismiss, colors = ButtonDefaults.textButtonColors(contentColor = WeatherText)) { Text(stringResource(R.string.music_cancel)) }
        },
    )
}

// endregion
