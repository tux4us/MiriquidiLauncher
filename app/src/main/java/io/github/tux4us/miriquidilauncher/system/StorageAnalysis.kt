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

sealed interface TreeState {
    object Idle : TreeState
    data class Scanning(val dirs: Int, val files: Long) : TreeState

    /** [path] beginnt mit [root]; das letzte Element ist der angezeigte Ordner. */
    class Done(val root: DirNode, val path: List<DirNode>) : TreeState
}

data class AnalysisState(
    val usageAccess: Boolean = false,
    val filesAccess: Boolean = false,
    /** `null`, solange nicht geladen (kein Zugriff oder noch nicht angefragt). */
    val apps: List<AppStorage>? = null,
    val appsLoading: Boolean = false,
    val tree: TreeState = TreeState.Idle,
)

/** Cache je App und Speicherbaum. Beides wird nur auf Wunsch bzw. mit gewährtem Sonderzugriff gelesen. */
class StorageAnalysis(application: Application, private val scope: CoroutineScope) {
    private val repository = AppStorageRepository(application)

    private val _state = MutableStateFlow(AnalysisState())
    val state: StateFlow<AnalysisState> = _state.asStateFlow()

    private var appsJob: Job? = null
    private var scanJob: Job? = null

    /** Liest die Zugriffsrechte neu; lädt die App-Größen, sobald der Nutzungszugriff vorliegt. */
    fun refreshAccess() {
        val usage = repository.hasUsageAccess()
        val files = repository.hasAllFilesAccess()
        _state.update { it.copy(usageAccess = usage, filesAccess = files, apps = if (usage) it.apps else null) }
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

    fun startScan() {
        if (scanJob?.isActive == true || !repository.hasAllFilesAccess()) return
        _state.update { it.copy(tree = TreeState.Scanning(0, 0)) }
        scanJob = scope.launch(Dispatchers.IO) {
            val root = DirectoryScanner.scan(
                repository.sharedStorageRoot(),
                isCancelled = { !isActive },
                onProgress = { dirs, files ->
                    _state.update { if (it.tree is TreeState.Scanning) it.copy(tree = TreeState.Scanning(dirs, files)) else it }
                },
            )
            _state.update { it.copy(tree = if (root != null) TreeState.Done(root, listOf(root)) else TreeState.Idle) }
        }
    }

    fun cancelScan() {
        scanJob?.cancel()
        _state.update { it.copy(tree = TreeState.Idle) }
    }

    /** Wechselt in den Unterordner [node] des angezeigten Ordners. */
    fun open(node: DirNode) {
        _state.update { old ->
            val tree = old.tree as? TreeState.Done ?: return@update old
            if (node !in tree.path.last().children) return@update old
            old.copy(tree = TreeState.Done(tree.root, tree.path + node))
        }
    }

    fun up() {
        _state.update { old ->
            val tree = old.tree as? TreeState.Done ?: return@update old
            if (tree.path.size <= 1) return@update old
            old.copy(tree = TreeState.Done(tree.root, tree.path.dropLast(1)))
        }
    }

    /** Verwirft den Baum, um Arbeitsspeicher freizugeben. */
    fun discard() {
        scanJob?.cancel()
        _state.update { it.copy(tree = TreeState.Idle) }
    }
}
