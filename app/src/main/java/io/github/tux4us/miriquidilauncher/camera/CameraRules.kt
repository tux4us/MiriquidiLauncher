// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.camera

import java.util.Locale
import kotlin.math.roundToInt

/**
 * Regeln, die Wünsche des Nutzers mit den Fähigkeiten der gewählten Kamera verbinden. Alles hier ist rein
 * rechnerisch und auf der JVM getestet; die Android-Seite (`CameraController`) wendet das Ergebnis nur an.
 */
object CameraRules {

    /**
     * Gibt die Bindung zurück, die tatsächlich verwendet wird:
     * - Erweiterungen gibt es nur im Fotomodus, nur wenn verfügbar und nur ohne manuelle Werte.
     * - Ultra HDR und RAW nur, wenn die Kamera sie meldet und keine Erweiterung aktiv ist (Erweiterungen liefern JPEG).
     * - Videoqualität, Bildrate und Stabilisierung nur, wenn unterstützt.
     * - Felder, die im jeweils anderen Modus keine Rolle spielen, werden auf feste Werte gesetzt, damit ein Wechsel
     *   solcher Einstellungen keine neue Bindung auslöst.
     */
    fun resolve(request: BindRequest, caps: CameraCaps): BindRequest {
        val video = request.mode == CaptureMode.Video
        val extension = when {
            video || request.manualActive -> ExtensionSetting.None
            request.extension in caps.extensions -> request.extension
            else -> ExtensionSetting.None
        }
        val format = when {
            video || extension != ExtensionSetting.None -> PhotoFormat.Jpeg
            request.photoFormat in caps.photoFormats -> request.photoFormat
            else -> PhotoFormat.Jpeg
        }
        return request.copy(
            extension = extension,
            photoFormat = format,
            priority = if (video) PriorityMode.Quality else request.priority,
            jpegQuality = if (video || format == PhotoFormat.Raw) JpegQuality.High else request.jpegQuality,
            videoQuality = if (video) effectiveVideoQuality(request.videoQuality, caps) else VideoQualitySetting.Highest,
            frameRate = if (video) effectiveFrameRate(request.frameRate, caps) else FrameRateSetting.Auto,
            stabilization = video && request.stabilization && caps.stabilization,
        )
    }

    private fun rank(quality: VideoQualitySetting): Int = when (quality) {
        VideoQualitySetting.Highest -> Int.MAX_VALUE
        VideoQualitySetting.Uhd -> 4
        VideoQualitySetting.Fhd -> 3
        VideoQualitySetting.Hd -> 2
        VideoQualitySetting.Sd -> 1
    }

    /** Gewünschte Qualität, sonst die beste unterstützte darunter; ohne Angaben der Kamera bleibt es bei „Höchste“. */
    fun effectiveVideoQuality(wanted: VideoQualitySetting, caps: CameraCaps): VideoQualitySetting {
        if (wanted == VideoQualitySetting.Highest || caps.videoQualities.isEmpty()) return VideoQualitySetting.Highest
        if (wanted in caps.videoQualities) return wanted
        return caps.videoQualities.filter { rank(it) <= rank(wanted) }.maxByOrNull { rank(it) }
            ?: VideoQualitySetting.Highest
    }

    fun effectiveFrameRate(wanted: FrameRateSetting, caps: CameraCaps): FrameRateSetting {
        val fps = wanted.fps ?: return FrameRateSetting.Auto
        return if (fps in caps.frameRates) wanted else FrameRateSetting.Auto
    }

    /** Blitzmodi, die zur Auswahl stehen. Bei manueller Belichtung gibt es keinen Blitz. */
    fun flashChoices(caps: CameraCaps, manualExposure: Boolean): List<FlashSetting> =
        if (!caps.hasFlash || manualExposure) {
            listOf(FlashSetting.Off)
        } else {
            listOf(FlashSetting.Off, FlashSetting.Auto, FlashSetting.On)
        }

    fun effectiveFlash(wanted: FlashSetting, caps: CameraCaps, manualExposure: Boolean): FlashSetting =
        if (wanted in flashChoices(caps, manualExposure)) wanted else FlashSetting.Off

    /** Der Eintrag nach [current] in [options]; am Ende wieder der erste. Ist [current] nicht enthalten, der erste. */
    fun <T> next(options: List<T>, current: T): T {
        if (options.isEmpty()) return current
        val index = options.indexOf(current)
        return options[(index + 1).mod(options.size)]
    }

    fun clampZoom(ratio: Float, caps: CameraCaps): Float = ratio.coerceIn(caps.zoomMin, caps.zoomMax)

    /** Neuer Zoom nach einer Zwei-Finger-Geste mit dem Faktor [scale]. */
    fun pinchZoom(current: Float, scale: Float, caps: CameraCaps): Float {
        if (!scale.isFinite() || scale <= 0f) return clampZoom(current, caps)
        return clampZoom(current * scale, caps)
    }

    /**
     * Schnellwahl für den Zoom: die kleinste Stufe unter 1× (z. B. 0,5× oder 0,6× einer Ultraweitwinkel-Linse),
     * dann 1×, 2×, 3×, 5×, 10×, soweit sie im Bereich liegen. Leer, wenn die Kamera nicht zoomt.
     */
    fun zoomPresets(caps: CameraCaps): List<Float> {
        if (!caps.canZoom) return emptyList()
        val presets = ArrayList<Float>()
        if (caps.zoomMin < 0.95f) presets += (caps.zoomMin * 10f).roundToInt() / 10f
        for (candidate in listOf(1f, 2f, 3f, 5f, 10f)) {
            if (candidate >= caps.zoomMin - 0.01f && candidate <= caps.zoomMax + 0.01f) presets += candidate
        }
        return presets.distinct()
    }

    /**
     * Drehung der Aufnahme (Konstanten von `Surface`: 0 = `ROTATION_0`, 1 = `ROTATION_90`, 2 = `ROTATION_180`,
     * 3 = `ROTATION_270`) zur Geräteneigung in Grad. `null`, wenn die Neigung unbekannt ist (−1) oder ungültig.
     */
    fun surfaceRotation(orientationDegrees: Int): Int? = when (orientationDegrees) {
        in 45 until 135 -> 3
        in 135 until 225 -> 2
        in 225 until 315 -> 1
        in 0 until 45, in 315..359 -> 0
        else -> null
    }

    fun clampEv(index: Int, caps: CameraCaps): Int = if (caps.hasExposureCompensation) index.coerceIn(caps.evMin, caps.evMax) else 0

    /** Text zur Belichtungskorrektur, z. B. „+1.0 EV“. */
    fun evText(index: Int, step: Float): String =
        if (index == 0 || step <= 0f) "0 EV" else String.format(Locale.ROOT, "%+.1f EV", index * step)
}
