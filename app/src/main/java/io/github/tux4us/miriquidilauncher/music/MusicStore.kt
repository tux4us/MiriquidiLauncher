// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.music

import android.content.Context
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Lokale Datei der Musikdaten (`files/music/data.json`): Sender, Favoriten, Wiedergabelisten, Equalizer und Einstellungen.
 * Sie liegt im privaten App-Speicher; die App ist vom Android-Backup ausgenommen. Die Musikdateien selbst werden nie kopiert.
 */
class MusicStore(context: Context) {
    private val directory = File(context.applicationContext.filesDir, "music")
    private val dataFile = File(directory, "data.json")

    suspend fun load(): MusicData = withContext(Dispatchers.IO) { loadBlocking() }

    /** Für den Wiedergabedienst, der beim Start nur die kleine Datei lesen muss. */
    fun loadBlocking(): MusicData = try {
        if (dataFile.isFile) MusicCodec.decode(dataFile.readText()) ?: MusicData() else MusicData()
    } catch (ignored: IOException) {
        MusicData()
    }

    suspend fun save(data: MusicData) {
        withContext(Dispatchers.IO) {
            try {
                directory.mkdirs()
                val temporary = File(directory, dataFile.name + ".tmp")
                temporary.writeText(MusicCodec.encode(data))
                if (!temporary.renameTo(dataFile)) temporary.delete()
            } catch (ignored: IOException) {
                // Der Stand bleibt im Speicher erhalten; beim nächsten Speichern wird erneut versucht.
            }
        }
    }
}
