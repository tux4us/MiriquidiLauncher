// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.data

/** Favorisierter Kontakt für das Anruf-Schnellmenü. Name und Nummer werden lokal gespeichert. */
data class FavoriteContact(val name: String, val number: String) {
    /** Schlüssel zum Erkennen doppelter Einträge: die Nummer ohne Leerzeichen, Bindestriche und Klammern. */
    val key: String get() = normalizeNumber(number)

    companion object {
        fun normalizeNumber(number: String): String =
            number.filter { it.isDigit() || it == '+' }
    }
}

/**
 * Einfache, verlustfreie Textdarstellung der Favoriten-Kontakte für DataStore.
 * Trennzeichen sind die ASCII-Steuerzeichen 0x1E (Datensatz) und 0x1F (Feld); sie werden aus Namen
 * und Nummern entfernt. Frei von Android-Klassen, damit sie auf der JVM testbar bleibt.
 */
object FavoriteContactCodec {
    private const val RECORD = '\u001E'
    private const val FIELD = '\u001F'

    fun encode(contacts: List<FavoriteContact>): String =
        contacts.joinToString(RECORD.toString()) { "${clean(it.name)}$FIELD${clean(it.number)}" }

    fun decode(raw: String?): List<FavoriteContact> =
        raw.orEmpty()
            .split(RECORD)
            .mapNotNull { record ->
                val parts = record.split(FIELD)
                if (parts.size != 2 || parts[1].isBlank()) null else FavoriteContact(parts[0], parts[1])
            }

    /** Fügt [contact] hinzu, sofern dessen Nummer noch nicht vorhanden ist. */
    fun add(list: List<FavoriteContact>, contact: FavoriteContact): List<FavoriteContact> =
        if (contact.number.isBlank() || list.any { it.key == contact.key }) list else list + contact

    fun remove(list: List<FavoriteContact>, contact: FavoriteContact): List<FavoriteContact> =
        list.filterNot { it.key == contact.key }

    private fun clean(text: String): String = text.filterNot { it == RECORD || it == FIELD }
}
