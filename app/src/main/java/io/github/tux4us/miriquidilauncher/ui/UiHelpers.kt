// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.ui

import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Farben für Textfelder in den dunklen Dialogen und Karten der Scopes. */
@Composable
internal fun filesFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = WeatherText,
    unfocusedTextColor = WeatherText,
    focusedBorderColor = WeatherText,
    unfocusedBorderColor = WeatherTextMuted,
    focusedLabelColor = WeatherText,
    unfocusedLabelColor = WeatherTextMuted,
    cursorColor = WeatherText,
)

/** Datum und Uhrzeit in kurzer, ortsüblicher Form; leer bei `millis <= 0`. */
internal fun formatDateTime(millis: Long, locale: Locale): String =
    if (millis <= 0L) {
        ""
    } else {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(locale)
            .format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))
    }
