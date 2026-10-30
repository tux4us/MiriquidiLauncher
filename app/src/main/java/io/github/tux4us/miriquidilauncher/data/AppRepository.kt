// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.data

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.tux4us.miriquidilauncher.search.TextNormalizer
import kotlin.math.roundToInt

/**
 * Liefert die Liste aller startbaren Apps über die [LauncherApps]-API (inklusive Arbeitsprofil)
 * und hält sie bei Installation, Update und Entfernung aktuell.
 *
 * Stabilitätsregeln:
 * - Das Laden läuft auf [Dispatchers.Default], nie auf dem Main-Thread.
 * - Mehrere Paket-Ereignisse in kurzer Folge (z. B. Massen-Updates) werden zu einem Ladevorgang
 *   zusammengefasst.
 * - Schlägt ein Ladevorgang fehl, bleibt die zuletzt gültige Liste erhalten.
 * - Der Callback wird in [close] abgemeldet.
 */
class AppRepository(
    context: Context,
    scope: CoroutineScope,
) : AutoCloseable {

    private val appContext = context.applicationContext
    private val launcherApps = appContext.getSystemService(LauncherApps::class.java)
    private val userManager = appContext.getSystemService(UserManager::class.java)
    private val packageManager = appContext.packageManager
    private val iconSizePx = (ICON_SIZE_DP * appContext.resources.displayMetrics.density).roundToInt()
    private val iconCache = LruCache<String, ImageBitmap>(ICON_CACHE_ENTRIES)

    private val reloadRequests = Channel<Unit>(Channel.CONFLATED)
    private val _apps = MutableStateFlow<List<AppEntry>>(emptyList())
    val apps: StateFlow<List<AppEntry>> = _apps.asStateFlow()

    private val callback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String, user: UserHandle) = requestReload()
        override fun onPackageAdded(packageName: String, user: UserHandle) = requestReload()
        override fun onPackageChanged(packageName: String, user: UserHandle) = requestReload()
        override fun onPackagesAvailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) =
            requestReload()
        override fun onPackagesUnavailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) =
            requestReload()
    }

    private val loader: Job = scope.launch(Dispatchers.Default) {
        for (request in reloadRequests) {
            delay(RELOAD_DEBOUNCE_MS)
            reloadRequests.tryReceive() // Anfragen, die während der Wartezeit eintrafen, zusammenfassen
            try {
                val loaded = loadApps()
                iconCache.evictAll()
                _apps.value = loaded
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (ignored: Exception) {
                // Zuletzt gültige Liste beibehalten.
            }
        }
    }

    init {
        launcherApps.registerCallback(callback)
        requestReload()
    }

    fun requestReload() {
        reloadRequests.trySend(Unit)
    }

    private fun loadApps(): List<AppEntry> {
        val result = ArrayList<AppEntry>()
        for (user in userManager.userProfiles) {
            val serial = userManager.getSerialNumberForUser(user)
            for (info in launcherApps.getActivityList(null, user)) {
                val component = info.componentName
                val label = info.label?.toString().orEmpty().ifBlank { component.packageName }
                result += AppEntry(
                    key = "${component.flattenToShortString()}#$serial",
                    label = label,
                    searchKey = TextNormalizer.normalize(label),
                    component = component,
                    user = user,
                )
            }
        }
        return result.sortedWith(compareBy<AppEntry> { it.searchKey }.thenBy { it.key })
    }

    suspend fun loadIcon(entry: AppEntry): ImageBitmap? {
        iconCache.get(entry.key)?.let { return it }
        return withContext(Dispatchers.Default) {
            try {
                val info = launcherApps
                    .getActivityList(entry.component.packageName, entry.user)
                    .firstOrNull { it.componentName == entry.component }
                    ?: return@withContext null
                val bitmap = info.getIcon(0).toBitmap(iconSizePx, iconSizePx).asImageBitmap()
                iconCache.put(entry.key, bitmap)
                bitmap
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (ignored: Exception) {
                null
            }
        }
    }

    fun launch(entry: AppEntry): Boolean = try {
        launcherApps.startMainActivity(entry.component, entry.user, null, null)
        true
    } catch (ignored: SecurityException) {
        false
    } catch (ignored: ActivityNotFoundException) {
        false
    }

    fun openAppInfo(entry: AppEntry): Boolean = try {
        launcherApps.startAppDetailsActivity(entry.component, entry.user, null, null)
        true
    } catch (ignored: SecurityException) {
        false
    } catch (ignored: ActivityNotFoundException) {
        false
    }

    /** Deinstallation ist nur für Nutzer-Apps des Hauptprofils vorgesehen. */
    fun canUninstall(entry: AppEntry): Boolean {
        if (entry.user != Process.myUserHandle()) return false
        return try {
            val flags = packageManager.getApplicationInfo(entry.packageName, 0).flags
            (flags and ApplicationInfo.FLAG_SYSTEM) == 0
        } catch (ignored: PackageManager.NameNotFoundException) {
            false
        }
    }

    fun uninstall(entry: AppEntry): Boolean = try {
        val intent = Intent(Intent.ACTION_DELETE, Uri.fromParts("package", entry.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        appContext.startActivity(intent)
        true
    } catch (ignored: ActivityNotFoundException) {
        false
    } catch (ignored: SecurityException) {
        false
    }

    override fun close() {
        launcherApps.unregisterCallback(callback)
        loader.cancel()
        reloadRequests.close()
    }

    private companion object {
        const val ICON_SIZE_DP = 48
        const val ICON_CACHE_ENTRIES = 256
        const val RELOAD_DEBOUNCE_MS = 150L
    }
}
