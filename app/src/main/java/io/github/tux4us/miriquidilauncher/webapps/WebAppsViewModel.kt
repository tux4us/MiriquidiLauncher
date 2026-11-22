// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.webapps

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Was mit dem Symbol einer Webapp beim Speichern geschehen soll. */
sealed interface IconChange {
    data object Keep : IconChange
    data object Remove : IconChange
    data class Set(val bitmap: Bitmap) : IconChange
}

/** Zustand der Scope „Webapps“. Kein Netzwerkzugriff: die Adressen werden nur gespeichert und an den Browser übergeben. */
class WebAppsViewModel(application: Application) : AndroidViewModel(application) {
    private val store = WebAppStore(application)
    private val icons = WebAppIcons(application)

    val apps: StateFlow<List<WebApp>> =
        store.apps.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _iconRevision = MutableStateFlow(0)

    /** Zählt hoch, wenn ein Symbol geändert wurde, damit Kacheln mit derselben Kennung es neu laden. */
    val iconRevision: StateFlow<Int> = _iconRevision.asStateFlow()

    /**
     * Legt eine Webapp an ([id] `null`) oder ändert sie. Name und Adresse werden hier noch einmal geprüft; bei
     * ungültigen Angaben geschieht nichts. Bei einer Verknüpfung aus dem Browser (`pin`) sind nur Name und Symbol
     * änderbar; [url] und [customTab] werden dann ignoriert. [onDone] erhält `true`, wenn gespeichert wurde.
     */
    fun save(id: String?, name: String, url: String, customTab: Boolean, icon: IconChange, onDone: (Boolean) -> Unit = {}) {
        val cleanName = WebAppLogic.cleanName(name)
        if (cleanName == null) {
            onDone(false)
            return
        }
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) {
                val existing = id?.let { key -> store.current().firstOrNull { it.id == key } }
                if (id != null && existing == null) return@withContext false
                val pin = existing?.pin
                val checked = if (pin != null) "" else (WebAppLogic.checkUrl(url) as? UrlCheck.Ok)?.url ?: return@withContext false
                if (existing == null && !WebAppLogic.canAdd(store.current())) return@withContext false
                val appId = existing?.id ?: UUID.randomUUID().toString()
                val hasIcon = when (icon) {
                    IconChange.Keep -> existing?.hasIcon ?: false
                    IconChange.Remove -> {
                        icons.delete(appId)
                        false
                    }
                    is IconChange.Set -> icons.save(appId, icon.bitmap) || (existing?.hasIcon ?: false)
                }
                val app = WebApp(appId, cleanName, checked, if (pin != null) false else customTab, hasIcon, pin)
                store.update { list -> if (existing != null) WebAppLogic.replace(list, app) else WebAppLogic.add(list, app) }
                true
            }
            if (saved) _iconRevision.value++
            onDone(saved)
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val removed = store.current().firstOrNull { it.id == id }
                store.update { WebAppLogic.delete(it, id) }
                icons.delete(id)
                // Eine Verknüpfung aus dem Browser wird auch beim System freigegeben.
                removed?.pin?.let { WebAppOpener.unpin(getApplication<Application>(), it, store.current().mapNotNull { app -> app.pin }) }
            }
        }
    }

    fun move(id: String, delta: Int) {
        viewModelScope.launch { store.update { WebAppLogic.move(it, id, delta) } }
    }

    /** Liest das Bild hinter [uri] als zugeschnittenes Symbol (auf dem IO-Dispatcher); `null` bei Fehler. */
    suspend fun renderIcon(uri: android.net.Uri): Bitmap? = withContext(Dispatchers.IO) { icons.render(uri) }

    /** Lädt das gespeicherte Symbol (auf dem IO-Dispatcher) oder `null`. */
    suspend fun loadIcon(id: String): Bitmap? = withContext(Dispatchers.IO) { icons.load(id) }
}
