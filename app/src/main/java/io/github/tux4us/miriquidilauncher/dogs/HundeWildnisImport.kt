// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.dogs

import io.github.tux4us.miriquidilauncher.weather.MiniJson
import io.github.tux4us.miriquidilauncher.weather.asArray
import io.github.tux4us.miriquidilauncher.weather.asObject
import io.github.tux4us.miriquidilauncher.weather.asString
import java.time.LocalDate

/**
 * Liest Hundeprofile aus der Datei `backup_data.json` einer Sicherung der HundeWildnis-App (`.hundebackup`, ZIP).
 * Unterstützt die vollständige Sicherung (`dogs`) und die Teilsicherung (`data.profiles`). Übernommen werden nur
 * Stammdaten, Kontakte und die Angaben zu Impfung, Entwurmung und Zecken-/Flohschutz; Fotos, Verläufe, Vitalwerte,
 * Läufigkeiten und Trainingsdaten werden nicht gelesen. Nichts davon verlässt das Gerät.
 */
object HundeWildnisImport {
    const val MAX_JSON_CHARS = 32 * 1024 * 1024

    class ImportException(message: String) : Exception(message)

    private val FULL_DATE = Regex("(\\d{1,2})\\.(\\d{1,2})\\.(\\d{4})")
    private val MONTH_DATE = Regex("^(\\d{1,2})\\.(\\d{4})$")
    private val YEAR_ONLY = Regex("^(\\d{4})$")
    private val PHONE = Regex("\\+?\\d[\\d ()/\\-]{5,}\\d")
    private val NUMBER = Regex("(\\d+(?:[.,]\\d+)?)")

    fun parse(json: String): List<DogProfile> {
        if (json.length > MAX_JSON_CHARS) throw ImportException("Datei zu groß")
        val root = try {
            MiniJson.parse(json).asObject()
        } catch (e: MiniJson.JsonException) {
            null
        } ?: throw ImportException("Kein JSON-Objekt")
        val profiles = root["dogs"].asArray()
            ?: root["data"].asObject()?.get("profiles").asArray()
            ?: throw ImportException("Keine Hundeprofile gefunden")
        val out = ArrayList<DogProfile>()
        for (entry in profiles) {
            if (out.size >= DogLogic.MAX_DOGS) break
            out += mapProfile(entry.asObject() ?: continue) ?: continue
        }
        return out
    }

    internal fun mapProfile(o: Map<String, Any?>): DogProfile? {
        val name = DogCodec.clean(o["name"].asString(), DogLogic.MAX_NAME)
        if (name.isEmpty()) return null
        fun s(key: String, max: Int = DogLogic.MAX_FIELD) = DogCodec.clean(o[key].asString(), max)
        val birth = parseBirth(s("birthDate", 20))
        val owner = splitPhone(s("owner"))
        val vet = splitPhone(s("veterinarian"))
        val insurance = listOf(s("healthInsurance"), s("liabilityInsurance")).filter { it.isNotEmpty() }.joinToString("; ")
        val items = ArrayList<HealthItem>()
        listOf(
            Triple("vaccinationStatus", HealthKind.VACCINATION, "Impfung"),
            Triple("additionalVaccination", HealthKind.VACCINATION, "Zusatzimpfung"),
            Triple("lastDeworming", HealthKind.DEWORMING, "Entwurmung"),
            Triple("lastFleaTick", HealthKind.TICK_FLEA, "Zecken-/Flohschutz"),
        ).forEach { (key, kind, title) ->
            val text = s(key, DogLogic.MAX_FIELD)
            if (text.isEmpty()) return@forEach
            val date = FULL_DATE.find(text)?.let { toDate(it.groupValues[3], it.groupValues[2], it.groupValues[1]) }
            // Freitext ohne Datum wird als Titel übernommen, damit nichts verloren geht.
            items += HealthItem(
                id = DogLogic.newId(), kind = kind,
                title = if (date != null) title else "$title: $text".take(DogLogic.MAX_FIELD),
                lastDate = date,
            )
        }
        return DogProfile(
            id = DogLogic.newId(),
            name = name,
            breed = s("breed"),
            sex = when (s("gender")) { "male", "Rüde" -> DogSex.MALE; "female", "Hündin" -> DogSex.FEMALE; else -> DogSex.UNKNOWN },
            neutered = when (s("neutered")) { "yes", "Ja" -> true; "no", "Nein" -> false; else -> null },
            birthDate = birth?.first,
            birthApprox = birth?.second ?: false,
            weightKg = parseWeight(s("weight", 30)),
            color = s("color"),
            chip = s("chipNumber", 40),
            ownerName = owner.first,
            ownerPhone = owner.second,
            emergencyContact = s("additionalEmergencyContact"),
            vetName = vet.first,
            vetPhone = vet.second,
            insurance = insurance.take(DogLogic.MAX_FIELD),
            medical = s("medicalNotes", DogLogic.MAX_TEXT),
            health = items.take(DogLogic.MAX_ITEMS),
        )
    }

    /** `dd.MM.yyyy`, `MM.yyyy` oder `yyyy`; bei den beiden letzten Formen ist das Datum ungefähr (Erster des Monats/Jahres). */
    internal fun parseBirth(text: String): Pair<LocalDate, Boolean>? {
        val t = text.trim()
        FULL_DATE.matchEntire(t)?.let { m -> return toDate(m.groupValues[3], m.groupValues[2], m.groupValues[1])?.let { it to false } }
        MONTH_DATE.find(t)?.let { m -> return toDate(m.groupValues[2], m.groupValues[1], "1")?.let { it to true } }
        YEAR_ONLY.find(t)?.let { m -> return toDate(m.groupValues[1], "1", "1")?.let { it to true } }
        return null
    }

    private fun toDate(year: String, month: String, day: String): LocalDate? = try {
        LocalDate.of(year.toInt(), month.toInt(), day.toInt()).takeIf { it.year in 1980..2100 }
    } catch (e: java.time.DateTimeException) {
        null
    }

    /** Trennt eine erste Telefonnummer vom Rest: "Dr. Meier, 0123 456789" ergibt ("Dr. Meier", "0123 456789"). */
    internal fun splitPhone(text: String): Pair<String, String> {
        val m = PHONE.find(text) ?: return text to ""
        val rest = (text.removeRange(m.range)).trim().trim(',', ';', '-', '/', ':').trim()
        return rest to m.value.trim()
    }

    /** Erste Zahl im Text in Kilogramm ("25,5 kg" ergibt 25.5); Gramm-Angaben ab 1000 werden umgerechnet. */
    internal fun parseWeight(text: String): Double? {
        val raw = NUMBER.find(text)?.value?.replace(',', '.')?.toDoubleOrNull() ?: return null
        val kg = if (raw >= 1000 && text.contains("g", true) && !text.contains("kg", true)) raw / 1000 else raw
        return kg.takeIf { it in 0.1..150.0 }
    }
}
