// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.ui

import android.app.DatePickerDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.provider.CalendarContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.tux4us.miriquidilauncher.R
import io.github.tux4us.miriquidilauncher.dogs.DogAge
import io.github.tux4us.miriquidilauncher.dogs.DogLogic
import io.github.tux4us.miriquidilauncher.dogs.DogProfile
import io.github.tux4us.miriquidilauncher.dogs.DogSex
import io.github.tux4us.miriquidilauncher.dogs.DogViewModel
import io.github.tux4us.miriquidilauncher.dogs.DueEntry
import io.github.tux4us.miriquidilauncher.dogs.DueState
import io.github.tux4us.miriquidilauncher.dogs.EmergencyField
import io.github.tux4us.miriquidilauncher.dogs.EmergencyLabels
import io.github.tux4us.miriquidilauncher.dogs.HealthItem
import io.github.tux4us.miriquidilauncher.dogs.HealthKind
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

private val DogDialogColor = Color(0xFF141A22)

/** Scope „Hunde“: Profile (mehrere Hunde), Fälligkeiten, Notfallkarte mit QR-Code und Import aus der HundeWildnis-App. */
@Composable
fun DogsScreen(viewModel: DogViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val today = remember(state.data) { LocalDate.now() }
    val dateFormat = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.getDefault()) }
    var editDog by remember { mutableStateOf<DogProfile?>(null) }
    var editItem by remember { mutableStateOf<Pair<String, HealthItem>?>(null) }
    var confirmDelete by remember { mutableStateOf<DogProfile?>(null) }
    var cardDog by remember { mutableStateOf<DogProfile?>(null) }
    var importHint by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importHundeWildnis(uri)
    }
    val selected = state.selected

    ScopePage(title = stringResource(R.string.scope_dogs), modifier = modifier) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = ScopeBottomClearance),
        ) {
            if (state.data.dogs.isNotEmpty()) {
                item {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.data.dogs.forEach { d -> DogChip(d.name, d.id == selected?.id) { viewModel.select(d.id) } }
                    }
                }
            } else if (state.loaded) {
                item { ScopeEmptyHint(stringResource(R.string.dogs_empty)) }
            }
            if (selected != null) {
                item { ProfileCard(selected, today, onEdit = { editDog = selected }, onCard = { cardDog = selected }, onDelete = { confirmDelete = selected }) }
                item {
                    HealthCard(
                        dog = selected, today = today, dateFormat = dateFormat,
                        onAdd = { editItem = selected.id to HealthItem(DogLogic.newId(), HealthKind.VACCINATION, "") },
                        onEdit = { editItem = selected.id to it },
                        onDone = { viewModel.markDone(selected.id, it.id) },
                        onDelete = { viewModel.deleteItem(selected.id, it.id) },
                        onCalendar = { entry -> if (!addToCalendar(context, entry)) android.widget.Toast.makeText(context, R.string.dogs_calendar_failed, android.widget.Toast.LENGTH_SHORT).show() },
                    )
                }
            }
            item {
                SectionCard(null) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PodcastButton(stringResource(R.string.dogs_add), { editDog = newDog() }, enabled = state.data.dogs.size < DogLogic.MAX_DOGS)
                        PodcastButton(stringResource(R.string.dogs_import), { importHint = true }, enabled = !state.importing)
                    }
                    if (state.importing) Text(stringResource(R.string.dogs_importing), style = MaterialTheme.typography.bodySmall, color = WeatherTextMuted)
                    state.importResult?.let { r ->
                        Text(
                            if (r.error) stringResource(R.string.dogs_import_error) else stringResource(R.string.dogs_import_done, r.added, r.skipped),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }

    editDog?.let { dog ->
        DogEditDialog(dog, onSave = { viewModel.saveDog(it); editDog = null }, onDismiss = { editDog = null })
    }
    editItem?.let { (dogId, item) ->
        ItemEditDialog(item, dateFormat, onSave = { viewModel.saveItem(dogId, it); editItem = null }, onDismiss = { editItem = null })
    }
    confirmDelete?.let { dog ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            containerColor = DogDialogColor, titleContentColor = WeatherText, textContentColor = WeatherTextMuted,
            title = { Text(stringResource(R.string.dogs_delete_title, dog.name)) },
            text = { Text(stringResource(R.string.dogs_delete_text)) },
            confirmButton = { PodcastTextButton(stringResource(R.string.dogs_delete)) { viewModel.deleteDog(dog.id); confirmDelete = null } },
            dismissButton = { PodcastTextButton(stringResource(R.string.dogs_cancel)) { confirmDelete = null } },
        )
    }
    cardDog?.let { dog -> EmergencyDialog(dog, onDismiss = { cardDog = null }) }
    if (importHint) {
        AlertDialog(
            onDismissRequest = { importHint = false },
            containerColor = DogDialogColor, titleContentColor = WeatherText, textContentColor = WeatherTextMuted,
            title = { Text(stringResource(R.string.dogs_import)) },
            text = { Text(stringResource(R.string.dogs_import_hint)) },
            confirmButton = {
                PodcastTextButton(stringResource(R.string.dogs_import)) {
                    importHint = false
                    picker.launch(arrayOf("*/*"))
                }
            },
            dismissButton = { PodcastTextButton(stringResource(R.string.dogs_cancel)) { importHint = false } },
        )
    }
}

private fun newDog() = DogProfile(id = DogLogic.newId(), name = "")

@Composable
private fun DogChip(name: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) Color(0x66FFFFFF) else Color(0x22FFFFFF))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(name, color = WeatherText, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
    }
}

@Composable
private fun ageText(age: DogAge?, approx: Boolean): String? = when {
    age == null -> null
    approx -> stringResource(R.string.dogs_age_approx, age.years, age.months)
    else -> stringResource(R.string.dogs_age, age.years, age.months)
}

@Composable
private fun ProfileCard(dog: DogProfile, today: LocalDate, onEdit: () -> Unit, onCard: () -> Unit, onDelete: () -> Unit) {
    val sexNames = stringArrayResource(R.array.dogs_sex)
    val parts = listOfNotNull(
        dog.breed.takeIf { it.isNotBlank() },
        sexNames[dog.sex.ordinal].takeIf { dog.sex != DogSex.UNKNOWN },
        ageText(DogLogic.age(dog.birthDate, today), dog.birthApprox),
        dog.weightKg?.let { stringResource(R.string.dogs_weight, oneDecimal(it)) },
        dog.color.takeIf { it.isNotBlank() },
    )
    SectionCard(dog.name) {
        if (parts.isNotEmpty()) Text(parts.joinToString(" · "))
        if (dog.chip.isNotBlank()) Text("${stringResource(R.string.dogs_label_chip)}: ${dog.chip}", style = MaterialTheme.typography.bodySmall, color = WeatherTextMuted)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PodcastButton(stringResource(R.string.dogs_emergency_open), onCard)
            OutlineAction(stringResource(R.string.dogs_edit), true, onEdit)
            OutlineAction(stringResource(R.string.dogs_delete), true, onDelete)
        }
    }
}

@Composable
private fun HealthCard(
    dog: DogProfile,
    today: LocalDate,
    dateFormat: DateTimeFormatter,
    onAdd: () -> Unit,
    onEdit: (HealthItem) -> Unit,
    onDone: (HealthItem) -> Unit,
    onDelete: (HealthItem) -> Unit,
    onCalendar: (DueEntry) -> Unit,
) {
    val entries = remember(dog, today) { DogLogic.dueEntries(dog, today).associateBy { it.item.id } }
    val sorted = remember(dog, today) {
        dog.health.sortedWith(compareBy({ entries[it.id]?.due ?: LocalDate.MAX }, { it.title.lowercase() }))
    }
    SectionCard(stringResource(R.string.dogs_section_health)) {
        if (sorted.isEmpty()) Text(stringResource(R.string.dogs_health_empty), color = WeatherTextMuted)
        sorted.forEach { item ->
            val entry = entries[item.id]
            val statusText = when {
                entry == null -> stringResource(R.string.dogs_due_none)
                entry.daysUntil < 0 -> stringResource(R.string.dogs_due_overdue, entry.due.format(dateFormat), -entry.daysUntil)
                entry.daysUntil == 0L -> stringResource(R.string.dogs_due_today)
                else -> stringResource(R.string.dogs_due_in, entry.due.format(dateFormat), entry.daysUntil)
            }
            val color = when (entry?.state) {
                DueState.OVERDUE -> Color(0xFFE53935)
                DueState.SOON -> Color(0xFFFFB300)
                DueState.LATER -> Color(0xFF66BB6A)
                null -> Color(0xFF90A4AE)
            }
            LevelDot(color, statusText) {
                Column {
                    Text(item.title, fontWeight = FontWeight.SemiBold)
                    Text(statusText, style = MaterialTheme.typography.bodySmall)
                    item.lastDate?.let {
                        Text(stringResource(R.string.dogs_last, it.format(dateFormat)), style = MaterialTheme.typography.bodySmall, color = WeatherTextMuted)
                    }
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlineAction(stringResource(R.string.dogs_done), true) { onDone(item) }
                if (entry != null) OutlineAction(stringResource(R.string.dogs_to_calendar), true) { onCalendar(entry) }
                OutlineAction(stringResource(R.string.dogs_edit), true) { onEdit(item) }
                OutlineAction(stringResource(R.string.dogs_delete), true) { onDelete(item) }
            }
            Spacer(Modifier.height(4.dp))
        }
        PodcastButton(stringResource(R.string.dogs_item_add), onAdd, enabled = dog.health.size < DogLogic.MAX_ITEMS)
    }
}

/** Legt über die Kalender-App einen ganztägigen Termin an; es ist keine Kalender-Berechtigung nötig. */
private fun addToCalendar(context: Context, entry: DueEntry): Boolean {
    val millis = entry.due.atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli()
    val intent = Intent(Intent.ACTION_INSERT)
        .setData(CalendarContract.Events.CONTENT_URI)
        .putExtra(CalendarContract.Events.TITLE, DogLogic.calendarTitle(entry))
        .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, true)
        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, millis)
        .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, millis)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    }
}

// region Dialoge -------------------------------------------------------------------------------

@Composable
private fun dogLabels() = EmergencyLabels(
    title = stringResource(R.string.dogs_emergency_header),
    breed = stringResource(R.string.dogs_label_breed),
    owner = stringResource(R.string.dogs_label_owner),
    contact = stringResource(R.string.dogs_label_contact),
    chip = stringResource(R.string.dogs_label_chip),
    vet = stringResource(R.string.dogs_label_vet),
    medical = stringResource(R.string.dogs_label_medical),
    insurance = stringResource(R.string.dogs_label_insurance),
)

@Composable
private fun EmergencyDialog(dog: DogProfile, onDismiss: () -> Unit) {
    val text = DogLogic.emergencyText(dog, dogLabels())
    val bitmap: Bitmap? = remember(text) { runCatching { renderQr(text) }.getOrNull() }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DogDialogColor, titleContentColor = WeatherText, textContentColor = WeatherTextMuted,
        title = { Text(stringResource(R.string.dogs_emergency_title, dog.name)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = stringResource(R.string.dogs_emergency_open),
                        modifier = Modifier.fillMaxWidth().background(Color.White),
                    )
                }
                Text(text, color = WeatherText, style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.dogs_emergency_hint), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { PodcastTextButton(stringResource(R.string.dogs_close), onDismiss) },
    )
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, label: String, keyboard: KeyboardType = KeyboardType.Text, singleLine: Boolean = true) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = singleLine,
        keyboardOptions = KeyboardOptions(
            keyboardType = keyboard,
            capitalization = if (keyboard == KeyboardType.Text) KeyboardCapitalization.Sentences else KeyboardCapitalization.None,
        ),
        colors = filesFieldColors(), modifier = Modifier.fillMaxWidth(),
    )
}

private fun pickDate(context: Context, initial: LocalDate?, onPicked: (LocalDate) -> Unit) {
    val d = initial ?: LocalDate.now()
    DatePickerDialog(context, { _, y, m, day -> onPicked(LocalDate.of(y, m + 1, day)) }, d.year, d.monthValue - 1, d.dayOfMonth).show()
}

@Composable
private fun DogEditDialog(initial: DogProfile, onSave: (DogProfile) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val dateFormat = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.getDefault()) }
    val sexNames = stringArrayResource(R.array.dogs_sex)
    val neuteredNames = stringArrayResource(R.array.dogs_neutered)
    var dog by remember { mutableStateOf(initial) }
    var weight by remember { mutableStateOf(initial.weightKg?.let { oneDecimal(it) }.orEmpty()) }
    val fieldLabels = listOf(
        EmergencyField.BREED to R.string.dogs_label_breed, EmergencyField.OWNER to R.string.dogs_label_owner,
        EmergencyField.CONTACT to R.string.dogs_label_contact, EmergencyField.CHIP to R.string.dogs_label_chip,
        EmergencyField.VET to R.string.dogs_label_vet, EmergencyField.MEDICAL to R.string.dogs_label_medical,
        EmergencyField.INSURANCE to R.string.dogs_label_insurance,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DogDialogColor, titleContentColor = WeatherText, textContentColor = WeatherText,
        title = { Text(stringResource(if (initial.name.isBlank()) R.string.dogs_add else R.string.dogs_edit)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(dog.name, { dog = dog.copy(name = it) }, stringResource(R.string.dogs_field_name))
                Field(dog.breed, { dog = dog.copy(breed = it) }, stringResource(R.string.dogs_field_breed))
                Field(dog.color, { dog = dog.copy(color = it) }, stringResource(R.string.dogs_field_color))
                OutlineAction(
                    dog.birthDate?.let { stringResource(R.string.dogs_field_birth, it.format(dateFormat)) } ?: stringResource(R.string.dogs_field_birth_none),
                    true,
                ) { pickDate(context, dog.birthDate) { dog = dog.copy(birthDate = it, birthApprox = false) } }
                if (dog.birthDate != null) PodcastTextButton(stringResource(R.string.dogs_clear)) { dog = dog.copy(birthDate = null, birthApprox = false) }
                OutlineAction(stringResource(R.string.dogs_field_sex, sexNames[dog.sex.ordinal]), true) {
                    dog = dog.copy(sex = DogSex.entries[(dog.sex.ordinal + 1) % DogSex.entries.size])
                }
                val neuteredIndex = when (dog.neutered) { null -> 0; true -> 1; false -> 2 }
                OutlineAction(stringResource(R.string.dogs_field_neutered, neuteredNames[neuteredIndex]), true) {
                    dog = dog.copy(neutered = when (neuteredIndex) { 0 -> true; 1 -> false; else -> null })
                }
                Field(weight, { weight = it }, stringResource(R.string.dogs_field_weight), KeyboardType.Decimal)
                Field(dog.chip, { dog = dog.copy(chip = it) }, stringResource(R.string.dogs_field_chip))
                Field(dog.ownerName, { dog = dog.copy(ownerName = it) }, stringResource(R.string.dogs_field_owner))
                Field(dog.ownerPhone, { dog = dog.copy(ownerPhone = it) }, stringResource(R.string.dogs_field_owner_phone), KeyboardType.Phone)
                Field(dog.emergencyContact, { dog = dog.copy(emergencyContact = it) }, stringResource(R.string.dogs_field_contact))
                Field(dog.vetName, { dog = dog.copy(vetName = it) }, stringResource(R.string.dogs_field_vet))
                Field(dog.vetPhone, { dog = dog.copy(vetPhone = it) }, stringResource(R.string.dogs_field_vet_phone), KeyboardType.Phone)
                Field(dog.insurance, { dog = dog.copy(insurance = it) }, stringResource(R.string.dogs_field_insurance))
                Field(dog.medical, { dog = dog.copy(medical = it) }, stringResource(R.string.dogs_field_medical), singleLine = false)
                Text(stringResource(R.string.dogs_field_card_title), color = WeatherTextMuted, style = MaterialTheme.typography.bodySmall)
                fieldLabels.forEach { (field, label) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = field in dog.emergencyFields,
                            onCheckedChange = { on ->
                                dog = dog.copy(emergencyFields = if (on) dog.emergencyFields + field else dog.emergencyFields - field)
                            },
                            colors = CheckboxDefaults.colors(checkedColor = WeatherText, uncheckedColor = WeatherTextMuted, checkmarkColor = Color.Black),
                        )
                        Text(stringResource(label), color = WeatherText)
                    }
                }
            }
        },
        confirmButton = {
            PodcastTextButton(stringResource(R.string.dogs_save)) {
                val kg = weight.replace(',', '.').toDoubleOrNull()?.takeIf { it in 0.1..150.0 }
                val name = io.github.tux4us.miriquidilauncher.dogs.DogCodec.clean(dog.name, DogLogic.MAX_NAME)
                if (name.isNotEmpty()) onSave(dog.copy(name = name, weightKg = kg))
            }
        },
        dismissButton = { PodcastTextButton(stringResource(R.string.dogs_cancel), onDismiss) },
    )
}

@Composable
private fun ItemEditDialog(initial: HealthItem, dateFormat: DateTimeFormatter, onSave: (HealthItem) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val kindNames = stringArrayResource(R.array.dogs_kinds)
    var item by remember { mutableStateOf(initial) }
    var interval by remember { mutableStateOf(initial.intervalMonths?.toString().orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DogDialogColor, titleContentColor = WeatherText, textContentColor = WeatherText,
        title = { Text(stringResource(R.string.dogs_item_edit)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlineAction(stringResource(R.string.dogs_item_kind, kindNames[item.kind.ordinal]), true) {
                    item = item.copy(kind = HealthKind.entries[(item.kind.ordinal + 1) % HealthKind.entries.size])
                }
                Field(item.title, { item = item.copy(title = it) }, stringResource(R.string.dogs_item_title))
                OutlineAction(
                    item.lastDate?.let { stringResource(R.string.dogs_item_last, it.format(dateFormat)) } ?: stringResource(R.string.dogs_item_last_none),
                    true,
                ) { pickDate(context, item.lastDate) { item = item.copy(lastDate = it) } }
                if (item.lastDate != null) PodcastTextButton(stringResource(R.string.dogs_clear)) { item = item.copy(lastDate = null) }
                Field(interval, { interval = it.filter(Char::isDigit).take(3) }, stringResource(R.string.dogs_item_interval), KeyboardType.Number)
                OutlineAction(
                    item.dueDate?.let { stringResource(R.string.dogs_item_due, it.format(dateFormat)) } ?: stringResource(R.string.dogs_item_due_none),
                    true,
                ) { pickDate(context, item.dueDate) { item = item.copy(dueDate = it) } }
                if (item.dueDate != null) PodcastTextButton(stringResource(R.string.dogs_clear)) { item = item.copy(dueDate = null) }
                Text(stringResource(R.string.dogs_item_hint), color = WeatherTextMuted, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            PodcastTextButton(stringResource(R.string.dogs_save)) {
                val title = item.title.trim().ifEmpty { kindNames[item.kind.ordinal] }
                onSave(item.copy(title = title.take(DogLogic.MAX_FIELD), intervalMonths = interval.toIntOrNull()?.takeIf { it in 1..120 }))
            }
        },
        dismissButton = { PodcastTextButton(stringResource(R.string.dogs_cancel), onDismiss) },
    )
}

// endregion

/** Card auf der Startseite: überfällige und in den nächsten Tagen fällige Einträge aller Hunde. */
@Composable
fun DogsHomeCard(entries: List<DueEntry>, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    if (entries.isEmpty()) return
    val shown = entries.take(3)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Color(0x66000000))
            .clickable(onClick = onOpen)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(stringResource(R.string.dogs_home_title), color = Color.White, fontWeight = FontWeight.SemiBold)
        shown.forEach { e ->
            val line = when {
                e.daysUntil < 0 -> stringResource(R.string.dogs_home_overdue, e.dogName, e.item.title)
                e.daysUntil == 0L -> stringResource(R.string.dogs_home_today, e.dogName, e.item.title)
                else -> stringResource(R.string.dogs_home_soon, e.dogName, e.item.title, e.daysUntil)
            }
            Text(line, color = Color.White, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
        }
        if (entries.size > shown.size) {
            Text(stringResource(R.string.dogs_home_more, entries.size - shown.size), color = Color.White.copy(alpha = 0.72f), style = MaterialTheme.typography.bodySmall)
        }
    }
}
