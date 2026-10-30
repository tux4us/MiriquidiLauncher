// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.search


/** Art einer erkannten Eingabe. */
enum class ActionKind { Calculation, Conversion, Call, Sms, Email, OpenUrl, WebSearch }

/**
 * Aktion, die zur Eingabe im Suchfeld passt. [title] ist der Haupttext (z. B. "12 * 7 = 84"), [payload] der Wert, den
 * die Aktion verwendet (Rechenergebnis zum Kopieren, Telefonnummer, Adresse, Suchbegriff).
 */
data class DetectedAction(
    val kind: ActionKind,
    val title: String,
    val payload: String,
    val score: Int,
    /** Die eingegebene Rechnung bei [ActionKind.Calculation], sonst `null`. */
    val expression: String? = null,
)

/**
 * Erkennt, was der Nutzer ins Suchfeld tippt: Rechnung, Einheitenumrechnung, Telefonnummer, E-Mail-Adresse,
 * Web-Adresse. Zu jeder Eingabe ab zwei Zeichen kommt zusätzlich "Im Web suchen". Reine Funktionen ohne
 * Android-Bezug.
 */
object InputActions {
    const val SCORE_CALCULATION = 1100
    const val SCORE_DIRECT = 1050
    const val SCORE_SMS = 1040
    const val SCORE_WEB_SEARCH = 10

    private val EMAIL = Regex("^[^\\s@]+@[^\\s@]+\\.[A-Za-z]{2,}$")
    private val PHONE = Regex("^(?:\\+|00)?[0-9][0-9 ()/.\\-]*$")
    private val URL_SCHEME = Regex("^https?://\\S+\\.\\S+$", RegexOption.IGNORE_CASE)
    private val URL_HOST = Regex("^(?:www\\.)?[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?)*\\.([A-Za-z]{2,})(?::\\d{1,5})?(?:[/?#]\\S*)?$")
    private val FILE_EXTENSIONS = setOf(
        "png", "jpg", "jpeg", "gif", "webp", "pdf", "txt", "doc", "docx", "xls", "xlsx", "zip", "apk", "mp3", "mp4",
        "kt", "xml", "json", "csv", "html", "htm", "md", "log", "tmp", "bak", "java", "py", "sh", "so", "jar",
    )

    fun detect(raw: String, decimalSeparator: Char = ','): List<DetectedAction> {
        val text = raw.trim()
        if (text.length < 2) return emptyList()
        val result = ArrayList<DetectedAction>()

        UnitConverter.convert(text)?.let { c ->
            val from = "${Calculator.format(c.value, decimalSeparator)} ${c.fromLabel}"
            val to = "${Calculator.format(c.result, decimalSeparator)} ${c.toLabel}"
            result += DetectedAction(ActionKind.Conversion, "$from = $to", to, SCORE_CALCULATION)
        }
        Calculator.evaluate(text)?.let { value ->
            val formatted = Calculator.format(value, decimalSeparator)
            result += DetectedAction(
                kind = ActionKind.Calculation,
                title = "$text = $formatted",
                payload = formatted,
                score = SCORE_CALCULATION,
                expression = text,
            )
        }
        if (EMAIL.matches(text)) {
            result += DetectedAction(ActionKind.Email, text, text, SCORE_DIRECT)
        }
        phoneNumber(text)?.let { number ->
            result += DetectedAction(ActionKind.Call, number, number, SCORE_DIRECT)
            result += DetectedAction(ActionKind.Sms, number, number, SCORE_SMS)
        }
        url(text)?.let { result += DetectedAction(ActionKind.OpenUrl, text, it, SCORE_DIRECT) }

        result += DetectedAction(ActionKind.WebSearch, text, text, SCORE_WEB_SEARCH)
        return result
    }

    /** Bereinigte Telefonnummer (Ziffern und führendes +), wenn die Eingabe wie eine aussieht (5 bis 15 Ziffern). */
    fun phoneNumber(text: String): String? {
        if (!PHONE.matches(text)) return null
        val digits = text.count { it.isDigit() }
        if (digits !in 5..15) return null
        val plus = if (text.startsWith("+")) "+" else ""
        return plus + text.filter { it.isDigit() }
    }

    /** Adresse mit Schema (ohne Schema wird `https://` ergänzt), wenn die Eingabe eine Web-Adresse ist. */
    fun url(text: String): String? {
        if (text.any { it.isWhitespace() }) return null
        if (URL_SCHEME.matches(text)) return text
        val match = URL_HOST.matchEntire(text) ?: return null
        val tld = match.groupValues[1].lowercase()
        if (tld in FILE_EXTENSIONS) return null
        return "https://$text"
    }
}
