// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.system

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.Process
import android.os.StatFs
import android.net.TrafficStats
import java.io.File

/** Arbeitsspeicher: [info] aus `/proc/meminfo`, sonst aus dem ActivityManager. [lowMemory] meldet das System. */
data class MemorySnapshot(val info: MemInfo, val lowMemory: Boolean)

data class StorageVolumeInfo(val isRemovable: Boolean, val totalBytes: Long, val freeBytes: Long) {
    val usedBytes: Long get() = (totalBytes - freeBytes).coerceAtLeast(0)
    val usedFraction: Double get() = if (totalBytes > 0) usedBytes.toDouble() / totalBytes else 0.0
}

/**
 * Akkudaten aus dem Broadcast `ACTION_BATTERY_CHANGED`. [currentMilliAmp] ist der momentane Strom (positiv = Laden,
 * negativ = Entladen, je nach Gerät auch umgekehrt), [cycles] gibt es ab Android 14.
 */
data class BatteryInfo(
    val level: Int,
    val voltageMv: Int?,
    val tempTenthsC: Int?,
    val health: Int,
    val status: Int,
    val plugged: Int,
    val technology: String?,
    val currentMilliAmp: Int?,
    val cycles: Int?,
) {
    val charging: Boolean get() = plugged != 0
}

data class CpuInfo(
    val cores: Int,
    val hardware: String,
    val socModel: String?,
    val abi: String?,
    val temperatureC: Double?,
)

/** [status] siehe `PowerManager.THERMAL_STATUS_*` (0 = keine Einschränkung), `null` vor Android 10. */
data class ThermalInfo(val status: Int?, val headroom: Float?)

data class SystemSnapshot(
    val memory: MemorySnapshot?,
    val storage: List<StorageVolumeInfo>,
    val battery: BatteryInfo?,
    val thermal: ThermalInfo,
    val cpu: CpuInfo,
    val netRxBytes: Long?,
    val netTxBytes: Long?,
)

/** Liest Systemwerte ohne besondere Berechtigung. Alle Zugriffe fangen Fehler ab; nicht lesbare Werte sind `null`. */
class SystemRepository(private val context: Context) {

    fun read(): SystemSnapshot = SystemSnapshot(
        memory = readMemory(),
        storage = readStorage(),
        battery = readBattery(),
        thermal = readThermal(),
        cpu = readCpu(),
        netRxBytes = TrafficStats.getUidRxBytes(Process.myUid()).takeIf { it != TrafficStats.UNSUPPORTED.toLong() },
        netTxBytes = TrafficStats.getUidTxBytes(Process.myUid()).takeIf { it != TrafficStats.UNSUPPORTED.toLong() },
    )

    private fun readMemory(): MemorySnapshot? {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(mi)
        val parsed = runCatching { MemInfoParser.parse(File("/proc/meminfo").readText()) }.getOrNull()
        val info = parsed ?: if (mi.totalMem > 0) {
            MemInfo(
                totalKb = mi.totalMem / 1024,
                availableKb = mi.availMem / 1024,
                freeKb = mi.availMem / 1024,
                cachedKb = 0,
                buffersKb = 0,
                swapTotalKb = 0,
                swapFreeKb = 0,
            )
        } else {
            return null
        }
        return MemorySnapshot(info, mi.lowMemory)
    }

    private fun readStorage(): List<StorageVolumeInfo> {
        val result = ArrayList<StorageVolumeInfo>()
        runCatching {
            val stat = StatFs(Environment.getDataDirectory().path)
            result += StorageVolumeInfo(false, stat.totalBytes, stat.availableBytes)
        }
        // Weitere Datenträger (SD-Karte, USB): app-eigene Ordner auf Wechselmedien; Index 0 ist der interne Speicher.
        val dirs = runCatching { context.getExternalFilesDirs(null) }.getOrNull().orEmpty()
        for (dir in dirs.drop(1)) {
            if (dir == null) continue
            runCatching {
                val removable = Environment.isExternalStorageRemovable(dir)
                val stat = StatFs(dir.path)
                result += StorageVolumeInfo(removable, stat.totalBytes, stat.availableBytes)
            }
        }
        return result
    }

    private fun readBattery(): BatteryInfo? {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val manager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        return parseBattery(intent, manager)
    }

    private fun readThermal(): ThermalInfo {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val status = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { pm?.currentThermalStatus }.getOrNull()
        } else {
            null
        }
        val headroom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { pm?.getThermalHeadroom(10) }.getOrNull()?.takeIf { !it.isNaN() }
        } else {
            null
        }
        return ThermalInfo(status, headroom)
    }

    private fun readCpu(): CpuInfo {
        val cores = Runtime.getRuntime().availableProcessors()

        return CpuInfo(
            cores = cores,
            hardware = Build.HARDWARE,
            socModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL.takeIf { it.isNotBlank() && it != Build.UNKNOWN } else null,
            abi = Build.SUPPORTED_ABIS.firstOrNull(),
            temperatureC = readCpuTemperature(),
        )
    }

    /** Sucht eine lesbare Thermalzone von CPU oder SoC. Auf vielen Geräten ist `/sys/class/thermal` gesperrt. */
    private fun readCpuTemperature(): Double? = runCatching {
        val zones = File("/sys/class/thermal").listFiles { f -> f.name.startsWith("thermal_zone") }.orEmpty()
        for (zone in zones.sortedBy { it.name }) {
            val type = runCatching { File(zone, "type").readText().trim().lowercase() }.getOrNull() ?: continue
            if (!(type.contains("cpu") || type.contains("soc") || type.contains("tsens") || type.contains("big") || type.contains("little"))) continue
            val raw = runCatching { File(zone, "temp").readText().trim().toDouble() }.getOrNull() ?: continue
            val celsius = if (raw > 1000) raw / 1000.0 else raw
            if (celsius in 0.0..150.0) return@runCatching celsius
        }
        null
    }.getOrNull()

    companion object {
        /** Wertet einen `ACTION_BATTERY_CHANGED`-Broadcast aus; [manager] liefert zusätzlich den momentanen Strom. */
        fun parseBattery(intent: Intent, manager: BatteryManager?): BatteryInfo? {
            val rawLevel = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (rawLevel < 0 || scale <= 0) return null
            val current = runCatching { manager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) }.getOrNull()
                ?.takeIf { it != Int.MIN_VALUE && it != 0 }
                // Je nach Hersteller in Mikro- oder Milliampere; Werte ab 20 000 gelten als Mikroampere.
                ?.let { if (kotlin.math.abs(it) >= 20_000) it / 1000 else it }
            val cycles = if (Build.VERSION.SDK_INT >= 34) {
                intent.getIntExtra(BatteryManager.EXTRA_CYCLE_COUNT, -1).takeIf { it >= 0 }
            } else {
                null
            }
            return BatteryInfo(
                level = (rawLevel * 100 / scale).coerceIn(0, 100),
                voltageMv = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0).takeIf { it > 0 },
                tempTenthsC = if (intent.hasExtra(BatteryManager.EXTRA_TEMPERATURE)) intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) else null,
                health = intent.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN),
                status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN),
                plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0),
                technology = intent.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY)?.takeIf { it.isNotBlank() },
                currentMilliAmp = current,
                cycles = cycles,
            )
        }
    }
}
