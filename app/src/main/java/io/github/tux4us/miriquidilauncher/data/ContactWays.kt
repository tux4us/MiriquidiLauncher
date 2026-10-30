// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.data

/**
 * Ein Weg, einen Kontakt zu erreichen. [label] ist der Text für die Anzeige (z. B. "Mobil", "Signal-Nachricht").
 */
sealed interface ContactWay {
    val label: String

    data class Call(val number: String, override val label: String) : ContactWay
    data class Sms(val number: String, override val label: String) : ContactWay
    data class Email(val address: String, override val label: String) : ContactWay

    /**
     * Ein Weg über eine andere App, die einen Eintrag im Adressbuch hinterlegt hat (Signal, Telegram,
     * WhatsApp, Threema …). Geöffnet wird der Eintrag mit `ACTION_VIEW` auf [dataId] und [mimeType]; die
     * App, die den Typ registriert hat, übernimmt. [packageName] ist die erkannte App oder `null`.
     */
    data class ViaApp(
        val dataId: Long,
        val mimeType: String,
        val packageName: String?,
        override val label: String,
    ) : ContactWay
}

/**
 * Alle bekannten Wege zu einem Kontakt. [lookupUri] öffnet den Kontakt in der Kontakte-App und ist `null`,
 * wenn der Kontakt im Adressbuch nicht gefunden wurde. [fromContacts] ist `false`, wenn nur die gespeicherte
 * Nummer zur Verfügung stand (ohne Kontaktberechtigung oder ohne Treffer).
 */
data class ContactWays(val ways: List<ContactWay>, val lookupUri: String?, val fromContacts: Boolean)

/** Auswertung der Datentypen des Adressbuchs; reine Funktionen ohne Android-Bezug. */
object ContactWaysLogic {
    private const val APP_MIME_PREFIX = "vnd.android.cursor.item/vnd."

    /**
     * Apps tragen ihre Einträge als `vnd.android.cursor.item/vnd.<Paketname>.<Art>` ins Adressbuch ein,
     * z. B. `…/vnd.org.thoughtcrime.securesms.contact` (Signal) oder `…/vnd.com.whatsapp.profile`. Die
     * Standardtypen des Systems (Telefon, E-Mail, Name, Foto …) tragen kein `vnd.` nach dem Schrägstrich.
     */
    fun isAppMime(mimeType: String): Boolean = mimeType.startsWith(APP_MIME_PREFIX)

    /**
     * Das Paket aus [installedPackages], dessen Name am Anfang des Datentyps steht (das längste gewinnt),
     * oder `null`. Nur Einträge installierter Apps sind nutzbar; alle anderen werden nicht angeboten.
     */
    fun packageOf(mimeType: String, installedPackages: Collection<String>): String? {
        if (!isAppMime(mimeType)) return null
        val rest = mimeType.removePrefix(APP_MIME_PREFIX)
        return installedPackages
            .filter { rest == it || rest.startsWith("$it.") }
            .maxByOrNull { it.length }
    }

    /** Anzeigetext eines App-Eintrags: die Beschriftung des Adressbuchs, sonst der Name der App. */
    fun appLabel(dataLabel: String?, appName: String?, fallback: String): String =
        dataLabel?.trim()?.takeIf { it.isNotEmpty() } ?: appName?.trim()?.takeIf { it.isNotEmpty() } ?: fallback

    /** Rufnummern ohne Formatierung, zum Erkennen doppelter Einträge. */
    fun normalizeNumber(number: String): String = FavoriteContact.normalizeNumber(number)

    /**
     * Zwei Nummern gelten als gleich, wenn die Ziffernfolgen übereinstimmen oder eine auf der anderen endet
     * (mit und ohne Landesvorwahl, ab acht Ziffern).
     */
    fun sameNumber(a: String, b: String): Boolean {
        val x = a.filter { it.isDigit() }
        val y = b.filter { it.isDigit() }
        if (x.isEmpty() || y.isEmpty()) return false
        if (x == y) return true
        val short = if (x.length <= y.length) x else y
        val long = if (x.length <= y.length) y else x
        return short.length >= 8 && long.endsWith(short.removePrefix("0"))
    }

    /** Fügt [number] vorn ein, falls keine Nummer der Liste dieselbe ist. */
    fun withNumberFirst(numbers: List<String>, number: String): List<String> {
        if (number.isBlank()) return numbers
        val match = numbers.firstOrNull { sameNumber(it, number) }
        return if (match == null) listOf(number) + numbers else listOf(match) + numbers.filter { it != match }
    }
}
