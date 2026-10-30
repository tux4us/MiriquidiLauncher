// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.feed

/** Das Dokument ist kein auswertbares XML oder überschreitet die Grenzen von [MiniXml]. */
class XmlException(message: String) : Exception(message)

/**
 * Ein Element. [name] ist der qualifizierte Name wie im Dokument (`content:encoded`), [local] der Name ohne
 * Präfix. [text] enthält den eigenen Text des Elements (Text und CDATA ohne die Kindelemente).
 */
class XmlNode(val name: String, val attributes: Map<String, String>) {
    val local: String = name.substringAfter(':')
    val children = ArrayList<XmlNode>()
    internal val textBuilder = StringBuilder()
    internal var innerStart = 0
    internal var innerEnd = 0
    internal var source: String = ""

    val text: String get() = textBuilder.toString().trim()

    /** Das innere XML als Zeichenfolge, z. B. für XHTML-Inhalte in Atom-Feeds. */
    val innerXml: String get() = if (innerEnd > innerStart) source.substring(innerStart, innerEnd) else ""

    /** Erstes Kindelement mit genau diesem qualifizierten Namen. */
    fun child(name: String): XmlNode? = children.firstOrNull { it.name == name }

    /** Erstes Kindelement mit diesem lokalen Namen, unabhängig vom Präfix. */
    fun childLocal(local: String): XmlNode? = children.firstOrNull { it.local == local }

    fun childrenNamed(name: String): List<XmlNode> = children.filter { it.name == name }

    fun childrenLocal(local: String): List<XmlNode> = children.filter { it.local == local }
}

/**
 * Kleiner, toleranter XML-Leser für Feeds und OPML-Dateien. Er verarbeitet weder DTDs noch Entity-Definitionen
 * oder externe Verweise: Der Dokumenttyp wird übersprungen, nur die fünf vordefinierten und numerische
 * Zeichenverweise werden aufgelöst. Damit sind Angriffe über Entity-Erweiterung (XXE, „Billion Laughs“)
 * ausgeschlossen. Tiefe, Elementanzahl und Größe sind begrenzt. Plattformunabhängig und auf der JVM testbar.
 */
object MiniXml {
    private const val MAX_DEPTH = 64
    private const val MAX_NODES = 200_000

    /** @throws XmlException wenn kein Wurzelelement gefunden wird oder eine Grenze überschritten ist */
    fun parse(text: String): XmlNode {
        val source = text.removePrefix("﻿")
        var root: XmlNode? = null
        val stack = ArrayList<XmlNode>()
        var nodes = 0
        var i = 0
        val n = source.length

        fun appendText(raw: String, decode: Boolean) {
            val top = stack.lastOrNull() ?: return
            top.textBuilder.append(if (decode) FeedText.decodeEntities(raw) else raw)
        }

        while (i < n) {
            val lt = source.indexOf('<', i)
            if (lt < 0) {
                appendText(source.substring(i), decode = true)
                break
            }
            if (lt > i) appendText(source.substring(i, lt), decode = true)
            i = lt
            when {
                source.startsWith("<!--", i) -> {
                    val end = source.indexOf("-->", i + 4)
                    i = if (end < 0) n else end + 3
                }
                source.startsWith("<![CDATA[", i) -> {
                    val end = source.indexOf("]]>", i + 9)
                    val stop = if (end < 0) n else end
                    appendText(source.substring(i + 9, stop), decode = false)
                    i = if (end < 0) n else end + 3
                }
                source.startsWith("<?", i) -> {
                    val end = source.indexOf("?>", i + 2)
                    i = if (end < 0) n else end + 2
                }
                source.startsWith("<!", i) -> i = skipDeclaration(source, i)
                source.startsWith("</", i) -> {
                    val end = source.indexOf('>', i + 2)
                    if (end < 0) {
                        i = n
                    } else {
                        val name = source.substring(i + 2, end).trim()
                        val index = stack.indexOfLast { it.name == name }
                        if (index >= 0) {
                            while (stack.size > index) stack.removeAt(stack.size - 1).innerEnd = lt
                        }
                        i = end + 1
                    }
                }
                else -> {
                    val tag = readTag(source, i) ?: throw XmlException("Ungültiges Element an Position $i")
                    if (++nodes > MAX_NODES) throw XmlException("Zu viele Elemente")
                    val node = XmlNode(tag.name, tag.attributes)
                    node.source = source
                    node.innerStart = tag.end
                    node.innerEnd = tag.end
                    val parent = stack.lastOrNull()
                    if (parent != null) parent.children.add(node) else if (root == null) root = node
                    if (!tag.selfClosing) {
                        if (stack.size >= MAX_DEPTH) throw XmlException("Zu tief verschachtelt")
                        stack.add(node)
                    }
                    i = tag.end
                }
            }
        }
        return root ?: throw XmlException("Kein Wurzelelement")
    }

    private class Tag(val name: String, val attributes: Map<String, String>, val selfClosing: Boolean, val end: Int)

    /** Überspringt `<!DOCTYPE …>` einschließlich einer inneren Teilmenge in eckigen Klammern. */
    private fun skipDeclaration(s: String, start: Int): Int {
        var i = start + 2
        var bracket = 0
        var quote = 0.toChar()
        while (i < s.length) {
            val c = s[i]
            when {
                quote != 0.toChar() -> if (c == quote) quote = 0.toChar()
                c == '"' || c == '\'' -> quote = c
                c == '[' -> bracket++
                c == ']' -> if (bracket > 0) bracket--
                c == '>' && bracket == 0 -> return i + 1
            }
            i++
        }
        return s.length
    }

    private fun readTag(s: String, start: Int): Tag? {
        var i = start + 1
        val n = s.length
        val nameStart = i
        while (i < n && !s[i].isWhitespace() && s[i] != '/' && s[i] != '>') i++
        if (i == nameStart) return null
        val name = s.substring(nameStart, i)
        val attributes = LinkedHashMap<String, String>()
        while (i < n) {
            while (i < n && s[i].isWhitespace()) i++
            if (i >= n) return null
            if (s[i] == '>') return Tag(name, attributes, false, i + 1)
            if (s[i] == '/') {
                val close = s.indexOf('>', i)
                return if (close < 0) null else Tag(name, attributes, true, close + 1)
            }
            val attrStart = i
            while (i < n && !s[i].isWhitespace() && s[i] != '=' && s[i] != '>' && s[i] != '/') i++
            val attrName = s.substring(attrStart, i)
            while (i < n && s[i].isWhitespace()) i++
            var value = ""
            if (i < n && s[i] == '=') {
                i++
                while (i < n && s[i].isWhitespace()) i++
                if (i < n && (s[i] == '"' || s[i] == '\'')) {
                    val quote = s[i]
                    val close = s.indexOf(quote, i + 1)
                    if (close < 0) return null
                    value = s.substring(i + 1, close)
                    i = close + 1
                } else {
                    val valueStart = i
                    while (i < n && !s[i].isWhitespace() && s[i] != '>') i++
                    value = s.substring(valueStart, i)
                }
            }
            if (attrName.isNotEmpty()) attributes.putIfAbsent(attrName, FeedText.decodeEntities(value))
        }
        return null
    }
}
