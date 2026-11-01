// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.dogs

import io.github.tux4us.miriquidilauncher.weather.MiniJson
import io.github.tux4us.miriquidilauncher.weather.asArray
import io.github.tux4us.miriquidilauncher.weather.asDouble
import io.github.tux4us.miriquidilauncher.weather.asObject
import io.github.tux4us.miriquidilauncher.weather.asString
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * JSON-Format der Hundedaten für den Datenspeicher und das Backup (`{"dogs": [...]}`).
 * Beim Lesen wird alles bereinigt: Längen, Anzahl, Wertebereiche, doppelte oder leere Kennungen.
 */
object DogCodec {
    private val MIN_DATE = LocalDate.of(1980, 1, 1)
    private val MAX_DATE = LocalDate.of(2100, 12, 31)

    fun encode(data: DogData): String {
        val sb = StringBuilder("{\"dogs\": [")
        data.dogs.forEachIndexed { i, d ->
            if (i > 0) sb.append(", ")
            sb.append("{")
            sb.append("\"id\": ").append(str(d.id))
            sb.append(", \"name\": ").append(str(d.name))
            sb.append(", \"breed\": ").append(str(d.breed))
            sb.append(", \"sex\": ").append(str(d.sex.key))
            d.neutered?.let { sb.append(", \"neutered\": ").append(it) }
            d.birthDate?.let { sb.append(", \"birth\": ").append(str(it.toString())).append(", \"birthApprox\": ").append(d.birthApprox) }
            d.weightKg?.let { sb.append(", \"weightKg\": ").append(java.lang.Double.toString(it)) }
            sb.append(", \"color\": ").append(str(d.color))
            sb.append(", \"chip\": ").append(str(d.chip))
            sb.append(", \"ownerName\": ").append(str(d.ownerName))
            sb.append(", \"ownerPhone\": ").append(str(d.ownerPhone))
            sb.append(", \"emergencyContact\": ").append(str(d.emergencyContact))
            sb.append(", \"vetName\": ").append(str(d.vetName))
            sb.append(", \"vetPhone\": ").append(str(d.vetPhone))
            sb.append(", \"insurance\": ").append(str(d.insurance))
            sb.append(", \"medical\": ").append(str(d.medical))
            sb.append(", \"emergencyFields\": [").append(d.emergencyFields.sortedBy { it.ordinal }.joinToString(", ") { str(it.key) }).append("]")
            sb.append(", \"health\": [")
            d.health.forEachIndexed { j, h ->
                if (j > 0) sb.append(", ")
                sb.append("{\"id\": ").append(str(h.id)).append(", \"kind\": ").append(str(h.kind.key))
                sb.append(", \"title\": ").append(str(h.title))
                h.lastDate?.let { sb.append(", \"last\": ").append(str(it.toString())) }
                h.intervalMonths?.let { sb.append(", \"interval\": ").append(it) }
                h.dueDate?.let { sb.append(", \"due\": ").append(str(it.toString())) }
                sb.append("}")
            }
            sb.append("]}")
        }
        return sb.append("]}").toString()
    }

    /** Liest gespeicherten Text; ungültiger oder leerer Text ergibt keine Hunde. */
    fun decode(text: String?): DogData {
        if (text.isNullOrBlank()) return DogData()
        val root = try {
            MiniJson.parse(text).asObject()
        } catch (e: MiniJson.JsonException) {
            null
        }
        return fromMap(root)
    }

    fun fromMap(root: Map<String, Any?>?): DogData {
        val array = root?.get("dogs").asArray() ?: return DogData()
        val seen = HashSet<String>()
        val dogs = ArrayList<DogProfile>()
        for (entry in array) {
            if (dogs.size >= DogLogic.MAX_DOGS) break
            val dog = parseDog(entry.asObject() ?: continue, seen) ?: continue
            dogs += dog
        }
        return DogData(dogs)
    }

    private fun parseDog(o: Map<String, Any?>, seenIds: MutableSet<String>): DogProfile? {
        val name = clean(o["name"].asString(), DogLogic.MAX_NAME)
        if (name.isEmpty()) return null
        val birth = date(o["birth"].asString())
        val items = ArrayList<HealthItem>()
        val itemIds = HashSet<String>()
        for (e in o["health"].asArray().orEmpty()) {
            if (items.size >= DogLogic.MAX_ITEMS) break
            val h = e.asObject() ?: continue
            val title = clean(h["title"].asString(), DogLogic.MAX_FIELD)
            if (title.isEmpty()) continue
            var id = clean(h["id"].asString(), 64)
            if (id.isEmpty() || !itemIds.add(id)) {
                id = DogLogic.newId()
                itemIds += id
            }
            items += HealthItem(
                id = id,
                kind = HealthKind.fromKey(h["kind"].asString()),
                title = title,
                lastDate = date(h["last"].asString()),
                intervalMonths = h["interval"].asDouble()?.toInt()?.takeIf { it in 1..120 },
                dueDate = date(h["due"].asString()),
            )
        }
        var id = clean(o["id"].asString(), 64)
        if (id.isEmpty() || !seenIds.add(id)) {
            id = DogLogic.newId()
            seenIds += id
        }
        val fields = o["emergencyFields"].asArray()?.mapNotNull { EmergencyField.fromKey(it.asString()) }?.toSet()
            ?: EmergencyField.DEFAULT
        return DogProfile(
            id = id,
            name = name,
            breed = clean(o["breed"].asString(), DogLogic.MAX_FIELD),
            sex = DogSex.fromKey(o["sex"].asString()),
            neutered = o["neutered"] as? Boolean,
            birthDate = birth,
            birthApprox = birth != null && (o["birthApprox"] as? Boolean ?: false),
            weightKg = o["weightKg"].asDouble()?.takeIf { it.isFinite() && it in 0.1..150.0 },
            color = clean(o["color"].asString(), DogLogic.MAX_FIELD),
            chip = clean(o["chip"].asString(), 40),
            ownerName = clean(o["ownerName"].asString(), DogLogic.MAX_FIELD),
            ownerPhone = clean(o["ownerPhone"].asString(), 40),
            emergencyContact = clean(o["emergencyContact"].asString(), DogLogic.MAX_FIELD),
            vetName = clean(o["vetName"].asString(), DogLogic.MAX_FIELD),
            vetPhone = clean(o["vetPhone"].asString(), 40),
            insurance = clean(o["insurance"].asString(), DogLogic.MAX_FIELD),
            medical = clean(o["medical"].asString(), DogLogic.MAX_TEXT),
            emergencyFields = fields,
            health = items,
        )
    }

    /** Entfernt Steuerzeichen (Zeilenumbrüche bleiben), kürzt und trimmt. */
    fun clean(text: String?, max: Int): String =
        (text ?: "").filter { it == '\n' || !it.isISOControl() }.trim().take(max)

    private fun date(text: String?): LocalDate? = try {
        text?.let { LocalDate.parse(it) }?.takeIf { it >= MIN_DATE && it <= MAX_DATE }
    } catch (e: DateTimeParseException) {
        null
    }

    private fun str(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' -> sb.append(String.format("\\u%04x", c.code))
                else -> sb.append(c)
            }
        }
        return sb.append('"').toString()
    }
}
