// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.ui

import android.content.Context
import android.graphics.Bitmap
import android.text.format.Formatter
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.tux4us.miriquidilauncher.R
import io.github.tux4us.miriquidilauncher.gallery.GalleryImages
import io.github.tux4us.miriquidilauncher.gallery.GalleryIntents
import io.github.tux4us.miriquidilauncher.gallery.GalleryLogic
import io.github.tux4us.miriquidilauncher.gallery.GalleryRepository
import io.github.tux4us.miriquidilauncher.gallery.GalleryUiState
import io.github.tux4us.miriquidilauncher.gallery.GalleryViewModel
import io.github.tux4us.miriquidilauncher.gallery.MediaEntry
import java.util.Locale
import kotlinx.coroutines.delay

private val ViewerScrim = Color(0xAA000000)

/**
 * Vollbild-Ansicht: Wischen zwischen den Medien, Zoom per Zwei-Finger-Geste und Doppeltipp, Videowiedergabe mit
 * eigenen Bedienelementen, Diashow, Info sowie Aktionen. Läuft in einem eigenen Fenster; das Wischen der
 * Launcher-Seiten ist dadurch während der Ansicht ausgeschaltet.
 */
@Composable
internal fun GalleryViewer(state: GalleryUiState, viewModel: GalleryViewModel, context: Context, onDialog: (GalleryDialog) -> Unit) {
    val index = state.viewerIndex ?: return
    if (state.visible.isEmpty()) return
    val pagerState = rememberPagerState(initialPage = index.coerceIn(0, state.visible.lastIndex)) { state.visible.size }
    var zoomed by remember { mutableStateOf(false) }
    var chrome by remember { mutableStateOf(true) }
    val view = LocalView.current

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect {
            viewModel.setViewerIndex(it)
            zoomed = false
        }
    }
    LaunchedEffect(state.viewerIndex) {
        val target = state.viewerIndex ?: return@LaunchedEffect
        if (target in state.visible.indices && pagerState.currentPage != target) pagerState.animateScrollToPage(target)
    }
    DisposableEffect(state.slideshow) {
        view.keepScreenOn = state.slideshow
        onDispose { view.keepScreenOn = false }
    }

    val entry = state.visible.getOrNull(pagerState.currentPage) ?: return
    val showChrome = chrome || entry.isVideo
    val favorite = entry.path.isNotEmpty() && entry.path in state.favorites

    Dialog(
        onDismissRequest = { viewModel.back() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                userScrollEnabled = !zoomed,
                key = { state.visible.getOrNull(it)?.key ?: it },
            ) { page ->
                val item = state.visible.getOrNull(page)
                if (item != null) {
                    if (item.isVideo) {
                        VideoPage(item, current = page == pagerState.currentPage)
                    } else {
                        ImagePage(item, onZoomed = { if (page == pagerState.currentPage) zoomed = it }, onTap = { chrome = !chrome })
                    }
                }
            }

            if (showChrome) {
                Row(
                    Modifier.align(Alignment.TopCenter).fillMaxWidth().background(ViewerScrim).statusBarsPadding().padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ViewerButton(stringResource(R.string.gallery_viewer_close)) { viewModel.closeViewer() }
                    Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                        Text(entry.name, color = Color.White, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${pagerState.currentPage + 1} / ${state.visible.size}", color = Color(0xFFCCCCCC), style = MaterialTheme.typography.bodySmall)
                    }
                }
                Row(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(ViewerScrim).navigationBarsPadding().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ViewerButton(if (favorite) "★ " + stringResource(R.string.gallery_unfavorite) else "☆ " + stringResource(R.string.gallery_favorite)) { viewModel.toggleFavorite(entry) }
                    ViewerButton(stringResource(R.string.gallery_share)) { if (!GalleryIntents.share(context, listOf(entry))) viewModel.reportNoApp() }
                    ViewerButton(stringResource(R.string.gallery_info)) { viewModel.showInfo(entry) }
                    ViewerButton(stringResource(if (state.slideshow) R.string.gallery_slideshow_stop else R.string.gallery_slideshow_start)) {
                        if (state.slideshow) viewModel.stopSlideshow() else { chrome = false; viewModel.startSlideshow() }
                    }
                    var more by remember { mutableStateOf(false) }
                    Box {
                        ViewerButton(stringResource(R.string.gallery_more)) { more = true }
                        DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.gallery_open_with)) }, onClick = { more = false; if (!GalleryIntents.view(context, entry)) viewModel.reportNoApp() })
                            if (!entry.isVideo) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.gallery_edit)) }, onClick = { more = false; if (!GalleryIntents.edit(context, entry)) viewModel.reportNoApp() })
                                DropdownMenuItem(text = { Text(stringResource(R.string.gallery_use_as)) }, onClick = { more = false; if (!GalleryIntents.useAs(context, entry)) viewModel.reportNoApp() })
                            }
                            DropdownMenuItem(text = { Text(stringResource(R.string.gallery_rename)) }, onClick = { more = false; onDialog(GalleryDialog.Rename(entry)) })
                            DropdownMenuItem(text = { Text(stringResource(R.string.gallery_move)) }, onClick = { more = false; onDialog(GalleryDialog.PickAlbum(listOf(entry), move = true)) })
                            DropdownMenuItem(text = { Text(stringResource(R.string.gallery_copy)) }, onClick = { more = false; onDialog(GalleryDialog.PickAlbum(listOf(entry), move = false)) })
                            DropdownMenuItem(text = { Text(stringResource(R.string.gallery_trash)) }, onClick = { more = false; viewModel.trash(listOf(entry)) })
                        }
                    }
                }
            } else if (state.slideshow) {
                ViewerButton(stringResource(R.string.gallery_slideshow_stop), Modifier.align(Alignment.TopEnd).statusBarsPadding()) { viewModel.stopSlideshow() }
            }
        }
    }

    state.info?.let { info -> InfoDialog(info.entry, info.exif, context, viewModel::closeInfo) }
}

@Composable
private fun ViewerButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = modifier, colors = ButtonDefaults.textButtonColors(contentColor = Color.White)) { Text(text, maxLines = 1) }
}

// region Bild ------------------------------------------------------------------------------------

@Composable
private fun ImagePage(entry: MediaEntry, onZoomed: (Boolean) -> Unit, onTap: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val metrics = LocalContext.current.resources.displayMetrics
    val maxDimension = maxOf(metrics.widthPixels, metrics.heightPixels)
    val loaded by produceState<LoadResult>(LoadResult.Loading, entry.key) {
        val bitmap = GalleryImages.loadFull(context, entry, maxDimension)
        value = if (bitmap != null) LoadResult.Ok(bitmap) else LoadResult.Failed
    }
    when (val result = loaded) {
        LoadResult.Loading -> Box(Modifier.fillMaxSize()) { CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White) }
        LoadResult.Failed -> Box(Modifier.fillMaxSize()) {
            Text(stringResource(R.string.gallery_load_failed), color = Color.White, modifier = Modifier.align(Alignment.Center).padding(24.dp))
        }
        is LoadResult.Ok -> ZoomableImage(result.bitmap, entry.name, onZoomed, onTap)
    }
}

private sealed interface LoadResult {
    data object Loading : LoadResult
    data object Failed : LoadResult
    data class Ok(val bitmap: Bitmap) : LoadResult
}

/** Zoom bis 6-fach: Kneifen mit zwei Fingern, Verschieben bei Vergrößerung, Doppeltipp wechselt zwischen 1× und 3×. */
@Composable
private fun ZoomableImage(bitmap: Bitmap, description: String, onZoomed: (Boolean) -> Unit, onTap: () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val isZoomed = scale > 1.01f
    LaunchedEffect(isZoomed) { onZoomed(isZoomed) }

    fun clamp(o: Offset, s: Float): Offset {
        val mx = size.width * (s - 1f) / 2f
        val my = size.height * (s - 1f) / 2f
        return Offset(o.x.coerceIn(-mx, mx), o.y.coerceIn(-my, my))
    }

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { size = it }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { p ->
                        if (scale > 1.01f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            scale = 3f
                            offset = clamp(Offset((size.width / 2f - p.x) * 2f, (size.height / 2f - p.y) * 2f), 3f)
                        }
                    },
                    onTap = { onTap() },
                )
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val multi = event.changes.count { it.pressed } >= 2
                        // Ein Finger bei Maßstab 1 bleibt unberührt, damit der Pager wischen kann.
                        if (multi || scale > 1.01f) {
                            val newScale = (scale * event.calculateZoom()).coerceIn(1f, 6f)
                            scale = newScale
                            offset = if (newScale <= 1.01f) Offset.Zero else clamp(offset + event.calculatePan(), newScale)
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = description,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().graphicsLayer(
                scaleX = scale,
                scaleY = scale,
                translationX = offset.x,
                translationY = offset.y,
            ),
        )
    }
}

// endregion

// region Video -----------------------------------------------------------------------------------

@Composable
private fun VideoPage(entry: MediaEntry, current: Boolean) {
    var video by remember { mutableStateOf<VideoView?>(null) }
    var playing by remember { mutableStateOf(false) }
    var position by remember { mutableFloatStateOf(0f) }
    var duration by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }

    LaunchedEffect(current, video) {
        val v = video ?: return@LaunchedEffect
        if (current) v.start() else v.pause()
        while (current) {
            delay(400)
            playing = v.isPlaying
            duration = v.duration.coerceAtLeast(0).toFloat()
            if (!dragging) position = v.currentPosition.toFloat()
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                FrameLayout(ctx).also { frame ->
                    val v = VideoView(ctx)
                    frame.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
                    v.setVideoURI(GalleryRepository.uriOf(entry))
                    v.setOnCompletionListener { playing = false }
                    video = v
                }
            },
            onRelease = { frame -> (frame.getChildAt(0) as? VideoView)?.stopPlayback(); video = null },
        )
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 96.dp, start = 16.dp, end = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ViewerButton(if (playing) "⏸" else "▶") {
                    val v = video ?: return@ViewerButton
                    if (v.isPlaying) v.pause() else v.start()
                    playing = v.isPlaying
                }
                Slider(
                    value = position.coerceIn(0f, maxOf(duration, 1f)),
                    onValueChange = { dragging = true; position = it },
                    onValueChangeFinished = { video?.seekTo(position.toInt()); dragging = false },
                    valueRange = 0f..maxOf(duration, 1f),
                    colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color(0x66FFFFFF)),
                    modifier = Modifier.weight(1f),
                )
                Text(
                    GalleryLogic.formatDuration(position.toLong()) + " / " + GalleryLogic.formatDuration(duration.toLong()),
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

// endregion

// region Info ------------------------------------------------------------------------------------

@Composable
private fun InfoDialog(entry: MediaEntry, exif: List<Pair<String, String>>, context: Context, onDismiss: () -> Unit) {
    val locale = remember { Locale.getDefault() }
    val rows = buildList {
        add(stringResource(R.string.gallery_info_name) to entry.name)
        if (entry.path.isNotEmpty()) add(stringResource(R.string.gallery_info_path) to entry.path)
        add(stringResource(R.string.gallery_info_album) to entry.bucketName)
        add(stringResource(R.string.gallery_info_date) to formatDateTime(entry.takenMillis, locale))
        add(stringResource(R.string.gallery_info_size) to Formatter.formatFileSize(context, entry.sizeBytes))
        if (entry.width > 0 && entry.height > 0) add(stringResource(R.string.gallery_info_dimensions) to "${entry.width} × ${entry.height}")
        if (entry.isVideo && entry.durationMs > 0) add(stringResource(R.string.gallery_info_duration) to GalleryLogic.formatDuration(entry.durationMs))
        if (entry.mime.isNotEmpty()) add(stringResource(R.string.gallery_info_type) to entry.mime)
        exif.forEach { (key, value) ->
            val label = when (key) {
                "camera" -> R.string.gallery_info_camera
                "exposure" -> R.string.gallery_info_exposure
                "aperture" -> R.string.gallery_info_aperture
                "iso" -> R.string.gallery_info_iso
                else -> R.string.gallery_info_focal
            }
            add(stringResource(label) to value)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = GalleryDialogColor,
        titleContentColor = WeatherText,
        textContentColor = WeatherTextMuted,
        title = { Text(stringResource(R.string.gallery_info)) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                rows.forEach { (label, value) ->
                    Column {
                        Text(label, color = WeatherTextMuted, style = MaterialTheme.typography.labelSmall)
                        Text(value, color = WeatherText, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, colors = ButtonDefaults.textButtonColors(contentColor = WeatherText)) { Text(stringResource(R.string.gallery_close)) } },
    )
}

// endregion
