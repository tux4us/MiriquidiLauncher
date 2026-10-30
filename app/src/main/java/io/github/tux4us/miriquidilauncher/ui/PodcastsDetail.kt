// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.ui

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.tux4us.miriquidilauncher.R
import io.github.tux4us.miriquidilauncher.feed.FeedHtml
import io.github.tux4us.miriquidilauncher.feed.ReaderColors
import io.github.tux4us.miriquidilauncher.podcasts.Episode
import io.github.tux4us.miriquidilauncher.podcasts.PodcastLogic
import io.github.tux4us.miriquidilauncher.podcasts.PodcastsUiState
import io.github.tux4us.miriquidilauncher.podcasts.PodcastsViewModel
import java.util.Locale

// Die Shownotes sind eine geschlossene, dunkle Fläche; sie ist unabhängig vom Hintergrundbild gut lesbar.
private val NotesBackground = Color(0xFF141A22)
private val NotesColors = ReaderColors(
    background = "#141a22",
    text = "#e8eaed",
    muted = "#a9b0b8",
    link = "#8ab4f8",
)

/** Folge mit Aktionen (Abspielen, Favorit, Download, Gehört) und den Shownotes als HTML ohne JavaScript. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun EpisodeDetail(
    state: PodcastsUiState,
    episode: Episode,
    viewModel: PodcastsViewModel,
    onPlay: (Episode) -> Unit,
) {
    val context = LocalContext.current
    val locale = remember { Locale.getDefault() }
    val untitled = stringResource(R.string.podcasts_untitled)
    val data = state.data
    val favorite = PodcastLogic.isFavorite(data, episode.id)
    val downloaded = data.downloads.containsKey(episode.id)
    val progress = state.downloading[episode.id]
    val played = episode.id in data.played
    val current = state.player.episode?.id == episode.id
    val playing = current && state.player.playing

    val document = remember(episode.id) {
        val meta = listOfNotNull(
            episode.podcastTitle.ifBlank { null },
            formatPublished(episode.publishedSeconds, locale).ifEmpty { null },
            if (episode.durationSeconds > 0) PodcastLogic.formatClock(episode.durationSeconds.toLong()) else null,
        ).joinToString(" · ")
        FeedHtml.readerDocument(
            title = episode.title.ifBlank { untitled },
            meta = meta,
            bodyHtml = episode.notesHtml,
            colors = NotesColors,
            loadImages = true,
        )
    }
    val baseUrl = episode.link?.takeIf { it.startsWith("https://", ignoreCase = true) }
    val webViewHolder = remember { arrayOfNulls<WebView>(1) }
    DisposableEffect(Unit) {
        onDispose {
            webViewHolder[0]?.apply {
                stopLoading()
                (parent as? ViewGroup)?.removeView(this)
                destroy()
            }
            webViewHolder[0] = null
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PodcastTextButton(stringResource(R.string.podcasts_back), viewModel::back)
            Text(
                episode.podcastTitle,
                color = WeatherTextMuted,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PodcastButton(stringResource(if (playing) R.string.podcasts_pause else R.string.podcasts_play), { onPlay(episode) })
            PodcastTextButton(
                stringResource(if (favorite) R.string.podcasts_favorite_remove else R.string.podcasts_favorite_add),
            ) { viewModel.toggleFavorite(episode) }
            when {
                progress != null -> PodcastTextButton(
                    if (progress >= 0) stringResource(R.string.podcasts_downloading, progress) else stringResource(R.string.podcasts_downloading_unknown),
                ) { viewModel.cancelDownload(episode) }
                downloaded -> PodcastTextButton(stringResource(R.string.podcasts_download_delete)) { viewModel.deleteDownload(episode) }
                else -> PodcastTextButton(stringResource(R.string.podcasts_download)) { viewModel.download(episode) }
            }
            PodcastTextButton(
                stringResource(if (played) R.string.podcasts_mark_unplayed else R.string.podcasts_mark_played),
            ) { viewModel.setPlayed(episode, !played) }
            episode.link?.let { link ->
                PodcastTextButton(stringResource(R.string.podcasts_open_browser)) { openPodcastLink(context, link) }
            }
        }
        AndroidView(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp),
            factory = { ctx ->
                WebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    setBackgroundColor(NotesBackground.toArgb())
                    settings.apply {
                        // Die Shownotes sind fremder Inhalt: kein JavaScript, kein Zugriff auf Dateien oder Speicher.
                        javaScriptEnabled = false
                        allowFileAccess = false
                        allowContentAccess = false
                        domStorageEnabled = false
                        javaScriptCanOpenWindowsAutomatically = false
                        setSupportMultipleWindows(false)
                        setSupportZoom(false)
                        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        cacheMode = WebSettings.LOAD_DEFAULT
                    }
                    // Links öffnen im Browser; die Ansicht navigiert nie selbst weg.
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            if (request.isForMainFrame) openPodcastLink(view.context, request.url.toString())
                            return true
                        }
                    }
                    webViewHolder[0] = this
                }
            },
            update = { view ->
                if (view.tag != document) {
                    view.tag = document
                    view.loadDataWithBaseURL(baseUrl, document, "text/html", "utf-8", null)
                }
            },
        )
    }
}

/** Player-Leiste am unteren Rand: Position, Springen, Wiedergabe, Geschwindigkeit. */
@Composable
internal fun PlayerBar(state: PodcastsUiState, viewModel: PodcastsViewModel) {
    val player = state.player
    val episode = player.episode ?: return
    var dragging by remember { mutableStateOf<Float?>(null) }
    val duration = player.durationMs.coerceAtLeast(1L)
    val shown = dragging ?: (player.positionMs.toFloat() / duration).coerceIn(0f, 1f)
    Column(
        Modifier.fillMaxWidth().padding(top = 8.dp).glass(RoundedCornerShape(20.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(episode.title, color = WeatherText, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(episode.podcastTitle, color = WeatherTextMuted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Slider(
            value = shown,
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { viewModel.seekTo((it * duration).toLong()) }
                dragging = null
            },
            enabled = player.durationMs > 0,
            colors = SliderDefaults.colors(
                thumbColor = WeatherText,
                activeTrackColor = WeatherText,
                inactiveTrackColor = Color(0x44FFFFFF),
                disabledThumbColor = WeatherTextMuted,
                disabledActiveTrackColor = WeatherTextMuted,
                disabledInactiveTrackColor = Color(0x22FFFFFF),
            ),
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                PodcastLogic.formatClock(((dragging?.times(duration)?.toLong()) ?: player.positionMs) / 1000) +
                    " / " + PodcastLogic.formatClock(player.durationMs / 1000),
                color = WeatherTextMuted,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.weight(1f),
            )
            PodcastTextButton(stringResource(R.string.podcasts_rewind)) { viewModel.skip(-10_000) }
            IconButton(onClick = viewModel::togglePlay) {
                Icon(
                    painter = painterResource(if (player.playing) R.drawable.ic_pause else R.drawable.ic_play),
                    contentDescription = stringResource(if (player.playing) R.string.podcasts_pause else R.string.podcasts_play),
                    tint = WeatherText,
                    modifier = Modifier.size(32.dp),
                )
            }
            PodcastTextButton(stringResource(R.string.podcasts_forward)) { viewModel.skip(30_000) }
            PodcastTextButton(stringResource(R.string.podcasts_speed, speedLabel(state.data.speed))) { viewModel.cycleSpeed() }
            IconButton(onClick = viewModel::stopPlayback) {
                Icon(painterResource(R.drawable.ic_close), stringResource(R.string.podcasts_close_player), tint = WeatherText)
            }
        }
    }
}

private fun speedLabel(speed: Float): String = if (speed % 1f == 0f) speed.toInt().toString() else speed.toString()

/** Öffnet [url] im Browser, sofern es eine Web- oder Mail-Adresse ist. */
private fun openPodcastLink(context: Context, url: String) {
    val uri = Uri.parse(url)
    if (uri.scheme?.lowercase(Locale.ROOT) !in setOf("http", "https", "mailto")) return
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (ignored: ActivityNotFoundException) {
        Toast.makeText(context, context.getString(R.string.launch_failed), Toast.LENGTH_SHORT).show()
    }
}
