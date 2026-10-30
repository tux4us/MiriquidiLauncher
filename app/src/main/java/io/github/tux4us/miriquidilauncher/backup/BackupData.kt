// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.backup

import io.github.tux4us.miriquidilauncher.data.AppGroup
import io.github.tux4us.miriquidilauncher.data.FavoriteContact
import io.github.tux4us.miriquidilauncher.markets.ChartChoice
import io.github.tux4us.miriquidilauncher.markets.ChartCurrency
import io.github.tux4us.miriquidilauncher.markets.ChartRange
import io.github.tux4us.miriquidilauncher.markets.Position
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
)

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
