// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.tux4us.miriquidilauncher.R
import io.github.tux4us.miriquidilauncher.support.SupportPrompt

/** Inhalt der Karte „Entwickler unterstützen“ in den Einstellungen. */
@Composable
internal fun SupportSection() {
    val context = LocalContext.current
    Text(stringResource(R.string.settings_support_text))
    Text(
        stringResource(R.string.settings_support_note),
        style = MaterialTheme.typography.bodySmall,
        color = WeatherTextMuted,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlineAction(
            stringResource(R.string.settings_support_button), enabled = true,
            onClick = { openWebLink(context, SupportPrompt.DONATE_URL) },
        )
    }
}

/**
 * Einmaliger Hinweis nach einem Update. Beide Schaltflächen sind gleich gestaltet; „Nicht jetzt“ und Tippen
 * außerhalb des Dialogs schließen ihn ohne Folgen. Die Spendenseite öffnet erst das Tippen auf die Spenden-Schaltfläche.
 */
@Composable
internal fun SupportPromptDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val versionName = remember { appVersion(context).first }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF141A22),
        titleContentColor = WeatherText,
        textContentColor = WeatherText,
        title = { Text(stringResource(R.string.support_prompt_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.support_prompt_updated, versionName))
                Text(stringResource(R.string.support_prompt_text))
                Text(
                    stringResource(R.string.support_prompt_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = WeatherTextMuted,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    openWebLink(context, SupportPrompt.DONATE_URL)
                    onDismiss()
                },
                colors = ButtonDefaults.textButtonColors(contentColor = WeatherText),
            ) { Text(stringResource(R.string.support_prompt_donate)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, colors = ButtonDefaults.textButtonColors(contentColor = WeatherText)) {
                Text(stringResource(R.string.support_prompt_later))
            }
        },
    )
}
