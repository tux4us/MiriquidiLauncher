// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.webapps

import android.app.AlertDialog
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.graphics.Bitmap
import android.os.Bundle
import android.os.UserManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.lifecycleScope
import io.github.tux4us.miriquidilauncher.R
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Nimmt „Zum Startbildschirm hinzufügen“ von Browsern entgegen (`ACTION_CONFIRM_PIN_SHORTCUT`; das System sendet die
 * Anfrage nur an den Standard-Launcher). Nach Rückfrage wird die Verknüpfung in die Scope „Webapps“ übernommen.
 *
 * Der Intent einer Verknüpfung ist für Launcher nicht lesbar (`ShortcutInfo.getIntent()` liefert immer `null`); es sind
 * daher nur Name, Symbol, Paket und Kennung bekannt, und die Verknüpfung wird später mit `LauncherApps.startShortcut`
 * gestartet. Anfragen, die keine Verknüpfung sind, keinen Namen oder unsichere Angaben haben, werden ohne Anzeige beendet.
 */
class PinShortcutActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val launcherApps = getSystemService(LauncherApps::class.java)
        val pinRequest = try {
            launcherApps?.getPinItemRequest(intent)
        } catch (e: RuntimeException) {
            null
        }
        val info = pinRequest
            ?.takeIf { it.isValid && it.requestType == LauncherApps.PinItemRequest.REQUEST_TYPE_SHORTCUT }
            ?.shortcutInfo
        if (launcherApps == null || pinRequest == null || info == null) {
            finish()
            return
        }
        val name = WebAppLogic.cleanName(
            info.shortLabel?.toString().orEmpty().ifBlank { info.longLabel?.toString().orEmpty() },
        )
        val serial = getSystemService(UserManager::class.java)?.getSerialNumberForUser(info.userHandle) ?: -1L
        val pin = PinnedShortcut(info.`package`, info.id, serial)
        if (name == null || !WebAppLogic.isValidPin(pin)) {
            finish()
            return
        }
        val sourceApp = try {
            launcherApps.getApplicationInfo(pin.packageName, 0, info.userHandle)?.loadLabel(packageManager)?.toString()
        } catch (e: Exception) {
            null
        } ?: pin.packageName

        AlertDialog.Builder(this)
            .setTitle(R.string.webapp_pin_title)
            .setMessage(getString(R.string.webapp_pin_message, name, sourceApp))
            .setPositiveButton(R.string.webapp_pin_add) { _, _ -> add(launcherApps, pinRequest, info, name, pin) }
            .setNegativeButton(R.string.scope_cancel) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun add(launcherApps: LauncherApps, pinRequest: LauncherApps.PinItemRequest, info: ShortcutInfo, name: String, pin: PinnedShortcut) {
        val appContext = applicationContext
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                val store = WebAppStore(appContext)
                val icons = WebAppIcons(appContext)
                val current = store.current()
                val existing = WebAppLogic.findPin(current, pin)
                if (existing == null && !WebAppLogic.canAdd(current)) return@withContext Result.FULL
                if (!pinRequest.accept()) return@withContext Result.FAILED
                if (existing != null) return@withContext Result.ADDED
                val id = UUID.randomUUID().toString()
                val hasIcon = iconOf(launcherApps, info)?.let { icons.save(id, it) } ?: false
                store.update { WebAppLogic.add(it, WebApp(id, name, "", customTab = false, hasIcon = hasIcon, pin = pin)) }
                Result.ADDED
            }
            when (result) {
                Result.FULL -> Toast.makeText(appContext, R.string.webapp_pin_full, Toast.LENGTH_LONG).show()
                Result.FAILED -> Toast.makeText(appContext, R.string.webapp_pin_failed, Toast.LENGTH_LONG).show()
                Result.ADDED -> Toast.makeText(appContext, R.string.webapp_pin_added, Toast.LENGTH_SHORT).show()
            }
            finish()
        }
    }

    /** Symbol der Verknüpfung als [WebAppIcons.SIZE]-Bitmap oder `null`, wenn das System keines liefert. */
    private fun iconOf(launcherApps: LauncherApps, info: ShortcutInfo): Bitmap? =
        try {
            launcherApps.getShortcutIconDrawable(info, 0)?.toBitmap(WebAppIcons.SIZE, WebAppIcons.SIZE)
        } catch (e: Exception) {
            null
        }

    private enum class Result { ADDED, FULL, FAILED }
}
