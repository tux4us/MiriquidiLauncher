// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.calculator

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cbrt
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.cosh
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.math.tanh

/** Einheit der Winkel für sin, cos, tan und ihre Umkehrfunktionen. */
enum class AngleMode(val key: String) {
    Deg("deg"),
    Rad("rad"),
    Grad("grad"),
    ;

    /** Reihenfolge beim Umschalten: Grad → Bogenmaß → Gon. */
    fun next(): AngleMode = entries[(ordinal + 1) % entries.size]

    companion object {
        fun fromKey(key: String?): AngleMode = entries.firstOrNull { it.key == key } ?: Deg
    }
}

/** Grund, warum ein Ausdruck kein Ergebnis hat. */
enum class CalcError {
    /** Der Ausdruck ist unvollständig oder ungültig geschrieben. */
    Syntax,

    /** Division durch null. */
    DivideByZero,

    /** Wert außerhalb des Definitionsbereichs (z. B. Wurzel aus -1, ln(0), tan(90°)). */
    Domain,

    /** Ergebnis zu groß für die Darstellung. */
    Overflow,
}

/** Ergebnis einer Auswertung. */
sealed interface CalcOutcome {
    data class Value(val value: Double) : CalcOutcome
    data class Error(val error: CalcError) : CalcOutcome
}

/**
 * Rechenkern für die Scope „Rechner“ und das Suchfeld. Reine Funktionen ohne Android-Bezug.
 *
 * Unterstützt `+ - * / ^ %`, die Zeichen `× · ÷ − –`, Klammern, Fakultät `!`, `mod`, die Konstanten `pi` (`π`), `e`
 * und `ans`, Zehnerpotenzen in der Schreibweise `1,5E3` sowie die Funktionen
 * `sin cos tan asin acos atan sinh cosh tanh asinh acosh atanh ln log lg lb exp sqrt (√) cbrt abs floor ceil round
 * sign fact ncr npr root`. Argumente werden mit `;` getrennt (das Komma ist das Dezimalzeichen). Das Malzeichen darf
 * bei Klammern, Funktionen und Konstanten entfallen (`2pi`, `2(3+4)`). Dezimalzeichen sind Punkt und Komma.
 *
 * Prozent: Allein oder vor `*` und `/` bedeutet `p%` den Wert `p/100`. Nach `+` und `-` bedeutet es den Anteil
 * des linken Werts: `100+10%` = 110, `100-10%` = 90.
 *
 * Zwischenergebnisse werden auf 15 gültige Stellen gerundet, damit `0,1+0,2` genau `0,3` ergibt.
 */
object CalcEngine {
    const val MAX_LENGTH = 200
    private const val MAX_DEPTH = 40
    private const val MAX_FACTORIAL = 170
    private const val MAX_COMBINATORICS = 100_000
    private const val SIGNIFICANT_DIGITS = 15

    /**
     * Wertet [input] aus. [ans] ist der Wert von `ans`. Mit [autoClose] werden fehlende schließende Klammern am
     * Ende ergänzt (für Vorschau und Gleichheitszeichen).
     */
    fun evaluate(
        input: String,
        angle: AngleMode = AngleMode.Deg,
        ans: Double = 0.0,
        autoClose: Boolean = false,
    ): CalcOutcome {
        var text = normalize(input)
        if (text.isEmpty() || text.length > MAX_LENGTH) return CalcOutcome.Error(CalcError.Syntax)
        if (autoClose) {
            val open = text.count { it == '(' } - text.count { it == ')' }
            if (open > 0) text += ")".repeat(open)
        }
        val parser = Parser(text, angle, ans)
        val value = parser.parse()
            ?: return CalcOutcome.Error(parser.failure ?: CalcError.Syntax)
        return when {
            value.isNaN() -> CalcOutcome.Error(CalcError.Domain)
            value.isInfinite() -> CalcOutcome.Error(CalcError.Overflow)
            else -> CalcOutcome.Value(roundSignificant(value) + 0.0)
        }
    }

    /** Vereinheitlicht Schreibweisen (Malzeichen, Minus, Wurzel, Hochzahlen) und entfernt Leerzeichen. */
    fun normalize(input: String): String = input.trim()
        .replace('×', '*').replace('·', '*').replace('÷', '/').replace('−', '-').replace('–', '-')
        .replace("²", "^2").replace("³", "^3")
        .replace("π", "pi")
        .replace(" x ", " * ").replace(" X ", " * ").replace(" : ", " / ")
        .replace(" ", "")

    /** Rundet auf 15 gültige Stellen; 0, NaN und unendliche Werte bleiben unverändert. */
    internal fun roundSignificant(value: Double): Double {
        if (value == 0.0 || !value.isFinite()) return value
        return BigDecimal(value).round(MathContext(SIGNIFICANT_DIGITS, RoundingMode.HALF_EVEN)).toDouble()
    }

    private class Parser(private val s: String, private val angle: AngleMode, private val ans: Double) {
        private var pos = 0
        private var depth = 0

        /** Gesetzt, wenn ein Wert außerhalb des Definitionsbereichs oder eine Division durch null auftrat. */
        var failure: CalcError? = null

        /** `true`, wenn das zuletzt gelesene Postfix-Element ein Prozentzeichen war und kein Operator folgte. */
        private var percentFlag = false
        private var termIsPercent = false

        fun parse(): Double? {
            val value = expression() ?: return null
            return if (pos == s.length) value else null
        }

        private fun fail(error: CalcError): Double? {
            if (failure == null) failure = error
            return null
        }

        private fun peek(): Char? = s.getOrNull(pos)

        private fun expression(): Double? {
            var left = term() ?: return null
            while (true) {
                val op = peek()
                if (op != '+' && op != '-') return left
                pos++
                val right = term() ?: return null
                // `a + p%` ist a + a·p/100; der Prozentwert wurde bereits durch 100 geteilt.
                val operand = if (termIsPercent) left * right else right
                left = round(if (op == '+') left + operand else left - operand)
            }
        }

        private fun term(): Double? {
            var left = unary() ?: return null
            var onlyPercent = percentFlag
            while (true) {
                when {
                    peek() == '*' -> {
                        pos++
                        left = round(left * (unary() ?: return null))
                        onlyPercent = false
                    }
                    peek() == '/' -> {
                        pos++
                        val right = unary() ?: return null
                        if (right == 0.0) return fail(CalcError.DivideByZero)
                        left = round(left / right)
                        onlyPercent = false
                    }
                    s.startsWith("mod", pos) -> {
                        pos += 3
                        val right = unary() ?: return null
                        if (right == 0.0) return fail(CalcError.DivideByZero)
                        left = round(left - right * floor(left / right))
                        onlyPercent = false
                    }
                    startsImplicitFactor() -> {
                        left = round(left * (unary() ?: return null))
                        onlyPercent = false
                    }
                    else -> {
                        termIsPercent = onlyPercent
                        return left
                    }
                }
            }
        }

        /** Malzeichen darf vor Klammer, Wurzel, Zahl, Konstante und Funktion entfallen. */
        private fun startsImplicitFactor(): Boolean {
            val c = peek() ?: return false
            return c == '(' || c == '√' || c.isDigit() || c == '.' || c.isLetter()
        }

        private fun unary(): Double? = when (peek()) {
            '-' -> {
                pos++
                val inner = unary()
                if (inner == null) null else -inner
            }
            '+' -> {
                pos++
                unary()
            }
            else -> power()
        }

        private fun power(): Double? {
            val base = postfix() ?: return null
            if (peek() == '^') {
                pos++
                val exponent = unary() ?: return null
                percentFlag = false
                if (base == 0.0 && exponent < 0.0) return fail(CalcError.DivideByZero)
                val result = base.pow(exponent)
                if (result.isNaN()) return fail(CalcError.Domain)
                if (result.isInfinite()) return fail(CalcError.Overflow)
                return round(result)
            }
            return base
        }

        private fun postfix(): Double? {
            var value = primary() ?: return null
            percentFlag = false
            while (true) {
                when (peek()) {
                    '%' -> {
                        pos++
                        value = round(value / 100.0)
                        percentFlag = true
                    }
                    '!' -> {
                        pos++
                        value = factorial(value) ?: return null
                        percentFlag = false
                    }
                    else -> return value
                }
            }
        }

        private fun primary(): Double? {
            if (++depth > MAX_DEPTH) return fail(CalcError.Syntax)
            try {
                val c = peek() ?: return null
                if (c == '(') {
                    pos++
                    val inner = expression() ?: return null
                    if (peek() != ')') return null
                    pos++
                    return inner
                }
                if (c == '√') {
                    pos++
                    val operand = (if (peek() == '(') primary() else postfixOperand()) ?: return null
                    return if (operand < 0.0) fail(CalcError.Domain) else round(sqrt(operand))
                }
                if (c.isLetter()) return identifier()
                return number()
            } finally {
                depth--
            }
        }

        /** Operand einer Wurzel ohne Klammer: Zahl, Konstante oder Funktionsaufruf. */
        private fun postfixOperand(): Double? {
            val c = peek() ?: return null
            return if (c.isLetter()) identifier() else number()
        }

        private fun identifier(): Double? {
            val start = pos
            while (pos < s.length && s[pos].isLetter()) pos++
            val name = s.substring(start, pos).lowercase()
            if (peek() == '(') {
                pos++
                val args = arguments() ?: return null
                return function(name, args)
            }
            return when (name) {
                "pi" -> PI
                "e" -> Math.E
                "ans" -> ans
                else -> null
            }
        }

        /** Liest `a;b;c)` bis einschließlich der schließenden Klammer. */
        private fun arguments(): List<Double>? {
            val list = ArrayList<Double>(2)
            if (peek() == ')') {
                pos++
                return list
            }
            while (true) {
                list += expression() ?: return null
                when (peek()) {
                    ';' -> pos++
                    ')' -> {
                        pos++
                        return list
                    }
                    else -> return null
                }
            }
        }

        private fun number(): Double? {
            val start = pos
            var seenSeparator = false
            while (pos < s.length) {
                val c = s[pos]
                if (c.isDigit()) {
                    pos++
                } else if ((c == '.' || c == ',') && !seenSeparator) {
                    seenSeparator = true
                    pos++
                } else {
                    break
                }
            }
            if (pos == start) return null
            val mantissa = s.substring(start, pos).replace(',', '.')
            if (mantissa == ".") return null
            var text = mantissa
            if (peek() == 'E') {
                var p = pos + 1
                if (s.getOrNull(p) == '+' || s.getOrNull(p) == '-') p++
                if (s.getOrNull(p)?.isDigit() == true) {
                    while (s.getOrNull(p)?.isDigit() == true) p++
                    text = mantissa + s.substring(pos, p)
                    pos = p
                }
            }
            return text.toDoubleOrNull()?.takeIf { it.isFinite() }
        }

        // region Funktionen ----------------------------------------------------------------------

        private fun function(name: String, a: List<Double>): Double? {
            return when (name) {
                "sin" -> one(a) { trig(0, it) }
                "cos" -> one(a) { trig(1, it) }
                "tan" -> one(a) { trig(2, it) }
                "asin" -> one(a) { if (abs(it) > 1.0) fail(CalcError.Domain) else fromRadians(asin(it)) }
                "acos" -> one(a) { if (abs(it) > 1.0) fail(CalcError.Domain) else fromRadians(acos(it)) }
                "atan" -> one(a) { fromRadians(atan(it)) }
                "sinh" -> one(a) { overflowCheck(sinh(it)) }
                "cosh" -> one(a) { overflowCheck(cosh(it)) }
                "tanh" -> one(a) { tanh(it) }
                "asinh" -> one(a) { if (it < 0.0) -ln(-it + sqrt(it * it + 1.0)) else ln(it + sqrt(it * it + 1.0)) }
                "acosh" -> one(a) { if (it < 1.0) fail(CalcError.Domain) else ln(it + sqrt(it * it - 1.0)) }
                "atanh" -> one(a) { if (abs(it) >= 1.0) fail(CalcError.Domain) else 0.5 * ln((1.0 + it) / (1.0 - it)) }
                "ln" -> one(a) { if (it <= 0.0) fail(CalcError.Domain) else ln(it) }
                "log", "lg" -> when (a.size) {
                    1 -> if (a[0] <= 0.0) fail(CalcError.Domain) else cleanPower10(kotlin.math.log10(a[0]))
                    2 -> if (a[0] <= 0.0 || a[1] <= 0.0 || a[1] == 1.0) fail(CalcError.Domain) else ln(a[0]) / ln(a[1])
                    else -> null
                }
                "lb", "log2" -> one(a) { if (it <= 0.0) fail(CalcError.Domain) else kotlin.math.log2(it) }
                "exp" -> one(a) { overflowCheck(exp(it)) }
                "sqrt" -> one(a) { if (it < 0.0) fail(CalcError.Domain) else sqrt(it) }
                "cbrt" -> one(a) { cbrt(it) }
                "abs" -> one(a) { abs(it) }
                "floor" -> one(a) { floor(it) }
                "ceil" -> one(a) { ceil(it) }
                "sign" -> one(a) { if (it > 0.0) 1.0 else if (it < 0.0) -1.0 else 0.0 }
                "round" -> when (a.size) {
                    1 -> roundHalfAway(a[0], 0)
                    2 -> if (a[1] != floor(a[1]) || abs(a[1]) > 15.0) fail(CalcError.Domain) else roundHalfAway(a[0], a[1].toInt())
                    else -> null
                }
                "fact" -> one(a) { factorial(it) }
                "ncr" -> if (a.size == 2) combinations(a[0], a[1]) else null
                "npr" -> if (a.size == 2) permutations(a[0], a[1]) else null
                "root" -> if (a.size == 2) root(a[0], a[1]) else null
                else -> null
            }?.let { round(it) }
        }

        private inline fun one(a: List<Double>, block: (Double) -> Double?): Double? =
            if (a.size == 1) block(a[0]) else null

        private fun overflowCheck(value: Double): Double? =
            if (value.isInfinite()) fail(CalcError.Overflow) else value

        /** log10 liefert für Zehnerpotenzen exakte Werte; hier nur für saubere Ausgabe, z. B. log(1000) = 3. */
        private fun cleanPower10(value: Double): Double {
            val nearest = Math.rint(value)
            return if (abs(value - nearest) < 1e-13) nearest else value
        }

        private fun round(value: Double): Double = roundSignificant(value)

        /** Rundet kaufmännisch (0,5 weg von null) auf [digits] Nachkommastellen. */
        private fun roundHalfAway(value: Double, digits: Int): Double {
            val scaled = BigDecimal(value.toString()).setScale(digits, RoundingMode.HALF_UP)
            return scaled.toDouble()
        }

        private fun toRadians(x: Double): Double = when (angle) {
            AngleMode.Deg -> x * PI / 180.0
            AngleMode.Rad -> x
            AngleMode.Grad -> x * PI / 200.0
        }

        private fun fromRadians(x: Double): Double = when (angle) {
            AngleMode.Deg -> x * 180.0 / PI
            AngleMode.Rad -> x
            AngleMode.Grad -> x * 200.0 / PI
        }

        /** [kind]: 0 = sin, 1 = cos, 2 = tan. Vielfache des rechten Winkels werden exakt behandelt. */
        private fun trig(kind: Int, x: Double): Double? {
            val quarter = when (angle) {
                AngleMode.Deg -> 90.0
                AngleMode.Grad -> 100.0
                AngleMode.Rad -> PI / 2.0
            }
            val quarters = Math.rint(x / quarter)
            if (abs(x - quarters * quarter) <= 1e-12 * max(1.0, abs(x)) && abs(quarters) < 1e15) {
                val index = (((quarters.toLong() % 4L) + 4L) % 4L).toInt()
                return when (kind) {
                    0 -> doubleArrayOf(0.0, 1.0, 0.0, -1.0)[index]
                    1 -> doubleArrayOf(1.0, 0.0, -1.0, 0.0)[index]
                    else -> if (index % 2 == 0) 0.0 else fail(CalcError.Domain)
                }
            }
            val r = toRadians(x)
            return when (kind) {
                0 -> sin(r)
                1 -> cos(r)
                else -> tan(r)
            }
        }

        private fun factorial(x: Double): Double? {
            if (x < 0.0 || x != floor(x) || x > MAX_FACTORIAL) return fail(CalcError.Domain)
            var result = 1.0
            for (i in 2..x.toInt()) result *= i
            return round(result)
        }

        private fun integers(n: Double, r: Double): Boolean =
            n == floor(n) && r == floor(r) && n >= 0.0 && r >= 0.0 && r <= n && n <= MAX_COMBINATORICS

        private fun combinations(n: Double, r: Double): Double? {
            if (!integers(n, r)) return fail(CalcError.Domain)
            val k = minOf(r, n - r).toInt()
            var result = 1.0
            for (i in 1..k) result = result * (n - k + i) / i
            return if (result.isInfinite()) fail(CalcError.Overflow) else round(result)
        }

        private fun permutations(n: Double, r: Double): Double? {
            if (!integers(n, r)) return fail(CalcError.Domain)
            var result = 1.0
            for (i in 0 until r.toInt()) result *= (n - i)
            return if (result.isInfinite()) fail(CalcError.Overflow) else round(result)
        }

        /** n-te Wurzel von [x]; für negative [x] nur bei ungeradem ganzzahligem [n]. */
        private fun root(x: Double, n: Double): Double? {
            if (n == 0.0) return fail(CalcError.Domain)
            if (x >= 0.0) return x.pow(1.0 / n)
            val odd = n == floor(n) && abs(n) < 1e15 && (n.toLong() % 2L != 0L)
            return if (odd) -((-x).pow(1.0 / n)) else fail(CalcError.Domain)
        }

        // endregion
    }
}

/** Darstellung von Zahlen für die Anzeige. */
object CalcFormat {
    /**
     * Zahl als Text mit höchstens [digits] gültigen Stellen, ohne überflüssige Nullen und ohne Exponent für übliche
     * Größen. [decimalSeparator] ist das Dezimalzeichen der Anzeige. Sehr große und sehr kleine Werte erscheinen
     * in der Schreibweise `1,5E20`.
     */
    fun format(value: Double, decimalSeparator: Char = ',', digits: Int = 12): String {
        if (value == 0.0 || value.isNaN()) return if (value.isNaN()) "NaN" else "0"
        if (value.isInfinite()) return if (value > 0) "∞" else "-∞"
        val rounded = BigDecimal(value).round(MathContext(digits, RoundingMode.HALF_EVEN))
        val useExponent = abs(value) >= 1e15 || abs(value) < 1e-6
        var text = if (useExponent) {
            val stripped = rounded.stripTrailingZeros()
            val unscaled = stripped.unscaledValue().abs().toString()
            val exponent = unscaled.length - 1 - stripped.scale()
            val mantissa = if (unscaled.length > 1) unscaled[0] + "." + unscaled.substring(1) else unscaled
            (if (value < 0) "-" else "") + mantissa + "E" + exponent
        } else {
            rounded.stripTrailingZeros().toPlainString()
        }
        if (text.contains('.') && !text.contains('E')) text = text.trimEnd('0').trimEnd('.')
        return text.replace('.', decimalSeparator)
    }
}
