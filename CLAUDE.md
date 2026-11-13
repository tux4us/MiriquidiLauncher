# CLAUDE.md – MiriquidiLauncher

Android-Launcher, komplette Eigenentwicklung (kein Fork). Repository: https://github.com/tux4us/MiriquidiLauncher
Rechteinhaber ist der Entwickler persönlich (tux4us), nicht die GbR.

## Projektziel
- Sehr funktionaler Launcher mit nützlichen Zusatzfunktionen: Startseite mit Uhr und Suchfeld, App-Grid, Suche, seitliche "Scopes" (Seiten neben der Startseite).
- Technisch stabil und ausgereift. Architekturregeln stehen in `docs/ARCHITECTURE.md`, Scopes in `docs/SCOPES.md`.
- Entscheidungen und Begründungen: `docs/projekt/entscheidungen.md` und die weiteren Dateien in `docs/projekt/`. Vor Änderungen an bestehenden Funktionen dort nachlesen.

## Stack
- Kotlin, Jetpack Compose, Material 3, DataStore, `LauncherApps`-API.
- minSdk 26, targetSdk 35 (Stand Oktober 2026, in `app/build.gradle*` prüfen).
- `applicationId`: `io.github.tux4us.miriquidilauncher`.
- App-Sprache folgt der Systemsprache.

## Build und Test (vor dem ersten Einsatz prüfen und hier korrigieren)
- Debug-Build: `./gradlew assembleDebug`
- Unit-Tests: `./gradlew test`
- Lint: `./gradlew lint`
- Zu Beginn jeder Sitzung prüfen, ob Android SDK und Gradle-Zugriff verfügbar sind. Falls nicht: Logik-Tests, soweit möglich, mit dem Kotlin-Compiler ausführen und im Ergebnis ausdrücklich angeben, dass Android- und Compose-Dateien nicht kompiliert wurden.
- Nie behaupten, ein Build oder Test sei gelaufen, wenn er nicht ausgeführt wurde.

## Arbeitsweise
- Auf einem Feature-Branch arbeiten (z. B. `claude/<thema>`), nie direkt auf `main`.
- Nach jeder abgeschlossenen Änderung committen und pushen, damit sie in Android Studio per Pull verfügbar ist.
- Antworten und Dokumentation auf Deutsch, sachlich und technisch präzise, ohne Metaphern und Vergleiche.
- Code-Änderungen vollständig liefern, keine Fragmente. Bei Änderungen an bestehenden Dateien Kontext davor und danach nennen.
- Bei jeder Umsetzung ein Dokument in `docs/projekt/` ablegen oder aktualisieren: Entscheidungen des Nutzers, Entscheidungen des Assistenten, Prüfstand (was ist getestet, was nicht), offene Punkte.
- Rückfragen vor der Umsetzung sind erwünscht, wenn eine Entscheidung den Umfang oder die Lizenz berührt.

## Lizenz und Konformität (verbindlich)
- Lizenz: GPL-3.0-or-later. Dateien: `LICENSE`, `LICENSES/`, `THIRD_PARTY_NOTICES.md`, `res/raw/third_party_notices.txt`.
- Neue Abhängigkeiten nur mit kompatibler freier Lizenz (z. B. Apache-2.0) und mit Eintrag in `THIRD_PARTY_NOTICES.md` und `res/raw/third_party_notices.txt`.
- F-Droid- und Google-Play-Konformität muss in jedem Fall erhalten bleiben: keine proprietären Bibliotheken, keine Google-Play-Dienste, kein Tracking, keine Anti-Features, die F-Droid ausschließt.
- Keine Übernahme von Code, Texten, Namen oder Gestaltung aus dem VollaLauncher (Volla Licence 2.0, nicht frei). Der Name "Volla" wird im Projekt nicht verwendet. Die Funktion heißt "Schnellmenü", der Begriff "Springboard" bleibt in öffentlichen Texten ungenutzt.
- Aus der HundeWildnis App übernommener Code ist eigener Code des Entwicklers.
- Wetterdaten: Open-Meteo (kostenlose API nur nicht kommerziell). Quellenangaben auf der Wetterseite beibehalten.

## Sicherheit und Datenschutz
- Netzwerkzugriffe nur über HTTPS. `usesCleartextTraffic=false` beibehalten. `http://`-Adressen ablehnen.
- Passwörter und Tokens nur verschlüsselt (AES-256-GCM, Android-Keystore) speichern und nie ins Launcher-Backup aufnehmen.
- Berechtigungen (`READ_CONTACTS`, `READ_CALENDAR` usw.) optional halten und erst dort anfragen, wo sie gebraucht werden.
- Eigener XML-Leser ohne DTD- und Entity-Verarbeitung, JSON-Parser mit Tiefenbegrenzung beibehalten.
- User-Agent der Netzwerkanfragen verweist auf https://github.com/tux4us.

## Backup
- Format `miriquidilauncher-backup`, Ordner `Download/MiriquidiLauncher`.
- Der Import akzeptiert zusätzlich `bc-launcher-backup`.
- Neue Nutzerdaten (z. B. Gruppen, Podcasts, Kurse) gehören ins Backup und müssen im `BackupCodecTest` abgedeckt werden.

## Nicht committen
- Keystores, Signing-Passwörter, `local.properties`, API-Schlüssel, Zugangsdaten. Prüfen, dass sie in `.gitignore` stehen.

## Hinweise zum Stand
- Das Projekt wurde zuvor in einem claude.ai-Projekt entwickelt. Dort fehlte das Android SDK, deshalb sind viele Compose-Oberflächen und Android-abhängige Funktionen nur auf Syntax geprüft und nicht auf einem Gerät getestet. Aktuelle Angaben dazu stehen in den Prüfstand-Abschnitten der Dokumente in `docs/projekt/`.
