// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.support

/** Regeln für den einmaligen Spendenhinweis nach einem Update. */
object SupportPrompt {
    /** Seite für freiwillige Spenden. Sie wird nur auf Tippen des Nutzers im Browser geöffnet. */
    const val DONATE_URL = "https://buymeacoffee.com/tux4us"

    /**
     * Ob der Hinweis jetzt erscheint.
     *
     * - [lastSeen] ist die Version, bei der zuletzt geprüft wurde, oder `null`, wenn noch nichts gespeichert ist.
     * - Mit gespeicherter Version erscheint der Hinweis genau dann, wenn [current] größer ist (Update).
     * - Ohne gespeicherte Version erscheint er nur, wenn die App seit der Installation aktualisiert wurde
     *   ([updatedSinceInstall]); das betrifft Geräte, auf denen die erste Version mit diesem Hinweis ein Update ist.
     *   Nach einer Neuinstallation erscheint er nicht.
     */
    fun shouldShow(lastSeen: Long?, current: Long, updatedSinceInstall: Boolean): Boolean {
        if (current <= 0L) return false
        return if (lastSeen != null) current > lastSeen else updatedSinceInstall
    }

    /** Zeitspanne, ab der ein Abstand zwischen Installation und letzter Aktualisierung als Update gilt. */
    const val UPDATE_TOLERANCE_MS = 60_000L

    /** Ob [lastUpdateTime] deutlich nach [firstInstallTime] liegt, die App also aktualisiert wurde. */
    fun isUpdate(firstInstallTime: Long, lastUpdateTime: Long): Boolean =
        lastUpdateTime - firstInstallTime > UPDATE_TOLERANCE_MS
}
