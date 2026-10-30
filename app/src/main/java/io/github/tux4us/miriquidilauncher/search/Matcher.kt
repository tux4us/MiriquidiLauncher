// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.search

/**
 * Bewertet, wie gut ein Suchbegriff zu einem Kandidaten passt.
 *
 * Beide Argumente müssen mit [TextNormalizer.normalize] vorverarbeitet sein.
 * Höhere Werte bedeuten bessere Treffer; [NO_MATCH] bedeutet kein Treffer.
 * Die Stufen liegen mindestens 50 Punkte auseinander, der Längenabzug beträgt höchstens
 * 49 Punkte. Dadurch bleibt die Reihenfolge der Trefferklassen stabil und bei gleicher
 * Klasse gewinnt der kürzere Kandidat.
 */
object Matcher {
    const val NO_MATCH = 0

    const val EXACT = 1000
    const val PREFIX = 900
    const val WORD_PREFIX = 800
    const val ALL_TOKENS_PREFIX = 700
    const val INITIALS = 600
    const val SUBSTRING = 500

    private const val MAX_LENGTH_PENALTY = 49
    private val WORD_SEPARATOR = Regex("[^\\p{L}\\p{N}]+")

    fun score(query: String, candidate: String): Int {
        if (query.isEmpty() || candidate.isEmpty()) return NO_MATCH

        val base = baseScore(query, candidate)
        if (base == NO_MATCH) return NO_MATCH
        return base - candidate.length.coerceAtMost(MAX_LENGTH_PENALTY)
    }

    private fun baseScore(query: String, candidate: String): Int {
        if (candidate == query) return EXACT
        if (candidate.startsWith(query)) return PREFIX

        val words = candidate.split(WORD_SEPARATOR).filter { it.isNotEmpty() }
        val tokens = query.split(WORD_SEPARATOR).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return NO_MATCH

        if (tokens.size == 1 && words.any { it.startsWith(tokens[0]) }) return WORD_PREFIX
        if (tokens.size > 1 && tokens.all { token -> words.any { it.startsWith(token) } }) {
            return ALL_TOKENS_PREFIX
        }

        if (query.length >= 2 && words.size >= 2) {
            val initials = words.joinToString("") { it.take(1) }
            if (initials.startsWith(query)) return INITIALS
        }

        if (candidate.contains(query)) return SUBSTRING
        return NO_MATCH
    }
}
