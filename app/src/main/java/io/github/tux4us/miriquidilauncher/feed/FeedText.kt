// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.feed

/** Aufbereitung von Feed-HTML für Vorschau und Leseansicht. Reine Textverarbeitung, ohne Android-Bezug. */
object FeedText {
    private val BLOCKS_TO_DROP = Regex(
        "<(script|style|iframe|object|embed|form|noscript|template)\\b.*?</\\1\\s*>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val TAGS_TO_DROP = Regex(
        "</?(script|style|iframe|object|embed|form|noscript|template|link|meta|base)\\b[^>]*>",
        RegexOption.IGNORE_CASE,
    )
    private val EVENT_ATTRIBUTES = Regex("\\son[a-z]+\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>]+)", RegexOption.IGNORE_CASE)
    private val SCRIPT_URLS = Regex("(href|src)\\s*=\\s*([\"'])\\s*(javascript|vbscript|data:text/html)[^\"']*\\2", RegexOption.IGNORE_CASE)
    private val LINE_BREAK_TAGS = Regex("<(br|/p|/div|/li|/h[1-6]|/tr|/blockquote)\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val ANY_TAG = Regex("<[^>]*>")
    private val ENTITY = Regex("&(#x[0-9a-fA-F]{1,6}|#[0-9]{1,7}|[a-zA-Z][a-zA-Z0-9]{1,9});")
    private val WHITESPACE = Regex("\\s+")
    private val IMG_TAG = Regex("<img\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val SRC_ATTRIBUTE = Regex("\\bsrc\\s*=\\s*([\"'])(.*?)\\1", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val TINY_SIZE = Regex("\\b(width|height)\\s*=\\s*[\"']?\\s*[0-2]\\s*(px)?\\s*[\"'\\s/>]", RegexOption.IGNORE_CASE)

    private val NAMED_ENTITIES = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "hellip" to "…", "ndash" to "–", "mdash" to "—", "laquo" to "«", "raquo" to "»",
        "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“", "rdquo" to "”", "bdquo" to "„", "sbquo" to "‚",
        "auml" to "ä", "ouml" to "ö", "uuml" to "ü", "Auml" to "Ä", "Ouml" to "Ö", "Uuml" to "Ü", "szlig" to "ß",
        "euro" to "€", "copy" to "©", "reg" to "®", "middot" to "·", "bull" to "•", "deg" to "°",
    )

    /**
     * Anfang des HTML für Vorschauen: höchstens [maxChars] Zeichen, ein abgeschnittenes Tag am Ende wird
     * entfernt. So bleibt die Vorschau auch bei sehr langen Artikeln schnell.
     */
    fun head(html: String, maxChars: Int): String {
        if (html.length <= maxChars) return html
        val cut = html.substring(0, maxChars)
        val open = cut.lastIndexOf('<')
        return if (open > cut.lastIndexOf('>')) cut.substring(0, open) else cut
    }

    /** Sichtbarer Text ohne Auszeichnung: Tags entfernt, Entitäten aufgelöst, Leerraum zusammengezogen. */
    fun plainText(html: String): String {
        val withoutBlocks = BLOCKS_TO_DROP.replace(html, " ")
        val withBreaks = LINE_BREAK_TAGS.replace(withoutBlocks, " ")
        return WHITESPACE.replace(decodeEntities(ANY_TAG.replace(withBreaks, " ")), " ").trim()
    }

    /** Textanfang für die Card: höchstens [maxChars] Zeichen, am Wortende gekürzt, mit "…" bei Kürzung. */
    fun excerpt(html: String, maxChars: Int): String {
        val text = plainText(html)
        if (text.length <= maxChars) return text
        val cut = text.take(maxChars)
        val atWord = cut.lastIndexOf(' ')
        return (if (atWord > maxChars / 2) cut.substring(0, atWord) else cut).trimEnd(',', ';', ':', '.', '-', ' ') + "…"
    }

    /** Adresse des ersten brauchbaren Bildes (nur HTTPS, keine 1×1-Zählpixel) oder `null`. */
    fun firstImageUrl(html: String): String? {
        for (tag in IMG_TAG.findAll(BLOCKS_TO_DROP.replace(html, " "))) {
            if (TINY_SIZE.containsMatchIn(tag.value)) continue
            val src = SRC_ATTRIBUTE.find(tag.value)?.groupValues?.get(2)?.let { decodeEntities(it).trim() } ?: continue
            if (src.startsWith("https://", ignoreCase = true)) return src
        }
        return null
    }

    /**
     * Entfernt aktive Inhalte aus dem Artikel-HTML (Skripte, Rahmen, Formulare, Ereignis-Attribute,
     * `javascript:`-Adressen). Das ist eine zusätzliche Schutzschicht: Die Leseansicht läuft ohnehin ohne
     * JavaScript und mit einer Content-Security-Policy.
     */
    fun sanitize(html: String): String {
        var out = BLOCKS_TO_DROP.replace(html, "")
        out = TAGS_TO_DROP.replace(out, "")
        out = EVENT_ATTRIBUTES.replace(out, "")
        out = SCRIPT_URLS.replace(out) { "${it.groupValues[1]}=\"#\"" }
        return out
    }

    fun escapeHtml(text: String): String = buildString(text.length) {
        for (c in text) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&#39;")
            else -> append(c)
        }
    }

    fun decodeEntities(text: String): String {
        if ('&' !in text) return text
        return ENTITY.replace(text) { match ->
            val name = match.groupValues[1]
            when {
                name.startsWith("#x") || name.startsWith("#X") -> codePoint(name.substring(2).toIntOrNull(16), match.value)
                name.startsWith("#") -> codePoint(name.substring(1).toIntOrNull(), match.value)
                else -> NAMED_ENTITIES[name] ?: match.value
            }
        }
    }

    private fun codePoint(value: Int?, original: String): String =
        if (value != null && value in 1..0x10FFFF && value !in 0xD800..0xDFFF) String(Character.toChars(value)) else original
}
