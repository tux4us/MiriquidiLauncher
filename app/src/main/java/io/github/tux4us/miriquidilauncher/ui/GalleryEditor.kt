// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.tux4us.miriquidilauncher.R
import io.github.tux4us.miriquidilauncher.gallery.MediaEntry
import io.github.tux4us.miriquidilauncher.gallery.edit.Annotation
import io.github.tux4us.miriquidilauncher.gallery.edit.CropHandle
import io.github.tux4us.miriquidilauncher.gallery.edit.CropMath
import io.github.tux4us.miriquidilauncher.gallery.edit.CropRect
import io.github.tux4us.miriquidilauncher.gallery.edit.EditNaming
import io.github.tux4us.miriquidilauncher.gallery.edit.EditParams
import io.github.tux4us.miriquidilauncher.gallery.edit.FitMath
import io.github.tux4us.miriquidilauncher.gallery.edit.GalleryEditStore
import io.github.tux4us.miriquidilauncher.gallery.edit.ImageFilter
import io.github.tux4us.miriquidilauncher.gallery.edit.ImageRenderer
import io.github.tux4us.miriquidilauncher.gallery.edit.NormPoint
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private enum class EditTab { CROP, ROTATE, ADJUST, FILTER, DRAW, TEXT }

private val EditorBackground = Color(0xFF0B0F14)
private val EditorPanel = Color(0xFF141A22)
private val ChipIdle = Color(0x1AFFFFFF)
private val ChipActive = Color(0x55FFFFFF)
private val DrawColors = listOf(
    Color.White, Color.Black, Color(0xFFE53935), Color(0xFFFFB300), Color(0xFF43A047), Color(0xFF1E88E5), Color(0xFF8E24AA),
)
private const val PREVIEW_MAX = 1600

/** Seitenverhältnisse des Zuschnitts; `null` = frei, `-1` = wie das Bild. */
private val AspectChoices: List<Pair<Float?, String?>> = listOf(
    null to null,
    -1f to null,
    1f to "1:1",
    4f / 3f to "4:3",
    3f / 4f to "3:4",
    3f / 2f to "3:2",
    2f / 3f to "2:3",
    16f / 9f to "16:9",
    9f / 16f to "9:16",
)

/**
 * Vollbild-Editor für ein Foto: Zuschnitt, Drehen, Anpassen, Filter, Zeichnen und Text. Die Vorschau arbeitet auf einer
 * verkleinerten Fassung; gespeichert wird die Kette in voller Auflösung (bis 4096 Pixel) als Kopie, das Original bleibt
 * unverändert.
 */
@Composable
internal fun GalleryEditor(
    entry: MediaEntry,
    saving: Boolean,
    error: String?,
    onCancel: () -> Unit,
    onSave: (source: Bitmap, params: EditParams, copyMetadata: Boolean) -> Unit,
) {
    val context = LocalContext.current
    var failed by remember { mutableStateOf(false) }
    val loaded by produceState<Pair<Bitmap, Bitmap>?>(null, entry.key) {
        value = withContext(Dispatchers.IO) {
            val full = GalleryEditStore.loadForEdit(context, entry)
            if (full == null) null else full to scaledForPreview(full)
        }
        if (value == null) failed = true
    }

    var params by remember { mutableStateOf(EditParams()) }
    var tab by remember { mutableStateOf(EditTab.CROP) }
    var aspect by remember { mutableStateOf<Float?>(null) }
    var aspectIndex by remember { mutableIntStateOf(0) }
    var confirmSave by remember { mutableStateOf(false) }
    var copyMeta by remember { mutableStateOf(true) }
    var textAt by remember { mutableStateOf<NormPoint?>(null) }
    var liveStroke by remember { mutableStateOf<List<NormPoint>>(emptyList()) }
    var drawColor by remember { mutableStateOf(DrawColors[2]) }
    var drawWidth by remember { mutableFloatStateOf(0.012f) }
    var textSize by remember { mutableFloatStateOf(0.06f) }
    var box by remember { mutableStateOf(IntSize.Zero) }
    var rendered by remember { mutableStateOf<Bitmap?>(null) }

    val previewSource = loaded?.second
    val geo = remember(previewSource, params.quarterTurns, params.flipH, params.flipV) {
        previewSource?.let { ImageRenderer.geometry(it, params.quarterTurns, params.flipH, params.flipV) }
    }
    val core = params.copy(annotations = emptyList())
    LaunchedEffect(previewSource, core, tab == EditTab.CROP) {
        val source = previewSource ?: return@LaunchedEffect
        if (tab == EditTab.CROP) return@LaunchedEffect
        delay(50)
        rendered = withContext(Dispatchers.Default) { ImageRenderer.render(source, core) }
    }

    fun changeGeometry(turns: Int, flipH: Boolean, flipV: Boolean) {
        params = params.copy(
            quarterTurns = ((turns % 4) + 4) % 4,
            flipH = flipH,
            flipV = flipV,
            crop = CropRect(),
            annotations = emptyList(),
        )
        aspect = null
        aspectIndex = 0
    }

    Dialog(
        onDismissRequest = { if (!saving) onCancel() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false, dismissOnClickOutside = false),
    ) {
        FitDialogWindow()
        val (measureWindow, overshoot) = rememberDialogOvershoot()
        Box(Modifier.fillMaxSize().then(measureWindow).background(EditorBackground)) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(bottom = overshoot)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    EditorTextButton(stringResource(R.string.gallery_ed_cancel), enabled = !saving, onClick = onCancel)
                    Spacer(Modifier.weight(1f))
                    EditorTextButton(
                        stringResource(R.string.gallery_ed_reset),
                        enabled = !saving && !params.isIdentity,
                    ) {
                        params = EditParams()
                        aspect = null
                        aspectIndex = 0
                    }
                    EditorTextButton(
                        stringResource(R.string.gallery_ed_save),
                        enabled = !saving && loaded != null && !params.isIdentity,
                    ) { confirmSave = true }
                }

                if (error != null) {
                    Text(error, color = Color(0xFFFFB4B4), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                }

                Box(Modifier.weight(1f).fillMaxWidth().onSizeChanged { box = it }) {
                    val shown = if (tab == EditTab.CROP) geo else (rendered ?: geo)
                    when {
                        failed -> Text(
                            stringResource(R.string.gallery_ed_load_failed),
                            color = WeatherTextMuted,
                            modifier = Modifier.align(Alignment.Center).padding(24.dp),
                        )
                        shown == null || box.width <= 0 -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = WeatherText)
                            Text(stringResource(R.string.gallery_ed_loading), color = WeatherTextMuted, modifier = Modifier.padding(top = 12.dp))
                        }
                        else -> {
                            val fit = FitMath.fit(box.width.toFloat(), box.height.toFloat(), shown.width, shown.height)
                            val image = remember(shown) { shown.asImageBitmap() }
                            Image(bitmap = image, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                            val geoW = geo?.width ?: shown.width
                            val geoH = geo?.height ?: shown.height
                            val cropNow by rememberUpdatedState(params.crop)
                            val aspectNow by rememberUpdatedState(aspect)
                            val colorNow by rememberUpdatedState(drawColor)
                            val widthNow by rememberUpdatedState(drawWidth)
                            val sizeNow by rememberUpdatedState(textSize)

                            fun norm(o: Offset) = NormPoint(
                                ((o.x - fit.left) / fit.width).coerceIn(0f, 1f),
                                ((o.y - fit.top) / fit.height).coerceIn(0f, 1f),
                            )

                            Canvas(
                                Modifier.fillMaxSize().pointerInput(tab, fit) {
                                    when (tab) {
                                        EditTab.CROP -> {
                                            var handle = CropHandle.NONE
                                            detectDragGestures(
                                                onDragStart = { o ->
                                                    handle = CropMath.hit(
                                                        cropNow,
                                                        (o.x - fit.left) / fit.width,
                                                        (o.y - fit.top) / fit.height,
                                                        28.dp.toPx() / fit.width,
                                                    )
                                                },
                                                onDrag = { change, drag ->
                                                    change.consume()
                                                    if (handle != CropHandle.NONE) {
                                                        params = params.copy(
                                                            crop = CropMath.drag(cropNow, handle, drag.x / fit.width, drag.y / fit.height, aspectNow, geoW, geoH),
                                                        )
                                                    }
                                                },
                                                onDragEnd = { handle = CropHandle.NONE },
                                                onDragCancel = { handle = CropHandle.NONE },
                                            )
                                        }
                                        EditTab.DRAW -> detectDragGestures(
                                            onDragStart = { o -> liveStroke = listOf(norm(o)) },
                                            onDrag = { change, _ ->
                                                change.consume()
                                                liveStroke = liveStroke + norm(change.position)
                                            },
                                            onDragEnd = {
                                                if (liveStroke.isNotEmpty()) {
                                                    params = params.copy(
                                                        annotations = params.annotations + Annotation.Stroke(colorNow.toArgbInt(), widthNow, liveStroke),
                                                    )
                                                }
                                                liveStroke = emptyList()
                                            },
                                            onDragCancel = { liveStroke = emptyList() },
                                        )
                                        EditTab.TEXT -> detectTapGestures { o -> textAt = norm(o) }
                                        else -> Unit
                                    }
                                },
                            ) {
                                if (tab == EditTab.CROP) {
                                    drawCropOverlay(fit.left, fit.top, fit.width, fit.height, params.crop)
                                } else {
                                    val live = if (liveStroke.isEmpty()) emptyList() else listOf<Annotation>(Annotation.Stroke(drawColor.toArgbInt(), drawWidth, liveStroke))
                                    drawIntoCanvas { canvas ->
                                        val native = canvas.nativeCanvas
                                        native.save()
                                        native.translate(fit.left, fit.top)
                                        ImageRenderer.drawAnnotations(native, fit.width.toInt(), fit.height.toInt(), params.annotations + live)
                                        native.restore()
                                    }
                                }
                            }
                        }
                    }
                }

                // Reiter
                Row(
                    Modifier.fillMaxWidth().background(EditorPanel).horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    EditTab.entries.forEach { t ->
                        val selected = tab == t
                        TextButton(
                            onClick = { tab = t },
                            colors = ButtonDefaults.textButtonColors(contentColor = if (selected) Color.White else WeatherTextMuted),
                        ) {
                            Text(stringResource(tabLabel(t)), style = if (selected) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium)
                        }
                    }
                }

                // Bedienfeld
                Column(
                    Modifier.fillMaxWidth().height(150.dp).background(EditorPanel).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    when (tab) {
                        EditTab.CROP -> {
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                AspectChoices.forEachIndexed { i, (ratio, label) ->
                                    val text = when {
                                        ratio == null -> stringResource(R.string.gallery_ed_aspect_free)
                                        ratio < 0f -> stringResource(R.string.gallery_ed_aspect_original)
                                        else -> label.orEmpty()
                                    }
                                    EditChip(text, selected = aspectIndex == i) {
                                        aspectIndex = i
                                        val g = geo
                                        val value = when {
                                            ratio == null -> null
                                            ratio < 0f -> g?.let { it.width.toFloat() / it.height }
                                            else -> ratio
                                        }
                                        aspect = value
                                        if (value != null && g != null) params = params.copy(crop = CropMath.centered(value, g.width, g.height))
                                    }
                                }
                            }
                        }
                        EditTab.ROTATE -> {
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                EditChip(stringResource(R.string.gallery_ed_rotate_left), false) {
                                    changeGeometry(params.quarterTurns + 3, params.flipH, params.flipV)
                                }
                                EditChip(stringResource(R.string.gallery_ed_rotate_right), false) {
                                    changeGeometry(params.quarterTurns + 1, params.flipH, params.flipV)
                                }
                            }
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                // Spiegeln wirkt auf das angezeigte Bild; die Drehung wird dafür umgekehrt.
                                EditChip(stringResource(R.string.gallery_ed_flip_h), params.flipH) {
                                    changeGeometry(-params.quarterTurns, !params.flipH, params.flipV)
                                }
                                EditChip(stringResource(R.string.gallery_ed_flip_v), params.flipV) {
                                    changeGeometry(-params.quarterTurns, params.flipH, !params.flipV)
                                }
                            }
                        }
                        EditTab.ADJUST -> {
                            EditSlider(stringResource(R.string.gallery_ed_brightness), params.brightness, -1f..1f, signed = true) { params = params.copy(brightness = it) }
                            EditSlider(stringResource(R.string.gallery_ed_contrast), params.contrast, -1f..1f, signed = true) { params = params.copy(contrast = it) }
                            EditSlider(stringResource(R.string.gallery_ed_saturation), params.saturation, -1f..1f, signed = true) { params = params.copy(saturation = it) }
                            EditSlider(stringResource(R.string.gallery_ed_sharpness), params.sharpness, 0f..1f, signed = false) { params = params.copy(sharpness = it) }
                        }
                        EditTab.FILTER -> {
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                ImageFilter.entries.forEach { f ->
                                    EditChip(stringResource(filterLabel(f)), params.filter == f) { params = params.copy(filter = f) }
                                }
                            }
                        }
                        EditTab.DRAW -> {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                DrawColors.forEach { c ->
                                    Box(
                                        Modifier.size(if (c == drawColor) 34.dp else 28.dp).clip(CircleShape).background(c)
                                            .clickable { drawColor = c },
                                    )
                                }
                            }
                            EditSlider(stringResource(R.string.gallery_ed_stroke_width), drawWidth, 0.004f..0.04f, signed = false, showValue = false) { drawWidth = it }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.gallery_ed_draw_hint), color = WeatherTextMuted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                EditorTextButton(stringResource(R.string.gallery_ed_undo), enabled = params.annotations.isNotEmpty()) {
                                    params = params.copy(annotations = params.annotations.dropLast(1))
                                }
                            }
                            Text(stringResource(R.string.gallery_ed_order_hint), color = WeatherTextMuted, style = MaterialTheme.typography.bodySmall)
                        }
                        EditTab.TEXT -> {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                DrawColors.forEach { c ->
                                    Box(
                                        Modifier.size(if (c == drawColor) 34.dp else 28.dp).clip(CircleShape).background(c)
                                            .clickable { drawColor = c },
                                    )
                                }
                            }
                            EditSlider(stringResource(R.string.gallery_ed_text_size), textSize, 0.03f..0.2f, signed = false, showValue = false) { textSize = it }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.gallery_ed_text_hint), color = WeatherTextMuted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                EditorTextButton(stringResource(R.string.gallery_ed_undo), enabled = params.annotations.isNotEmpty()) {
                                    params = params.copy(annotations = params.annotations.dropLast(1))
                                }
                            }
                            Text(stringResource(R.string.gallery_ed_order_hint), color = WeatherTextMuted, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            if (saving) {
                Box(
                    Modifier.fillMaxSize().background(Color(0xAA000000))
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = WeatherText)
                        Text(stringResource(R.string.gallery_ed_saving), color = WeatherText, modifier = Modifier.padding(top = 12.dp))
                    }
                }
            }
        }
    }

    // Text eingeben
    textAt?.let { at ->
        var text by remember(at) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { textAt = null },
            containerColor = GalleryDialogColor,
            titleContentColor = WeatherText,
            title = { Text(stringResource(R.string.gallery_ed_tab_text)) },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(120) },
                    label = { Text(stringResource(R.string.gallery_ed_text_label)) },
                    maxLines = 4,
                    colors = filesFieldColors(),
                )
            },
            confirmButton = {
                EditDialogButton(stringResource(R.string.gallery_ed_text_ok)) {
                    if (text.isNotBlank()) {
                        params = params.copy(annotations = params.annotations + Annotation.Label(text.trim(), at.x, at.y, textSize, drawColor.toArgbInt()))
                    }
                    textAt = null
                }
            },
            dismissButton = { EditDialogButton(stringResource(R.string.gallery_ed_cancel)) { textAt = null } },
        )
    }

    // Speichern bestätigen
    if (confirmSave) {
        val png = EditNaming.isPng(entry.mime)
        AlertDialog(
            onDismissRequest = { confirmSave = false },
            containerColor = GalleryDialogColor,
            titleContentColor = WeatherText,
            textContentColor = WeatherTextMuted,
            title = { Text(stringResource(R.string.gallery_ed_save_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.gallery_ed_save_text) + "\n" + EditNaming.copyName(entry.name, png))
                    if (!png) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = copyMeta,
                                onCheckedChange = { copyMeta = it },
                                colors = CheckboxDefaults.colors(checkedColor = WeatherText, checkmarkColor = EditorBackground, uncheckedColor = WeatherTextMuted),
                            )
                            Text(stringResource(R.string.gallery_ed_copy_meta), color = WeatherText, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            },
            confirmButton = {
                EditDialogButton(stringResource(R.string.gallery_ed_save)) {
                    confirmSave = false
                    loaded?.first?.let { onSave(it, params, copyMeta && !png) }
                }
            },
            dismissButton = { EditDialogButton(stringResource(R.string.gallery_ed_cancel)) { confirmSave = false } },
        )
    }
}

private fun scaledForPreview(full: Bitmap): Bitmap {
    val longest = max(full.width, full.height)
    if (longest <= PREVIEW_MAX) return full
    val scale = PREVIEW_MAX.toFloat() / longest
    return Bitmap.createScaledBitmap(full, max(1, (full.width * scale).toInt()), max(1, (full.height * scale).toInt()), true)
}

private fun Color.toArgbInt(): Int = this.toArgb()

private fun tabLabel(t: EditTab): Int = when (t) {
    EditTab.CROP -> R.string.gallery_ed_tab_crop
    EditTab.ROTATE -> R.string.gallery_ed_tab_rotate
    EditTab.ADJUST -> R.string.gallery_ed_tab_adjust
    EditTab.FILTER -> R.string.gallery_ed_tab_filter
    EditTab.DRAW -> R.string.gallery_ed_tab_draw
    EditTab.TEXT -> R.string.gallery_ed_tab_text
}

private fun filterLabel(f: ImageFilter): Int = when (f) {
    ImageFilter.NONE -> R.string.gallery_ed_filter_none
    ImageFilter.MONO -> R.string.gallery_ed_filter_mono
    ImageFilter.SEPIA -> R.string.gallery_ed_filter_sepia
    ImageFilter.WARM -> R.string.gallery_ed_filter_warm
    ImageFilter.COOL -> R.string.gallery_ed_filter_cool
    ImageFilter.VINTAGE -> R.string.gallery_ed_filter_vintage
    ImageFilter.DRAMA -> R.string.gallery_ed_filter_drama
}

/** Abdunkelung außerhalb des Zuschnitts, Rahmen, Drittelraster und Eckgriffe. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCropOverlay(left: Float, top: Float, width: Float, height: Float, crop: CropRect) {
    val dim = Color(0x99000000)
    val l = left + crop.left * width
    val t = top + crop.top * height
    val r = left + crop.right * width
    val b = top + crop.bottom * height
    drawRect(dim, Offset(left, top), Size(width, t - top))
    drawRect(dim, Offset(left, b), Size(width, top + height - b))
    drawRect(dim, Offset(left, t), Size(l - left, b - t))
    drawRect(dim, Offset(r, t), Size(left + width - r, b - t))
    drawRect(Color.White, Offset(l, t), Size(r - l, b - t), style = Stroke(width = 2.dp.toPx()))
    val grid = Color(0x66FFFFFF)
    for (i in 1..2) {
        val x = l + (r - l) * i / 3f
        val y = t + (b - t) * i / 3f
        drawLine(grid, Offset(x, t), Offset(x, b), strokeWidth = 1.dp.toPx())
        drawLine(grid, Offset(l, y), Offset(r, y), strokeWidth = 1.dp.toPx())
    }
    val radius = 7.dp.toPx()
    for (p in listOf(Offset(l, t), Offset(r, t), Offset(l, b), Offset(r, b))) drawCircle(Color.White, radius, p)
}

@Composable
private fun EditorTextButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.textButtonColors(contentColor = Color.White, disabledContentColor = Color(0x66FFFFFF)),
    ) { Text(text, maxLines = 1) }
}

@Composable
private fun EditDialogButton(text: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, colors = ButtonDefaults.textButtonColors(contentColor = WeatherText)) { Text(text) }
}

@Composable
private fun EditChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(16.dp)).background(if (selected) ChipActive else ChipIdle).clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) { Text(text, color = Color.White, style = MaterialTheme.typography.bodyMedium, maxLines = 1) }
}

@Composable
private fun EditSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    signed: Boolean,
    showValue: Boolean = true,
    onChange: (Float) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = WeatherTextMuted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.size(width = 84.dp, height = 20.dp))
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            modifier = Modifier.weight(1f).height(28.dp),
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color(0x44FFFFFF)),
        )
        if (showValue) {
            val percent = (value * 100f).toInt()
            Text(
                (if (signed && percent > 0) "+" else "") + percent,
                color = WeatherText,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.size(width = 40.dp, height = 20.dp),
            )
        }
    }
}
