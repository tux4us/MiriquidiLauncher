// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.tux4us.miriquidilauncher.R
import io.github.tux4us.miriquidilauncher.markets.CryptoCatalog
import io.github.tux4us.miriquidilauncher.markets.FxLogic
import io.github.tux4us.miriquidilauncher.markets.HoldingsLogic
import io.github.tux4us.miriquidilauncher.markets.MarketError
import io.github.tux4us.miriquidilauncher.markets.MarketFormat
import io.github.tux4us.miriquidilauncher.markets.MarketsUiState
import io.github.tux4us.miriquidilauncher.markets.Position
import io.github.tux4us.miriquidilauncher.markets.PositionKind
import io.github.tux4us.miriquidilauncher.markets.PositionsLogic
import io.github.tux4us.miriquidilauncher.markets.QuoteCatalog
import io.github.tux4us.miriquidilauncher.markets.PositionsValuation
import io.github.tux4us.miriquidilauncher.markets.SecurityHit
import io.github.tux4us.miriquidilauncher.markets.SecuritySearch
import java.util.Locale

/** Aktionen der Eingabe unter „Eigene Werte“. */
internal class SecurityActions(
    val onSearch: (String) -> Unit,
    val onClearSearch: () -> Unit,
    val onAdd: (SecurityHit, String, Double) -> Unit,
    val onAddCommodity: (String, Double) -> Unit,
    val onAddCrypto: (String, Double) -> Unit,
    val onQuantity: (PositionKind, String, Double) -> Unit,
    val onRemove: (PositionKind, String) -> Unit,
)

private fun plain(value: Double): String = java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()

/** Eingaben unter „Eigene Werte“: Art wählen (Aktie, Rohstoff, Krypto), hinzufügen und die Liste der Einträge. */
@Composable
internal fun OwnValueInputs(state: MarketsUiState, actions: SecurityActions) {
    var kind by remember { mutableStateOf(PositionKind.Stock) }
    val full = state.positions.size >= PositionsLogic.MAX_POSITIONS
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            SegmentChip(stringResource(R.string.markets_own_kind_stock), kind == PositionKind.Stock) { kind = PositionKind.Stock }
            SegmentChip(stringResource(R.string.markets_own_kind_commodity), kind == PositionKind.Commodity) { kind = PositionKind.Commodity }
            SegmentChip(stringResource(R.string.markets_own_kind_crypto), kind == PositionKind.Crypto) { kind = PositionKind.Crypto }
        }
        when (kind) {
            PositionKind.Stock -> {
                Text(stringResource(R.string.markets_sec_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                if (!state.yahooEnabled) {
                    Text(stringResource(R.string.markets_sec_source_off), style = MaterialTheme.typography.bodySmall, color = WeatherTextMuted)
                } else {
                    AddSecurity(state, actions)
                }
            }
            PositionKind.Commodity -> if (!full) {
                val options = QuoteCatalog.commodities.map { it.id to (commodityNameRes(it.id)?.let { res -> stringResource(res) } ?: it.name) }
                AddByCatalog(
                    pickLabel = stringResource(R.string.markets_own_pick_commodity),
                    options = options,
                    unitOf = { id -> QuoteCatalog.byId(id)?.tag.orEmpty() },
                    onAdd = actions.onAddCommodity,
                )
            }
            PositionKind.Crypto -> if (!full) {
                AddByCatalog(
                    pickLabel = stringResource(R.string.markets_own_pick_crypto),
                    options = CryptoCatalog.selectable.map { it.id to "${it.name} (${it.symbol})" },
                    unitOf = { id -> CryptoCatalog.byId(id)?.symbol.orEmpty() },
                    onAdd = actions.onAddCrypto,
                )
            }
        }
        state.positions.forEach { position -> PositionRow(position, actions) }
    }
}

/** Auswahl aus einem festen Katalog (Rohstoff oder Krypto-Wert) mit Mengenfeld. */
@Composable
private fun AddByCatalog(
    pickLabel: String,
    options: List<Pair<String, String>>,
    unitOf: (String) -> String,
    onAdd: (String, Double) -> Unit,
) {
    var chosen by remember { mutableStateOf(options.first().first) }
    var open by remember { mutableStateOf(false) }
    var quantity by remember { mutableStateOf("") }
    val parsed = remember(quantity) { HoldingsLogic.parseAmount(quantity) }
    val amount = (parsed as? HoldingsLogic.Parsed.Ok)?.value?.takeIf { it > 0.0 }
    Text(pickLabel, style = MaterialTheme.typography.labelMedium, color = WeatherTextMuted)
    OutlinedButton(
        onClick = { open = true },
        colors = ButtonDefaults.outlinedButtonColors(contentColor = WeatherText),
        modifier = Modifier.fillMaxWidth(),
    ) { Text(options.firstOrNull { it.first == chosen }?.second.orEmpty()) }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        options.forEach { (id, label) ->
            DropdownMenuItem(
                text = { Text(label) },
                onClick = {
                    open = false
                    chosen = id
                },
            )
        }
    }
    OutlinedTextField(
        value = quantity,
        onValueChange = { quantity = it },
        singleLine = true,
        label = { Text(stringResource(R.string.markets_own_amount)) },
        suffix = { Text(unitOf(chosen)) },
        isError = quantity.isNotBlank() && amount == null,
        supportingText = if (quantity.isNotBlank() && amount == null) {
            { Text(stringResource(R.string.markets_holdings_invalid)) }
        } else {
            null
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
        colors = holdingsFieldColors(),
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
        TextButton(
            enabled = amount != null,
            onClick = {
                onAdd(chosen, amount ?: return@TextButton)
                quantity = ""
            },
            colors = ButtonDefaults.textButtonColors(contentColor = WeatherText, disabledContentColor = WeatherTextMuted),
        ) { Text(stringResource(R.string.markets_own_add)) }
    }
}

@Composable
private fun AddSecurity(state: MarketsUiState, actions: SecurityActions) {
    var ident by remember { mutableStateOf("") }
    var chosen by remember { mutableStateOf<SecurityHit?>(null) }
    var quantity by remember { mutableStateOf("") }
    val search = state.search
    val loading = search == SecuritySearch.Loading
    OutlinedTextField(
        value = ident,
        onValueChange = {
            ident = it
            chosen = null
            if (search != SecuritySearch.Idle && !loading) actions.onClearSearch()
        },
        singleLine = true,
        label = { Text(stringResource(R.string.markets_sec_ident)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Search, autoCorrectEnabled = false),
        colors = holdingsFieldColors(),
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
        TextButton(
            enabled = ident.isNotBlank() && !loading && state.positions.size < io.github.tux4us.miriquidilauncher.markets.PositionsLogic.MAX_POSITIONS,
            onClick = {
                chosen = null
                actions.onSearch(ident)
            },
            colors = ButtonDefaults.textButtonColors(contentColor = WeatherText, disabledContentColor = WeatherTextMuted),
        ) { Text(stringResource(R.string.markets_sec_search)) }
    }
    when (search) {
        SecuritySearch.Idle -> Unit
        SecuritySearch.Loading -> Text(stringResource(R.string.markets_sec_searching), style = MaterialTheme.typography.bodySmall, color = WeatherTextMuted)
        SecuritySearch.InvalidId -> Hint(R.string.markets_sec_invalid)
        SecuritySearch.NotFound -> Hint(R.string.markets_sec_not_found)
        is SecuritySearch.Error -> Hint(
            when (search.error) {
                MarketError.NETWORK -> R.string.markets_error_network
                MarketError.RATE_LIMIT -> R.string.markets_sec_rate_limit
                MarketError.DATA -> R.string.markets_error_data
                MarketError.KEY -> R.string.markets_error_key
            },
        )
        is SecuritySearch.Found -> {
            Text(stringResource(R.string.markets_sec_pick), style = MaterialTheme.typography.bodySmall, color = WeatherTextMuted)
            search.hits.forEach { hit ->
                val selected = chosen == hit
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.RadioButton) { chosen = hit }
                        .padding(vertical = 4.dp),
                ) {
                    Text(
                        (if (selected) "● " else "○ ") + hit.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    )
                    Text(
                        listOfNotNull(hit.symbol, hit.exchange).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = WeatherTextMuted,
                    )
                }
            }
            val hit = chosen
            if (hit != null) {
                val parsed = remember(quantity) { HoldingsLogic.parseAmount(quantity) }
                val amount = (parsed as? HoldingsLogic.Parsed.Ok)?.value?.takeIf { it > 0.0 }
                OutlinedTextField(
                    value = quantity,
                    onValueChange = { quantity = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.markets_sec_quantity)) },
                    isError = quantity.isNotBlank() && amount == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                    colors = holdingsFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(
                        enabled = amount != null,
                        onClick = {
                            actions.onAdd(hit, search.ident, amount ?: return@TextButton)
                            ident = ""
                            quantity = ""
                            chosen = null
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = WeatherText, disabledContentColor = WeatherTextMuted),
                    ) { Text(stringResource(R.string.markets_sec_add)) }
                }
            }
        }
    }
}

@Composable
private fun Hint(textRes: Int) {
    Text(stringResource(textRes), style = MaterialTheme.typography.bodySmall, color = WeatherTextMuted)
}

/** Anzeigename eines Eintrags: Rohstoffe in der Sprache der App, sonst der gespeicherte Name. */
@Composable
internal fun positionName(position: Position): String =
    if (position.kind == PositionKind.Commodity) commodityNameRes(position.symbol)?.let { stringResource(it) } ?: position.name else position.name

/** Einheit der Menge: Handelseinheit bei Rohstoffen, Kürzel bei Krypto, bei Aktien keine. */
internal fun positionUnit(position: Position): String = when (position.kind) {
    PositionKind.Commodity -> QuoteCatalog.byId(position.symbol)?.tag.orEmpty()
    PositionKind.Crypto -> CryptoCatalog.byId(position.symbol)?.symbol.orEmpty()
    PositionKind.Stock -> ""
}

@Composable
private fun PositionRow(position: Position, actions: SecurityActions) {
    var text by remember(position.quantity) { mutableStateOf(plain(position.quantity)) }
    val parsed = remember(text) { HoldingsLogic.parseAmount(text) }
    val candidate = (parsed as? HoldingsLogic.Parsed.Ok)?.value
    val unit = positionUnit(position)
    HorizontalDivider(color = WeatherTextMuted.copy(alpha = 0.3f))
    Text(positionName(position), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    if (position.kind == PositionKind.Stock) {
        Text(
            listOf(position.symbol, position.ident).filter { it.isNotBlank() }.joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = WeatherTextMuted,
        )
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            label = { Text(stringResource(if (position.kind == PositionKind.Stock) R.string.markets_sec_quantity else R.string.markets_own_amount)) },
            suffix = if (unit.isNotEmpty()) {
                { Text(unit) }
            } else {
                null
            },
            isError = candidate == null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
            colors = holdingsFieldColors(),
            modifier = Modifier.weight(1f),
        )
        TextButton(
            enabled = candidate != null && candidate != position.quantity,
            onClick = { candidate?.let { actions.onQuantity(position.kind, position.symbol, it) } },
            colors = ButtonDefaults.textButtonColors(contentColor = WeatherText, disabledContentColor = WeatherTextMuted),
        ) { Text(stringResource(R.string.markets_holdings_save)) }
        TextButton(
            onClick = { actions.onRemove(position.kind, position.symbol) },
            colors = ButtonDefaults.textButtonColors(contentColor = WeatherText, disabledContentColor = WeatherTextMuted),
        ) { Text(stringResource(R.string.markets_holdings_remove)) }
    }
}

/** Werte der Einträge in Euro, dazu der Kurs in seiner Notierungswährung. */
@Composable
internal fun OwnValueRows(valuation: PositionsValuation, locale: Locale) {
    val none = stringResource(R.string.markets_holdings_unavailable)
    valuation.items.forEach { item ->
        val unit = positionUnit(item.position)
        val amount = MarketFormat.trimmed(item.position.quantity, locale, 8) + if (unit.isNotEmpty()) " $unit" else ""
        ValueRow("${positionName(item.position)} ($amount)", item.valueEur?.let { MarketFormat.money(it, "EUR", locale) } ?: none)
        val price = item.price
        val currency = item.currency
        if (price != null && currency != null && FxLogic.isSupported(currency)) {
            Text(
                stringResource(R.string.markets_own_price, MarketFormat.price(price, currency, locale)),
                style = MaterialTheme.typography.bodySmall,
                color = WeatherTextMuted,
            )
        }
    }
}
