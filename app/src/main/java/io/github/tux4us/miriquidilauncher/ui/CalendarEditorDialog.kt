// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import io.github.tux4us.miriquidilauncher.R
import io.github.tux4us.miriquidilauncher.calendar.CalendarEdit
import io.github.tux4us.miriquidilauncher.calendar.CalendarEditorUi
import io.github.tux4us.miriquidilauncher.calendar.CalendarViewModel
import io.github.tux4us.miriquidilauncher.calendar.DraftError
import io.github.tux4us.miriquidilauncher.calendar.Repeat
import io.github.tux4us.miriquidilauncher.calendar.WritableCalendar
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

private val EditorDialogColor = Color(0xFF141A22)
private val EditorError = Color(0xFFFFB4B4)

private fun pickDate(context: Context, initial: LocalDate, onPicked: (LocalDate) -> Unit) {
    DatePickerDialog(context, { _, y, m, d -> onPicked(LocalDate.of(y, m + 1, d)) }, initial.year, initial.monthValue - 1, initial.dayOfMonth).show()
}

private fun pickTime(context: Context, initial: LocalTime, onPicked: (LocalTime) -> Unit) {
    TimePickerDialog(context, { _, h, min -> onPicked(LocalTime.of(h, min)) }, initial.hour, initial.minute, DateFormat.is24HourFormat(context)).show()
}

/** Dialog zum Anlegen und Bearbeiten eines Termins im Gerätekalender. */
@Composable
internal fun CalendarEditorDialog(ui: CalendarEditorUi, viewModel: CalendarViewModel) {
    val context = LocalContext.current
    val locale = remember { Locale.getDefault() }
    val dateFormat = remember(locale) { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale) }
    val timeFormat = remember(locale) { DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale) }
    val d = ui.draft

    AlertDialog(
        onDismissRequest = viewModel::cancelEdit,
        containerColor = EditorDialogColor,
        titleContentColor = WeatherText,
        textContentColor = WeatherText,
        title = { Text(stringResource(if (ui.isNew) R.string.calendar_editor_new else R.string.calendar_editor_edit)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = d.title,
                    onValueChange = { v -> viewModel.editDraft { it.copy(title = v.take(CalendarEdit.MAX_TITLE)) } },
                    singleLine = true,
                    label = { Text(stringResource(R.string.calendar_field_title)) },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    colors = filesFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                CalendarPicker(ui, viewModel)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.calendar_all_day), modifier = Modifier.weight(1f))
                    Switch(checked = d.allDay, onCheckedChange = { v -> viewModel.editDraft { CalendarEdit.withEndFixed(it.copy(allDay = v)) } })
                }
                Text(stringResource(R.string.calendar_field_start), color = WeatherTextMuted, style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EditorChip(dateFormat.format(d.startDate)) {
                        pickDate(context, d.startDate) { date -> viewModel.editDraft { CalendarEdit.moveStart(it, date, it.startTime) } }
                    }
                    if (!d.allDay) {
                        EditorChip(timeFormat.format(d.startTime)) {
                            pickTime(context, d.startTime) { time -> viewModel.editDraft { CalendarEdit.moveStart(it, it.startDate, time) } }
                        }
                    }
                }
                Text(stringResource(R.string.calendar_field_end), color = WeatherTextMuted, style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EditorChip(dateFormat.format(d.endDate)) {
                        pickDate(context, d.endDate) { date -> viewModel.editDraft { it.copy(endDate = date) } }
                    }
                    if (!d.allDay) {
                        EditorChip(timeFormat.format(d.endTime)) {
                            pickTime(context, d.endTime) { time -> viewModel.editDraft { it.copy(endTime = time) } }
                        }
                    }
                }
                RepeatPicker(ui, viewModel)
                ReminderPicker(ui, viewModel)
                OutlinedTextField(
                    value = d.location,
                    onValueChange = { v -> viewModel.editDraft { it.copy(location = v.take(CalendarEdit.MAX_TEXT)) } },
                    singleLine = true,
                    label = { Text(stringResource(R.string.calendar_field_location)) },
                    colors = filesFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = d.description,
                    onValueChange = { v -> viewModel.editDraft { it.copy(description = v.take(CalendarEdit.MAX_TEXT)) } },
                    minLines = 3,
                    maxLines = 6,
                    label = { Text(stringResource(R.string.calendar_field_description)) },
                    colors = filesFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (!ui.isNew && d.repeat != Repeat.NONE) {
                    Text(stringResource(R.string.calendar_series_hint), color = WeatherTextMuted, style = MaterialTheme.typography.bodySmall)
                }
                ui.error?.let {
                    Text(
                        stringResource(if (it == DraftError.END_BEFORE_START) R.string.calendar_error_end else R.string.calendar_error_no_calendar),
                        color = EditorError,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = viewModel::save, enabled = !ui.saving, colors = ButtonDefaults.textButtonColors(contentColor = WeatherText)) {
                Text(stringResource(R.string.calendar_save))
            }
        },
        dismissButton = {
            TextButton(onClick = viewModel::cancelEdit, colors = ButtonDefaults.textButtonColors(contentColor = WeatherTextMuted)) {
                Text(stringResource(R.string.calendar_cancel))
            }
        },
    )
}

@Composable
private fun EditorChip(text: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, colors = ButtonDefaults.textButtonColors(containerColor = Color(0x33FFFFFF), contentColor = WeatherText)) { Text(text) }
}

@Composable
private fun CalendarPicker(ui: CalendarEditorUi, viewModel: CalendarViewModel) {
    var open by remember { mutableStateOf(false) }
    val selected = ui.calendars.firstOrNull { it.id == ui.draft.calendarId }
    Text(stringResource(R.string.calendar_field_calendar), color = WeatherTextMuted, style = MaterialTheme.typography.labelMedium)
    if (ui.calendars.isEmpty()) {
        Text(stringResource(R.string.calendar_none_writable), style = MaterialTheme.typography.bodySmall)
        EditorChip(stringResource(R.string.calendar_create_local)) { viewModel.createLocalCalendar("MiriquidiLauncher") }
        return
    }
    Column {
        EditorChip(selected?.let { calendarLabel(it) } ?: stringResource(R.string.calendar_choose)) { if (ui.isNew) open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ui.calendars.forEach { cal ->
                DropdownMenuItem(
                    text = { Text(calendarLabel(cal)) },
                    onClick = { open = false; viewModel.editDraft { it.copy(calendarId = cal.id) } },
                )
            }
        }
        if (!ui.isNew) Text(stringResource(R.string.calendar_fixed_calendar), color = WeatherTextMuted, style = MaterialTheme.typography.bodySmall)
    }
}

private fun calendarLabel(c: WritableCalendar): String =
    if (c.account.isBlank() || c.account == c.name) c.name else "${c.name} (${c.account})"

@Composable
private fun RepeatPicker(ui: CalendarEditorUi, viewModel: CalendarViewModel) {
    var open by remember { mutableStateOf(false) }
    val options = buildList {
        add(Repeat.NONE); add(Repeat.DAILY); add(Repeat.WEEKLY); add(Repeat.MONTHLY); add(Repeat.YEARLY)
        if (ui.draft.repeat == Repeat.KEEP) add(Repeat.KEEP)
    }
    Text(stringResource(R.string.calendar_field_repeat), color = WeatherTextMuted, style = MaterialTheme.typography.labelMedium)
    Column {
        EditorChip(repeatLabel(ui.draft.repeat)) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(repeatLabel(option)) }, onClick = { open = false; viewModel.editDraft { it.copy(repeat = option) } })
            }
        }
    }
}

@Composable
private fun repeatLabel(repeat: Repeat): String = stringResource(
    when (repeat) {
        Repeat.NONE -> R.string.calendar_repeat_none
        Repeat.DAILY -> R.string.calendar_repeat_daily
        Repeat.WEEKLY -> R.string.calendar_repeat_weekly
        Repeat.MONTHLY -> R.string.calendar_repeat_monthly
        Repeat.YEARLY -> R.string.calendar_repeat_yearly
        Repeat.KEEP -> R.string.calendar_repeat_keep
    },
)

@Composable
private fun ReminderPicker(ui: CalendarEditorUi, viewModel: CalendarViewModel) {
    var open by remember { mutableStateOf(false) }
    val choices = remember(ui.draft.reminderMinutes) {
        val current = ui.draft.reminderMinutes
        if (current != null && current !in CalendarEdit.REMINDER_CHOICES) CalendarEdit.REMINDER_CHOICES + current else CalendarEdit.REMINDER_CHOICES
    }
    Text(stringResource(R.string.calendar_field_reminder), color = WeatherTextMuted, style = MaterialTheme.typography.labelMedium)
    Column {
        EditorChip(reminderLabel(ui.draft.reminderMinutes)) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            choices.forEach { minutes ->
                DropdownMenuItem(text = { Text(reminderLabel(minutes)) }, onClick = { open = false; viewModel.editDraft { it.copy(reminderMinutes = minutes) } })
            }
        }
    }
}

@Composable
private fun reminderLabel(minutes: Int?): String = when {
    minutes == null -> stringResource(R.string.calendar_reminder_none)
    minutes == 0 -> stringResource(R.string.calendar_reminder_at_start)
    minutes == 1440 -> stringResource(R.string.calendar_reminder_day)
    minutes == 60 -> stringResource(R.string.calendar_reminder_hour)
    minutes == 120 -> stringResource(R.string.calendar_reminder_two_hours)
    else -> stringResource(R.string.calendar_reminder_minutes, minutes)
}
