// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.recents

import android.app.Application
import android.os.Process
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.tux4us.miriquidilauncher.data.AppEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Eine Karte der Übersicht. */
data class RecentItem(val entry: AppEntry, val lastUsedMillis: Long)

data class RecentsUiState(
    /** Ob der Nutzungszugriff erteilt ist; ohne ihn lässt sich die Liste nicht bilden. */
    val usageAccess: Boolean = true,
    val items: List<RecentItem> = emptyList(),
    val loading: Boolean = true,
)

/**
 * Zustand der App-Übersicht. Die Liste wird beim Öffnen der Übersicht neu gebildet ([refresh]); „Entfernen“ blendet
 * eine App nur aus der Übersicht aus, beendet sie aber nicht.
 */
class RecentsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = RecentsRepository(application)
    private val store = RecentsStore(application)

    private val _state = MutableStateFlow(RecentsUiState())
    val state: StateFlow<RecentsUiState> = _state.asStateFlow()

    private var apps: List<AppEntry> = emptyList()
    private var samples: List<RecentsLogic.UsageSample> = emptyList()
    private var dismissed: Map<String, Long> = emptyMap()
    private var loadJob: Job? = null

    /** Liest die Nutzungsereignisse neu und bildet die Liste für die übergebenen [apps]. */
    fun refresh(apps: List<AppEntry>) {
        this.apps = apps
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            if (!repository.hasUsageAccess()) {
                _state.value = RecentsUiState(usageAccess = false, loading = false)
                return@launch
            }
            _state.value = _state.value.copy(usageAccess = true, loading = true)
            val now = System.currentTimeMillis()
            val loaded = withContext(Dispatchers.IO) {
                dismissed = RecentsLogic.prune(store.dismissed.first(), now)
                repository.loadSamples(now)
            }
            samples = loaded
            _state.value = RecentsUiState(usageAccess = true, items = buildItems(), loading = false)
        }
    }

    /** Blendet [packageName] aus der Übersicht aus, bis die App wieder benutzt wird. */
    fun dismiss(packageName: String) = dismissAll(listOf(packageName))

    /** Blendet alle gezeigten Apps aus. */
    fun dismissAllShown() = dismissAll(_state.value.items.map { it.entry.packageName })

    private fun dismissAll(packages: List<String>) {
        if (packages.isEmpty()) return
        dismissed = RecentsLogic.withDismissed(dismissed, packages, System.currentTimeMillis())
        _state.value = _state.value.copy(items = buildItems())
        val marks = dismissed
        viewModelScope.launch { store.setDismissed(marks) }
    }

    private fun buildItems(): List<RecentItem> {
        val ownPackage = getApplication<Application>().packageName
        val main = Process.myUserHandle()
        // Je Paket der Eintrag des Hauptprofils, sonst der erste.
        val byPackage = HashMap<String, AppEntry>()
        for (entry in apps) {
            val known = byPackage[entry.packageName]
            if (known == null || (known.user != main && entry.user == main)) byPackage[entry.packageName] = entry
        }
        return RecentsLogic.recentApps(samples, ownPackage, byPackage.keys, dismissed)
            .mapNotNull { recent -> byPackage[recent.packageName]?.let { RecentItem(it, recent.lastUsedMillis) } }
    }
}
