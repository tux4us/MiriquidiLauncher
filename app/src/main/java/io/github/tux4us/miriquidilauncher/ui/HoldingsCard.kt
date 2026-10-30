// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.tux4us.miriquidilauncher.R
import io.github.tux4us.miriquidilauncher.markets.MarketFormat
import io.github.tux4us.miriquidilauncher.markets.MarketsUiState
import io.github.tux4us.miriquidilauncher.markets.PositionsLogic
import java.util.Locale

private val HoldingsError = Color(0xFFFF8A80)

/**
 * Ausblendbare Card „Eigene Werte“ am Ende der Scope „Kurse“: Aktien, Rohstoffe und Krypto-Werte mit Mengen eintragen;
 * darunter die Werte in Euro und US-Dollar. Ein Tipp auf die Kopfzeile blendet Eingaben und Werte ein oder aus.
 */
@Composable
internal fun HoldingsCard(
    visible: Boolean,
    locale: Locale,
    onToggle: (Boolean) -> Unit,
    market: MarketsUiState,
    actions: SecurityActions,
) {
    val expanded = stringResource(R.string.markets_holdings_expanded)
    val collapsed = stringResource(R.string.markets_holdings_collapsed)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .glass(RoundedCornerShape(20.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CompositionLocalProvider(LocalContentColor provides WeatherText) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button) { onToggle(!visible) }
                    .semantics { stateDescription = if (visible) expanded else collapsed },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.markets_holdings),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    stringResource(if (visible) R.string.markets_holdings_hide else R.string.markets_holdings_show),
                    style = MaterialTheme.typography.labelLarge,
                    color = WeatherTextMuted,
                )
            }
            if (visible) {
                OwnValueInputs(market, actions)
                HorizontalDivider(color = WeatherTextMuted.copy(alpha = 0.4f))
                HoldingsValues(locale, market)
                Text(
                    stringResource(R.string.markets_holdings_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = WeatherTextMuted,
                )
            }
        }
    }
}

@Composable
private fun HoldingsValues(locale: Locale, market: MarketsUiState) {
    if (market.positions.isEmpty()) {
        Text(stringResource(R.string.markets_holdings_empty), style = MaterialTheme.typography.bodyMedium, color = WeatherTextMuted)
        return
    }
    val snapshot = market.snapshot
    val valuation = remember(market.positions, snapshot) { PositionsLogic.value(market.positions, snapshot.assetPrices()) }
    OwnValueRows(valuation, locale)
    HorizontalDivider(color = WeatherTextMuted.copy(alpha = 0.4f))
    val eurUsd = snapshot.eurUsd()
    val totalEur = valuation.totalEur
    val totalUsd = if (totalEur != null && eurUsd != null) totalEur * eurUsd else null
    ValueRow(
        stringResource(R.string.markets_holdings_total_eur),
        totalEur?.let { MarketFormat.money(it, "EUR", locale) } ?: stringResource(R.string.markets_holdings_unavailable),
    )
    ValueRow(
        stringResource(R.string.markets_holdings_total_usd),
        totalUsd?.let { MarketFormat.money(it, "USD", locale) } ?: stringResource(R.string.markets_holdings_unavailable),
    )
}

@Composable
internal fun holdingsFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = WeatherText,
    unfocusedTextColor = WeatherText,
    errorTextColor = WeatherText,
    cursorColor = WeatherText,
    focusedBorderColor = WeatherText,
    unfocusedBorderColor = WeatherTextMuted,
    errorBorderColor = HoldingsError,
    focusedLabelColor = WeatherText,
    unfocusedLabelColor = WeatherTextMuted,
    errorLabelColor = HoldingsError,
    focusedSuffixColor = WeatherTextMuted,
    unfocusedSuffixColor = WeatherTextMuted,
    errorSuffixColor = HoldingsError,
    errorSupportingTextColor = HoldingsError,
)
