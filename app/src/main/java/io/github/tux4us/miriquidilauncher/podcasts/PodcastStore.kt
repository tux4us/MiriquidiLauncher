// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.podcasts

import android.content.Context
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Lokale Datei der Podcast-Daten (`files/podcasts/data.json`) und der heruntergeladenen Folgen
 * (`files/podcasts/audio/`). Beides liegt im privaten App-Speicher; die App ist vom Android-Backup ausgenommen.
 */
class PodcastStore(context: Context) {
    private val directory = File(context.applicationContext.filesDir, "podcasts")
    private val dataFile = File(directory, "data.json")
    private val episodesFile = File(directory, "episodes.json")

    /** Ordner der heruntergeladenen Folgen. */
    val audioDirectory = File(directory, "audio")

    suspend fun load(): PodcastData = withContext(Dispatchers.IO) {
        try {
            if (dataFile.isFile) {
                val episodes = if (episodesFile.isFile) episodesFile.readText() else null
                PodcastCodec.decode(dataFile.readText(), episodes) ?: PodcastData()
            } else {
                PodcastData()
            }
        } catch (ignored: IOException) {
            PodcastData()
        }
    }

    /** Speichert den Stand; der Folgen-Zwischenspeicher nur mit [withEpisodes] (er ist groß und ändert sich selten). */
    suspend fun save(data: PodcastData, withEpisodes: Boolean) {
        withContext(Dispatchers.IO) {
            try {
                directory.mkdirs()
                write(dataFile, PodcastCodec.encode(data, withEpisodes = false))
                if (withEpisodes) write(episodesFile, PodcastCodec.encodeEpisodes(data.episodes))
            } catch (ignored: IOException) {
                // Der Stand bleibt im Speicher erhalten; beim nächsten Speichern wird erneut versucht.
            }
        }
    }

    private fun write(file: File, text: String) {
        val temporary = File(directory, file.name + ".tmp")
        temporary.writeText(text)
        if (!temporary.renameTo(file)) temporary.delete()
    }

    fun audioFile(fileName: String): File = File(audioDirectory, fileName)
}
