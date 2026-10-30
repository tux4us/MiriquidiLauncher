// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.notes

/** Ein Abschnitt Text mit Auszeichnung. [link] ist nur gesetzt, wenn die Adresse mit https, http oder mailto beginnt. */
data class MdSpan(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
    val link: String? = null,
)

sealed interface MdBlock {
    data class Heading(val level: Int, val spans: List<MdSpan>) : MdBlock
    data class Bullet(val indent: Int, val spans: List<MdSpan>) : MdBlock
    data class Numbered(val number: String, val spans: List<MdSpan>) : MdBlock

    /** Aufgabe; [lineIndex] ist die Zeile im Text (für [NotesMarkdown.toggleCheck]). */
    data class Check(val lineIndex: Int, val checked: Boolean, val spans: List<MdSpan>) : MdBlock
    data class Quote(val spans: List<MdSpan>) : MdBlock
    data class Code(val text: String) : MdBlock
    data class Paragraph(val spans: List<MdSpan>) : MdBlock
    data object Rule : MdBlock
}

/**
 * Kleiner Markdown-Leser für die Vorschau: Überschriften, Listen, Aufgaben (`- [ ]`), Zitate, Codeblöcke,
 * Trennlinien sowie fett, kursiv, Code und Links im Text. Kein HTML, keine Bilder, keine Tabellen.
 */
object NotesMarkdown {
    private val HEADING = Regex("^(#{1,6})\\s+(.*)$")
    private val CHECK = Regex("^(\\s*)[-*+]\\s+\\[( |x|X)]\\s+(.*)$")
    private val BULLET = Regex("^(\\s*)[-*+]\\s+(.*)$")
    private val NUMBERED = Regex("^\\s*(\\d+)[.)]\\s+(.*)$")
    private val RULE = Regex("^\\s*([-*_])(\\s*\\1){2,}\\s*$")
    private val ALLOWED_LINK = Regex("^(https?://|mailto:)\\S+$", RegexOption.IGNORE_CASE)

    fun parse(content: String): List<MdBlock> {
        val blocks = ArrayList<MdBlock>()
        val lines = content.lines()
        var inCode = false
        val code = StringBuilder()
        val paragraph = ArrayList<String>()
        fun flushParagraph() {
            if (paragraph.isNotEmpty()) {
                blocks.add(MdBlock.Paragraph(parseInline(paragraph.joinToString("\n"))))
                paragraph.clear()
            }
        }
        for ((i, line) in lines.withIndex()) {
            if (line.trimStart().startsWith("```")) {
                if (inCode) {
                    blocks.add(MdBlock.Code(code.toString().trimEnd('\n')))
                    code.clear()
                } else {
                    flushParagraph()
                }
                inCode = !inCode
                continue
            }
            if (inCode) {
                code.append(line).append('\n')
                continue
            }
            val heading = HEADING.matchEntire(line)
            val check = CHECK.matchEntire(line)
            val numbered = NUMBERED.matchEntire(line)
            val bullet = BULLET.matchEntire(line)
            when {
                line.isBlank() -> flushParagraph()
                heading != null -> { flushParagraph(); blocks.add(MdBlock.Heading(heading.groupValues[1].length, parseInline(heading.groupValues[2].trim()))) }
                RULE.matches(line) -> { flushParagraph(); blocks.add(MdBlock.Rule) }
                check != null -> { flushParagraph(); blocks.add(MdBlock.Check(i, check.groupValues[2] != " ", parseInline(check.groupValues[3]))) }
                bullet != null -> { flushParagraph(); blocks.add(MdBlock.Bullet(bullet.groupValues[1].length / 2, parseInline(bullet.groupValues[2]))) }
                numbered != null -> { flushParagraph(); blocks.add(MdBlock.Numbered(numbered.groupValues[1], parseInline(numbered.groupValues[2]))) }
                line.trimStart().startsWith(">") -> { flushParagraph(); blocks.add(MdBlock.Quote(parseInline(line.trimStart().removePrefix(">").trim()))) }
                else -> paragraph.add(line.trim())
            }
        }
        if (inCode && code.isNotEmpty()) blocks.add(MdBlock.Code(code.toString().trimEnd('\n')))
        flushParagraph()
        return blocks
    }

    /** Schaltet die Aufgabe in Zeile [lineIndex] um. Ist dort keine Aufgabe, bleibt der Text unverändert. */
    fun toggleCheck(content: String, lineIndex: Int): String {
        val lines = content.split("\n").toMutableList()
        val line = lines.getOrNull(lineIndex) ?: return content
        val m = CHECK.matchEntire(line.trimEnd('\r')) ?: return content
        val checked = m.groupValues[2] != " "
        val marker = Regex("\\[( |x|X)]")
        lines[lineIndex] = marker.replaceFirst(line, if (checked) "[ ]" else "[x]")
        return lines.joinToString("\n")
    }

    fun parseInline(text: String): List<MdSpan> {
        val spans = ArrayList<MdSpan>()
        val plain = StringBuilder()
        fun flush() {
            if (plain.isNotEmpty()) {
                spans.add(MdSpan(plain.toString()))
                plain.clear()
            }
        }
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                text.startsWith("**", i) || text.startsWith("__", i) -> {
                    val mark = text.substring(i, i + 2)
                    val end = text.indexOf(mark, i + 2)
                    if (end > i + 2) { flush(); spans.addAll(parseInline(text.substring(i + 2, end)).map { it.copy(bold = true) }); i = end + 2 } else { plain.append(c); i++ }
                }
                c == '`' -> {
                    val end = text.indexOf('`', i + 1)
                    if (end > i + 1) { flush(); spans.add(MdSpan(text.substring(i + 1, end), code = true)); i = end + 1 } else { plain.append(c); i++ }
                }
                (c == '*' || c == '_') && i + 1 < text.length && !text[i + 1].isWhitespace() -> {
                    val end = text.indexOf(c, i + 1)
                    if (end > i + 1 && !text[end - 1].isWhitespace()) { flush(); spans.addAll(parseInline(text.substring(i + 1, end)).map { it.copy(italic = true) }); i = end + 1 } else { plain.append(c); i++ }
                }
                c == '[' -> {
                    val close = text.indexOf(']', i + 1)
                    val open = if (close > 0 && close + 1 < text.length && text[close + 1] == '(') close + 1 else -1
                    val end = if (open > 0) text.indexOf(')', open + 1) else -1
                    val url = if (end > 0) text.substring(open + 1, end).trim() else ""
                    if (end > 0 && close > i + 1 && ALLOWED_LINK.matches(url)) {
                        flush()
                        spans.add(MdSpan(text.substring(i + 1, close), link = url))
                        i = end + 1
                    } else { plain.append(c); i++ }
                }
                else -> { plain.append(c); i++ }
            }
        }
        flush()
        return spans
    }
}
