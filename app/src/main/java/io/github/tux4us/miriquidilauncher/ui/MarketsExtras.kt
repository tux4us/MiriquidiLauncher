// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.tux4us.miriquidilauncher.R
import io.github.tux4us.miriquidilauncher.markets.FxData
import io.github.tux4us.miriquidilauncher.markets.FxLogic
import io.github.tux4us.miriquidilauncher.markets.MarketError
import io.github.tux4us.miriquidilauncher.markets.MarketFormat
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** Kurzer Text zu einem Fehler einer Zusatzquelle (ohne gemeinsame Sperrzeit). */
@Composable
internal fun extraErrorText(error: MarketError): String = when (error) {
    MarketError.NETWORK -> stringResource(R.string.markets_error_network)
    MarketError.DATA -> stringResource(R.string.markets_error_data)
    MarketError.RATE_LIMIT -> stringResource(R.string.markets_sec_rate_limit)
    MarketError.KEY -> stringResource(R.string.markets_error_key)
}

// region Wechselkurse --------------------------------------------------------------------------

/** Karte „Wechselkurse“: Betrag von einer Währung in eine andere umrechnen, mit Tauschtaste für die Richtung. */
@Composable
internal fun FxCard(
    from: String,
    to: String,
    fx: FxData?,
    error: MarketError?,
    locale: Locale,
    onPair: (String, String) -> Unit,
    onSwap: () -> Unit,
) {
    var amountText by remember { mutableStateOf("100") }
    val amount = remember(amountText) { FxLogic.parseAmount(amountText) }
    val perEur = fx?.perEur.orEmpty()
    GlassCard(stringResource(R.string.markets_fx)) {
        OutlinedTextField(
            value = amountText,
            onValueChange = { amountText = it },
            singleLine = true,
            label = { Text(stringResource(R.string.markets_fx_amount)) },
            isError = amountText.isNotBlank() && amount == null,
            supportingText = if (amountText.isNotBlank() && amount == null) {
                { Text(stringResource(R.string.markets_holdings_invalid)) }
            } else {
                null
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
            colors = holdingsFieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            CurrencyPicker(stringResource(R.string.markets_fx_from), from, Modifier.weight(1f)) { onPair(it, to) }
            val swapLabel = stringResource(R.string.markets_fx_swap)
            TextButton(
                onClick = onSwap,
                colors = ButtonDefaults.textButtonColors(contentColor = WeatherText),
                modifier = Modifier.semantics { contentDescription = swapLabel },
            ) { Text("⇄", style = MaterialTheme.typography.titleLarge) }
            CurrencyPicker(stringResource(R.string.markets_fx_to), to, Modifier.weight(1f)) { onPair(from, it) }
        }
        val result = amount?.let { FxLogic.convert(it, from, to, perEur) }
        val rate = FxLogic.convert(1.0, from, to, perEur)
        if (amount != null && result != null && rate != null) {
            Text(
                stringResource(
                    R.string.markets_fx_result,
                    MarketFormat.trimmed(amount, locale, 2),
                    from,
                    MarketFormat.number(result, locale, 2),
                    to,
                ),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Start,
            )
            Text(
                stringResource(R.string.markets_fx_rate, from, MarketFormat.trimmed(rate, locale, 6), to),
                style = MaterialTheme.typography.bodySmall,
                color = WeatherTextMuted,
            )
        } else if (amount != null) {
            Text(
                if (error != null) extraErrorText(error) else stringResource(R.string.markets_fx_loading),
                style = MaterialTheme.typography.bodySmall,
                color = WeatherTextMuted,
            )
        }
        fx?.takeIf { it.atMillis > 0L }?.let {
            Text(
                stringResource(R.string.markets_fx_note, formatStampShort(it.atMillis, locale)),
                style = MaterialTheme.typography.bodySmall,
                color = WeatherTextMuted,
            )
        }
    }
}

@Composable
private fun CurrencyPicker(label: String, selected: String, modifier: Modifier, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = WeatherTextMuted)
        OutlinedButton(
            onClick = { open = true },
            colors = ButtonDefaults.outlinedButtonColors(contentColor = WeatherText),
            modifier = Modifier.fillMaxWidth(),
        ) { Text(selected) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            FxLogic.currencies.forEach { code ->
                DropdownMenuItem(
                    text = { Text(code) },
                    onClick = {
                        open = false
                        onSelect(code)
                    },
                )
            }
        }
    }
}

// endregion

private fun formatStampShort(millis: Long, locale: Locale): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, locale).format(Date(millis))
