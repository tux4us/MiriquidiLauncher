// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import io.github.tux4us.miriquidilauncher.R

/** Öffnet [url] im Browser. Ohne installierten Browser geschieht nichts. */
internal fun openWebLink(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        // Kein Browser vorhanden: nichts zu tun.
    }
}

/** Quellenangabe, die beim Tippen die Seite des Anbieters öffnet. */
@Composable
internal fun AttributionLink(textRes: Int, url: String, onOpen: (String) -> Unit) {
    Text(
        text = stringResource(textRes),
        style = MaterialTheme.typography.bodySmall,
        color = WeatherTextMuted,
        textDecoration = TextDecoration.Underline,
        modifier = Modifier.clickable(role = Role.Button) { onOpen(url) },
    )
}

/** Inhalt der Karte "Info und Lizenzen" in den Einstellungen. */
@Composable
internal fun LicensesSection() {
    val context = LocalContext.current
    var showNotices by rememberSaveable { mutableStateOf(false) }
    val version = remember { appVersion(context) }
    Text(stringResource(R.string.settings_version, version.first, version.second))
    Text(stringResource(R.string.settings_license_text))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlineAction(stringResource(R.string.settings_license_notices), enabled = true, onClick = { showNotices = true })
    }
    if (showNotices) LicensesDialog(onDismiss = { showNotices = false })
}

@Composable
private fun LicensesDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val notices = remember { readNotices(context) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF141A22),
        titleContentColor = WeatherText,
        textContentColor = WeatherText,
        title = { Text(stringResource(R.string.settings_license_notices)) },
        text = {
            Text(
                text = notices,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(contentColor = WeatherText),
            ) { Text(stringResource(R.string.settings_license_close)) }
        },
    )
}

/** Versionsname und -nummer der installierten App; bei einem Fehler ein Strich. */
private fun appVersion(context: Context): Pair<String, Long> =
    try {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val code = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        (info.versionName ?: "?") to code
    } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
        "?" to 0L
    }

private fun readNotices(context: Context): String =
    try {
        context.resources.openRawResource(R.raw.third_party_notices).bufferedReader(Charsets.UTF_8).use { it.readText() }
    } catch (e: java.io.IOException) {
        ""
    }
