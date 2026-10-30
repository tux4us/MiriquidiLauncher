# Drittkomponenten und Hinweise

MiriquidiLauncher steht unter der GNU General Public License, Version 3 oder später (`LICENSE`, `LICENSES/GPL-3.0-or-later.txt`).

## Bibliotheken (Apache License 2.0, Volltext in `LICENSES/Apache-2.0.txt`)

| Komponente | Verwendung |
|---|---|
| AndroidX (Core, Activity, Lifecycle, DataStore, Compose UI/Foundation/Material3/Animation/Runtime, Startup, Tracing, Emoji2, ProfileInstaller) | Oberfläche, Lebenszyklus, Einstellungen |
| Kotlin Standard Library (JetBrains) | Sprache |
| kotlinx.coroutines (JetBrains) | Nebenläufigkeit |
| AndroidX CameraX (Core, Camera2, Lifecycle, View, Video, Extensions) | Kamera des QR-Scanners und Scope „Kamera“ (Foto, Video, Hersteller-Erweiterungen); die Erweiterungen nutzen die Herstellerbibliotheken des Geräts, keine Google-Dienste |
| AndroidX Media3 (ExoPlayer, Session; Apache-2.0) | Podcast-Wiedergabe mit Vordergrunddienst und Mediensitzung, keine Google-Dienste |
| ZXing core (Copyright ZXing authors) | Erkennung von QR-Codes, vollständig auf dem Gerät |

Nur für Tests: JUnit 4 (EPL-1.0), kotlinx-coroutines-test (Apache-2.0). Beides ist nicht im APK enthalten.

## Symbole

Die Symbole in `app/src/main/res/drawable/` mit der Kopfzeile `SPDX-License-Identifier: Apache-2.0` stammen aus den Google Material Icons (Copyright Google LLC, Apache License 2.0). Das App-Symbol und `ic_scopes` sind eigene Gestaltungen (GPL-3.0-or-later).

## Daten

| Quelle | Lizenz / Bedingungen | Hinweis in der App |
|---|---|---|
| Open-Meteo.com (Wetter, Geokodierung) | CC BY 4.0; kostenlose API nur für nicht kommerzielle Nutzung | Wetterseite, Fußzeile (verlinkt) |
| CAMS / Copernicus (Luftqualität, Pollen, über Open-Meteo) | Copernicus-Lizenz, Nennung des Anbieters | Wetterseite, Fußzeile (verlinkt) |
| OpenStreetMap / Nominatim (Ortsnamen) | ODbL, Nutzungsrichtlinie von Nominatim | Wetterseite, Fußzeile (verlinkt) |
| mempool.space (Bitcoin-Kurs, Gebühren, Netzwerk; wahlweise eigene Instanz) | Öffentlicher Dienst, Software unter AGPL-3.0; kein Schlüssel | Kursseite, Fußzeile (verlinkt) |
| Yahoo Finance (Indizes, Rohstoffe, Aktien; inoffizielle Schnittstelle) | Keine Lizenz für die Schnittstelle; die Nutzungsbedingungen von Yahoo schränken automatisierte Abrufe ein, keine Zusage zur Verfügbarkeit. Nutzung auf eigene Verantwortung, in den Datenquellen abschaltbar | Kursseite, Fußzeile (verlinkt), Hinweis „ohne Gewähr“ |
| CoinGecko (Ethereum, Stablecoins) | Öffentliche API ohne Schlüssel, Namensnennung verlangt, niedrige Ratenbegrenzung | Kursseite, Fußzeile (verlinkt) |
| Stooq (optional) | Nur mit persönlichem API-Schlüssel des Nutzers, Bedingungen des Dienstes; standardmäßig aus | Kursseite, Datenquellen, Fußzeile (verlinkt) |
| Apple iTunes Search API (Podcast-Suche) | Öffentliche Schnittstelle ohne Schlüssel; es gelten die Nutzungsbedingungen von Apple, keine Zusage zur Verfügbarkeit; abgefragt wird nur auf Wunsch des Nutzers auf der Podcast-Seite | Podcast-Seite, Verzeichnis-Auswahl |
| fyyd (Podcast-Suche) | Öffentliche Schnittstelle ohne Schlüssel; es gelten die Bedingungen des Dienstes; abgefragt wird nur auf Wunsch des Nutzers | Podcast-Seite, Verzeichnis-Auswahl |
| Frankfurter (Wechselkurse, Referenzkurse der EZB) | Frei nutzbar, Quelle EZB | Kursseite, Fußzeile (verlinkt) |
| Nextcloud Notes und Nextcloud News (eigene Instanz des Nutzers) | Schnittstellen der Nextcloud (Server unter AGPL-3.0); nur mit Zugang des Nutzers, nur HTTPS, kein Code übernommen | Notizen, FeedReader |
| Eigener Wetterserver (optional) | Vom Nutzer eingetragener Open-Meteo-kompatibler Server; Bedingungen des jeweiligen Betreibers | Wetterseite, Fußzeile |

Bei einer Monetarisierung (Werbung, Abo, Bezahlversion) ist für Open-Meteo ein kostenpflichtiges Abonnement nötig.

## Marken

Nextcloud ist eine Marke der Nextcloud GmbH und wird nur beschreibend genannt. 
