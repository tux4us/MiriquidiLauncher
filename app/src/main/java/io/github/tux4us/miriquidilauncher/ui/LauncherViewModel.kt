// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import io.github.tux4us.miriquidilauncher.data.AppEntry
import io.github.tux4us.miriquidilauncher.data.AppGroup
import io.github.tux4us.miriquidilauncher.data.AppGroupCodec
import io.github.tux4us.miriquidilauncher.data.AppGroupsStore
import io.github.tux4us.miriquidilauncher.data.ContactWays
import io.github.tux4us.miriquidilauncher.data.ContactWaysRepository
import io.github.tux4us.miriquidilauncher.data.AppRepository
import io.github.tux4us.miriquidilauncher.data.ContactPhotos
import io.github.tux4us.miriquidilauncher.data.FavoriteContact
import io.github.tux4us.miriquidilauncher.data.FavoriteContactsStore
import io.github.tux4us.miriquidilauncher.data.FavoritesStore
import io.github.tux4us.miriquidilauncher.data.PickedContact
import io.github.tux4us.miriquidilauncher.search.ActionSearchProvider
import io.github.tux4us.miriquidilauncher.search.AppSearchProvider
import io.github.tux4us.miriquidilauncher.search.ContactSearchProvider
import io.github.tux4us.miriquidilauncher.search.SearchEngine
import io.github.tux4us.miriquidilauncher.search.SearchResult
import io.github.tux4us.miriquidilauncher.search.SettingsSearchProvider

/** Ergebnis einer Suche zusammen mit der Anfrage, zu der es gehört. */
data class SearchState(val query: String, val results: List<SearchResult>)

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class LauncherViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = AppRepository(application, viewModelScope)
    private val favoritesStore = FavoritesStore(application)
    private val contactsStore = FavoriteContactsStore(application)
    private val contactPhotos = ContactPhotos(application)
    private val groupsStore = AppGroupsStore(application)
    private val contactWaysRepository = ContactWaysRepository(application)
    private val searchEngine = SearchEngine(
        listOf(
            AppSearchProvider { repository.apps.value },
            ContactSearchProvider(application),
            SettingsSearchProvider(application),
            ActionSearchProvider(application),
        ),
    )

    val apps: StateFlow<List<AppEntry>> = repository.apps

    val favorites: StateFlow<List<AppEntry>> =
        combine(repository.apps, favoritesStore.favoriteKeys) { apps, keys ->
            val byKey = apps.associateBy { it.key }
            keys.mapNotNull { byKey[it] }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    val favoriteKeys: StateFlow<Set<String>> =
        favoritesStore.favoriteKeys
            .map { it.toSet() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptySet())

    /** Favoriten-Kontakte des Anruf-Schnellmenüs. */
    val favoriteContacts: StateFlow<List<FavoriteContact>> =
        contactsStore.contacts
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    /** App-Gruppen in der Reihenfolge der Anzeige. */
    val groups: StateFlow<List<AppGroup>> =
        groupsStore.groups
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val searchRefresh = MutableStateFlow(0)

    val searchState: StateFlow<SearchState> =
        combine(_query.debounce(SEARCH_DEBOUNCE_MS), repository.apps, searchRefresh) { query, _, _ -> query }
            .mapLatest { query -> SearchState(query, searchEngine.search(query)) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), SearchState("", emptyList()))

    private val _homePressed = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Wird ausgelöst, wenn die Home-Taste gedrückt wird, während der Launcher bereits sichtbar ist. */
    val homePressed: SharedFlow<Unit> = _homePressed.asSharedFlow()

    fun setQuery(value: String) {
        _query.value = value
    }

    /** Wiederholt die aktuelle Suche, z. B. nachdem die Kontaktberechtigung erteilt wurde. */
    fun refreshSearch() {
        searchRefresh.value += 1
    }

    fun onHomePressed() {
        _query.value = ""
        _homePressed.tryEmit(Unit)
    }

    fun toggleFavorite(entry: AppEntry) {
        viewModelScope.launch { favoritesStore.toggle(entry.key) }
    }

    /** Fügt den gewählten Kontakt hinzu und legt eine lokale Kopie seines Fotos an. */
    fun addFavoriteContact(picked: PickedContact) {
        viewModelScope.launch {
            contactPhotos.savePicked(picked.contact, picked.photoUris)
            contactsStore.add(picked.contact)
        }
    }

    fun removeFavoriteContact(contact: FavoriteContact) {
        viewModelScope.launch {
            contactsStore.remove(contact)
            contactPhotos.delete(contact)
        }
    }

    /** Verschiebt einen Favoriten im Schnellmenü; [delta] = -1 nach oben in der Liste (weiter vorn), +1 nach unten. */
    fun moveFavoriteApp(entry: AppEntry, delta: Int) {
        viewModelScope.launch { favoritesStore.move(entry.key, delta) }
    }

    fun moveFavoriteContact(contact: FavoriteContact, delta: Int) {
        viewModelScope.launch { contactsStore.move(contact, delta) }
    }

    /** Legt eine Gruppe an. Der Name wird vorher von der Oberfläche mit [AppGroupCodec.checkName] geprüft. */
    fun createGroup(name: String) {
        val id = UUID.randomUUID().toString()
        viewModelScope.launch { groupsStore.update { AppGroupCodec.add(it, id, name) } }
    }

    fun renameGroup(id: String, name: String) {
        viewModelScope.launch { groupsStore.update { AppGroupCodec.rename(it, id, name) } }
    }

    fun deleteGroup(id: String) {
        viewModelScope.launch { groupsStore.update { AppGroupCodec.delete(it, id) } }
    }

    /** Nimmt die App in die Gruppe auf oder entfernt sie wieder daraus. */
    fun toggleAppInGroup(id: String, entry: AppEntry) {
        viewModelScope.launch { groupsStore.update { AppGroupCodec.toggleApp(it, id, entry.key) } }
    }

    fun moveAppInGroup(id: String, entry: AppEntry, delta: Int) {
        viewModelScope.launch { groupsStore.update { AppGroupCodec.moveApp(it, id, entry.key, delta) } }
    }

    fun moveGroup(id: String, delta: Int) {
        viewModelScope.launch { groupsStore.update { AppGroupCodec.moveGroup(it, id, delta) } }
    }

    /**
     * Kontaktwege eines Favoriten (Anruf, SMS, E-Mail, Messenger). Liest das Adressbuch, wenn `READ_CONTACTS`
     * erteilt ist, sonst gibt es nur die gespeicherte Nummer.
     */
    suspend fun loadContactWays(contact: FavoriteContact): ContactWays {
        val installed = repository.apps.value.associate { it.packageName to it.label }
        return withContext(Dispatchers.IO) { contactWaysRepository.load(contact, installed) }
    }

    fun launchApp(entry: AppEntry): Boolean = repository.launch(entry)

    fun openAppInfo(entry: AppEntry): Boolean = repository.openAppInfo(entry)

    fun canUninstall(entry: AppEntry): Boolean = repository.canUninstall(entry)

    fun uninstall(entry: AppEntry): Boolean = repository.uninstall(entry)

    suspend fun loadIcon(entry: AppEntry) = repository.loadIcon(entry)

    /** Foto eines Favoriten-Kontakts oder `null`. */
    suspend fun loadContactPhoto(contact: FavoriteContact) = contactPhotos.load(contact)

    /** Foto zu einer Foto-Adresse aus der Kontaktsuche oder `null`. */
    suspend fun loadContactPhoto(uri: android.net.Uri) = contactPhotos.load(uri)

    override fun onCleared() {
        repository.close()
        super.onCleared()
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 80L
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
