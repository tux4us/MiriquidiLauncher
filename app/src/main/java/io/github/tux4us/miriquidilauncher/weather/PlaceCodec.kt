// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.weather

/**
 * Textdarstellung einer Ortsliste für DataStore. Trennzeichen sind die ASCII-Steuerzeichen 0x1E
 * (Datensatz) und 0x1F (Feld); sie werden aus Namen entfernt.
 */
object PlaceCodec {
    private const val RECORD = '\u001E'
    private const val FIELD = '\u001F'

    fun encode(places: List<GeoPlace>): String =
        places.joinToString(RECORD.toString()) { encodeOne(it) }

    fun encodeOne(place: GeoPlace): String =
        "${clean(place.name)}$FIELD${place.latitude}$FIELD${place.longitude}"

    fun decode(raw: String?): List<GeoPlace> =
        raw.orEmpty().split(RECORD).mapNotNull { decodeOne(it) }

    fun decodeOne(record: String?): GeoPlace? {
        val parts = record?.split(FIELD) ?: return null
        if (parts.size != 3) return null
        val lat = parts[1].toDoubleOrNull() ?: return null
        val lon = parts[2].toDoubleOrNull() ?: return null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0 || parts[0].isBlank()) return null
        return GeoPlace(parts[0], lat, lon)
    }

    /** Fügt [place] hinzu, falls der Ort (gleiche Koordinaten auf ca. 1 km) noch nicht enthalten ist. */
    fun add(list: List<GeoPlace>, place: GeoPlace): List<GeoPlace> =
        if (list.any { it.key == place.key }) list else list + place

    fun remove(list: List<GeoPlace>, place: GeoPlace): List<GeoPlace> = list.filterNot { it.key == place.key }

    private fun clean(text: String): String = text.filterNot { it == RECORD || it == FIELD }
}
