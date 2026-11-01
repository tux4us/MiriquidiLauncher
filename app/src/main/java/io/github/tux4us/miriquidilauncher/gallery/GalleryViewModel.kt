// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.gallery

import android.app.Application
import android.content.IntentSender
import android.os.Build
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.tux4us.miriquidilauncher.files.FilePaths
import io.github.tux4us.miriquidilauncher.files.FileProgress
import io.github.tux4us.miriquidilauncher.files.NameCheck
import io.github.tux4us.miriquidilauncher.files.OpKind
import io.github.tux4us.miriquidilauncher.files.OpResult
import io.github.tux4us.miriquidilauncher.files.OperationUi
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
    NEEDS_ANDROID_11,
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
    /** Papierkorb, Löschen, Umbenennen und Verschieben gehen nur ab Android 11 (Systemdialoge der Mediathek). */
    val canManage: Boolean = false,
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
    val trashItems: List<MediaEntry> = emptyList(),
    val trashSelection: Set<String> = emptySet(),
    val operation: OperationUi? = null,
    val notice: GalleryNotice? = null,
)

/**
 * Zustand und Aktionen der Galerie. Gelesen wird über die Mediathek (MediaStore). Papierkorb, Löschen, Umbenennen und
 * Verschieben laufen über die Systemdialoge der Mediathek (ab Android 11): Die App stellt die Anfrage, das System fragt
 * den Nutzer, danach führt die App die Änderung aus. Kopien legt die App selbst an. Dafür ist kein Zugriff auf alle
 * Dateien nötig.
 */
class GalleryViewModel(application: Application) : AndroidViewModel(application) {
    private val store = GalleryStore(application)
    private val _state = MutableStateFlow(GalleryUiState())
    val state: StateFlow<GalleryUiState> = _state.asStateFlow()

    private var settingsLoaded = false

    /** Die gespeicherten Einstellungen wurden ersetzt (Backup-Import): Sie werden beim nächsten Öffnen der Seite neu gelesen. */
    fun invalidateSettings() {
        settingsLoaded = false
    }
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
        _state.update { it.copy(mediaAccess = access, canManage = GalleryRepository.canManage) }
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

    private fun notice(n: GalleryNotice?) = _state.update { it.copy(notice = n) }

    fun consumeNotice() = notice(null)

    fun reportNoApp() = notice(GalleryNotice(GalleryNoticeKind.NO_APP))

    fun cancelOperation() {
        cancelFlag = true
    }

    // Anfrage an das System, die die Oberfläche startet (Systemdialog „Erlauben?“).
    private val _consentRequest = MutableStateFlow<IntentSender?>(null)
    val consentRequest: StateFlow<IntentSender?> = _consentRequest.asStateFlow()
    private var afterConsent: (suspend () -> Unit)? = null

    private fun askConsent(sender: IntentSender, onGranted: suspend () -> Unit) {
        afterConsent = onGranted
        _consentRequest.value = sender
    }

    /** Die Oberfläche hat den Systemdialog gestartet. */
    fun consentLaunched() {
        _consentRequest.value = null
    }

    /** Ergebnis des Systemdialogs: bei Zustimmung folgt die geplante Änderung. */
    fun onConsentResult(granted: Boolean) {
        val action = afterConsent
        afterConsent = null
        if (!granted || action == null) {
            notice(GalleryNotice(GalleryNoticeKind.CANCELLED))
            return
        }
        viewModelScope.launch { action() }
    }

    private fun unsupported() = notice(GalleryNotice(GalleryNoticeKind.NEEDS_ANDROID_11))

    /** Nach erledigter Änderung: Meldung zeigen, Auswahl leeren, Mediathek neu lesen. */
    private fun finished(kind: OpKind?, done: Int, failed: Int = 0) {
        val n = if (failed == 0) GalleryNotice(GalleryNoticeKind.DONE, kind, done) else GalleryNotice(GalleryNoticeKind.PARTLY_FAILED, kind, done, failed)
        _state.update { it.copy(notice = n, selection = emptySet(), trashSelection = emptySet()) }
        reload()
    }

    /** Verschiebt in den Papierkorb der Mediathek (Wiederherstellung innerhalb von 30 Tagen möglich). */
    fun trash(entries: List<MediaEntry>) {
        if (entries.isEmpty()) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) { unsupported(); return }
        val keys = entries.map { it.key }.toSet()
        _state.update { s -> if (s.viewerIndex != null && s.visible.getOrNull(s.viewerIndex)?.key in keys) s.copy(info = null) else s }
        val sender = try {
            GalleryRepository.trashRequest(getApplication(), entries, true)
        } catch (e: RuntimeException) {
            notice(GalleryNotice(GalleryNoticeKind.PARTLY_FAILED, OpKind.TRASH, 0, entries.size))
            return
        }
        askConsent(sender) { finished(OpKind.TRASH, entries.size) }
    }

    fun moveTo(entries: List<MediaEntry>, albumDir: String) = transfer(entries, GalleryLogic.relativeDir(albumDir, storageRoot()), move = true)

    fun copyTo(entries: List<MediaEntry>, albumDir: String) = transfer(entries, GalleryLogic.relativeDir(albumDir, storageRoot()), move = false)

    private fun storageRoot(): String = Environment.getExternalStorageDirectory().absolutePath

    private fun transfer(entries: List<MediaEntry>, albumRelative: String?, move: Boolean) {
        if (entries.isEmpty()) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) { unsupported(); return }
        // Ziele gibt es nur im internen Speicher; Wechselmedien und unbekannte Ordner lässt die Mediathek nicht zu.
        if (albumRelative == null) {
            notice(GalleryNotice(GalleryNoticeKind.NO_PATH))
            return
        }
        if (move) moveEntries(entries, albumRelative) else copyEntries(entries, albumRelative)
    }

    private fun moveEntries(entries: List<MediaEntry>, albumRelative: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val sender = try {
            GalleryRepository.writeRequest(getApplication(), entries)
        } catch (e: RuntimeException) {
            notice(GalleryNotice(GalleryNoticeKind.PARTLY_FAILED, OpKind.MOVE, 0, entries.size))
            return
        }
        askConsent(sender) {
            val root = storageRoot()
            val moved = HashMap<String, String>()
            val result = withContext(Dispatchers.IO) {
                var ok = 0
                for (entry in entries) {
                    val rel = GalleryLogic.targetRelativePath(albumRelative, entry.isVideo)
                    if (GalleryRepository.moveToFolder(getApplication(), entry, rel)) {
                        ok++
                        if (entry.path.isNotEmpty()) moved[entry.path] = "$root/$rel${entry.name}"
                    }
                }
                ok
            }
            carryFavorites(moved)
            finished(OpKind.MOVE, result, entries.size - result)
        }
    }

    private fun copyEntries(entries: List<MediaEntry>, albumRelative: String) {
        if (opJob?.isActive == true) return
        cancelFlag = false
        val total = entries.sumOf { it.sizeBytes }
        _state.update { it.copy(operation = OperationUi(OpKind.COPY)) }
        opJob = viewModelScope.launch {
            var done = 0L
            var reported = 0L
            val result = withContext(Dispatchers.IO) {
                var ok = 0
                val failed = ArrayList<String>()
                for (entry in entries) {
                    if (cancelFlag) break
                    val rel = GalleryLogic.targetRelativePath(albumRelative, entry.isVideo)
                    try {
                        _state.update { it.copy(operation = OperationUi(OpKind.COPY, FileProgress(done, total, entry.name))) }
                        GalleryRepository.copyToFolder(
                            getApplication(), entry, rel,
                            onBytes = { n ->
                                done += n
                                // Die Anzeige wird höchstens alle 256 KiB aktualisiert.
                                if (done - reported >= 256 * 1024) {
                                    reported = done
                                    _state.update { it.copy(operation = OperationUi(OpKind.COPY, FileProgress(done, total, entry.name))) }
                                }
                            },
                            cancelled = { cancelFlag },
                        )
                        ok++
                    } catch (e: IOException) {
                        if (!cancelFlag) failed.add(entry.name)
                    }
                }
                OpResult(ok, failed)
            }
            val n = when {
                cancelFlag -> GalleryNotice(GalleryNoticeKind.CANCELLED, OpKind.COPY)
                result.failed.isEmpty() -> GalleryNotice(GalleryNoticeKind.DONE, OpKind.COPY, result.done)
                else -> GalleryNotice(GalleryNoticeKind.PARTLY_FAILED, OpKind.COPY, result.done, result.failed.size)
            }
            _state.update { it.copy(operation = null, notice = n, selection = emptySet()) }
            reload()
        }
    }

    /** Legt ein Album (Ordner unter „Pictures“ bzw. „Movies“) an und verschiebt oder kopiert [entries] hinein. */
    fun createAlbumAndTransfer(name: String, entries: List<MediaEntry>, move: Boolean) {
        if (FilePaths.checkName(name) != NameCheck.OK) {
            notice(GalleryNotice(GalleryNoticeKind.NAME_INVALID))
            return
        }
        transfer(entries, "Pictures/${name.trim()}/", move)
    }

    fun rename(entry: MediaEntry, newName: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) { unsupported(); return }
        if (FilePaths.checkName(newName) != NameCheck.OK) {
            notice(GalleryNotice(GalleryNoticeKind.NAME_INVALID))
            return
        }
        val name = newName.trim()
        val sender = try {
            GalleryRepository.writeRequest(getApplication(), listOf(entry))
        } catch (e: RuntimeException) {
            notice(GalleryNotice(GalleryNoticeKind.RENAME_FAILED))
            return
        }
        askConsent(sender) {
            val ok = withContext(Dispatchers.IO) { GalleryRepository.rename(getApplication(), entry, name) }
            if (!ok) {
                notice(GalleryNotice(GalleryNoticeKind.RENAME_FAILED))
                return@askConsent
            }
            if (entry.path.isNotEmpty()) carryFavorites(mapOf(entry.path to entry.dir + "/" + name))
            notice(GalleryNotice(GalleryNoticeKind.DONE, null, 1))
            reload()
        }
    }

    /** Favoriten sind nach Dateipfad gespeichert; nach Umbenennen oder Verschieben wird der neue Pfad übernommen. */
    private suspend fun carryFavorites(moved: Map<String, String>) {
        val favorites = _state.value.favorites
        val affected = moved.filterKeys { it in favorites }
        if (affected.isEmpty()) return
        val next = favorites - affected.keys + affected.values
        _state.update { it.copy(favorites = next) }
        store.saveFavorites(next)
    }

    // endregion

    // region Papierkorb --------------------------------------------------------------------------

    fun loadTrash() {
        viewModelScope.launch {
            val items = withContext(Dispatchers.IO) {
                try {
                    GalleryRepository.load(getApplication(), trashed = true).sortedWith(compareByDescending<MediaEntry> { it.expiresMillis }.thenByDescending { it.id })
                } catch (e: SecurityException) {
                    emptyList()
                }
            }
            _state.update { s -> s.copy(trashItems = items, trashSelection = s.trashSelection.filter { k -> items.any { it.key == k } }.toSet()) }
        }
    }

    fun toggleTrashSelect(item: MediaEntry) = _state.update {
        it.copy(trashSelection = if (item.key in it.trashSelection) it.trashSelection - item.key else it.trashSelection + item.key)
    }

    private fun trashTargets(): List<MediaEntry> {
        val s = _state.value
        return if (s.trashSelection.isEmpty()) s.trashItems else s.trashItems.filter { it.key in s.trashSelection }
    }

    fun restoreTrash() {
        val items = trashTargets()
        if (items.isEmpty()) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) { unsupported(); return }
        val sender = try {
            GalleryRepository.trashRequest(getApplication(), items, false)
        } catch (e: RuntimeException) {
            notice(GalleryNotice(GalleryNoticeKind.PARTLY_FAILED, OpKind.RESTORE, 0, items.size))
            return
        }
        askConsent(sender) { finished(OpKind.RESTORE, items.size) }
    }

    fun purgeTrash() {
        val items = trashTargets()
        if (items.isEmpty()) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) { unsupported(); return }
        val sender = try {
            GalleryRepository.deleteRequest(getApplication(), items)
        } catch (e: RuntimeException) {
            notice(GalleryNotice(GalleryNoticeKind.PARTLY_FAILED, OpKind.DELETE, 0, items.size))
            return
        }
        askConsent(sender) { finished(OpKind.DELETE, items.size) }
    }

    // endregion

    override fun onCleared() {
        slideshowJob?.cancel()
        super.onCleared()
    }
}
