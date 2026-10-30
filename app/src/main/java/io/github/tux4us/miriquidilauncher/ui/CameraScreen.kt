// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.ui

import android.Manifest
import android.content.Context
import android.content.res.Configuration
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.tux4us.miriquidilauncher.R
import io.github.tux4us.miriquidilauncher.camera.BindRequest
import io.github.tux4us.miriquidilauncher.camera.CameraFormat
import io.github.tux4us.miriquidilauncher.camera.CameraRules
import io.github.tux4us.miriquidilauncher.camera.CameraUiState
import io.github.tux4us.miriquidilauncher.camera.CameraViewModel
import io.github.tux4us.miriquidilauncher.camera.CaptureMode
import io.github.tux4us.miriquidilauncher.camera.ExtensionSetting
import io.github.tux4us.miriquidilauncher.camera.FlashSetting
import io.github.tux4us.miriquidilauncher.camera.GridSetting
import io.github.tux4us.miriquidilauncher.camera.PhotoFormat
import io.github.tux4us.miriquidilauncher.camera.RatioSetting
import io.github.tux4us.miriquidilauncher.camera.RecordingState
import kotlin.math.abs
import kotlinx.coroutines.delay

private enum class CameraPanelKind { None, Settings, Pro }

private val RecordRed = Color(0xFFE53935)
private val OverlayFill = Color(0x99000000)

private fun requiredPermissions(): List<String> = buildList {
    add(Manifest.permission.CAMERA)
    // Bis Android 9 braucht das Speichern in die Mediathek die Speicherberechtigung.
    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
}

private fun hasPermission(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

private fun hasAll(context: Context, permissions: List<String>): Boolean = permissions.all { hasPermission(context, it) }

/**
 * Scope „Kamera“: Vorschau, Foto und Video mit den Funktionen, die das Gerät bereitstellt. [active] ist nur wahr,
 * solange die Seite im seitlichen Pager aktuell ist; nur dann wird die Kamera gebunden, sonst bleibt sie frei.
 */
@Composable
fun CameraScreen(viewModel: CameraViewModel, active: Boolean) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val required = remember { requiredPermissions() }
    var granted by remember { mutableStateOf(hasAll(context, required)) }
    var asked by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = hasAll(context, required)
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            // Die Berechtigung kann in den Systemeinstellungen geändert worden sein.
            if (event == Lifecycle.Event.ON_RESUME) granted = hasAll(context, required)
            if (event == Lifecycle.Event.ON_STOP) viewModel.onHostStopped()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(active, granted) {
        if (active && !granted && !asked) {
            asked = true
            launcher.launch(required.toTypedArray())
        }
    }

    if (!granted) {
        PermissionPrompt(
            onGrant = { launcher.launch(required.toTypedArray()) },
            onSettings = {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(intent) }
            },
        )
    } else {
        CameraContent(viewModel = viewModel, active = active)
    }
}

@Composable
private fun PermissionPrompt(onGrant: () -> Unit, onSettings: () -> Unit) {
    ScopePage(title = stringResource(R.string.scope_camera)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.cam_permission_text),
                color = WeatherTextMuted,
                textAlign = TextAlign.Center,
            )
            Button(onClick = onGrant) { Text(stringResource(R.string.cam_permission_grant)) }
            TextButton(onClick = onSettings) { Text(stringResource(R.string.cam_permission_settings), color = WeatherText) }
        }
    }
}

@Composable
private fun CameraContent(viewModel: CameraViewModel, active: Boolean) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val state by viewModel.state.collectAsState()
    val previewView = remember {
        PreviewView(context).apply {
            // TextureView statt SurfaceView: verhält sich im Pager beim Wischen und Überblenden zuverlässig.
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    var panel by remember { mutableStateOf(CameraPanelKind.None) }
    var focusPoint by remember { mutableStateOf<Offset?>(null) }
    var audioGranted by remember { mutableStateOf(hasPermission(context, Manifest.permission.RECORD_AUDIO)) }
    var audioAsked by remember { mutableStateOf(false) }
    val audioLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { audioGranted = it }

    val request = viewModel.bindRequest(state)

    LaunchedEffect(active) { if (active) viewModel.prepare() }
    // Gebunden wird nur, solange die Seite sichtbar ist; jede Änderung der Bindung löst neu aus.
    LaunchedEffect(active, request) {
        if (active && request != null) viewModel.bind(lifecycleOwner, previewView) else viewModel.unbind()
    }
    DisposableEffect(active) {
        if (active) viewModel.startOrientationUpdates()
        onDispose { viewModel.stopOrientationUpdates() }
    }
    DisposableEffect(Unit) { onDispose { viewModel.unbind() } }
    LaunchedEffect(active) { if (!active) panel = CameraPanelKind.None }
    LaunchedEffect(active, state.mode, state.settings.audio) {
        if (active && state.mode == CaptureMode.Video && state.settings.audio && !audioGranted && !audioAsked) {
            audioAsked = true
            audioLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    LaunchedEffect(state.message) {
        if (state.message != null) {
            delay(2500)
            viewModel.clearMessage()
        }
    }
    LaunchedEffect(focusPoint) {
        if (focusPoint != null) {
            delay(1200)
            focusPoint = null
        }
    }
    BackHandler(enabled = active && panel != CameraPanelKind.None) { panel = CameraPanelKind.None }

    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    val topBar: @Composable () -> Unit = {
        TopBar(
            state = state,
            panel = panel,
            onFlash = viewModel::cycleFlash,
            onTimer = viewModel::cycleTimer,
            onRatio = viewModel::toggleRatio,
            onGrid = viewModel::cycleGrid,
            onPanel = { kind -> panel = if (panel == kind) CameraPanelKind.None else kind },
        )
    }
    val viewfinder: @Composable (Modifier) -> Unit = { mod ->
        Box(modifier = mod) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val aspect = viewfinderAspect(state.mode, state.settings.ratio, portrait = maxHeight >= maxWidth)
                val width = minOf(maxWidth, maxHeight * aspect)
                Box(
                    modifier = Modifier
                        .size(width, width / aspect)
                        .clip(RoundedCornerShape(12.dp))
                        .pointerInput(Unit) {
                            detectTapGestures { offset ->
                                focusPoint = offset
                                viewModel.focusAt(previewView, offset.x, offset.y)
                            }
                        }
                        .pinchToZoom { scale -> viewModel.zoomBy(scale) },
                ) {
                    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
                    if (state.settings.grid == GridSetting.Thirds) GridOverlay()
                    FocusRing(focusPoint)
                    ViewfinderOverlays(state = state, request = request, onPreset = viewModel::setZoom)
                }
            }
            if (state.prepared && state.camera == null) {
                Text(
                    text = stringResource(R.string.cam_no_camera),
                    color = WeatherTextMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                )
            }
            state.message?.let { message ->
                Text(
                    text = stringResource(message.labelRes()),
                    color = WeatherText,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 8.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(OverlayFill)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
            when (panel) {
                CameraPanelKind.Settings -> CameraSettingsPanel(
                    state = state,
                    viewModel = viewModel,
                    onClose = { panel = CameraPanelKind.None },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
                CameraPanelKind.Pro -> CameraProPanel(
                    state = state,
                    viewModel = viewModel,
                    onClose = { panel = CameraPanelKind.None },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
                CameraPanelKind.None -> Unit
            }
        }
    }
    val modeAndControls: @Composable (Modifier) -> Unit = { mod ->
        Column(modifier = mod) {
            ModeToggle(mode = state.mode, enabled = !state.busy, onMode = viewModel::setMode)
            Spacer(Modifier.height(8.dp))
            Controls(state = state, viewModel = viewModel, audioGranted = audioGranted, compact = landscape)
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (landscape) {
            // Querformat: Der Sucher nutzt die ganze Höhe, Bedienelemente stehen in einer Spalte rechts.
            Row(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
                viewfinder(Modifier.weight(1f).fillMaxHeight())
                Column(
                    modifier = Modifier.width(232.dp).fillMaxHeight().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.Center,
                ) {
                    topBar()
                    modeAndControls(Modifier.fillMaxWidth())
                    // Platz für die schwebenden Buttons unten rechts (Home und Nachbar-Scope).
                    Spacer(Modifier.height(ScopeBottomClearance))
                }
            }
        } else {
            Column(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
                topBar()
                viewfinder(Modifier.weight(1f).fillMaxWidth())
                modeAndControls(Modifier.fillMaxWidth())
                Spacer(Modifier.height(ScopeBottomClearance))
            }
        }
    }
}

/** Seitenverhältnis (Breite : Höhe) des Suchers; im Video immer 16:9. */
private fun viewfinderAspect(mode: CaptureMode, ratio: RatioSetting, portrait: Boolean): Float {
    val wide = mode == CaptureMode.Video || ratio == RatioSetting.R16x9
    val longOverShort = if (wide) 16f / 9f else 4f / 3f
    return if (portrait) 1f / longOverShort else longOverShort
}

/**
 * Zoom per Zwei-Finger-Geste. Ein einzelner Finger bleibt unberührt, damit das seitliche Wischen des Pagers
 * weiter funktioniert; erst wenn zwei Finger aufliegen, werden die Ereignisse verbraucht.
 */
private fun Modifier.pinchToZoom(onScale: (Float) -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var previous = 0f
        do {
            val event = awaitPointerEvent()
            val pressed = event.changes.filter { it.pressed }
            if (pressed.size >= 2) {
                val distance = (pressed[0].position - pressed[1].position).getDistance()
                if (previous > 0f && distance > 0f) onScale(distance / previous)
                previous = distance
                event.changes.forEach { it.consume() }
            } else {
                previous = 0f
            }
        } while (event.changes.any { it.pressed })
    }
}

@Composable
private fun TopBar(
    state: CameraUiState,
    panel: CameraPanelKind,
    onFlash: () -> Unit,
    onTimer: () -> Unit,
    onRatio: () -> Unit,
    onGrid: () -> Unit,
    onPanel: (CameraPanelKind) -> Unit,
) {
    val video = state.mode == CaptureMode.Video
    val effective = CameraRules.effectiveFlash(state.settings.flash, state.caps, state.manual.exposureEnabled)
    // Im Video gibt es keinen Blitz „Auto“, nur Lampe aus/an.
    val flash = if (video && effective == FlashSetting.Auto) FlashSetting.Off else effective
    val flashChoices = CameraRules.flashChoices(state.caps, state.manual.exposureEnabled)
        .filter { !video || it != FlashSetting.Auto }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (state.caps.hasFlash) {
            CamChip(
                text = stringResource(flash.labelRes(video)),
                selected = flash != FlashSetting.Off,
                enabled = flashChoices.size > 1,
                onClick = onFlash,
            )
        }
        if (!video) {
            CamChip(
                text = stringResource(state.settings.timer.labelRes()),
                selected = state.settings.timer.seconds > 0,
                enabled = !state.busy,
                onClick = onTimer,
            )
            CamChip(text = stringResource(state.settings.ratio.labelRes()), enabled = !state.busy, onClick = onRatio)
        }
        CamChip(
            text = stringResource(state.settings.grid.labelRes()),
            selected = state.settings.grid != GridSetting.Off,
            onClick = onGrid,
        )
        CamChip(
            text = stringResource(R.string.cam_pro),
            selected = panel == CameraPanelKind.Pro || state.manual.active,
            enabled = state.camera != null,
            onClick = { onPanel(CameraPanelKind.Pro) },
        )
        CamChip(
            text = stringResource(R.string.cam_settings),
            selected = panel == CameraPanelKind.Settings,
            enabled = state.camera != null && !state.busy,
            onClick = { onPanel(CameraPanelKind.Settings) },
        )
    }
}

@Composable
private fun GridOverlay() {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val line = Color(0x88FFFFFF)
        val stroke = 1.dp.toPx()
        for (i in 1..2) {
            val x = size.width * i / 3f
            val y = size.height * i / 3f
            drawLine(line, Offset(x, 0f), Offset(x, size.height), strokeWidth = stroke)
            drawLine(line, Offset(0f, y), Offset(size.width, y), strokeWidth = stroke)
        }
    }
}

@Composable
private fun FocusRing(point: Offset?) {
    if (point == null) return
    Canvas(modifier = Modifier.fillMaxSize()) {
        drawCircle(Color.White, radius = 34.dp.toPx(), center = point, style = Stroke(width = 2.dp.toPx()))
    }
}

/** Anzeigen über dem Sucher: Aufnahmezeit, Zoom, besondere Modi, Zoom-Schnellwahl und Countdown. */
@Composable
private fun ViewfinderOverlays(state: CameraUiState, request: BindRequest?, onPreset: (Float) -> Unit) {
    val badges = buildList {
        if (state.mode == CaptureMode.Photo && request != null) {
            if (request.photoFormat != PhotoFormat.Jpeg) add(stringResource(request.photoFormat.labelRes()))
            if (request.extension != ExtensionSetting.None) add(stringResource(request.extension.labelRes()))
        }
        if (state.manual.active) add(stringResource(R.string.cam_badge_manual))
    }
    Box(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (badge in badges) OverlayLabel(badge)
        }
        val recording = state.recording
        if (recording is RecordingState.Active) {
            Row(
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp)
                    .clip(RoundedCornerShape(14.dp)).background(OverlayFill).padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(if (recording.paused) Color.Gray else RecordRed))
                Text(CameraFormat.duration(recording.elapsedNanos), color = WeatherText, fontWeight = FontWeight.SemiBold)
                if (recording.paused) Text(stringResource(R.string.cam_recording_paused), color = WeatherTextMuted)
            }
        } else if (abs(state.zoom - 1f) > 0.05f) {
            OverlayLabel(CameraFormat.zoom(state.zoom), Modifier.align(Alignment.TopCenter).padding(top = 8.dp))
        }
        state.countdown?.let { seconds ->
            Text(
                text = seconds.toString(),
                color = Color.White,
                fontSize = 96.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        val presets = CameraRules.zoomPresets(state.caps)
        if (presets.size > 1) {
            Row(
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (preset in presets) {
                    CamChip(
                        text = CameraFormat.zoom(preset),
                        selected = abs(state.zoom - preset) < 0.05f,
                        onClick = { onPreset(preset) },
                    )
                }
            }
        }
    }
}

@Composable
private fun OverlayLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = WeatherText,
        style = MaterialTheme.typography.labelMedium,
        modifier = modifier.clip(RoundedCornerShape(10.dp)).background(OverlayFill).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
private fun ModeToggle(mode: CaptureMode, enabled: Boolean, onMode: (CaptureMode) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        CamChip(
            text = stringResource(R.string.cam_mode_photo),
            selected = mode == CaptureMode.Photo,
            enabled = enabled,
            onClick = { onMode(CaptureMode.Photo) },
        )
        CamChip(
            text = stringResource(R.string.cam_mode_video),
            selected = mode == CaptureMode.Video,
            enabled = enabled,
            onClick = { onMode(CaptureMode.Video) },
        )
    }
}

@Composable
private fun Controls(state: CameraUiState, viewModel: CameraViewModel, audioGranted: Boolean, compact: Boolean = false) {
    val recording = state.recording
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = if (compact) 8.dp else 28.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(56.dp), contentAlignment = Alignment.Center) {
            if (recording is RecordingState.Active) {
                val label = stringResource(if (recording.paused) R.string.cam_resume else R.string.cam_pause)
                RoundButton(label = label, onClick = viewModel::togglePause) {
                    Text(if (recording.paused) "▶" else "❚❚", color = Color.White, fontSize = 20.sp)
                }
            } else {
                val bitmap = state.thumbnail
                if (bitmap != null) {
                    val label = stringResource(R.string.cam_last_capture)
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = label,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(52.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .border(1.dp, Color(0x99FFFFFF), RoundedCornerShape(12.dp))
                            .clickable(onClickLabel = label, onClick = viewModel::openLast),
                    )
                }
            }
        }

        ShutterButton(
            video = state.mode == CaptureMode.Video,
            recording = state.isRecording,
            label = stringResource(
                when {
                    state.countdown != null -> R.string.cam_cancel_timer
                    state.mode == CaptureMode.Photo -> R.string.cam_shutter_photo
                    state.isRecording -> R.string.cam_shutter_stop
                    else -> R.string.cam_shutter_video_start
                },
            ),
            enabled = state.camera != null && !state.capturing,
            onClick = { viewModel.shutter(audioGranted) },
        )

        Box(modifier = Modifier.size(56.dp), contentAlignment = Alignment.Center) {
            if (state.choices.size > 1 && !state.busy) {
                RoundButton(label = stringResource(R.string.cam_flip), onClick = viewModel::flip) {
                    Icon(
                        painter = painterResource(R.drawable.ic_refresh),
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun RoundButton(label: String, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(OverlayFill)
            .border(1.dp, Color(0x55FFFFFF), CircleShape)
            .semantics { contentDescription = label; role = Role.Button }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun ShutterButton(video: Boolean, recording: Boolean, label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(76.dp)
            .clip(CircleShape)
            .border(4.dp, Color.White, CircleShape)
            .semantics { contentDescription = label; role = Role.Button }
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val fill = if (video) RecordRed else Color.White
        if (recording) {
            Box(Modifier.size(28.dp).clip(RoundedCornerShape(6.dp)).background(fill))
        } else {
            Box(Modifier.size(58.dp).clip(CircleShape).background(fill.copy(alpha = if (enabled) 1f else 0.4f)))
        }
    }
}
