// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.tux4us.miriquidilauncher.R
import io.github.tux4us.miriquidilauncher.markets.CryptoRange
import io.github.tux4us.miriquidilauncher.markets.MarketFormat
import io.github.tux4us.miriquidilauncher.markets.MetalMath
import io.github.tux4us.miriquidilauncher.markets.Quote
import io.github.tux4us.miriquidilauncher.markets.QuoteDef
import io.github.tux4us.miriquidilauncher.markets.QuoteGroup
import io.github.tux4us.miriquidilauncher.markets.QuoteSource
import io.github.tux4us.miriquidilauncher.markets.QuotesData
import io.github.tux4us.miriquidilauncher.markets.Series
import io.github.tux4us.miriquidilauncher.markets.SeriesLoad
import io.github.tux4us.miriquidilauncher.markets.SeriesRange
import java.text.DateFormat
import java.util.Date
import java.util.Locale

private val Up = Color(0xFF7DDC8A)
private val Down = Color(0xFFFF9E9E)

/** Farbe einer Veränderung: grün, rot oder neutral (gedämpft ohne Wert). */
internal fun changeColor(change: Double?): Color = when {
    change == null -> WeatherTextMuted
    change > 0.05 -> Up
    change < -0.05 -> Down
    else -> WeatherText
}

/** Name eines Eintrags des Katalogs in der Sprache der App: Rohstoffe aus den Texten, Indizes als Eigenname. */
@Composable
internal fun quoteName(def: QuoteDef): String {
    val res = if (def.group == QuoteGroup.Commodity) commodityNameRes(def.id) else null
    return if (res != null) stringResource(res) else def.name
}

internal fun commodityNameRes(id: String): Int? = when (id) {
    "brent" -> R.string.markets_commodity_brent
    "wti" -> R.string.markets_commodity_wti
    "gas" -> R.string.markets_commodity_gas
    "heating" -> R.string.markets_commodity_heating
    "gold" -> R.string.markets_commodity_gold
    "silver" -> R.string.markets_commodity_silver
    "platinum" -> R.string.markets_commodity_platinum
    "palladium" -> R.string.markets_commodity_palladium
    "copper" -> R.string.markets_commodity_copper
    "aluminium" -> R.string.markets_commodity_aluminium
    "wheat" -> R.string.markets_commodity_wheat
    "corn" -> R.string.markets_commodity_corn
    "coffee" -> R.string.markets_commodity_coffee
    "sugar" -> R.string.markets_commodity_sugar
    else -> null
}

@Composable
internal fun rangeLabel(range: SeriesRange): String = stringResource(
    when (range) {
        SeriesRange.Day -> R.string.markets_range_1d
        SeriesRange.Week -> R.string.markets_range_1w
        SeriesRange.Month -> R.string.markets_range_1m
        SeriesRange.Year -> R.string.markets_range_1y
        SeriesRange.FiveYears -> R.string.markets_range_5y
    },
)

@Composable
internal fun rangeLabel(range: CryptoRange): String = stringResource(
    when (range) {
        CryptoRange.Day -> R.string.markets_range_1d
        CryptoRange.Week -> R.string.markets_range_1w
        CryptoRange.Month -> R.string.markets_range_1m
        CryptoRange.Year -> R.string.markets_range_1y
    },
)

/** Zeitbeschriftung der Diagrammachse: bei kurzen Zeiträumen die Uhrzeit, sonst das Datum. */
internal fun axisTime(seconds: Long, intraday: Boolean, locale: Locale): String {
    val date = Date(seconds * 1000L)
    return if (intraday) {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, locale).format(date)
    } else {
        DateFormat.getDateInstance(DateFormat.MEDIUM, locale).format(date)
    }
}

/**
 * Diagramm einer Zeitreihe mit Kopfzeile (Wert, Veränderung im Zeitraum). Der Monatsverlauf von Indizes und Rohstoffen
 * kann aus den Tagesschlusskursen des Kurs-Abrufs stammen ([fallbackCloses]); er hat dann keine Zeitachse.
 */
@Composable
internal fun SeriesChart(
    title: String,
    series: Series?,
    fallbackCloses: List<Double>,
    load: SeriesLoad,
    waiting: Boolean,
    currency: String?,
    intraday: Boolean,
    locale: Locale,
) {
    val values: List<Double>
    val startLabel: String
    val endLabel: String
    if (series != null && series.isUsable) {
        values = series.values
        startLabel = axisTime(series.times.first(), intraday, locale)
        endLabel = axisTime(series.times.last(), intraday, locale)
    } else if (fallbackCloses.size >= 2) {
        values = fallbackCloses
        startLabel = ""
        endLabel = ""
    } else {
        values = emptyList()
        startLabel = ""
        endLabel = ""
    }
    if (values.size >= 2) {
        // Bei einem Tag zählt die Veränderung gegen den Vortagesschluss, sonst gegen den ersten Wert des Zeitraums.
        val reference = if (series != null && intraday && series.times.size >= 2 && series.prevClose != null) series.prevClose else values.first()
        val change = if (reference > 0.0) (values.last() - reference) / reference * 100.0 else null
        val high = MarketFormat.price(values.max(), currency, locale)
        val low = MarketFormat.price(values.min(), currency, locale)
        Text(
            change?.let { stringResource(R.string.markets_series_change, MarketFormat.signedPercent(it, locale)) } ?: "",
            style = MaterialTheme.typography.bodyMedium,
            color = changeColor(change),
        )
        PriceChart(
            values = values,
            maxLabel = high,
            minLabel = low,
            startLabel = startLabel,
            endLabel = endLabel,
            description = stringResource(R.string.markets_series_description, title, low, high),
        )
        if (series == null || !series.isUsable) {
            Text(stringResource(R.string.markets_series_month_note), style = MaterialTheme.typography.bodySmall, color = WeatherTextMuted)
        }
    } else if (load.loading || waiting) {
        Text(stringResource(R.string.markets_series_loading), style = MaterialTheme.typography.bodyMedium, color = WeatherTextMuted)
    } else if (load.error != null) {
        Text(
            stringResource(R.string.markets_series_error, extraErrorText(load.error)),
            style = MaterialTheme.typography.bodyMedium,
            color = Down,
        )
    } else {
        Text(stringResource(R.string.markets_chart_empty), style = MaterialTheme.typography.bodyMedium, color = WeatherTextMuted)
    }
}

/** Zeile der Zeitraumauswahl; scrollt waagerecht, falls der Platz nicht reicht. */
@Composable
internal fun RangeChips(content: @Composable () -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.horizontalScroll(rememberScrollState()),
    ) { content() }
}

/**
 * Card für Indizes bzw. Rohstoffe: Zeitraumauswahl, Diagramm des gewählten Eintrags und die Liste aller Einträge mit
 * Preis und Veränderung zum Vortagesschluss. Ein Tipp auf eine Zeile wählt sie für das Diagramm.
 */
@Composable
internal fun QuoteChartCard(
    title: String,
    defs: List<QuoteDef>,
    quotes: QuotesData?,
    selectedId: String,
    range: SeriesRange,
    load: SeriesLoad,
    eurUsd: Double?,
    locale: Locale,
    onSelect: (String) -> Unit,
    onRange: (SeriesRange) -> Unit,
    footer: @Composable () -> Unit = {},
) {
    val shown = defs.filter { quotes?.byId(it.id) != null }
    GlassCard(title) {
        RangeChips {
            SeriesRange.entries.forEach { r -> SegmentChip(rangeLabel(r), r == range) { onRange(r) } }
        }
        val selected = shown.firstOrNull { it.id == selectedId } ?: shown.firstOrNull()
        val quote = selected?.let { quotes?.byId(it.id) }
        if (selected != null && quote != null) {
            val name = quoteName(selected)
            Text(name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            val current = load.id == selected.id && load.range == range.key
            SeriesChart(
                title = name,
                series = if (current) load.series else null,
                fallbackCloses = if (range == SeriesRange.Month) quote.closes else emptyList(),
                load = if (current) load else SeriesLoad(),
                waiting = !current,
                currency = displayCurrency(selected, quote),
                intraday = range == SeriesRange.Day || range == SeriesRange.Week,
                locale = locale,
            )
            HorizontalDivider(color = WeatherTextMuted.copy(alpha = 0.4f))
        }
        shown.forEach { def ->
            val q = quotes?.byId(def.id) ?: return@forEach
            QuoteRow(def, q, def.id == (selected?.id ?: ""), eurUsd, locale) { onSelect(def.id) }
        }
        footer()
    }
}

/** Währung, in der der Preis angezeigt wird: bei Indizes keine (Punkte), bei Rohstoffen die Notierungswährung. */
private fun displayCurrency(def: QuoteDef, quote: Quote): String? = if (def.group == QuoteGroup.Commodity) quote.currency ?: "USD" else null

@Composable
private fun QuoteRow(def: QuoteDef, quote: Quote, selected: Boolean, eurUsd: Double?, locale: Locale, onClick: () -> Unit) {
    val change = quote.changePercent
    val name = quoteName(def)
    val currency = displayCurrency(def, quote)
    val priceText = MarketFormat.price(quote.price, currency, locale, if (def.group == QuoteGroup.Index) 2 else null)
    // Euro-Wert für Rohstoffe, die in Dollar notieren.
    val euroText = if (def.group == QuoteGroup.Commodity && currency == "USD" && eurUsd != null && eurUsd > 0.0) {
        MarketFormat.price(MetalMath.usdToEur(quote.price, eurUsd), "EUR", locale)
    } else {
        null
    }
    val selectedText = stringResource(R.string.markets_selected)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) Color(0x22FFFFFF) else Color.Transparent)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics {
                this.selected = selected
                if (selected) stateDescription = selectedText
            }
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
            Text(
                if (def.group == QuoteGroup.Commodity) stringResource(R.string.markets_per_unit, def.tag) else def.tag,
                style = MaterialTheme.typography.bodySmall,
                color = WeatherTextMuted,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(priceText, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            if (euroText != null) Text(euroText, style = MaterialTheme.typography.bodySmall, color = WeatherTextMuted)
            Text(
                change?.let { MarketFormat.signedPercent(it, locale) } ?: "–",
                style = MaterialTheme.typography.bodySmall,
                color = changeColor(change),
            )
        }
    }
}

/** Hinweise unter der Rohstoff-Card: Gold-Silber-Verhältnis und Fußnote. */
@Composable
internal fun CommodityFooter(quotes: QuotesData?, eurUsd: Double?, source: String, locale: Locale) {
    val gold = quotes?.byId("gold")?.price
    val silver = quotes?.byId("silver")?.price
    if (gold != null && silver != null) {
        MetalMath.goldSilverRatio(gold, silver)?.let {
            ValueRow(stringResource(R.string.markets_gold_silver_ratio), MarketFormat.number(it, locale, 1))
        }
    }
    val stamp = quotes?.atMillis?.takeIf { it > 0L }?.let { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, locale).format(Date(it)) } ?: "–"
    Text(
        if (eurUsd != null) {
            stringResource(R.string.markets_commodities_note, MarketFormat.number(eurUsd, locale, 4), source, stamp)
        } else {
            stringResource(R.string.markets_commodities_note_no_rate, source, stamp)
        },
        style = MaterialTheme.typography.bodySmall,
        color = WeatherTextMuted,
    )
}

/** Fußnote unter der Index-Card. */
@Composable
internal fun IndicesFooter(quotes: QuotesData?, source: String, locale: Locale) {
    val stamp = quotes?.atMillis?.takeIf { it > 0L }?.let { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, locale).format(Date(it)) } ?: "–"
    Text(
        stringResource(R.string.markets_indices_note, source, stamp),
        style = MaterialTheme.typography.bodySmall,
        color = WeatherTextMuted,
    )
}

/** Bezeichnung der Quelle, aus der die angezeigten Kurse überwiegend stammen. */
internal fun sourceName(quotes: QuotesData?): String {
    val stooq = quotes?.quotes?.count { it.source == QuoteSource.STOOQ } ?: 0
    val yahoo = (quotes?.quotes?.size ?: 0) - stooq
    return when {
        stooq == 0 -> "Yahoo Finance"
        yahoo == 0 -> "Stooq"
        else -> "Yahoo Finance, Stooq"
    }
}
