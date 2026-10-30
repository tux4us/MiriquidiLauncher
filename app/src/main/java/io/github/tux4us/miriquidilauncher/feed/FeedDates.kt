// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.feed

import java.time.DateTimeException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException

/**
 * Datumsangaben aus Feeds: RFC 822 (RSS: `Tue, 10 Jun 2025 04:00:00 +0200`) und ISO 8601 (Atom, JSON Feed,
 * Dublin Core: `2025-06-10T04:00:00+02:00`). Ohne Zeitzone wird UTC angenommen.
 */
object FeedDates {
    private val RFC822 = Regex(
        "^(?:[A-Za-z]{3,9},?\\s+)?(\\d{1,2})\\s+([A-Za-z]{3,9})\\.?\\s+(\\d{2,4})" +
            "(?:\\s+(\\d{1,2}):(\\d{2})(?::(\\d{2}))?)?\\s*([A-Za-z]{1,5}|[+-]\\d{2}:?\\d{2})?$",
    )
    private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
    private val ZONES = mapOf(
        "z" to 0, "ut" to 0, "utc" to 0, "gmt" to 0,
        "est" to -5, "edt" to -4, "cst" to -6, "cdt" to -5, "mst" to -7, "mdt" to -6, "pst" to -8, "pdt" to -7,
        "cet" to 1, "cest" to 2,
    )

    /** Sekunden seit 1970 (UTC) oder `null`, wenn die Angabe nicht lesbar ist. */
    fun parse(raw: String?): Long? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        return rfc822(text) ?: iso(text)
    }

    private fun rfc822(text: String): Long? {
        val m = RFC822.matchEntire(text) ?: return null
        val month = MONTHS.indexOf(m.groupValues[2].take(3).lowercase()) + 1
        if (month == 0) return null
        val day = m.groupValues[1].toInt()
        var year = m.groupValues[3].toInt()
        if (m.groupValues[3].length <= 2) year += if (year < 50) 2000 else 1900
        val hour = m.groupValues[4].ifEmpty { "0" }.toInt()
        val minute = m.groupValues[5].ifEmpty { "0" }.toInt()
        val second = m.groupValues[6].ifEmpty { "0" }.toInt()
        val offset = offsetSeconds(m.groupValues[7]) ?: return null
        return try {
            LocalDateTime.of(year, month, day, hour, minute, second).toEpochSecond(ZoneOffset.ofTotalSeconds(offset))
        } catch (ignored: DateTimeException) {
            null
        }
    }

    private fun offsetSeconds(zone: String): Int? {
        if (zone.isEmpty()) return 0
        if (zone[0] == '+' || zone[0] == '-') {
            val digits = zone.substring(1).replace(":", "")
            if (digits.length != 4) return null
            val seconds = digits.substring(0, 2).toInt() * 3600 + digits.substring(2).toInt() * 60
            return if (zone[0] == '-') -seconds else seconds
        }
        return ZONES[zone.lowercase()]?.let { it * 3600 }
    }

    private fun iso(text: String): Long? {
        val normalized = if (text.length > 10 && text[10] == ' ') text.substring(0, 10) + "T" + text.substring(11) else text
        try {
            return OffsetDateTime.parse(normalized).toEpochSecond()
        } catch (ignored: DateTimeParseException) {
            // weiter mit den Formaten ohne Zeitzone
        }
        try {
            return LocalDateTime.parse(normalized).toEpochSecond(ZoneOffset.UTC)
        } catch (ignored: DateTimeParseException) {
            // weiter mit dem reinen Datum
        }
        return try {
            LocalDate.parse(normalized).atStartOfDay().toEpochSecond(ZoneOffset.UTC)
        } catch (ignored: DateTimeParseException) {
            null
        }
    }
}
