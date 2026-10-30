// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.search

import io.github.tux4us.miriquidilauncher.calculator.AngleMode
import io.github.tux4us.miriquidilauncher.calculator.CalcEngine
import io.github.tux4us.miriquidilauncher.calculator.CalcFormat
import io.github.tux4us.miriquidilauncher.calculator.CalcOutcome

/**
 * Taschenrechner für das Suchfeld. Die Auswertung übernimmt [CalcEngine] (Grundrechenarten, Potenzen, Prozent,
 * Wurzel, Winkelfunktionen, Logarithmen, Fakultät, Konstanten; Winkel in Grad).
 *
 * Ein Ausdruck gilt in der Suche nur dann als Rechnung, wenn er mindestens eine Ziffer sowie einen Operator, eine
 * Klammer oder eine Funktion enthält; eine einzelne Zahl oder ein Wort ist keine Rechnung. Reine Funktionen ohne
 * Android-Bezug.
 */
object Calculator {
    private const val MAX_LENGTH = 120
    private const val OPERATOR_CHARS = "+-*/^()%!√"

    /** Wert des Ausdrucks oder `null`, wenn es keine gültige Rechnung ist. */
    fun evaluate(input: String): Double? {
        val text = input.trim()
        if (text.isEmpty() || text.length > MAX_LENGTH) return null
        val normalized = CalcEngine.normalize(text)
        if (normalized.none { it.isDigit() }) return null
        if (normalized.none { it in OPERATOR_CHARS }) return null
        return (CalcEngine.evaluate(text, AngleMode.Deg) as? CalcOutcome.Value)?.value
    }

    /**
     * Zahl als Text: höchstens 10 gültige Stellen, ohne überflüssige Nullen, ohne Exponent für übliche Größen.
     * [decimalSeparator] ist das Dezimalzeichen der Anzeige.
     */
    fun format(value: Double, decimalSeparator: Char = ','): String = CalcFormat.format(value, decimalSeparator, 10)
}
