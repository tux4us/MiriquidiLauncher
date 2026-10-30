// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.calendar

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AgendaUiState(
    val enabled: Boolean = false,
    val permissionGranted: Boolean = false,
    /** `null`, solange nichts angezeigt werden soll oder die Termine noch nicht gelesen sind. */
    val view: AgendaView? = null,
)

/**
 * Zustand der Termin-Card auf der Startseite. Die Termine werden nur gelesen, wenn die Einstellung eingeschaltet
 * und `READ_CALENDAR` erteilt ist. [refresh] liest den Zieltag neu (beim Öffnen der App und einmal pro Minute,
 * solange die Startseite zu sehen ist), damit die Card um 20 Uhr und um Mitternacht umschaltet.
 */
class AgendaViewModel(application: Application) : AndroidViewModel(application) {

    private val store = AgendaStore(application)
    private val repository = CalendarRepository(application)

    private val _state = MutableStateFlow(AgendaUiState(permissionGranted = repository.hasPermission()))
    val state: StateFlow<AgendaUiState> = _state.asStateFlow()

    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            store.enabled.collect { enabled ->
                _state.update { it.copy(enabled = enabled) }
                refresh()
            }
        }
    }

    fun setEnabled(value: Boolean) {
        viewModelScope.launch { store.setEnabled(value) }
    }

    fun refresh() {
        loadJob?.cancel()
        val granted = repository.hasPermission()
        val enabled = _state.value.enabled
        if (!enabled || !granted) {
            _state.update { it.copy(permissionGranted = granted, view = null) }
            return
        }
        _state.update { it.copy(permissionGranted = true) }
        val zone = ZoneId.systemDefault()
        val now = ZonedDateTime.now(zone)
        val day = HomeAgenda.targetDay(now)
        loadJob = viewModelScope.launch {
            val events = repository.load(day, day, zone)
            _state.update { current -> if (current.enabled) current.copy(view = HomeAgenda.select(events, now)) else current }
        }
    }
}
