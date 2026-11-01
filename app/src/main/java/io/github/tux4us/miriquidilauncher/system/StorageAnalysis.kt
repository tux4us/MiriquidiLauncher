// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.system

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class AnalysisState(
    val usageAccess: Boolean = false,
    /** `null`, solange nicht geladen (kein Zugriff oder noch nicht angefragt). */
    val apps: List<AppStorage>? = null,
    val appsLoading: Boolean = false,
)

/** Cache je App. Wird nur mit gewährtem Nutzungszugriff gelesen. */
class StorageAnalysis(application: Application, private val scope: CoroutineScope) {
    private val repository = AppStorageRepository(application)

    private val _state = MutableStateFlow(AnalysisState())
    val state: StateFlow<AnalysisState> = _state.asStateFlow()

    private var appsJob: Job? = null

    /** Liest die Zugriffsrechte neu; lädt die App-Größen, sobald der Nutzungszugriff vorliegt. */
    fun refreshAccess() {
        val usage = repository.hasUsageAccess()
        _state.update { it.copy(usageAccess = usage, apps = if (usage) it.apps else null) }
        if (usage && _state.value.apps == null && !_state.value.appsLoading) loadApps()
    }

    fun loadApps() {
        if (!repository.hasUsageAccess()) return
        appsJob?.cancel()
        _state.update { it.copy(appsLoading = true) }
        appsJob = scope.launch(Dispatchers.IO) {
            val apps = repository.loadAppStorage()
            _state.update { it.copy(apps = apps, appsLoading = false) }
        }
    }
}
