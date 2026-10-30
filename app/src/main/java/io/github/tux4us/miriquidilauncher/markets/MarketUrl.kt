// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.markets

import java.net.URI

/** Ergebnis der Prüfung einer eingegebenen Basis-Adresse für die Bitcoin-Daten. */
sealed interface BaseUrlCheck {
    /** Gültig; [url] ist die bereinigte Adresse ohne abschließenden Schrägstrich. */
    data class Ok(val url: String) : BaseUrlCheck
    data object Empty : BaseUrlCheck

    /** Kein HTTPS. Der Launcher verbindet sich nur verschlüsselt. */
    data object NotHttps : BaseUrlCheck
    data object Invalid : BaseUrlCheck
}

/**
 * Basis-Adresse der Bitcoin-Datenquelle. Standard ist die öffentliche Instanz von mempool.space; alternativ kann eine
 * eigene, mempool-kompatible Instanz (gleiche `/api/...`-Pfade) eingetragen werden. Nur HTTPS.
 */
object MarketUrl {
    const val DEFAULT = "https://mempool.space"
    private const val MAX_LENGTH = 200

    fun check(input: String): BaseUrlCheck {
        val text = input.trim()
        if (text.isEmpty()) return BaseUrlCheck.Empty
        if (text.length > MAX_LENGTH || text.any { it.isWhitespace() || it.isISOControl() }) return BaseUrlCheck.Invalid
        val lower = text.lowercase()
        if (lower.startsWith("http://")) return BaseUrlCheck.NotHttps
        // Ohne Schema wird https:// ergänzt.
        val withScheme = if (lower.startsWith("https://")) text else if ("://" in text) return BaseUrlCheck.Invalid else "https://$text"
        val uri = try {
            URI(withScheme)
        } catch (e: java.net.URISyntaxException) {
            return BaseUrlCheck.Invalid
        }
        val host = uri.host?.lowercase() ?: return BaseUrlCheck.Invalid
        if (host.isEmpty() || uri.userInfo != null || uri.query != null || uri.fragment != null) return BaseUrlCheck.Invalid
        if (uri.port != -1 && uri.port !in 1..65535) return BaseUrlCheck.Invalid
        var path = (uri.rawPath ?: "").trimEnd('/')
        if (path.endsWith("/api")) path = path.removeSuffix("/api")
        if (path.isNotEmpty() && !path.startsWith("/")) return BaseUrlCheck.Invalid
        val port = if (uri.port != -1) ":${uri.port}" else ""
        return BaseUrlCheck.Ok("https://$host$port$path")
    }

    /** Wahr, wenn [url] die öffentliche Standard-Instanz ist (oder fehlt). */
    fun isDefault(url: String?): Boolean = url == null || url == DEFAULT
}
