// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.tux4us.miriquidilauncher.R
import io.github.tux4us.miriquidilauncher.feed.FeedImages
import io.github.tux4us.miriquidilauncher.podcasts.Episode
import io.github.tux4us.miriquidilauncher.podcasts.NoticeKind
import io.github.tux4us.miriquidilauncher.podcasts.Podcast
import io.github.tux4us.miriquidilauncher.podcasts.PodcastHit
import io.github.tux4us.miriquidilauncher.podcasts.PodcastLogic
import io.github.tux4us.miriquidilauncher.podcasts.PodcastNotice
import io.github.tux4us.miriquidilauncher.podcasts.PodcastTab
import io.github.tux4us.miriquidilauncher.podcasts.PodcastsUiState
import io.github.tux4us.miriquidilauncher.podcasts.PodcastsViewModel
import io.github.tux4us.miriquidilauncher.podcasts.SearchProvider
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

private val PodcastError = Color(0xFFFFB4B4)

/**
 * Podcast-Seite: Abos, Favoriten, Downloads und Suche, Folgenliste mit Shownotes (HTML ohne JavaScript) und
 * Player. [active] ist `true`, solange die Seite sichtbar ist; dann werden die Abos aktualisiert.
 */
@Composable
fun PodcastsScreen(viewModel: PodcastsViewModel, active: Boolean, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val exportTitle = stringResource(R.string.podcasts_export_title)

    LaunchedEffect(active) { if (active) viewModel.onActive() }
    // Zurück schließt zuerst die Shownotes, dann die Folgenliste; erst danach geht es zur Startseite.
    BackHandler(enabled = active && (state.openEpisode != null || state.selectedPodcast != null)) { viewModel.back() }

    // Die Systemdialoge zur Dateiwahl brauchen keine Berechtigung; gelesen oder geschrieben wird nur die gewählte Datei.
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importFile(uri)
    }
    val opmlSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/x-opml")) { uri ->
        if (uri != null) viewModel.exportTo(uri, html = false, title = exportTitle)
    }
    val htmlSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/html")) { uri ->
        if (uri != null) viewModel.exportTo(uri, html = true, title = exportTitle)
    }
    // Ab Android 13 ist die Benachrichtigung des Wiedergabedienstes eine Berechtigung; sie wird einmal angefragt.
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    var askedNotification by rememberSaveable { mutableStateOf(false) }
    val play: (Episode) -> Unit = { episode ->
        if (Build.VERSION.SDK_INT >= 33 && !askedNotification &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askedNotification = true
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        viewModel.play(episode)
    }

    ScopePage(title = stringResource(R.string.scope_podcasts), modifier = modifier) {
        Column(Modifier.fillMaxSize()) {
            state.notice?.let { NoticeBanner(it, viewModel::dismissNotice) }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val episode = state.openEpisode
                val podcastUrl = state.selectedPodcast
                when {
                    !state.loaded -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = WeatherText)
                    episode != null -> EpisodeDetail(state, episode, viewModel, play)
                    podcastUrl != null -> EpisodeListPage(state, podcastUrl, viewModel, play)
                    else -> {
                        Column(Modifier.fillMaxSize()) {
                            TabRow(state.tab, viewModel::selectTab)
                            Spacer(Modifier.height(8.dp))
                            when (state.tab) {
                                PodcastTab.SUBSCRIPTIONS -> SubscriptionsTab(
                                    state = state,
                                    viewModel = viewModel,
                                    onImport = { importPicker.launch(arrayOf("*/*")) },
                                    onExportOpml = { opmlSaver.launch("podcasts.opml") },
                                    onExportHtml = { htmlSaver.launch("podcasts.html") },
                                )
                                PodcastTab.FAVORITES -> EpisodeList(
                                    episodes = state.data.favorites,
                                    emptyText = stringResource(R.string.podcasts_empty_favorites),
                                    state = state,
                                    viewModel = viewModel,
                                    onPlay = play,
                                )
                                PodcastTab.DOWNLOADS -> EpisodeList(
                                    episodes = state.data.saved,
                                    emptyText = stringResource(R.string.podcasts_empty_downloads),
                                    state = state,
                                    viewModel = viewModel,
                                    onPlay = play,
                                )
                                PodcastTab.SEARCH -> SearchTab(state, viewModel)
                            }
                        }
                    }
                }
            }
            if (state.player.episode != null) {
                PlayerBar(state, viewModel)
            }
            Spacer(Modifier.height(ScopeBottomClearance))
        }
    }
}

// region Gemeinsame Bausteine ------------------------------------------------------------------

@Composable
private fun TabRow(selected: PodcastTab, onSelect: (PodcastTab) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SegmentChip(stringResource(R.string.podcasts_tab_subs), selected == PodcastTab.SUBSCRIPTIONS) { onSelect(PodcastTab.SUBSCRIPTIONS) }
        SegmentChip(stringResource(R.string.podcasts_tab_favorites), selected == PodcastTab.FAVORITES) { onSelect(PodcastTab.FAVORITES) }
        SegmentChip(stringResource(R.string.podcasts_tab_downloads), selected == PodcastTab.DOWNLOADS) { onSelect(PodcastTab.DOWNLOADS) }
        SegmentChip(stringResource(R.string.podcasts_tab_search), selected == PodcastTab.SEARCH) { onSelect(PodcastTab.SEARCH) }
    }
}

@Composable
private fun NoticeBanner(notice: PodcastNotice, onDismiss: () -> Unit) {
    val text = when (notice.kind) {
        NoticeKind.NETWORK -> stringResource(R.string.podcasts_notice_network)
        NoticeKind.NOT_A_FEED -> stringResource(R.string.podcasts_notice_not_feed)
        NoticeKind.LIMIT -> stringResource(R.string.podcasts_notice_limit)
        NoticeKind.ADDED -> stringResource(R.string.podcasts_notice_added)
        NoticeKind.IMPORTED -> stringResource(R.string.podcasts_notice_imported, notice.a, notice.b)
        NoticeKind.IMPORT_FAILED -> stringResource(R.string.podcasts_notice_import_failed)
        NoticeKind.EXPORTED -> stringResource(R.string.podcasts_notice_exported, notice.a)
        NoticeKind.EXPORT_FAILED -> stringResource(R.string.podcasts_notice_export_failed)
        NoticeKind.DOWNLOAD_FAILED -> stringResource(R.string.podcasts_notice_download_failed)
        NoticeKind.PLAYBACK_FAILED -> stringResource(R.string.podcasts_notice_playback_failed)
    }
    val failed = notice.kind in setOf(
        NoticeKind.NETWORK, NoticeKind.NOT_A_FEED, NoticeKind.LIMIT, NoticeKind.IMPORT_FAILED,
        NoticeKind.EXPORT_FAILED, NoticeKind.DOWNLOAD_FAILED, NoticeKind.PLAYBACK_FAILED,
    )
    Row(
        Modifier.fillMaxWidth().padding(bottom = 8.dp).glass(RoundedCornerShape(14.dp)).padding(start = 14.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = if (failed) PodcastError else WeatherText, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        TextButton(onClick = onDismiss, colors = ButtonDefaults.textButtonColors(contentColor = WeatherText)) {
            Text(stringResource(R.string.podcasts_notice_dismiss))
        }
    }
}

@Composable
internal fun PodcastCover(url: String?, modifier: Modifier = Modifier) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, url) { value = url?.let { FeedImages.load(it) } }
    Box(modifier.clip(RoundedCornerShape(10.dp)).background(Color(0x22FFFFFF))) {
        bitmap?.let {
            Image(bitmap = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun podcastFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = WeatherText,
    unfocusedTextColor = WeatherText,
    focusedBorderColor = WeatherText,
    unfocusedBorderColor = WeatherTextMuted,
    focusedLabelColor = WeatherText,
    unfocusedLabelColor = WeatherTextMuted,
    cursorColor = WeatherText,
)

@Composable
internal fun PodcastButton(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = WeatherText,
            contentColor = Color.Black,
            disabledContainerColor = Color(0x33FFFFFF),
            disabledContentColor = WeatherTextMuted,
        ),
    ) { Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis) }
}

@Composable
internal fun PodcastTextButton(text: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, colors = ButtonDefaults.textButtonColors(contentColor = WeatherText)) { Text(text) }
}

internal fun formatPublished(seconds: Long, locale: Locale): String =
    if (seconds <= 0L) {
        ""
    } else {
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
            .format(Instant.ofEpochSecond(seconds).atZone(ZoneId.systemDefault()))
    }

// endregion

// region Abos ----------------------------------------------------------------------------------

@Composable
private fun SubscriptionsTab(
    state: PodcastsUiState,
    viewModel: PodcastsViewModel,
    onImport: () -> Unit,
    onExportOpml: () -> Unit,
    onExportHtml: () -> Unit,
) {
    var address by rememberSaveable { mutableStateOf("") }
    val submit = {
        if (address.isNotBlank()) {
            viewModel.addByUrl(address)
            address = ""
        }
    }
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(20.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = address,
                        onValueChange = { address = it },
                        singleLine = true,
                        label = { Text(stringResource(R.string.podcasts_add_hint)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { submit() }),
                        colors = podcastFieldColors(),
                        modifier = Modifier.weight(1f),
                    )
                    PodcastButton(stringResource(R.string.podcasts_add), submit, enabled = address.isNotBlank() && !state.working)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    PodcastTextButton(stringResource(R.string.podcasts_import), onImport)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    PodcastTextButton(stringResource(R.string.podcasts_export_opml), onExportOpml)
                    PodcastTextButton(stringResource(R.string.podcasts_export_html), onExportHtml)
                }
                if (state.working || state.refreshing) {
                    CircularProgressIndicator(Modifier.size(20.dp), color = WeatherText, strokeWidth = 2.dp)
                }
            }
        }
        if (state.data.subscriptions.isEmpty()) {
            item { ScopeEmptyHint(stringResource(R.string.podcasts_empty_subs)) }
        }
        items(state.data.subscriptions, key = { it.feedUrl }) { podcast ->
            PodcastRow(podcast) { viewModel.openPodcast(podcast.feedUrl) }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun PodcastRow(podcast: Podcast, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().glass(RoundedCornerShape(20.dp)).clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PodcastCover(podcast.imageUrl, Modifier.size(56.dp))
        Column(Modifier.weight(1f)) {
            Text(podcast.title, color = WeatherText, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (podcast.author.isNotBlank()) {
                Text(podcast.author, color = WeatherTextMuted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

// endregion

// region Folgen --------------------------------------------------------------------------------

@Composable
private fun EpisodeListPage(state: PodcastsUiState, podcastUrl: String, viewModel: PodcastsViewModel, onPlay: (Episode) -> Unit) {
    val podcast = state.data.subscriptions.firstOrNull { it.feedUrl == podcastUrl }
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PodcastTextButton(stringResource(R.string.podcasts_back), viewModel::back)
            Text(
                podcast?.title.orEmpty(),
                color = WeatherTextMuted,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            PodcastTextButton(stringResource(R.string.podcasts_refresh)) { viewModel.refresh(podcastUrl) }
            PodcastTextButton(stringResource(R.string.podcasts_unsubscribe)) { viewModel.unsubscribe(podcastUrl) }
            if (state.refreshing) CircularProgressIndicator(Modifier.size(20.dp), color = WeatherText, strokeWidth = 2.dp)
        }
        EpisodeList(
            episodes = state.data.episodes[podcastUrl].orEmpty(),
            emptyText = stringResource(R.string.podcasts_empty_episodes),
            state = state,
            viewModel = viewModel,
            onPlay = onPlay,
        )
    }
}

@Composable
private fun EpisodeList(
    episodes: List<Episode>,
    emptyText: String,
    state: PodcastsUiState,
    viewModel: PodcastsViewModel,
    onPlay: (Episode) -> Unit,
) {
    val locale = remember { Locale.getDefault() }
    if (episodes.isEmpty()) {
        ScopeEmptyHint(emptyText)
        return
    }
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(episodes, key = { it.id }) { episode ->
            EpisodeRow(episode, state, locale, onOpen = { viewModel.openEpisode(episode) }, onPlay = { onPlay(episode) })
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun EpisodeRow(episode: Episode, state: PodcastsUiState, locale: Locale, onOpen: () -> Unit, onPlay: () -> Unit) {
    val played = episode.id in state.data.played
    val favorite = PodcastLogic.isFavorite(state.data, episode.id)
    val downloaded = state.data.downloads.containsKey(episode.id)
    val current = state.player.episode?.id == episode.id
    val resume = state.data.positionsMs[episode.id]
    val meta = buildList {
        add(episode.podcastTitle)
        formatPublished(episode.publishedSeconds, locale).takeIf { it.isNotEmpty() }?.let { add(it) }
        if (episode.durationSeconds > 0) add(PodcastLogic.formatClock(episode.durationSeconds.toLong()))
        if (resume != null) add("▶ " + PodcastLogic.formatClock(resume / 1000))
    }.filter { it.isNotBlank() }.joinToString(" · ")
    Row(
        Modifier.fillMaxWidth().glass(RoundedCornerShape(20.dp)).clickable(onClick = onOpen).padding(start = 14.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                episode.title.ifBlank { stringResource(R.string.podcasts_untitled) },
                color = if (played) WeatherTextMuted else WeatherText,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(meta, color = WeatherTextMuted, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val badges = listOfNotNull(
                if (favorite) "★" else null,
                if (downloaded) "⬇" else null,
                if (played) stringResource(R.string.podcasts_played) else null,
            ).joinToString("  ")
            if (badges.isNotEmpty()) Text(badges, color = WeatherTextMuted, style = MaterialTheme.typography.labelSmall)
        }
        androidx.compose.material3.IconButton(onClick = onPlay) {
            androidx.compose.material3.Icon(
                painter = androidx.compose.ui.res.painterResource(if (current && state.player.playing) R.drawable.ic_pause else R.drawable.ic_play),
                contentDescription = stringResource(if (current && state.player.playing) R.string.podcasts_pause else R.string.podcasts_play),
                tint = WeatherText,
            )
        }
    }
}

// endregion

// region Suche ---------------------------------------------------------------------------------

@Composable
private fun SearchTab(state: PodcastsUiState, viewModel: PodcastsViewModel) {
    val search = { viewModel.search() }
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(20.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = state.query,
                        onValueChange = viewModel::setQuery,
                        singleLine = true,
                        label = { Text(stringResource(R.string.podcasts_search_hint)) },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { search() }),
                        colors = podcastFieldColors(),
                        modifier = Modifier.weight(1f),
                    )
                    PodcastButton(stringResource(R.string.podcasts_search_button), search, enabled = state.query.isNotBlank() && !state.searching)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.podcasts_provider), color = WeatherTextMuted, style = MaterialTheme.typography.labelLarge)
                    SegmentChip(stringResource(R.string.podcasts_provider_itunes), state.data.provider == SearchProvider.ITUNES) {
                        viewModel.setProvider(SearchProvider.ITUNES)
                    }
                    SegmentChip(stringResource(R.string.podcasts_provider_fyyd), state.data.provider == SearchProvider.FYYD) {
                        viewModel.setProvider(SearchProvider.FYYD)
                    }
                }
                Text(stringResource(R.string.podcasts_provider_note), color = WeatherTextMuted, style = MaterialTheme.typography.bodySmall)
                if (state.searching) CircularProgressIndicator(Modifier.size(20.dp), color = WeatherText, strokeWidth = 2.dp)
            }
        }
        if (state.searched && state.hits.isEmpty() && !state.searching) {
            item { ScopeEmptyHint(stringResource(R.string.podcasts_empty_search)) }
        }
        items(state.hits, key = { it.feedUrl }) { hit ->
            HitRow(hit, subscribed = state.data.subscriptions.any { it.feedUrl.equals(hit.feedUrl, ignoreCase = true) }) {
                viewModel.subscribe(hit)
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun HitRow(hit: PodcastHit, subscribed: Boolean, onSubscribe: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().glass(RoundedCornerShape(20.dp)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PodcastCover(hit.imageUrl, Modifier.size(56.dp))
        Column(Modifier.weight(1f)) {
            Text(hit.title, color = WeatherText, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (hit.author.isNotBlank()) {
                Text(hit.author, color = WeatherTextMuted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (subscribed) {
            Text(stringResource(R.string.podcasts_subscribed), color = WeatherTextMuted, style = MaterialTheme.typography.labelLarge)
        } else {
            PodcastButton(stringResource(R.string.podcasts_subscribe), onSubscribe)
        }
    }
}

// endregion
