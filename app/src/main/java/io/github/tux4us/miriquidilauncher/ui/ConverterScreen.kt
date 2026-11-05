// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.tux4us.miriquidilauncher.R
import io.github.tux4us.miriquidilauncher.converter.ConverterCatalog
import io.github.tux4us.miriquidilauncher.converter.ConverterCategory
import io.github.tux4us.miriquidilauncher.converter.ConverterNumbers
import io.github.tux4us.miriquidilauncher.converter.ConverterViewModel
import io.github.tux4us.miriquidilauncher.converter.FieldInput
import io.github.tux4us.miriquidilauncher.converter.display
import java.text.DecimalFormatSymbols
import java.util.Locale

private fun categoryName(id: String): Int = when (id) {
    ConverterCatalog.LENGTH -> R.string.converter_cat_length
    ConverterCatalog.AREA -> R.string.converter_cat_area
    ConverterCatalog.VOLUME -> R.string.converter_cat_volume
    ConverterCatalog.TIME -> R.string.converter_cat_time
    ConverterCatalog.TEMPERATURE -> R.string.converter_cat_temperature
    ConverterCatalog.SPEED -> R.string.converter_cat_speed
    ConverterCatalog.MASS -> R.string.converter_cat_mass
    ConverterCatalog.FORCE -> R.string.converter_cat_force
    ConverterCatalog.FUEL -> R.string.converter_cat_fuel
    ConverterCatalog.RADIX -> R.string.converter_cat_radix
    ConverterCatalog.ENERGY -> R.string.converter_cat_energy
    ConverterCatalog.POWER -> R.string.converter_cat_power
    ConverterCatalog.PRESSURE -> R.string.converter_cat_pressure
    ConverterCatalog.ANGLE -> R.string.converter_cat_angle
    ConverterCatalog.DENSITY -> R.string.converter_cat_density
    ConverterCatalog.SHOE -> R.string.converter_cat_shoe
    ConverterCatalog.CLOTHES_WOMEN -> R.string.converter_cat_clothes_women
    ConverterCatalog.CLOTHES_MEN -> R.string.converter_cat_clothes_men
    ConverterCatalog.DIGITAL -> R.string.converter_cat_digital
    else -> R.string.converter_cat_prefixes
}

/** Hinweis zu einer Kategorie (Näherungswerte, Definitionen); `null`, wenn keiner nötig ist. */
private fun categoryNote(id: String): Int? = when (id) {
    ConverterCatalog.TIME -> R.string.converter_note_time
    ConverterCatalog.FUEL -> R.string.converter_note_fuel
    ConverterCatalog.RADIX -> R.string.converter_note_radix
    ConverterCatalog.SHOE -> R.string.converter_note_shoe
    ConverterCatalog.CLOTHES_WOMEN, ConverterCatalog.CLOTHES_MEN -> R.string.converter_note_clothes
    else -> null
}

/**
 * Scope „Umrechner“: Je Kategorie (Längen, Flächen, Volumen, Temperatur, Zahlensysteme, Schuh- und Kleidergrößen usw.)
 * gibt es für jede Einheit ein Eingabefeld. Sobald in einem Feld ein Wert steht, füllt der Umrechner alle übrigen
 * Felder. Die Rechenlogik liegt in `ConverterCatalog` und `ConverterEngine`.
 */
@Composable
fun ConverterScreen(viewModel: ConverterViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val category = ConverterCatalog.byId(state.categoryId) ?: ConverterCatalog.default
    val german = remember { Locale.getDefault().language == "de" }
    val separator = remember { DecimalFormatSymbols.getInstance().decimalSeparator }
    val entry = state.entries[category.id]
    val values = remember(category.id, entry, separator) { category.display(entry?.sourceId, entry?.text.orEmpty(), separator) }

    ScopePage(title = stringResource(R.string.scope_converter), modifier = modifier) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CategoryPicker(category.id, viewModel::selectCategory, Modifier.weight(1f))
                TextButton(
                    onClick = viewModel::clear,
                    enabled = entry != null,
                    colors = ButtonDefaults.textButtonColors(contentColor = WeatherText, disabledContentColor = WeatherTextMuted),
                ) { Text(stringResource(R.string.converter_clear)) }
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                categoryNote(category.id)?.let { note ->
                    item(key = "note") {
                        Text(stringResource(note), style = MaterialTheme.typography.bodySmall, color = WeatherTextMuted)
                    }
                }
                items(category.units, key = { "${category.id}:${it.id}" }) { unit ->
                    ConverterField(
                        category = category,
                        label = unit.label(german),
                        text = values[unit.id].orEmpty(),
                        onChange = { viewModel.edit(unit.id, it) },
                    )
                }
                item(key = "end") { Spacer(Modifier.height(ScopeBottomClearance)) }
            }
        }
    }
}

@Composable
private fun CategoryPicker(selectedId: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    val description = stringResource(R.string.converter_category)
    Box(modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .glass(RoundedCornerShape(16.dp))
                .clickable(role = Role.DropdownList) { open = true }
                .semantics { contentDescription = description }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(categoryName(selectedId)),
                color = WeatherText,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text("▾", color = WeatherTextMuted, style = MaterialTheme.typography.titleMedium)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ConverterCatalog.ids.forEach { id ->
                DropdownMenuItem(
                    text = { Text(stringResource(categoryName(id)), fontWeight = if (id == selectedId) FontWeight.Bold else FontWeight.Normal) },
                    onClick = {
                        open = false
                        onSelect(id)
                    },
                )
            }
        }
    }
}

@Composable
private fun ConverterField(category: ConverterCategory, label: String, text: String, onChange: (String) -> Unit) {
    val signLabel = stringResource(R.string.converter_toggle_sign)
    val numeric = category.input == FieldInput.Decimal
    OutlinedTextField(
        value = text,
        onValueChange = onChange,
        singleLine = true,
        label = { Text(label, maxLines = 1) },
        keyboardOptions = KeyboardOptions(
            keyboardType = if (numeric) KeyboardType.Decimal else KeyboardType.Ascii,
            capitalization = if (numeric) KeyboardCapitalization.None else KeyboardCapitalization.Characters,
            imeAction = ImeAction.Done,
        ),
        // Die Zahlentastatur hat nicht überall ein Minus; bei Kategorien mit negativen Werten wechselt „±“ das Vorzeichen.
        trailingIcon = if (numeric && category.allowNegative) {
            {
                TextButton(
                    onClick = { onChange(ConverterNumbers.toggleMinus(text)) },
                    colors = ButtonDefaults.textButtonColors(contentColor = WeatherText),
                    modifier = Modifier.semantics { contentDescription = signLabel },
                ) { Text("±", style = MaterialTheme.typography.titleMedium) }
            }
        } else {
            null
        },
        colors = holdingsFieldColors(),
        modifier = Modifier.fillMaxWidth(),
    )
}
