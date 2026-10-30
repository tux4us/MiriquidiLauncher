// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.data

/**
 * Eine vom Nutzer angelegte Gruppe von Apps (z. B. "Messenger", "Finanzen").
 *
 * @property id stabile Kennung; bleibt beim Umbenennen gleich
 * @property appKeys [AppEntry.key] der Apps in der Reihenfolge der Anzeige. Schlüssel nicht (mehr)
 *   installierter Apps bleiben gespeichert, damit vorübergehend fehlende Apps (SD-Karte, pausiertes
 *   Arbeitsprofil) nach der Rückkehr wieder erscheinen.
 */
data class AppGroup(val id: String, val name: String, val appKeys: List<String>)

/** Ergebnis der Prüfung eines Gruppennamens. */
sealed interface GroupNameCheck {
    /** [name] ist der bereinigte Name (ohne Leerraum am Rand, auf [AppGroupCodec.MAX_NAME_LENGTH] gekürzt). */
    data class Ok(val name: String) : GroupNameCheck
    data object Blank : GroupNameCheck
    data object Taken : GroupNameCheck
    data object LimitReached : GroupNameCheck
}

/**
 * Textdarstellung und Änderungen der App-Gruppen. Reine Funktionen ohne Android-Bezug, daher auf der JVM
 * testbar. Trennzeichen sind ASCII-Steuerzeichen (0x1E Gruppe, 0x1F Feld, 0x1D Eintrag); sie werden aus
 * Namen und Schlüsseln entfernt.
 */
object AppGroupCodec {
    const val MAX_GROUPS = 30
    const val MAX_NAME_LENGTH = 40
    const val MAX_APPS_PER_GROUP = 200

    private const val RECORD = '\u001E'
    private const val FIELD = '\u001F'
    private const val ITEM = '\u001D'

    fun encode(groups: List<AppGroup>): String =
        groups.joinToString(RECORD.toString()) { group ->
            listOf(clean(group.id), clean(group.name), group.appKeys.joinToString(ITEM.toString()) { clean(it) })
                .joinToString(FIELD.toString())
        }

    /** Liest die gespeicherte Form; beschädigte Einträge werden übersprungen. */
    fun decode(raw: String?): List<AppGroup> {
        val seen = HashSet<String>()
        return raw.orEmpty().split(RECORD).mapNotNull { record ->
            val parts = record.split(FIELD)
            if (parts.size != 3) return@mapNotNull null
            val id = parts[0]
            val name = parts[1].trim()
            if (id.isBlank() || name.isEmpty() || !seen.add(id)) return@mapNotNull null
            val keys = parts[2].split(ITEM).filter { it.isNotEmpty() }.distinct().take(MAX_APPS_PER_GROUP)
            AppGroup(id, name.take(MAX_NAME_LENGTH), keys)
        }.take(MAX_GROUPS)
    }

    /** Prüft [name]; [exceptId] ist die Gruppe, die gerade umbenannt wird (ihr eigener Name zählt nicht als belegt). */
    fun checkName(groups: List<AppGroup>, name: String, exceptId: String? = null): GroupNameCheck {
        val clean = name.trim().take(MAX_NAME_LENGTH)
        return when {
            clean.isEmpty() -> GroupNameCheck.Blank
            groups.any { it.id != exceptId && it.name.equals(clean, ignoreCase = true) } -> GroupNameCheck.Taken
            exceptId == null && groups.size >= MAX_GROUPS -> GroupNameCheck.LimitReached
            else -> GroupNameCheck.Ok(clean)
        }
    }

    /** Hängt eine neue, leere Gruppe an. Ein ungültiger Name lässt die Liste unverändert. */
    fun add(groups: List<AppGroup>, id: String, name: String): List<AppGroup> =
        when (val check = checkName(groups, name)) {
            is GroupNameCheck.Ok -> groups + AppGroup(id, check.name, emptyList())
            else -> groups
        }

    fun rename(groups: List<AppGroup>, id: String, name: String): List<AppGroup> =
        when (val check = checkName(groups, name, exceptId = id)) {
            is GroupNameCheck.Ok -> groups.map { if (it.id == id) it.copy(name = check.name) else it }
            else -> groups
        }

    fun delete(groups: List<AppGroup>, id: String): List<AppGroup> = groups.filterNot { it.id == id }

    /** Nimmt die App auf oder, falls sie schon enthalten ist, wieder heraus. */
    fun toggleApp(groups: List<AppGroup>, id: String, appKey: String): List<AppGroup> =
        groups.map { group ->
            when {
                group.id != id -> group
                appKey in group.appKeys -> group.copy(appKeys = group.appKeys - appKey)
                group.appKeys.size >= MAX_APPS_PER_GROUP -> group
                else -> group.copy(appKeys = group.appKeys + appKey)
            }
        }

    /** Verschiebt die App innerhalb der Gruppe um [delta] Positionen (negativ = nach vorn). */
    fun moveApp(groups: List<AppGroup>, id: String, appKey: String, delta: Int): List<AppGroup> =
        groups.map { group ->
            if (group.id == id) group.copy(appKeys = ListOrder.moveBy(group.appKeys, delta) { it == appKey }) else group
        }

    fun moveGroup(groups: List<AppGroup>, id: String, delta: Int): List<AppGroup> =
        ListOrder.moveBy(groups, delta) { it.id == id }

    /** Ersetzt die Liste vollständig (Import eines Backups): ungültige und doppelte Einträge entfallen. */
    fun sanitize(groups: List<AppGroup>): List<AppGroup> = decode(encode(groups))

    private fun clean(text: String): String = text.filterNot { it == RECORD || it == FIELD || it == ITEM }
}
