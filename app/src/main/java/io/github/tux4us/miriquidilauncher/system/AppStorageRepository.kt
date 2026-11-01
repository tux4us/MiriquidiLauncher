// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.system

import android.app.AppOpsManager
import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.os.storage.StorageManager

/**
 * Größen von Daten und Cache fremder Apps. Dafür ist der Nutzungszugriff nötig (`PACKAGE_USAGE_STATS`, Spezialzugriff
 * in den Einstellungen).
 */
class AppStorageRepository(private val context: Context) {

    fun hasUsageAccess(): Boolean {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /**
     * Daten- und Cachegrößen aller Apps mit Launcher-Aktivität (das ist die Menge, die der Launcher ohne
     * `QUERY_ALL_PACKAGES` sieht). Apps, deren Werte nicht lesbar sind, entfallen.
     */
    fun loadAppStorage(): List<AppStorage> {
        val manager = context.getSystemService(Context.STORAGE_STATS_SERVICE) as? StorageStatsManager ?: return emptyList()
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val infos = pm.queryIntentActivities(intent, 0)
        val result = ArrayList<AppStorage>()
        val seen = HashSet<String>()
        for (info in infos) {
            val pkg = info.activityInfo?.packageName ?: continue
            if (!seen.add(pkg)) continue
            val stats = runCatching {
                manager.queryStatsForPackage(StorageManager.UUID_DEFAULT, pkg, Process.myUserHandle())
            }.getOrNull() ?: continue
            val label = runCatching { info.loadLabel(pm).toString() }.getOrDefault(pkg)
            result += AppStorage(pkg, label, stats.appBytes, stats.dataBytes, stats.cacheBytes)
        }
        return result
    }
}
