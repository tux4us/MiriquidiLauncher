// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.feed

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import android.net.Uri
import androidx.lifecycle.viewModelScope
import io.github.tux4us.miriquidilauncher.weather.MiniJson
import java.io.IOException
import java.security.GeneralSecurityException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** Gründe, aus denen eine Anmeldung oder ein Abruf scheitert; die Oberfläche übersetzt sie in Texte. */
enum class FeedProblem {
    NETWORK, AUTH, NOT_FOUND, SERVER, DATA, STORAGE,
    URL_EMPTY, URL_NOT_HTTPS, URL_INVALID, INPUT_MISSING,

    // Lokale Feeds (ohne Nextcloud)
    FEED_URL_EMPTY, FEED_URL_NOT_HTTPS, FEED_URL_INVALID,
    /** Der Feed wurde unter der Adresse nicht gefunden (HTTP 404). */
    FEED_NOT_FOUND,
    /** Die Adresse oder Datei enthält keinen lesbaren Feed. */
    NOT_A_FEED,
    FILE_READ, ALREADY_ADDED, LIMIT,
    /** Ein Teil der Feeds konnte beim Abruf nicht geladen werden. */
    PARTIAL,
}

/** Ergebnis eines Imports: wie viele Feeds hinzukamen und was nicht übernommen wurde. */
data class ImportSummary(val added: Int, val duplicates: Int, val skipped: Int, val overLimit: Int)

data class FeedAccount(val baseUrl: String, val user: String)

data class FeedUiState(
    /** `false`, bis die gespeicherten Zugangsdaten gelesen sind. */
    val ready: Boolean = false,
    val account: FeedAccount? = null,
    val feeds: Map<Long, FeedInfo> = emptyMap(),
    val items: List<FeedItem> = emptyList(),
    val unreadOnly: Boolean = true,
    val loadImages: Boolean = true,
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    val canLoadMore: Boolean = true,
    val problem: FeedProblem? = null,
    val fetchedAtMillis: Long = 0L,
    val openItemId: Long? = null,
    val loginBusy: Boolean = false,
    val loginProblem: FeedProblem? = null,
    /** Lokale Abos (ohne Nextcloud). Sie werden nur angezeigt, solange kein Nextcloud-Konto angemeldet ist. */
    val localFeeds: List<LocalFeed> = emptyList(),
    /** Der Nutzer hat im lokalen Modus die Anmeldung bei Nextcloud geöffnet. */
    val loginRequested: Boolean = false,
    val sourceBusy: Boolean = false,
    val sourceProblem: FeedProblem? = null,
    val importSummary: ImportSummary? = null,
    /** Lokale Feeds, die beim letzten Abruf nicht geladen werden konnten. */
    val failedFeedIds: Set<Long> = emptySet(),
    /** Gewähltes Abonnement; `null` zeigt die Artikel aller Abonnements. */
    val selectedFeedId: Long? = null,
) {
    /** Lokaler Modus: kein Nextcloud-Konto, aber mindestens ein lokales Abo. */
    val localMode: Boolean get() = account == null && localFeeds.isNotEmpty() && !loginRequested

    /** Einrichtung: weder Konto noch Abo vorhanden (oder die Anmeldung wurde ausdrücklich geöffnet). */
    val showSetup: Boolean get() = account == null && (loginRequested || localFeeds.isEmpty())

    val visibleItems: List<FeedItem> get() = FeedLogic.filter(items, unreadOnly, selectedFeedId)

    /** Das gewählte Abonnement, sofern es noch existiert. */
    val selectedFeed: FeedInfo? get() = selectedFeedId?.let { feeds[it] }
    val openItem: FeedItem? get() = openItemId?.let { id -> items.firstOrNull { it.id == id } }
}

/**
 * Zustand des FeedReaders. Mit Nextcloud-Konto liefert der Server Feeds und Artikel; die App zeigt sie als
 * Liste, öffnet einzelne Artikel und gibt den Gelesen-Status an den Server zurück. Wird der Server nicht
 * erreicht, bleibt der zuletzt gespeicherte Stand sichtbar, und noch nicht übermittelte Gelesen-Markierungen
 * werden beim nächsten Abruf nachgeholt.
 *
 * Ohne Nextcloud-Konto gibt es lokale Abos: Feed-Adressen (eingegeben oder per OPML bzw. JSON Feed
 * importiert), die die App selbst per HTTPS abruft. Der Gelesen-Status bleibt dann auf dem Gerät; in
 * [pendingRead] stehen in diesem Modus alle als gelesen markierten Artikel.
 */
class FeedViewModel(application: Application) : AndroidViewModel(application) {

    private val store = FeedStore(application)
    private var repository: FeedRepository? = null
    private val pendingRead = LinkedHashSet<Long>()
    private val saveLock = Mutex()
    private var fetchGeneration = 0

    private val _state = MutableStateFlow(FeedUiState())
    val state: StateFlow<FeedUiState> = _state.asStateFlow()

    /** `true`, solange ein Artikel geöffnet ist (der seitliche Pager wischt dann nicht). */
    val articleOpen: StateFlow<Boolean> = _state
        .map { it.openItemId != null }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    init {
        viewModelScope.launch {
            store.settings.collect { settings ->
                _state.update { it.copy(unreadOnly = settings.unreadOnly, loadImages = settings.loadImages) }
            }
        }
        viewModelScope.launch {
            val credentials = store.loadCredentials()
            if (credentials == null) loadLocal()
            val cache = if (credentials != null) store.loadCache() else null
            if (credentials != null) {
                repository = FeedRepository(credentials.baseUrl, credentials.user, credentials.password)
                if (cache != null) {
                    pendingRead.addAll(cache.pendingRead)
                    _state.update {
                        it.copy(
                            feeds = cache.feeds.associateBy(FeedInfo::id),
                            items = cache.items,
                            fetchedAtMillis = cache.fetchedAtMillis,
                        )
                    }
                }
            }
            _state.update {
                it.copy(ready = true, account = credentials?.let { c -> FeedAccount(c.baseUrl, c.user) })
            }
        }
    }

    /** Prüft die Zugangsdaten gegen den Server und speichert sie bei Erfolg. */
    fun login(serverInput: String, user: String, password: String) {
        if (_state.value.loginBusy) return
        val baseUrl = when (val parsed = NewsApi.parseServerUrl(serverInput)) {
            is ServerUrl.Ok -> parsed.baseUrl
            ServerUrl.Empty -> return fail(FeedProblem.URL_EMPTY)
            ServerUrl.NotHttps -> return fail(FeedProblem.URL_NOT_HTTPS)
            ServerUrl.Invalid -> return fail(FeedProblem.URL_INVALID)
        }
        val name = user.trim()
        if (name.isEmpty() || password.isEmpty()) return fail(FeedProblem.INPUT_MISSING)

        _state.update { it.copy(loginBusy = true, loginProblem = null) }
        viewModelScope.launch {
            val candidate = FeedRepository(baseUrl, name, password)
            try {
                val feeds = candidate.fetchFeeds()
                store.saveCredentials(FeedCredentials(baseUrl, name, password))
                repository = candidate
                pendingRead.clear()
                fetchGeneration++
                _state.update {
                    it.copy(
                        account = FeedAccount(baseUrl, name),
                        feeds = feeds.associateBy(FeedInfo::id),
                        items = emptyList(),
                        problem = null,
                        loginBusy = false,
                        loginProblem = null,
                        loginRequested = false,
                        openItemId = null,
                        refreshing = false,
                        loadingMore = false,
                        canLoadMore = true,
                        failedFeedIds = emptySet(),
                        fetchedAtMillis = 0L,
                    )
                }
                refresh(force = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loginBusy = false, loginProblem = if (isStorage(e)) FeedProblem.STORAGE else problemOf(e)) }
            }
        }
    }

    private fun fail(problem: FeedProblem) {
        _state.update { it.copy(loginProblem = problem) }
    }

    fun clearLoginProblem() {
        _state.update { it.copy(loginProblem = null) }
    }

    /** Abmelden: löscht Zugangsdaten, Schlüssel und den gespeicherten Stand. Lokale Abos erscheinen wieder. */
    fun logout() {
        fetchGeneration++
        repository = null
        pendingRead.clear()
        _state.update {
            FeedUiState(ready = true, unreadOnly = it.unreadOnly, loadImages = it.loadImages)
        }
        viewModelScope.launch {
            store.clear()
            loadLocal()
        }
    }

    /**
     * Lädt neue Artikel. Ohne [force] nur, wenn der letzte Abruf länger als [STALE_AFTER_MILLIS] zurückliegt;
     * so löst das bloße Öffnen der Seite nicht jedes Mal eine Anfrage aus.
     */
    fun refresh(force: Boolean = false) {
        val repo = repository
        if (repo == null) {
            refreshLocal(force)
            return
        }
        val current = _state.value
        if (current.refreshing) return
        if (!force && current.fetchedAtMillis != 0L &&
            System.currentTimeMillis() - current.fetchedAtMillis < STALE_AFTER_MILLIS
        ) return
        val generation = fetchGeneration
        _state.update { it.copy(refreshing = true, problem = null) }
        viewModelScope.launch {
            try {
                flushPending(repo)
                val selectedId = current.selectedFeedId
                val unreadOnly = current.unreadOnly
                val (feeds, unread, all) = coroutineScope {
                    val feeds = async { repo.fetchFeeds() }
                    val unread = async { repo.fetchItems(includeRead = false) }
                    val all = async { repo.fetchItems(includeRead = true) }
                    Triple(feeds.await(), unread.await(), all.await())
                }
                // Das gewählte Abonnement wird zusätzlich einzeln geladen, damit seine Liste vollständig bleibt.
                val ofSelected = if (selectedId != null) repo.fetchItems(includeRead = !unreadOnly, feedId = selectedId) else emptyList()
                if (generation != fetchGeneration) return@launch
                val merged = FeedLogic.mergeKeeping(pendingRead, selectedId, unread, all, ofSelected)
                _state.update {
                    it.copy(
                        feeds = feeds.associateBy(FeedInfo::id),
                        items = merged,
                        // Ein geöffneter Artikel, der nicht mehr in der Liste ist, wird geschlossen.
                        openItemId = it.openItemId?.takeIf { id -> merged.any { m -> m.id == id } },
                        refreshing = false,
                        canLoadMore = true,
                        fetchedAtMillis = System.currentTimeMillis(),
                    )
                }
                persist()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation == fetchGeneration) _state.update { it.copy(refreshing = false, problem = problemOf(e)) }
            }
        }
    }

    /** Lädt die nächste Seite älterer Artikel passend zum Filter. */
    fun loadMore() {
        val repo = repository ?: return
        val current = _state.value
        if (current.loadingMore || current.refreshing || !current.canLoadMore) return
        val offset = FeedLogic.oldestId(current.visibleItems)
        if (offset <= 0L) return
        val feedId = current.selectedFeedId
        val generation = fetchGeneration
        _state.update { it.copy(loadingMore = true, problem = null) }
        viewModelScope.launch {
            try {
                val older = repo.fetchItems(includeRead = !current.unreadOnly, offset = offset, feedId = feedId)
                if (generation != fetchGeneration) return@launch
                _state.update {
                    it.copy(
                        items = FeedLogic.appendOlder(it.items, older, pendingRead, feedId),
                        loadingMore = false,
                        canLoadMore = older.size >= FeedRepository.PAGE_SIZE,
                    )
                }
                persist()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation == fetchGeneration) _state.update { it.copy(loadingMore = false, problem = problemOf(e)) }
            }
        }
    }

    /** Öffnet einen Artikel, markiert ihn lokal als gelesen und meldet das dem Server. */
    fun open(itemId: Long) {
        val item = _state.value.items.firstOrNull { it.id == itemId } ?: return
        _state.update { it.copy(openItemId = itemId, items = FeedLogic.markRead(it.items, itemId)) }
        if (item.unread) {
            pendingRead.add(itemId)
            viewModelScope.launch {
                persist()
                repository?.let { sendRead(it, itemId) }
            }
        }
    }

    fun close() {
        _state.update { it.copy(openItemId = null) }
    }

    /**
     * Zeigt nur die Artikel eines Abonnements ([feedId]) oder wieder alle (`null`). Mit Nextcloud-Konto wird die
     * erste Seite dieses Abonnements zusätzlich vom Server geholt, damit auch Artikel erscheinen, die nicht unter
     * den zuletzt geladenen aller Abonnements sind.
     */
    fun selectFeed(feedId: Long?) {
        val start = _state.value
        if (start.selectedFeedId == feedId) return
        _state.update { it.copy(selectedFeedId = feedId, canLoadMore = it.account != null, loadingMore = false) }
        val repo = repository ?: return
        if (feedId == null) return
        val generation = fetchGeneration
        val includeRead = !start.unreadOnly
        _state.update { it.copy(loadingMore = true, problem = null) }
        viewModelScope.launch {
            try {
                val page = repo.fetchItems(includeRead = includeRead, feedId = feedId)
                if (generation != fetchGeneration || _state.value.selectedFeedId != feedId) return@launch
                _state.update {
                    it.copy(
                        items = FeedLogic.mergeKeeping(pendingRead, feedId, it.items, page),
                        loadingMore = false,
                        canLoadMore = page.size >= FeedRepository.PAGE_SIZE,
                    )
                }
                persist()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation == fetchGeneration && _state.value.selectedFeedId == feedId) {
                    _state.update { it.copy(loadingMore = false, problem = problemOf(e)) }
                }
            }
        }
    }

    fun setUnreadOnly(value: Boolean) {
        // Weitere Seiten gibt es nur bei Nextcloud; lokale Feeds liefern ihre Artikel mit dem Abruf.
        _state.update { it.copy(unreadOnly = value, canLoadMore = it.account != null) }
        viewModelScope.launch { store.setUnreadOnly(value) }
    }

    fun setLoadImages(value: Boolean) {
        _state.update { it.copy(loadImages = value) }
        viewModelScope.launch { store.setLoadImages(value) }
    }

    private suspend fun sendRead(repo: FeedRepository, itemId: Long) {
        try {
            repo.markRead(itemId)
            pendingRead.remove(itemId)
            persist()
        } catch (e: CancellationException) {
            throw e
        } catch (ignored: Exception) {
            // Bleibt vorgemerkt und wird beim nächsten Abruf erneut gesendet.
        }
    }

    /** Sendet vorgemerkte Gelesen-Markierungen; bei einem Fehler (offline, Anmeldung) wird abgebrochen. */
    private suspend fun flushPending(repo: FeedRepository) {
        for (id in pendingRead.toList()) {
            try {
                repo.markRead(id)
                pendingRead.remove(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: NewsHttpException) {
                // Der Artikel existiert nicht mehr: Markierung verwerfen. Sonst später erneut versuchen.
                if (e.code == 404) pendingRead.remove(id) else return
            } catch (ignored: Exception) {
                return
            }
        }
    }

    private suspend fun persist() {
        saveLock.withLock {
            val s = _state.value
            if (s.account != null) {
                store.saveCache(FeedSnapshot(s.feeds.values.toList(), s.items, s.fetchedAtMillis, pendingRead.toSet()))
            } else if (s.localFeeds.isNotEmpty()) {
                val feeds = s.localFeeds.map { FeedInfo(it.id, it.title, it.url) }
                store.saveLocal(FeedSnapshot(feeds, s.items, s.fetchedAtMillis, pendingRead.toSet()))
            } else {
                store.deleteLocal()
            }
        }
    }

    // region Lokale Feeds --------------------------------------------------------------------------

    /** Lädt die lokalen Abos und ihre gespeicherten Artikel in den Zustand (nur ohne Nextcloud-Konto). */
    private suspend fun loadLocal() {
        val snapshot = store.loadLocal() ?: return
        val feeds = snapshot.feeds.mapNotNull { info -> info.link?.let { LocalFeed(it, info.title) } }
        if (feeds.isEmpty()) return
        val ids = feeds.mapTo(HashSet()) { it.id }
        pendingRead.clear()
        pendingRead.addAll(snapshot.pendingRead)
        _state.update {
            it.copy(
                localFeeds = feeds,
                feeds = localInfos(feeds),
                items = snapshot.items.filter { item -> item.feedId in ids },
                fetchedAtMillis = snapshot.fetchedAtMillis,
                canLoadMore = false,
            )
        }
    }

    private fun localInfos(feeds: List<LocalFeed>): Map<Long, FeedInfo> =
        feeds.associate { it.id to FeedInfo(it.id, LocalFeeds.displayTitle(it), it.url) }

    private fun refreshLocal(force: Boolean) {
        val current = _state.value
        if (!current.localMode || current.refreshing) return
        if (!force && current.fetchedAtMillis != 0L &&
            System.currentTimeMillis() - current.fetchedAtMillis < STALE_AFTER_MILLIS
        ) return
        val live = current.localFeeds.filter { !it.isStatic }
        if (live.isEmpty()) return
        val generation = fetchGeneration
        _state.update { it.copy(refreshing = true, problem = null) }
        viewModelScope.launch {
            try {
                val results = fetchAll(live)
                if (generation != fetchGeneration) return@launch
                val fetched = HashMap<String, ParsedFeed>()
                var firstFailure: Throwable? = null
                for ((url, result) in results) {
                    result.onSuccess { fetched[url] = it }
                    if (result.isFailure && firstFailure == null) firstFailure = result.exceptionOrNull()
                }
                val failedUrls = live.map { it.url }.filter { it !in fetched }
                val problem = when {
                    failedUrls.isEmpty() -> null
                    fetched.isEmpty() -> localProblemOf(firstFailure)
                    else -> FeedProblem.PARTIAL
                }
                val nowMillis = System.currentTimeMillis()
                _state.update { s ->
                    // Titel von Feeds ohne Namen (z. B. aus OPML) werden aus dem Feed übernommen.
                    val feeds = s.localFeeds.map { f ->
                        val title = fetched[f.url]?.title?.takeIf { it.isNotBlank() }
                        if (f.title.isBlank() && title != null) f.copy(title = title) else f
                    }
                    val items = LocalFeeds.combine(feeds, fetched, s.items, pendingRead, nowMillis / 1000)
                    s.copy(
                        localFeeds = feeds,
                        feeds = localInfos(feeds),
                        items = items,
                        openItemId = s.openItemId?.takeIf { id -> items.any { it.id == id } },
                        refreshing = false,
                        problem = problem,
                        failedFeedIds = feeds.filter { it.url in failedUrls }.mapTo(HashSet()) { it.id },
                        fetchedAtMillis = if (fetched.isEmpty()) s.fetchedAtMillis else nowMillis,
                    )
                }
                // Gelesen-Markierungen bleiben nur für Artikel, die es noch gibt.
                val keep = LocalFeeds.liveIds(live, fetched) + _state.value.items.map { it.id }
                pendingRead.retainAll(keep)
                persist()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation == fetchGeneration) _state.update { it.copy(refreshing = false, problem = FeedProblem.DATA) }
            }
        }
    }

    /** Lädt die Feeds, höchstens [PARALLEL_FETCHES] gleichzeitig. Ein Fehler betrifft nur den jeweiligen Feed. */
    private suspend fun fetchAll(feeds: List<LocalFeed>): Map<String, Result<ParsedFeed>> = coroutineScope {
        val gate = Semaphore(PARALLEL_FETCHES)
        feeds.map { feed ->
            async { feed.url to gate.withPermit { fetchOne(feed.url) } }
        }.awaitAll().toMap()
    }

    private suspend fun fetchOne(url: String): Result<ParsedFeed> = try {
        Result.success(FeedFetcher.loadFeed(url))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    /** Fügt einen Feed per Adresse hinzu. Ist die Adresse eine Webseite, wird ihr Feed-Verweis verwendet. */
    fun addFeedUrl(input: String) {
        val current = _state.value
        if (current.sourceBusy || current.account != null) return
        val url = when (val parsed = FeedUrl.parse(input)) {
            is FeedUrlResult.Ok -> parsed.url
            FeedUrlResult.Empty -> return failSource(FeedProblem.FEED_URL_EMPTY)
            FeedUrlResult.NotHttps -> return failSource(FeedProblem.FEED_URL_NOT_HTTPS)
            FeedUrlResult.Invalid -> return failSource(FeedProblem.FEED_URL_INVALID)
        }
        _state.update { it.copy(sourceBusy = true, sourceProblem = null, importSummary = null) }
        viewModelScope.launch {
            try {
                val (feedUrl, parsed) = FeedFetcher.loadOrDiscover(url)
                finishImport(FeedImport.planFor(parsed, fetchedFrom = feedUrl), single = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(sourceBusy = false, sourceProblem = localProblemOf(e)) }
            }
        }
    }

    /** Liest eine vom Nutzer gewählte Datei (OPML, JSON Feed, RSS oder Atom) und übernimmt ihren Inhalt. */
    fun importFile(uri: Uri) {
        val current = _state.value
        if (current.sourceBusy || current.account != null) return
        _state.update { it.copy(sourceBusy = true, sourceProblem = null, importSummary = null) }
        viewModelScope.launch {
            try {
                val text = withContext(Dispatchers.IO) { readFile(uri) }
                finishImport(FeedImport.parse(text), single = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: FeedParseException) {
                _state.update { it.copy(sourceBusy = false, sourceProblem = FeedProblem.NOT_A_FEED) }
            } catch (e: Exception) {
                _state.update { it.copy(sourceBusy = false, sourceProblem = FeedProblem.FILE_READ) }
            }
        }
    }

    private fun readFile(uri: Uri): String {
        val input = getApplication<Application>().contentResolver.openInputStream(uri) ?: throw IOException("Datei nicht lesbar")
        val bytes = input.use { stream ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val n = stream.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
                if (out.size() > MAX_IMPORT_BYTES) throw IOException("Datei zu groß")
            }
            out.toByteArray()
        }
        return FeedParser.decode(bytes, null)
    }

    private fun finishImport(plan: ImportPlan, single: Boolean) {
        val before = _state.value
        val result = LocalFeeds.addFeeds(before.localFeeds, plan.feeds)
        if (single && result.added == 0) {
            val problem = if (result.overLimit > 0) FeedProblem.LIMIT else FeedProblem.ALREADY_ADDED
            _state.update { it.copy(sourceBusy = false, sourceProblem = problem) }
            return
        }
        val nowMillis = System.currentTimeMillis()
        val seeds = plan.seeds.filterKeys { url -> result.feeds.any { it.url == url } }
        val summary = ImportSummary(result.added, result.duplicates, plan.skippedInsecure + plan.skippedInvalid, result.overLimit)
        _state.update { s ->
            s.copy(
                localFeeds = result.feeds,
                feeds = localInfos(result.feeds),
                items = LocalFeeds.combine(result.feeds, seeds, s.items, pendingRead, nowMillis / 1000),
                sourceBusy = false,
                sourceProblem = null,
                importSummary = summary,
                canLoadMore = false,
                fetchedAtMillis = if (s.fetchedAtMillis == 0L && seeds.isNotEmpty()) nowMillis else s.fetchedAtMillis,
            )
        }
        viewModelScope.launch { persist() }
        // Feeds ohne mitgelieferte Artikel werden sofort abgerufen.
        if (plan.feeds.any { !it.isStatic && it.url !in seeds }) refresh(force = true)
    }

    /** Entfernt ein lokales Abo samt seinen gespeicherten Artikeln. */
    fun removeFeed(feedId: Long) {
        _state.update { s ->
            val feeds = s.localFeeds.filter { it.id != feedId }
            val items = s.items.filter { it.feedId != feedId }
            val failed = s.failedFeedIds - feedId
            s.copy(
                selectedFeedId = s.selectedFeedId?.takeIf { it != feedId },
                localFeeds = feeds,
                feeds = localInfos(feeds),
                items = items,
                failedFeedIds = failed,
                openItemId = s.openItemId?.takeIf { id -> items.any { it.id == id } },
                problem = if (s.problem == FeedProblem.PARTIAL && failed.isEmpty()) null else s.problem,
            )
        }
        if (_state.value.localFeeds.isEmpty()) pendingRead.clear()
        viewModelScope.launch { persist() }
    }

    /** Öffnet im lokalen Modus die Anmeldung bei Nextcloud; Nextcloud hat danach Vorrang vor den lokalen Abos. */
    fun requestNextcloudLogin() {
        _state.update { it.copy(loginRequested = true, loginProblem = null) }
    }

    fun cancelNextcloudLogin() {
        _state.update { it.copy(loginRequested = false, loginProblem = null) }
    }

    fun clearSourceMessages() {
        _state.update { it.copy(sourceProblem = null, importSummary = null) }
    }

    private fun failSource(problem: FeedProblem) {
        _state.update { it.copy(sourceProblem = problem, importSummary = null) }
    }

    private fun localProblemOf(e: Throwable?): FeedProblem = when (e) {
        is NewsHttpException -> if (e.code == 404 || e.code == 410) FeedProblem.FEED_NOT_FOUND else FeedProblem.SERVER
        is FeedParseException -> FeedProblem.NOT_A_FEED
        is IOException -> FeedProblem.NETWORK
        else -> FeedProblem.DATA
    }

    // endregion

    private fun isStorage(e: Exception): Boolean = e is GeneralSecurityException

    private fun problemOf(e: Throwable): FeedProblem = when (e) {
        is NewsHttpException -> when (e.code) {
            401, 403 -> FeedProblem.AUTH
            404 -> FeedProblem.NOT_FOUND
            else -> FeedProblem.SERVER
        }
        is MiniJson.JsonException -> FeedProblem.DATA
        is IOException -> FeedProblem.NETWORK
        else -> FeedProblem.DATA
    }

    private companion object {
        const val STALE_AFTER_MILLIS = 5 * 60 * 1000L
        const val PARALLEL_FETCHES = 4
        const val MAX_IMPORT_BYTES = 8 * 1024 * 1024
    }
}
