// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.ui

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.tux4us.miriquidilauncher.R
import io.github.tux4us.miriquidilauncher.markets.BaseUrlCheck
import io.github.tux4us.miriquidilauncher.markets.BitcoinMath
import io.github.tux4us.miriquidilauncher.markets.BitcoinPrice
import io.github.tux4us.miriquidilauncher.markets.ChartCurrency
import io.github.tux4us.miriquidilauncher.markets.ChartRange
import io.github.tux4us.miriquidilauncher.markets.DifficultyInfo
import io.github.tux4us.miriquidilauncher.markets.MarketError
import io.github.tux4us.miriquidilauncher.markets.MarketFormat
import io.github.tux4us.miriquidilauncher.markets.MarketSnapshot
import io.github.tux4us.miriquidilauncher.markets.MarketUrl
import io.github.tux4us.miriquidilauncher.markets.MarketsUiState
import io.github.tux4us.miriquidilauncher.markets.QuoteCatalog
import io.github.tux4us.miriquidilauncher.markets.MarketsViewModel
import io.github.tux4us.miriquidilauncher.markets.MetalMath
import io.github.tux4us.miriquidilauncher.markets.PricePoint
import io.github.tux4us.miriquidilauncher.markets.PriceSeries
import java.text.DateFormat
import java.util.Date
import java.util.Locale

private val Positive = Color(0xFF7DDC8A)
private val Negative = Color(0xFFFF9E9E)

/**
 * Scope „Kurse“. Reihenfolge: weltweite Indizes (mit Diagramm), Rohstoffe (mit Diagramm), Bitcoin mit Netzwerk-Cards,
 * Ethereum und Stablecoins, Wechselkurse, Datenquellen und zuletzt die ausblendbare Card „Eigene Werte“. [active] ist
 * `true`, solange die Seite sichtbar ist; nur dann wird abgerufen.
 */
@Composable
fun MarketsScreen(viewModel: MarketsViewModel, active: Boolean, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(active) { viewModel.setActive(active) }
    val locale = LocalConfiguration.current.locales[0]
    val snapshot = state.snapshot
    val units = stringArrayResource(R.array.markets_compact_units).toList()
    val context = LocalContext.current
    val securityActions = remember(viewModel) {
        SecurityActions(
            onSearch = viewModel::searchSecurity,
            onClearSearch = viewModel::clearSearch,
            onAdd = viewModel::addSecurity,
            onAddCommodity = viewModel::addCommodity,
            onAddCrypto = viewModel::addCrypto,
            onQuantity = viewModel::setPositionQuantity,
            onRemove = viewModel::removePosition,
        )
    }
    val eurUsd = snapshot.eurUsd()
    val quoteSource = sourceName(snapshot.quotes)

    ScopePage(title = stringResource(R.string.scope_markets), modifier = modifier) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = ScopeBottomClearance),
            modifier = Modifier.fillMaxWidth(),
        ) {
            item { StatusRow(state, locale, onRefresh = viewModel::refresh) }
            // 1. Weltweite Indizes
            if (snapshot.quotes?.quotes?.any { q -> QuoteCatalog.indices.any { it.id == q.id } } == true) {
                item {
                    QuoteChartCard(
                        title = stringResource(R.string.markets_indices),
                        defs = QuoteCatalog.indices,
                        quotes = snapshot.quotes,
                        selectedId = state.choice.indexId,
                        range = state.choice.indexRange,
                        load = state.indexSeries,
                        eurUsd = eurUsd,
                        locale = locale,
                        onSelect = viewModel::selectIndex,
                        onRange = viewModel::setIndexRange,
                        footer = { IndicesFooter(snapshot.quotes, quoteSource, locale) },
                    )
                }
            }
            // 2. Rohstoffe
            if (snapshot.quotes?.quotes?.any { q -> QuoteCatalog.commodities.any { it.id == q.id } } == true) {
                item {
                    QuoteChartCard(
                        title = stringResource(R.string.markets_commodities),
                        defs = QuoteCatalog.commodities,
                        quotes = snapshot.quotes,
                        selectedId = state.choice.commodityId,
                        range = state.choice.commodityRange,
                        load = state.commoditySeries,
                        eurUsd = eurUsd,
                        locale = locale,
                        onSelect = viewModel::selectCommodity,
                        onRange = viewModel::setCommodityRange,
                        footer = { CommodityFooter(snapshot.quotes, eurUsd, quoteSource, locale) },
                    )
                }
            }
            // 3. Bitcoin mit allen Kennzahlen, danach Ethereum und Stablecoins
            val price = snapshot.fast?.price
            if (price != null) {
                item { PriceCard(snapshot, price, state.chartCurrency, locale, units) }
                item {
                    ChartCard(
                        snapshot = snapshot,
                        price = price,
                        range = state.range,
                        currency = state.chartCurrency,
                        locale = locale,
                        onRange = viewModel::setRange,
                        onCurrency = viewModel::setChartCurrency,
                    )
                }
            }
            if (snapshot.fast?.tip != null || snapshot.slow?.hashrate != null) {
                item { BitcoinNetworkCard(snapshot, locale) }
            }
            if (snapshot.fast?.fees != null || snapshot.fast?.mempool != null) {
                item { FeesCard(snapshot, price, locale) }
            }
            snapshot.slow?.difficulty?.let { item { DifficultyCard(it, locale) } }
            snapshot.fast?.tip?.let { item { HalvingCard(it.height, locale) } }
            snapshot.slow?.lightning?.let { item { LightningCard(it.channels, it.nodes, it.capacitySats, locale) } }
            if (snapshot.crypto != null) {
                item {
                    EthereumCard(
                        crypto = snapshot.crypto,
                        range = state.choice.cryptoRange,
                        load = state.cryptoSeries,
                        locale = locale,
                        units = units,
                        onRange = viewModel::setCryptoRange,
                    )
                }
                item { StablecoinsCard(snapshot.crypto, locale, units) }
            }
            item {
                FxCard(
                    from = state.fxFrom,
                    to = state.fxTo,
                    fx = snapshot.fx,
                    error = state.fxError,
                    locale = locale,
                    onPair = viewModel::setFxPair,
                    onSwap = viewModel::swapFx,
                )
            }
            item {
                SourceCard(
                    state,
                    onApply = viewModel::setCustomBaseUrl,
                    onStooq = viewModel::setStooqEnabled,
                    onStooqKey = viewModel::setStooqKey,
                    onYahoo = viewModel::setYahooEnabled,
                )
            }
            // 4. Eigene Werte stehen als letzte Card, weil sie mit jedem Eintrag länger werden.
            item {
                HoldingsCard(
                    visible = state.holdingsVisible,
                    locale = locale,
                    onToggle = viewModel::setHoldingsVisible,
                    market = state,
                    actions = securityActions,
                )
            }
            item { FooterCard(onOpen = { openWebLink(context, it) }) }
        }
    }
}

// region Status ----------------------------------------------------------------------------------

@Composable
private fun StatusRow(state: MarketsUiState, locale: Locale, onRefresh: () -> Unit) {
    val snapshot = state.snapshot
    val updated = listOfNotNull(snapshot.fast?.atMillis, snapshot.quotes?.atMillis, snapshot.crypto?.atMillis).filter { it > 0L }.maxOrNull()
    val blocked = System.currentTimeMillis() < state.blockedUntilMillis
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val text = when {
                state.loading && updated == null -> stringResource(R.string.markets_loading)
                updated != null -> stringResource(R.string.markets_updated, formatStamp(updated, locale))
                state.cacheLoaded -> stringResource(R.string.markets_no_data)
                else -> ""
            }
            Text(text, color = WeatherTextMuted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            if (state.loading) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = WeatherText)
            } else {
                IconButton(onClick = onRefresh, enabled = !blocked) {
                    Icon(painterResource(R.drawable.ic_refresh), stringResource(R.string.markets_refresh))
                }
            }
        }
        state.bitcoinError?.let { error ->
            Text(
                stringResource(R.string.markets_error_bitcoin, errorText(error, state.blockedUntilMillis, locale)),
                color = Negative,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        state.quotesError?.let { error -> ExtraError(R.string.markets_error_quotes, error) }
        state.cryptoError?.let { error -> ExtraError(R.string.markets_error_crypto, error) }
        state.securitiesError?.let { error -> ExtraError(R.string.markets_error_securities, error) }
        state.fxError?.let { error -> ExtraError(R.string.markets_error_fx, error) }
        if (state.bitcoinError != null && updated != null) {
            Text(
                stringResource(R.string.markets_stale_notice, formatStamp(updated, locale)),
                color = WeatherTextMuted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ExtraError(labelRes: Int, error: MarketError) {
    Text(stringResource(labelRes, extraErrorText(error)), color = Negative, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun errorText(error: MarketError, blockedUntilMillis: Long, locale: Locale): String = when (error) {
    MarketError.NETWORK -> stringResource(R.string.markets_error_network)
    MarketError.DATA -> stringResource(R.string.markets_error_data)
    MarketError.RATE_LIMIT -> stringResource(R.string.markets_error_rate_limit, formatTime(blockedUntilMillis, locale))
    MarketError.KEY -> stringResource(R.string.markets_error_key)
}

// endregion

// region Bitcoin-Kurs und Verlauf ---------------------------------------------------------------

@Composable
private fun PriceCard(
    snapshot: MarketSnapshot,
    price: BitcoinPrice,
    currency: ChartCurrency,
    locale: Locale,
    units: List<String>,
) {
    val history = snapshot.history?.points.orEmpty()
    val nowSec = if (price.timeSec > 0L) price.timeSec else history.lastOrNull()?.timeSec ?: 0L
    val current = if (currency == ChartCurrency.Eur) price.eur else price.usd
    val supply = snapshot.fast?.tip?.let { BitcoinMath.supplySats(it.height) }
    GlassCard(stringResource(R.string.markets_bitcoin)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                MarketFormat.money(price.eur, "EUR", locale, 0),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                MarketFormat.money(price.usd, "USD", locale, 0),
                style = MaterialTheme.typography.headlineMedium,
                color = WeatherTextMuted,
            )
        }
        if (price.timeSec > 0L) {
            Text(
                stringResource(R.string.markets_price_time, formatStamp(price.timeSec * 1000L, locale)),
                style = MaterialTheme.typography.bodySmall,
                color = WeatherTextMuted,
            )
        }
        if (history.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChangeCell(R.string.markets_change_24h, ChartRange.Day, history, current, nowSec, currency, locale, Modifier.weight(1f))
                ChangeCell(R.string.markets_change_7d, ChartRange.Week, history, current, nowSec, currency, locale, Modifier.weight(1f))
                ChangeCell(R.string.markets_change_30d, ChartRange.Month, history, current, nowSec, currency, locale, Modifier.weight(1f))
            }
        }
        ValueRow(
            stringResource(R.string.markets_sats_per_usd),
            MarketFormat.number(BitcoinMath.satsPerUnit(price.usd), locale, 0),
        )
        ValueRow(
            stringResource(R.string.markets_sats_per_eur),
            MarketFormat.number(BitcoinMath.satsPerUnit(price.eur), locale, 0),
        )
        snapshot.quotes?.byId("gold")?.takeIf { it.currency == null || it.currency == "USD" }?.let { gold ->
            MetalMath.bitcoinInGoldOunces(price.usd, gold.price)?.let {
                ValueRow(
                    stringResource(R.string.markets_btc_in_gold),
                    stringResource(R.string.markets_ounces_value, MarketFormat.number(it, locale, 1)),
                )
            }
        }
        if (supply != null) {
            ValueRow(
                stringResource(R.string.markets_market_cap),
                stringResource(
                    R.string.markets_price_pair,
                    MarketFormat.compact(BitcoinMath.marketCap(supply, price.eur), locale, units) + " €",
                    MarketFormat.compact(BitcoinMath.marketCap(supply, price.usd), locale, units) + " $",
                ),
            )
            ValueRow(stringResource(R.string.markets_supply), MarketFormat.btc(supply, locale))
            Text(
                stringResource(R.string.markets_supply_note),
                style = MaterialTheme.typography.bodySmall,
                color = WeatherTextMuted,
            )
        }
    }
}

@Composable
private fun ChangeCell(
    labelRes: Int,
    range: ChartRange,
    history: List<PricePoint>,
    current: Double,
    nowSec: Long,
    currency: ChartCurrency,
    locale: Locale,
    modifier: Modifier = Modifier,
) {
    val change = PriceSeries.changeOver(history, current, nowSec, range.seconds, currency)
    Column(modifier) {
        Text(stringResource(labelRes), style = MaterialTheme.typography.labelMedium, color = WeatherTextMuted)
        Text(
            text = change?.let { MarketFormat.signedPercent(it, locale) } ?: "–",
            style = MaterialTheme.typography.titleSmall,
            color = when {
                change == null -> WeatherTextMuted
                change > 0.05 -> Positive
                change < -0.05 -> Negative
                else -> WeatherText
            },
        )
    }
}

@Composable
private fun ChartCard(
    snapshot: MarketSnapshot,
    price: BitcoinPrice,
    range: ChartRange,
    currency: ChartCurrency,
    locale: Locale,
    onRange: (ChartRange) -> Unit,
    onCurrency: (ChartCurrency) -> Unit,
) {
    val history = snapshot.history?.points.orEmpty()
    val nowSec = if (price.timeSec > 0L) price.timeSec else history.lastOrNull()?.timeSec ?: 0L
    val points = remember(history, range, nowSec) { PriceSeries.window(history, range, nowSec) }
    GlassCard(stringResource(R.string.markets_chart)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            SegmentChip(stringResource(R.string.markets_change_24h), range == ChartRange.Day) { onRange(ChartRange.Day) }
            SegmentChip(stringResource(R.string.markets_change_7d), range == ChartRange.Week) { onRange(ChartRange.Week) }
            SegmentChip(stringResource(R.string.markets_change_30d), range == ChartRange.Month) { onRange(ChartRange.Month) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            SegmentChip("EUR", currency == ChartCurrency.Eur) { onCurrency(ChartCurrency.Eur) }
            SegmentChip("USD", currency == ChartCurrency.Usd) { onCurrency(ChartCurrency.Usd) }
        }
        if (points.size >= 2) {
            val values = points.map { PriceSeries.value(it, currency) }
            val start = formatChartTime(points.first().timeSec * 1000L, range, locale)
            val end = formatChartTime(points.last().timeSec * 1000L, range, locale)
            val maxText = MarketFormat.money(values.max(), currency.code, locale, 0)
            val minText = MarketFormat.money(values.min(), currency.code, locale, 0)
            PriceChart(
                values = values,
                maxLabel = maxText,
                minLabel = minText,
                startLabel = start,
                endLabel = end,
                description = stringResource(R.string.markets_chart_description, currency.code, start, end) +
                    ", $minText – $maxText",
            )
        } else {
            Text(
                stringResource(R.string.markets_chart_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = WeatherTextMuted,
            )
        }
    }
}

/** Auswahl-Schaltfläche in Pillenform; die Auswahl ist auch für Screenreader gesetzt. */
@Composable
internal fun SegmentChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        color = if (selected) Color.Black else WeatherText,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) WeatherText else Color(0x33FFFFFF))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = 14.dp, vertical = 10.dp),
    )
}

// endregion

// region Netzwerk, Gebühren, Difficulty, Halving, Lightning --------------------------------------

@Composable
private fun BitcoinNetworkCard(snapshot: MarketSnapshot, locale: Locale) {
    val tip = snapshot.fast?.tip
    val hashrate = snapshot.slow?.hashrate
    GlassCard(stringResource(R.string.markets_network)) {
        if (tip != null) {
            ValueRow(stringResource(R.string.markets_block_height), MarketFormat.number(tip.height.toDouble(), locale, 0))
            val blockTime = tip.timeSec
            if (blockTime != null) {
                val age = relativeTime(blockTime * 1000L)
                val value = tip.txCount?.let {
                    stringResource(R.string.markets_last_block_value, age, MarketFormat.number(it.toDouble(), locale, 0))
                } ?: age
                ValueRow(stringResource(R.string.markets_last_block), value)
            }
        }
        if (hashrate != null) {
            ValueRow(stringResource(R.string.markets_hashrate), MarketFormat.hashrate(hashrate.hashrate, locale))
            ValueRow(stringResource(R.string.markets_difficulty), MarketFormat.compact(hashrate.difficulty, locale, DIFFICULTY_UNITS))
        }
    }
}

/** Kurzschreibweise der Difficulty ohne Sprachbezug (T = 10^12, P = 10^15, ...), wie bei Hashraten üblich. */
private val DIFFICULTY_UNITS = listOf("k", "M", "G", "T")

@Composable
private fun FeesCard(snapshot: MarketSnapshot, price: BitcoinPrice?, locale: Locale) {
    val fees = snapshot.fast?.fees
    val mempool = snapshot.fast?.mempool
    GlassCard(stringResource(R.string.markets_fees)) {
        if (fees != null) {
            FeeRow(R.string.markets_fee_fastest, fees.fastest, price, locale)
            FeeRow(R.string.markets_fee_half_hour, fees.halfHour, price, locale)
            FeeRow(R.string.markets_fee_hour, fees.hour, price, locale)
            FeeRow(R.string.markets_fee_economy, fees.economy, price, locale)
            FeeRow(R.string.markets_fee_minimum, fees.minimum, price, locale)
            if (price != null) {
                Text(
                    stringResource(R.string.markets_fee_note, BitcoinMath.TYPICAL_TX_VBYTES),
                    style = MaterialTheme.typography.bodySmall,
                    color = WeatherTextMuted,
                )
            }
        }
        if (mempool != null) {
            ValueRow(stringResource(R.string.markets_mempool_count), MarketFormat.number(mempool.txCount.toDouble(), locale, 0))
            ValueRow(stringResource(R.string.markets_mempool_size), MarketFormat.bytes(mempool.vsize, locale))
        }
    }
}

@Composable
private fun FeeRow(labelRes: Int, satPerVbyte: Double, price: BitcoinPrice?, locale: Locale) {
    val rate = MarketFormat.trimmed(satPerVbyte, locale, 1)
    val value = if (price != null) {
        stringResource(
            R.string.markets_fee_value,
            rate,
            MarketFormat.money(BitcoinMath.feeFiat(satPerVbyte, BitcoinMath.TYPICAL_TX_VBYTES, price.eur), "EUR", locale),
        )
    } else {
        "$rate sat/vB"
    }
    ValueRow(stringResource(labelRes), value)
}

@Composable
private fun DifficultyCard(info: DifficultyInfo, locale: Locale) {
    GlassCard(stringResource(R.string.markets_difficulty_adjustment)) {
        UsageBar((info.progressPercent / 100.0).toFloat())
        ValueRow(stringResource(R.string.markets_difficulty_progress), MarketFormat.number(info.progressPercent, locale, 1) + " %")
        ValueRow(stringResource(R.string.markets_difficulty_remaining), MarketFormat.number(info.remainingBlocks.toDouble(), locale, 0))
        ValueRow(stringResource(R.string.markets_difficulty_expected), MarketFormat.signedPercent(info.expectedChangePercent, locale))
        ValueRow(stringResource(R.string.markets_difficulty_date), formatDate(info.estimatedRetargetMillis, locale))
        info.previousChangePercent?.let {
            ValueRow(stringResource(R.string.markets_difficulty_previous), MarketFormat.signedPercent(it, locale))
        }
    }
}

@Composable
private fun HalvingCard(height: Int, locale: Locale) {
    val subsidy = BitcoinMath.subsidySats(height + 1)
    val remaining = BitcoinMath.blocksUntilHalving(height)
    GlassCard(stringResource(R.string.markets_halving)) {
        UsageBar(BitcoinMath.epochProgress(height).toFloat())
        ValueRow(stringResource(R.string.markets_halving_progress), MarketFormat.number(BitcoinMath.epochProgress(height) * 100.0, locale, 1) + " %")
        ValueRow(stringResource(R.string.markets_halving_subsidy), MarketFormat.trimmed(subsidy.toDouble() / BitcoinMath.SATS_PER_BTC, locale, 8) + " BTC")
        ValueRow(stringResource(R.string.markets_halving_height), MarketFormat.number(BitcoinMath.nextHalvingHeight(height).toDouble(), locale, 0))
        ValueRow(stringResource(R.string.markets_halving_remaining), MarketFormat.number(remaining.toDouble(), locale, 0))
        ValueRow(
            stringResource(R.string.markets_halving_date),
            formatDate(BitcoinMath.estimatedHalvingMillis(height, System.currentTimeMillis()), locale),
        )
        Text(stringResource(R.string.markets_halving_note), style = MaterialTheme.typography.bodySmall, color = WeatherTextMuted)
    }
}

@Composable
private fun LightningCard(channels: Long, nodes: Long, capacitySats: Long, locale: Locale) {
    GlassCard(stringResource(R.string.markets_lightning)) {
        ValueRow(stringResource(R.string.markets_lightning_channels), MarketFormat.number(channels.toDouble(), locale, 0))
        ValueRow(stringResource(R.string.markets_lightning_nodes), MarketFormat.number(nodes.toDouble(), locale, 0))
        ValueRow(
            stringResource(R.string.markets_lightning_capacity),
            MarketFormat.number(capacitySats.toDouble() / BitcoinMath.SATS_PER_BTC, locale, 1) + " BTC",
        )
    }
}

// endregion

// region Quelle und Hinweise ---------------------------------------------------------------------

@Composable
private fun SourceCard(
    state: MarketsUiState,
    onApply: (String?) -> Unit,
    onStooq: (Boolean) -> Unit,
    onStooqKey: (String) -> Unit,
    onYahoo: (Boolean) -> Unit,
) {
    var custom by remember(state.customBaseUrl) { mutableStateOf(state.customBaseUrl != null) }
    var text by remember(state.customBaseUrl) { mutableStateOf(state.customBaseUrl.orEmpty()) }
    val check = remember(text) { MarketUrl.check(text) }
    val error = when (check) {
        BaseUrlCheck.Empty -> null
        BaseUrlCheck.NotHttps -> stringResource(R.string.markets_source_error_not_https)
        BaseUrlCheck.Invalid -> stringResource(R.string.markets_source_error_invalid)
        is BaseUrlCheck.Ok -> null
    }
    GlassCard(stringResource(R.string.markets_source)) {
        SourceOption(selected = !custom, label = stringResource(R.string.markets_source_default)) {
            custom = false
            if (state.customBaseUrl != null) onApply(null)
        }
        SourceOption(selected = custom, label = stringResource(R.string.markets_source_custom)) { custom = true }
        if (custom) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                placeholder = { Text(stringResource(R.string.markets_source_url_hint)) },
                isError = error != null,
                supportingText = error?.let { message -> { Text(message) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done, autoCorrectEnabled = false),
                colors = sourceFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
            val candidate = (check as? BaseUrlCheck.Ok)?.url
            TextButton(
                enabled = candidate != null && candidate != state.customBaseUrl,
                onClick = { candidate?.let(onApply) },
            ) { Text(stringResource(R.string.markets_source_apply)) }
            if (check == BaseUrlCheck.Empty && text.isNotEmpty()) {
                Text(stringResource(R.string.markets_source_error_empty), color = Negative, style = MaterialTheme.typography.bodySmall)
            }
            Text(stringResource(R.string.markets_source_note), style = MaterialTheme.typography.bodySmall, color = WeatherTextMuted)
        }
        Text(
            stringResource(R.string.markets_source_active, state.baseUrl.removePrefix("https://")),
            style = MaterialTheme.typography.bodySmall,
            color = WeatherTextMuted,
        )
        HorizontalDivider(color = WeatherTextMuted.copy(alpha = 0.4f))
        SourceSwitch(stringResource(R.string.markets_source_yahoo), state.yahooEnabled, onYahoo)
        SourceSwitch(stringResource(R.string.markets_source_stooq), state.stooqEnabled, onStooq)
        if (state.stooqEnabled) {
            var keyText by remember(state.stooqKey) { mutableStateOf(state.stooqKey) }
            OutlinedTextField(
                value = keyText,
                onValueChange = { keyText = it },
                singleLine = true,
                label = { Text(stringResource(R.string.markets_stooq_key)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done, autoCorrectEnabled = false),
                visualTransformation = PasswordVisualTransformation(),
                colors = sourceFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    enabled = state.stooqKey.isNotEmpty() || keyText.isNotEmpty(),
                    onClick = {
                        keyText = ""
                        onStooqKey("")
                    },
                ) { Text(stringResource(R.string.markets_stooq_key_remove)) }
                TextButton(
                    enabled = keyText.isNotBlank() && keyText.trim() != state.stooqKey,
                    onClick = { onStooqKey(keyText) },
                ) { Text(stringResource(R.string.markets_stooq_key_save)) }
            }
            Text(
                stringResource(if (state.stooqKey.isBlank()) R.string.markets_stooq_key_missing else R.string.markets_stooq_key_note),
                style = MaterialTheme.typography.bodySmall,
                color = WeatherTextMuted,
            )
        }
        Text(stringResource(R.string.markets_source_switch_note), style = MaterialTheme.typography.bodySmall, color = WeatherTextMuted)
    }
}

@Composable
private fun SourceSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun SourceOption(selected: Boolean, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            colors = RadioButtonDefaults.colors(selectedColor = WeatherText, unselectedColor = WeatherTextMuted),
        )
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun sourceFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = WeatherText,
    unfocusedTextColor = WeatherText,
    errorTextColor = WeatherText,
    cursorColor = WeatherText,
    focusedBorderColor = WeatherText,
    unfocusedBorderColor = WeatherTextMuted,
    errorBorderColor = Negative,
    focusedPlaceholderColor = WeatherTextMuted,
    unfocusedPlaceholderColor = WeatherTextMuted,
    errorSupportingTextColor = Negative,
)

@Composable
private fun FooterCard(onOpen: (String) -> Unit) {
    Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        AttributionLink(R.string.markets_attr_mempool, "https://mempool.space/", onOpen)
        AttributionLink(R.string.markets_attr_yahoo, "https://finance.yahoo.com/", onOpen)
        AttributionLink(R.string.markets_attr_coingecko, "https://www.coingecko.com/", onOpen)
        AttributionLink(R.string.markets_attr_frankfurter, "https://frankfurter.dev/", onOpen)
        AttributionLink(R.string.markets_attr_stooq, "https://stooq.com/", onOpen)
        Text(stringResource(R.string.markets_disclaimer), style = MaterialTheme.typography.bodySmall, color = WeatherTextMuted)
        Text(stringResource(R.string.markets_privacy_note), style = MaterialTheme.typography.bodySmall, color = WeatherTextMuted)
    }
}

// endregion

// region Hilfen ----------------------------------------------------------------------------------

private fun formatStamp(millis: Long, locale: Locale): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, locale).format(Date(millis))

private fun formatDate(millis: Long, locale: Locale): String =
    DateFormat.getDateInstance(DateFormat.MEDIUM, locale).format(Date(millis))

private fun formatTime(millis: Long, locale: Locale): String =
    DateFormat.getTimeInstance(DateFormat.SHORT, locale).format(Date(millis))

/** Zeitbeschriftung der Diagrammachse: bei 24 Stunden die Uhrzeit, sonst das Datum. */
private fun formatChartTime(millis: Long, range: ChartRange, locale: Locale): String =
    if (range == ChartRange.Day) formatTime(millis, locale) else formatDate(millis, locale)

private fun relativeTime(millis: Long): String =
    DateUtils.getRelativeTimeSpanString(millis, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()

// endregion
