// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.gallery

import android.app.Application
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.tux4us.miriquidilauncher.files.FileOps
import io.github.tux4us.miriquidilauncher.files.FilePaths
import io.github.tux4us.miriquidilauncher.files.FileProgress
import io.github.tux4us.miriquidilauncher.files.FileTrash
import io.github.tux4us.miriquidilauncher.files.FileVolumes
import io.github.tux4us.miriquidilauncher.files.NameCheck
import io.github.tux4us.miriquidilauncher.files.OpKind
import io.github.tux4us.miriquidilauncher.files.OpResult
import io.github.tux4us.miriquidilauncher.files.OperationCancelled
import io.github.tux4us.miriquidilauncher.files.OperationUi
import io.github.tux4us.miriquidilauncher.files.TrashItem
import java.io.File
import java.io.IOException
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class GalleryTab { ALL, ALBUMS, FAVORITES, TRASH }

enum class GalleryNoticeKind {
    DONE,
    PARTLY_FAILED,
    CANCELLED,
    NEEDS_ALL_FILES,
    NAME_INVALID,
    NAME_EXISTS,
    RENAME_FAILED,
    NO_APP,
    NO_PATH,
}

/** Hinweis für die Oberfläche: [count] erledigte, [other] fehlgeschlagene Einträge. */
data class GalleryNotice(val kind: GalleryNoticeKind, val op: OpKind? = null, val count: Int = 0, val other: Int = 0)

data class GalleryInfo(val entry: MediaEntry, val exif: List<Pair<String, String>>)

data class GalleryUiState(
    /** `null`, solange der Zugriff noch nicht geprüft ist. */
    val mediaAccess: Boolean? = null,
    val allFilesAccess: Boolean = false,
    val loading: Boolean = false,
    val items: List<MediaEntry> = emptyList(),
    val tab: GalleryTab = GalleryTab.ALL,
    val filter: MediaFilter = MediaFilter.ALL,
    /** `null`, wenn nicht gesucht wird. */
    val searchQuery: String? = null,
    /** Geöffnetes Album, sonst `null`. */
    val albumOpen: Long? = null,
    val ascending: Boolean = false,
    val showHidden: Boolean = false,
    val favorites: Set<String> = emptySet(),
    val hiddenBuckets: Set<Long> = emptySet(),
    /** Aktuell angezeigte Medien (nach Reiter, Filter, Suche, Album). */
    val visible: List<MediaEntry> = emptyList(),
    val sections: List<DaySection> = emptyList(),
    val albums: List<Album> = emptyList(),
    val selection: Set<String> = emptySet(),
    /** Index in [visible]; `null`, wenn die Vollansicht geschlossen ist. */
    val viewerIndex: Int? = null,
    val slideshow: Boolean = false,
    val info: GalleryInfo? = null,
    val trashItems: List<TrashItem> = emptyList(),
    val trashSelection: Set<String> = emptySet(),
    val operation: OperationUi? = null,
    val notice: GalleryNotice? = null,
)

/**
 * Zustand und Aktionen der Galerie. Gelesen wird über die Mediathek; Löschen, Verschieben, Umbenennen und der
 * Papierkorb arbeiten auf den Dateien selbst und brauchen deshalb den Zugriff auf alle Dateien.
 */
class GalleryViewModel(application: Application) : AndroidViewModel(application) {
    private val store = GalleryStore(application)
    private val _state = MutableStateFlow(GalleryUiState())
    val state: StateFlow<GalleryUiState> = _state.asStateFlow()

    private var settingsLoaded = false
    private var loadJob: Job? = null
    private var opJob: Job? = null
    private var slideshowJob: Job? = null

    @Volatile
    private var cancelFlag = false

    // region Start und Laden ---------------------------------------------------------------------

    /** Die Seite wird sichtbar oder die App kommt zurück (Berechtigungsdialog, Systemeinstellungen). */
    fun onActive() {
        val app = getApplication<Application>()
        val access = GalleryRepository.hasMediaAccess(app)
        _state.update { it.copy(mediaAccess = access, allFilesAccess = FileVolumes.hasAllFilesAccess(app)) }
        if (!access) return
        viewModelScope.launch {
            if (!settingsLoaded) {
                val s = store.load()
                settingsLoaded = true
                _state.update { it.copy(favorites = s.favorites, hiddenBuckets = s.hiddenBuckets, ascending = s.ascending, showHidden = s.showHidden) }
            }
            reload()
        }
    }

    fun reload() {
        loadJob?.cancel()
        _state.update { it.copy(loading = true) }
        loadJob = viewModelScope.launch {
            val items = withContext(Dispatchers.IO) {
                try {
                    GalleryRepository.load(getApplication())
                } catch (e: SecurityException) {
                    emptyList()
                }
            }
            _state.update { it.copy(loading = false, items = items) }
            recompute()
            if (_state.value.tab == GalleryTab.TRASH) loadTrash()
        }
    }

    private fun recompute() {
        _state.update { s ->
            val visible = GalleryLogic.visible(
                items = s.items,
                filter = s.filter,
                query = s.searchQuery.orEmpty(),
                bucketId = if (s.tab == GalleryTab.ALBUMS) s.albumOpen else null,
                favoritesOnly = s.tab == GalleryTab.FAVORITES,
                favorites = s.favorites,
                hiddenBuckets = s.hiddenBuckets,
                showHidden = s.showHidden,
                ascending = s.ascending,
            )
            val keys = visible.map { it.key }.toSet()
            val viewer = s.viewerIndex?.let { if (visible.isEmpty()) null else it.coerceIn(0, visible.size - 1) }
            s.copy(
                visible = visible,
                sections = GalleryLogic.groupByDay(visible, ZoneId.systemDefault()),
                albums = GalleryLogic.albums(s.items, s.hiddenBuckets, s.showHidden),
                selection = s.selection.filter { it in keys }.toSet(),
                viewerIndex = viewer,
                slideshow = s.slideshow && viewer != null,
            )
        }
    }

    // endregion

    // region Reiter, Filter, Suche, Sortierung ---------------------------------------------------

    fun setTab(tab: GalleryTab) {
        closeViewer()
        _state.update { it.copy(tab = tab, albumOpen = null, selection = emptySet(), trashSelection = emptySet()) }
        recompute()
        if (tab == GalleryTab.TRASH) loadTrash()
    }

    fun setFilter(filter: MediaFilter) {
        _state.update { it.copy(filter = filter) }
        recompute()
    }

    fun openSearch() = _state.update { it.copy(searchQuery = it.searchQuery ?: "") }

    fun setQuery(query: String) {
        _state.update { it.copy(searchQuery = query) }
        recompute()
    }

    fun closeSearch() {
        _state.update { it.copy(searchQuery = null) }
        recompute()
    }

    fun toggleSort() {
        val value = !_state.value.ascending
        _state.update { it.copy(ascending = value) }
        recompute()
        viewModelScope.launch { store.saveAscending(value) }
    }

    fun toggleShowHidden() {
        val value = !_state.value.showHidden
        _state.update { it.copy(showHidden = value) }
        recompute()
        viewModelScope.launch { store.saveShowHidden(value) }
    }

    fun openAlbum(bucketId: Long) {
        _state.update { it.copy(albumOpen = bucketId, selection = emptySet()) }
        recompute()
    }

    fun toggleAlbumHidden(bucketId: Long) {
        val next = _state.value.hiddenBuckets.let { if (bucketId in it) it - bucketId else it + bucketId }
        _state.update { it.copy(hiddenBuckets = next) }
        recompute()
        viewModelScope.launch { store.saveHidden(next) }
    }

    /** Eine Ebene zurück: Vollansicht, Info, Auswahl, Suche, Album. `false`, wenn nichts zu schließen ist. */
    fun back(): Boolean {
        val s = _state.value
        return when {
            s.info != null -> { closeInfo(); true }
            s.viewerIndex != null -> { closeViewer(); true }
            s.selection.isNotEmpty() -> { clearSelection(); true }
            s.trashSelection.isNotEmpty() -> { _state.update { it.copy(trashSelection = emptySet()) }; true }
            s.searchQuery != null -> { closeSearch(); true }
            s.tab == GalleryTab.ALBUMS && s.albumOpen != null -> {
                _state.update { it.copy(albumOpen = null) }
                recompute()
                true
            }
            s.tab != GalleryTab.ALL -> { setTab(GalleryTab.ALL); true }
            else -> false
        }
    }

    // endregion

    // region Auswahl und Favoriten ---------------------------------------------------------------

    fun toggleSelect(key: String) = _state.update {
        it.copy(selection = if (key in it.selection) it.selection - key else it.selection + key)
    }

    fun selectAll() = _state.update { s -> s.copy(selection = s.visible.map { it.key }.toSet()) }

    fun clearSelection() = _state.update { it.copy(selection = emptySet()) }

    fun selectedEntries(): List<MediaEntry> {
        val s = _state.value
        return s.visible.filter { it.key in s.selection }
    }

    fun isFavorite(entry: MediaEntry): Boolean = entry.path.isNotEmpty() && entry.path in _state.value.favorites

    fun toggleFavorite(entry: MediaEntry) = setFavorite(listOf(entry), !isFavorite(entry))

    fun setFavorite(entries: List<MediaEntry>, value: Boolean) {
        val paths = entries.map { it.path }.filter { it.isNotEmpty() }
        if (paths.isEmpty()) {
            notice(GalleryNotice(GalleryNoticeKind.NO_PATH))
            return
        }
        val next = _state.value.favorites.let { if (value) it + paths else it - paths.toSet() }
        _state.update { it.copy(favorites = next) }
        recompute()
        viewModelScope.launch { store.saveFavorites(next) }
    }

    // endregion

    // region Vollansicht, Diashow, Info ----------------------------------------------------------

    fun openViewer(key: String) {
        val index = _state.value.visible.indexOfFirst { it.key == key }
        if (index >= 0) _state.update { it.copy(viewerIndex = index) }
    }

    fun setViewerIndex(index: Int) = _state.update {
        if (it.viewerIndex == null || index !in it.visible.indices) it else it.copy(viewerIndex = index)
    }

    fun closeViewer() {
        stopSlideshow()
        _state.update { it.copy(viewerIndex = null, info = null) }
    }

    fun currentEntry(): MediaEntry? = _state.value.let { s -> s.viewerIndex?.let { s.visible.getOrNull(it) } }

    /** Diashow: wechselt alle [intervalMs] zum nächsten Foto; Videos werden übersprungen, wenn Fotos vorhanden sind. */
    fun startSlideshow(intervalMs: Long = 4000L) {
        slideshowJob?.cancel()
        _state.update { it.copy(slideshow = true) }
        slideshowJob = viewModelScope.launch {
            while (_state.value.slideshow) {
                delay(intervalMs)
                val s = _state.value
                val current = s.viewerIndex ?: break
                var next = GalleryLogic.nextIndex(current, s.visible.size)
                var guard = s.visible.size
                while (next >= 0 && s.visible[next].isVideo && guard-- > 0 && s.visible.any { !it.isVideo }) {
                    next = GalleryLogic.nextIndex(next, s.visible.size)
                }
                if (next < 0) break
                _state.update { it.copy(viewerIndex = next) }
            }
        }
    }

    fun stopSlideshow() {
        slideshowJob?.cancel()
        slideshowJob = null
        _state.update { it.copy(slideshow = false) }
    }

    fun showInfo(entry: MediaEntry) {
        _state.update { it.copy(info = GalleryInfo(entry, emptyList())) }
        if (entry.isVideo) return
        viewModelScope.launch {
            val exif = withContext(Dispatchers.IO) { GalleryImages.exifSummary(entry.path) }
            _state.update { s -> if (s.info?.entry?.key == entry.key) s.copy(info = GalleryInfo(entry, exif)) else s }
        }
    }

    fun closeInfo() = _state.update { it.copy(info = null) }

    // endregion

    // region Dateivorgänge -----------------------------------------------------------------------

    private fun roots(): List<File> = FileVolumes.list(getApplication()).map { File(it.path) }

    private fun notice(n: GalleryNotice?) = _state.update { it.copy(notice = n) }

    fun consumeNotice() = notice(null)

    fun reportNoApp() = notice(GalleryNotice(GalleryNoticeKind.NO_APP))

    fun cancelOperation() {
        cancelFlag = true
    }

    private fun requireAllFiles(): Boolean {
        val granted = FileVolumes.hasAllFilesAccess(getApplication())
        _state.update { it.copy(allFilesAccess = granted) }
        if (!granted) notice(GalleryNotice(GalleryNoticeKind.NEEDS_ALL_FILES))
        return granted
    }

    /** Führt [block] im Hintergrund aus, meldet Fortschritt, aktualisiert die Mediathek für [touched] und liest neu. */
    private fun run(kind: OpKind, touched: List<String>, block: (progress: (FileProgress) -> Unit, cancelled: () -> Boolean, created: (File) -> Unit) -> OpResult) {
        if (opJob?.isActive == true) return
        cancelFlag = false
        val created = ArrayList<String>()
        _state.update { it.copy(operation = OperationUi(kind)) }
        opJob = viewModelScope.launch {
            val outcome = try {
                withContext(Dispatchers.IO) {
                    block(
                        { p -> _state.update { s -> s.copy(operation = OperationUi(kind, p)) } },
                        { cancelFlag },
                        { f -> synchronized(created) { created.add(f.absolutePath) } },
                    )
                }
            } catch (e: OperationCancelled) {
                null
            } catch (e: IOException) {
                OpResult(0, listOf(e.message.orEmpty()))
            }
            withContext(Dispatchers.IO) {
                GalleryRepository.scan(getApplication(), touched + synchronized(created) { created.toList() })
            }
            val n = when {
                outcome == null -> GalleryNotice(GalleryNoticeKind.CANCELLED, kind)
                outcome.failed.isEmpty() -> GalleryNotice(GalleryNoticeKind.DONE, kind, outcome.done)
                else -> GalleryNotice(GalleryNoticeKind.PARTLY_FAILED, kind, outcome.done, outcome.failed.size)
            }
            _state.update { it.copy(operation = null, notice = n, selection = emptySet()) }
            reload()
        }
    }

    /** Verschiebt in den Papierkorb (Wiederherstellung innerhalb von 30 Tagen möglich). */
    fun trash(entries: List<MediaEntry>) {
        if (entries.isEmpty() || !requireAllFiles()) return
        val files = entries.filter { it.path.isNotEmpty() }.map { File(it.path) }
        if (files.isEmpty()) {
            notice(GalleryNotice(GalleryNoticeKind.NO_PATH))
            return
        }
        val roots = roots()
        val keys = entries.map { it.key }.toSet()
        _state.update { s -> if (s.viewerIndex != null && s.visible.getOrNull(s.viewerIndex)?.key in keys) s.copy(info = null) else s }
        run(OpKind.TRASH, files.map { it.absolutePath }) { progress, cancelled, _ ->
            FileTrash.trash(files, roots, System.currentTimeMillis(), progress, cancelled)
        }
    }

    fun moveTo(entries: List<MediaEntry>, albumDir: String) = transfer(entries, albumDir, move = true)

    fun copyTo(entries: List<MediaEntry>, albumDir: String) = transfer(entries, albumDir, move = false)

    private fun transfer(entries: List<MediaEntry>, targetDir: String, move: Boolean) {
        if (entries.isEmpty() || !requireAllFiles()) return
        val files = entries.filter { it.path.isNotEmpty() }.map { File(it.path) }
        val target = File(targetDir)
        if (files.isEmpty() || !target.isDirectory) {
            notice(GalleryNotice(GalleryNoticeKind.NO_PATH))
            return
        }
        val kind = if (move) OpKind.MOVE else OpKind.COPY
        run(kind, if (move) files.map { it.absolutePath } else emptyList()) { progress, cancelled, created ->
            if (move) FileOps.move(files, target, progress, cancelled, created) else FileOps.copy(files, target, progress, cancelled, created)
        }
    }

    /** Legt ein Album (Ordner unter „Pictures“ des internen Speichers) an und verschiebt oder kopiert [entries] hinein. */
    fun createAlbumAndTransfer(name: String, entries: List<MediaEntry>, move: Boolean) {
        if (!requireAllFiles()) return
        val check = FilePaths.checkName(name)
        if (check != NameCheck.OK) {
            notice(GalleryNotice(GalleryNoticeKind.NAME_INVALID))
            return
        }
        val pictures = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        val dir = File(pictures, name.trim())
        try {
            if (!dir.isDirectory) FileOps.createFolder(pictures, name.trim())
        } catch (e: IOException) {
            notice(GalleryNotice(GalleryNoticeKind.NAME_EXISTS))
            return
        }
        transfer(entries, dir.absolutePath, move)
    }

    fun rename(entry: MediaEntry, newName: String) {
        if (!requireAllFiles()) return
        if (entry.path.isEmpty()) {
            notice(GalleryNotice(GalleryNoticeKind.NO_PATH))
            return
        }
        if (FilePaths.checkName(newName) != NameCheck.OK) {
            notice(GalleryNotice(GalleryNoticeKind.NAME_INVALID))
            return
        }
        viewModelScope.launch {
            val old = File(entry.path)
            val result = withContext(Dispatchers.IO) {
                try {
                    FileOps.rename(old, newName)
                } catch (e: IOException) {
                    null
                } catch (e: IllegalArgumentException) {
                    null
                }
            }
            if (result == null) {
                notice(GalleryNotice(GalleryNoticeKind.RENAME_FAILED))
                return@launch
            }
            // Favoritenpfad mitführen, damit die Markierung erhalten bleibt.
            val favorites = _state.value.favorites
            if (entry.path in favorites) {
                val next = favorites - entry.path + result.absolutePath
                _state.update { it.copy(favorites = next) }
                store.saveFavorites(next)
            }
            withContext(Dispatchers.IO) { GalleryRepository.scan(getApplication(), listOf(old.absolutePath, result.absolutePath)) }
            notice(GalleryNotice(GalleryNoticeKind.DONE, null, 1))
            reload()
        }
    }

    // endregion

    // region Papierkorb --------------------------------------------------------------------------

    fun loadTrash() {
        viewModelScope.launch {
            val roots = roots()
            val items = withContext(Dispatchers.IO) {
                if (FileVolumes.hasAllFilesAccess(getApplication())) GalleryLogic.trashedMedia(FileTrash.list(roots)).sortedByDescending { it.deletedAtMillis } else emptyList()
            }
            _state.update { it.copy(trashItems = items, trashSelection = it.trashSelection.filter { p -> items.any { t -> t.item.absolutePath == p } }.toSet()) }
        }
    }

    fun toggleTrashSelect(item: TrashItem) = _state.update {
        val key = item.item.absolutePath
        it.copy(trashSelection = if (key in it.trashSelection) it.trashSelection - key else it.trashSelection + key)
    }

    private fun trashTargets(): List<TrashItem> {
        val s = _state.value
        return if (s.trashSelection.isEmpty()) s.trashItems else s.trashItems.filter { it.item.absolutePath in s.trashSelection }
    }

    fun restoreTrash() {
        val items = trashTargets()
        if (items.isEmpty() || !requireAllFiles()) return
        run(OpKind.RESTORE, items.map { it.originalPath }) { progress, cancelled, created ->
            var done = 0
            val failed = ArrayList<String>()
            for (item in items) {
                if (cancelled()) throw OperationCancelled()
                try {
                    created(FileTrash.restore(item, progress, cancelled))
                    done++
                } catch (e: IOException) {
                    failed.add(item.displayName)
                }
            }
            OpResult(done, failed)
        }
        _state.update { it.copy(trashSelection = emptySet()) }
    }

    fun purgeTrash() {
        val items = trashTargets()
        if (items.isEmpty() || !requireAllFiles()) return
        run(OpKind.DELETE, emptyList()) { _, cancelled, _ ->
            var done = 0
            val failed = ArrayList<String>()
            for (item in items) {
                if (cancelled()) throw OperationCancelled()
                if (FileTrash.purge(item)) done++ else failed.add(item.displayName)
            }
            OpResult(done, failed)
        }
        _state.update { it.copy(trashSelection = emptySet()) }
    }

    // endregion

    override fun onCleared() {
        slideshowJob?.cancel()
        super.onCleared()
    }
}
