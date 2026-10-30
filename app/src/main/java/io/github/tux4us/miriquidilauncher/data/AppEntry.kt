// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.data

import android.content.ComponentName
import android.os.UserHandle

/**
 * Eine startbare App-Aktivität eines Benutzerprofils (Hauptprofil oder Arbeitsprofil).
 *
 * @property key stabiler Schlüssel: Komponente + Seriennummer des Benutzerprofils. Überlebt
 *   Neustarts und wird für Favoriten und Listen-Keys verwendet.
 * @property searchKey für den Vergleich normalisierte Bezeichnung (siehe TextNormalizer).
 */
data class AppEntry(
    val key: String,
    val label: String,
    val searchKey: String,
    val component: ComponentName,
    val user: UserHandle,
) {
    val packageName: String get() = component.packageName
}
