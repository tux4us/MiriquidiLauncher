// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.calculator

/** Dauerhaft gespeicherter Teil des Rechners: Winkelmodus, Speicher, letztes Ergebnis und Verlauf. */
data class CalcPersisted(
    val angle: AngleMode = AngleMode.Deg,
    val memory: Double? = null,
    val ans: Double = 0.0,
    val history: List<HistoryEntry> = emptyList(),
)

/**
 * Textformat für den Datenspeicher: eine Kopfzeile `v1`, dann `angle=`, `mem=`, `ans=` und je Verlaufseintrag eine
 * Zeile `h=Ausdruck<TAB>Wert`. Fehlerhafte Zeilen werden beim Lesen übersprungen.
 */
object CalcCodec {
    private const val HEADER = "v1"

    fun encode(state: CalcPersisted): String = buildString {
        append(HEADER).append('\n')
        append("angle=").append(state.angle.key).append('\n')
        append("mem=").append(state.memory?.toString().orEmpty()).append('\n')
        append("ans=").append(state.ans.toString()).append('\n')
        for (entry in state.history.take(CalcSession.MAX_HISTORY)) {
            val expression = entry.expression.replace('\n', ' ').replace('\t', ' ')
            append("h=").append(expression).append('\t').append(entry.value.toString()).append('\n')
        }
    }

    fun decode(text: String?): CalcPersisted {
        if (text.isNullOrBlank()) return CalcPersisted()
        val lines = text.lines()
        if (lines.firstOrNull() != HEADER) return CalcPersisted()
        var angle = AngleMode.Deg
        var memory: Double? = null
        var ans = 0.0
        val history = ArrayList<HistoryEntry>()
        for (line in lines.drop(1)) {
            when {
                line.startsWith("angle=") -> angle = AngleMode.fromKey(line.removePrefix("angle="))
                line.startsWith("mem=") -> memory = line.removePrefix("mem=").toDoubleOrNull()?.takeIf { it.isFinite() }
                line.startsWith("ans=") -> ans = line.removePrefix("ans=").toDoubleOrNull()?.takeIf { it.isFinite() } ?: 0.0
                line.startsWith("h=") -> {
                    val body = line.removePrefix("h=")
                    val tab = body.lastIndexOf('\t')
                    if (tab > 0) {
                        val value = body.substring(tab + 1).toDoubleOrNull()?.takeIf { it.isFinite() }
                        val expression = body.substring(0, tab)
                        if (value != null && expression.isNotBlank() && expression.length <= CalcEngine.MAX_LENGTH) {
                            history += HistoryEntry(expression, value)
                        }
                    }
                }
            }
        }
        return CalcPersisted(angle, memory, ans, history.take(CalcSession.MAX_HISTORY))
    }
}

/** Dauerhaft zu speichernder Teil des Zustands. */
fun CalcSession.persisted(): CalcPersisted = CalcPersisted(angle, memory, ans, history)

/** Zustand mit leerer Eingabe aus dem gespeicherten Teil. */
fun CalcPersisted.toSession(): CalcSession = CalcSession(angle = angle, memory = memory, ans = ans, history = history)
