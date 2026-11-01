// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.files

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class OpKind { COPY, MOVE, DELETE, TRASH, RESTORE, ZIP, UNZIP, EMPTY_TRASH }

enum class FilesNoticeKind {
    DONE,
    PARTLY_FAILED,
    CANCELLED,
    ERROR_LISTING,
    NAME_INVALID,
    NAME_EXISTS,
    RENAME_FAILED,
    CREATE_FAILED,
    NOT_IN_VOLUME,
    NO_APP,
    APK_UNSUPPORTED,
    ZIP_ENCRYPTED,
    ZIP_LIMIT,
    ZIP_FAILED,
    SEARCH_LIMIT,
}

/** Hinweis für die Oberfläche: [count] erledigte, [other] fehlgeschlagene oder übersprungene Einträge, [detail] Namen. */
data class FilesNotice(val kind: FilesNoticeKind, val op: OpKind? = null, val count: Int = 0, val other: Int = 0, val detail: String = "")

data class Clipboard(val paths: List<String>, val cut: Boolean)

data class OperationUi(val kind: OpKind, val progress: FileProgress? = null)

data class PropertiesUi(val entry: FileEntry, val size: SizeInfo? = null, val loading: Boolean = true)

data class FilesUiState(
    /** `null`, solange der Zugriff noch nicht geprüft ist. */
    val access: Boolean? = null,
    val volumes: List<Volume> = emptyList(),
    val quickAccess: List<String> = emptyList(),
    /** Wurzel des gewählten Speichers. */
    val root: String = "",
    val path: String = "",
    val entries: List<FileEntry> = emptyList(),
    val loading: Boolean = false,
    val listError: Boolean = false,
    val selection: Set<String> = emptySet(),
    val clipboard: Clipboard? = null,
    val sort: SortOrder = SortOrder(),
    val showHidden: Boolean = false,
    /** `null`, wenn nicht gesucht wird. */
    val searchQuery: String? = null,
    val searchResults: List<FileEntry> = emptyList(),
    val searching: Boolean = false,
    val trashOpen: Boolean = false,
    val trashItems: List<TrashItem> = emptyList(),
    val operation: OperationUi? = null,
    val properties: PropertiesUi? = null,
    val notice: FilesNotice? = null,
)

/**
 * Zustand und Aktionen des Dateimanagers. Vorgänge laufen auf `Dispatchers.IO` und sind abbrechbar; nach jedem
 * Vorgang wird der Ordner neu gelesen. Die Vorgänge laufen, solange der Prozess lebt, es gibt keinen eigenen Dienst.
 */
class FilesViewModel(application: Application) : AndroidViewModel(application) {
    private val store = FilesStore(application)
    private val _state = MutableStateFlow(FilesUiState())
    val state: StateFlow<FilesUiState> = _state.asStateFlow()

    private var opJob: Job? = null
    private var searchJob: Job? = null
    private var propertiesJob: Job? = null
    private var listJob: Job? = null

    @Volatile
    private var cancelFlag = false
    private var settingsLoaded = false

    /** Die gespeicherten Einstellungen wurden ersetzt (Backup-Import): Sie werden beim nächsten Öffnen der Seite neu gelesen. */
    fun invalidateSettings() {
        settingsLoaded = false
    }
    private var trashPurged = false

    // region Zugriff und Start ------------------------------------------------------------------

    /** Die Seite wird sichtbar oder die App kommt zurück (z. B. aus den Systemeinstellungen). */
    fun onActive() {
        val granted = FileVolumes.hasAllFilesAccess(getApplication())
        _state.update { it.copy(access = granted) }
        if (!granted) return
        viewModelScope.launch {
            if (!settingsLoaded) {
                val settings = store.load()
                settingsLoaded = true
                _state.update { it.copy(sort = settings.sort, showHidden = settings.showHidden) }
                startAt(settings.lastPath)
            } else if (_state.value.path.isEmpty()) {
                startAt(null)
            } else {
                reload()
            }
            if (!trashPurged) {
                trashPurged = true
                val roots = _state.value.volumes.map { File(it.path) }
                withContext(Dispatchers.IO) { FileTrash.purgeExpired(roots, System.currentTimeMillis()) }
            }
        }
    }

    private fun startAt(last: String?) {
        val volumes = FileVolumes.list(getApplication())
        val paths = volumes.map { it.path }
        val start = last?.takeIf { File(it).isDirectory && FilePaths.rootOf(it, paths) != null } ?: volumes.first().path
        val root = FilePaths.rootOf(start, paths) ?: volumes.first().path
        _state.update {
            it.copy(
                volumes = volumes,
                quickAccess = FileVolumes.quickAccess().map { f -> f.absolutePath },
                root = root,
                path = start,
            )
        }
        reload()
    }

    // endregion

    // region Navigation und Liste ----------------------------------------------------------------

    fun open(path: String) {
        val paths = _state.value.volumes.map { it.path }
        val root = FilePaths.rootOf(path, paths) ?: return
        _state.update {
            it.copy(root = root, path = path, selection = emptySet(), searchQuery = null, searchResults = emptyList(), trashOpen = false)
        }
        viewModelScope.launch { store.saveLastPath(path) }
        reload()
    }

    fun selectVolume(volume: Volume) = open(volume.path)

    fun up() {
        val s = _state.value
        FilePaths.parentWithin(s.path, s.root)?.let(::open)
    }

    /** Geht eine Ebene zurück: Auswahl, Suche, Papierkorb, dann Ordner. `false`, wenn nichts mehr zu schließen ist. */
    fun back(): Boolean {
        val s = _state.value
        return when {
            s.properties != null -> { closeProperties(); true }
            s.selection.isNotEmpty() -> { clearSelection(); true }
            s.searchQuery != null -> { closeSearch(); true }
            s.trashOpen -> { closeTrash(); true }
            FilePaths.parentWithin(s.path, s.root) != null -> { up(); true }
            else -> false
        }
    }

    fun reload() {
        val s = _state.value
        if (s.path.isEmpty()) return
        listJob?.cancel()
        _state.update { it.copy(loading = true, listError = false) }
        listJob = viewModelScope.launch {
            val dir = File(s.path)
            val entries = withContext(Dispatchers.IO) { readEntries(dir, s.showHidden) }
            _state.update { current ->
                if (current.path != s.path) {
                    current
                } else if (entries == null) {
                    current.copy(loading = false, listError = true, entries = emptyList())
                } else {
                    current.copy(
                        loading = false,
                        entries = FileSorter.sort(entries, current.sort),
                        selection = current.selection.filter { p -> entries.any { it.path == p } }.toSet(),
                    )
                }
            }
        }
    }

    private fun readEntries(dir: File, showHidden: Boolean): List<FileEntry>? {
        val children = dir.listFiles() ?: return null
        return children.mapNotNull { f ->
            val hidden = f.name.startsWith('.')
            if (hidden && !showHidden) return@mapNotNull null
            toEntry(f)
        }
    }

    private fun toEntry(f: File): FileEntry {
        val dir = f.isDirectory
        return FileEntry(
            path = f.absolutePath,
            name = f.name,
            isDirectory = dir,
            sizeBytes = if (dir) 0L else f.length(),
            modifiedMillis = f.lastModified(),
            hidden = f.name.startsWith('.'),
            kind = FileTypes.kindOf(f.name, dir),
        )
    }

    fun setSort(order: SortOrder) {
        _state.update { it.copy(sort = order, entries = FileSorter.sort(it.entries, order)) }
        viewModelScope.launch { store.saveSort(order) }
    }

    fun toggleHidden() {
        val next = !_state.value.showHidden
        _state.update { it.copy(showHidden = next) }
        viewModelScope.launch { store.saveShowHidden(next) }
        reload()
    }

    // endregion

    // region Auswahl und Zwischenablage ----------------------------------------------------------

    fun toggleSelect(path: String) = _state.update {
        it.copy(selection = if (path in it.selection) it.selection - path else it.selection + path)
    }

    fun selectAll() = _state.update { s -> s.copy(selection = s.entries.map { it.path }.toSet()) }
    fun clearSelection() = _state.update { it.copy(selection = emptySet()) }
    fun dismissNotice() = _state.update { it.copy(notice = null) }
    private fun notice(n: FilesNotice) = _state.update { it.copy(notice = n) }

    fun copySelection() = putOnClipboard(cut = false)
    fun cutSelection() = putOnClipboard(cut = true)

    private fun putOnClipboard(cut: Boolean) = _state.update {
        if (it.selection.isEmpty()) it else it.copy(clipboard = Clipboard(it.selection.toList(), cut), selection = emptySet())
    }

    fun clearClipboard() = _state.update { it.copy(clipboard = null) }

    fun paste() {
        val s = _state.value
        val clip = s.clipboard ?: return
        val target = File(s.path)
        val sources = clip.paths.map(::File).filter { it.exists() }
        val kind = if (clip.cut) OpKind.MOVE else OpKind.COPY
        runOperation(kind) { progress, cancelled ->
            if (clip.cut) FileOps.move(sources, target, progress, cancelled) else FileOps.copy(sources, target, progress, cancelled)
        }
        if (clip.cut) _state.update { it.copy(clipboard = null) }
    }

    // endregion

    // region Vorgänge ----------------------------------------------------------------------------

    fun cancelOperation() {
        cancelFlag = true
    }

    private fun runOperation(kind: OpKind, block: (progress: (FileProgress) -> Unit, cancelled: () -> Boolean) -> OpResult) {
        if (opJob?.isActive == true) return
        cancelFlag = false
        opJob = viewModelScope.launch {
            _state.update { it.copy(operation = OperationUi(kind)) }
            var last = 0L
            val result = withContext(Dispatchers.IO) {
                try {
                    block(
                        { p ->
                            val now = System.currentTimeMillis()
                            if (now - last >= PROGRESS_INTERVAL_MS) {
                                last = now
                                _state.update { it.copy(operation = OperationUi(kind, p)) }
                            }
                        },
                        { cancelFlag },
                    )
                } catch (e: OperationCancelled) {
                    null
                } catch (e: IOException) {
                    OpResult(0, listOf(""))
                }
            }
            _state.update { it.copy(operation = null) }
            notice(
                when {
                    result == null -> FilesNotice(FilesNoticeKind.CANCELLED, kind)
                    result.ok -> FilesNotice(FilesNoticeKind.DONE, kind, result.done, result.skipped)
                    else -> FilesNotice(FilesNoticeKind.PARTLY_FAILED, kind, result.done, result.failed.size, result.failed.take(3).joinToString(", "))
                },
            )
            if (_state.value.trashOpen) loadTrash()
            reload()
        }
    }

    /** Verschiebt die Auswahl in den Papierkorb ([permanent] `false`) oder löscht endgültig. */
    fun deleteSelection(permanent: Boolean) {
        val s = _state.value
        val files = s.selection.map(::File)
        if (files.isEmpty()) return
        val roots = s.volumes.map { File(it.path) }
        clearSelection()
        if (permanent) {
            runOperation(OpKind.DELETE) { _, cancelled -> FileOps.delete(files, cancelled) }
        } else {
            runOperation(OpKind.TRASH) { progress, cancelled -> FileTrash.trash(files, roots, System.currentTimeMillis(), progress, cancelled) }
        }
    }

    fun rename(path: String, newName: String) {
        when (FilePaths.checkName(newName)) {
            NameCheck.OK -> Unit
            else -> { notice(FilesNotice(FilesNoticeKind.NAME_INVALID)); return }
        }
        viewModelScope.launch {
            val error = withContext(Dispatchers.IO) {
                try {
                    FileOps.rename(File(path), newName)
                    null
                } catch (e: IOException) {
                    if (e.message?.contains("Existiert") == true) FilesNoticeKind.NAME_EXISTS else FilesNoticeKind.RENAME_FAILED
                }
            }
            if (error != null) notice(FilesNotice(error))
            clearSelection()
            reload()
        }
    }

    fun createFolder(name: String) = create(name, folder = true)
    fun createFile(name: String) = create(name, folder = false)

    private fun create(name: String, folder: Boolean) {
        if (FilePaths.checkName(name) != NameCheck.OK) {
            notice(FilesNotice(FilesNoticeKind.NAME_INVALID))
            return
        }
        val parent = File(_state.value.path)
        viewModelScope.launch {
            val error = withContext(Dispatchers.IO) {
                try {
                    if (folder) FileOps.createFolder(parent, name) else FileOps.createEmptyFile(parent, name)
                    null
                } catch (e: IOException) {
                    if (e.message?.contains("Existiert") == true) FilesNoticeKind.NAME_EXISTS else FilesNoticeKind.CREATE_FAILED
                }
            }
            if (error != null) notice(FilesNotice(error))
            reload()
        }
    }

    // endregion

    // region ZIP ---------------------------------------------------------------------------------

    /** Packt die Auswahl in `name`.zip im aktuellen Ordner. */
    fun zipSelection(name: String) {
        val s = _state.value
        val base = name.trim().removeSuffix(".zip").removeSuffix(".ZIP")
        if (FilePaths.checkName("$base.zip") != NameCheck.OK) {
            notice(FilesNotice(FilesNoticeKind.NAME_INVALID))
            return
        }
        val dir = File(s.path)
        val sources = s.selection.map(::File)
        if (sources.isEmpty()) return
        clearSelection()
        runOperation(OpKind.ZIP) { progress, cancelled ->
            val target = File(dir, FilePaths.uniqueName(dir, "$base.zip"))
            try {
                val count = FileZip.create(sources, target, progress, cancelled)
                OpResult(count, emptyList())
            } catch (e: OperationCancelled) {
                throw e
            } catch (e: IOException) {
                OpResult(0, listOf(target.name))
            }
        }
    }

    /** Entpackt [zip] in einen neuen Ordner im aktuellen Ordner. */
    fun unzip(zip: File) {
        val dir = File(_state.value.path)
        if (opJob?.isActive == true) return
        cancelFlag = false
        opJob = viewModelScope.launch {
            _state.update { it.copy(operation = OperationUi(OpKind.UNZIP)) }
            var last = 0L
            val outcome = withContext(Dispatchers.IO) {
                try {
                    val result = FileZip.extract(zip, dir, progress = { p ->
                        val now = System.currentTimeMillis()
                        if (now - last >= PROGRESS_INTERVAL_MS) {
                            last = now
                            _state.update { it.copy(operation = OperationUi(OpKind.UNZIP, p)) }
                        }
                    }, cancelled = { cancelFlag })
                    FilesNotice(FilesNoticeKind.DONE, OpKind.UNZIP, result.files, result.skipped)
                } catch (e: OperationCancelled) {
                    FilesNotice(FilesNoticeKind.CANCELLED, OpKind.UNZIP)
                } catch (e: ZipEncryptedException) {
                    FilesNotice(FilesNoticeKind.ZIP_ENCRYPTED)
                } catch (e: ZipLimitException) {
                    FilesNotice(FilesNoticeKind.ZIP_LIMIT)
                } catch (e: IOException) {
                    FilesNotice(FilesNoticeKind.ZIP_FAILED)
                }
            }
            _state.update { it.copy(operation = null) }
            notice(outcome)
            reload()
        }
    }

    // endregion

    // region Suche -------------------------------------------------------------------------------

    fun openSearch() = _state.update { it.copy(searchQuery = "", searchResults = emptyList(), selection = emptySet()) }

    fun closeSearch() {
        searchJob?.cancel()
        _state.update { it.copy(searchQuery = null, searchResults = emptyList(), searching = false) }
    }

    fun search(query: String) {
        _state.update { it.copy(searchQuery = query) }
        searchJob?.cancel()
        if (query.isBlank()) {
            _state.update { it.copy(searchResults = emptyList(), searching = false) }
            return
        }
        val s = _state.value
        searchJob = viewModelScope.launch {
            _state.update { it.copy(searching = true) }
            val job = coroutineContext[Job]
            val found = withContext(Dispatchers.IO) {
                try {
                    FileOps.search(File(s.path), query, s.showHidden, SEARCH_LIMIT) { job?.isActive == false }
                } catch (e: OperationCancelled) {
                    emptyList()
                }
            }
            _state.update {
                it.copy(
                    searching = false,
                    searchResults = FileSorter.sort(found.map(::toEntry), it.sort),
                    notice = if (found.size >= SEARCH_LIMIT) FilesNotice(FilesNoticeKind.SEARCH_LIMIT, count = SEARCH_LIMIT) else it.notice,
                )
            }
        }
    }

    // endregion

    // region Eigenschaften -----------------------------------------------------------------------

    fun showProperties(entry: FileEntry) {
        propertiesJob?.cancel()
        _state.update { it.copy(properties = PropertiesUi(entry)) }
        propertiesJob = viewModelScope.launch {
            val job = coroutineContext[Job]
            val size = withContext(Dispatchers.IO) {
                try {
                    FileOps.treeSize(entry.file) { job?.isActive == false }
                } catch (e: OperationCancelled) {
                    null
                }
            }
            _state.update { s -> s.properties?.takeIf { it.entry.path == entry.path }?.let { s.copy(properties = it.copy(size = size, loading = false)) } ?: s }
        }
    }

    fun closeProperties() {
        propertiesJob?.cancel()
        _state.update { it.copy(properties = null) }
    }

    // endregion

    // region Papierkorb --------------------------------------------------------------------------

    fun openTrash() {
        _state.update { it.copy(trashOpen = true, selection = emptySet(), searchQuery = null) }
        loadTrash()
    }

    fun closeTrash() = _state.update { it.copy(trashOpen = false, trashItems = emptyList()) }

    private fun loadTrash() {
        val roots = _state.value.volumes.map { File(it.path) }
        viewModelScope.launch {
            val items = withContext(Dispatchers.IO) { FileTrash.list(roots) }
            _state.update { it.copy(trashItems = items) }
        }
    }

    fun restore(item: TrashItem) {
        runOperation(OpKind.RESTORE) { progress, cancelled ->
            try {
                FileTrash.restore(item, progress, cancelled)
                OpResult(1, emptyList())
            } catch (e: OperationCancelled) {
                throw e
            } catch (e: IOException) {
                OpResult(0, listOf(item.displayName))
            }
        }
    }

    fun purge(item: TrashItem) {
        runOperation(OpKind.DELETE) { _, _ -> if (FileTrash.purge(item)) OpResult(1, emptyList()) else OpResult(0, listOf(item.displayName)) }
    }

    fun emptyTrash() {
        val roots = _state.value.volumes.map { File(it.path) }
        runOperation(OpKind.EMPTY_TRASH) { _, _ -> FileTrash.empty(roots) }
    }

    // endregion

    /** Hinweise der Oberfläche, die keinen Vorgang betreffen (z. B. keine App zum Öffnen gefunden). */
    fun report(kind: FilesNoticeKind) = notice(FilesNotice(kind))

    override fun onCleared() {
        cancelFlag = true
        super.onCleared()
    }

    private companion object {
        const val PROGRESS_INTERVAL_MS = 120L
        const val SEARCH_LIMIT = 500
    }
}
