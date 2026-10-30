// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.tux4us.miriquidilauncher.R
import io.github.tux4us.miriquidilauncher.camera.CameraFormat
import io.github.tux4us.miriquidilauncher.camera.CameraRules
import io.github.tux4us.miriquidilauncher.camera.CameraUiState
import io.github.tux4us.miriquidilauncher.camera.CameraViewModel
import io.github.tux4us.miriquidilauncher.camera.CaptureMode
import io.github.tux4us.miriquidilauncher.camera.ExtensionSetting
import io.github.tux4us.miriquidilauncher.camera.FrameRateSetting
import io.github.tux4us.miriquidilauncher.camera.JpegQuality
import io.github.tux4us.miriquidilauncher.camera.LensFacing
import io.github.tux4us.miriquidilauncher.camera.ManualControls
import io.github.tux4us.miriquidilauncher.camera.PriorityMode
import io.github.tux4us.miriquidilauncher.camera.VideoQualitySetting
import kotlin.math.roundToInt

/* Bausteine und Panels der Kamera-Scope: Auswahl-Chips, Schalter, Regler und die Panels „Einstellungen“ und „Pro“. */

private val PanelFill = Color(0xCC000000)
private val ChipFill = Color(0x99000000)
private val ChipSelectedFill = Color(0xE6FFFFFF)
private val ChipBorder = Color(0x55FFFFFF)

@Composable
internal fun CamChip(
    text: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(18.dp)
    Box(
        modifier = modifier
            .heightIn(min = 40.dp)
            .clip(shape)
            .background(if (selected) ChipSelectedFill else ChipFill)
            .border(1.dp, ChipBorder, shape)
            .semantics { this.selected = selected; role = Role.Button }
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) Color.Black else WeatherText.copy(alpha = if (enabled) 1f else 0.4f),
            maxLines = 1,
        )
    }
}

@Composable
internal fun CameraPanel(
    title: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp)
            .glass(shape)
            .background(PanelFill)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = WeatherText,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onClose) { Text(stringResource(R.string.cam_close), color = WeatherText) }
        }
        Column(
            modifier = Modifier
                .heightIn(max = 320.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun OptionRow(title: String, content: @Composable RowScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = WeatherTextMuted)
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = WeatherText.copy(alpha = if (enabled) 1f else 0.4f),
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.Black,
                checkedTrackColor = Color.White,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = Color(0x55FFFFFF),
            ),
        )
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    enabled: Boolean = true,
    onChange: (Float) -> Unit,
) {
    Column {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = WeatherTextMuted)
            Text(valueText, style = MaterialTheme.typography.labelMedium, color = WeatherText)
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
            enabled = enabled,
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color.White,
                inactiveTrackColor = Color(0x55FFFFFF),
            ),
        )
    }
}

/** Regler über feste Stufen; bei weniger als zwei Stufen gibt es nichts zu verstellen und nur den Wert als Text. */
@Composable
private fun IndexSlider(label: String, valueText: String, index: Int, count: Int, enabled: Boolean = true, onIndex: (Int) -> Unit) {
    if (count < 2) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = WeatherTextMuted)
            Text(valueText, style = MaterialTheme.typography.labelMedium, color = WeatherText)
        }
        return
    }
    LabeledSlider(
        label = label,
        valueText = valueText,
        value = index.toFloat(),
        range = 0f..(count - 1).toFloat(),
        steps = count - 2,
        enabled = enabled,
        onChange = { onIndex(it.roundToInt().coerceIn(0, count - 1)) },
    )
}

@Composable
internal fun CameraSettingsPanel(
    state: CameraUiState,
    viewModel: CameraViewModel,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings = state.settings
    val caps = state.caps
    val video = state.mode == CaptureMode.Video
    CameraPanel(title = stringResource(R.string.cam_set_title), onClose = onClose, modifier = modifier) {
        if (state.choices.size > 1) {
            OptionRow(stringResource(R.string.cam_set_camera)) {
                for (choice in state.choices) {
                    CamChip(
                        text = stringResource(choice.facing.labelRes(), choice.ordinal),
                        selected = choice.id == state.camera?.id,
                        onClick = { viewModel.selectCamera(choice) },
                    )
                }
            }
        }
        if (!video) {
            if (caps.photoFormats.size > 1) {
                OptionRow(stringResource(R.string.cam_set_format)) {
                    for (format in caps.photoFormats) {
                        CamChip(
                            text = stringResource(format.labelRes()),
                            selected = settings.photoFormat == format,
                            onClick = { viewModel.updateSettings { it.copy(photoFormat = format) } },
                        )
                    }
                }
            }
            if (caps.extensions.isNotEmpty()) {
                OptionRow(stringResource(R.string.cam_set_extension)) {
                    for (extension in ExtensionSetting.entries.filter { it == ExtensionSetting.None || it in caps.extensions }) {
                        CamChip(
                            text = stringResource(extension.labelRes()),
                            selected = settings.extension == extension,
                            onClick = { viewModel.updateSettings { it.copy(extension = extension) } },
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.cam_set_extension_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = WeatherTextMuted,
                )
            }
            OptionRow(stringResource(R.string.cam_set_priority)) {
                for (priority in PriorityMode.entries) {
                    CamChip(
                        text = stringResource(priority.labelRes()),
                        selected = settings.priority == priority,
                        onClick = { viewModel.updateSettings { it.copy(priority = priority) } },
                    )
                }
            }
            OptionRow(stringResource(R.string.cam_set_jpeg)) {
                for (quality in JpegQuality.entries) {
                    CamChip(
                        text = stringResource(R.string.cam_percent, quality.percent),
                        selected = settings.jpegQuality == quality,
                        onClick = { viewModel.updateSettings { it.copy(jpegQuality = quality) } },
                    )
                }
            }
        } else {
            OptionRow(stringResource(R.string.cam_set_vquality)) {
                for (quality in listOf(VideoQualitySetting.Highest) + caps.videoQualities) {
                    CamChip(
                        text = stringResource(quality.labelRes()),
                        selected = settings.videoQuality == quality,
                        onClick = { viewModel.updateSettings { it.copy(videoQuality = quality) } },
                    )
                }
            }
            if (caps.frameRates.isNotEmpty()) {
                OptionRow(stringResource(R.string.cam_set_fps)) {
                    for (rate in FrameRateSetting.entries.filter { it.fps == null || it.fps in caps.frameRates }) {
                        CamChip(
                            text = stringResource(rate.labelRes()),
                            selected = settings.frameRate == rate,
                            onClick = { viewModel.updateSettings { it.copy(frameRate = rate) } },
                        )
                    }
                }
            }
            if (caps.stabilization) {
                SwitchRow(
                    title = stringResource(R.string.cam_set_stabilization),
                    checked = settings.stabilization,
                    onChange = { value -> viewModel.updateSettings { it.copy(stabilization = value) } },
                )
            }
            SwitchRow(
                title = stringResource(R.string.cam_set_audio),
                checked = settings.audio,
                onChange = { value -> viewModel.updateSettings { it.copy(audio = value) } },
            )
        }
        if (state.choices.any { it.facing == LensFacing.Front }) {
            SwitchRow(
                title = stringResource(R.string.cam_set_mirror),
                checked = settings.mirrorFront,
                onChange = { value -> viewModel.updateSettings { it.copy(mirrorFront = value) } },
            )
        }
        Text(
            text = stringResource(R.string.cam_set_storage_note),
            style = MaterialTheme.typography.bodySmall,
            color = WeatherTextMuted,
        )
    }
}

@Composable
internal fun CameraProPanel(
    state: CameraUiState,
    viewModel: CameraViewModel,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val caps = state.caps
    val manual = state.manual
    val manualCaps = caps.manual
    val recording = state.isRecording
    CameraPanel(title = stringResource(R.string.cam_pro_title), onClose = onClose, modifier = modifier) {
        if (caps.canZoom) {
            LabeledSlider(
                label = stringResource(R.string.cam_pro_zoom),
                valueText = CameraFormat.zoom(state.zoom),
                value = state.zoom,
                range = caps.zoomMin..caps.zoomMax,
                onChange = { viewModel.setZoom(it) },
            )
        }
        if (caps.hasExposureCompensation && !manual.exposureEnabled) {
            IndexSlider(
                label = stringResource(R.string.cam_pro_ev),
                valueText = CameraRules.evText(state.evIndex, caps.evStep),
                index = state.evIndex - caps.evMin,
                count = caps.evMax - caps.evMin + 1,
                onIndex = { viewModel.setEv(it + caps.evMin) },
            )
        }
        if (manualCaps != null) {
            SwitchRow(
                title = stringResource(R.string.cam_pro_exposure_manual),
                checked = manual.exposureEnabled,
                enabled = !recording,
                onChange = { viewModel.setManual(manual.copy(exposureEnabled = it)) },
            )
            if (manual.exposureEnabled) {
                val isoStops = ManualControls.isoStops(manualCaps)
                val isoIndex = ManualControls.nearestIsoIndex(isoStops, manual.iso).coerceAtLeast(0)
                IndexSlider(
                    label = stringResource(R.string.cam_pro_iso),
                    valueText = CameraFormat.iso(isoStops[isoIndex]),
                    index = isoIndex,
                    count = isoStops.size,
                    onIndex = { viewModel.setManual(manual.copy(iso = isoStops[it])) },
                )
                val shutterStops = ManualControls.shutterStops(manualCaps)
                val shutterIndex = ManualControls.nearestIndex(shutterStops, manual.exposureNs).coerceAtLeast(0)
                IndexSlider(
                    label = stringResource(R.string.cam_pro_shutter),
                    valueText = CameraFormat.shutter(shutterStops[shutterIndex]),
                    index = shutterIndex,
                    count = shutterStops.size,
                    onIndex = { viewModel.setManual(manual.copy(exposureNs = shutterStops[it])) },
                )
            }
            if (manualCaps.hasManualFocus) {
                SwitchRow(
                    title = stringResource(R.string.cam_pro_focus_manual),
                    checked = manual.focusEnabled,
                    enabled = !recording,
                    onChange = { viewModel.setManual(manual.copy(focusEnabled = it)) },
                )
                if (manual.focusEnabled) {
                    LabeledSlider(
                        label = stringResource(R.string.cam_pro_focus),
                        valueText = CameraFormat.focus(ManualControls.focusDiopters(manual.focusFraction, manualCaps)),
                        value = manual.focusFraction,
                        range = 0f..1f,
                        onChange = { viewModel.setManual(manual.copy(focusFraction = it)) },
                    )
                }
            }
        } else if (caps.whiteBalances.size <= 1) {
            Text(
                text = stringResource(R.string.cam_pro_unsupported),
                style = MaterialTheme.typography.bodySmall,
                color = WeatherTextMuted,
            )
        }
        if (caps.whiteBalances.size > 1) {
            OptionRow(stringResource(R.string.cam_pro_wb)) {
                for (balance in caps.whiteBalances) {
                    CamChip(
                        text = stringResource(balance.labelRes()),
                        selected = manual.whiteBalance == balance,
                        enabled = !recording,
                        onClick = { viewModel.setManual(manual.copy(whiteBalance = balance)) },
                    )
                }
            }
        }
        if (manual.active) {
            CamChip(
                text = stringResource(R.string.cam_pro_reset),
                enabled = !recording,
                onClick = { viewModel.resetManual() },
            )
        }
    }
}
