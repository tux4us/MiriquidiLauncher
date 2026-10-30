// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.backup

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.github.tux4us.miriquidilauncher.data.AppGroupsStore
import io.github.tux4us.miriquidilauncher.data.FavoriteContactsStore
import io.github.tux4us.miriquidilauncher.data.FavoritesStore
import io.github.tux4us.miriquidilauncher.markets.MarketStore
import io.github.tux4us.miriquidilauncher.notes.NotesRepository
import io.github.tux4us.miriquidilauncher.notes.NotesStore
import io.github.tux4us.miriquidilauncher.podcasts.PodcastData
import io.github.tux4us.miriquidilauncher.podcasts.PodcastStore
import io.github.tux4us.miriquidilauncher.weather.WeatherStore
import kotlinx.coroutines.flow.first

/** Ergebnis eines Imports: Anzahl der übernommenen Einträge je Bereich. */
data class ImportSummary(
    val apps: Int,
    val contacts: Int?,
    val places: Int,
    val notes: Int? = null,
    /** Anzahl der Podcast-Abos bzw. der Werte im Kurse-Bestand; `null` = nicht im Backup. */
    val podcasts: Int? = null,
    val markets: Int? = null,
)

/**
 * Export und Import der Launcher-Einstellungen.
 *
 * Export: Ab Android 10 über MediaStore nach `Download/MiriquidiLauncher/` (keine Berechtigung nötig), bis Android 9
 * direkt in den öffentlichen Download-Ordner (benötigt `WRITE_EXTERNAL_STORAGE`).
 * Import: über die Dateiauswahl des Systems. Dateien aus einer früheren Installation sind für MediaStore nicht
 * mehr lesbar, die Dateiauswahl funktioniert dagegen immer.
 */
class BackupManager(context: Context) {
    private val appContext = context.applicationContext
    private val appStore = FavoritesStore(appContext)
    private val contactStore = FavoriteContactsStore(appContext)
    private val weatherStore = WeatherStore(appContext)
    private val groupsStore = AppGroupsStore(appContext)
    private val notes = NotesRepository(appContext, NotesStore(appContext))
    private val podcastStore = PodcastStore(appContext)
    private val marketStore = MarketStore(appContext)

    /** Aktueller Bestand als Backup-JSON. */
    private suspend fun snapshot(includeContacts: Boolean, includeFileOnlyData: Boolean): String {
        val data = BackupData(
            appFavorites = appStore.current(),
            contactFavorites = if (includeContacts) contactStore.current() else null,
            weatherFavorites = weatherStore.currentFavorites(),
            selectedPlace = weatherStore.currentSelected(),
            shadowHeights = weatherStore.currentShadowHeights(),
            appGroups = groupsStore.current(),
            // Ohne Zugriff auf Dateien (oder für QR-Codes) bleiben die Notizen außen vor.
            notes = if (includeFileOnlyData) notes.exportForBackup() else null,
            // Podcast- und Kursdaten sind zu groß bzw. zu persönlich für QR-Codes und stehen nur in der Datei.
            podcasts = if (includeFileOnlyData) podcastStore.load() else null,
            markets = if (includeFileOnlyData) marketsForBackup() else null,
        )
        return BackupCodec.encode(data, Instant.now().toString())
    }

    private suspend fun marketsForBackup() = BackupMarkets(
        positions = marketStore.positions.first(),
        choice = marketStore.chartChoice.first(),
        range = marketStore.range.first(),
        chartCurrency = marketStore.chartCurrency.first(),
        fxFrom = marketStore.fxPair.first().first,
        fxTo = marketStore.fxPair.first().second,
        yahooEnabled = marketStore.yahooEnabled.first(),
        stooqEnabled = marketStore.stooqEnabled.first(),
        baseUrl = marketStore.customBaseUrl.first(),
        holdingsVisible = marketStore.holdingsVisible.first(),
    )

    suspend fun export(includeContacts: Boolean): String = withContext(Dispatchers.IO) {
        val text = snapshot(includeContacts, includeFileOnlyData = true)
        val name = "miriquidilauncher-backup-" + LocalDateTime.now().format(FILE_TIME) + ".json"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) writeViaMediaStore(name, text) else writeLegacy(name, text)
        "$FOLDER_NAME/$name"
    }

    /**
     * Texte der QR-Codes für den aktuellen Bestand ([BackupQr]). [password] `null` = unverschlüsselt.
     * @throws BackupQr.TooLargeException wenn mehr als [BackupQr.MAX_PARTS] Codes nötig wären.
     */
    suspend fun createQrParts(includeContacts: Boolean, password: CharArray?): List<String> =
        withContext(Dispatchers.IO) { BackupQr.createParts(snapshot(includeContacts, includeFileOnlyData = false), password) }

    /** Liest die Datei hinter [uri] und ersetzt die vorhandenen Favoriten durch ihren Inhalt. */
    suspend fun import(uri: Uri): ImportSummary = withContext(Dispatchers.IO) {
        val text = appContext.contentResolver.openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
                if (out.size() > BackupCodec.MAX_FILE_BYTES) throw BackupCodec.BackupException("Datei zu groß")
            }
            out.toString(Charsets.UTF_8.name())
        } ?: throw IOException("Datei nicht lesbar")
        restore(text, BackupCodec.MAX_FILE_BYTES)
    }

    /** Spielt ein Backup aus dem JSON-Text [text] ein (z. B. aus QR-Codes). */
    suspend fun importText(text: String): ImportSummary = withContext(Dispatchers.IO) { restore(text, BackupCodec.MAX_BYTES) }

    private suspend fun restore(text: String, maxBytes: Int): ImportSummary {
        val data = BackupCodec.decode(text, maxBytes)
        appStore.replaceAll(data.appFavorites)
        data.contactFavorites?.let { contactStore.replaceAll(it) }
        weatherStore.replaceAll(data.weatherFavorites, data.selectedPlace)
        data.shadowHeights?.let { weatherStore.saveShadowHeights(it) }
        data.appGroups?.let { groupsStore.replaceAll(it) }
        // Notizen werden nur ergänzt, nie überschrieben; ohne Zugriff auf Dateien bleiben sie unberücksichtigt.
        val restoredNotes = data.notes?.let { list ->
            try {
                notes.importFromBackup(list)
            } catch (e: IOException) {
                null
            }
        }
        data.podcasts?.let { restorePodcasts(it) }
        data.markets?.let { restoreMarkets(it) }
        return ImportSummary(
            data.appFavorites.size, data.contactFavorites?.size, data.weatherFavorites.size, restoredNotes,
            podcasts = data.podcasts?.subscriptions?.size,
            markets = data.markets?.positions?.size,
        )
    }

    /**
     * Ersetzt Abos, Favoriten, Positionen, Gehört-Markierungen, Verzeichnis und Geschwindigkeit. Heruntergeladene Folgen
     * und deren Zuordnung bleiben bestehen; der Folgen-Zwischenspeicher bleibt nur für noch abonnierte Feeds.
     */
    private suspend fun restorePodcasts(restored: PodcastData) {
        val current = podcastStore.load()
        val feeds = restored.subscriptions.map { it.feedUrl }.toSet()
        podcastStore.save(
            current.copy(
                subscriptions = restored.subscriptions,
                favorites = restored.favorites,
                positionsMs = restored.positionsMs,
                played = restored.played,
                provider = restored.provider,
                speed = restored.speed,
                episodes = current.episodes.filterKeys { it in feeds },
            ),
            withEpisodes = true,
        )
    }

    private suspend fun restoreMarkets(m: BackupMarkets) {
        marketStore.setPositions(m.positions)
        marketStore.setChartChoice(m.choice)
        marketStore.setRange(m.range)
        marketStore.setChartCurrency(m.chartCurrency)
        marketStore.setFxPair(m.fxFrom, m.fxTo)
        marketStore.setYahooEnabled(m.yahooEnabled)
        marketStore.setStooqEnabled(m.stooqEnabled)
        marketStore.setCustomBaseUrl(m.baseUrl)
        marketStore.setHoldingsVisible(m.holdingsVisible)
    }

    private fun writeViaMediaStore(name: String, text: String) {
        val resolver = appContext.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/json")
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER_NAME")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IOException("MediaStore")
        try {
            resolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) } ?: throw IOException("Stream")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    @Suppress("DEPRECATION")
    private fun writeLegacy(name: String, text: String) {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FOLDER_NAME)
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Ordner nicht anlegbar")
        File(dir, name).writeText(text, Charsets.UTF_8)
    }

    companion object {
        const val FOLDER_NAME = "MiriquidiLauncher"
        private val FILE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

        /** Start-Ordner der Dateiauswahl beim Import: Download/MiriquidiLauncher. */
        val IMPORT_INITIAL_URI: Uri = Uri.parse(
            "content://com.android.externalstorage.documents/document/primary%3ADownload%2F$FOLDER_NAME",
        )
    }
}
