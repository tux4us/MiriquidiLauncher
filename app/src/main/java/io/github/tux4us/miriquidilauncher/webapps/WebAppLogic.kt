// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.webapps

import io.github.tux4us.miriquidilauncher.data.ListOrder
import java.net.IDN
import java.net.URI
import java.net.URISyntaxException

/**
 * Eine vom Nutzer gespeicherte Webapp.
 *
 * @property id stabile Kennung (nur `A-Z a-z 0-9 -`, siehe [WebAppLogic.isSafeId]); zugleich Dateiname des Symbols
 * @property url normalisierte HTTPS-Adresse (siehe [WebAppLogic.checkUrl])
 * @property customTab `true` = in einem Custom Tab öffnen, `false` = im Standardbrowser
 * @property hasIcon `true`, wenn der Nutzer ein eigenes Symbol gespeichert hat; sonst zeigt die Scope eine Kachel mit Anfangsbuchstaben
 * @property pin nicht `null` bei einer Verknüpfung, die ein Browser über „Zum Startbildschirm hinzufügen“ angelegt hat.
 *   Der Intent einer Verknüpfung ist für Launcher nicht lesbar; [url] ist dann leer und [customTab] ohne Wirkung,
 *   gestartet wird über `LauncherApps.startShortcut`.
 */
data class WebApp(
    val id: String,
    val name: String,
    val url: String,
    val customTab: Boolean = false,
    val hasIcon: Boolean = false,
    val pin: PinnedShortcut? = null,
)

/**
 * Verweis auf eine vom System angenommene Pin-Verknüpfung: Paket der veröffentlichenden App, Kennung der Verknüpfung
 * und Seriennummer des Benutzerprofils (`UserManager.getSerialNumberForUser`).
 */
data class PinnedShortcut(val packageName: String, val shortcutId: String, val userSerial: Long)

/** Ergebnis der Prüfung einer eingegebenen Adresse. */
sealed interface UrlCheck {
    /** [url] ist die bereinigte Adresse mit `https://`, kleingeschriebenem Rechnernamen (Punycode bei Umlauten) und unverändertem Rest. */
    data class Ok(val url: String) : UrlCheck
    data object Blank : UrlCheck

    /** Ausdrücklich `http://`: wird abgelehnt. */
    data object NotHttps : UrlCheck

    /** Anderes Schema, Zugangsdaten in der Adresse, ungültiger Rechnername oder Anschluss, Leerraum, Steuerzeichen, zu lang. */
    data object Invalid : UrlCheck
}

/**
 * Reine Logik der Scope „Webapps“ ohne Android-Bezug (JVM-getestet): Prüfung von Adresse und Name, Änderungen an
 * der Liste und die Textdarstellung für den Speicher. Trennzeichen sind ASCII-Steuerzeichen (0x1E Eintrag,
 * 0x1F Feld); sie werden aus Namen entfernt und kommen in geprüften Adressen nicht vor.
 */
object WebAppLogic {
    const val MAX_APPS = 100
    const val MAX_NAME_LENGTH = 40
    const val MAX_URL_LENGTH = 2048

    private const val RECORD = '\u001E'
    private const val FIELD = '\u001F'

    private val SAFE_ID = Regex("[A-Za-z0-9-]{1,40}")
    private val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
    const val MAX_SHORTCUT_ID_LENGTH = 255
    private val HOST = Regex("[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)*")

    /** Die Kennung wird als Dateiname verwendet; nur diese Zeichen sind erlaubt (kein Pfadanteil möglich). */
    fun isSafeId(id: String): Boolean = SAFE_ID.matches(id)

    fun isSafePackage(name: String): Boolean = name.length <= 255 && PACKAGE_NAME.matches(name)

    /** Kennung einer Verknüpfung: 1 bis 255 Zeichen ohne Steuerzeichen (die Trennzeichen des Speichers sind Steuerzeichen). */
    fun isSafeShortcutId(id: String): Boolean =
        id.isNotEmpty() && id.length <= MAX_SHORTCUT_ID_LENGTH && id.none { it.isISOControl() }

    fun isValidPin(pin: PinnedShortcut): Boolean =
        isSafePackage(pin.packageName) && isSafeShortcutId(pin.shortcutId) && pin.userSerial >= 0

    /**
     * Prüft und normalisiert eine Adresse. Fehlt das Schema, wird `https://` ergänzt. Erlaubt ist nur HTTPS;
     * `http://` ergibt [UrlCheck.NotHttps], jedes andere Schema (`javascript:`, `intent:`, `file:`, `content:` …)
     * und Zugangsdaten vor dem `@` ergeben [UrlCheck.Invalid]. IPv6-Adressen in eckigen Klammern werden nicht angenommen.
     */
    fun checkUrl(input: String): UrlCheck {
        val s = input.trim()
        if (s.isEmpty()) return UrlCheck.Blank
        if (s.length > MAX_URL_LENGTH) return UrlCheck.Invalid
        if (s.any { it.isWhitespace() || it.isISOControl() }) return UrlCheck.Invalid

        val schemeEnd = s.indexOf("://")
        val rest = if (schemeEnd >= 0) {
            val scheme = s.substring(0, schemeEnd)
            when {
                scheme.equals("https", ignoreCase = true) -> s.substring(schemeEnd + 3)
                scheme.equals("http", ignoreCase = true) -> return UrlCheck.NotHttps
                else -> return UrlCheck.Invalid
            }
        } else {
            s
        }

        val authorityEnd = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }.let { if (it < 0) rest.length else it }
        val authority = rest.substring(0, authorityEnd)
        val tail = rest.substring(authorityEnd)
        if (authority.isEmpty() || authority.any { it == '@' || it == '[' || it == ']' || it == '\\' }) return UrlCheck.Invalid

        val colon = authority.lastIndexOf(':')
        val hostPart = if (colon >= 0) authority.substring(0, colon) else authority
        val port = if (colon >= 0) {
            val text = authority.substring(colon + 1)
            val number = text.takeIf { it.isNotEmpty() && it.length <= 5 && it.all { c -> c in '0'..'9' } }?.toInt()
            if (number == null || number !in 1..65535) return UrlCheck.Invalid
            number
        } else {
            null
        }

        val host = try {
            IDN.toASCII(hostPart.lowercase())
        } catch (e: IllegalArgumentException) {
            return UrlCheck.Invalid
        }
        if (host.isEmpty() || host.length > 253 || !HOST.matches(host)) return UrlCheck.Invalid

        val url = "https://" + host + (port?.let { ":$it" } ?: "") + tail
        return try {
            URI(url)
            UrlCheck.Ok(url)
        } catch (e: URISyntaxException) {
            UrlCheck.Invalid
        }
    }

    /** Bereinigter Name (Leerraum am Rand entfernt, auf [MAX_NAME_LENGTH] gekürzt) oder `null`, wenn er leer ist. */
    fun cleanName(name: String): String? =
        name.filterNot { it == RECORD || it == FIELD || it.isISOControl() }.trim().take(MAX_NAME_LENGTH).trim().takeIf { it.isNotEmpty() }

    /** Anfangsbuchstabe (ganzes Zeichen, auch außerhalb der Grundebene) in Großschreibung für die Ersatzkachel. */
    fun initial(name: String): String {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return "?"
        return String(Character.toChars(trimmed.codePointAt(0))).uppercase()
    }

    /** Stabiler Farbton 0 bis 359 aus dem Namen für die Ersatzkachel. */
    fun hue(name: String): Float = (name.trim().lowercase().hashCode().toLong().let { Math.floorMod(it, 360L) }).toFloat()

    fun canAdd(apps: List<WebApp>): Boolean = apps.size < MAX_APPS

    /** Hängt [app] an. Ist die Höchstzahl erreicht oder die Kennung schon vergeben, bleibt die Liste unverändert. */
    fun add(apps: List<WebApp>, app: WebApp): List<WebApp> =
        if (!canAdd(apps) || apps.any { it.id == app.id } || !isSafeId(app.id)) apps else apps + app

    /** Eintrag, der auf dieselbe Verknüpfung verweist wie [pin], oder `null`. */
    fun findPin(apps: List<WebApp>, pin: PinnedShortcut): WebApp? = apps.firstOrNull { it.pin == pin }

    /** Ersetzt den Eintrag mit der Kennung von [app]; unbekannte Kennungen lassen die Liste unverändert. */
    fun replace(apps: List<WebApp>, app: WebApp): List<WebApp> = apps.map { if (it.id == app.id) app else it }

    fun delete(apps: List<WebApp>, id: String): List<WebApp> = apps.filterNot { it.id == id }

    /** Verschiebt den Eintrag um [delta] Positionen (negativ = nach vorn). */
    fun move(apps: List<WebApp>, id: String, delta: Int): List<WebApp> = ListOrder.moveBy(apps, delta) { it.id == id }

    fun encode(apps: List<WebApp>): String =
        apps.joinToString(RECORD.toString()) { app ->
            listOf(
                app.id,
                app.name.filterNot { it == RECORD || it == FIELD },
                app.url.filterNot { it == RECORD || it == FIELD },
                if (app.customTab) "1" else "0",
                if (app.hasIcon) "1" else "0",
                app.pin?.packageName.orEmpty(),
                app.pin?.shortcutId.orEmpty(),
                app.pin?.userSerial?.toString().orEmpty(),
            ).joinToString(FIELD.toString())
        }

    /**
     * Liest die gespeicherte Form. Einträge mit unsicherer Kennung, leerem Namen, doppelter Kennung oder einer
     * Adresse, die [checkUrl] nicht annimmt, werden übersprungen; höchstens [MAX_APPS] Einträge.
     */
    fun decode(raw: String?): List<WebApp> {
        val seen = HashSet<String>()
        return raw.orEmpty().split(RECORD).mapNotNull { record ->
            val parts = record.split(FIELD)
            // Frühere Fassung ohne Verknüpfungen: 5 Felder; mit Verknüpfungen: 8 Felder.
            if (parts.size != 5 && parts.size != 8) return@mapNotNull null
            val id = parts[0]
            if (!isSafeId(id)) return@mapNotNull null
            val name = cleanName(parts[1]) ?: return@mapNotNull null
            val pin = if (parts.size == 8 && parts[5].isNotEmpty()) {
                val candidate = PinnedShortcut(parts[5], parts[6], parts[7].toLongOrNull() ?: return@mapNotNull null)
                if (!isValidPin(candidate)) return@mapNotNull null
                candidate
            } else {
                null
            }
            // Bei einer Verknüpfung ist die Adresse leer (der Intent ist nicht lesbar); sonst muss sie gültig sein.
            val url = if (pin != null) "" else (checkUrl(parts[2]) as? UrlCheck.Ok)?.url ?: return@mapNotNull null
            if (!seen.add(id)) return@mapNotNull null
            WebApp(id, name, url, customTab = pin == null && parts[3] == "1", hasIcon = parts[4] == "1", pin = pin)
        }.take(MAX_APPS)
    }

    /** Ersetzt die Liste vollständig (Import eines Backups): ungültige und doppelte Einträge entfallen. */
    fun sanitize(apps: List<WebApp>): List<WebApp> = decode(encode(apps))
}
