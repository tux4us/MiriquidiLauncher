# Privacy Policy – MiriquidiLauncher

Last updated: 1 November 2026 · applies to MiriquidiLauncher 0.6.8 and later · [Deutsche Fassung](DATENSCHUTZ.md) (the German version is authoritative)

## 1. Summary

- The developer operates **no server** for the launcher. There is **no user account, no advertising, no usage analytics, no crash reporting and no Google services**. The developer receives no data from the app.
- Personal data (favourites, notes, dog profiles, holdings on the markets page, podcast subscriptions, calculator history, etc.) is stored **on the device only**.
- Some pages (weather, markets, feed reader, notes with Nextcloud, podcasts) fetch data **directly from your device from third parties** and only when you use the page. These pages (weather, feed reader, podcasts, markets) are **hidden** after installation and after resetting the app; the launcher makes no connection for them until you show them in the settings ("Scopes" card) and agree in the notice dialog. An imported backup cannot show them without this consent. All connections use HTTPS. The recipients see at least your IP address (section 5).
- Permissions are requested **on first use** of the respective feature and can be revoked at any time (section 6).

## 2. Controller and contact

> Jörg Ulbricht
> Hohe Lehne 14
> 01705 Freital
> E-Mail: tux4us@online.de

Data protection officer:

> Jörg Ulbricht
> Hohe Lehne 14
> 01705 Freital
> E-Mail: tux4us@online.de

## 3. Principle: who processes what?

The launcher runs entirely on your device. The developer has no access to your data and cannot attribute it to a person. Data you enter or that the app reads from your device (contacts, calendar, files, photos, etc.) is processed locally only, to provide the respective feature.

For network requests (section 5), **your device** sends the request directly to the named provider, which is responsible for its own processing. The developer receives neither the requests nor the responses. Access to or storage of information on the device happens only to provide the feature you explicitly requested (Section 25(2) no. 2 TDDDG, Germany).

## 4. Data stored locally

| Feature | Data | Location |
|---|---|---|
| App favourites, app groups, scope order | App identifiers, group names, settings | App-internal storage |
| Search | Input is used for the running search only and is **not stored**. Searches installed apps, settings pages and (with permission) contacts | Memory |
| Contact favourites | Name and phone number of selected contacts | App-internal storage |
| Weather | Saved places (name, coordinates), shadow heights, last fetch (cache), optionally address and key of your own weather server | App-internal storage (key encrypted) |
| Dogs | Dog profiles (name, breed, birth date, weight, chip number, owner and phone number, vet, insurance, notes), due dates, selection of fields shown on the emergency card | App-internal storage |
| Markets | Your own holdings, chart selection, data sources, optional Stooq key | App-internal storage (key encrypted) |
| Podcasts | Subscriptions, favourites, playback position, played marks, feed cache, downloaded episodes | App-internal storage |
| Feed reader | Nextcloud address and user name, app password (encrypted), local feeds, read marks, cached articles | App-internal storage |
| Notes | Markdown files in `Documents/MiriquidiNotes`, favourites, sorting, optionally Nextcloud credentials (password encrypted) | Shared storage / app storage |
| Calculator | Angle mode, memory, last result, history (limited) | App-internal storage |
| System | Battery statistics of up to the last 7 days (recorded by the launcher), the app's own network traffic (system counters) | App-internal storage |
| Camera | Photos and videos in the `MiriquidiLauncher` folder of the media library; settings | Shared storage (media library) |
| Gallery, files | Files are shown, moved, deleted or shared only on your command; the file manager's trash keeps deleted files for 30 days | Shared storage |

Android cloud backup and device-to-device transfer are **disabled** for the app (`allowBackup=false`).

### Backup and export

On request, the backup function in the settings creates a JSON file (`Downloads/MiriquidiLauncher`). It may contain favourites (optionally without phone numbers), weather places, notes, dog profiles with due dates, podcast and markets data, feed subscriptions and settings, but **not** passwords, keys or caches. The file is **not encrypted**; other apps with access to shared storage can read it. Store it securely and delete it when no longer needed. A backup QR code contains only favourites, app groups, weather places and the scope order, **never** dog, note or markets data.

### Emergency card (dogs)

The emergency card shows the details you select as text and QR code. **Anyone who sees or scans the code can read these details**, possibly including your name and phone number. Choose the details deliberately and share the code only if you want to.

### Import from the HundeWildnis app

On request, the launcher reads only the dog profiles (file `backup_data.json`) from a backup file (`.hundebackup`) of the HundeWildnis app that you select. The file is read locally only; other content (e.g. photos) is neither evaluated nor stored.

## 5. Network connections

All connections use HTTPS (plain HTTP is blocked). For technical reasons, every request transmits your **IP address** and the program identifier (user agent) `MiriquidiLauncher/<version>`. No advertising, device or account identifiers are sent. Requests are made only when you open or refresh the respective page; there are no background requests except for playback or download of podcast episodes that you started.

| Feature | Recipient | Data transmitted | Trigger |
|---|---|---|---|
| Weather, dog weather | Open-Meteo (`api.open-meteo.com`, `air-quality-api.open-meteo.com`) or your own weather server | Coordinates of the chosen place (4 decimals, about 11 m) | Opening or refreshing the weather page |
| Place search | Open-Meteo Geocoding (`geocoding-api.open-meteo.com`) | Entered place name, language | Searching for a place |
| Current location | OpenStreetMap Nominatim (`nominatim.openstreetmap.org`) | Coordinates (4 decimals) of the coarse location supplied by the system, language | Tapping "Use current location" |
| Markets | Yahoo Finance (unofficial interface, can be switched off), CoinGecko, mempool.space or your own instance, Frankfurter (ECB rates), optionally Stooq | Identifiers of displayed values (e.g. indices, commodities, symbols), period; for security search the search term or WKN/ISIN (to Yahoo Finance); optionally your Stooq key (to Stooq). **Your holdings and quantities are not transmitted.** | Opening or refreshing the markets page |
| Feed reader (Nextcloud) | Your own Nextcloud instance | User name and app password (login), read status, subscriptions | Opening or refreshing |
| Feed reader (local feeds) | The servers of the feeds you subscribed to; with "load images" enabled also their image servers | Request of the feed address, possibly image addresses | Opening or refreshing |
| Notes (optional) | Your own Nextcloud instance (Notes) | Credentials, note texts for synchronisation | Synchronisation |
| Podcasts | Servers of the subscribed podcasts and their media; directory of your choice: Apple iTunes Search (`itunes.apple.com`) or fyyd (`api.fyyd.de`) | Feed and media address; your search term when searching | Refreshing, playing, downloading, searching |
| Links, web search | The browser you choose or its search engine; without a browser with web search, DuckDuckGo | Search term or address | Tapping a link or "Search the web" |

The recipients are controllers in their own right. What else they process and where (also outside the EU/EEA, e.g. Yahoo, Apple or CoinGecko) is governed by their privacy policies. The launcher has no influence on this; the transmission originates from your device at your initiative. For servers you enter yourself (Nextcloud, weather server, mempool instance, feeds, podcasts), the respective operator determines the processing. Open-Meteo weather data is licensed under CC BY 4.0; see `THIRD_PARTY_NOTICES.md`.

## 6. Permissions

All permissions are optional and requested on use (exceptions: `INTERNET` and the foreground service permission for podcasts, which Android grants without a prompt). Without them the respective feature is limited.

| Permission | Purpose | Leaves the device? |
|---|---|---|
| `INTERNET` | Requests as in section 5 | yes, as described there |
| `READ_CONTACTS` | Contact search, contact favourites | no |
| `CALL_PHONE`, `READ_PHONE_STATE` | Call directly after confirmation; choose SIM card (neither phone number nor device identifier is read) | no |
| `READ_CALENDAR`, `WRITE_CALENDAR` | Show, create, change, delete events; agenda card on the home page | no |
| `ACCESS_COARSE_LOCATION` | Coarse location for the weather page, only when you tap | only as coordinates to Open-Meteo/Nominatim |
| `CAMERA`, `RECORD_AUDIO` | QR scanner (decoded on the device with ZXing), camera page; sound for videos | no |
| `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`, `READ_MEDIA_VISUAL_USER_SELECTED`, `READ_EXTERNAL_STORAGE`, `WRITE_EXTERNAL_STORAGE` (up to Android 10) | Gallery | no |
| `MANAGE_EXTERNAL_STORAGE` | File manager, notes folder, move/delete in gallery, storage tree of the system page; only on user command, file contents are not sent to third parties | no |
| `PACKAGE_USAGE_STATS` | Only data and cache sizes of other apps (system page); usage data (which app ran when) is not read | no |
| `REQUEST_DELETE_PACKAGES` | Uninstall apps on request | no |
| `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | Notification and foreground service for podcast playback | no |

The app does **not** use `QUERY_ALL_PACKAGES`; it sees only apps with a launcher activity and camera apps.

## 7. Credentials

Passwords and keys (Nextcloud app password, Stooq key, key of your own weather server) are stored encrypted with a key in the Android Keystore (AES-256-GCM), sent only to the service they were entered for, and never written to backups.

## 8. Retention

Local data remains until you delete it in the app, clear the app data in Android settings or uninstall the app. Caches (weather, markets, feeds, episodes) are replaced on each fetch. The developer stores no data and therefore has no deletion periods. Retention at third parties is governed by their policies.

## 9. Your rights

Under the GDPR, where its conditions are met, you have the right of access (Art. 15), rectification (Art. 16), erasure (Art. 17), restriction (Art. 18), data portability (Art. 20) and objection (Art. 21). As the developer receives no personal data from the app, he cannot attribute any to a person (Art. 11 GDPR). You can view, export (backup) and delete the data on your device in the app itself. Towards the providers named in section 5, you exercise your rights with them. You can revoke permissions at any time in the Android settings. Send requests to the controller to the address in section 2.

You also have the right to lodge a complaint with a supervisory authority, in particular in the Member State of your residence or of the controller's seat. Competent for the controller: [competent data protection authority].

There is no automated decision-making including profiling (Art. 22 GDPR). Providing data is not mandatory; without a permission or network request the respective features are unavailable.

## 10. Distribution and source code

- **GitHub:** Source code and releases are hosted on GitHub (https://github.com/tux4us/MiriquidiLauncher). When visiting GitHub, opening issues or downloading, GitHub processes data under its own privacy policy. Please do not post personal data in public issues.
- **Google Play, F-Droid:** When obtaining the app through a store, the store operator processes data for delivery (e.g. account, device, installation) under its own policy. The developer receives at most anonymised statistics provided by the store (e.g. installation numbers, crash statistics in the Play Console).

## 11. Changes

This policy will be updated when the app or the legal situation changes. The current version is in the repository (`PRIVACY.md`, `DATENSCHUTZ.md`); the history is available in the Git log.
