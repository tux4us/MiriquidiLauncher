// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.camera

/*
 * Reine Datenmodelle der Kamera-Scope, frei von Android-Klassen und damit auf der JVM testbar.
 * `Setting`-Aufzählungen sind die Wünsche des Nutzers; was das Gerät davon tatsächlich kann, steht in
 * [CameraCaps], und [CameraRules] verbindet beides.
 */

enum class CaptureMode { Photo, Video }

enum class FlashSetting { Off, Auto, On }

enum class RatioSetting { R4x3, R16x9 }

enum class TimerSetting(val seconds: Int) { Off(0), S3(3), S10(10) }

enum class GridSetting { Off, Thirds }

/** Ausgabeformat eines Fotos. [UltraHdr] ist ein JPEG mit Gain-Map, [Raw] eine DNG-Datei. */
enum class PhotoFormat { Jpeg, UltraHdr, Raw }

/** Hersteller-Erweiterung (CameraX Extensions); steht nur auf Geräten mit passender Herstellerbibliothek zur Verfügung. */
enum class ExtensionSetting { None, Auto, Hdr, Night, Bokeh, FaceRetouch }

/** [Speed] verkürzt die Auslöseverzögerung, [Quality] priorisiert die Bildqualität. */
enum class PriorityMode { Speed, Quality }

enum class JpegQuality(val percent: Int) { Standard(90), High(95), Maximum(100) }

enum class VideoQualitySetting { Highest, Uhd, Fhd, Hd, Sd }

/** Bildrate des Videos; [Auto] überlässt die Wahl dem System. */
enum class FrameRateSetting(val fps: Int?) { Auto(null), Fps30(30), Fps60(60) }

enum class WhiteBalanceSetting { Auto, Incandescent, Fluorescent, Daylight, Cloudy, Shade }

/** Dauerhaft gespeicherte Einstellungen der Kamera-Scope. */
data class CameraSettings(
    /** Kennung der zuletzt gewählten Kamera; `null` = Standard (erste Rückkamera). */
    val cameraId: String? = null,
    val flash: FlashSetting = FlashSetting.Off,
    val ratio: RatioSetting = RatioSetting.R4x3,
    val timer: TimerSetting = TimerSetting.Off,
    val grid: GridSetting = GridSetting.Off,
    val photoFormat: PhotoFormat = PhotoFormat.Jpeg,
    val extension: ExtensionSetting = ExtensionSetting.None,
    val priority: PriorityMode = PriorityMode.Quality,
    val jpegQuality: JpegQuality = JpegQuality.High,
    val videoQuality: VideoQualitySetting = VideoQualitySetting.Highest,
    val frameRate: FrameRateSetting = FrameRateSetting.Auto,
    val stabilization: Boolean = true,
    val audio: Boolean = true,
    val mirrorFront: Boolean = true,
    /** Adresse der zuletzt aufgenommenen Datei (MediaStore), für die Miniatur. */
    val lastUri: String? = null,
    val lastIsVideo: Boolean = false,
)

/**
 * Grenzen der manuellen Steuerung einer Kamera.
 *
 * @property minFocusDiopters größte Naheinstellung in Dioptrien (1 / Meter); 0 = Festfokus, keine manuelle Schärfe
 */
data class ManualCaps(
    val isoMin: Int,
    val isoMax: Int,
    val exposureMinNs: Long,
    val exposureMaxNs: Long,
    val minFocusDiopters: Float,
) {
    val hasManualFocus: Boolean get() = minFocusDiopters > 0f
}

/** Fähigkeiten der gewählten Kamera, soweit die App sie nutzt. */
data class CameraCaps(
    val hasFlash: Boolean = false,
    val zoomMin: Float = 1f,
    val zoomMax: Float = 1f,
    val evMin: Int = 0,
    val evMax: Int = 0,
    /** Schrittweite der Belichtungskorrektur in EV je Indexschritt. */
    val evStep: Float = 0f,
    val photoFormats: Set<PhotoFormat> = setOf(PhotoFormat.Jpeg),
    /** Verfügbare Erweiterungen (ohne [ExtensionSetting.None]). */
    val extensions: Set<ExtensionSetting> = emptySet(),
    /** Unterstützte Videoqualitäten, beste zuerst (ohne [VideoQualitySetting.Highest]). */
    val videoQualities: List<VideoQualitySetting> = emptyList(),
    val frameRates: Set<Int> = emptySet(),
    val stabilization: Boolean = false,
    val manual: ManualCaps? = null,
    val whiteBalances: Set<WhiteBalanceSetting> = setOf(WhiteBalanceSetting.Auto),
) {
    val canZoom: Boolean get() = zoomMax > zoomMin + 0.01f
    val hasExposureCompensation: Boolean get() = evMax > evMin && evStep > 0f
}

/** Welche Kamera eines Geräts gemeint ist. */
enum class LensFacing { Back, Front, External }

/** Eine am Gerät verfügbare Kamera. [ordinal] zählt die Kameras gleicher Blickrichtung ab 1. */
data class CameraChoice(val id: String, val facing: LensFacing, val ordinal: Int)

/** Vom Nutzer gesetzte manuelle Werte; nur im Arbeitsspeicher, gelten für die laufende Sitzung. */
data class ManualState(
    val exposureEnabled: Boolean = false,
    val iso: Int = 100,
    val exposureNs: Long = 8_000_000L,
    val focusEnabled: Boolean = false,
    /** 0 = unendlich, 1 = Naheinstellung. */
    val focusFraction: Float = 0f,
    val whiteBalance: WhiteBalanceSetting = WhiteBalanceSetting.Auto,
) {
    /** `true`, sobald irgendein Wert die Automatik ersetzt. */
    val active: Boolean get() = exposureEnabled || focusEnabled || whiteBalance != WhiteBalanceSetting.Auto
}

/**
 * Umstände, die ein neues Binden der Kamera (Vorschau und Aufnahmefunktionen) erfordern. Ändert sich nur etwas
 * anderes (Blitz, Zoom, Belichtung, manuelle Werte, Timer, Gitter), wird live angewendet.
 */
data class BindRequest(
    val mode: CaptureMode,
    val cameraId: String?,
    val ratio: RatioSetting,
    val photoFormat: PhotoFormat,
    val extension: ExtensionSetting,
    val priority: PriorityMode,
    val jpegQuality: JpegQuality,
    val videoQuality: VideoQualitySetting,
    val frameRate: FrameRateSetting,
    val stabilization: Boolean,
    val mirrorFront: Boolean,
    /** `true`, wenn manuelle Werte die Automatik ersetzen (schließt Erweiterungen aus). */
    val manualActive: Boolean,
)

/** Zustand einer Videoaufnahme. */
sealed interface RecordingState {
    data object Idle : RecordingState
    data class Active(val elapsedNanos: Long, val paused: Boolean) : RecordingState
}
