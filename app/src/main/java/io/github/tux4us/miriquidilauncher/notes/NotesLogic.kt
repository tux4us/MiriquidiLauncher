// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.notes

import java.security.MessageDigest

/** Reine Funktionen der Notizen: Titel, Dateinamen, Kategorien, Auswahl und Sortierung. Ohne Android-Klassen. */
object NotesLogic {
    const val EXTENSION = ".md"
    val READ_EXTENSIONS = setOf("md", "txt")
    const val MAX_NOTE_CHARS = 500_000
    const val MAX_NOTES = 5000
    const val MAX_DEPTH = 5
    const val MAX_NAME = 80
    const val DEFAULT_BASE = "Notiz"

    private val ILLEGAL = Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]")
    private val MARKUP = Regex("^(#{1,6}\\s*|[-*+]\\s+|>\\s*|\\d+\\.\\s+|\\[[ xX]]\\s*)+")
    private val SUFFIX = Regex(" \\(\\d+\\)$")

    /** Erste nichtleere Zeile ohne Markdown-Auszeichnung am Zeilenanfang; leer, wenn der Inhalt leer ist. */
    fun titleOf(content: String): String {
        for (line in content.lineSequence()) {
            val text = line.trim().replace(MARKUP, "").replace("*", "").replace("`", "").trim()
            if (text.isNotEmpty()) return text.take(100)
        }
        return ""
    }

    /** Dateiname ohne Endung zu einem Titel: verbotene Zeichen werden `-`, Ränder und Punkte am Ende entfernt. */
    fun fileBase(title: String): String {
        val cleaned = title.replace(ILLEGAL, "-").replace(Regex("\\s+"), " ").trim().trimEnd('.', ' ').trimStart('.')
        val cut = cleaned.take(MAX_NAME).trimEnd('.', ' ')
        return cut.ifEmpty { DEFAULT_BASE }
    }

    /** Bereinigt eine Kategorie (`A/B`): leere und gefährliche Teile (`.`, `..`) entfallen, höchstens [MAX_DEPTH] Ebenen. */
    fun sanitizeCategory(raw: String): String =
        raw.split('/', '\\')
            .map { it.replace(ILLEGAL, "-").trim().trimEnd('.', ' ') }
            .filter { it.isNotEmpty() && it != "." && it != ".." && !it.startsWith(".") }
            .map { it.take(MAX_NAME) }
            .take(MAX_DEPTH)
            .joinToString("/")

    fun categoryOf(path: String): String = path.substringBeforeLast('/', "")

    fun fileNameOf(path: String): String = path.substringAfterLast('/')

    /** Dateiname ohne Endung. */
    fun baseNameOf(path: String): String = fileNameOf(path).substringBeforeLast('.', fileNameOf(path))

    fun extensionOf(path: String): String = fileNameOf(path).substringAfterLast('.', "").lowercase()

    fun isNotePath(path: String): Boolean = extensionOf(path) in READ_EXTENSIONS

    /** Ob [path] sicher relativ ist: keine `..`-Teile, kein Anfang mit `/`, keine Steuerzeichen. */
    fun isSafePath(path: String): Boolean =
        path.isNotEmpty() && !path.startsWith("/") && !path.contains('\\') && !path.contains(Regex("[\\u0000-\\u001f]")) &&
            path.split('/').none { it.isEmpty() || it == "." || it == ".." }

    /** Freier Pfad `Kategorie/Basis.md`, bei Kollision (Groß-/Kleinschreibung ignoriert) `Basis (1).md`, … */
    fun uniquePath(taken: Set<String>, category: String, base: String, extension: String = EXTENSION): String {
        val dir = if (category.isEmpty()) "" else "$category/"
        var candidate = "$dir$base$extension"
        var n = 1
        while (candidate.lowercase() in taken) {
            candidate = "$dir$base ($n)$extension"
            n++
        }
        return candidate
    }

    /** Menge der kleingeschriebenen Pfade, für [uniquePath]. */
    fun takenSet(paths: Collection<String>): Set<String> = paths.mapTo(HashSet()) { it.lowercase() }

    /**
     * Pfad nach dem Bearbeiten: Der Dateiname folgt dem Titel (erste Zeile). Ein Zähler (`(1)`) zählt nicht als
     * Abweichung, damit nicht bei jedem Speichern umbenannt wird. [taken] enthält die anderen Pfade.
     */
    fun pathAfterEdit(oldPath: String, newContent: String, taken: Set<String>): String {
        val title = titleOf(newContent)
        if (title.isEmpty()) return oldPath
        val wanted = fileBase(title)
        val current = baseNameOf(oldPath).replace(SUFFIX, "")
        if (wanted.equals(current, ignoreCase = true)) return oldPath
        val others = taken - oldPath.lowercase()
        return uniquePath(others, categoryOf(oldPath), wanted, "." + extensionOf(oldPath).ifEmpty { "md" })
    }

    /** Pfad nach dem Wechsel der Kategorie; der Dateiname bleibt, bei Kollision mit Zähler. */
    fun pathInCategory(oldPath: String, newCategory: String, taken: Set<String>): String {
        val category = sanitizeCategory(newCategory)
        if (category == categoryOf(oldPath)) return oldPath
        val base = baseNameOf(oldPath).replace(SUFFIX, "")
        return uniquePath(taken - oldPath.lowercase(), category, base, "." + extensionOf(oldPath).ifEmpty { "md" })
    }

    /** Vorschau: Text nach der Titelzeile in einer Zeile, höchstens [maxChars] Zeichen. */
    fun preview(content: String, maxChars: Int = 140): String {
        val lines = content.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        return lines.drop(1).joinToString(" ") { it.replace(MARKUP, "").replace("*", "").replace("`", "") }.trim().take(maxChars)
    }

    fun hash(content: String): String =
        MessageDigest.getInstance("SHA-256").digest(content.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    /** Kategorien mit Anzahl (genaue Kategorie, ohne Unterkategorien), alphabetisch; ohne die Wurzel. */
    fun categories(notes: List<Note>): List<Pair<String, Int>> =
        notes.map { it.category }.filter { it.isNotEmpty() }.groupingBy { it }.eachCount().toList().sortedBy { it.first.lowercase() }

    /**
     * Anzeigeliste: Favoriten zuerst, dann nach [sort]. [category] `null` = alle, sonst diese Kategorie samt
     * Unterkategorien. Die Suche prüft Titel, Kategorie und Inhalt ohne Beachtung der Schreibweise.
     */
    fun visible(
        notes: List<Note>,
        query: String,
        category: String?,
        favoritesOnly: Boolean,
        sort: NoteSort,
        ascending: Boolean,
    ): List<Note> {
        val needle = query.trim().lowercase()
        val filtered = notes.filter { n ->
            (category == null || n.category == category || n.category.startsWith("$category/")) &&
                (!favoritesOnly || n.favorite) &&
                (needle.isEmpty() || n.title.lowercase().contains(needle) || n.category.lowercase().contains(needle) || n.content.lowercase().contains(needle))
        }
        val comparator: Comparator<Note> = when (sort) {
            NoteSort.MODIFIED -> compareBy<Note> { it.modifiedMillis }
            NoteSort.TITLE -> compareBy<Note, String>(String.CASE_INSENSITIVE_ORDER) { it.title }
        }
        val ordered = filtered.sortedWith(if (ascending) comparator else comparator.reversed())
        return ordered.filter { it.favorite } + ordered.filter { !it.favorite }
    }
}
