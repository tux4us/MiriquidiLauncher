// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Zustand der Einstellungen für die optionalen Cards der Startseite. */
class HomeCardsViewModel(application: Application) : AndroidViewModel(application) {
    private val store = HomeCardsStore(application)

    private val _settings = MutableStateFlow(HomeCardsSettings())
    val settings: StateFlow<HomeCardsSettings> = _settings.asStateFlow()

    init {
        viewModelScope.launch { store.settings.collect { _settings.value = it } }
    }

    fun setTomorrow(value: Boolean) = update { it.copy(tomorrow = value) }

    fun setMessages(value: Boolean) = update { it.copy(messages = value) }

    fun setMessagesText(value: Boolean) = update { it.copy(messagesText = value) }

    fun setWeather(value: Boolean) = update { it.copy(weather = value) }

    fun setMenuOpacity(value: Float) = update {
        it.copy(menuOpacity = value.coerceIn(HomeCardsSettings.MIN_MENU_OPACITY, HomeCardsSettings.MAX_MENU_OPACITY))
    }

    private fun update(change: (HomeCardsSettings) -> HomeCardsSettings) {
        val next = change(_settings.value)
        _settings.value = next
        viewModelScope.launch { store.save(next) }
    }
}
