// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.recents

/**
 * Reine Logik der App-Übersicht (Fächeransicht der zuletzt verwendeten Apps). Ohne Android-Abhängigkeiten, damit
 * sie sich mit JVM-Tests prüfen lässt.
 *
 * Wichtig: Android erlaubt einem normalen Launcher nicht, die laufenden Aufgaben (Tasks) zu lesen. Die Liste
 * entsteht deshalb aus den Nutzungsereignissen des Systems (`UsageStatsManager`) und bedeutet „zuletzt verwendet“,
 * nicht „sicher noch im Arbeitsspeicher“.
 */
object RecentsLogic {

    /** Höchstzahl der Karten in der Übersicht. */
    const val MAX_ITEMS = 12

    /** Zeitfenster, in dem nach Nutzungsereignissen gesucht wird. */
    const val WINDOW_MILLIS = 3L * 24 * 60 * 60 * 1000

    /** Höchstzahl gespeicherter Entfernungsmarken. */
    const val MAX_DISMISSED = 60

    /** Ein Nutzungsereignis einer App: Vordergrund-/Hintergrundwechsel zum Zeitpunkt [timeMillis]. */
    data class UsageSample(val packageName: String, val timeMillis: Long)

    /** Eine App der Übersicht mit dem Zeitpunkt ihrer letzten Nutzung. */
    data class RecentApp(val packageName: String, val lastUsedMillis: Long)

    /**
     * Ordnet die Ereignisse zur Liste der zuletzt verwendeten Apps, jüngste zuerst.
     *
     * - Je Paket zählt der späteste Ereigniszeitpunkt.
     * - Das eigene Paket und Pakete ohne Launcher-Aktivität ([launchable]) entfallen.
     * - Eine in der Übersicht entfernte App ([dismissed]: Paket → Zeitpunkt der Entfernung) bleibt verborgen, bis
     *   sie danach wieder benutzt wurde.
     */
    fun recentApps(
        samples: List<UsageSample>,
        ownPackage: String,
        launchable: Set<String>,
        dismissed: Map<String, Long>,
        limit: Int = MAX_ITEMS,
    ): List<RecentApp> {
        val latest = HashMap<String, Long>()
        for (sample in samples) {
            if (sample.packageName == ownPackage || sample.packageName !in launchable) continue
            val known = latest[sample.packageName]
            if (known == null || sample.timeMillis > known) latest[sample.packageName] = sample.timeMillis
        }
        return latest.entries
            .filter { (pkg, time) -> time > (dismissed[pkg] ?: Long.MIN_VALUE) }
            .map { RecentApp(it.key, it.value) }
            .sortedWith(compareByDescending<RecentApp> { it.lastUsedMillis }.thenBy { it.packageName })
            .take(limit.coerceAtLeast(0))
    }

    /** Markiert [packages] als entfernt zum Zeitpunkt [now]; ältere Marken werden gekürzt. */
    fun withDismissed(current: Map<String, Long>, packages: Collection<String>, now: Long): Map<String, Long> {
        val merged = LinkedHashMap(current)
        for (pkg in packages) merged[pkg] = now
        return prune(merged, now)
    }

    /** Entfernt Marken, die älter sind als das Suchfenster (dort gäbe es kein Ereignis mehr), und kürzt die Menge. */
    fun prune(marks: Map<String, Long>, now: Long): Map<String, Long> =
        marks.entries
            .filter { now - it.value <= WINDOW_MILLIS }
            .sortedByDescending { it.value }
            .take(MAX_DISMISSED)
            .associate { it.key to it.value }
}

/** Speicherformat der Entfernungsmarken: je Zeile `Paket<TAB>Zeitpunkt`. Beschädigte Zeilen werden übersprungen. */
object RecentsCodec {

    fun encode(marks: Map<String, Long>): String =
        marks.entries.joinToString("\n") { "${it.key}\t${it.value}" }

    fun decode(text: String?): Map<String, Long> {
        if (text.isNullOrBlank()) return emptyMap()
        val result = LinkedHashMap<String, Long>()
        for (line in text.lineSequence()) {
            val parts = line.split('\t')
            if (parts.size != 2) continue
            val pkg = parts[0].trim()
            val time = parts[1].trim().toLongOrNull() ?: continue
            if (pkg.isNotEmpty() && time >= 0) result[pkg] = time
        }
        return result
    }
}
