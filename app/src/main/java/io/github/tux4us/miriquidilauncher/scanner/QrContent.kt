// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.scanner

/** Inhalt eines gescannten QR-Codes, nach Art erkannt. [raw] ist immer der vollständige Text des Codes. */
sealed interface QrContent {
    val raw: String

    data class Url(override val raw: String, val url: String) : QrContent
    data class Phone(override val raw: String, val number: String) : QrContent
    data class Sms(override val raw: String, val number: String, val body: String?) : QrContent
    data class Email(override val raw: String, val address: String, val subject: String?, val body: String?) : QrContent
    data class Geo(override val raw: String, val latitude: Double, val longitude: Double, val label: String?) : QrContent
    data class Wifi(
        override val raw: String,
        val ssid: String,
        val password: String?,
        val security: String?,
        val hidden: Boolean,
    ) : QrContent
    data class VCard(override val raw: String, val name: String?) : QrContent
    data class Text(override val raw: String) : QrContent
}

/** Vorschlag für Dateiname und Typ beim Speichern. */
data class FileSuggestion(val name: String, val mime: String)

object QrParser {
    private val EMAIL = Regex("^[A-Za-z0-9._%+'-]+@[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)+$")
    private val PHONE = Regex("^[+]?[0-9 ()./-]{3,}$")

    /**
     * Erkennt die Art des Inhalts. Nur `http`/`https`-Adressen gelten als Link; andere Schemata (etwa `javascript:`,
     * `intent:`, `file:`) bleiben Text und werden nie als Link angeboten.
     */
    fun parse(input: String): QrContent {
        val text = input.trim()
        if (text.isEmpty()) return QrContent.Text(input)
        val lower = text.lowercase()
        val parsed: QrContent? = when {
            lower.startsWith("http://") || lower.startsWith("https://") -> url(text, text)
            lower.startsWith("www.") -> url(text, "https://$text")
            lower.startsWith("tel:") -> phone(text)
            lower.startsWith("smsto:") || lower.startsWith("sms:") -> sms(text)
            lower.startsWith("mailto:") -> mailto(text)
            lower.startsWith("matmsg:") -> matmsg(text)
            lower.startsWith("geo:") -> geo(text)
            lower.startsWith("wifi:") -> wifi(text)
            lower.startsWith("begin:vcard") -> vcard(text)
            EMAIL.matches(text) -> QrContent.Email(text, text, null, null)
            else -> null
        }
        return parsed ?: QrContent.Text(input)
    }

    /** `true`, wenn der Text Zeichen enthält, die auf Binärdaten statt Text hindeuten. */
    fun isBinary(text: String): Boolean =
        text.any { it == '�' || (it.code < 0x20 && it != '\n' && it != '\r' && it != '\t') }

    fun fileSuggestion(content: QrContent, binary: Boolean, stamp: String): FileSuggestion = when {
        binary -> FileSuggestion("qr-$stamp.bin", "application/octet-stream")
        content is QrContent.VCard -> FileSuggestion("qr-$stamp.vcf", "text/x-vcard")
        else -> FileSuggestion("qr-$stamp.txt", "text/plain")
    }

    private fun url(raw: String, url: String): QrContent? {
        if (url.any { it.isWhitespace() }) return null
        val host = url.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
        if (host.isEmpty() || !host.contains('.') && !host.equals("localhost", true) && !host.contains(':')) return null
        return QrContent.Url(raw, url)
    }

    private fun phone(text: String): QrContent? {
        val number = text.substring(4).trim()
        return if (PHONE.matches(number)) QrContent.Phone(text, number) else null
    }

    private fun sms(text: String): QrContent? {
        val lower = text.lowercase()
        val rest = text.substring(text.indexOf(':') + 1)
        val number: String
        var body: String? = null
        if (lower.startsWith("smsto:")) {
            number = rest.substringBefore(':').trim()
            if (rest.contains(':')) body = rest.substringAfter(':')
        } else {
            number = rest.substringBefore('?').substringBefore(';').trim()
            body = queryParam(rest.substringAfter('?', ""), "body")
        }
        return if (PHONE.matches(number)) QrContent.Sms(text, number, body?.takeIf { it.isNotEmpty() }) else null
    }

    private fun mailto(text: String): QrContent? {
        val rest = text.substring(7)
        val address = percentDecode(rest.substringBefore('?')).trim()
        if (!address.contains('@') || address.any { it.isWhitespace() }) return null
        val query = rest.substringAfter('?', "")
        return QrContent.Email(text, address, queryParam(query, "subject"), queryParam(query, "body"))
    }

    /** DoCoMo-Format `MATMSG:TO:a@b.c;SUB:Betreff;BODY:Text;;`. */
    private fun matmsg(text: String): QrContent? {
        val fields = splitFields(text.substring(7))
        val address = fields["TO"]?.trim().orEmpty()
        if (!address.contains('@')) return null
        return QrContent.Email(text, address, fields["SUB"]?.takeIf { it.isNotEmpty() }, fields["BODY"]?.takeIf { it.isNotEmpty() })
    }

    private fun geo(text: String): QrContent? {
        val rest = text.substring(4)
        val coords = rest.substringBefore('?').substringBefore(';').split(',')
        if (coords.size < 2) return null
        val lat = coords[0].trim().toDoubleOrNull() ?: return null
        val lon = coords[1].trim().toDoubleOrNull() ?: return null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        return QrContent.Geo(text, lat, lon, queryParam(rest.substringAfter('?', ""), "q"))
    }

    /** `WIFI:T:WPA;S:Name;P:Passwort;H:true;;` mit Maskierung durch Rückstrich. */
    private fun wifi(text: String): QrContent? {
        val fields = splitFields(text.substring(5))
        val ssid = fields["S"] ?: return null
        val security = fields["T"]?.takeIf { it.isNotEmpty() }
        val password = fields["P"]?.takeIf { it.isNotEmpty() && !"nopass".equals(security, true) }
        return QrContent.Wifi(text, ssid, password, security, fields["H"].equals("true", true))
    }

    private fun vcard(text: String): QrContent {
        val lines = text.lines().map { it.trim() }
        val fn = lines.firstOrNull { it.startsWith("FN:", true) }?.substring(3)?.trim()
        val name = fn?.takeIf { it.isNotEmpty() }
            ?: lines.firstOrNull { it.startsWith("N:", true) }?.substring(2)?.split(';')
                ?.map { it.trim() }?.filter { it.isNotEmpty() }?.reversed()?.joinToString(" ")?.takeIf { it.isNotEmpty() }
        return QrContent.VCard(text, name)
    }

    /** Zerlegt `K:Wert;K:Wert;;` in Felder; `\;`, `\:`, `\,`, `\"` und `\\` sind maskiert. Erstes Vorkommen eines Schlüssels gilt. */
    internal fun splitFields(body: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        val key = StringBuilder()
        val value = StringBuilder()
        var inValue = false
        var escaped = false
        fun flush() {
            if (inValue && key.isNotEmpty()) result.putIfAbsent(key.toString().uppercase(), value.toString())
            key.setLength(0)
            value.setLength(0)
            inValue = false
        }
        for (c in body) {
            when {
                escaped -> {
                    (if (inValue) value else key).append(c)
                    escaped = false
                }
                c == '\\' -> escaped = true
                c == ';' -> flush()
                c == ':' && !inValue -> inValue = true
                else -> (if (inValue) value else key).append(c)
            }
        }
        flush()
        return result
    }

    private fun queryParam(query: String, name: String): String? {
        if (query.isEmpty()) return null
        for (part in query.split('&')) {
            val key = part.substringBefore('=')
            if (key.equals(name, ignoreCase = true) && part.contains('=')) {
                return percentDecode(part.substringAfter('=')).takeIf { it.isNotEmpty() }
            }
        }
        return null
    }

    /** Dekodiert `%XX` als UTF-8; ungültige Folgen bleiben unverändert. */
    internal fun percentDecode(s: String): String {
        if (!s.contains('%')) return s
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length) {
                val hex = s.substring(i + 1, i + 3).toIntOrNull(16)
                if (hex != null) {
                    out.write(hex)
                    i += 3
                    continue
                }
            }
            out.write(c.toString().toByteArray(Charsets.UTF_8))
            i++
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }
}
