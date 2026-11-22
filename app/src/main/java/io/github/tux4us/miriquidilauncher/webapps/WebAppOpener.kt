// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.webapps

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.net.Uri
import android.os.UserManager
import androidx.browser.customtabs.CustomTabsIntent

/**
 * Startet eine Webapp: eine gespeicherte Adresse im Standardbrowser oder in einem Custom Tab (der Launcher lädt die
 * Seite nicht selbst), eine Verknüpfung aus dem Browser über das System.
 */
object WebAppOpener {
    /**
     * @return `false`, wenn die Adresse nicht mit HTTPS beginnt (sie wird nie geöffnet), keine App sie öffnen kann
     *   oder eine Verknüpfung nicht gestartet werden konnte
     */
    fun open(context: Context, app: WebApp): Boolean {
        app.pin?.let { return startPin(context, it) }
        // Die Adresse wurde beim Speichern geprüft; hier gilt dieselbe Regel noch einmal, falls der Speicher von außen verändert wurde.
        val url = (WebAppLogic.checkUrl(app.url) as? UrlCheck.Ok)?.url ?: return false
        val uri = Uri.parse(url)
        if (app.customTab) {
            try {
                CustomTabsIntent.Builder().build().launchUrl(context, uri)
                return true
            } catch (e: ActivityNotFoundException) {
                // Kein Browser mit Custom-Tab-Unterstützung: weiter mit dem normalen Öffnen.
            }
        }
        return try {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }

    /**
     * Startet die Verknüpfung über `LauncherApps.startShortcut` (der Intent ist für Launcher nicht lesbar). Das gelingt nur,
     * solange der Launcher der Standard-Launcher ist und die App die Verknüpfung noch anbietet.
     */
    private fun startPin(context: Context, pin: PinnedShortcut): Boolean {
        val launcherApps = context.getSystemService(LauncherApps::class.java) ?: return false
        val user = context.getSystemService(UserManager::class.java)?.getUserForSerialNumber(pin.userSerial) ?: return false
        return try {
            launcherApps.startShortcut(pin.packageName, pin.shortcutId, null, null, user)
            true
        } catch (e: ActivityNotFoundException) {
            false
        } catch (e: SecurityException) {
            false
        } catch (e: IllegalStateException) {
            false
        }
    }

    /**
     * Gibt die Verknüpfung [removed] beim System frei. `pinShortcuts` setzt die Liste der angehefteten Verknüpfungen eines
     * Pakets neu; [remaining] sind daher alle übrigen Verknüpfungen, die der Launcher noch hält. Fehler sind unkritisch
     * (der Eintrag in der Scope ist ohnehin schon entfernt).
     */
    fun unpin(context: Context, removed: PinnedShortcut, remaining: List<PinnedShortcut>) {
        val launcherApps = context.getSystemService(LauncherApps::class.java) ?: return
        val user = context.getSystemService(UserManager::class.java)?.getUserForSerialNumber(removed.userSerial) ?: return
        val ids = remaining
            .filter { it.packageName == removed.packageName && it.userSerial == removed.userSerial && it.shortcutId != removed.shortcutId }
            .map { it.shortcutId }
        try {
            launcherApps.pinShortcuts(removed.packageName, ids, user)
        } catch (e: SecurityException) {
            // Nicht (mehr) Standard-Launcher.
        } catch (e: IllegalStateException) {
            // Benutzerprofil gesperrt.
        }
    }
}
