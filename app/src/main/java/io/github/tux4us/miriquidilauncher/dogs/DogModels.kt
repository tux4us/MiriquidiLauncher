// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.dogs

import java.time.LocalDate

enum class DogSex(val key: String) {
    UNKNOWN("unknown"), MALE("male"), FEMALE("female");

    companion object {
        fun fromKey(key: String?): DogSex = entries.firstOrNull { it.key == key } ?: UNKNOWN
    }
}

/** Art einer Gesundheitsfälligkeit. */
enum class HealthKind(val key: String) {
    VACCINATION("vaccination"), DEWORMING("deworming"), TICK_FLEA("tickFlea"), VET_CHECK("vetCheck"), OTHER("other");

    companion object {
        fun fromKey(key: String?): HealthKind = entries.firstOrNull { it.key == key } ?: OTHER
    }
}

/** Angaben, die auf der Notfallkarte (und im QR-Code) stehen können. Der Name des Hundes steht immer darauf. */
enum class EmergencyField(val key: String) {
    BREED("breed"), OWNER("owner"), CONTACT("contact"), CHIP("chip"), VET("vet"), MEDICAL("medical"), INSURANCE("insurance");

    companion object {
        fun fromKey(key: String?): EmergencyField? = entries.firstOrNull { it.key == key }

        /** Voreinstellung: alles außer der Versicherung. */
        val DEFAULT: Set<EmergencyField> = setOf(BREED, OWNER, CONTACT, CHIP, VET, MEDICAL)
    }
}

/**
 * Eine Fälligkeit (Impfung, Entwurmung, Zecken-/Flohschutz, Tierarzttermin, Sonstiges).
 * Fällig ist [dueDate], sonst [lastDate] plus [intervalMonths]; ohne beides ist nichts fällig.
 */
data class HealthItem(
    val id: String,
    val kind: HealthKind,
    val title: String,
    val lastDate: LocalDate? = null,
    val intervalMonths: Int? = null,
    val dueDate: LocalDate? = null,
)

data class DogProfile(
    val id: String,
    val name: String,
    val breed: String = "",
    val sex: DogSex = DogSex.UNKNOWN,
    /** `null` = unbekannt. */
    val neutered: Boolean? = null,
    val birthDate: LocalDate? = null,
    /** `true`, wenn nur Monat oder Jahr bekannt waren (importiert); das Datum steht dann auf dem Ersten. */
    val birthApprox: Boolean = false,
    val weightKg: Double? = null,
    val color: String = "",
    val chip: String = "",
    val ownerName: String = "",
    val ownerPhone: String = "",
    /** Name und Telefonnummer einer weiteren Person, frei eingegeben. */
    val emergencyContact: String = "",
    val vetName: String = "",
    val vetPhone: String = "",
    val insurance: String = "",
    /** Allergien, Medikamente, Erkrankungen: frei eingegeben, nur zur Anzeige. */
    val medical: String = "",
    val emergencyFields: Set<EmergencyField> = EmergencyField.DEFAULT,
    val health: List<HealthItem> = emptyList(),
)

data class DogData(val dogs: List<DogProfile> = emptyList())
