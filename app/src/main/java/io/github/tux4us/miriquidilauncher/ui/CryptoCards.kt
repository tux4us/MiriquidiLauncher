// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.tux4us.miriquidilauncher.R
import io.github.tux4us.miriquidilauncher.markets.CryptoCatalog
import io.github.tux4us.miriquidilauncher.markets.CryptoData
import io.github.tux4us.miriquidilauncher.markets.CryptoRange
import io.github.tux4us.miriquidilauncher.markets.MarketFormat
import io.github.tux4us.miriquidilauncher.markets.SeriesLoad
import java.text.DateFormat
import java.util.Date
import java.util.Locale

private fun stamp(millis: Long?, locale: Locale): String =
    millis?.takeIf { it > 0L }?.let { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, locale).format(Date(it)) } ?: "–"

/** Card „Ethereum“: Kurs in Euro und Dollar, Veränderung in 24 Stunden, Marktkapitalisierung und Verlauf. */
@Composable
internal fun EthereumCard(
    crypto: CryptoData?,
    range: CryptoRange,
    load: SeriesLoad,
    locale: Locale,
    units: List<String>,
    onRange: (CryptoRange) -> Unit,
) {
    val quote = crypto?.byId(CryptoCatalog.ethereum.id) ?: return
    GlassCard(stringResource(R.string.markets_ethereum)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                MarketFormat.money(quote.eur, "EUR", locale, 2),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(MarketFormat.money(quote.usd, "USD", locale, 2), style = MaterialTheme.typography.headlineMedium, color = WeatherTextMuted)
        }
        quote.change24h?.let {
            ValueRow(stringResource(R.string.markets_crypto_change_note), MarketFormat.signedPercent(it, locale))
        }
        quote.marketCapEur?.let {
            ValueRow(stringResource(R.string.markets_market_cap), MarketFormat.compact(it, locale, units) + " €")
        }
        RangeChips {
            CryptoRange.entries.forEach { r -> SegmentChip(rangeLabel(r), r == range) { onRange(r) } }
        }
        val current = load.id == CryptoCatalog.ethereum.id && load.range == range.key
        SeriesChart(
            title = CryptoCatalog.ethereum.name,
            series = if (current) load.series else null,
            fallbackCloses = emptyList(),
            load = if (current) load else SeriesLoad(),
            waiting = !current,
            currency = "EUR",
            intraday = range == CryptoRange.Day || range == CryptoRange.Week,
            locale = locale,
        )
        Text(
            stringResource(R.string.markets_crypto_note, stamp(crypto.atMillis, locale)),
            style = MaterialTheme.typography.bodySmall,
            color = WeatherTextMuted,
        )
    }
}

/** Card „Stablecoins“: Kurs, Bindung und Abweichung von der Bindung. */
@Composable
internal fun StablecoinsCard(crypto: CryptoData?, locale: Locale, units: List<String>) {
    val shown = CryptoCatalog.stablecoins.filter { crypto?.byId(it.id) != null }
    if (shown.isEmpty()) return
    GlassCard(stringResource(R.string.markets_stablecoins)) {
        shown.forEach { def ->
            val q = crypto?.byId(def.id) ?: return@forEach
            val peg = def.peg ?: "USD"
            val price = if (peg == "EUR") q.eur else q.usd
            val deviation = q.pegDeviationPercent(def)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("${def.name} (${def.symbol})", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        stringResource(R.string.markets_peg, peg),
                        style = MaterialTheme.typography.bodySmall,
                        color = WeatherTextMuted,
                    )
                    q.marketCapEur?.let {
                        Text(
                            stringResource(R.string.markets_market_cap) + ": " + MarketFormat.compact(it, locale, units) + " €",
                            style = MaterialTheme.typography.bodySmall,
                            color = WeatherTextMuted,
                        )
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(MarketFormat.money(price, peg, locale, 4), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    deviation?.let {
                        Text(
                            stringResource(R.string.markets_peg_deviation, signedDeviation(it, locale)),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (kotlin.math.abs(it) >= 0.5) changeColor(it) else WeatherTextMuted,
                        )
                    }
                }
            }
        }
        Text(stringResource(R.string.markets_stablecoins_note), style = MaterialTheme.typography.bodySmall, color = WeatherTextMuted)
        Text(
            stringResource(R.string.markets_crypto_note, stamp(crypto?.atMillis, locale)),
            style = MaterialTheme.typography.bodySmall,
            color = WeatherTextMuted,
        )
    }
}

/** Abweichung mit zwei Nachkommastellen und Vorzeichen, z. B. „+0,04 %“; bei Stablecoins zählen schon Hundertstel. */
private fun signedDeviation(value: Double, locale: Locale): String {
    val sign = if (value > 0.005) "+" else if (value < -0.005) "−" else ""
    return sign + MarketFormat.number(kotlin.math.abs(value), locale, 2) + " %"
}
