// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.converter

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Zuletzt bearbeitetes Feld einer Kategorie und sein Text; aus ihm ergeben sich alle übrigen Felder. */
data class ConverterEntry(val sourceId: String, val text: String)

data class ConverterUiState(
    val categoryId: String = ConverterCatalog.default.id,
    /** Eingabe je Kategorie (Kennung → Eingabe), damit beim Wechsel der Kategorie nichts verloren geht. */
    val entries: Map<String, ConverterEntry> = emptyMap(),
)

/**
 * Zustand der Scope „Umrechner“. Gespeichert wird nur im Zustand der Aktivität (übersteht Drehen und das Beenden des
 * Prozesses durch das System), nicht auf dem Gerät: Die Eingaben sind flüchtig und stehen nicht im Backup.
 */
class ConverterViewModel(private val handle: SavedStateHandle) : ViewModel() {
    private val _state = MutableStateFlow(restore())
    val state: StateFlow<ConverterUiState> = _state.asStateFlow()

    private fun restore(): ConverterUiState {
        val category = handle.get<String>(KEY_CATEGORY)?.takeIf { ConverterCatalog.byId(it) != null } ?: ConverterCatalog.default.id
        val entries = handle.get<ArrayList<String>>(KEY_ENTRIES).orEmpty().mapNotNull { line ->
            val parts = line.split(SEPARATOR, limit = 3)
            if (parts.size != 3) return@mapNotNull null
            val c = ConverterCatalog.byId(parts[0]) ?: return@mapNotNull null
            if (c.units.none { it.id == parts[1] }) return@mapNotNull null
            parts[0] to ConverterEntry(parts[1], c.sanitize(parts[1], parts[2]))
        }.toMap()
        return ConverterUiState(category, entries)
    }

    private fun persist(state: ConverterUiState) {
        handle[KEY_CATEGORY] = state.categoryId
        handle[KEY_ENTRIES] = ArrayList(state.entries.map { (id, e) -> "$id$SEPARATOR${e.sourceId}$SEPARATOR${e.text}" })
    }

    private fun mutate(block: (ConverterUiState) -> ConverterUiState) {
        _state.update(block)
        persist(_state.value)
    }

    fun selectCategory(id: String) {
        if (ConverterCatalog.byId(id) == null || id == _state.value.categoryId) return
        mutate { it.copy(categoryId = id) }
    }

    /** Der Nutzer hat im Feld [unitId] der gewählten Kategorie [text] eingegeben; ein leerer Text leert alle Felder. */
    fun edit(unitId: String, text: String) {
        val category = ConverterCatalog.byId(_state.value.categoryId) ?: return
        if (category.units.none { it.id == unitId }) return
        val clean = category.sanitize(unitId, text)
        mutate {
            val entries = if (clean.isEmpty()) it.entries - category.id else it.entries + (category.id to ConverterEntry(unitId, clean))
            it.copy(entries = entries)
        }
    }

    /** Leert alle Felder der gewählten Kategorie. */
    fun clear() = mutate { it.copy(entries = it.entries - it.categoryId) }

    private companion object {
        const val KEY_CATEGORY = "category"
        const val KEY_ENTRIES = "entries"
        const val SEPARATOR = '\u001F'
    }
}
