// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.data

/** Hilfsfunktionen zum Umsortieren von Favoritenlisten; ohne Android-Abhängigkeit. */
object ListOrder {
    /**
     * Verschiebt das Element an Position [from] nach [to]. Liegt [from] außerhalb der Liste, bleibt sie
     * unverändert; [to] wird auf den gültigen Bereich begrenzt.
     */
    fun <T> move(list: List<T>, from: Int, to: Int): List<T> {
        if (from !in list.indices) return list
        val target = to.coerceIn(0, list.lastIndex)
        if (target == from) return list
        val result = list.toMutableList()
        result.add(target, result.removeAt(from))
        return result
    }

    /** Verschiebt das erste Element, für das [matches] gilt, um [delta] Positionen (negativ = nach oben). */
    fun <T> moveBy(list: List<T>, delta: Int, matches: (T) -> Boolean): List<T> {
        val index = list.indexOfFirst(matches)
        return if (index < 0) list else move(list, index, index + delta)
    }
}
