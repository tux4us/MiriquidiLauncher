// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.music

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
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class MusicTab { LIBRARY, RADIO, LISTS, EQUALIZER }

enum class LibraryMode { TRACKS, ALBUMS, ARTISTS }

enum class RadioMode { SAVED, SEARCH }

enum class MusicNoticeKind {
    NETWORK,
    LIMIT,
    PLAYBACK_FAILED,
    HTTP_BLOCKED,
    INVALID_URL,
    STATION_SAVED,
    PLAYLIST_NAME_TAKEN,
    ADDED_TO_PLAYLIST,
    QUEUED_NEXT,
    QUEUED_END,
    STATIONS_IMPORTED,
    STATIONS_EXPORTED,
    IMPORT_FAILED,
    EXPORT_FAILED,
}

data class MusicNotice(val kind: MusicNoticeKind, val a: Int = 0, val b: Int = 0)

/** Anzeigezustand des Players. Bei Radio ist [station] gesetzt, bei lokalen Liedern [track]. */
data class NowPlaying(
    val track: Track? = null,
    val station: Station? = null,
    /** Titel der Anzeige: Liedtitel, bei Radio der aktuelle Titel aus dem Stream (sonst der Sendername). */
    val title: String = "",
    val subtitle: String = "",
    val playing: Boolean = false,
    val buffering: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false,
) {
    val active: Boolean get() = track != null || station != null
    val live: Boolean get() = station != null
}

data class MusicUiState(
    val data: MusicData = MusicData(),
    val loaded: Boolean = false,
    val tab: MusicTab = MusicTab.LIBRARY,
    val hasAccess: Boolean = false,
    val libraryLoading: Boolean = false,
    val tracks: List<Track> = emptyList(),
    val libraryMode: LibraryMode = LibraryMode.TRACKS,
    val query: String = "",
    val openAlbum: Long? = null,
    val openArtist: String? = null,
    val openPlaylist: String? = null,
    val showFavorites: Boolean = false,
    val radioMode: RadioMode = RadioMode.SAVED,
    val radioQuery: String = "",
    val radioType: RadioSearch = RadioSearch.NAME,
    val radioHits: List<Station> = emptyList(),
    val radioSearching: Boolean = false,
    val radioSearched: Boolean = false,
    /** `true`, solange die große Player-Ansicht offen ist. */
    val playerOpen: Boolean = false,
    val player: NowPlaying = NowPlaying(),
    val notice: MusicNotice? = null,
)

/**
 * Zustand und Aktionen der Musik-Scope: lokale Mediathek, Radio-Sender, Listen, Equalizer und die Steuerung der
 * Wiedergabe über einen `MediaController` des [MusicService].
 */
@OptIn(UnstableApi::class)
class MusicViewModel(application: Application) : AndroidViewModel(application) {
    private val store = MusicStore(application)
    private val saveLock = Mutex()
    private val _state = MutableStateFlow(MusicUiState())
    val state: StateFlow<MusicUiState> = _state.asStateFlow()

    private var controller: MediaController? = null
    private var connecting = false
    private val pending = ArrayList<(MediaController) -> Unit>()
    private var tracksById: Map<Long, Track> = emptyMap()
    private var lastStation: Station? = null
    private var libraryJob: Job? = null
    private var searchJob: Job? = null
    private var eqSaveJob: Job? = null

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = updatePlayer()

        override fun onPlayerError(error: PlaybackException) {
            notice(MusicNotice(MusicNoticeKind.PLAYBACK_FAILED))
            updatePlayer()
        }
    }

    init {
        viewModelScope.launch {
            val data = store.load()
            MusicRuntime.setAllowHttp(data.allowHttp)
            MusicRuntime.setEq(data.eq)
            _state.update { it.copy(data = data, loaded = true) }
        }
        viewModelScope.launch {
            while (true) {
                delay(500)
                val c = controller ?: continue
                if (c.isPlaying) updatePlayer()
            }
        }
    }

    /** Liest die Daten neu aus dem Speicher, z. B. nach dem Einspielen eines Backups. */
    fun reloadFromStore() {
        viewModelScope.launch {
            saveLock.withLock { }
            val data = store.load()
            MusicRuntime.setAllowHttp(data.allowHttp)
            MusicRuntime.setEq(data.eq)
            _state.update { it.copy(data = data, loaded = true) }
        }
    }

    // region Anzeige und Navigation --------------------------------------------------------------

    /** Die Seite wird sichtbar: Mediathek neu lesen und die Verbindung zum Wiedergabedienst herstellen. */
    fun onActive() {
        withController { updatePlayer() }
        refreshLibrary()
    }

    fun selectTab(tab: MusicTab) = _state.update { it.copy(tab = tab, openAlbum = null, openArtist = null, openPlaylist = null, showFavorites = false) }
    fun setLibraryMode(mode: LibraryMode) = _state.update { it.copy(libraryMode = mode, openAlbum = null, openArtist = null) }
    fun setQuery(text: String) = _state.update { it.copy(query = text.take(80)) }
    fun openAlbum(id: Long) = _state.update { it.copy(openAlbum = id) }
    fun openArtist(name: String) = _state.update { it.copy(openArtist = name) }
    fun openPlaylist(id: String) = _state.update { it.copy(openPlaylist = id, showFavorites = false) }
    fun openFavorites() = _state.update { it.copy(showFavorites = true, openPlaylist = null) }
    fun setRadioMode(mode: RadioMode) = _state.update { it.copy(radioMode = mode) }
    fun setRadioQuery(text: String) = _state.update { it.copy(radioQuery = text.take(RadioBrowser.MAX_QUERY)) }
    fun setRadioType(type: RadioSearch) = _state.update { it.copy(radioType = type) }
    fun openPlayer() = _state.update { it.copy(playerOpen = it.player.active) }
    fun closePlayer() = _state.update { it.copy(playerOpen = false) }
    fun dismissNotice() = _state.update { it.copy(notice = null) }
    private fun notice(n: MusicNotice) = _state.update { it.copy(notice = n) }

    /** Geht eine Ebene zurück. Liefert `false`, wenn nichts mehr zu schließen ist. */
    fun back(): Boolean {
        val s = _state.value
        return when {
            s.playerOpen -> { _state.update { it.copy(playerOpen = false) }; true }
            s.openAlbum != null || s.openArtist != null -> { _state.update { it.copy(openAlbum = null, openArtist = null) }; true }
            s.openPlaylist != null || s.showFavorites -> { _state.update { it.copy(openPlaylist = null, showFavorites = false) }; true }
            else -> false
        }
    }

    // endregion

    // region Zustand speichern -------------------------------------------------------------------

    /** Ändert die Daten; `null` aus [change] bedeutet „abgelehnt“ (Limit) und liefert `false`. */
    private fun mutate(change: (MusicData) -> MusicData?): Boolean {
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
        if (!accepted) {
            notice(MusicNotice(MusicNoticeKind.LIMIT))
            return false
        }
        val snapshot = _state.value.data
        viewModelScope.launch { saveLock.withLock { store.save(snapshot) } }
        return true
    }

    // endregion

    // region Mediathek ---------------------------------------------------------------------------

    /** Prüft die Berechtigung und liest die Mediathek neu. */
    fun refreshLibrary() {
        val context = getApplication<Application>()
        val access = MusicLibrary.hasAccess(context)
        _state.update { it.copy(hasAccess = access) }
        if (!access) return
        libraryJob?.cancel()
        libraryJob = viewModelScope.launch {
            _state.update { it.copy(libraryLoading = true) }
            val tracks = withContext(Dispatchers.IO) { MusicLogic.sortByTitle(MusicLibrary.load(context)) }
            tracksById = tracks.associateBy { it.id }
            _state.update { it.copy(tracks = tracks, libraryLoading = false) }
            updatePlayer()
        }
    }

    // endregion

    // region Wiedergabe --------------------------------------------------------------------------

    private fun itemOf(track: Track): MediaItem = MediaItem.Builder()
        .setMediaId("t:${track.id}")
        .setUri(MusicLibrary.trackUri(track.id))
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(track.title)
                .setArtist(track.artist.ifEmpty { null })
                .setAlbumTitle(track.album.ifEmpty { null })
                .setArtworkUri(if (track.albumId > 0) MusicLibrary.albumArtUri(track.albumId) else null)
                .build(),
        )
        .build()

    /** Spielt [tracks] ab dem Lied an Position [index]; Zufall und Wiederholung kommen aus den gespeicherten Einstellungen. */
    fun playTracks(tracks: List<Track>, index: Int) {
        if (tracks.isEmpty()) return
        val (queue, start) = MusicLogic.window(tracks, index)
        val data = _state.value.data
        withController { c ->
            c.shuffleModeEnabled = data.shuffle
            c.repeatMode = data.repeat.toPlayerMode()
            c.setMediaItems(queue.map(::itemOf), start, 0L)
            c.prepare()
            c.play()
        }
    }

    /** Schaltet Zufall ein und spielt [tracks] ab einem zufälligen Lied. */
    fun playShuffled(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        mutate { it.copy(shuffle = true) }
        playTracks(tracks, tracks.indices.random())
    }

    /** Spielt ein einzelnes Lied weiter oder pausiert es, wenn es schon läuft. */
    fun toggleTrack(tracks: List<Track>, index: Int) {
        val current = _state.value.player
        val target = tracks.getOrNull(index) ?: return
        if (current.track?.id == target.id) togglePlay() else playTracks(tracks, index)
    }

    /** Reiht ein Lied als nächstes bzw. ans Ende ein; läuft nichts, startet es sofort. */
    fun enqueue(track: Track, next: Boolean) {
        withController { c ->
            if (c.mediaItemCount == 0 || c.currentMediaItem?.mediaId?.startsWith("t:") != true) {
                playTracks(listOf(track), 0)
                return@withController
            }
            if (next) c.addMediaItem(c.currentMediaItemIndex + 1, itemOf(track)) else c.addMediaItem(itemOf(track))
            notice(MusicNotice(if (next) MusicNoticeKind.QUEUED_NEXT else MusicNoticeKind.QUEUED_END))
        }
    }

    fun playStation(station: Station) {
        val data = _state.value.data
        val url = RadioBrowser.playableUrl(station.streamUrl, data.allowHttp)
        if (url == null) {
            notice(MusicNotice(if (station.streamUrl.startsWith("http://", ignoreCase = true)) MusicNoticeKind.HTTP_BLOCKED else MusicNoticeKind.INVALID_URL))
            return
        }
        lastStation = station
        withController { c ->
            if (c.currentMediaItem?.mediaId == "s:${station.id}") {
                if (c.isPlaying) c.pause() else c.play()
                return@withController
            }
            val item = MediaItem.Builder()
                .setMediaId("s:${station.id}")
                .setUri(Uri.parse(url))
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(station.name)
                        .setStation(station.name)
                        .setArtworkUri(if (data.showLogos) station.logoUrl?.let(Uri::parse) else null)
                        .build(),
                )
                .build()
            c.setMediaItem(item)
            c.prepare()
            c.play()
        }
    }

    fun togglePlay() {
        controller?.let { if (it.isPlaying) it.pause() else it.play() }
    }

    fun next() {
        controller?.seekToNextMediaItem()
    }

    /** Springt in den ersten Sekunden zum vorigen Lied, danach an den Anfang des Liedes (wie die Systemsteuerung). */
    fun previous() {
        controller?.seekToPrevious()
    }

    fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs.coerceAtLeast(0L))
    }

    fun toggleShuffle() {
        val enabled = !_state.value.data.shuffle
        mutate { it.copy(shuffle = enabled) }
        controller?.shuffleModeEnabled = enabled
    }

    fun cycleRepeat() {
        val mode = _state.value.data.repeat.next()
        mutate { it.copy(repeat = mode) }
        controller?.repeatMode = mode.toPlayerMode()
    }

    /** Beendet die Wiedergabe und entlädt die Warteschlange; die Benachrichtigung verschwindet. */
    fun stop() {
        controller?.let { c ->
            c.stop()
            c.clearMediaItems()
        }
        _state.update { it.copy(player = NowPlaying(), playerOpen = false) }
    }

    private fun RepeatMode.toPlayerMode(): Int = when (this) {
        RepeatMode.OFF -> Player.REPEAT_MODE_OFF
        RepeatMode.ALL -> Player.REPEAT_MODE_ALL
        RepeatMode.ONE -> Player.REPEAT_MODE_ONE
    }

    private fun stationFor(id: String): Station? =
        _state.value.data.stations.firstOrNull { it.id == id }
            ?: lastStation?.takeIf { it.id == id }
            ?: _state.value.radioHits.firstOrNull { it.id == id }

    private fun updatePlayer() {
        val c = controller ?: return
        val item = c.currentMediaItem
        if (item == null) {
            _state.update { if (it.player.active) it.copy(player = NowPlaying(), playerOpen = false) else it }
            return
        }
        val id = item.mediaId
        val meta = c.mediaMetadata
        // Ist das Element noch nicht in Mediathek oder Senderliste bekannt (z. B. nach Neustart der Oberfläche), dienen die
        // Metadaten des Elements als Ersatz, damit laufende Wiedergabe immer sichtbar und steuerbar bleibt.
        val track = if (id.startsWith("t:")) {
            id.substring(2).toLongOrNull()?.let { tid ->
                tracksById[tid] ?: Track(tid, meta.title?.toString().orEmpty(), meta.artist?.toString().orEmpty(), meta.albumTitle?.toString().orEmpty(), 0L, 0L)
            }
        } else {
            null
        }
        val station = if (id.startsWith("s:")) {
            val sid = id.substring(2)
            stationFor(sid) ?: Station(sid, meta.station?.toString().orEmpty(), item.localConfiguration?.uri?.toString().orEmpty())
        } else {
            null
        }
        if (track == null && station == null) return
        val streamTitle = meta.title?.toString()?.trim().orEmpty()
        val title: String
        val subtitle: String
        if (track != null) {
            title = track.title
            subtitle = listOf(track.artist, track.album).filter { it.isNotEmpty() }.joinToString(" · ")
        } else {
            val name = station!!.name
            val live = streamTitle.takeIf { it.isNotEmpty() && it != name }
            title = live ?: name
            subtitle = if (live != null) name else listOf(station.tags, station.country).filter { it.isNotEmpty() }.joinToString(" · ")
        }
        val duration = c.duration
        _state.update {
            it.copy(
                player = NowPlaying(
                    track = track,
                    station = station,
                    title = title,
                    subtitle = subtitle,
                    playing = c.isPlaying,
                    buffering = c.playbackState == Player.STATE_BUFFERING,
                    positionMs = c.currentPosition.coerceAtLeast(0L),
                    durationMs = if (duration == C.TIME_UNSET) track?.durationMs ?: 0L else duration.coerceAtLeast(0L),
                    hasNext = c.hasNextMediaItem(),
                    hasPrevious = c.hasPreviousMediaItem(),
                ),
            )
        }
    }

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
        val token = SessionToken(context, ComponentName(context, MusicService::class.java))
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
                notice(MusicNotice(MusicNoticeKind.PLAYBACK_FAILED))
            } else {
                controller = connected
                connected.addListener(playerListener)
                val actions = pending.toList()
                pending.clear()
                actions.forEach { it(connected) }
            }
        }, ContextCompat.getMainExecutor(context))
    }

    // endregion

    // region Favoriten und Listen ----------------------------------------------------------------

    fun toggleFavorite(track: Track) {
        mutate { MusicLogic.toggleFavorite(it, track.id) }
    }

    /** Legt eine Liste an. Liefert die Kennung oder `null` (Name leer oder vergeben, Limit). */
    fun createPlaylist(name: String, thenAdd: List<Long> = emptyList()): String? {
        if (name.isBlank()) return null
        val current = _state.value.data
        val result = MusicLogic.createPlaylist(current, name)
        if (result == null) {
            notice(MusicNotice(if (current.playlists.size >= MusicLogic.MAX_PLAYLISTS) MusicNoticeKind.LIMIT else MusicNoticeKind.PLAYLIST_NAME_TAKEN))
            return null
        }
        val (created, playlist) = result
        val next = if (thenAdd.isEmpty()) created else MusicLogic.addToPlaylist(created, playlist.id, thenAdd) ?: created
        return if (mutate { next }) playlist.id else null
    }

    fun renamePlaylist(id: String, name: String) {
        if (name.isBlank()) return
        val next = MusicLogic.renamePlaylist(_state.value.data, id, name)
        if (next == null) notice(MusicNotice(MusicNoticeKind.PLAYLIST_NAME_TAKEN)) else mutate { next }
    }

    fun deletePlaylist(id: String) {
        mutate { MusicLogic.deletePlaylist(it, id) }
        _state.update { if (it.openPlaylist == id) it.copy(openPlaylist = null) else it }
    }

    fun addToPlaylist(id: String, tracks: List<Track>) {
        val ok = mutate { MusicLogic.addToPlaylist(it, id, tracks.map(Track::id)) }
        if (ok) notice(MusicNotice(MusicNoticeKind.ADDED_TO_PLAYLIST, tracks.size))
    }

    fun removeFromPlaylist(id: String, trackId: Long) {
        mutate { MusicLogic.removeFromPlaylist(it, id, trackId) }
    }

    fun moveInPlaylist(id: String, trackId: Long, delta: Int) {
        mutate { MusicLogic.moveInPlaylist(it, id, trackId, delta) }
    }

    // endregion

    // region Radio -------------------------------------------------------------------------------

    /** Stimmt der Sendersuche im Verzeichnis zu; ab dann werden Suchanfragen gesendet. */
    fun setRadioEnabled(enabled: Boolean) {
        mutate { it.copy(radioEnabled = enabled) }
        if (!enabled) _state.update { it.copy(radioHits = emptyList(), radioSearched = false) }
    }

    fun setAllowHttp(allowed: Boolean) {
        MusicRuntime.setAllowHttp(allowed)
        mutate { it.copy(allowHttp = allowed) }
        _state.update { it.copy(radioHits = emptyList(), radioSearched = false) }
    }

    fun setShowLogos(show: Boolean) {
        mutate { it.copy(showLogos = show) }
    }

    /** Sucht Sender; mit [RadioSearch.TOP] erscheinen die beliebtesten Sender des Geräte-Landes (sonst weltweit). */
    fun searchStations(type: RadioSearch = _state.value.radioType) {
        val s = _state.value
        if (!s.data.radioEnabled) return
        val query = s.radioQuery
        if (type != RadioSearch.TOP && query.isBlank()) return
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _state.update { it.copy(radioSearching = true, radioType = if (type == RadioSearch.TOP) it.radioType else type) }
            try {
                val country = Locale.getDefault().country
                val hits = RadioRepository.search(type, query, s.data.allowHttp, country)
                _state.update { it.copy(radioHits = hits, radioSearched = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                notice(MusicNotice(MusicNoticeKind.NETWORK))
            } finally {
                _state.update { it.copy(radioSearching = false) }
            }
        }
    }

    fun saveStation(station: Station) {
        if (mutate { MusicLogic.addStation(it, station) }) notice(MusicNotice(MusicNoticeKind.STATION_SAVED))
    }

    fun removeStation(id: String) {
        mutate { MusicLogic.removeStation(it, id) }
    }

    fun renameStation(id: String, name: String) {
        mutate { MusicLogic.renameStation(it, id, name) }
    }

    /** Speichert einen selbst eingegebenen Sender. Liefert `true`, wenn die Adresse gültig war. */
    fun addManualStation(name: String, url: String): Boolean {
        val allowHttp = _state.value.data.allowHttp
        val clean = RadioBrowser.playableUrl(url, allowHttp)
        if (clean == null) {
            notice(MusicNotice(if (url.trim().startsWith("http://", ignoreCase = true) && RadioBrowser.playableUrl(url, true) != null) MusicNoticeKind.HTTP_BLOCKED else MusicNoticeKind.INVALID_URL))
            return false
        }
        val title = name.trim().ifEmpty { clean.substringAfter("://").substringBefore('/') }
        saveStation(Station(MusicLogic.manualStationId(clean), title, clean))
        return true
    }

    /** Liest eine Senderliste (JSON) aus [uri] und fügt neue Sender hinzu; vorhandene bleiben unverändert. */
    fun importStations(uri: Uri) {
        if (!_state.value.loaded) return
        viewModelScope.launch {
            val parsed = withContext(Dispatchers.IO) {
                try {
                    val input = getApplication<Application>().contentResolver.openInputStream(uri) ?: return@withContext null
                    input.use { stream ->
                        val out = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(16 * 1024)
                        while (true) {
                            val n = stream.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            if (out.size() > StationList.MAX_BYTES) return@withContext null
                        }
                        StationList.parse(String(out.toByteArray(), Charsets.UTF_8))
                    }
                } catch (e: IOException) {
                    null
                }
            }
            if (parsed == null) {
                notice(MusicNotice(MusicNoticeKind.IMPORT_FAILED))
                return@launch
            }
            var merged: StationList.Merge? = null
            _state.update { s ->
                val m = StationList.merge(s.data, parsed.stations)
                merged = m
                s.copy(data = m.data)
            }
            val result = merged ?: return@launch
            val snapshot = _state.value.data
            saveLock.withLock { store.save(snapshot) }
            notice(MusicNotice(MusicNoticeKind.STATIONS_IMPORTED, result.added, parsed.invalid + result.duplicates + result.overLimit))
        }
    }

    /** Schreibt alle gespeicherten Sender als JSON nach [uri]. */
    fun exportStations(uri: Uri) {
        val stations = MusicLogic.sortedStations(_state.value.data.stations)
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    val out = getApplication<Application>().contentResolver.openOutputStream(uri, "wt") ?: return@withContext false
                    out.use { it.write(StationList.export(stations).toByteArray(Charsets.UTF_8)) }
                    true
                } catch (e: IOException) {
                    false
                }
            }
            notice(MusicNotice(if (ok) MusicNoticeKind.STATIONS_EXPORTED else MusicNoticeKind.EXPORT_FAILED, stations.size))
        }
    }

    // endregion

    // region Equalizer ---------------------------------------------------------------------------

    /** Übernimmt neue Equalizer-Werte sofort in die Wiedergabe; gespeichert wird verzögert, damit Ziehen nicht schreibt. */
    fun setEq(settings: EqSettings) {
        val normalized = settings.normalized()
        MusicRuntime.setEq(normalized)
        _state.update { it.copy(data = it.data.copy(eq = normalized)) }
        eqSaveJob?.cancel()
        eqSaveJob = viewModelScope.launch {
            delay(400)
            val snapshot = _state.value.data
            saveLock.withLock { store.save(snapshot) }
        }
    }

    fun applyPreset(preset: EqPreset) = setEq(EqPresets.apply(_state.value.data.eq, preset))
    fun setEqEnabled(enabled: Boolean) = setEq(_state.value.data.eq.copy(enabled = enabled))
    fun setEqBand(index: Int, db: Float) = setEq(_state.value.data.eq.withBand(index, db))
    fun setEqBass(db: Float) = setEq(_state.value.data.eq.withBass(db))
    fun setEqPreamp(db: Float) = setEq(_state.value.data.eq.withPreamp(db))
    fun setEqProtect(protect: Boolean) = setEq(_state.value.data.eq.copy(protect = protect))
    fun resetEq() = setEq(_state.value.data.eq.reset())

    // endregion

    override fun onCleared() {
        eqSaveJob?.cancel()
        controller?.removeListener(playerListener)
        controller?.release()
        controller = null
        super.onCleared()
    }
}
