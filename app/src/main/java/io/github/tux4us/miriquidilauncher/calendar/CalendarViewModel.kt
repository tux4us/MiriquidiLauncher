// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.calendar

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.WeekFields
import java.util.Locale

/** Rückmeldungen des Editors. */
enum class CalendarNotice { SAVED, DELETED, SAVE_FAILED, DELETE_FAILED, LOAD_FAILED, CALENDAR_CREATED, CALENDAR_CREATE_FAILED }

data class CalendarEditorUi(
    val draft: EventDraft,
    val calendars: List<WritableCalendar>,
    val error: DraftError? = null,
    val saving: Boolean = false,
) {
    val isNew: Boolean get() = draft.eventId == null
}

data class CalendarUiState(
    val mode: CalendarMode = CalendarMode.Month,
    /** Der angezeigte Tag bzw. ein beliebiger Tag der angezeigten Woche oder des angezeigten Monats. */
    val date: LocalDate = LocalDate.now(),
    val events: List<CalendarEvent> = emptyList(),
    val permissionGranted: Boolean = false,
    val loading: Boolean = false,
    val writeGranted: Boolean = false,
    val editor: CalendarEditorUi? = null,
    val notice: CalendarNotice? = null,
)

/**
 * Zustand der Kalenderseite: Ansicht, angezeigtes Datum und die Termine des sichtbaren Bereichs. Die Termine
 * werden bei jeder Änderung von Ansicht oder Datum sowie beim Öffnen der Seite neu gelesen, weil sie in einer
 * anderen App geändert worden sein können.
 */
class CalendarViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = CalendarRepository(application)
    private val calendarEditor = CalendarEditor(application)
    private val prefs = application.getSharedPreferences("calendar_edit", android.content.Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(CalendarUiState(permissionGranted = repository.hasPermission(), writeGranted = calendarEditor.hasWritePermission()))
    val state: StateFlow<CalendarUiState> = _state.asStateFlow()

    private var loadJob: Job? = null

    /** Zeitzone und Wochenbeginn folgen den Systemeinstellungen. */
    val zone: ZoneId get() = ZoneId.systemDefault()
    val firstDayOfWeek: DayOfWeek get() = WeekFields.of(Locale.getDefault()).firstDayOfWeek

    fun setMode(mode: CalendarMode) {
        if (_state.value.mode == mode) return
        _state.update { it.copy(mode = mode) }
        reload()
    }

    fun step(delta: Int) {
        _state.update { it.copy(date = CalendarMath.shift(it.date, it.mode, delta)) }
        reload()
    }

    fun goToToday() {
        _state.update { it.copy(date = LocalDate.now()) }
        reload()
    }

    /** Zeigt [date] in der Tagesansicht (Tippen auf einen Tag im Monat oder in der Woche). */
    fun openDay(date: LocalDate) {
        _state.update { it.copy(date = date, mode = CalendarMode.Day) }
        reload()
    }

    /** Wird beim Öffnen der Seite und nach einer Berechtigungsänderung aufgerufen. */
    fun refresh() {
        _state.update { it.copy(permissionGranted = repository.hasPermission(), writeGranted = calendarEditor.hasWritePermission()) }
        reload()
    }

    private fun reload() {
        loadJob?.cancel()
        val snapshot = _state.value
        if (!snapshot.permissionGranted) {
            _state.update { it.copy(events = emptyList(), loading = false) }
            return
        }
        val days = CalendarMath.visibleDays(snapshot.date, snapshot.mode, firstDayOfWeek)
        _state.update { it.copy(loading = true) }
        loadJob = viewModelScope.launch {
            val events = repository.load(days.first(), days.last(), zone)
            _state.update { current ->
                // Ein Ergebnis für einen inzwischen verlassenen Bereich wird verworfen.
                if (current.mode == snapshot.mode && current.date == snapshot.date) {
                    current.copy(events = events, loading = false)
                } else {
                    current
                }
            }
        }
    }

    // region Termine anlegen, bearbeiten, löschen ------------------------------------------------

    fun dismissNotice() = _state.update { it.copy(notice = null) }

    /** Öffnet den Editor für einen neuen Termin am angezeigten Tag. Voraussetzung: Schreibrecht. */
    fun startNew() {
        viewModelScope.launch {
            val calendars = calendarEditor.writableCalendars()
            val zone = zone
            val draft = CalendarEdit.newDraft(_state.value.date, java.time.LocalTime.now(zone), zone, defaultCalendar(calendars))
            _state.update { it.copy(editor = CalendarEditorUi(draft, calendars)) }
        }
    }

    /** Öffnet den Editor für den Termin [event] (bei Serien die gesamte Serie). */
    fun startEdit(event: CalendarEvent) {
        viewModelScope.launch {
            val details = calendarEditor.details(event.id)
            if (details == null) {
                _state.update { it.copy(notice = CalendarNotice.LOAD_FAILED) }
                return@launch
            }
            val calendars = calendarEditor.writableCalendars()
            _state.update { it.copy(editor = CalendarEditorUi(CalendarEdit.draftFrom(details), calendars)) }
        }
    }

    fun editDraft(change: (EventDraft) -> EventDraft) {
        _state.update { s -> s.copy(editor = s.editor?.let { it.copy(draft = change(it.draft), error = null) }) }
    }

    fun cancelEdit() = _state.update { it.copy(editor = null) }

    fun save() {
        val editor = _state.value.editor ?: return
        if (editor.saving) return
        val error = CalendarEdit.validate(editor.draft)
        if (error != null) {
            _state.update { it.copy(editor = editor.copy(error = error)) }
            return
        }
        _state.update { it.copy(editor = editor.copy(saving = true, error = null)) }
        viewModelScope.launch {
            val id = calendarEditor.save(editor.draft)
            if (id == null) {
                _state.update { it.copy(editor = it.editor?.copy(saving = false), notice = CalendarNotice.SAVE_FAILED) }
            } else {
                prefs.edit().putLong(KEY_LAST_CALENDAR, editor.draft.calendarId).apply()
                _state.update { it.copy(editor = null, notice = CalendarNotice.SAVED) }
                reload()
            }
        }
    }

    fun delete(eventId: Long) {
        viewModelScope.launch {
            val ok = calendarEditor.delete(eventId)
            _state.update { it.copy(notice = if (ok) CalendarNotice.DELETED else CalendarNotice.DELETE_FAILED) }
            if (ok) reload()
        }
    }

    /** Legt einen lokalen Kalender an und wählt ihn im Editor aus. */
    fun createLocalCalendar(name: String) {
        viewModelScope.launch {
            val id = calendarEditor.createLocalCalendar(name.trim().ifEmpty { "MiriquidiLauncher" })
            if (id == null) {
                _state.update { it.copy(notice = CalendarNotice.CALENDAR_CREATE_FAILED) }
                return@launch
            }
            val calendars = calendarEditor.writableCalendars()
            _state.update { s ->
                s.copy(
                    editor = s.editor?.let { e -> e.copy(calendars = calendars, draft = e.draft.copy(calendarId = id)) },
                    notice = CalendarNotice.CALENDAR_CREATED,
                )
            }
            reload()
        }
    }

    /** Zuletzt benutzter Kalender, sonst der erste lokale, sonst der erste beschreibbare; -1 ohne Kalender. */
    private fun defaultCalendar(calendars: List<WritableCalendar>): Long {
        val last = prefs.getLong(KEY_LAST_CALENDAR, -1L)
        return calendars.firstOrNull { it.id == last }?.id
            ?: calendars.firstOrNull { it.accountType == android.provider.CalendarContract.ACCOUNT_TYPE_LOCAL }?.id
            ?: calendars.firstOrNull()?.id
            ?: -1L
    }

    // endregion

    private companion object {
        const val KEY_LAST_CALENDAR = "last_calendar"
    }
}
