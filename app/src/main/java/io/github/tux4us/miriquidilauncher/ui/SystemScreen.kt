// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.ui

import android.Manifest
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.tux4us.miriquidilauncher.R
import io.github.tux4us.miriquidilauncher.system.BatteryInfo
import io.github.tux4us.miriquidilauncher.system.BatterySample
import io.github.tux4us.miriquidilauncher.system.BatteryStatsSummary
import io.github.tux4us.miriquidilauncher.system.CpuInfo
import io.github.tux4us.miriquidilauncher.system.MemorySnapshot
import io.github.tux4us.miriquidilauncher.system.StorageVolumeInfo
import io.github.tux4us.miriquidilauncher.system.SystemFormat
import io.github.tux4us.miriquidilauncher.system.SystemViewModel
import java.text.DecimalFormatSymbols
import java.util.Locale

private val BarTrack = Color(0x33FFFFFF)
private val BarFill = Color(0xFF6CB6FF)
private val BarWarn = Color(0xFFFFB347)
private val BarCritical = Color(0xFFFF6B6B)

/**
 * System-Scope: Arbeitsspeicher, Speicher, Akku, Temperatur und Netzwerkverkehr der App. [active] ist `true`, solange
 * die Seite sichtbar ist; nur dann werden die Werte regelmäßig gelesen.
 */
@Composable
fun SystemScreen(viewModel: SystemViewModel, active: Boolean, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val analysisState by viewModel.analysis.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(active) { viewModel.setActive(active) }
    // Nach der Rückkehr aus den Einstellungen die Zugriffsrechte neu lesen.
    DisposableEffect(active, context) {
        val lifecycle = (context as? ComponentActivity)?.lifecycle
        val observer = LifecycleEventObserver { _, event ->
            if (active && event == Lifecycle.Event.ON_RESUME) viewModel.analysis.refreshAccess()
        }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer) }
    }
    val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        viewModel.analysis.refreshAccess()
    }
    val grantFilesAccess = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            openAllFilesSettings(context)
        } else {
            storagePermission.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }
    val separator = DecimalFormatSymbols.getInstance().decimalSeparator
    val snapshot = state.snapshot

    ScopePage(title = stringResource(R.string.scope_system), modifier = modifier) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = ScopeBottomClearance),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (snapshot == null) {
                item { ScopeEmptyHint(stringResource(R.string.system_loading)) }
            } else {
                snapshot.memory?.let { item { MemoryCard(it, separator) } }
                if (snapshot.storage.isNotEmpty()) item { StorageCard(snapshot.storage, separator) }
                snapshot.battery?.let { item { BatteryCard(it, separator) } }
                item { BatteryStatsCard(state.batteryStats, state.batterySamples, separator, viewModel::clearBatteryHistory) }
                item { ThermalCpuCard(snapshot.thermal.status, snapshot.thermal.headroom, snapshot.cpu, separator) }
                item {
                    AppCacheCard(
                        state = analysisState,
                        separator = separator,
                        onGrant = { openUsageAccessSettings(context) },
                        onReload = viewModel.analysis::loadApps,
                        onOpenApp = { openAppInfo(context, it) },
                    )
                }
                item {
                    StorageTreeCard(
                        state = analysisState,
                        separator = separator,
                        onGrant = grantFilesAccess,
                        onScan = viewModel.analysis::startScan,
                        onCancel = viewModel.analysis::cancelScan,
                        onOpen = viewModel.analysis::open,
                        onUp = viewModel.analysis::up,
                        onDiscard = viewModel.analysis::discard,
                    )
                }
                item { NetworkCard(snapshot.netRxBytes, snapshot.netTxBytes, state.netRxRate, state.netTxRate, separator) }
            }
        }
    }
}

@Composable
internal fun GlassCard(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .glass(RoundedCornerShape(20.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CompositionLocalProvider(LocalContentColor provides WeatherText) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
internal fun ValueRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, color = WeatherTextMuted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, color = WeatherText, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End)
    }
}

@Composable
internal fun UsageBar(fraction: Float, modifier: Modifier = Modifier, lowIsBad: Boolean = false) {
    val f = fraction.coerceIn(0f, 1f)
    val bad = if (lowIsBad) 1f - f else f
    val color = when {
        bad >= 0.9f -> BarCritical
        bad >= 0.75f -> BarWarn
        else -> BarFill
    }
    Box(modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(BarTrack)) {
        Box(Modifier.fillMaxWidth(f).height(10.dp).clip(RoundedCornerShape(5.dp)).background(color))
    }
}

/** Liniendiagramm der Werte [values] im Bereich [minValue]..[maxValue]. Mit weniger als zwei Werten bleibt es leer. */
@Composable
private fun Sparkline(values: List<Float>, modifier: Modifier = Modifier, minValue: Float = 0f, maxValue: Float = 1f) {
    Canvas(modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(8.dp)).background(Color(0x22000000))) {
        if (values.size < 2 || maxValue <= minValue) return@Canvas
        val stepX = size.width / (values.size - 1)
        val path = Path()
        values.forEachIndexed { i, v ->
            val y = size.height - ((v - minValue) / (maxValue - minValue)).coerceIn(0f, 1f) * size.height
            if (i == 0) path.moveTo(0f, y) else path.lineTo(stepX * i, y)
        }
        drawPath(path, BarFill, style = Stroke(width = 3f))
    }
}

@Composable
private fun MemoryCard(memory: MemorySnapshot, separator: Char) {
    val info = memory.info
    GlassCard(stringResource(R.string.system_memory)) {
        UsageBar(info.usedFraction.toFloat())
        ValueRow(
            stringResource(R.string.system_memory_used),
            "${SystemFormat.bytes(info.usedKb * 1024, 1024, separator)} / ${SystemFormat.bytes(info.totalKb * 1024, 1024, separator)} (${percent(info.usedFraction)})",
        )
        ValueRow(stringResource(R.string.system_memory_available), SystemFormat.bytes(info.availableKb * 1024, 1024, separator))
        ValueRow(stringResource(R.string.system_memory_cached), SystemFormat.bytes(info.cachedKb * 1024, 1024, separator))
        if (info.swapTotalKb > 0) {
            ValueRow(
                stringResource(R.string.system_memory_swap),
                "${SystemFormat.bytes(info.swapUsedKb * 1024, 1024, separator)} / ${SystemFormat.bytes(info.swapTotalKb * 1024, 1024, separator)}",
            )
        }
        if (memory.lowMemory) Text(stringResource(R.string.system_memory_low), color = BarCritical)
    }
}

@Composable
private fun StorageCard(volumes: List<StorageVolumeInfo>, separator: Char) {
    GlassCard(stringResource(R.string.system_storage)) {
        volumes.forEachIndexed { index, volume ->
            if (index > 0) Box(Modifier.height(4.dp))
            Text(
                stringResource(if (volume.isRemovable) R.string.system_storage_sd else R.string.system_storage_internal),
                color = WeatherTextMuted,
                style = MaterialTheme.typography.labelLarge,
            )
            UsageBar(volume.usedFraction.toFloat())
            ValueRow(
                stringResource(R.string.system_storage_used),
                "${SystemFormat.bytes(volume.usedBytes, 1000, separator)} / ${SystemFormat.bytes(volume.totalBytes, 1000, separator)} (${percent(volume.usedFraction)})",
            )
            ValueRow(stringResource(R.string.system_storage_free), SystemFormat.bytes(volume.freeBytes, 1000, separator))
        }
    }
}

@Composable
private fun BatteryCard(battery: BatteryInfo, separator: Char) {
    GlassCard(stringResource(R.string.system_battery)) {
        UsageBar(battery.level / 100f, lowIsBad = true)
        ValueRow(stringResource(R.string.system_battery_level), "${battery.level} %")
        ValueRow(stringResource(R.string.system_battery_state), stringResource(statusText(battery.status)))
        ValueRow(stringResource(R.string.system_battery_health), stringResource(healthText(battery.health)))
        battery.tempTenthsC?.let { ValueRow(stringResource(R.string.system_battery_temperature), decimal(it / 10.0, separator) + " °C") }
        battery.voltageMv?.let { ValueRow(stringResource(R.string.system_battery_voltage), decimal(it / 1000.0, separator, 2) + " V") }
        battery.currentMilliAmp?.let { ValueRow(stringResource(R.string.system_battery_current), "$it mA") }
        if (battery.plugged != 0) ValueRow(stringResource(R.string.system_battery_source), stringResource(pluggedText(battery.plugged)))
        battery.cycles?.let { ValueRow(stringResource(R.string.system_battery_cycles), it.toString()) }
        battery.technology?.let { ValueRow(stringResource(R.string.system_battery_technology), it) }
    }
}

@Composable
private fun BatteryStatsCard(
    stats: BatteryStatsSummary,
    samples: List<BatterySample>,
    separator: Char,
    onClear: () -> Unit,
) {
    GlassCard(stringResource(R.string.system_stats)) {
        if (stats.sampleCount < 2) {
            Text(stringResource(R.string.system_stats_empty), color = WeatherTextMuted, style = MaterialTheme.typography.bodyMedium)
        } else {
            val recent = samples.takeLast(300)
            Sparkline(recent.map { it.level / 100f })
            ValueRow(stringResource(R.string.system_stats_span), duration(stats.spanMillis))
            stats.dischargePctPerHour?.let {
                ValueRow(stringResource(R.string.system_stats_discharge), decimal(it, separator) + " %/h")
            }
            stats.hoursRemaining?.let {
                ValueRow(stringResource(R.string.system_stats_remaining), duration((it * 3_600_000).toLong()))
            }
            stats.chargePctPerHour?.let {
                ValueRow(stringResource(R.string.system_stats_charge), decimal(it, separator) + " %/h")
            }
            ValueRow(stringResource(R.string.system_stats_sessions), stats.chargeSessions.toString())
            stats.minLevel?.let { ValueRow(stringResource(R.string.system_stats_min_level), "$it %") }
            stats.avgTempC?.let { ValueRow(stringResource(R.string.system_stats_temp_avg), decimal(it, separator) + " °C") }
            stats.maxTempC?.let { ValueRow(stringResource(R.string.system_stats_temp_max), decimal(it, separator) + " °C") }
        }
        Text(stringResource(R.string.system_stats_hint), color = WeatherTextMuted, style = MaterialTheme.typography.labelSmall)
        if (stats.sampleCount > 0) {
            TextButton(onClick = onClear) { Text(stringResource(R.string.system_stats_clear)) }
        }
    }
}

@Composable
private fun ThermalCpuCard(status: Int?, headroom: Float?, cpu: CpuInfo, separator: Char) {
    GlassCard(stringResource(R.string.system_cpu)) {
        ValueRow(stringResource(R.string.system_thermal), status?.let { stringResource(thermalText(it)) } ?: stringResource(R.string.system_unknown))
        headroom?.let { ValueRow(stringResource(R.string.system_thermal_headroom), decimal(it.toDouble(), separator, 2)) }
        cpu.temperatureC?.let { ValueRow(stringResource(R.string.system_cpu_temperature), decimal(it, separator) + " °C") }
        ValueRow(stringResource(R.string.system_cpu_cores), cpu.cores.toString())
        ValueRow(stringResource(R.string.system_cpu_hardware), listOfNotNull(cpu.socModel, cpu.hardware).distinct().joinToString(" · "))
        cpu.abi?.let { ValueRow(stringResource(R.string.system_cpu_abi), it) }

        Text(stringResource(R.string.system_cpu_hint), color = WeatherTextMuted, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun NetworkCard(rx: Long?, tx: Long?, rxRate: Long?, txRate: Long?, separator: Char) {
    GlassCard(stringResource(R.string.system_network)) {
        if (rx == null || tx == null) {
            Text(stringResource(R.string.system_unknown), color = WeatherTextMuted)
        } else {
            ValueRow(stringResource(R.string.system_network_received), SystemFormat.bytes(rx, 1000, separator))
            ValueRow(stringResource(R.string.system_network_sent), SystemFormat.bytes(tx, 1000, separator))
            if (rxRate != null && txRate != null) {
                ValueRow(stringResource(R.string.system_network_rate), "↓ ${SystemFormat.bytes(rxRate, 1000, separator)}/s  ↑ ${SystemFormat.bytes(txRate, 1000, separator)}/s")
            }
        }
        Text(stringResource(R.string.system_network_hint), color = WeatherTextMuted, style = MaterialTheme.typography.labelSmall)
    }
}

private fun percent(fraction: Double): String = "${(fraction * 100).toInt()} %"

private fun decimal(value: Double, separator: Char, digits: Int = 1): String =
    String.format(Locale.ROOT, "%.${digits}f", value).replace('.', separator)

private fun duration(millis: Long): String {
    val minutes = millis / 60_000
    val days = minutes / (24 * 60)
    val hours = (minutes / 60) % 24
    val mins = minutes % 60
    return when {
        days > 0 -> "$days d $hours h"
        hours > 0 -> "$hours h $mins min"
        else -> "$mins min"
    }
}

private fun statusText(status: Int): Int = when (status) {
    BatteryManager.BATTERY_STATUS_CHARGING -> R.string.system_status_charging
    BatteryManager.BATTERY_STATUS_DISCHARGING -> R.string.system_status_discharging
    BatteryManager.BATTERY_STATUS_FULL -> R.string.system_status_full
    BatteryManager.BATTERY_STATUS_NOT_CHARGING -> R.string.system_status_not_charging
    else -> R.string.system_unknown
}

private fun healthText(health: Int): Int = when (health) {
    BatteryManager.BATTERY_HEALTH_GOOD -> R.string.system_health_good
    BatteryManager.BATTERY_HEALTH_OVERHEAT -> R.string.system_health_overheat
    BatteryManager.BATTERY_HEALTH_DEAD -> R.string.system_health_dead
    BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> R.string.system_health_over_voltage
    BatteryManager.BATTERY_HEALTH_COLD -> R.string.system_health_cold
    BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> R.string.system_health_failure
    else -> R.string.system_unknown
}

private fun pluggedText(plugged: Int): Int = when {
    plugged and BatteryManager.BATTERY_PLUGGED_AC != 0 -> R.string.system_plugged_ac
    plugged and BatteryManager.BATTERY_PLUGGED_USB != 0 -> R.string.system_plugged_usb
    plugged and BatteryManager.BATTERY_PLUGGED_WIRELESS != 0 -> R.string.system_plugged_wireless
    else -> R.string.system_plugged_other
}

private fun thermalText(status: Int): Int = when (status) {
    PowerManager.THERMAL_STATUS_NONE -> R.string.system_thermal_none
    PowerManager.THERMAL_STATUS_LIGHT -> R.string.system_thermal_light
    PowerManager.THERMAL_STATUS_MODERATE -> R.string.system_thermal_moderate
    PowerManager.THERMAL_STATUS_SEVERE -> R.string.system_thermal_severe
    PowerManager.THERMAL_STATUS_CRITICAL -> R.string.system_thermal_critical
    PowerManager.THERMAL_STATUS_EMERGENCY -> R.string.system_thermal_emergency
    PowerManager.THERMAL_STATUS_SHUTDOWN -> R.string.system_thermal_shutdown
    else -> R.string.system_unknown
}
