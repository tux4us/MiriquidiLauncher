// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.feed

/** Farben der Leseansicht als CSS-Werte, z. B. `#1c1b1f`. */
data class ReaderColors(val background: String, val text: String, val muted: String, val link: String)

/** Baut das HTML-Dokument für die Leseansicht (WebView ohne JavaScript). */
object FeedHtml {
    /**
     * @param meta Zeile unter der Überschrift (Quelle, Autor, Datum), wird maskiert
     * @param loadImages `false` erlaubt in der Content-Security-Policy keine Bilder aus dem Netz
     */
    fun readerDocument(
        title: String,
        meta: String,
        bodyHtml: String,
        colors: ReaderColors,
        loadImages: Boolean,
    ): String {
        val images = if (loadImages) "https: data:" else "data:"
        val csp = "default-src 'none'; img-src $images; media-src ${if (loadImages) "https:" else "'none'"}; style-src 'unsafe-inline'"
        return """
            <!DOCTYPE html>
            <html>
            <head>
            <meta charset="utf-8">
            <meta http-equiv="Content-Security-Policy" content="$csp">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <meta name="referrer" content="no-referrer">
            <style>
            html { -webkit-text-size-adjust: 100%; }
            body { margin: 0; padding: 16px 16px 96px 16px; background: ${colors.background}; color: ${colors.text};
                   font-family: sans-serif; font-size: 17px; line-height: 1.6; overflow-wrap: anywhere; }
            h1 { font-size: 1.4em; line-height: 1.25; margin: 0 0 8px 0; }
            .meta { color: ${colors.muted}; font-size: 0.85em; margin: 0 0 16px 0; }
            a { color: ${colors.link}; }
            img, video { max-width: 100%; height: auto; border-radius: 6px; }
            figure { margin: 16px 0; }
            figcaption { color: ${colors.muted}; font-size: 0.85em; }
            pre, code { font-family: monospace; font-size: 0.9em; }
            pre { overflow-x: auto; padding: 8px; background: rgba(128,128,128,0.15); border-radius: 6px; }
            blockquote { margin: 12px 0; padding-left: 12px; border-left: 3px solid ${colors.muted}; color: ${colors.muted}; }
            table { display: block; overflow-x: auto; border-collapse: collapse; }
            td, th { border: 1px solid ${colors.muted}; padding: 4px 8px; }
            hr { border: 0; border-top: 1px solid ${colors.muted}; opacity: 0.4; }
            </style>
            </head>
            <body>
            <h1>${FeedText.escapeHtml(title)}</h1>
            <p class="meta">${FeedText.escapeHtml(meta)}</p>
            <hr>
            ${FeedText.sanitize(bodyHtml)}
            </body>
            </html>
        """.trimIndent()
    }
}
