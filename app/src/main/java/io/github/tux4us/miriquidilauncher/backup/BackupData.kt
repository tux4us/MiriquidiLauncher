// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.backup

import io.github.tux4us.miriquidilauncher.calculator.CalcPersisted
import io.github.tux4us.miriquidilauncher.camera.CameraSettings
import io.github.tux4us.miriquidilauncher.data.AppGroup
import io.github.tux4us.miriquidilauncher.data.FavoriteContact
import io.github.tux4us.miriquidilauncher.dogs.DogData
import io.github.tux4us.miriquidilauncher.feed.FeedSnapshot
import io.github.tux4us.miriquidilauncher.markets.ChartChoice
import io.github.tux4us.miriquidilauncher.markets.ChartCurrency
import io.github.tux4us.miriquidilauncher.markets.ChartRange
import io.github.tux4us.miriquidilauncher.markets.Position
import io.github.tux4us.miriquidilauncher.notes.NoteSort
import io.github.tux4us.miriquidilauncher.podcasts.PodcastData
import io.github.tux4us.miriquidilauncher.weather.GeoPlace
import io.github.tux4us.miriquidilauncher.weather.ShadowHeights

/**
 * Inhalt eines Backups. `null` bedeutet "nicht im Backup enthalten"; beim Einspielen bleibt der
 * zugehörige Bestand dann unverändert.
 */
data class BackupData(
    val appFavorites: List<String>,
    val contactFavorites: List<FavoriteContact>?,
    val weatherFavorites: List<GeoPlace>,
    val selectedPlace: GeoPlace?,
    val shadowHeights: ShadowHeights?,
    /** App-Gruppen; `null` = nicht im Backup (Backups älterer Versionen) und beim Einspielen unverändert. */
    val appGroups: List<AppGroup>? = null,
    /** Notizen (Text aller Dateien im Notizordner); `null` = nicht im Backup und beim Einspielen unverändert. */
    val notes: List<BackupNote>? = null,
    /**
     * Nutzerdaten der Scope „Podcasts“ (Abos, Favoriten, Positionen, Gehört-Markierungen, Verzeichnis, Geschwindigkeit);
     * `null` = nicht im Backup und beim Einspielen unverändert. Nur in der Backup-Datei, nicht in QR-Codes.
     */
    val podcasts: PodcastData? = null,
    /** Nutzerdaten der Scope „Kurse“ (Bestand, Auswahl, Quellen); `null` wie bei [podcasts]. Ohne API-Schlüssel. */
    val markets: BackupMarkets? = null,
    /** Sichtbarkeit und Reihenfolge der Scopes; `null` = nicht im Backup und beim Einspielen unverändert. Auch in QR-Codes. */
    val scopes: BackupScopes? = null,
    /** Adresse des eigenen Wetterservers (ohne Schlüssel); nur in der Backup-Datei. */
    val weatherServer: BackupWeatherServer? = null,
    /** Lokale Feed-Abos und Einstellungen der Scope „Feed“ (ohne Nextcloud-Zugang); nur in der Backup-Datei. */
    val feed: BackupFeed? = null,
    /** Favoriten, ausgeblendete Alben und Sortierung der Galerie; nur in der Backup-Datei. */
    val gallery: BackupGallery? = null,
    /** Sortierung und Anzeige der Scope „Dateien“; nur in der Backup-Datei. */
    /** Winkelmodus, Speicher, letztes Ergebnis und Verlauf des Rechners; nur in der Backup-Datei. */
    val calculator: CalcPersisted? = null,
    /** Einstellungen der Kamera-Scope und die gewählte Kamera-App des Kamera-Buttons; nur in der Backup-Datei. */
    val camera: BackupCamera? = null,
    /** Sortierung der Notizen; nur in der Backup-Datei. */
    val notesSettings: BackupNotesSettings? = null,
    /** Ob die Terminübersicht auf der Startseite angezeigt wird; nur in der Backup-Datei. */
    val agendaEnabled: Boolean? = null,
    /** Hundeprofile mit Fälligkeiten und Angaben der Notfallkarte (persönliche Daten); nur in der Backup-Datei, nie im QR-Code. */
    val dogs: DogData? = null,
)

/** Ausgeblendete Scopes und die Reihenfolge aller Scopes (Namen). Unbekannte Namen werden beim Einspielen verworfen. */
data class BackupScopes(val hidden: Set<String>, val order: List<String>)

/** Eigener Wetterserver ohne Schlüssel; [forecastBase] `null` = öffentliche Adressen. */
data class BackupWeatherServer(val forecastBase: String?, val airBase: String?)

/**
 * Scope „Feed“: [local] enthält die lokalen Abos mit Gelesen-Markierungen und die Artikel importierter Feeds ohne
 * eigene Adresse (alle anderen Artikel werden neu abgerufen); `null` = keine lokalen Abos. Zugangsdaten gehören nicht dazu.
 */
data class BackupFeed(val local: FeedSnapshot?, val unreadOnly: Boolean, val loadImages: Boolean)

data class BackupGallery(val favorites: Set<String>, val hiddenBuckets: Set<Long>, val ascending: Boolean, val showHidden: Boolean)


/**
 * Kamera-Einstellungen ohne gerätespezifische Angaben (Kamerakennung, zuletzt aufgenommene Datei) und die Kamera-App
 * des Buttons neben dem Suchfeld (Paketname) oder `null`.
 */
data class BackupCamera(val settings: CameraSettings, val buttonApp: String?)

data class BackupNotesSettings(val sort: NoteSort, val ascending: Boolean)

/**
 * Einstellungen und Bestand der Scope „Kurse“ im Backup. Der Stooq-Schlüssel gehört nicht dazu; [baseUrl] ist die
 * eigene Adresse der Bitcoin-Daten oder `null` für die öffentliche Instanz.
 */
data class BackupMarkets(
    val positions: List<Position>,
    val choice: ChartChoice,
    val range: ChartRange,
    val chartCurrency: ChartCurrency,
    val fxFrom: String,
    val fxTo: String,
    val yahooEnabled: Boolean,
    val stooqEnabled: Boolean,
    val baseUrl: String?,
    val holdingsVisible: Boolean,
)

/** Eine Notiz im Backup: relativer Pfad im Notizordner, Text, Favorit, Änderungszeit in Millisekunden. */
data class BackupNote(val path: String, val content: String, val favorite: Boolean, val modifiedMillis: Long)
