// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.search

/**
 * Eine Seite der Android-Einstellungen, die das Suchfeld direkt öffnen kann. [key] benennt den Anzeigenamen
 * (Textressource `settings_target_<key>`), [action] ist die Intent-Aktion des Systems, [keywords] sind
 * Suchbegriffe in Deutsch und Englisch.
 */
data class SettingsTarget(val key: String, val action: String, val keywords: List<String>)

object SettingsCatalog {
    val targets: List<SettingsTarget> = listOf(
        SettingsTarget("wifi", "android.settings.WIFI_SETTINGS", listOf("wlan", "wifi", "wi-fi", "wireless lan", "internet")),
        SettingsTarget("bluetooth", "android.settings.BLUETOOTH_SETTINGS", listOf("bluetooth")),
        SettingsTarget("airplane", "android.settings.AIRPLANE_MODE_SETTINGS", listOf("flugmodus", "flugzeugmodus", "airplane mode", "flight mode")),
        SettingsTarget("mobile_network", "android.settings.NETWORK_OPERATOR_SETTINGS", listOf("mobilfunk", "mobile netzwerk", "mobile data", "mobile daten", "sim", "netzbetreiber", "cellular")),
        SettingsTarget("wireless", "android.settings.WIRELESS_SETTINGS", listOf("netzwerk", "drahtlos", "hotspot", "tethering", "network", "connections", "verbindungen")),
        SettingsTarget("vpn", "android.net.vpn.SETTINGS", listOf("vpn")),
        SettingsTarget("nfc", "android.settings.NFC_SETTINGS", listOf("nfc")),
        SettingsTarget("display", "android.settings.DISPLAY_SETTINGS", listOf("display", "bildschirm", "helligkeit", "brightness", "schriftgröße", "font size", "dunkelmodus", "dark mode")),
        SettingsTarget("sound", "android.settings.SOUND_SETTINGS", listOf("ton", "sound", "lautstärke", "volume", "klingelton", "ringtone", "audio")),
        SettingsTarget("battery", "android.intent.action.POWER_USAGE_SUMMARY", listOf("akku", "batterie", "battery", "stromverbrauch", "power usage")),
        SettingsTarget("battery_saver", "android.settings.BATTERY_SAVER_SETTINGS", listOf("energiesparmodus", "akkusparmodus", "battery saver", "power saving")),
        SettingsTarget("storage", "android.settings.INTERNAL_STORAGE_SETTINGS", listOf("speicher", "storage", "speicherplatz", "festplatte")),
        SettingsTarget("apps", "android.settings.MANAGE_ALL_APPLICATIONS_SETTINGS", listOf("apps", "anwendungen", "applications", "app verwaltung", "installierte apps")),
        SettingsTarget("default_apps", "android.settings.MANAGE_DEFAULT_APPS_SETTINGS", listOf("standard apps", "standardanwendungen", "default apps")),
        SettingsTarget("home", "android.settings.HOME_SETTINGS", listOf("startbildschirm", "standard launcher", "home app", "default launcher", "home screen")),
        SettingsTarget("location", "android.settings.LOCATION_SOURCE_SETTINGS", listOf("standort", "ortung", "gps", "location")),
        SettingsTarget("privacy", "android.settings.PRIVACY_SETTINGS", listOf("datenschutz", "privatsphäre", "privacy", "berechtigungen", "permissions")),
        SettingsTarget("security", "android.settings.SECURITY_SETTINGS", listOf("sicherheit", "security", "bildschirmsperre", "screen lock", "pin", "passwort", "fingerabdruck", "fingerprint")),
        SettingsTarget("accessibility", "android.settings.ACCESSIBILITY_SETTINGS", listOf("bedienungshilfen", "barrierefreiheit", "accessibility", "talkback")),
        SettingsTarget("language", "android.settings.LOCALE_SETTINGS", listOf("sprache", "language", "sprachen", "locale")),
        SettingsTarget("keyboard", "android.settings.INPUT_METHOD_SETTINGS", listOf("tastatur", "keyboard", "eingabemethode", "input method")),
        SettingsTarget("date", "android.settings.DATE_SETTINGS", listOf("datum", "uhrzeit", "zeit", "zeitzone", "date", "time", "time zone")),
        SettingsTarget("accounts", "android.settings.SYNC_SETTINGS", listOf("konten", "accounts", "synchronisierung", "sync")),
        SettingsTarget("print", "android.settings.ACTION_PRINT_SETTINGS", listOf("drucken", "drucker", "print", "printing")),
        SettingsTarget("developer", "android.settings.APPLICATION_DEVELOPMENT_SETTINGS", listOf("entwickleroptionen", "developer options", "usb debugging")),
        SettingsTarget("about", "android.settings.DEVICE_INFO_SETTINGS", listOf("telefoninfo", "geräteinformationen", "über das telefon", "about phone", "device info", "android version", "build nummer")),
        SettingsTarget("settings", "android.settings.SETTINGS", listOf("einstellungen", "settings")),
    )

    /** Passende Ziele mit Punktzahl (Werte von [Matcher]), beste zuerst. [normalizedQuery] ist mit [TextNormalizer] bearbeitet. */
    fun match(normalizedQuery: String): List<Pair<SettingsTarget, Int>> {
        if (normalizedQuery.length < 2) return emptyList()
        return targets.mapNotNull { target ->
            val best = target.keywords.maxOf { Matcher.score(normalizedQuery, TextNormalizer.normalize(it)) }
            if (best > Matcher.NO_MATCH) target to best else null
        }.sortedByDescending { it.second }
    }
}
