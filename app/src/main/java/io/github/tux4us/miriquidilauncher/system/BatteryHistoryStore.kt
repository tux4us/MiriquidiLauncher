// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.system

import java.io.File

/** Speichert die Akku-Aufzeichnung als Textdatei im app-eigenen Ordner. Fehler führen zu leerem Verlauf, nie zum Absturz. */
class BatteryHistoryStore(private val file: File) {
    @Synchronized
    fun load(): List<BatterySample> = runCatching {
        if (file.exists()) BatteryHistoryCodec.decode(file.readText()) else emptyList()
    }.getOrDefault(emptyList())

    @Synchronized
    fun save(samples: List<BatterySample>) {
        runCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(BatteryHistoryCodec.encode(samples))
            if (!tmp.renameTo(file)) {
                file.writeText(BatteryHistoryCodec.encode(samples))
                tmp.delete()
            }
        }
    }

    @Synchronized
    fun clear() {
        runCatching { file.delete() }
    }
}
