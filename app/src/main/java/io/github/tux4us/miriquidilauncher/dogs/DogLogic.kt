// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.dogs

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

data class DogAge(val years: Int, val months: Int)

enum class DueState { OVERDUE, SOON, LATER }

/** Fälligkeit mit Hund; [daysUntil] ist negativ, wenn sie überschritten ist. */
data class DueEntry(
    val dogId: String,
    val dogName: String,
    val item: HealthItem,
    val due: LocalDate,
    val daysUntil: Long,
    val state: DueState,
)

/** Beschriftungen der Notfallkarte in der Sprache der Oberfläche. */
data class EmergencyLabels(
    val title: String,
    val breed: String,
    val owner: String,
    val contact: String,
    val chip: String,
    val vet: String,
    val medical: String,
    val insurance: String,
)

/** Ergebnis des Übernehmens fremder Profile. */
data class MergeResult(val data: DogData, val added: Int, val skipped: Int)

/** Reine Logik für Hundeprofile, Fälligkeiten und die Notfallkarte. */
object DogLogic {
    const val MAX_DOGS = 12
    const val MAX_ITEMS = 40
    const val SOON_DAYS = 30L
    const val HOME_DAYS = 14L

    /** Obergrenze des QR-Inhalts in Zeichen; bei Stufe M bleibt er dann gut lesbar. */
    const val QR_MAX_CHARS = 700

    const val MAX_NAME = 60
    const val MAX_FIELD = 120
    const val MAX_TEXT = 600

    fun newId(): String = UUID.randomUUID().toString()

    fun age(birth: LocalDate?, today: LocalDate): DogAge? {
        if (birth == null || birth.isAfter(today)) return null
        val total = ChronoUnit.MONTHS.between(birth, today).toInt()
        return DogAge(total / 12, total % 12)
    }

    /** Fälligkeitsdatum: ausdrücklich gesetzt oder letzte Durchführung plus Abstand. */
    fun dueOf(item: HealthItem): LocalDate? {
        item.dueDate?.let { return it }
        val last = item.lastDate ?: return null
        val interval = item.intervalMonths ?: return null
        return if (interval > 0) last.plusMonths(interval.toLong()) else null
    }

    fun stateOf(daysUntil: Long, soonDays: Long = SOON_DAYS): DueState = when {
        daysUntil < 0 -> DueState.OVERDUE
        daysUntil <= soonDays -> DueState.SOON
        else -> DueState.LATER
    }

    /** Alle Fälligkeiten mit Datum, nach Datum sortiert (überschrittene zuerst). */
    fun dueEntries(dog: DogProfile, today: LocalDate): List<DueEntry> =
        dog.health.mapNotNull { item ->
            val due = dueOf(item) ?: return@mapNotNull null
            val days = ChronoUnit.DAYS.between(today, due)
            DueEntry(dog.id, dog.name, item, due, days, stateOf(days))
        }.sortedWith(compareBy({ it.due }, { it.item.title.lowercase() }))

    /** Überschrittene und in den nächsten [withinDays] Tagen fällige Einträge aller Hunde. */
    fun upcoming(dogs: List<DogProfile>, today: LocalDate, withinDays: Long = SOON_DAYS): List<DueEntry> =
        dogs.flatMap { dueEntries(it, today) }
            .filter { it.daysUntil <= withinDays }
            .sortedWith(compareBy({ it.due }, { it.dogName.lowercase() }, { it.item.title.lowercase() }))

    /** Erledigt: Datum der letzten Durchführung ist [today]; ein fest gesetzter Termin entfällt (mit Abstand folgt der nächste). */
    fun markDone(item: HealthItem, today: LocalDate): HealthItem = item.copy(lastDate = today, dueDate = null)

    fun upsert(data: DogData, dog: DogProfile): DogData {
        val index = data.dogs.indexOfFirst { it.id == dog.id }
        val list = data.dogs.toMutableList()
        if (index >= 0) list[index] = dog else if (list.size < MAX_DOGS) list += dog
        return DogData(list)
    }

    fun remove(data: DogData, id: String): DogData = DogData(data.dogs.filter { it.id != id })

    fun upsertItem(dog: DogProfile, item: HealthItem): DogProfile {
        val index = dog.health.indexOfFirst { it.id == item.id }
        val list = dog.health.toMutableList()
        if (index >= 0) list[index] = item else if (list.size < MAX_ITEMS) list += item
        return dog.copy(health = list)
    }

    fun removeItem(dog: DogProfile, id: String): DogProfile = dog.copy(health = dog.health.filter { it.id != id })

    /** Übernimmt [imported] zu [existing]; gleiche Chipnummer oder gleicher Name mit gleichem Geburtsdatum zählt als vorhanden. */
    fun merge(existing: DogData, imported: List<DogProfile>): MergeResult {
        val list = existing.dogs.toMutableList()
        var added = 0
        var skipped = 0
        for (dog in imported) {
            val duplicate = list.any { o ->
                (dog.chip.isNotBlank() && o.chip.filter(Char::isLetterOrDigit).equals(dog.chip.filter(Char::isLetterOrDigit), true)) ||
                    (o.name.equals(dog.name, true) && o.birthDate == dog.birthDate)
            }
            if (duplicate || list.size >= MAX_DOGS) {
                skipped++
            } else {
                list += dog.copy(id = newId())
                added++
            }
        }
        return MergeResult(DogData(list), added, skipped)
    }

    /**
     * Text der Notfallkarte und des QR-Codes: Name immer, dann die gewählten Angaben in der Reihenfolge ihrer Wichtigkeit.
     * Leere Angaben entfallen; was nicht mehr in [maxChars] passt, wird weggelassen (kürzere Angaben dahinter dürfen bleiben).
     */
    fun emergencyText(dog: DogProfile, labels: EmergencyLabels, maxChars: Int = QR_MAX_CHARS): String {
        val lines = ArrayList<String>()
        lines += labels.title
        lines += dog.name.trim()
        var length = lines.sumOf { it.length + 1 }
        fun add(label: String, value: String) {
            val v = value.trim().replace(Regex("\\s*\\n+\\s*"), "; ")
            if (v.isEmpty()) return
            val line = "$label: $v"
            if (length + line.length + 1 > maxChars) return
            lines += line
            length += line.length + 1
        }
        val f = dog.emergencyFields
        if (EmergencyField.OWNER in f) add(labels.owner, listOf(dog.ownerName, dog.ownerPhone).filter { it.isNotBlank() }.joinToString(", "))
        if (EmergencyField.CONTACT in f) add(labels.contact, dog.emergencyContact)
        if (EmergencyField.MEDICAL in f) add(labels.medical, dog.medical)
        if (EmergencyField.CHIP in f) add(labels.chip, dog.chip)
        if (EmergencyField.VET in f) add(labels.vet, listOf(dog.vetName, dog.vetPhone).filter { it.isNotBlank() }.joinToString(", "))
        if (EmergencyField.BREED in f) add(labels.breed, dog.breed)
        if (EmergencyField.INSURANCE in f) add(labels.insurance, dog.insurance)
        return lines.joinToString("\n")
    }

    /** Titel eines Kalendereintrags zu einer Fälligkeit, z. B. "Bella: Tollwutimpfung". */
    fun calendarTitle(entry: DueEntry): String = "${entry.dogName}: ${entry.item.title}".take(MAX_FIELD)
}
