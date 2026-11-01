// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
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
import io.github.tux4us.miriquidilauncher.system.SystemFormat

private const val CACHE_ROWS = 10

/** Öffnet die Einstellungsseite "Nutzungszugriff", wenn möglich direkt für diese App. */
internal fun openUsageAccessSettings(context: Context) {
    val direct = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:${context.packageName}"))
    if (!runCatching { context.startActivity(direct) }.isSuccess) {
        runCatching { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
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
