// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.camera

/**
 * Textformat für den Datenspeicher: Kopfzeile `v1`, danach je Einstellung eine Zeile `schlüssel=wert`. Aufzählungen
 * stehen mit ihrem Namen. Unbekannte Zeilen und ungültige Werte werden beim Lesen übersprungen; es gilt dann der
 * Standardwert. Adressen dürfen `=` enthalten, gelesen wird ab dem ersten `=`.
 */
object CameraSettingsCodec {
    private const val HEADER = "v1"

    fun encode(settings: CameraSettings): String = buildString {
        append(HEADER).append('\n')
        settings.cameraId?.takeIf { it.isNotBlank() }?.let { line("camera", it.oneLine()) }
        line("flash", settings.flash.name)
        line("ratio", settings.ratio.name)
        line("timer", settings.timer.name)
        line("grid", settings.grid.name)
        line("format", settings.photoFormat.name)
        line("extension", settings.extension.name)
        line("priority", settings.priority.name)
        line("jpeg", settings.jpegQuality.name)
        line("vquality", settings.videoQuality.name)
        line("fps", settings.frameRate.name)
        line("stabilization", settings.stabilization.toString())
        line("audio", settings.audio.toString())
        line("mirror", settings.mirrorFront.toString())
        settings.lastUri?.takeIf { it.isNotBlank() }?.let { line("last", it.oneLine()) }
        line("lastvideo", settings.lastIsVideo.toString())
    }

    private fun StringBuilder.line(key: String, value: String) {
        append(key).append('=').append(value).append('\n')
    }

    private fun String.oneLine(): String = replace('\n', ' ').replace('\r', ' ')

    fun decode(text: String?): CameraSettings {
        if (text.isNullOrBlank()) return CameraSettings()
        val lines = text.lines()
        if (lines.firstOrNull() != HEADER) return CameraSettings()
        var s = CameraSettings()
        for (line in lines.drop(1)) {
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val key = line.substring(0, eq)
            val value = line.substring(eq + 1)
            s = when (key) {
                "camera" -> s.copy(cameraId = value.takeIf { it.isNotBlank() })
                "flash" -> s.copy(flash = enumOr(value, s.flash))
                "ratio" -> s.copy(ratio = enumOr(value, s.ratio))
                "timer" -> s.copy(timer = enumOr(value, s.timer))
                "grid" -> s.copy(grid = enumOr(value, s.grid))
                "format" -> s.copy(photoFormat = enumOr(value, s.photoFormat))
                "extension" -> s.copy(extension = enumOr(value, s.extension))
                "priority" -> s.copy(priority = enumOr(value, s.priority))
                "jpeg" -> s.copy(jpegQuality = enumOr(value, s.jpegQuality))
                "vquality" -> s.copy(videoQuality = enumOr(value, s.videoQuality))
                "fps" -> s.copy(frameRate = enumOr(value, s.frameRate))
                "stabilization" -> s.copy(stabilization = boolOr(value, s.stabilization))
                "audio" -> s.copy(audio = boolOr(value, s.audio))
                "mirror" -> s.copy(mirrorFront = boolOr(value, s.mirrorFront))
                "last" -> s.copy(lastUri = value.takeIf { it.isNotBlank() })
                "lastvideo" -> s.copy(lastIsVideo = boolOr(value, s.lastIsVideo))
                else -> s
            }
        }
        return s
    }

    private inline fun <reified E : Enum<E>> enumOr(value: String, default: E): E =
        enumValues<E>().firstOrNull { it.name == value } ?: default

    private fun boolOr(value: String, default: Boolean): Boolean = when (value) {
        "true" -> true
        "false" -> false
        else -> default
    }
}
