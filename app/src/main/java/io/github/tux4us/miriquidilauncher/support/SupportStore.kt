// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.support

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first

private val Context.supportDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "support",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * Merkt sich die Version, bei der zuletzt auf den Spendenhinweis geprüft wurde. Der Wert bleibt auf dem Gerät
 * und ist nicht Teil des Launcher-Backups; ein Import kann den Hinweis daher weder auslösen noch unterdrücken.
 */
class SupportStore(context: Context) {
    private val appContext = context.applicationContext
    private val store = appContext.supportDataStore

    /**
     * Prüft, ob der Hinweis für die laufende Version erscheinen soll, und hält die Version fest. Der Hinweis gilt damit
     * als gezeigt, sobald diese Funktion `true` liefert; er erscheint pro Version höchstens einmal.
     */
    suspend fun consumePrompt(): Boolean {
        val info = installInfo() ?: return false
        val saved = store.data.catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }.first()[LAST_VERSION]
        val show = SupportPrompt.shouldShow(saved, info.versionCode, info.updated)
        if (saved != info.versionCode) store.edit { it[LAST_VERSION] = info.versionCode }
        return show
    }

    private data class InstallInfo(val versionCode: Long, val updated: Boolean)

    private fun installInfo(): InstallInfo? = try {
        val pi = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pi.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            pi.versionCode.toLong()
        }
        InstallInfo(code, SupportPrompt.isUpdate(pi.firstInstallTime, pi.lastUpdateTime))
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    private companion object {
        val LAST_VERSION = longPreferencesKey("lastPromptVersion")
    }
}
