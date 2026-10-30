// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.files

import java.util.Locale

/** Einordnung und Medientyp nach Dateiendung. Der Inhalt wird nicht gelesen. */
object FileTypes {
    private val IMAGES = mapOf(
        "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png", "gif" to "image/gif", "webp" to "image/webp",
        "bmp" to "image/bmp", "heic" to "image/heic", "heif" to "image/heif", "avif" to "image/avif", "svg" to "image/svg+xml",
        "dng" to "image/x-adobe-dng",
    )
    private val VIDEOS = mapOf(
        "mp4" to "video/mp4", "mkv" to "video/x-matroska", "webm" to "video/webm", "3gp" to "video/3gpp", "mov" to "video/quicktime",
        "avi" to "video/x-msvideo", "m4v" to "video/x-m4v",
    )
    private val AUDIO = mapOf(
        "mp3" to "audio/mpeg", "m4a" to "audio/mp4", "aac" to "audio/aac", "ogg" to "audio/ogg", "opus" to "audio/ogg",
        "flac" to "audio/flac", "wav" to "audio/x-wav", "mid" to "audio/midi", "amr" to "audio/amr",
    )
    private val ARCHIVES = mapOf(
        "zip" to "application/zip", "7z" to "application/x-7z-compressed", "rar" to "application/vnd.rar",
        "tar" to "application/x-tar", "gz" to "application/gzip", "bz2" to "application/x-bzip2", "xz" to "application/x-xz",
    )
    private val TEXTS = mapOf(
        "txt" to "text/plain", "md" to "text/markdown", "csv" to "text/csv", "log" to "text/plain", "json" to "application/json",
        "xml" to "text/xml", "html" to "text/html", "htm" to "text/html", "css" to "text/css", "js" to "text/javascript",
        "kt" to "text/plain", "java" to "text/plain", "py" to "text/x-python", "sh" to "text/x-sh", "ini" to "text/plain",
        "conf" to "text/plain", "yml" to "text/plain", "yaml" to "text/plain", "opml" to "text/x-opml", "gpx" to "application/gpx+xml",
        "vcf" to "text/x-vcard", "ics" to "text/calendar",
    )
    private val OTHERS = mapOf(
        "pdf" to "application/pdf",
        "apk" to "application/vnd.android.package-archive",
        "doc" to "application/msword",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xls" to "application/vnd.ms-excel",
        "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "ppt" to "application/vnd.ms-powerpoint",
        "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "odt" to "application/vnd.oasis.opendocument.text",
        "ods" to "application/vnd.oasis.opendocument.spreadsheet",
        "odp" to "application/vnd.oasis.opendocument.presentation",
        "epub" to "application/epub+zip",
    )

    /** Endung in Kleinbuchstaben ohne Punkt; leer bei Namen ohne Endung und bei Namen wie `.nomedia`. */
    fun extension(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot <= 0 || dot == name.length - 1) "" else name.substring(dot + 1).lowercase(Locale.ROOT)
    }

    fun kindOf(name: String, isDirectory: Boolean): FileKind {
        if (isDirectory) return FileKind.FOLDER
        val ext = extension(name)
        return when {
            ext in IMAGES -> FileKind.IMAGE
            ext in VIDEOS -> FileKind.VIDEO
            ext in AUDIO -> FileKind.AUDIO
            ext in ARCHIVES -> FileKind.ARCHIVE
            ext in TEXTS -> FileKind.TEXT
            ext == "pdf" -> FileKind.PDF
            ext == "apk" -> FileKind.APK
            else -> FileKind.OTHER
        }
    }

    /** Medientyp für „Öffnen mit“ und „Teilen“; unbekannte Endungen ergeben `application/octet-stream`. */
    fun mimeOf(name: String): String {
        val ext = extension(name)
        return IMAGES[ext] ?: VIDEOS[ext] ?: AUDIO[ext] ?: ARCHIVES[ext] ?: TEXTS[ext] ?: OTHERS[ext] ?: "application/octet-stream"
    }

    fun isZip(name: String): Boolean = extension(name) == "zip"
}
