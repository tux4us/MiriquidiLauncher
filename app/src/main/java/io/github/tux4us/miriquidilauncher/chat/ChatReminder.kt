// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.chat

import java.net.URI
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Ein Kalender des Nextcloud-Kontos aus der CalDAV-Antwort; [href] ist der Pfad auf dem Server. */
data class RemoteCalendar(val href: String, val name: String)

/**
 * Erinnerung an eine Nachricht als Termin im Nextcloud-Kalender (CalDAV). Ohne Android-Klassen, damit Aufbau der
 * iCalendar-Datei und Auswertung der Antwort auf der JVM testbar sind.
 */
object ChatReminder {
    /** Dauer des Termins in Minuten. */
    const val DURATION_MINUTES = 30

    private const val SUMMARY_CHARS = 80
    private const val DESCRIPTION_CHARS = 2_000

    private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

    /** Anfragekörper, der die Kalender mit Anzeigename, Typ und unterstützten Komponenten abfragt. */
    const val PROPFIND_BODY: String =
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<d:propfind xmlns:d=\"DAV:\" xmlns:c=\"urn:ietf:params:xml:ns:caldav\"><d:prop>" +
            "<d:resourcetype/><d:displayname/><c:supported-calendar-component-set/></d:prop></d:propfind>"

    /** Adresse der Kalenderablage des Benutzers [userId]. */
    fun calendarHomeUrl(baseUrl: String, userId: String): String =
        "$baseUrl/remote.php/dav/calendars/" + java.net.URLEncoder.encode(userId, "UTF-8").replace("+", "%20") + "/"

    private val RESPONSE = Regex("(?s)<(?:\\w+:)?response\\b.*?</(?:\\w+:)?response>")
    private val HREF = Regex("(?s)<(?:\\w+:)?href>(.*?)</(?:\\w+:)?href>")
    private val NAME = Regex("(?s)<(?:\\w+:)?displayname>(.*?)</(?:\\w+:)?displayname>")
    private val IS_CALENDAR = Regex("<(?:\\w+:)?calendar\\s*/>")
    private val HAS_EVENTS = Regex("<(?:\\w+:)?comp\\s+name=[\"']VEVENT[\"']\\s*/?>")

    /** Kalender, die Termine (VEVENT) aufnehmen; Geburtstagskalender (nur lesbar) und Papierkorb sind ausgenommen. */
    fun parseCalendars(xml: String): List<RemoteCalendar> =
        RESPONSE.findAll(xml).mapNotNull { m ->
            val block = m.value
            if (!IS_CALENDAR.containsMatchIn(block) || !HAS_EVENTS.containsMatchIn(block)) return@mapNotNull null
            val href = HREF.find(block)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            if (href.contains("contact_birthdays") || href.contains("trashbin")) return@mapNotNull null
            val name = NAME.find(block)?.groupValues?.get(1)?.let(::unescapeXml)?.trim().orEmpty()
            RemoteCalendar(href.let { if (it.endsWith("/")) it else "$it/" }, name.ifEmpty { href.trimEnd('/').substringAfterLast('/') })
        }.toList()

    /** Der Standardkalender „personal“, sonst der erste; `null` ohne Kalender. */
    fun pickCalendar(calendars: List<RemoteCalendar>): RemoteCalendar? =
        calendars.firstOrNull { it.href.trimEnd('/').endsWith("/personal") } ?: calendars.firstOrNull()

    private fun unescapeXml(s: String): String =
        s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

    /**
     * Löst den Pfad [href] aus der Serverantwort gegen [baseUrl] auf. Nur `https` und derselbe Host sind erlaubt, sonst
     * `null`: Zugangsdaten gehen nie an einen anderen Server.
     */
    fun resolveHref(baseUrl: String, href: String): String? = try {
        val base = URI(baseUrl)
        val target = base.resolve(href)
        if (target.scheme.equals("https", ignoreCase = true) && target.host.equals(base.host, ignoreCase = true)) target.toString() else null
    } catch (e: Exception) {
        null
    }

    /** Dateiname des Termins im Kalender. */
    fun fileName(uid: String): String = "$uid.ics"

    /** Titel des Termins: erste nicht leere Zeile der Nachricht, gekürzt; ohne Text der Name des Gesprächs. */
    fun summary(messageText: String, roomName: String): String {
        val line = messageText.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
        return (line ?: roomName.ifBlank { "Talk" }).take(SUMMARY_CHARS)
    }

    /** Beschreibung mit Absender, Gespräch, vollem Text und Link (sofern vorhanden). */
    fun description(messageText: String, author: String, roomName: String): String {
        val head = listOf(author, roomName).filter { it.isNotBlank() }.joinToString(" · ")
        return (if (head.isEmpty()) messageText else "$head\n\n$messageText").take(DESCRIPTION_CHARS)
    }

    /** Link auf die Nachricht in Talk. */
    fun talkLink(baseUrl: String, token: String, messageId: Long): String = "$baseUrl/call/$token#message_$messageId"

    /**
     * Die iCalendar-Datei (RFC 5545) für einen Termin ab [startMillis] mit Anzeige-Erinnerung zum Beginn. Zeilen sind
     * auf 75 Byte gefaltet, Zeilenenden sind CRLF.
     */
    fun buildIcs(uid: String, summary: String, description: String, url: String?, startMillis: Long, nowMillis: Long): String {
        val start = Instant.ofEpochMilli(startMillis)
        val end = start.plusSeconds(DURATION_MINUTES * 60L)
        val lines = mutableListOf(
            "BEGIN:VCALENDAR",
            "VERSION:2.0",
            "PRODID:-//MiriquidiLauncher//Chat//DE",
            "CALSCALE:GREGORIAN",
            "BEGIN:VEVENT",
            "UID:${cleanUid(uid)}",
            "DTSTAMP:${STAMP.format(Instant.ofEpochMilli(nowMillis))}",
            "DTSTART:${STAMP.format(start)}",
            "DTEND:${STAMP.format(end)}",
            "SUMMARY:${escapeText(summary)}",
            "DESCRIPTION:${escapeText(description)}",
        )
        if (url != null && ChatApi.isHttps(url)) lines += "URL:${url.filter { it.code in 33..126 }}"
        lines += listOf(
            "BEGIN:VALARM",
            "ACTION:DISPLAY",
            "DESCRIPTION:${escapeText(summary)}",
            "TRIGGER:PT0S",
            "END:VALARM",
            "END:VEVENT",
            "END:VCALENDAR",
        )
        return lines.joinToString("") { fold(it) + "\r\n" }
    }

    private fun cleanUid(uid: String): String = uid.filter { it.isLetterOrDigit() || it == '-' || it == '@' || it == '.' }

    /** Schützt Zeichen mit besonderer Bedeutung (RFC 5545, 3.3.11) und entfernt Steuerzeichen. */
    fun escapeText(text: String): String {
        val sb = StringBuilder()
        for (ch in text) {
            when {
                ch == '\\' -> sb.append("\\\\")
                ch == ';' -> sb.append("\\;")
                ch == ',' -> sb.append("\\,")
                ch == '\n' -> sb.append("\\n")
                ch == '\r' -> Unit
                ch.code < 32 && ch != '\t' -> Unit
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    /** Faltet eine Zeile nach 75 Byte (UTF-8); Fortsetzungszeilen beginnen mit einem Leerzeichen. Zeichen werden nie geteilt. */
    fun fold(line: String): String {
        val sb = StringBuilder()
        var bytes = 0
        var limit = 75
        var i = 0
        while (i < line.length) {
            val cp = line.codePointAt(i)
            val piece = String(Character.toChars(cp))
            val n = piece.toByteArray(Charsets.UTF_8).size
            if (bytes + n > limit) {
                sb.append("\r\n ")
                bytes = 0
                limit = 74
            }
            sb.append(piece)
            bytes += n
            i += Character.charCount(cp)
        }
        return sb.toString()
    }
}
