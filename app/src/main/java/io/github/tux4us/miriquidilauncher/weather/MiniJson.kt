// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.weather

/**
 * Minimaler JSON-Parser ohne Abhängigkeiten. Liefert `Map<String, Any?>`, `List<Any?>`, `String`,
 * `Double`, `Boolean` oder `null`. Alle Zahlen werden als `Double` gelesen. Dadurch bleibt die
 * Auswertung der Wetterdaten ohne Android-Klassen auf der JVM testbar.
 */
object MiniJson {
    class JsonException(message: String) : Exception(message)

    private const val MAX_DEPTH = 64

    fun parse(text: String): Any? {
        val parser = Parser(text)
        val value = parser.readValue()
        parser.skipWhitespace()
        if (!parser.atEnd()) throw JsonException("Unerwartete Zeichen nach dem JSON-Wert an Position ${parser.position}")
        return value
    }

    private class Parser(private val s: String) {
        var position = 0
            private set
        private var depth = 0

        fun atEnd() = position >= s.length

        fun skipWhitespace() {
            while (position < s.length && s[position].isWhitespace()) position++
        }

        fun readValue(): Any? {
            skipWhitespace()
            if (atEnd()) throw JsonException("Unerwartetes Ende")
            return when (val c = s[position]) {
                '{' -> nested { readObject() }
                '[' -> nested { readArray() }
                '"' -> readString()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c.isDigit()) readNumber() else throw JsonException("Unerwartetes Zeichen '$c' an Position $position")
            }
        }

        /** Begrenzt die Verschachtelung, damit manipulierte Dateien keinen Stapelüberlauf auslösen. */
        private inline fun <T> nested(read: () -> T): T {
            if (++depth > MAX_DEPTH) throw JsonException("Zu tief verschachtelt")
            try {
                return read()
            } finally {
                depth--
            }
        }

        private fun literal(word: String, value: Any?): Any? {
            if (!s.startsWith(word, position)) throw JsonException("Ungültiges Literal an Position $position")
            position += word.length
            return value
        }

        private fun readNumber(): Double {
            val start = position
            while (position < s.length && (s[position].isDigit() || s[position] in "+-.eE")) position++
            return s.substring(start, position).toDoubleOrNull()
                ?: throw JsonException("Ungültige Zahl an Position $start")
        }

        private fun readString(): String {
            position++ // öffnendes Anführungszeichen
            val out = StringBuilder()
            while (true) {
                if (atEnd()) throw JsonException("Zeichenkette nicht beendet")
                val c = s[position++]
                when (c) {
                    '"' -> return out.toString()
                    '\\' -> {
                        if (atEnd()) throw JsonException("Ungültige Escape-Folge")
                        when (val e = s[position++]) {
                            '"', '\\', '/' -> out.append(e)
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                if (position + 4 > s.length) throw JsonException("Ungültige \\u-Folge")
                                val code = s.substring(position, position + 4).toIntOrNull(16)
                                    ?: throw JsonException("Ungültige \\u-Folge")
                                out.append(code.toChar())
                                position += 4
                            }
                            else -> throw JsonException("Unbekannte Escape-Folge \\$e")
                        }
                    }
                    else -> out.append(c)
                }
            }
        }

        private fun readArray(): List<Any?> {
            position++ // [
            val list = ArrayList<Any?>()
            skipWhitespace()
            if (!atEnd() && s[position] == ']') {
                position++
                return list
            }
            while (true) {
                list.add(readValue())
                skipWhitespace()
                if (atEnd()) throw JsonException("Array nicht beendet")
                when (s[position++]) {
                    ',' -> Unit
                    ']' -> return list
                    else -> throw JsonException("Erwartet ',' oder ']' an Position ${position - 1}")
                }
            }
        }

        private fun readObject(): Map<String, Any?> {
            position++ // {
            val map = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (!atEnd() && s[position] == '}') {
                position++
                return map
            }
            while (true) {
                skipWhitespace()
                if (atEnd() || s[position] != '"') throw JsonException("Erwartet Schlüssel an Position $position")
                val key = readString()
                skipWhitespace()
                if (atEnd() || s[position++] != ':') throw JsonException("Erwartet ':' nach Schlüssel '$key'")
                map[key] = readValue()
                skipWhitespace()
                if (atEnd()) throw JsonException("Objekt nicht beendet")
                when (s[position++]) {
                    ',' -> Unit
                    '}' -> return map
                    else -> throw JsonException("Erwartet ',' oder '}' an Position ${position - 1}")
                }
            }
        }
    }
}

@Suppress("UNCHECKED_CAST")
internal fun Any?.asObject(): Map<String, Any?>? = this as? Map<String, Any?>

@Suppress("UNCHECKED_CAST")
internal fun Any?.asArray(): List<Any?>? = this as? List<Any?>

internal fun Any?.asDouble(): Double? = this as? Double

internal fun Any?.asString(): String? = this as? String

internal fun List<Any?>?.doubleAt(index: Int): Double? = this?.getOrNull(index) as? Double

internal fun List<Any?>?.stringAt(index: Int): String? = this?.getOrNull(index) as? String
