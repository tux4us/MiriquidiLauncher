// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.tux4us.miriquidilauncher.R
import io.github.tux4us.miriquidilauncher.feed.FeedImages
import io.github.tux4us.miriquidilauncher.music.CoverColors
import io.github.tux4us.miriquidilauncher.music.CoverPalette
import io.github.tux4us.miriquidilauncher.music.MusicLibrary
import io.github.tux4us.miriquidilauncher.music.MusicLogic
import io.github.tux4us.miriquidilauncher.music.MusicUiState
import io.github.tux4us.miriquidilauncher.music.MusicViewModel
import io.github.tux4us.miriquidilauncher.music.RepeatMode
import io.github.tux4us.miriquidilauncher.music.Station
import io.github.tux4us.miriquidilauncher.music.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Akzentfarbe, solange kein Cover ausgewertet ist, und für markierte Zeilen in Listen. */
internal val MusicAccent = Color(CoverPalette.DEFAULT.accent)

private val MusicIconIdle = Color(0xCCFFFFFF)
private val MusicIconOff = Color(0x55FFFFFF)

/**
 * Cover eines Liedes bzw. Logo eines Senders. Fehlt das Bild, erscheint bei Liedern ein Notensymbol und bei Sendern die
 * Anfangsbuchstaben auf einer Farbfläche, die aus dem Namen gewonnen wird. Sender-Logos kommen nur bei [showLogos] aus dem Netz.
 */
@Composable
internal fun MusicCover(
    track: Track?,
    station: Station?,
    showLogos: Boolean,
    edgePx: Int,
    modifier: Modifier = Modifier,
    corner: Dp = 12.dp,
) {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(null, track?.id, station?.logoUrl, showLogos, edgePx) {
        value = when {
            track != null -> MusicLibrary.cover(context, track, edgePx)?.asImageBitmap()
            station != null && showLogos -> station.logoUrl?.let { FeedImages.load(it, edgePx) }
            else -> null
        }
    }
    val badge = remember(station?.name) { station?.let { CoverColors.forName(it.name) } ?: CoverPalette.DEFAULT }
    Box(
        modifier
            .clip(RoundedCornerShape(corner))
            .background(Brush.linearGradient(listOf(Color(badge.top), Color(badge.accent).copy(alpha = 0.55f)))),
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap
        when {
            image != null -> Image(bitmap = image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            station != null -> Text(
                CoverColors.initials(station.name),
                color = Color.White,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            else -> Icon(painterResource(R.drawable.ic_music), contentDescription = null, tint = Color(0x99FFFFFF), modifier = Modifier.fillMaxSize(0.5f))
        }
    }
}

/** Farben der Player-Ansicht, gewonnen aus dem Cover (bzw. dem Sender-Logo oder -Namen). */
@Composable
private fun rememberCoverPalette(track: Track?, station: Station?, showLogos: Boolean): CoverPalette {
    val context = LocalContext.current
    val palette by produceState(CoverPalette.DEFAULT, track?.id, station?.id, station?.logoUrl, showLogos) {
        value = try {
            when {
                track != null -> MusicLibrary.cover(context, track, 128)?.let { bitmap -> withContext(Dispatchers.Default) { MusicLibrary.palette(bitmap) } }
                    ?: CoverPalette.DEFAULT
                station != null -> {
                    val logo = if (showLogos) station.logoUrl?.let { FeedImages.load(it, 128) } else null
                    if (logo != null) withContext(Dispatchers.Default) { MusicLibrary.palette(logo.asAndroidBitmap()) } else CoverColors.forName(station.name)
                }
                else -> CoverPalette.DEFAULT
            }
        } catch (ignored: RuntimeException) {
            CoverPalette.DEFAULT
        }
    }
    return palette
}

/** Kompakte Leiste unten auf der Seite; Antippen öffnet die große Player-Ansicht. */
@Composable
internal fun MiniPlayer(state: MusicUiState, viewModel: MusicViewModel) {
    val player = state.player
    if (!player.active) return
    val fraction = if (!player.live && player.durationMs > 0) (player.positionMs.toFloat() / player.durationMs).coerceIn(0f, 1f) else 0f
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .glass(RoundedCornerShape(20.dp))
            .clickable(onClick = viewModel::openPlayer),
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            MusicCover(player.track, player.station, state.data.showLogos, 128, Modifier.size(48.dp), corner = 10.dp)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(player.title, color = WeatherText, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(player.subtitle, color = WeatherTextMuted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (player.buffering) CircularProgressIndicator(Modifier.size(20.dp), color = WeatherText, strokeWidth = 2.dp)
            IconButton(onClick = viewModel::togglePlay) {
                Icon(
                    painterResource(if (player.playing) R.drawable.ic_pause else R.drawable.ic_play),
                    contentDescription = stringResource(if (player.playing) R.string.music_pause else R.string.music_play),
                    tint = WeatherText,
                    modifier = Modifier.size(30.dp),
                )
            }
            IconButton(onClick = viewModel::stop) {
                Icon(painterResource(R.drawable.ic_close), stringResource(R.string.music_stop), tint = WeatherText)
            }
        }
        if (!player.live) {
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth().height(2.dp),
                color = WeatherText,
                trackColor = Color(0x33FFFFFF),
            )
        }
    }
}

/**
 * Große Player-Ansicht: Cover mit Leuchten in der Akzentfarbe, Farbverlauf aus dem Cover, Titel, Fortschritt und Steuerung.
 * Bei Radio entfallen Fortschritt, Springen, Zufall und Wiederholen; stattdessen erscheint die Marke „LIVE“.
 */
@Composable
internal fun NowPlayingView(state: MusicUiState, viewModel: MusicViewModel, onOpenEqualizer: () -> Unit) {
    val player = state.player
    val palette = rememberCoverPalette(player.track, player.station, state.data.showLogos)
    val accent = Color(palette.accent)
    val top by animateColorAsState(Color(palette.top), tween(700), label = "playerTop")
    val bottom by animateColorAsState(Color(palette.bottom), tween(700), label = "playerBottom")
    var dragging by remember { mutableStateOf<Float?>(null) }
    val duration = player.durationMs.coerceAtLeast(1L)
    val shown = dragging ?: (player.positionMs.toFloat() / duration).coerceIn(0f, 1f)

    Column(
        Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(28.dp))
            .background(Brush.verticalGradient(listOf(top, bottom)))
            .padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = viewModel::closePlayer) {
                Icon(painterResource(R.drawable.ic_arrow_down), stringResource(R.string.music_player_close), tint = Color.White)
            }
            Spacer(Modifier.weight(1f))
            if (player.live) {
                Text(
                    stringResource(R.string.music_live),
                    color = Color.Black,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(accent).padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onOpenEqualizer) {
                Icon(painterResource(R.drawable.ic_tune), stringResource(R.string.music_tab_eq), tint = if (state.data.eq.enabled) accent else MusicIconIdle)
            }
        }

        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val edge = minOf(maxWidth, maxHeight) * 0.9f
            MusicCover(
                track = player.track,
                station = player.station,
                showLogos = state.data.showLogos,
                edgePx = 640,
                modifier = Modifier
                    .size(edge)
                    .shadow(24.dp, RoundedCornerShape(24.dp), ambientColor = accent, spotColor = accent),
                corner = 24.dp,
            )
        }

        Spacer(Modifier.height(12.dp))
        Text(
            player.title,
            color = Color.White,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        Text(
            player.subtitle,
            color = Color(0xCCFFFFFF),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )

        if (player.live) {
            Box(Modifier.fillMaxWidth().height(56.dp), contentAlignment = Alignment.Center) {
                if (player.buffering) CircularProgressIndicator(Modifier.size(24.dp), color = accent, strokeWidth = 2.dp)
            }
        } else {
            Slider(
                value = shown,
                onValueChange = { dragging = it },
                onValueChangeFinished = {
                    dragging?.let { viewModel.seekTo((it * duration).toLong()) }
                    dragging = null
                },
                enabled = player.durationMs > 0,
                colors = SliderDefaults.colors(
                    thumbColor = accent,
                    activeTrackColor = accent,
                    inactiveTrackColor = Color(0x44FFFFFF),
                    disabledThumbColor = Color(0x88FFFFFF),
                    disabledActiveTrackColor = Color(0x88FFFFFF),
                    disabledInactiveTrackColor = Color(0x22FFFFFF),
                ),
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                val position = dragging?.let { (it * duration).toLong() } ?: player.positionMs
                Text(MusicLogic.formatTime(position), color = Color(0xCCFFFFFF), style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                Text("−" + MusicLogic.formatTime((player.durationMs - position).coerceAtLeast(0L)), color = Color(0xCCFFFFFF), style = MaterialTheme.typography.labelMedium)
            }
        }

        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            if (player.live) {
                IconButton(onClick = viewModel::stop) {
                    Icon(painterResource(R.drawable.ic_close), stringResource(R.string.music_stop), tint = MusicIconIdle, modifier = Modifier.size(28.dp))
                }
                PlayButton(player.playing, accent, viewModel::togglePlay)
                SaveStationButton(player.station, state, viewModel, accent)
            } else {
                IconButton(onClick = viewModel::toggleShuffle) {
                    Icon(
                        painterResource(R.drawable.ic_shuffle),
                        stringResource(R.string.music_shuffle),
                        tint = if (state.data.shuffle) accent else MusicIconIdle,
                        modifier = Modifier.size(26.dp),
                    )
                }
                IconButton(onClick = viewModel::previous) {
                    Icon(painterResource(R.drawable.ic_skip_previous), stringResource(R.string.music_previous), tint = Color.White, modifier = Modifier.size(36.dp))
                }
                PlayButton(player.playing, accent, viewModel::togglePlay)
                IconButton(onClick = viewModel::next, enabled = player.hasNext || state.data.repeat != RepeatMode.OFF) {
                    Icon(
                        painterResource(R.drawable.ic_skip_next),
                        stringResource(R.string.music_next),
                        tint = if (player.hasNext || state.data.repeat != RepeatMode.OFF) Color.White else MusicIconOff,
                        modifier = Modifier.size(36.dp),
                    )
                }
                IconButton(onClick = viewModel::cycleRepeat) {
                    Icon(
                        painterResource(if (state.data.repeat == RepeatMode.ONE) R.drawable.ic_repeat_one else R.drawable.ic_repeat),
                        stringResource(
                            when (state.data.repeat) {
                                RepeatMode.OFF -> R.string.music_repeat_off
                                RepeatMode.ALL -> R.string.music_repeat_all
                                RepeatMode.ONE -> R.string.music_repeat_one
                            },
                        ),
                        tint = if (state.data.repeat != RepeatMode.OFF) accent else MusicIconIdle,
                        modifier = Modifier.size(26.dp),
                    )
                }
            }
        }
        val track = player.track
        if (track != null) {
            val favorite = track.id in state.data.favoriteTracks
            IconButton(onClick = { viewModel.toggleFavorite(track) }) {
                Icon(
                    painterResource(if (favorite) R.drawable.ic_favorite else R.drawable.ic_favorite_border),
                    stringResource(if (favorite) R.string.music_menu_favorite_remove else R.string.music_menu_favorite_add),
                    tint = if (favorite) accent else MusicIconIdle,
                )
            }
        }
        Spacer(Modifier.height(ScopeBottomClearance))
    }
}

@Composable
private fun PlayButton(playing: Boolean, accent: Color, onClick: () -> Unit) {
    FilledIconButton(
        onClick = onClick,
        modifier = Modifier.size(72.dp),
        shape = CircleShape,
        colors = IconButtonDefaults.filledIconButtonColors(containerColor = accent, contentColor = Color.Black),
    ) {
        Icon(
            painterResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play),
            contentDescription = stringResource(if (playing) R.string.music_pause else R.string.music_play),
            modifier = Modifier.size(40.dp),
        )
    }
}

@Composable
private fun SaveStationButton(station: Station?, state: MusicUiState, viewModel: MusicViewModel, accent: Color) {
    if (station == null) return
    val saved = MusicLogic.isSaved(state.data, station)
    IconButton(onClick = { if (saved) viewModel.removeStation(station.id) else viewModel.saveStation(station) }) {
        Icon(
            painterResource(if (saved) R.drawable.ic_favorite else R.drawable.ic_favorite_border),
            stringResource(if (saved) R.string.music_station_remove else R.string.music_station_save),
            tint = if (saved) accent else MusicIconIdle,
            modifier = Modifier.size(28.dp),
        )
    }
}
