// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.MediaStore
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.cameraDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "camera",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/** Eine installierte Kamera-App. [activity] ist die Aktivität, die `STILL_IMAGE_CAMERA` verarbeitet. */
data class CameraApp(val packageName: String, val label: String, val activity: String)

/** Gespeicherte Standard-Kamera-App des Nutzers; `null`, solange bei jedem Start gewählt wird. */
class CameraStore(context: Context) {
    private val store = context.applicationContext.cameraDataStore

    val preferred: Flow<String?> = store.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map { it[PACKAGE] }

    suspend fun setPreferred(packageName: String?) {
        store.edit { prefs ->
            if (packageName == null) prefs.remove(PACKAGE) else prefs[PACKAGE] = packageName
        }
    }

    private companion object {
        val PACKAGE = stringPreferencesKey("package")
    }
}

/** Findet installierte Kamera-Apps und startet sie. */
class CameraHelper(private val context: Context) {

    /** Alle Apps, die den Standard-Auftrag „Foto aufnehmen“ (`STILL_IMAGE_CAMERA`) anbieten, je Paket eine, nach Name sortiert. */
    fun cameraApps(): List<CameraApp> {
        val pm = context.packageManager
        val intent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
        @Suppress("DEPRECATION")
        val found = pm.queryIntentActivities(intent, 0)
        return found
            .mapNotNull { info ->
                val activity = info.activityInfo ?: return@mapNotNull null
                if (!activity.exported) return@mapNotNull null
                CameraApp(activity.packageName, info.loadLabel(pm).toString(), activity.name)
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    fun icon(app: CameraApp) = runCatching { context.packageManager.getApplicationIcon(app.packageName) }.getOrNull()

    /** Startet [app]; `false`, wenn die App nicht gestartet werden konnte. */
    fun launch(app: CameraApp): Boolean {
        val intent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
            .setComponent(ComponentName(app.packageName, app.activity))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (ignored: Exception) {
            // ActivityNotFoundException, SecurityException
            false
        }
    }

    companion object {
        /** Wählt aus [apps] die gespeicherte App; `null`, wenn keine gespeichert oder nicht mehr installiert ist. */
        fun pickPreferred(apps: List<CameraApp>, preferred: String?): CameraApp? =
            if (preferred == null) null else apps.firstOrNull { it.packageName == preferred }
    }
}
