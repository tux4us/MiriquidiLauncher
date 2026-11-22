// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.chat

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.tux4us.miriquidilauncher.feed.FeedStore
import io.github.tux4us.miriquidilauncher.feed.NewsApi
import io.github.tux4us.miriquidilauncher.feed.ServerUrl
import io.github.tux4us.miriquidilauncher.notes.NotesStore
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock

enum class ChatNoticeKind {
    LOGIN_INVALID_URL,
    LOGIN_NOT_HTTPS,
    LOGIN_AUTH,
    LOGIN_NOT_FOUND,
    LOGIN_NETWORK,
    LOGIN_KEYSTORE,
    FEED_ACCOUNT_MISSING,
    NOTES_ACCOUNT_MISSING,
    AUTH_LOST,
    ROOM_GONE,
    LOAD_FAILED,
    SEND_TOO_LONG,
    SEND_EMPTY,
    READ_ONLY,
    ATTACH_TOO_LARGE,
    ATTACH_FAILED,
}

data class ChatNotice(val kind: ChatNoticeKind)

data class ChatAccount(val server: String, val user: String)

data class ChatUiState(
    /** `false`, bis die gespeicherten Zugangsdaten geprüft sind. */
    val ready: Boolean = false,
    val account: ChatAccount? = null,
    /** Benutzer-Id des Servers, um eigene Nachrichten zu erkennen. */
    val ownId: String = "",
    val busy: Boolean = false,
    val rooms: List<ChatRoom> = emptyList(),
    val query: String? = null,
    val roomsLoading: Boolean = false,
    /** Das geöffnete Gespräch oder `null` in der Raumliste. */
    val openRoom: ChatRoom? = null,
    val messages: List<ChatMessage> = emptyList(),
    val messagesLoading: Boolean = false,
    val loadingOlder: Boolean = false,
    val hasOlder: Boolean = true,
    val outbox: List<Outgoing> = emptyList(),
    val replyTo: ChatMessage? = null,
    /** Die letzte Anfrage ist gescheitert; die Anzeige stammt (teilweise) aus dem Zwischenspeicher. */
    val offline: Boolean = false,
    val notice: ChatNotice? = null,
)

/**
 * Zustand und Aktionen des Messenger-Scopes (Nextcloud Talk). Neue Nachrichten kommen per Long-Polling, solange die
 * Seite sichtbar ist ([setActive]); in der Raumliste wird alle 30 Sekunden aktualisiert. Im Hintergrund gibt es keine
 * Verbindung. Senden läuft unabhängig davon und zeigt den Status je Nachricht (wird gesendet, fehlgeschlagen mit erneutem Versuch).
 */
class ChatViewModel(application: Application) : AndroidViewModel(application) {
    private val store = ChatStore(application)
    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private var credentials: ChatCredentials? = null
    private var remote: ChatRemote? = null
    private var loaded = false
    private var loadingAccount = false
    private var active = false

    /** Der Server hat die Zugangsdaten abgelehnt: Bis zum nächsten Öffnen der Seite oder Anmelden wird nicht erneut gefragt. */
    private var authBroken = false
    private var loopJob: Job? = null
    private val cacheLock = Mutex()

    // region Start -------------------------------------------------------------------------------

    /** Die Seite ist sichtbar und die App im Vordergrund (`true`) oder nicht (`false`). */
    fun setActive(value: Boolean) {
        if (active == value) return
        active = value
        if (value) authBroken = false
        if (value && !loaded) loadAccount() else restartLoop()
    }

    private fun loadAccount() {
        if (loadingAccount) return
        loadingAccount = true
        cleanStaging()
        viewModelScope.launch {
            val saved = store.loadCredentials()
            loaded = true
            loadingAccount = false
            credentials = saved
            remote = saved?.let { ChatRemote(it.baseUrl, it.user, it.password) }
            val rooms = if (saved != null) ChatLogic.sortRooms(store.loadRooms()) else emptyList()
            _state.update {
                it.copy(
                    ready = true,
                    account = saved?.let { c -> ChatAccount(c.baseUrl, c.user) },
                    ownId = saved?.userId.orEmpty(),
                    rooms = rooms,
                )
            }
            restartLoop()
        }
    }

    // endregion

    // region Schleifen: Raumliste und Long-Polling ------------------------------------------------

    private fun restartLoop() {
        loopJob?.cancel()
        loopJob = null
        if (!active || remote == null) return
        val token = _state.value.openRoom?.token
        loopJob = viewModelScope.launch { if (token == null) roomsLoop() else pollLoop(token) }
    }

    private suspend fun roomsLoop() {
        var failures = 0
        while (currentCoroutineContext().isActive && !authBroken) {
            failures = if (refreshRoomsNow()) 0 else failures + 1
            delay(if (failures == 0) LIST_REFRESH_MS else ChatLogic.backoffMillis(failures).coerceAtLeast(LIST_REFRESH_MS))
        }
    }

    /** `true` bei Erfolg. */
    private suspend fun refreshRoomsNow(): Boolean {
        val r = remote ?: return false
        _state.update { it.copy(roomsLoading = true) }
        return try {
            val fresh = ChatLogic.sortRooms(r.rooms())
            _state.update { s ->
                s.copy(rooms = fresh, roomsLoading = false, offline = false, openRoom = s.openRoom?.let { o -> fresh.firstOrNull { it.token == o.token } ?: o })
            }
            cacheLock.withLock {
                store.saveRooms(fresh)
                store.prune(fresh.map { it.token }.toSet())
            }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: ChatHttpException) {
            _state.update { it.copy(roomsLoading = false, offline = true) }
            if (e.code == 401 || e.code == 403) authLost() else notice(ChatNoticeKind.LOAD_FAILED)
            false
        } catch (e: IOException) {
            _state.update { it.copy(roomsLoading = false, offline = true) }
            false
        }
    }

    private suspend fun pollLoop(token: String) {
        var failures = 0
        _state.update { it.copy(messagesLoading = true) }
        val first = loadLatest(token)
        _state.update { it.copy(messagesLoading = false) }
        if (first == LoadResult.GONE || first == LoadResult.AUTH) return
        if (first == LoadResult.FAILED) failures = 1
        while (currentCoroutineContext().isActive && !authBroken) {
            if (failures > 0) delay(ChatLogic.backoffMillis(failures))
            val r = remote ?: return
            val started = System.currentTimeMillis()
            try {
                val batch = r.messages(token, future = true, lastKnownId = ChatLogic.newestId(_state.value.messages), limit = ChatLogic.PAGE_SIZE)
                failures = 0
                _state.update { it.copy(offline = false) }
                if (batch.isNotEmpty()) applyMessages(token, batch)
                // Antwortet der Server sofort ohne Inhalt, nicht im Takt der Verbindung fragen.
                val elapsed = System.currentTimeMillis() - started
                if (batch.isEmpty() && elapsed < MIN_POLL_MS) delay(MIN_POLL_MS - elapsed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ChatHttpException) {
                when (e.code) {
                    401, 403 -> return authLost()
                    404 -> return roomGone()
                    else -> {
                        failures++
                        _state.update { it.copy(offline = true) }
                    }
                }
            } catch (e: IOException) {
                failures++
                _state.update { it.copy(offline = true) }
            }
        }
    }

    private enum class LoadResult { OK, FAILED, GONE, AUTH }

    /** Zeigt zuerst den Zwischenspeicher und holt dann die neuesten Nachrichten. */
    private suspend fun loadLatest(token: String): LoadResult {
        val r = remote ?: return LoadResult.FAILED
        val cached = store.loadMessages(token)
        if (_state.value.openRoom?.token == token && _state.value.messages.isEmpty() && cached.isNotEmpty()) {
            _state.update { it.copy(messages = cached) }
        }
        return try {
            val latest = r.messages(token, future = false, lastKnownId = 0L, limit = ChatLogic.PAGE_SIZE)
            val merged = ChatLogic.mergeLatest(_state.value.messages, latest, ChatLogic.PAGE_SIZE)
            _state.update { it.copy(messages = merged, offline = false, hasOlder = latest.size >= ChatLogic.PAGE_SIZE) }
            cacheLock.withLock { store.saveMessages(token, merged) }
            markRead(token)
            LoadResult.OK
        } catch (e: CancellationException) {
            throw e
        } catch (e: ChatHttpException) {
            _state.update { it.copy(offline = true) }
            when (e.code) {
                401, 403 -> { authLost(); LoadResult.AUTH }
                404 -> { roomGone(); LoadResult.GONE }
                else -> { notice(ChatNoticeKind.LOAD_FAILED); LoadResult.FAILED }
            }
        } catch (e: IOException) {
            _state.update { it.copy(offline = true) }
            LoadResult.FAILED
        }
    }

    private suspend fun applyMessages(token: String, incoming: List<ChatMessage>) {
        var merged: List<ChatMessage> = emptyList()
        _state.update { s ->
            if (s.openRoom?.token != token) return@update s
            merged = ChatLogic.merge(s.messages, incoming)
            val confirmed = ChatLogic.confirmedLocalIds(s.outbox, incoming)
            s.copy(messages = merged, outbox = s.outbox.filter { it.localId !in confirmed })
        }
        if (merged.isEmpty()) return
        cacheLock.withLock { store.saveMessages(token, merged) }
        markRead(token)
    }

    /** Markiert das geöffnete Gespräch bis zur neuesten Nachricht als gelesen (Anzeige sofort, Server im Hintergrund). */
    private fun markRead(token: String) {
        val s = _state.value
        if (s.openRoom?.token != token || !active) return
        val newest = ChatLogic.newestId(s.messages)
        val room = s.rooms.firstOrNull { it.token == token }
        if (newest <= 0L || room == null || (room.unread == 0 && !room.unreadMention)) return
        _state.update { st -> st.copy(rooms = st.rooms.map { if (it.token == token) it.copy(unread = 0, unreadMention = false) else it }) }
        val r = remote ?: return
        viewModelScope.launch {
            try {
                r.markRead(token, newest)
            } catch (e: IOException) {
                // Die Markierung wird beim nächsten Öffnen oder der nächsten Aktualisierung nachgeholt.
            }
        }
    }

    // endregion

    // region Raumliste und Gespräch -----------------------------------------------------------------

    fun refresh() {
        if (_state.value.openRoom != null) return
        viewModelScope.launch { refreshRoomsNow() }
    }

    fun setQuery(query: String?) {
        _state.update { it.copy(query = query) }
    }

    fun openRoom(token: String) {
        val room = _state.value.rooms.firstOrNull { it.token == token } ?: return
        _state.update {
            it.copy(openRoom = room, messages = emptyList(), outbox = emptyList(), replyTo = null, hasOlder = true, loadingOlder = false)
        }
        restartLoop()
    }

    fun closeRoom() {
        if (_state.value.openRoom == null) return
        _state.update { it.copy(openRoom = null, messages = emptyList(), replyTo = null, messagesLoading = false, loadingOlder = false) }
        restartLoop()
    }

    /** Zurück in die Raumliste bzw. Suche beenden; `true`, wenn etwas zu schließen war. */
    fun back(): Boolean {
        val s = _state.value
        return when {
            s.replyTo != null -> { _state.update { it.copy(replyTo = null) }; true }
            s.openRoom != null -> { closeRoom(); true }
            s.query != null -> { setQuery(null); true }
            else -> false
        }
    }

    fun loadOlder() {
        val s = _state.value
        val room = s.openRoom ?: return
        if (s.loadingOlder || !s.hasOlder || s.messages.isEmpty()) return
        val r = remote ?: return
        val token = room.token
        _state.update { it.copy(loadingOlder = true) }
        viewModelScope.launch {
            try {
                val older = r.messages(token, future = false, lastKnownId = ChatLogic.oldestId(_state.value.messages), limit = ChatLogic.PAGE_SIZE)
                _state.update { st ->
                    if (st.openRoom?.token != token) {
                        st
                    } else {
                        val merged = ChatLogic.merge(st.messages, older)
                        st.copy(messages = merged, hasOlder = older.size >= ChatLogic.PAGE_SIZE && merged.size < ChatLogic.MEMORY_MESSAGES, loadingOlder = false)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                _state.update { it.copy(loadingOlder = false) }
                notice(ChatNoticeKind.LOAD_FAILED)
            }
        }
    }

    // endregion

    // region Senden ----------------------------------------------------------------------------------

    fun setReply(message: ChatMessage?) {
        _state.update { it.copy(replyTo = message) }
    }

    fun send(text: String) {
        val room = _state.value.openRoom ?: return
        if (room.readOnly) return notice(ChatNoticeKind.READ_ONLY)
        val clean = ChatLogic.clean(text)
        when (ChatLogic.check(clean)) {
            OutgoingCheck.EMPTY -> return notice(ChatNoticeKind.SEND_EMPTY)
            OutgoingCheck.TOO_LONG -> return notice(ChatNoticeKind.SEND_TOO_LONG)
            OutgoingCheck.OK -> Unit
        }
        val outgoing = Outgoing(
            localId = UUID.randomUUID().toString(),
            referenceId = UUID.randomUUID().toString().replace("-", ""),
            token = room.token,
            text = clean,
            replyToId = _state.value.replyTo?.id,
        )
        _state.update { it.copy(outbox = it.outbox + outgoing, replyTo = null) }
        deliver(outgoing)
    }

    fun retry(localId: String) {
        val o = _state.value.outbox.firstOrNull { it.localId == localId && it.state == OutgoingState.FAILED } ?: return
        val again = o.copy(state = OutgoingState.SENDING)
        _state.update { s -> s.copy(outbox = s.outbox.map { if (it.localId == localId) again else it }) }
        deliver(again)
    }

    fun discard(localId: String) {
        _state.value.outbox.firstOrNull { it.localId == localId }?.let(::dropFile)
        _state.update { s -> s.copy(outbox = s.outbox.filter { it.localId != localId }) }
    }

    /**
     * Sendet die vom Benutzer gewählte Datei [uri] mit der Bildunterschrift [caption] ins geöffnete Gespräch. Die Datei
     * wird zuerst in den App-Cache kopiert (so übersteht ein erneuter Versuch auch den Verlust der Zugriffsrechte) und
     * nach dem Senden oder Verwerfen wieder gelöscht.
     */
    fun sendAttachment(uri: Uri, caption: String) {
        val room = _state.value.openRoom ?: return
        if (room.readOnly) return notice(ChatNoticeKind.READ_ONLY)
        val text = ChatLogic.clean(caption)
        if (text.length > ChatApi.MAX_MESSAGE_CHARS) return notice(ChatNoticeKind.SEND_TOO_LONG)
        val replyTo = _state.value.replyTo?.id
        viewModelScope.launch {
            val staged = withContext(Dispatchers.IO) { stage(uri) }
            when (staged) {
                null -> return@launch notice(ChatNoticeKind.ATTACH_FAILED)
                is Staged.TooLarge -> return@launch notice(ChatNoticeKind.ATTACH_TOO_LARGE)
                is Staged.Ready -> {
                    val outgoing = Outgoing(
                        localId = UUID.randomUUID().toString(),
                        referenceId = UUID.randomUUID().toString().replace("-", ""),
                        token = room.token,
                        text = text,
                        replyToId = replyTo,
                        attachmentName = staged.name,
                        filePath = staged.file.absolutePath,
                    )
                    _state.update { it.copy(outbox = it.outbox + outgoing, replyTo = null) }
                    deliver(outgoing)
                }
            }
        }
    }

    private sealed interface Staged {
        class Ready(val name: String, val file: File) : Staged
        data object TooLarge : Staged
    }

    private fun outDir(): File = File(getApplication<Application>().cacheDir, "chat_out")

    /** Kopiert die Datei hinter [uri] in den Cache. `null` bei Lesefehler. */
    private fun stage(uri: Uri): Staged? {
        val resolver = getApplication<Application>().contentResolver
        return try {
            var display: String? = null
            var size = -1L
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    display = c.getString(0)
                    size = if (c.isNull(1)) -1L else c.getLong(1)
                }
            }
            if (size > ChatApi.MAX_ATTACHMENT_BYTES) return Staged.TooLarge
            val name = ChatApi.safeFileName(display ?: uri.lastPathSegment.orEmpty())
            val dir = outDir().apply { mkdirs() }
            val target = File(dir, UUID.randomUUID().toString())
            var copied = 0L
            val input = resolver.openInputStream(uri) ?: return null
            input.use { source ->
                target.outputStream().use { sink ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        val n = source.read(buffer)
                        if (n < 0) break
                        copied += n
                        if (copied > ChatApi.MAX_ATTACHMENT_BYTES) {
                            target.delete()
                            return Staged.TooLarge
                        }
                        sink.write(buffer, 0, n)
                    }
                }
            }
            if (copied == 0L) {
                target.delete()
                return null
            }
            Staged.Ready(name, target)
        } catch (e: IOException) {
            null
        } catch (e: SecurityException) {
            null
        }
    }

    private fun dropFile(o: Outgoing) {
        o.filePath?.let { path -> runCatching { File(path).delete() } }
    }

    /** Beim Start: Reste früherer Sendungen im Cache löschen (die Warteschlange liegt nur im Speicher). */
    private fun cleanStaging() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { outDir().listFiles()?.forEach { it.delete() } }
        }
    }

    /** Datei hochladen, dann im Gespräch teilen. Ein erneuter Versuch lädt eine schon hochgeladene Datei nicht noch einmal hoch. */
    private suspend fun deliverAttachment(r: ChatRemote, o: Outgoing) {
        val file = File(o.filePath ?: throw IOException("Datei fehlt"))
        if (!file.isFile) throw IOException("Datei fehlt")
        val folder = r.attachmentFolder()
        val remoteName = o.remoteName ?: r.upload(_state.value.ownId, folder, o.attachmentName ?: "Datei", file).also { name ->
            _state.update { s -> s.copy(outbox = s.outbox.map { if (it.localId == o.localId) it.copy(remoteName = name) else it }) }
        }
        r.share(o.token, remoteName, o.text, o.referenceId)
        dropFile(o)
        // Die Nachricht entsteht auf dem Server; sofort holen, damit sie die Wartezeile ersetzt.
        val latest = try {
            r.messages(o.token, future = false, lastKnownId = 0L, limit = 5)
        } catch (e: ChatHttpException) {
            emptyList()
        } catch (e: IOException) {
            emptyList()
        }
        if (latest.isNotEmpty()) applyMessages(o.token, latest)
        _state.update { s -> s.copy(outbox = s.outbox.filter { it.localId != o.localId }) }
    }

    private fun deliver(o: Outgoing) {
        val r = remote ?: return
        viewModelScope.launch {
            try {
                if (o.filePath != null) {
                    deliverAttachment(r, o)
                    return@launch
                }
                val sent = r.send(o.token, o.text, o.replyToId, o.referenceId)
                _state.update { s -> s.copy(outbox = s.outbox.filter { it.localId != o.localId }) }
                applyMessages(o.token, listOf(sent))
            } catch (e: CancellationException) {
                throw e
            } catch (e: ChatHttpException) {
                fail(o.localId)
                val attachment = o.filePath != null
                when (e.code) {
                    401 -> authLost()
                    403 -> notice(if (attachment) ChatNoticeKind.ATTACH_FAILED else ChatNoticeKind.READ_ONLY)
                    413 -> notice(if (attachment) ChatNoticeKind.ATTACH_TOO_LARGE else ChatNoticeKind.SEND_TOO_LONG)
                    in 400..599 -> if (attachment) notice(ChatNoticeKind.ATTACH_FAILED)
                }
            } catch (e: IOException) {
                fail(o.localId)
            }
        }
    }

    private fun fail(localId: String) {
        _state.update { s -> s.copy(outbox = s.outbox.map { if (it.localId == localId) it.copy(state = OutgoingState.FAILED) else it }) }
    }

    // endregion

    // region Konto -------------------------------------------------------------------------------------

    fun login(serverInput: String, user: String, password: String) {
        val base = when (val parsed = NewsApi.parseServerUrl(serverInput)) {
            is ServerUrl.Ok -> parsed.baseUrl
            ServerUrl.NotHttps -> return notice(ChatNoticeKind.LOGIN_NOT_HTTPS)
            else -> return notice(ChatNoticeKind.LOGIN_INVALID_URL)
        }
        val name = user.trim()
        if (name.isEmpty() || password.isEmpty()) return notice(ChatNoticeKind.LOGIN_AUTH)
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            val candidate = ChatRemote(base, name, password)
            val userId = try {
                candidate.check()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ChatHttpException) {
                _state.update { it.copy(busy = false) }
                return@launch notice(if (e.code == 401 || e.code == 403) ChatNoticeKind.LOGIN_AUTH else ChatNoticeKind.LOGIN_NOT_FOUND)
            } catch (e: IOException) {
                _state.update { it.copy(busy = false) }
                return@launch notice(ChatNoticeKind.LOGIN_NETWORK)
            }
            val saved = ChatCredentials(base, name, password, userId)
            try {
                // Ein anderes Konto darf nichts vom Zwischenspeicher des vorherigen sehen.
                if (credentials != null) store.clear()
                store.saveCredentials(saved)
            } catch (e: GeneralSecurityException) {
                _state.update { it.copy(busy = false) }
                return@launch notice(ChatNoticeKind.LOGIN_KEYSTORE)
            }
            credentials = saved
            remote = candidate
            authBroken = false
            _state.update {
                it.copy(
                    busy = false, account = ChatAccount(base, name), ownId = userId, rooms = emptyList(),
                    openRoom = null, messages = emptyList(), outbox = emptyList(), offline = false,
                )
            }
            restartLoop()
        }
    }

    /** Übernimmt Server, Benutzer und App-Passwort des FeedReaders. */
    fun loginWithFeedAccount() {
        viewModelScope.launch {
            val c = FeedStore(getApplication()).loadCredentials()
            if (c == null) notice(ChatNoticeKind.FEED_ACCOUNT_MISSING) else login(c.baseUrl, c.user, c.password)
        }
    }

    /** Übernimmt Server, Benutzer und App-Passwort der Notizen. */
    fun loginWithNotesAccount() {
        viewModelScope.launch {
            val c = NotesStore(getApplication()).loadCredentials()
            if (c == null) notice(ChatNoticeKind.NOTES_ACCOUNT_MISSING) else login(c.baseUrl, c.user, c.password)
        }
    }

    /** Meldet ab: Zugangsdaten, Schlüssel und Zwischenspeicher werden gelöscht. */
    fun logout() {
        loopJob?.cancel()
        loopJob = null
        viewModelScope.launch {
            store.clear()
            credentials = null
            remote = null
            _state.value = ChatUiState(ready = true)
        }
    }

    private fun authLost() {
        authBroken = true
        notice(ChatNoticeKind.AUTH_LOST)
    }

    private fun roomGone() {
        _state.update { it.copy(openRoom = null, messages = emptyList(), replyTo = null, messagesLoading = false) }
        notice(ChatNoticeKind.ROOM_GONE)
        restartLoop()
    }

    // endregion

    fun dismissNotice() {
        _state.update { it.copy(notice = null) }
    }

    private fun notice(kind: ChatNoticeKind) {
        _state.update { it.copy(notice = ChatNotice(kind)) }
    }

    private companion object {
        const val LIST_REFRESH_MS = 30_000L
        const val MIN_POLL_MS = 1_000L
    }
}
