// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.tux4us.miriquidilauncher.R
import io.github.tux4us.miriquidilauncher.music.EqMath
import io.github.tux4us.miriquidilauncher.music.EqPresets
import io.github.tux4us.miriquidilauncher.music.EqSettings
import io.github.tux4us.miriquidilauncher.music.MusicUiState
import io.github.tux4us.miriquidilauncher.music.MusicViewModel
import kotlin.math.log10
import kotlin.math.roundToInt

private val BandLabels = listOf("31", "62", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")

@Composable
private fun presetName(id: String): String = when (id) {
    "flat" -> stringResource(R.string.music_preset_flat)
    "bass" -> stringResource(R.string.music_preset_bass)
    "treble" -> stringResource(R.string.music_preset_treble)
    "rock" -> stringResource(R.string.music_preset_rock)
    "pop" -> stringResource(R.string.music_preset_pop)
    "jazz" -> stringResource(R.string.music_preset_jazz)
    "classical" -> stringResource(R.string.music_preset_classical)
    "vocal" -> stringResource(R.string.music_preset_vocal)
    "electronic" -> stringResource(R.string.music_preset_electronic)
    "acoustic" -> stringResource(R.string.music_preset_acoustic)
    "loudness" -> stringResource(R.string.music_preset_loudness)
    else -> id
}

/** Equalizer-Tab: Ein/Aus, Presets, Frequenzgang, zehn Bänder, Bass, Vorverstärkung und Übersteuerungsschutz. */
@Composable
internal fun EqualizerTab(state: MusicUiState, viewModel: MusicViewModel) {
    val eq = state.data.eq
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        GlassCard(stringResource(R.string.music_eq_title)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.music_eq_enabled), color = WeatherText, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Switch(
                    checked = eq.enabled,
                    onCheckedChange = viewModel::setEqEnabled,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.Black,
                        checkedTrackColor = WeatherText,
                        uncheckedThumbColor = WeatherTextMuted,
                        uncheckedTrackColor = Color(0x33FFFFFF),
                        uncheckedBorderColor = WeatherTextMuted,
                    ),
                )
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EqPresets.all.forEach { preset ->
                    SegmentChip(presetName(preset.id), eq.preset == preset.id) { viewModel.applyPreset(preset) }
                }
            }
            ResponseCurve(eq)
        }
        GlassCard(stringResource(R.string.music_eq_bands)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                for (i in 0 until EqMath.BAND_COUNT) {
                    BandSlider(BandLabels[i], eq.bandsDb[i], eq.enabled, Modifier.weight(1f)) { viewModel.setEqBand(i, it) }
                }
            }
            Text(stringResource(R.string.music_eq_hint), color = WeatherTextMuted, style = MaterialTheme.typography.bodySmall)
        }
        GlassCard(stringResource(R.string.music_eq_extras)) {
            LabeledSlider(stringResource(R.string.music_eq_bass), eq.bassDb, 0f, EqMath.MAX_DB, eq.enabled) { viewModel.setEqBass(it) }
            LabeledSlider(stringResource(R.string.music_eq_preamp), eq.preampDb, EqMath.MIN_DB, EqMath.MAX_DB, eq.enabled) { viewModel.setEqPreamp(it) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.music_eq_protect), color = WeatherText, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(end = 12.dp))
                Switch(
                    checked = eq.protect,
                    onCheckedChange = viewModel::setEqProtect,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.Black,
                        checkedTrackColor = WeatherText,
                        uncheckedThumbColor = WeatherTextMuted,
                        uncheckedTrackColor = Color(0x33FFFFFF),
                        uncheckedBorderColor = WeatherTextMuted,
                    ),
                )
            }
            val total = remember(eq) { EqMath.totalGainDb(eq) }
            Text(
                stringResource(R.string.music_eq_total_gain, formatDb(total.toFloat())),
                color = WeatherTextMuted,
                style = MaterialTheme.typography.bodySmall,
            )
            PodcastTextButton(stringResource(R.string.music_eq_reset)) { viewModel.resetEq() }
        }
        Box(Modifier.height(8.dp))
    }
}

private fun formatDb(db: Float): String {
    val rounded = (db * 10).roundToInt() / 10f
    return (if (rounded > 0f) "+" else "") + String.format(java.util.Locale.getDefault(), "%.1f", rounded) + " dB"
}

/** Frequenzgang von 20 Hz bis 20 kHz (logarithmisch) mit 0-dB-Linie; Skala ±12 dB. */
@Composable
private fun ResponseCurve(eq: EqSettings) {
    val points = remember(eq) {
        val steps = 120
        FloatArray(steps + 1) { i ->
            val f = 20.0 * Math.pow(1000.0, i.toDouble() / steps)
            EqMath.responseDb(eq, f).toFloat()
        }
    }
    Canvas(Modifier.fillMaxWidth().height(120.dp)) {
        val range = 15f
        fun y(db: Float) = size.height / 2f - (db.coerceIn(-range, range) / range) * (size.height / 2f)
        val grid = Color(0x33FFFFFF)
        drawLine(grid, Offset(0f, y(0f)), Offset(size.width, y(0f)), strokeWidth = 1.5f)
        for (db in listOf(-10f, -5f, 5f, 10f)) drawLine(Color(0x14FFFFFF), Offset(0f, y(db)), Offset(size.width, y(db)), strokeWidth = 1f)
        for (hz in listOf(100.0, 1000.0, 10000.0)) {
            val x = (log10(hz / 20.0) / 3.0).toFloat() * size.width
            drawLine(Color(0x14FFFFFF), Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
        }
        val path = Path()
        points.forEachIndexed { i, db ->
            val x = i.toFloat() / (points.size - 1) * size.width
            if (i == 0) path.moveTo(x, y(db)) else path.lineTo(x, y(db))
        }
        drawPath(path, if (eq.enabled) MusicAccent else WeatherTextMuted, style = Stroke(width = 5f, cap = StrokeCap.Round))
    }
}

/** Senkrechter Regler eines Bandes: ziehen ändert in 0,5-dB-Schritten, doppeltes Tippen setzt auf 0 dB zurück. */
@Composable
private fun BandSlider(label: String, value: Float, enabled: Boolean, modifier: Modifier, onChange: (Float) -> Unit) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            formatDb(value).removeSuffix(" dB"),
            color = if (enabled) WeatherText else WeatherTextMuted,
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
        val track = Color(0x33FFFFFF)
        val active = if (enabled) MusicAccent else WeatherTextMuted
        Canvas(
            Modifier
                .width(32.dp)
                .height(160.dp)
                .padding(vertical = 8.dp)
                .pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = { onChange(0f) })
                }
                .pointerInput(Unit) {
                    detectVerticalDragGestures { change, _ ->
                        val fraction = (1f - change.position.y / size.height).coerceIn(0f, 1f)
                        val db = EqMath.MIN_DB + fraction * (EqMath.MAX_DB - EqMath.MIN_DB)
                        onChange((db * 2).roundToInt() / 2f)
                    }
                },
        ) {
            val cx = size.width / 2f
            val range = EqMath.MAX_DB - EqMath.MIN_DB
            val yv = size.height * (1f - (value - EqMath.MIN_DB) / range)
            val y0 = size.height * (1f - (0f - EqMath.MIN_DB) / range)
            drawLine(track, Offset(cx, 0f), Offset(cx, size.height), strokeWidth = 6f, cap = StrokeCap.Round)
            drawLine(active, Offset(cx, y0), Offset(cx, yv), strokeWidth = 6f, cap = StrokeCap.Round)
            drawLine(Color(0x66FFFFFF), Offset(cx - 10f, y0), Offset(cx + 10f, y0), strokeWidth = 2f)
            drawCircle(active, radius = 11f, center = Offset(cx, yv))
        }
        Text(label, color = WeatherTextMuted, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

@Composable
private fun LabeledSlider(label: String, value: Float, min: Float, max: Float, enabled: Boolean, onChange: (Float) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth()) {
            Text(label, color = WeatherText, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(formatDb(value), color = WeatherTextMuted, style = MaterialTheme.typography.bodyMedium)
        }
        Slider(
            value = value,
            onValueChange = { onChange((it * 2).roundToInt() / 2f) },
            valueRange = min..max,
            enabled = enabled,
            colors = SliderDefaults.colors(
                thumbColor = MusicAccent,
                activeTrackColor = MusicAccent,
                inactiveTrackColor = Color(0x33FFFFFF),
                disabledThumbColor = WeatherTextMuted,
                disabledActiveTrackColor = WeatherTextMuted,
                disabledInactiveTrackColor = Color(0x22FFFFFF),
            ),
        )
    }
}
