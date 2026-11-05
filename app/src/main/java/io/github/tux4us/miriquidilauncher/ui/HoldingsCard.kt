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
import io.github.tux4us.miriquidilauncher.markets.PortfolioHistory
import io.github.tux4us.miriquidilauncher.markets.PositionsLogic
import io.github.tux4us.miriquidilauncher.markets.SeriesRange
import java.util.Locale

private val HoldingsError = Color(0xFFFF8A80)

/**
 * Card „Eigene Werte“ am Ende der Scope „Kurse“: Aktien, Rohstoffe und Krypto-Werte mit Mengen eintragen. Die beiden
 * Gesamtbeträge (Euro und US-Dollar) und das Diagramm zum Verlauf des Gesamtwerts stehen immer sichtbar direkt unter der
 * Kopfzeile, sobald ein Wert eingetragen ist. Ein Tipp auf die Kopfzeile blendet Eingaben, Einzelwerte und Hinweis ein oder aus.
 */
@Composable
internal fun HoldingsCard(
    visible: Boolean,
    locale: Locale,
    onToggle: (Boolean) -> Unit,
    market: MarketsUiState,
    actions: SecurityActions,
    onPortfolioRange: (SeriesRange) -> Unit,
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
            // Gesamtbeträge und Diagramm bleiben auch bei eingeklappter Card sichtbar.
            val valuation = remember(market.positions, market.snapshot) {
                PositionsLogic.value(market.positions, market.snapshot.assetPrices())
            }
            if (market.positions.isNotEmpty()) {
                HoldingsTotals(locale, market, valuation.totalEur)
                PortfolioChart(locale, market, onPortfolioRange)
            }
            if (visible) {
                HorizontalDivider(color = WeatherTextMuted.copy(alpha = 0.4f))
                OwnValueInputs(market, actions)
                HorizontalDivider(color = WeatherTextMuted.copy(alpha = 0.4f))
                if (market.positions.isEmpty()) {
                    Text(stringResource(R.string.markets_holdings_empty), style = MaterialTheme.typography.bodyMedium, color = WeatherTextMuted)
                } else {
                    OwnValueRows(valuation, locale)
                }
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
private fun HoldingsTotals(locale: Locale, market: MarketsUiState, totalEur: Double?) {
    val eurUsd = market.snapshot.eurUsd()
    val totalUsd = if (totalEur != null && eurUsd != null) totalEur * eurUsd else null
    val none = stringResource(R.string.markets_holdings_unavailable)
    ValueRow(
        stringResource(R.string.markets_holdings_total_eur),
        totalEur?.let { MarketFormat.money(it, "EUR", locale) } ?: none,
    )
    ValueRow(
        stringResource(R.string.markets_holdings_total_usd),
        totalUsd?.let { MarketFormat.money(it, "USD", locale) } ?: none,
    )
}

/**
 * Diagramm zum Verlauf des Gesamtwerts in Euro. Grundlage sind die heutigen Mengen und die Kursverläufe der Einträge im
 * gewählten Zeitraum; die Einträge werden einzeln abgerufen und zwischengespeichert.
 */
@Composable
private fun PortfolioChart(locale: Locale, market: MarketsUiState, onRange: (SeriesRange) -> Unit) {
    val range = market.portfolioRange
    val load = market.portfolio
    val current = load.range == range.key
    val combined = remember(market.positions, load, market.snapshot, current) {
        if (current) PortfolioHistory.combine(market.positions, load.series, market.snapshot.assetPrices()) else null
    }
    Text(
        stringResource(R.string.markets_portfolio_title),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
    )
    RangeChips {
        PortfolioHistory.ranges.forEach { option ->
            val label = when (option) {
                SeriesRange.Day -> R.string.markets_range_1d
                SeriesRange.Week -> R.string.markets_range_1w
                SeriesRange.Month -> R.string.markets_range_1m
                else -> R.string.markets_range_1y
            }
            SegmentChip(stringResource(label), option == range) { onRange(option) }
        }
    }
    if (combined != null && combined.isUsable) {
        val intraday = range == SeriesRange.Day || range == SeriesRange.Week
        val values = combined.valuesEur
        val change = combined.changePercent()
        val high = MarketFormat.money(values.max(), "EUR", locale, 0)
        val low = MarketFormat.money(values.min(), "EUR", locale, 0)
        val start = axisTime(combined.times.first(), intraday, locale)
        val end = axisTime(combined.times.last(), intraday, locale)
        change?.let {
            Text(
                stringResource(R.string.markets_series_change, MarketFormat.signedPercent(it, locale)),
                style = MaterialTheme.typography.bodyMedium,
                color = changeColor(it),
            )
        }
        PriceChart(
            values = values,
            maxLabel = high,
            minLabel = low,
            startLabel = start,
            endLabel = end,
            description = stringResource(R.string.markets_portfolio_description, low, high, start, end),
        )
        if (combined.missing > 0) {
            Text(
                stringResource(R.string.markets_portfolio_missing, combined.missing, combined.total),
                style = MaterialTheme.typography.bodySmall,
                color = WeatherTextMuted,
            )
        }
        Text(
            stringResource(R.string.markets_portfolio_note),
            style = MaterialTheme.typography.bodySmall,
            color = WeatherTextMuted,
        )
    } else if (load.loading || !current) {
        Text(stringResource(R.string.markets_series_loading), style = MaterialTheme.typography.bodyMedium, color = WeatherTextMuted)
    } else if (load.error != null) {
        Text(
            stringResource(R.string.markets_series_error, extraErrorText(load.error)),
            style = MaterialTheme.typography.bodyMedium,
            color = HoldingsError,
        )
    } else {
        Text(stringResource(R.string.markets_chart_empty), style = MaterialTheme.typography.bodyMedium, color = WeatherTextMuted)
    }
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
