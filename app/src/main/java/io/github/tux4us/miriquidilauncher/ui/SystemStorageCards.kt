// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.tux4us.miriquidilauncher.R
import io.github.tux4us.miriquidilauncher.system.AnalysisState
import io.github.tux4us.miriquidilauncher.system.AppStorageLogic
import io.github.tux4us.miriquidilauncher.system.DirNode
import io.github.tux4us.miriquidilauncher.system.SystemFormat
import io.github.tux4us.miriquidilauncher.system.TreeState

private const val CACHE_ROWS = 10
private const val TREE_ROWS = 15

/** Öffnet die Einstellungsseite "Nutzungszugriff", wenn möglich direkt für diese App. */
internal fun openUsageAccessSettings(context: Context) {
    val direct = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:${context.packageName}"))
    if (!runCatching { context.startActivity(direct) }.isSuccess) {
        runCatching { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
    }
}

/** Öffnet "Zugriff auf alle Dateien" (Android 11 und neuer), wenn möglich direkt für diese App. */
internal fun openAllFilesSettings(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
    val direct = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
    if (!runCatching { context.startActivity(direct) }.isSuccess) {
        runCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
    }
}

/** App-Info-Seite einer App; dort lässt sich der Cache löschen. */
internal fun openAppInfo(context: Context, packageName: String) {
    runCatching {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }
}

@Composable
internal fun AppCacheCard(
    state: AnalysisState,
    separator: Char,
    onGrant: () -> Unit,
    onReload: () -> Unit,
    onOpenApp: (String) -> Unit,
) {
    GlassCard(stringResource(R.string.system_cache)) {
        val apps = state.apps
        when {
            !state.usageAccess -> {
                Text(stringResource(R.string.system_cache_permission), color = WeatherTextMuted, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = onGrant) { Text(stringResource(R.string.system_cache_grant)) }
            }
            apps == null || state.appsLoading -> Text(stringResource(R.string.system_loading), color = WeatherTextMuted)
            else -> {
                ValueRow(stringResource(R.string.system_cache_total), SystemFormat.bytes(AppStorageLogic.totalCache(apps), 1000, separator))
                ValueRow(stringResource(R.string.system_cache_apps), apps.size.toString())
                AppStorageLogic.topByCache(apps, CACHE_ROWS).forEach { app ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { onOpenApp(app.packageName) }.padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(app.label, color = WeatherText, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text(SystemFormat.bytes(app.cacheBytes, 1000, separator), color = WeatherText)
                    }
                }
                Text(stringResource(R.string.system_cache_hint), color = WeatherTextMuted, style = MaterialTheme.typography.labelSmall)
                TextButton(onClick = onReload) { Text(stringResource(R.string.system_refresh)) }
            }
        }
    }
}

@Composable
internal fun StorageTreeCard(
    state: AnalysisState,
    separator: Char,
    onGrant: () -> Unit,
    onScan: () -> Unit,
    onCancel: () -> Unit,
    onOpen: (DirNode) -> Unit,
    onUp: () -> Unit,
    onDiscard: () -> Unit,
) {
    GlassCard(stringResource(R.string.system_tree)) {
        val tree = state.tree
        when {
            !state.filesAccess -> {
                Text(stringResource(R.string.system_tree_permission), color = WeatherTextMuted, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = onGrant) { Text(stringResource(R.string.system_tree_grant)) }
            }
            tree is TreeState.Idle -> {
                Text(stringResource(R.string.system_tree_intro), color = WeatherTextMuted, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = onScan) { Text(stringResource(R.string.system_tree_scan)) }
            }
            tree is TreeState.Scanning -> {
                Text(
                    stringResource(R.string.system_tree_progress, tree.dirs, tree.files),
                    color = WeatherText,
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(onClick = onCancel) { Text(stringResource(R.string.system_tree_cancel)) }
            }
            tree is TreeState.Done -> TreeContent(tree, separator, onOpen, onUp, onScan, onDiscard)
        }
    }
}

@Composable
private fun TreeContent(
    tree: TreeState.Done,
    separator: Char,
    onOpen: (DirNode) -> Unit,
    onUp: () -> Unit,
    onScan: () -> Unit,
    onDiscard: () -> Unit,
) {
    val current = tree.path.last()
    val breadcrumb = tree.path.mapIndexed { i, node -> if (i == 0) "/" else node.name }.joinToString(" / ").replace("/ /", "/")
    Text(breadcrumb, color = WeatherTextMuted, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
    ValueRow(stringResource(R.string.system_tree_size), SystemFormat.bytes(current.totalBytes, 1000, separator))
    if (tree.path.size > 1) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onUp).padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) { Text(stringResource(R.string.system_tree_up), color = WeatherText) }
    }
    val total = current.totalBytes.coerceAtLeast(1)
    current.children.take(TREE_ROWS).forEach { child ->
        Column(
            modifier = Modifier.fillMaxWidth().clickable { onOpen(child) }.padding(vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(child.name, color = WeatherText, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(SystemFormat.bytes(child.totalBytes, 1000, separator), color = WeatherText)
            }
            UsageBar((child.totalBytes.toDouble() / total).toFloat())
        }
    }
    val hidden = current.children.size - TREE_ROWS
    if (hidden > 0) {
        Text(stringResource(R.string.system_tree_more, hidden), color = WeatherTextMuted, style = MaterialTheme.typography.labelMedium)
    }
    if (current.ownBytes > 0) {
        ValueRow(stringResource(R.string.system_tree_files_here, current.ownFiles), SystemFormat.bytes(current.ownBytes, 1000, separator))
    }
    Text(stringResource(R.string.system_tree_hint), color = WeatherTextMuted, style = MaterialTheme.typography.labelSmall)
    Row {
        TextButton(onClick = onScan) { Text(stringResource(R.string.system_refresh)) }
        TextButton(onClick = onDiscard) { Text(stringResource(R.string.system_tree_discard)) }
    }
}
