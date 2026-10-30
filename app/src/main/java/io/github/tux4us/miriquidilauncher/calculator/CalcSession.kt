// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.calculator

/** Eingabezeile mit Cursor. Reine Textbearbeitung ohne Android-Bezug. */
data class CalcEditor(val text: String = "", val cursor: Int = 0) {
    private val position: Int get() = cursor.coerceIn(0, text.length)

    /** Fügt [token] an der Cursorposition ein; bei Überschreiten der Höchstlänge bleibt die Eingabe unverändert. */
    fun insert(token: String): CalcEditor {
        if (token.isEmpty() || text.length + token.length > CalcEngine.MAX_LENGTH) return this
        val p = position
        return CalcEditor(text.substring(0, p) + token + text.substring(p), p + token.length)
    }

    /**
     * Löscht das Zeichen vor dem Cursor. Steht dort die öffnende Klammer eines Funktionsaufrufs, wird der Name
     * mit entfernt (aus `sin(` wird nichts, nicht `sin`).
     */
    fun backspace(): CalcEditor {
        val p = position
        if (p == 0) return this
        var start = p - 1
        if (text[start] == '(') {
            if (start > 0 && text[start - 1] == '√') {
                start--
            } else {
                while (start > 0 && text[start - 1].isLetter()) start--
            }
        }
        return CalcEditor(text.removeRange(start, p), start)
    }

    fun moveLeft(): CalcEditor = copy(cursor = (position - 1).coerceAtLeast(0))

    fun moveRight(): CalcEditor = copy(cursor = (position + 1).coerceAtMost(text.length))

    fun atEnd(): Boolean = position == text.length

    /** Zeichen unmittelbar vor dem Cursor oder `null` am Anfang. */
    fun charBefore(): Char? = if (position == 0) null else text[position - 1]

    /** Text links vom Cursor. */
    fun beforeCursor(): String = text.substring(0, position)

    companion object {
        fun of(text: String): CalcEditor = CalcEditor(text, text.length)
    }
}

/** Eintrag im Verlauf: der eingegebene Ausdruck und sein Ergebnis. */
data class HistoryEntry(val expression: String, val value: Double)

/**
 * Zustand des Rechners: Eingabe, Winkelmodus, Speicher, letztes Ergebnis (`ans`) und Verlauf. Jede Aktion liefert einen
 * neuen Zustand; es gibt keine Nebenwirkungen. Die Oberfläche hält nur Darstellungszustand (2nd, Funktionstasten).
 */
data class CalcSession(
    val editor: CalcEditor = CalcEditor(),
    val angle: AngleMode = AngleMode.Deg,
    val memory: Double? = null,
    val ans: Double = 0.0,
    val history: List<HistoryEntry> = emptyList(),
    /** Ergebnis nach Gleichheitszeichen oder Fehler; `null`, solange gerade eingegeben wird. */
    val outcome: CalcOutcome? = null,
    /** `true` direkt nach einem erfolgreichen Gleichheitszeichen. */
    val evaluated: Boolean = false,
) {
    /**
     * Fügt eine Eingabe ein. Nach einem Ergebnis beginnt eine Ziffer, Klammer, Funktion oder Konstante eine neue
     * Rechnung, ein Operator setzt mit `Ans` fort. Doppelte Operatoren ersetzen einander; ein zweites Dezimalzeichen
     * in derselben Zahl und eine unpassende schließende Klammer werden ignoriert.
     */
    fun type(token: String, decimalSeparator: Char = ','): CalcSession {
        val key = if (token == "." || token == ",") decimalSeparator.toString() else token
        if (evaluated && (key == "E" || key == ";")) return this
        val operatorLike = isOperatorToken(key)
        val base = when {
            evaluated && operatorLike -> copy(editor = CalcEditor.of("Ans"), outcome = null, evaluated = false)
            evaluated -> copy(editor = CalcEditor(), outcome = null, evaluated = false)
            else -> copy(outcome = null)
        }
        val e = base.editor
        if (e.text.isEmpty() && operatorLike && key != "−" && key != "+") return base
        if (key == decimalSeparator.toString() && currentNumberHasSeparator(e)) return base
        if (key == ")" && openParentheses(e) <= 0) return base
        if (key == ";" && openParentheses(e) <= 0) return base
        if (key == "E" && e.charBefore()?.let { it.isDigit() || it == '.' || it == ',' } != true) return base
        if (key.length == 1 && key[0] in "+−×÷" && e.atEnd()) {
            val last = e.charBefore()
            if (last != null && last in "+−×÷") {
                // "×−" und "÷−" bleiben als Vorzeichen erhalten, alles andere ersetzt den vorigen Operator.
                if (key == "−" && last in "×÷") return base.copy(editor = e.insert(key))
                return base.copy(editor = e.backspace().insert(key))
            }
        }
        return base.copy(editor = e.insert(key))
    }

    fun backspace(): CalcSession = copy(editor = editor.backspace(), outcome = null, evaluated = false)

    fun moveLeft(): CalcSession = copy(editor = editor.moveLeft())

    fun moveRight(): CalcSession = copy(editor = editor.moveRight())

    /** Löscht die Eingabe; Verlauf, Speicher und `ans` bleiben. */
    fun clear(): CalcSession = copy(editor = CalcEditor(), outcome = null, evaluated = false)

    fun toggleAngle(): CalcSession = copy(angle = angle.next())

    /** Berechnet die Eingabe. Fehlende schließende Klammern werden ergänzt. */
    fun evaluate(): CalcSession {
        val text = editor.text
        if (text.isBlank() || evaluated) return this
        return when (val result = CalcEngine.evaluate(text, angle, ans, autoClose = true)) {
            is CalcOutcome.Value -> {
                val entry = HistoryEntry(text, result.value)
                val newHistory = if (history.firstOrNull() == entry) history else (listOf(entry) + history).take(MAX_HISTORY)
                copy(ans = result.value, outcome = result, evaluated = true, history = newHistory)
            }
            is CalcOutcome.Error -> copy(outcome = result, evaluated = false)
        }
    }

    /** Wert der aktuellen Eingabe oder des angezeigten Ergebnisses; `null`, wenn es keinen gibt. */
    fun currentValue(): Double? {
        if (evaluated) return (outcome as? CalcOutcome.Value)?.value
        if (editor.text.isBlank()) return null
        return (CalcEngine.evaluate(editor.text, angle, ans, autoClose = true) as? CalcOutcome.Value)?.value
    }

    /** Vorschau des Ergebnisses während der Eingabe; `null` bei leerer Eingabe, einer einzelnen Zahl oder Fehler. */
    fun preview(): Double? {
        if (evaluated || editor.text.isBlank() || isPlainNumber(editor.text)) return null
        return (CalcEngine.evaluate(editor.text, angle, ans, autoClose = true) as? CalcOutcome.Value)?.value
    }

    /** Addiert (oder mit [subtract] subtrahiert) den aktuellen Wert zum Speicher. */
    fun memoryAdd(subtract: Boolean = false): CalcSession {
        val value = currentValue() ?: return this
        val signed = if (subtract) -value else value
        val sum = CalcEngine.roundSignificant((memory ?: 0.0) + signed)
        return copy(memory = sum + 0.0)
    }

    fun memoryClear(): CalcSession = copy(memory = null)

    /** Fügt den Speicherwert in die Eingabe ein (negative Werte in Klammern). */
    fun memoryRecall(decimalSeparator: Char = ','): CalcSession {
        val value = memory ?: return this
        val text = CalcFormat.format(value, decimalSeparator, 15)
        return type(if (value < 0.0) "($text)" else text, decimalSeparator)
    }

    /** Fügt den Wert eines Verlaufseintrags ein. */
    fun insertValue(value: Double, decimalSeparator: Char = ','): CalcSession {
        val text = CalcFormat.format(value, decimalSeparator, 15)
        return type(if (value < 0.0) "($text)" else text, decimalSeparator)
    }

    /** Ersetzt die Eingabe durch [expression] (aus dem Verlauf oder der Suche). */
    fun loadExpression(expression: String): CalcSession {
        val text = expression.trim().take(CalcEngine.MAX_LENGTH)
        return copy(editor = CalcEditor.of(text), outcome = null, evaluated = false)
    }

    fun clearHistory(): CalcSession = copy(history = emptyList())

    private fun currentNumberHasSeparator(e: CalcEditor): Boolean {
        val before = e.beforeCursor()
        var i = before.length - 1
        while (i >= 0 && (before[i].isDigit() || before[i] == '.' || before[i] == ',')) {
            if (before[i] == '.' || before[i] == ',') return true
            i--
        }
        return false
    }

    private fun openParentheses(e: CalcEditor): Int {
        val before = e.beforeCursor()
        return before.count { it == '(' } - before.count { it == ')' }
    }

    companion object {
        const val MAX_HISTORY = 50

        /** `true`, wenn [text] nur aus Ziffern und Dezimalzeichen besteht (keine Rechnung). */
        fun isPlainNumber(text: String): Boolean = text.isNotEmpty() && text.all { it.isDigit() || it == '.' || it == ',' }

        /** Eingaben, die einen vorhandenen Wert fortsetzen statt eine neue Rechnung zu beginnen. */
        fun isOperatorToken(token: String): Boolean {
            if (token.isEmpty()) return false
            val c = token[0]
            return c in "+−×÷^!%" || token == "mod"
        }
    }
}
