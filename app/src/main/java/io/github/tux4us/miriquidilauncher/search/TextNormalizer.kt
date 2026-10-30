// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.search

import java.text.Normalizer
import java.util.Locale

/**
 * Normalisiert Text für den Vergleich: Unicode-Zerlegung (NFD), Entfernen von Akzenten
 * und Umlaut-Punkten, "ß" → "ss", Kleinschreibung, Zusammenfassen von Leerraum.
 *
 * Beispiel: "Café Müller" → "cafe muller".
 */
object TextNormalizer {
    private val COMBINING_MARKS = Regex("\\p{InCombiningDiacriticalMarks}+")
    private val WHITESPACE = Regex("\\s+")

    fun normalize(input: String): String {
        if (input.isEmpty()) return input
        val decomposed = Normalizer.normalize(input, Normalizer.Form.NFD)
        return COMBINING_MARKS.replace(decomposed, "")
            .replace("ß", "ss")
            .lowercase(Locale.ROOT)
            .trim()
            .replace(WHITESPACE, " ")
    }
}
