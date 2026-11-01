// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.podcasts

import android.app.Application
import android.content.ComponentName
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import io.github.tux4us.miriquidilauncher.feed.FeedParseException
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class PodcastTab { SUBSCRIPTIONS, FAVORITES, DOWNLOADS, SEARCH }

enum class NoticeKind {
    NETWORK,
    NOT_A_FEED,
    LIMIT,
    ADDED,
    IMPORTED,
    IMPORT_FAILED,
    EXPORTED,
    EXPORT_FAILED,
    DOWNLOAD_FAILED,
    PLAYBACK_FAILED,
}

/** Hinweis für die Oberfläche; [a] und [b] sind Zahlen für Platzhalter (z. B. übernommen und übersprungen). */
data class PodcastNotice(val kind: NoticeKind, val a: Int = 0, val b: Int = 0)

data class PlayerUi(
    val episode: Episode? = null,
    val playing: Boolean = false,
    val buffering: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
)

data class PodcastsUiState(
    val data: PodcastData = PodcastData(),
    val loaded: Boolean = false,
    val refreshing: Boolean = false,
    val tab: PodcastTab = PodcastTab.SUBSCRIPTIONS,
    /** Adresse des geöffneten Podcasts (Folgenliste) oder `null`. */
    val selectedPodcast: String? = null,
    /** Geöffnete Folge (Shownotes) oder `null`. */
    val openEpisode: Episode? = null,
    /** `true`, solange die Liste aller Folgen aller Abos offen ist. */
    val showAll: Boolean = false,
    val query: String = "",
    val searching: Boolean = false,
    val searched: Boolean = false,
    val hits: List<PodcastHit> = emptyList(),
    val working: Boolean = false,
    /** Folgen-ID → Fortschritt in Prozent, -1 wenn die Größe unbekannt ist. */
    val downloading: Map<String, Int> = emptyMap(),
    val player: PlayerUi = PlayerUi(),
    val notice: PodcastNotice? = null,
)

/**
 * Zustand und Aktionen der Podcast-Scope: Abos, Suche, Import/Export, Favoriten, Downloads und die Steuerung der
 * Wiedergabe über einen `MediaController` des [PlaybackService].
 */
class PodcastsViewModel(application: Application) : AndroidViewModel(application) {
    private val store = PodcastStore(application)
    private val saveLock = Mutex()
    private val _state = MutableStateFlow(PodcastsUiState())
    val state: StateFlow<PodcastsUiState> = _state.asStateFlow()

    /** `true`, solange Shownotes (WebView) offen sind; der seitliche Pager wischt dann nicht. */
    val detailOpen: StateFlow<Boolean> = _state
        .map { it.openEpisode != null }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private var controller: MediaController? = null
    private var connecting = false
    private val pending = ArrayList<(MediaController) -> Unit>()
    private val downloadJobs = HashMap<String, Job>()
    private var lastRefreshMillis = 0L
    private var refreshJob: Job? = null

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying) savePositionNow()
            updatePlayer()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) markEndedAsPlayed()
            updatePlayer()
        }

        override fun onPlayerError(error: PlaybackException) {
            notice(PodcastNotice(NoticeKind.PLAYBACK_FAILED))
            updatePlayer()
        }
    }

    /** Liest die Podcast-Daten neu aus dem Speicher, z. B. nach dem Einspielen eines Backups. */
    fun reloadFromStore() {
        viewModelScope.launch {
            saveLock.withLock { }
            val data = store.load()
            _state.update { it.copy(data = data, loaded = true) }
        }
    }

    init {
        viewModelScope.launch {
            val data = store.load()
            _state.update { it.copy(data = data, loaded = true) }
        }
        viewModelScope.launch {
            var tick = 0
            while (true) {
                delay(1000)
                val c = controller ?: continue
                if (c.isPlaying) {
                    updatePlayer()
                    if (++tick % 5 == 0) savePositionNow()
                }
            }
        }
    }

    // region Anzeige und Navigation --------------------------------------------------------------

    /** Die Seite wird sichtbar: Wiedergabe-Verbindung herstellen und veraltete Abos aktualisieren. */
    fun onActive() {
        withController { updatePlayer() }
        if (System.currentTimeMillis() - lastRefreshMillis > REFRESH_INTERVAL_MS) refreshAll()
    }

    fun selectTab(tab: PodcastTab) = _state.update { it.copy(tab = tab, selectedPodcast = null, openEpisode = null, showAll = false) }

    /** Öffnet die Liste aller Folgen aller Abos (neueste zuerst). */
    fun openAll() = _state.update { it.copy(showAll = true, selectedPodcast = null, openEpisode = null) }

    fun openPodcast(feedUrl: String) {
        _state.update { it.copy(selectedPodcast = feedUrl, openEpisode = null) }
        if (_state.value.data.episodes[feedUrl].isNullOrEmpty()) refresh(feedUrl)
    }

    fun openEpisode(episode: Episode) = _state.update { it.copy(openEpisode = episode) }

    /** Geht eine Ebene zurück. Liefert `false`, wenn nichts mehr zu schließen ist. */
    fun back(): Boolean {
        val s = _state.value
        return when {
            s.openEpisode != null -> { _state.update { it.copy(openEpisode = null) }; true }
            s.selectedPodcast != null -> { _state.update { it.copy(selectedPodcast = null) }; true }
            s.showAll -> { _state.update { it.copy(showAll = false) }; true }
            else -> false
        }
    }

    fun setQuery(text: String) = _state.update { it.copy(query = text.take(PodcastSearch.MAX_QUERY)) }
    fun dismissNotice() = _state.update { it.copy(notice = null) }
    private fun notice(n: PodcastNotice) = _state.update { it.copy(notice = n) }

    // endregion

    // region Zustand speichern -------------------------------------------------------------------

    /** Ändert die Daten; `null` aus [change] bedeutet „abgelehnt“ (Limit) und liefert `false`. */
    private fun mutate(withEpisodes: Boolean = false, change: (PodcastData) -> PodcastData?): Boolean {
        if (!_state.value.loaded) return false
        var accepted = true
        _state.update { s ->
            val next = change(s.data)
            if (next == null) {
                accepted = false
                s
            } else {
                accepted = true
                s.copy(data = next)
            }
        }
        if (accepted) {
            viewModelScope.launch { saveLock.withLock { store.save(_state.value.data, withEpisodes) } }
        }
        return accepted
    }

    // endregion

    // region Abos, Suche, Import und Export ------------------------------------------------------

    fun refreshAll() {
        if (refreshJob?.isActive == true || !_state.value.loaded) return
        lastRefreshMillis = System.currentTimeMillis()
        refreshJob = viewModelScope.launch {
            _state.update { it.copy(refreshing = true) }
            try {
                for (podcast in _state.value.data.subscriptions) {
                    loadAndApply(podcast.feedUrl)
                }
            } finally {
                _state.update { it.copy(refreshing = false) }
            }
        }
    }

    fun refresh(feedUrl: String) {
        viewModelScope.launch {
            _state.update { it.copy(refreshing = true) }
            try {
                if (!loadAndApply(feedUrl)) notice(PodcastNotice(NoticeKind.NETWORK))
            } finally {
                _state.update { it.copy(refreshing = false) }
            }
        }
    }

    /** Lädt einen Feed und übernimmt ihn, wenn das Abo noch besteht. `true` bei Erfolg. */
    private suspend fun loadAndApply(feedUrl: String): Boolean = try {
        val parsed = PodcastRepository.loadFeed(feedUrl)
        mutate(withEpisodes = true) { data ->
            if (data.subscriptions.none { it.feedUrl == feedUrl }) data else PodcastLogic.applyFeed(data, parsed)
        }
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        false
    } catch (e: FeedParseException) {
        false
    }

    /** Abonniert die Feed-Adresse [input] (mit oder ohne `https://`). */
    fun addByUrl(input: String) {
        val url = PodcastUrls.https(input)
        if (url == null) {
            notice(PodcastNotice(NoticeKind.NOT_A_FEED))
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(working = true) }
            try {
                val parsed = PodcastRepository.loadFeed(url)
                val added = mutate(withEpisodes = true) { data ->
                    PodcastLogic.subscribe(data, parsed.podcast)?.let { PodcastLogic.applyFeed(it, parsed) }
                }
                notice(PodcastNotice(if (added) NoticeKind.ADDED else NoticeKind.LIMIT, a = 1))
            } catch (e: CancellationException) {
                throw e
            } catch (e: FeedParseException) {
                notice(PodcastNotice(NoticeKind.NOT_A_FEED))
            } catch (e: IOException) {
                notice(PodcastNotice(NoticeKind.NETWORK))
            } finally {
                _state.update { it.copy(working = false) }
            }
        }
    }

    fun search() {
        val query = _state.value.query
        val provider = _state.value.data.provider
        if (query.isBlank()) return
        viewModelScope.launch {
            _state.update { it.copy(searching = true) }
            try {
                val hits = PodcastRepository.search(provider, query)
                _state.update { it.copy(hits = hits, searched = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                notice(PodcastNotice(NoticeKind.NETWORK))
            } finally {
                _state.update { it.copy(searching = false) }
            }
        }
    }

    fun setProvider(provider: SearchProvider) {
        mutate { it.copy(provider = provider) }
        _state.update { it.copy(hits = emptyList(), searched = false) }
    }

    fun subscribe(hit: PodcastHit) {
        val added = mutate { PodcastLogic.subscribe(it, Podcast(hit.feedUrl, hit.title, hit.author, hit.imageUrl)) }
        if (!added) {
            notice(PodcastNotice(NoticeKind.LIMIT))
            return
        }
        refresh(hit.feedUrl)
    }

    fun unsubscribe(feedUrl: String) {
        mutate(withEpisodes = true) { PodcastLogic.unsubscribe(it, feedUrl) }
        _state.update { if (it.selectedPodcast == feedUrl) it.copy(selectedPodcast = null, openEpisode = null) else it }
    }

    fun isSubscribed(feedUrl: String): Boolean = _state.value.data.subscriptions.any { it.feedUrl.equals(feedUrl, ignoreCase = true) }

    /**
     * Liest eine OPML- oder HTML-Datei. OPML-Einträge werden sofort abonniert (Folgen laden danach); bei HTML sind
     * es nur Kandidaten, die erst nach erfolgreichem Abruf als Podcast-Feed übernommen werden.
     */
    fun importFile(uri: Uri) {
        viewModelScope.launch {
            _state.update { it.copy(working = true) }
            try {
                val text = withContext(Dispatchers.IO) { readText(uri) }
                var added = 0
                var skipped = 0
                if (text.contains("<opml", ignoreCase = true)) {
                    val result = PodcastOpml.parse(text)
                    skipped = result.skippedInsecure + result.skippedInvalid
                    for (feed in result.feeds) {
                        val ok = mutate { PodcastLogic.subscribe(it, Podcast(feed.url, feed.title.ifBlank { feed.url })) }
                        if (ok) added++ else skipped++
                    }
                } else {
                    for (candidate in PodcastHtml.parse(text)) {
                        try {
                            val parsed = PodcastRepository.loadFeed(candidate.url)
                            val ok = mutate(withEpisodes = true) {
                                PodcastLogic.subscribe(it, parsed.podcast)?.let { data -> PodcastLogic.applyFeed(data, parsed) }
                            }
                            if (ok) added++ else skipped++
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: IOException) {
                            skipped++
                        } catch (e: FeedParseException) {
                            skipped++
                        }
                    }
                }
                notice(PodcastNotice(NoticeKind.IMPORTED, a = added, b = skipped))
                if (added > 0) {
                    lastRefreshMillis = 0L
                    refreshAll()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                notice(PodcastNotice(NoticeKind.IMPORT_FAILED))
            } catch (e: FeedParseException) {
                notice(PodcastNotice(NoticeKind.IMPORT_FAILED))
            } finally {
                _state.update { it.copy(working = false) }
            }
        }
    }

    private fun readText(uri: Uri): String {
        val input = getApplication<Application>().contentResolver.openInputStream(uri) ?: throw IOException("Datei nicht lesbar")
        return input.use { stream ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val n = stream.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
                if (out.size() > MAX_IMPORT_BYTES) throw IOException("Datei zu groß")
            }
            String(out.toByteArray(), Charsets.UTF_8)
        }
    }

    /** Schreibt die Abos als OPML ([html] `false`) oder als HTML-Seite nach [uri]. */
    fun exportTo(uri: Uri, html: Boolean, title: String) {
        val subs = _state.value.data.subscriptions
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    val text = if (html) PodcastHtml.export(subs, title) else PodcastOpml.export(subs, title)
                    val out = getApplication<Application>().contentResolver.openOutputStream(uri, "wt") ?: return@withContext false
                    out.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                    true
                } catch (e: IOException) {
                    false
                }
            }
            notice(PodcastNotice(if (ok) NoticeKind.EXPORTED else NoticeKind.EXPORT_FAILED, a = subs.size))
        }
    }

    // endregion

    // region Favoriten, Gehört, Download ---------------------------------------------------------

    fun toggleFavorite(episode: Episode) {
        if (!mutate { PodcastLogic.toggleFavorite(it, episode) }) notice(PodcastNotice(NoticeKind.LIMIT))
    }

    fun setPlayed(episode: Episode, played: Boolean) {
        mutate { PodcastLogic.setPlayed(it, episode.id, played) }
    }

    fun download(episode: Episode) {
        if (downloadJobs.containsKey(episode.id) || _state.value.data.downloads.containsKey(episode.id)) return
        val name = PodcastLogic.downloadFileName(episode)
        downloadJobs[episode.id] = viewModelScope.launch {
            _state.update { it.copy(downloading = it.downloading + (episode.id to -1)) }
            try {
                PodcastRepository.download(episode, store.audioFile(name)) { percent ->
                    _state.update { it.copy(downloading = it.downloading + (episode.id to percent)) }
                }
                mutate { PodcastLogic.setDownload(it, episode, name) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                notice(PodcastNotice(NoticeKind.DOWNLOAD_FAILED))
            } finally {
                downloadJobs.remove(episode.id)
                _state.update { it.copy(downloading = it.downloading - episode.id) }
            }
        }
    }

    fun cancelDownload(episode: Episode) {
        downloadJobs[episode.id]?.cancel()
    }

    fun deleteDownload(episode: Episode) {
        val name = _state.value.data.downloads[episode.id] ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.audioFile(name).delete() }
            mutate { PodcastLogic.setDownload(it, episode, null) }
        }
    }

    // endregion

    // region Wiedergabe --------------------------------------------------------------------------

    private fun withController(action: (MediaController) -> Unit) {
        val ready = controller
        if (ready != null) {
            action(ready)
            return
        }
        pending.add(action)
        if (connecting) return
        connecting = true
        val context = getApplication<Application>()
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            connecting = false
            val connected = try {
                future.get()
            } catch (e: Exception) {
                null
            }
            if (connected == null) {
                pending.clear()
                notice(PodcastNotice(NoticeKind.PLAYBACK_FAILED))
            } else {
                controller = connected
                connected.addListener(playerListener)
                val actions = pending.toList()
                pending.clear()
                actions.forEach { it(connected) }
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun play(episode: Episode) {
        withController { c ->
            if (c.currentMediaItem?.mediaId == episode.id) {
                if (c.isPlaying) c.pause() else c.play()
                return@withController
            }
            savePositionNow()
            val data = _state.value.data
            val file = data.downloads[episode.id]?.let(store::audioFile)?.takeIf { it.isFile }
            val uri = if (file != null) Uri.fromFile(file) else Uri.parse(episode.audioUrl)
            val item = MediaItem.Builder()
                .setMediaId(episode.id)
                .setUri(uri)
                .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(episode.title)
                        .setArtist(episode.podcastTitle)
                        .setArtworkUri((episode.imageUrl ?: data.subscriptions.firstOrNull { it.feedUrl == episode.podcastUrl }?.imageUrl)?.let(Uri::parse))
                        .build(),
                )
                .build()
            c.setMediaItem(item, PodcastLogic.resumePosition(data, episode.id))
            c.setPlaybackSpeed(data.speed)
            c.prepare()
            c.play()
            _state.update { it.copy(player = PlayerUi(episode = episode, buffering = true)) }
        }
    }

    fun togglePlay() {
        controller?.let { if (it.isPlaying) it.pause() else it.play() }
    }

    fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs.coerceAtLeast(0))
        updatePlayer()
    }

    fun skip(deltaMs: Long) {
        val c = controller ?: return
        val target = (c.currentPosition + deltaMs).coerceAtLeast(0)
        val duration = c.duration
        c.seekTo(if (duration != C.TIME_UNSET) target.coerceAtMost(duration) else target)
        updatePlayer()
    }

    fun cycleSpeed() {
        val next = PodcastLogic.nextSpeed(_state.value.data.speed)
        mutate { it.copy(speed = next) }
        controller?.setPlaybackSpeed(next)
    }

    /** Beendet die Wiedergabe und blendet den Player aus. */
    fun stopPlayback() {
        savePositionNow()
        controller?.run {
            stop()
            clearMediaItems()
        }
        _state.update { it.copy(player = PlayerUi()) }
    }

    private fun updatePlayer() {
        val c = controller ?: return
        val id = c.currentMediaItem?.mediaId
        if (id == null) {
            _state.update { it.copy(player = PlayerUi()) }
            return
        }
        val episode = PodcastLogic.knownEpisodes(_state.value.data)[id] ?: _state.value.player.episode?.takeIf { it.id == id }
        val duration = c.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: ((episode?.durationSeconds ?: 0) * 1000L)
        _state.update {
            it.copy(
                player = PlayerUi(
                    episode = episode,
                    playing = c.isPlaying,
                    buffering = c.playbackState == Player.STATE_BUFFERING,
                    positionMs = c.currentPosition.coerceAtLeast(0L),
                    durationMs = duration,
                ),
            )
        }
    }

    private fun savePositionNow() {
        val c = controller ?: return
        val id = c.currentMediaItem?.mediaId ?: return
        val duration = c.duration.takeIf { it != C.TIME_UNSET } ?: 0L
        val position = c.currentPosition
        mutate { PodcastLogic.savePosition(it, id, position, duration) }
    }

    private fun markEndedAsPlayed() {
        val id = controller?.currentMediaItem?.mediaId ?: return
        mutate { PodcastLogic.setPlayed(it, id, true) }
    }

    override fun onCleared() {
        savePositionNow()
        controller?.removeListener(playerListener)
        controller?.release()
        controller = null
        super.onCleared()
    }

    // endregion

    private companion object {
        const val REFRESH_INTERVAL_MS = 15 * 60 * 1000L
        const val MAX_IMPORT_BYTES = 4 * 1024 * 1024
    }
}
