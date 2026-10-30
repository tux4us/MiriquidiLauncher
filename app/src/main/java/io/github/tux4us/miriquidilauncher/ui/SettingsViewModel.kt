// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import io.github.tux4us.miriquidilauncher.backup.BackupCodec
import io.github.tux4us.miriquidilauncher.backup.BackupManager
import io.github.tux4us.miriquidilauncher.backup.BackupQr
import io.github.tux4us.miriquidilauncher.backup.ImportSummary
import io.github.tux4us.miriquidilauncher.scopes.ScopeVisibility
import io.github.tux4us.miriquidilauncher.scopes.ScopeVisibilityStore

/** Rückmeldung zu einem Export oder Import; [Text] wird in der Oberfläche übersetzt. */
sealed interface BackupMessage {
    data class Exported(val path: String) : BackupMessage
    data object ExportFailed : BackupMessage
    data class Imported(val summary: ImportSummary) : BackupMessage
    data object InvalidFile : BackupMessage
    data object ReadFailed : BackupMessage
}

data class SettingsUiState(
    val includeContacts: Boolean = true,
    val busy: Boolean = false,
    val message: BackupMessage? = null,
)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val backup = BackupManager(application)

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    private val scopeStore = ScopeVisibilityStore(application)
    private val _hiddenScopes = MutableStateFlow<Set<String>>(emptySet())

    /** Namen der ausgeblendeten Scopes. */
    val hiddenScopes: StateFlow<Set<String>> = _hiddenScopes.asStateFlow()

    init {
        viewModelScope.launch { _hiddenScopes.value = scopeStore.loadHidden(ScopeNames.toSet()) }
    }

    /** Blendet [scope] ein oder aus; die Einstellungen bleiben immer sichtbar. */
    fun setScopeVisible(scope: Scope, visible: Boolean) {
        val next = ScopeVisibility.withVisible(_hiddenScopes.value, scope.name, visible, PinnedScopeNames)
        if (next == _hiddenScopes.value) return
        _hiddenScopes.value = next
        viewModelScope.launch { scopeStore.saveHidden(next) }
    }

    fun setIncludeContacts(value: Boolean) = _state.update { it.copy(includeContacts = value) }

    fun clearMessage() = _state.update { it.copy(message = null) }

    fun export() {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            val result = try {
                BackupMessage.Exported(backup.export(_state.value.includeContacts))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                BackupMessage.ExportFailed
            }
            _state.update { it.copy(busy = false, message = result) }
        }
    }

    /** [onRestored] wird nach erfolgreichem Import aufgerufen, damit abhängige Bildschirme neu laden. */
    fun import(uri: Uri, onRestored: () -> Unit) = runImport(onRestored) { backup.import(uri) }

    /** Spielt ein Backup aus JSON-Text ein, z. B. aus gescannten QR-Codes. */
    fun importText(text: String, onRestored: () -> Unit) = runImport(onRestored) { backup.importText(text) }

    /**
     * Texte der QR-Codes für den aktuellen Bestand; ob Kontakte enthalten sind, bestimmt die Einstellung oben im
     * Backup-Abschnitt. [password] `null` = unverschlüsselt.
     * @throws BackupQr.TooLargeException wenn das Backup nicht in höchstens [BackupQr.MAX_PARTS] Codes passt.
     */
    suspend fun createQrParts(password: CharArray?): List<String> =
        backup.createQrParts(_state.value.includeContacts, password)

    private fun runImport(onRestored: () -> Unit, block: suspend () -> ImportSummary) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            val result = try {
                BackupMessage.Imported(block()).also { onRestored() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: BackupCodec.BackupException) {
                BackupMessage.InvalidFile
            } catch (e: Exception) {
                BackupMessage.ReadFailed
            }
            _state.update { it.copy(busy = false, message = result) }
        }
    }
}
