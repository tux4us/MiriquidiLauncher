// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.dogs

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.LocalDate
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Ergebnis des Imports aus einer HundeWildnis-Sicherung; [error] = Datei nicht lesbar oder ohne Hundeprofile. */
data class DogImportResult(val added: Int, val skipped: Int, val error: Boolean)

data class DogUiState(
    val data: DogData = DogData(),
    val loaded: Boolean = false,
    val selectedId: String? = null,
    val importResult: DogImportResult? = null,
    val importing: Boolean = false,
) {
    val selected: DogProfile? get() = data.dogs.firstOrNull { it.id == selectedId } ?: data.dogs.firstOrNull()
}

/** Zustand der Scope „Hunde“ und der Fälligkeits-Card der Startseite. Änderungen werden sofort gespeichert. */
class DogViewModel(application: Application) : AndroidViewModel(application) {
    private val store = DogStore(application)

    private val _state = MutableStateFlow(DogUiState())
    val state: StateFlow<DogUiState> = _state.asStateFlow()

    init {
        reloadFromStore()
    }

    /** Liest den gespeicherten Stand (beim Start und nach dem Einspielen eines Backups). */
    fun reloadFromStore() {
        viewModelScope.launch {
            val data = store.load()
            _state.update { it.copy(data = data, loaded = true) }
        }
    }

    fun select(id: String) = _state.update { it.copy(selectedId = id) }

    private fun commit(data: DogData, selectedId: String? = _state.value.selectedId) {
        _state.update { it.copy(data = data, selectedId = selectedId) }
        viewModelScope.launch { store.save(data) }
    }

    /** Legt den Hund an oder ersetzt ihn und wählt ihn aus. */
    fun saveDog(dog: DogProfile) = commit(DogLogic.upsert(_state.value.data, dog), dog.id)

    fun deleteDog(id: String) {
        val data = DogLogic.remove(_state.value.data, id)
        commit(data, data.dogs.firstOrNull()?.id)
    }

    fun saveItem(dogId: String, item: HealthItem) {
        val data = _state.value.data
        val dog = data.dogs.firstOrNull { it.id == dogId } ?: return
        commit(DogLogic.upsert(data, DogLogic.upsertItem(dog, item)))
    }

    fun deleteItem(dogId: String, itemId: String) {
        val data = _state.value.data
        val dog = data.dogs.firstOrNull { it.id == dogId } ?: return
        commit(DogLogic.upsert(data, DogLogic.removeItem(dog, itemId)))
    }

    fun markDone(dogId: String, itemId: String, today: LocalDate = LocalDate.now()) {
        val data = _state.value.data
        val dog = data.dogs.firstOrNull { it.id == dogId } ?: return
        val item = dog.health.firstOrNull { it.id == itemId } ?: return
        commit(DogLogic.upsert(data, DogLogic.upsertItem(dog, DogLogic.markDone(item, today))))
    }

    fun clearImportResult() = _state.update { it.copy(importResult = null) }

    /**
     * Übernimmt Hundeprofile aus einer Sicherung der HundeWildnis-App (`.hundebackup`). Die Datei wird nur gelesen,
     * von den Einträgen des ZIP-Archivs wird allein `backup_data.json` ausgewertet (nichts wird entpackt).
     */
    fun importHundeWildnis(uri: Uri) {
        _state.update { it.copy(importing = true, importResult = null) }
        viewModelScope.launch {
            val parsed = withContext(Dispatchers.IO) {
                try {
                    HundeWildnisImport.parse(readBackupJson(uri))
                } catch (e: HundeWildnisImport.ImportException) {
                    null
                } catch (e: IOException) {
                    null
                } catch (e: SecurityException) {
                    null
                }
            }
            if (parsed == null || parsed.isEmpty()) {
                _state.update { it.copy(importing = false, importResult = DogImportResult(0, 0, error = true)) }
                return@launch
            }
            val merged = DogLogic.merge(_state.value.data, parsed)
            commit(merged.data, _state.value.selectedId ?: merged.data.dogs.firstOrNull()?.id)
            _state.update { it.copy(importing = false, importResult = DogImportResult(merged.added, merged.skipped, error = false)) }
        }
    }

    private fun readBackupJson(uri: Uri): String {
        val resolver = getApplication<Application>().contentResolver
        val stream = resolver.openInputStream(uri) ?: throw IOException("Datei nicht lesbar")
        try {
            ZipInputStream(stream.buffered()).use { zip ->
                while (true) {
                    val entry = try {
                        zip.nextEntry
                    } catch (e: ZipException) {
                        throw IOException("Kein ZIP-Archiv")
                    } ?: break
                    if (entry.isDirectory || entry.name.substringAfterLast('/') != DATA_FILE) continue
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = zip.read(buffer)
                        if (n < 0) break
                        if (out.size() + n > HundeWildnisImport.MAX_JSON_CHARS) throw HundeWildnisImport.ImportException("Datei zu groß")
                        out.write(buffer, 0, n)
                    }
                    return out.toString(Charsets.UTF_8.name())
                }
            }
        } catch (e: ZipException) {
            throw IOException("Kein ZIP-Archiv")
        }
        throw HundeWildnisImport.ImportException("backup_data.json fehlt")
    }

    private companion object {
        const val DATA_FILE = "backup_data.json"
    }
}
