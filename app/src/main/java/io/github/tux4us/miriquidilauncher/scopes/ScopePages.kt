// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.scopes

/**
 * Seitenfolge des seitlichen Pagers: die Startseite (Seite 0) und danach die sichtbaren Scopes in ihrer Reihenfolge.
 * Scopes werden hier über ihre Kennung (Name) geführt, damit die Logik ohne Android-Klassen testbar bleibt.
 */
data class ScopePages(val visible: List<String>) {
    /** Anzahl der Seiten einschließlich der Startseite. */
    val count: Int get() = visible.size + 1

    /** Seite der Scope [id]; `null`, wenn sie ausgeblendet ist. */
    fun pageOf(id: String): Int? = visible.indexOf(id).takeIf { it >= 0 }?.plus(1)

    /** Kennung der Scope auf [page]; `null` für die Startseite und für Seiten außerhalb. */
    fun idAt(page: Int): String? = visible.getOrNull(page - 1)

    companion object {
        const val LAUNCHER = 0
    }
}

/** Welche Scopes angezeigt werden. Ausgeblendet wird über eine Menge von Kennungen; unverzichtbare Scopes bleiben immer sichtbar. */
object ScopeVisibility {

    /** [order] ohne die ausgeblendeten Scopes; Einträge aus [pinned] sind nie ausgeblendet. */
    fun visible(order: List<String>, hidden: Set<String>, pinned: Set<String>): List<String> =
        order.filter { it in pinned || it !in hidden }

    /** Menge der ausgeblendeten Scopes nach dem Ein- oder Ausschalten von [id]; angeheftete Scopes ändern nichts. */
    fun withVisible(hidden: Set<String>, id: String, visible: Boolean, pinned: Set<String>): Set<String> =
        when {
            id in pinned -> hidden
            visible -> hidden - id
            else -> hidden + id
        }

    fun encode(hidden: Set<String>): String = hidden.sorted().joinToString(",")

    /** Liest die gespeicherte Menge; unbekannte Kennungen (z. B. aus einer anderen Version) werden verworfen. */
    fun decode(text: String?, known: Set<String>): Set<String> =
        text.orEmpty().split(',').map { it.trim() }.filter { it in known }.toSet()
}
