// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.notes

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.tux4us.miriquidilauncher.feed.FeedStore
import io.github.tux4us.miriquidilauncher.feed.NewsApi
import io.github.tux4us.miriquidilauncher.feed.ServerUrl
import io.github.tux4us.miriquidilauncher.files.TrashItem
import java.io.IOException
import java.security.GeneralSecurityException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class NotesTab { ALL, FAVORITES, TRASH }

enum class NotesNoticeKind {
    SYNC_DONE,
    SYNC_AUTH,
    SYNC_NOT_FOUND,
    SYNC_NETWORK,
    LOGIN_INVALID_URL,
    LOGIN_NOT_HTTPS,
    LOGIN_AUTH,
    LOGIN_NOT_FOUND,
    LOGIN_NETWORK,
    LOGIN_KEYSTORE,
    FEED_ACCOUNT_MISSING,
    SAVE_FAILED,
    MOVE_FAILED,
    TRASHED,
    RESTORED,
    PURGED,
    PARTLY_FAILED,
    NO_APP,
}

/** Hinweis für die Oberfläche: [count] erledigte, [other] fehlgeschlagene Einträge, [summary] beim Abgleich. */
data class NotesNotice(val kind: NotesNoticeKind, val count: Int = 0, val other: Int = 0, val summary: SyncSummary? = null)

data class EditorUi(
    /** `null`, solange die neue Notiz noch nicht gespeichert wurde. */
    val path: String?,
    val content: String,
    val category: String,
    val preview: Boolean,
    val dirty: Boolean = false,
)

data class NotesAccount(val server: String, val user: String)

data class NotesUiState(
    /** `null`, solange der Zugriff noch nicht geprüft ist. */
    val access: Boolean? = null,
    val loading: Boolean = false,
    val notes: List<Note> = emptyList(),
    val visible: List<Note> = emptyList(),
    val categories: List<Pair<String, Int>> = emptyList(),
    val tab: NotesTab = NotesTab.ALL,
    val category: String? = null,
    /** `null`, wenn nicht gesucht wird. */
    val query: String? = null,
    val sort: NoteSort = NoteSort.MODIFIED,
    val ascending: Boolean = false,
    val selection: Set<String> = emptySet(),
    val editor: EditorUi? = null,
    val trash: List<TrashItem> = emptyList(),
    val trashSelection: Set<String> = emptySet(),
    val account: NotesAccount? = null,
    val syncing: Boolean = false,
    val lastSyncMillis: Long = 0L,
    val notice: NotesNotice? = null,
)

/**
 * Zustand und Aktionen der Notizen-Scope. Der Editor speichert 1,2 s nach der letzten Eingabe zwischen und beim
 * Schließen endgültig (mit Umbenennen nach dem Titel). Mit Nextcloud-Zugang wird beim Öffnen der Seite (höchstens alle
 * zwei Minuten), nach dem Schließen des Editors und auf Wunsch abgeglichen.
 */
class NotesViewModel(application: Application) : AndroidViewModel(application) {
    private val store = NotesStore(application)
    private val repository = NotesRepository(application, store)
    private val _state = MutableStateFlow(NotesUiState())
    val state: StateFlow<NotesUiState> = _state.asStateFlow()

    private var settingsLoaded = false

    /** Sortierung und Notizen wurden ersetzt (Backup-Import): Sie werden beim nächsten Öffnen der Seite neu gelesen. */
    fun invalidateSettings() {
        settingsLoaded = false
    }
    private var credentials: NotesCredentials? = null
    private var autosaveJob: Job? = null
    private var syncJob: Job? = null
    private val editorLock = Mutex()

    // region Start und Laden ---------------------------------------------------------------------

    fun onActive() {
        val access = repository.hasAccess()
        _state.update { it.copy(access = access) }
        if (!access) return
        viewModelScope.launch {
            if (!settingsLoaded) {
                val s = store.settings()
                credentials = store.loadCredentials()
                settingsLoaded = true
                _state.update {
                    it.copy(
                        sort = s.sort,
                        ascending = s.ascending,
                        lastSyncMillis = s.lastSyncMillis,
                        account = credentials?.let { c -> NotesAccount(c.baseUrl, c.user) },
                    )
                }
            }
            reloadNow()
            val last = _state.value.lastSyncMillis
            if (credentials != null && System.currentTimeMillis() - last > AUTO_SYNC_MS) sync()
        }
    }

    fun reload() {
        viewModelScope.launch { reloadNow() }
    }

    private suspend fun reloadNow() {
        _state.update { it.copy(loading = true) }
        val notes = try {
            repository.load()
        } catch (e: IOException) {
            emptyList()
        }
        _state.update { it.copy(loading = false, notes = notes) }
        recompute()
        if (_state.value.tab == NotesTab.TRASH) loadTrashNow()
    }

    private fun recompute() {
        _state.update { s ->
            val visible = NotesLogic.visible(
                notes = s.notes,
                query = s.query.orEmpty(),
                category = s.category,
                favoritesOnly = s.tab == NotesTab.FAVORITES,
                sort = s.sort,
                ascending = s.ascending,
            )
            val paths = s.notes.mapTo(HashSet()) { it.path }
            s.copy(visible = visible, categories = NotesLogic.categories(s.notes), selection = s.selection.filter { it in paths }.toSet())
        }
    }

    // endregion

    // region Liste, Suche, Auswahl ---------------------------------------------------------------

    fun setTab(tab: NotesTab) {
        _state.update { it.copy(tab = tab, selection = emptySet(), trashSelection = emptySet()) }
        recompute()
        if (tab == NotesTab.TRASH) viewModelScope.launch { loadTrashNow() }
    }

    fun setCategory(category: String?) {
        _state.update { it.copy(category = category) }
        recompute()
    }

    fun openSearch() = _state.update { it.copy(query = it.query ?: "") }

    fun setQuery(query: String) {
        _state.update { it.copy(query = query) }
        recompute()
    }

    fun closeSearch() {
        _state.update { it.copy(query = null) }
        recompute()
    }

    fun setSort(sort: NoteSort) {
        val ascending = if (sort == _state.value.sort) !_state.value.ascending else sort == NoteSort.TITLE
        _state.update { it.copy(sort = sort, ascending = ascending) }
        recompute()
        viewModelScope.launch { store.saveSort(sort, ascending) }
    }

    fun toggleSelect(path: String) = _state.update {
        it.copy(selection = if (path in it.selection) it.selection - path else it.selection + path)
    }

    fun clearSelection() = _state.update { it.copy(selection = emptySet()) }

    fun toggleFavorite(path: String) {
        val value = _state.value.notes.firstOrNull { it.path == path }?.favorite?.not() ?: return
        setFavorites(listOf(path), value)
    }

    fun setFavorites(paths: List<String>, value: Boolean) {
        val set = paths.toSet()
        _state.update { s -> s.copy(notes = s.notes.map { if (it.path in set) it.copy(favorite = value) else it }) }
        recompute()
        viewModelScope.launch { paths.forEach { repository.setFavorite(it, value) } }
    }

    // endregion

    // region Editor ------------------------------------------------------------------------------

    fun newNote() = _state.update {
        it.copy(editor = EditorUi(path = null, content = "", category = it.category.orEmpty(), preview = false))
    }

    fun openNote(note: Note) = _state.update {
        it.copy(editor = EditorUi(path = note.path, content = note.content, category = note.category, preview = note.content.isNotBlank()))
    }

    fun setPreview(preview: Boolean) = _state.update { s -> s.copy(editor = s.editor?.copy(preview = preview)) }

    fun editContent(text: String) {
        if (text.length > NotesLogic.MAX_NOTE_CHARS) return
        _state.update { s -> s.copy(editor = s.editor?.copy(content = text, dirty = true)) }
        autosaveJob?.cancel()
        autosaveJob = viewModelScope.launch {
            delay(AUTOSAVE_MS)
            withContext(NonCancellable) { persist(rename = false) }
        }
    }

    /** Schaltet eine Aufgabe in der Vorschau um und speichert sofort. */
    fun toggleCheck(lineIndex: Int) {
        val editor = _state.value.editor ?: return
        val updated = NotesMarkdown.toggleCheck(editor.content, lineIndex)
        if (updated == editor.content) return
        _state.update { s -> s.copy(editor = s.editor?.copy(content = updated, dirty = true)) }
        viewModelScope.launch { persist(rename = false) }
    }

    /** Kategorie der geöffneten Notiz ändern (verschiebt die Datei, sobald sie existiert). */
    fun setEditorCategory(category: String) {
        val clean = NotesLogic.sanitizeCategory(category)
        val path = _state.value.editor?.path
        _state.update { s -> s.copy(editor = s.editor?.copy(category = clean)) }
        if (path == null) return
        viewModelScope.launch {
            editorLock.withLock {
                try {
                    val moved = repository.moveToCategory(_state.value.editor?.path ?: path, clean)
                    _state.update { s -> s.copy(editor = s.editor?.copy(path = moved)) }
                } catch (e: IOException) {
                    notice(NotesNotice(NotesNoticeKind.MOVE_FAILED))
                }
            }
            reloadNow()
        }
    }

    private suspend fun persist(rename: Boolean) {
        editorLock.withLock {
            val editor = _state.value.editor ?: return
            if (!editor.dirty && !rename) return
            if (editor.path == null && editor.content.isBlank()) return
            try {
                val saved = repository.save(editor.path, editor.content, editor.category, rename)
                _state.update { s ->
                    s.copy(editor = s.editor?.let { e -> e.copy(path = saved.path, dirty = e.content != saved.content) })
                }
            } catch (e: IOException) {
                notice(NotesNotice(NotesNoticeKind.SAVE_FAILED))
            }
        }
    }

    /** Schließt den Editor, speichert endgültig (mit Umbenennen) und gleicht bei Nextcloud-Zugang ab. */
    fun closeEditor() {
        autosaveJob?.cancel()
        val editor = _state.value.editor ?: return
        viewModelScope.launch {
            withContext(NonCancellable) { persist(rename = editor.dirty || editor.path != null) }
            _state.update { it.copy(editor = null) }
            reloadNow()
            if (credentials != null && editor.dirty) sync()
        }
    }

    /** Verschiebt die geöffnete Notiz in den Papierkorb. */
    fun trashEditor() {
        autosaveJob?.cancel()
        val path = _state.value.editor?.path
        viewModelScope.launch {
            if (path != null) {
                editorLock.withLock { repository.trash(listOf(_state.value.editor?.path ?: path)) }
                notice(NotesNotice(NotesNoticeKind.TRASHED, 1))
            }
            _state.update { it.copy(editor = null) }
            reloadNow()
            if (credentials != null) sync()
        }
    }

    // endregion

    // region Papierkorb --------------------------------------------------------------------------

    fun trashSelected() {
        val paths = _state.value.selection.toList()
        if (paths.isEmpty()) return
        viewModelScope.launch {
            val result = repository.trash(paths)
            _state.update { it.copy(selection = emptySet()) }
            notice(if (result.failed.isEmpty()) NotesNotice(NotesNoticeKind.TRASHED, result.done) else NotesNotice(NotesNoticeKind.PARTLY_FAILED, result.done, result.failed.size))
            reloadNow()
            if (credentials != null) sync()
        }
    }

    private suspend fun loadTrashNow() {
        val items = repository.listTrash()
        _state.update { s ->
            s.copy(trash = items, trashSelection = s.trashSelection.filter { p -> items.any { it.item.absolutePath == p } }.toSet())
        }
    }

    fun toggleTrashSelect(item: TrashItem) = _state.update {
        val key = item.item.absolutePath
        it.copy(trashSelection = if (key in it.trashSelection) it.trashSelection - key else it.trashSelection + key)
    }

    private fun trashTargets(): List<TrashItem> {
        val s = _state.value
        return if (s.trashSelection.isEmpty()) s.trash else s.trash.filter { it.item.absolutePath in s.trashSelection }
    }

    fun restoreTrash() {
        val items = trashTargets()
        if (items.isEmpty()) return
        viewModelScope.launch {
            val result = repository.restore(items)
            _state.update { it.copy(trashSelection = emptySet()) }
            notice(if (result.failed.isEmpty()) NotesNotice(NotesNoticeKind.RESTORED, result.done) else NotesNotice(NotesNoticeKind.PARTLY_FAILED, result.done, result.failed.size))
            reloadNow()
            if (credentials != null) sync()
        }
    }

    fun purgeTrash() {
        val items = trashTargets()
        if (items.isEmpty()) return
        viewModelScope.launch {
            val result = repository.purge(items)
            _state.update { it.copy(trashSelection = emptySet()) }
            notice(if (result.failed.isEmpty()) NotesNotice(NotesNoticeKind.PURGED, result.done) else NotesNotice(NotesNoticeKind.PARTLY_FAILED, result.done, result.failed.size))
            loadTrashNow()
        }
    }

    // endregion

    // region Nextcloud ---------------------------------------------------------------------------

    fun login(serverInput: String, user: String, password: String) {
        val base = when (val parsed = NewsApi.parseServerUrl(serverInput)) {
            is ServerUrl.Ok -> parsed.baseUrl
            ServerUrl.NotHttps -> return notice(NotesNotice(NotesNoticeKind.LOGIN_NOT_HTTPS))
            else -> return notice(NotesNotice(NotesNoticeKind.LOGIN_INVALID_URL))
        }
        val name = user.trim()
        if (name.isEmpty() || password.isEmpty()) return notice(NotesNotice(NotesNoticeKind.LOGIN_AUTH))
        viewModelScope.launch {
            _state.update { it.copy(syncing = true) }
            val candidate = NotesCredentials(base, name, password)
            val failure = try {
                NotesRemote(base, name, password).check()
                null
            } catch (e: NotesHttpException) {
                if (e.code == 401 || e.code == 403) NotesNoticeKind.LOGIN_AUTH else NotesNoticeKind.LOGIN_NOT_FOUND
            } catch (e: IOException) {
                NotesNoticeKind.LOGIN_NETWORK
            }
            if (failure != null) {
                _state.update { it.copy(syncing = false) }
                return@launch notice(NotesNotice(failure))
            }
            try {
                store.saveCredentials(candidate)
            } catch (e: GeneralSecurityException) {
                _state.update { it.copy(syncing = false) }
                return@launch notice(NotesNotice(NotesNoticeKind.LOGIN_KEYSTORE))
            }
            credentials = candidate
            _state.update { it.copy(account = NotesAccount(base, name), syncing = false) }
            sync()
        }
    }

    /** Übernimmt Server, Benutzer und App-Passwort des FeedReaders. */
    fun loginWithFeedAccount() {
        viewModelScope.launch {
            val feed = FeedStore(getApplication()).loadCredentials()
            if (feed == null) notice(NotesNotice(NotesNoticeKind.FEED_ACCOUNT_MISSING)) else login(feed.baseUrl, feed.user, feed.password)
        }
    }

    /** Meldet ab: Zugangsdaten und Zuordnung werden gelöscht, die lokalen Notizen bleiben. */
    fun logout() {
        viewModelScope.launch {
            store.clearAccount()
            credentials = null
            _state.update { it.copy(account = null, lastSyncMillis = 0L) }
        }
    }

    fun sync() {
        val c = credentials ?: return
        if (syncJob?.isActive == true) return
        syncJob = viewModelScope.launch {
            _state.update { it.copy(syncing = true) }
            val n = try {
                val r = repository.sync(c)
                NotesNotice(NotesNoticeKind.SYNC_DONE, summary = r)
            } catch (e: NotesHttpException) {
                NotesNotice(if (e.code == 401 || e.code == 403) NotesNoticeKind.SYNC_AUTH else NotesNoticeKind.SYNC_NOT_FOUND)
            } catch (e: IOException) {
                NotesNotice(NotesNoticeKind.SYNC_NETWORK)
            }
            _state.update { it.copy(syncing = false, lastSyncMillis = store.settings().lastSyncMillis, notice = n) }
            reloadNow()
        }
    }

    // endregion

    fun notice(n: NotesNotice) = _state.update { it.copy(notice = n) }

    fun dismissNotice() = _state.update { it.copy(notice = null) }

    fun back(): Boolean {
        val s = _state.value
        return when {
            s.editor != null -> { closeEditor(); true }
            s.selection.isNotEmpty() || s.trashSelection.isNotEmpty() -> { _state.update { it.copy(selection = emptySet(), trashSelection = emptySet()) }; true }
            s.query != null -> { closeSearch(); true }
            s.category != null -> { setCategory(null); true }
            s.tab != NotesTab.ALL -> { setTab(NotesTab.ALL); true }
            else -> false
        }
    }

    private companion object {
        const val AUTOSAVE_MS = 1200L
        const val AUTO_SYNC_MS = 2 * 60 * 1000L
    }
}
