// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.gallery.edit

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle

/** Die bearbeitbaren Angaben einer Bilddatei (EXIF). [dateOriginal] im EXIF-Format `yyyy:MM:dd HH:mm:ss` oder leer. */
data class MetadataFields(
    val description: String = "",
    val artist: String = "",
    val copyright: String = "",
    val dateOriginal: String = "",
    val hasGps: Boolean = false,
)

/** Ergebnis der Planung: Welche EXIF-Felder werden gesetzt (`null` = entfernen), ob der Standort entfernt wird. */
sealed interface MetadataPlan {
    data object InvalidDate : MetadataPlan

    data class Ok(val set: Map<String, String?>, val removeGps: Boolean, val takenMillis: Long?) : MetadataPlan {
        val isEmpty: Boolean get() = set.isEmpty() && !removeGps
    }
}

/** Auswertung und Aufbereitung der Metadaten ohne Android-Klassen. */
object MetadataLogic {
    const val TAG_DESCRIPTION = "ImageDescription"
    const val TAG_ARTIST = "Artist"
    const val TAG_COPYRIGHT = "Copyright"
    const val TAG_DATE_ORIGINAL = "DateTimeOriginal"
    const val TAG_DATE_DIGITIZED = "DateTimeDigitized"

    /** Alle Felder, die einen Standort verraten können. */
    val GPS_TAGS: List<String> = listOf(
        "GPSLatitude", "GPSLatitudeRef", "GPSLongitude", "GPSLongitudeRef", "GPSAltitude", "GPSAltitudeRef",
        "GPSTimeStamp", "GPSDateStamp", "GPSProcessingMethod", "GPSSpeed", "GPSSpeedRef", "GPSImgDirection",
        "GPSImgDirectionRef", "GPSDestLatitude", "GPSDestLongitude", "GPSAreaInformation", "GPSMapDatum", "GPSVersionID",
    )

    private const val MAX_TEXT = 500

    private val EXIF_FORMAT = DateTimeFormatter.ofPattern("uuuu:MM:dd HH:mm:ss").withResolverStyle(ResolverStyle.STRICT)
    private val ISO_MINUTES = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm").withResolverStyle(ResolverStyle.STRICT)
    private val ISO_SECONDS = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withResolverStyle(ResolverStyle.STRICT)
    private val GERMAN_MINUTES = DateTimeFormatter.ofPattern("dd.MM.uuuu HH:mm").withResolverStyle(ResolverStyle.STRICT)
    private val GERMAN_SECONDS = DateTimeFormatter.ofPattern("dd.MM.uuuu HH:mm:ss").withResolverStyle(ResolverStyle.STRICT)

    /** Datum aus Eingabe (`2026-11-24 14:30`, `24.11.2026 14:30`, mit oder ohne Sekunden, oder EXIF-Format); `null` bei Fehler. */
    fun parseDate(text: String): LocalDateTime? {
        val t = text.trim()
        if (t.isEmpty()) return null
        for (f in listOf(ISO_MINUTES, ISO_SECONDS, GERMAN_MINUTES, GERMAN_SECONDS, EXIF_FORMAT)) {
            try {
                return LocalDateTime.parse(t, f)
            } catch (e: DateTimeParseException) {
                // nächstes Format
            }
        }
        return null
    }

    fun toExifDate(value: LocalDateTime): String = EXIF_FORMAT.format(value)

    fun fromExifDate(text: String): LocalDateTime? = try {
        LocalDateTime.parse(text.trim(), EXIF_FORMAT)
    } catch (e: DateTimeParseException) {
        null
    }

    /** Anzeige in der Eingabe: `24.11.2026 14:30`; leer, wenn kein gültiges Datum vorliegt. */
    fun displayDate(exifDate: String): String = fromExifDate(exifDate)?.let { GERMAN_MINUTES.format(it) }.orEmpty()

    /** Text ohne Steuerzeichen und Zeilenumbrüche, gekürzt. */
    fun cleanText(text: String): String =
        text.filter { it.code >= 32 && it.code != 127 }.trim().take(MAX_TEXT)

    /**
     * Bestimmt aus den bisherigen Angaben [old] und den Eingaben, was zu schreiben ist. Nur Geändertes wird gesetzt; ein
     * leerer Text entfernt das Feld. [dateText] leer oder unverändert lässt das Datum bestehen.
     */
    fun plan(
        old: MetadataFields,
        description: String,
        artist: String,
        copyright: String,
        dateText: String,
        removeGps: Boolean,
        zone: ZoneId,
    ): MetadataPlan {
        val set = LinkedHashMap<String, String?>()
        fun text(tag: String, before: String, input: String) {
            val now = cleanText(input)
            if (now != cleanText(before)) set[tag] = now.ifEmpty { null }
        }
        text(TAG_DESCRIPTION, old.description, description)
        text(TAG_ARTIST, old.artist, artist)
        text(TAG_COPYRIGHT, old.copyright, copyright)
        var taken: Long? = null
        val unchangedDate = dateText.trim() == displayDate(old.dateOriginal) || dateText.isBlank()
        if (!unchangedDate) {
            val parsed = parseDate(dateText) ?: return MetadataPlan.InvalidDate
            val exif = toExifDate(parsed)
            if (exif != old.dateOriginal) {
                set[TAG_DATE_ORIGINAL] = exif
                set[TAG_DATE_DIGITIZED] = exif
                taken = parsed.atZone(zone).toInstant().toEpochMilli()
            }
        }
        return MetadataPlan.Ok(set, removeGps && old.hasGps, taken)
    }
}
