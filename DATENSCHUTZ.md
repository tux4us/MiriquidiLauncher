# Datenschutzerklärung MiriquidiLauncher

Stand: 1. November 2026 · gilt für MiriquidiLauncher ab Version 0.6.8 · [English version](PRIVACY.md)

## 1. Kurzfassung

- Der Entwickler betreibt **keinen Server** für den Launcher. Es gibt **kein Benutzerkonto, keine Werbung, keine Nutzungsanalyse, keine Absturzberichte und keine Google-Dienste**. Der Entwickler erhält keine Daten aus der App.
- Persönliche Daten (Favoriten, Notizen, Hundeprofile, Kurse-Bestand, Podcast-Abos, Rechner-Verlauf u. a.) werden **nur auf dem Gerät** gespeichert.
- Einige Seiten (Wetter, Kurse, FeedReader, Notizen mit Nextcloud, Podcasts) rufen Daten **direkt vom Gerät aus bei Drittanbietern** ab, und zwar erst, wenn du die Seite benutzt. Diese Seiten (Wetter, FeedReader, Podcasts, Kurse) sind nach der Installation und nach dem Zurücksetzen der App **ausgeblendet**; der Launcher stellt für sie keine Verbindung her, bis du sie in den Einstellungen (Karte „Scopes“) einblendest und im Hinweisdialog zustimmst. Ein eingespieltes Backup kann sie nicht ohne diese Zustimmung einblenden. Die Verbindungen sind ausschließlich per HTTPS. Die Empfänger sehen dabei mindestens deine IP-Adresse (Abschnitt 5).
- Berechtigungen werden **erst bei der ersten Nutzung** der jeweiligen Funktion angefragt und sind widerrufbar (Abschnitt 6).

## 2. Verantwortlicher und Kontakt

Verantwortlich für diese Datenschutzerklärung und das Angebot der App:

> Jörg Ulbricht
> Hohe Lehne 14
> 01705 Freital
> E-Mail: tux4us@online.de

Datenschutzbeauftragter:

> Jörg Ulbricht
> Hohe Lehne 14
> 01705 Freital
> E-Mail: tux4us@online.de

## 3. Grundsatz: Wer verarbeitet was?

Der Launcher läuft vollständig auf deinem Gerät. Der Entwickler hat auf deine Daten keinen Zugriff und kann sie nicht einer Person zuordnen. Daten, die du in die App eingibst oder die die App aus deinem Gerät liest (Kontakte, Kalender, Fotos u. a.), werden nur lokal verarbeitet, um die jeweilige Funktion bereitzustellen.

Bei Netzwerkabrufen (Abschnitt 5) sendet **dein Gerät** die Anfrage direkt an den genannten Anbieter. Dieser ist für seine Verarbeitung selbst verantwortlich. Der Entwickler erhält weder die Anfragen noch die Antworten.

Soweit die App auf Informationen im Endgerät zugreift oder dort speichert, geschieht das ausschließlich, um die von dir ausdrücklich gewünschte Funktion bereitzustellen (§ 25 Abs. 2 Nr. 2 TDDDG).

## 4. Lokal gespeicherte Daten

| Funktion | Daten | Speicherort |
|---|---|---|
| App-Favoriten, App-Gruppen, Scope-Reihenfolge | Kennungen von Apps, Namen der Gruppen, Einstellungen | Interner App-Speicher |
| Suche | Eingabe wird nur für die laufende Suche verwendet und **nicht gespeichert**. Durchsucht werden installierte Apps, Einstellungsseiten und (nur mit Berechtigung) Kontakte | Arbeitsspeicher |
| Kontakt-Favoriten | Name und Telefonnummer ausgewählter Kontakte | Interner App-Speicher |
| Wetter | Gespeicherte Orte (Name, Koordinaten), Schattenhöhen, letzter Abruf (Zwischenspeicher), optional Adresse und Schlüssel eines eigenen Wetterservers | Interner App-Speicher (Schlüssel verschlüsselt) |
| Hunde | Hundeprofile (Name, Rasse, Geburtsdatum, Gewicht, Chipnummer, Besitzer und Telefonnummer, Tierarzt, Versicherung, Hinweise), Fälligkeiten, Auswahl der Angaben auf der Notfallkarte | Interner App-Speicher |
| Kurse | Eigener Bestand („Eigene Werte“), Diagrammauswahl, Datenquellen, optional Stooq-Schlüssel | Interner App-Speicher (Schlüssel verschlüsselt) |
| Podcasts | Abos, Favoriten, Hörposition, Gehört-Markierungen, Zwischenspeicher der Feeds, heruntergeladene Folgen | Interner App-Speicher |
| FeedReader | Nextcloud-Adresse und Benutzername, App-Passwort (verschlüsselt), lokale Feeds, Gelesen-Markierungen, zwischengespeicherte Artikel | Interner App-Speicher |
| Notizen | Markdown-Dateien im app-eigenen Dokumentenordner (`Android/data/<Paket>/files/Documents/MiriquidiNotes`), Favoriten, Sortierung, optional Nextcloud-Zugang (Passwort verschlüsselt) | App-Speicher |
| Rechner | Winkelmodus, Speicherwert, letztes Ergebnis, Verlauf (begrenzt) | Interner App-Speicher |
| System | Akku-Statistik der letzten bis zu 7 Tage (vom Launcher aufgezeichnet), eigener Netzwerkverkehr der App (Zähler des Systems) | Interner App-Speicher |
| Kamera | Fotos und Videos im Ordner `MiriquidiLauncher` der Mediathek; Einstellungen | Gemeinsamer Speicher (Mediathek) |
| Galerie | Fotos und Videos werden nur auf deinen Befehl angezeigt, verschoben, kopiert, umbenannt, gelöscht oder geteilt; Papierkorb, Löschen, Umbenennen und Verschieben bestätigt Android jeweils mit einem Systemdialog; der Papierkorb der Mediathek hält gelöschte Medien 30 Tage vor. Favoriten und ausgeblendete Alben speichert die App lokal | Gemeinsamer Speicher (Mediathek), App-Speicher |

Android-Cloud-Backup und Gerät-zu-Gerät-Übertragung sind für die App **ausgeschaltet** (`allowBackup=false`).

### Backup und Export

Die Backup-Funktion in den Einstellungen erzeugt auf Wunsch eine JSON-Datei (Ordner `Downloads/MiriquidiLauncher`). Sie kann Favoriten (optional ohne Telefonnummern), Wetterorte, Notizen, Hundeprofile mit Fälligkeiten, Podcast- und Kursdaten, Feed-Abos und Einstellungen enthalten, **nicht** aber Passwörter, Schlüssel oder Zwischenspeicher. Die Datei ist **nicht verschlüsselt**. Andere Apps mit Zugriff auf den gemeinsamen Speicher können sie lesen. Bewahre sie sicher auf und lösche sie, wenn du sie nicht mehr brauchst. Ein Backup-QR-Code enthält nur Favoriten, App-Gruppen, Wetterorte und die Scope-Anordnung, **nie** Hunde-, Notiz- oder Kursdaten.

### Notfallkarte (Hunde)

Die Notfallkarte zeigt die von dir gewählten Angaben als Text und QR-Code. **Jede Person, die den Code sieht oder scannt, kann diese Angaben lesen**, darunter ggf. deinen Namen und deine Telefonnummer. Wähle die Angaben bewusst aus und gib den Code nur weiter, wenn du es willst.

### Import aus der HundeWildnis-App

Auf deinen Wunsch liest der Launcher aus einer von dir gewählten Sicherungsdatei der HundeWildnis-App (`.hundebackup`) ausschließlich die Hundeprofile (Datei `backup_data.json`). Die Datei wird nur lokal gelesen; sonstige Inhalte (z. B. Fotos) werden nicht ausgewertet oder gespeichert.

## 5. Netzwerkverbindungen

Alle Verbindungen laufen verschlüsselt über HTTPS (unverschlüsseltes HTTP ist gesperrt). Die App sendet bei jeder Anfrage technisch bedingt deine **IP-Adresse** und den Programmkennzeichner (User-Agent) `MiriquidiLauncher/<Version>`. Es werden keine Werbe-, Geräte- oder Konto-Kennungen gesendet. Ein Abruf erfolgt nur, wenn du die jeweilige Seite öffnest oder aktualisierst; es gibt keinen Hintergrundabruf mit Ausnahme der Wiedergabe oder des Downloads von Podcast-Folgen, die du gestartet hast.

| Funktion | Empfänger | Übertragene Daten | Auslöser |
|---|---|---|---|
| Wetter, Hunde-Wetter | Open-Meteo (`api.open-meteo.com`, `air-quality-api.open-meteo.com`) oder dein eigener Wetterserver | Koordinaten des gewählten Ortes (4 Nachkommastellen, ca. 11 m) | Öffnen oder Aktualisieren der Wetterseite |
| Ortssuche | Open-Meteo Geocoding (`geocoding-api.open-meteo.com`) | eingegebener Ortsname, Sprache | Suche nach einem Ort |
| Aktueller Standort | OpenStreetMap Nominatim (`nominatim.openstreetmap.org`) | Koordinaten (4 Nachkommastellen) des vom System gelieferten groben Standorts, Sprache | Tippen auf „Aktuellen Standort verwenden“ |
| Kurse | Yahoo Finance (inoffizielle Schnittstelle, abschaltbar), CoinGecko, mempool.space oder eigene Instanz, Frankfurter (EZB-Kurse), optional Stooq | Kennungen der angezeigten Werte (z. B. Indizes, Rohstoffe, Symbole), Zeitraum; bei der Wertpapiersuche der Suchbegriff bzw. die WKN/ISIN (an Yahoo Finance); ggf. dein Stooq-Schlüssel (an Stooq). **Dein Bestand und deine Stückzahlen werden nicht übertragen.** | Öffnen oder Aktualisieren der Kursseite |
| FeedReader (Nextcloud) | deine eigene Nextcloud-Instanz | Benutzername und App-Passwort (Anmeldung), Gelesen-Status, Abonnements | Öffnen oder Aktualisieren |
| FeedReader (lokale Feeds) | die Server der Feeds, die du abonniert hast; bei eingeschaltetem „Bilder laden“ auch deren Bildserver | Abruf der Feed-Adresse, ggf. Bild-Adressen | Öffnen oder Aktualisieren |
| Notizen (optional) | deine eigene Nextcloud-Instanz (Notes) | Zugangsdaten, Notiztexte zum Abgleich | Abgleich |
| Podcasts | Server der abonnierten Podcasts und ihrer Medien; Verzeichnis nach deiner Wahl: Apple iTunes Search (`itunes.apple.com`) oder fyyd (`api.fyyd.de`) | Feed- und Medienadresse; bei der Suche dein Suchbegriff | Aktualisieren, Abspielen, Download, Suche |
| Links, Websuche | der von dir gewählte Browser bzw. die von ihm genutzte Suchmaschine; ohne Browser mit Websuche DuckDuckGo | Suchbegriff bzw. Adresse | Tippen auf einen Link bzw. „Im Web suchen“ |

Die genannten Empfänger sind eigene Verantwortliche. Welche Daten sie darüber hinaus verarbeiten und wo (auch außerhalb der EU/des EWR, etwa bei Yahoo, Apple oder CoinGecko), richtet sich nach deren Datenschutzerklärungen. Darauf hat der Launcher keinen Einfluss; die Übermittlung geht von deinem Gerät auf deine Veranlassung aus. Bei Servern, die du selbst einträgst (Nextcloud, Wetterserver, Mempool-Instanz, Feeds, Podcasts), bestimmt der jeweilige Betreiber die Verarbeitung.

Die Wetterdaten von Open-Meteo stehen unter CC BY 4.0; Näheres in `THIRD_PARTY_NOTICES.md`.

## 6. Berechtigungen

Alle Berechtigungen sind optional und werden erst beim Gebrauch der Funktion angefragt (Ausnahme: `INTERNET` und die Vordergrunddienst-Berechtigung für Podcasts, die Android ohne Abfrage erteilt). Ohne sie bleibt die jeweilige Funktion eingeschränkt.

| Berechtigung | Zweck | Verlässt das Gerät? |
|---|---|---|
| `INTERNET` | Abrufe nach Abschnitt 5 | ja, wie dort beschrieben |
| `READ_CONTACTS` | Kontaktsuche, Kontakt-Favoriten | nein |
| `CALL_PHONE`, `READ_PHONE_STATE` | Direkt anrufen nach Bestätigung; Auswahl der SIM-Karte (es wird weder Rufnummer noch Gerätekennung gelesen) | nein |
| `READ_CALENDAR`, `WRITE_CALENDAR` | Termine anzeigen, anlegen, ändern, löschen; Termin-Card der Startseite | nein |
| `ACCESS_COARSE_LOCATION` | Grober Standort für die Wetterseite, nur auf Tippen | nur als Koordinaten an Open-Meteo/Nominatim |
| `CAMERA`, `RECORD_AUDIO` | QR-Scanner (Dekodierung mit ZXing auf dem Gerät), Kamera-Seite; Ton bei Videos | nein |
| `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`, `READ_MEDIA_VISUAL_USER_SELECTED`, `READ_EXTERNAL_STORAGE`, `WRITE_EXTERNAL_STORAGE` (bis Android 10) | Galerie | nein |
| `PACKAGE_USAGE_STATS` | Nur Daten- und Cachegrößen anderer Apps (Systemseite); die Nutzungsdaten (welche App wann lief) werden nicht gelesen | nein |
| `REQUEST_DELETE_PACKAGES` | Apps auf Wunsch deinstallieren | nein |
| `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | Benachrichtigung und Vordergrunddienst der Podcast-Wiedergabe | nein |

Die App verwendet **kein** `QUERY_ALL_PACKAGES`; sie sieht nur Apps mit Launcher-Aktivität und Kamera-Apps.

## 7. Zugangsdaten

Passwörter und Schlüssel (Nextcloud-App-Passwort, Stooq-Schlüssel, Schlüssel eines eigenen Wetterservers) werden mit einem Schlüssel im Android-Keystore (AES-256-GCM) verschlüsselt gespeichert, nur an den jeweils eingetragenen Dienst gesendet und nicht in Backups geschrieben.

## 8. Speicherdauer

Lokale Daten bleiben, bis du sie in der App löschst, die App-Daten in den Android-Einstellungen löschst oder die App deinstallierst. Zwischenspeicher (Wetter, Kurse, Feeds, Folgen) werden bei jedem Abruf ersetzt. Der Entwickler speichert keine Daten und hat deshalb keine Löschfristen. Die Speicherdauer bei Dritten richtet sich nach deren Erklärungen.

## 9. Deine Rechte

Du hast nach der DSGVO, soweit deren Voraussetzungen vorliegen, das Recht auf Auskunft (Art. 15), Berichtigung (Art. 16), Löschung (Art. 17), Einschränkung der Verarbeitung (Art. 18), Datenübertragbarkeit (Art. 20) und Widerspruch (Art. 21). Da der Entwickler keine personenbezogenen Daten aus der App erhält, kann er sie auch keiner Person zuordnen (Art. 11 DSGVO). Die Daten auf deinem Gerät kannst du in der App selbst einsehen, exportieren (Backup) und löschen. Gegenüber den in Abschnitt 5 genannten Anbietern machst du deine Rechte dort geltend. Einwilligungen (Berechtigungen) kannst du jederzeit in den Android-Einstellungen widerrufen. Anfragen an den Verantwortlichen richtest du an die Adresse in Abschnitt 2.

Du hast außerdem das Recht, dich bei einer Datenschutzaufsichtsbehörde zu beschweren, insbesondere in dem Mitgliedstaat deines Aufenthaltsorts oder des Sitzes des Verantwortlichen. Zuständig ist für den Verantwortlichen: [zuständige Landesdatenschutzbehörde].

Eine automatisierte Entscheidungsfindung einschließlich Profiling (Art. 22 DSGVO) findet nicht statt. Die Bereitstellung von Daten ist nicht vorgeschrieben; ohne Berechtigung oder Netzabruf stehen die betreffenden Funktionen nicht zur Verfügung.

## 10. Bezug und Quellcode

- **GitHub:** Quellcode und Veröffentlichungen liegen auf GitHub (https://github.com/tux4us/MiriquidiLauncher). Beim Besuch von GitHub, bei Issues und beim Herunterladen verarbeitet GitHub Daten nach seiner eigenen Datenschutzerklärung. Bitte schreibe in öffentliche Issues keine persönlichen Daten.
- **Google Play, F-Droid:** Beim Bezug über einen App-Store verarbeitet der Betreiber des Stores Daten zur Auslieferung (z. B. Konto, Gerät, Installation) nach seiner eigenen Erklärung. Der Entwickler erhält daraus allenfalls anonymisierte Statistiken (z. B. Installationszahlen, Absturzstatistiken der Play Console), die der Store bereitstellt.

## 11. Änderungen

Diese Erklärung wird angepasst, wenn sich die App oder die Rechtslage ändert. Die jeweils gültige Fassung steht im Repository (`DATENSCHUTZ.md`); der Verlauf ist in der Git-Historie nachvollziehbar.
