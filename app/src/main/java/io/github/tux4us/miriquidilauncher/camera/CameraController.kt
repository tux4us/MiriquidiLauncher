// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.camera

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.net.Uri
import android.provider.MediaStore
import android.util.Range
import android.view.OrientationEventListener
import androidx.camera.core.Camera
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.MirrorMode
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.TimeUnit

/** Genauer Zoom- und Belichtungsbereich der gebundenen Kamera, wie CameraX ihn meldet. */
internal data class LiveCaps(
    val zoomMin: Float,
    val zoomMax: Float,
    val evMin: Int,
    val evMax: Int,
    val evStep: Float,
)

internal sealed interface BindResult {
    data class Success(val live: LiveCaps) : BindResult
    data class Failure(val error: Throwable) : BindResult
}

/**
 * Brücke zu CameraX: bindet Vorschau und Aufnahmefunktionen an den Lebenszyklus, nimmt Fotos und Videos auf und
 * wendet Zoom, Blitz, Belichtung und manuelle Werte an. Die Entscheidungen darüber, *was* gebunden wird, trifft
 * [CameraRules]; hier wird nur ausgeführt. Der Controller hält keine Activity, nur den Anwendungskontext.
 *
 * Alle Methoden außer den `suspend`-Funktionen sind für den Hauptthread gedacht.
 */
internal class CameraController(context: Context) {
    private val appContext: Context = context.applicationContext
    private val mainExecutor = ContextCompat.getMainExecutor(appContext)

    private var provider: ProcessCameraProvider? = null
    private var extensions: ExtensionsManager? = null

    private var camera: Camera? = null
    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null

    private var rotation: Int = 0
    private var orientationListener: OrientationEventListener? = null

    // --- Vorbereitung -----------------------------------------------------------------------------------------

    private suspend fun ensureProvider(): ProcessCameraProvider {
        provider?.let { return it }
        val created = ProcessCameraProvider.getInstance(appContext).awaitResult(mainExecutor)
        provider = created
        extensions = runCatching { ExtensionsManager.getInstanceAsync(appContext, created).awaitResult(mainExecutor) }
            .getOrNull()
        return created
    }

    /** Alle Kameras, die CameraX am Gerät bereitstellt (hinten, vorne, extern). */
    suspend fun cameras(): List<CameraChoice> {
        val available = ensureProvider().availableCameraInfos
        val raw = available.mapNotNull { info ->
            val id = runCatching { CameraXCompat.cameraId(info) }.getOrNull() ?: return@mapNotNull null
            val facing = CameraXCompat.characteristic(info, CameraCharacteristics.LENS_FACING) ?: 1
            id to facing
        }
        return CameraChoices.build(raw)
    }

    /** Fähigkeiten einer Kamera. Fehlt sie, kommt ein Eintrag ohne Sonderfunktionen zurück. */
    suspend fun capsFor(id: String): CameraCaps {
        val p = ensureProvider()
        val info = p.availableCameraInfos.firstOrNull { runCatching { CameraXCompat.cameraId(it) }.getOrNull() == id }
            ?: return CameraCaps()
        val (zoomMin, zoomMax) = CameraXCompat.zoomRange(info)
        val (evMin, evMax, evStep) = CameraXCompat.exposureCompensation(info)
        val selector = CameraXCompat.selectorFor(id)
        val available = extensions?.let { manager ->
            EXTENSION_MODES.filter { (_, mode) -> runCatching { manager.isExtensionAvailable(selector, mode) }.getOrDefault(false) }
                .map { it.first }
                .toSet()
        } ?: emptySet()
        return CameraCaps(
            hasFlash = info.hasFlashUnit(),
            zoomMin = zoomMin,
            zoomMax = zoomMax,
            evMin = evMin,
            evMax = evMax,
            evStep = evStep,
            photoFormats = CameraXCompat.supportedPhotoFormats(info),
            extensions = available,
            videoQualities = CameraXCompat.supportedVideoQualities(info),
            frameRates = CameraXCompat.supportedFrameRates(info),
            stabilization = CameraXCompat.stabilizationSupported(info),
            manual = CameraXCompat.manualCaps(info),
            whiteBalances = CameraXCompat.whiteBalances(info),
        )
    }

    // --- Binden -----------------------------------------------------------------------------------------------

    /**
     * Bindet die zur [request] gehörenden Funktionen. Vorher wird das zuvor Gebundene gelöst. Die Anfrage muss schon
     * von [CameraRules.resolve] bereinigt sein.
     */
    suspend fun bind(owner: LifecycleOwner, view: PreviewView, request: BindRequest): BindResult {
        val id = request.cameraId ?: return BindResult.Failure(IllegalStateException("no camera"))
        return try {
            val p = ensureProvider()
            releaseUseCases(p)

            var selector = CameraXCompat.selectorFor(id)
            val mode = EXTENSION_MODES.firstOrNull { it.first == request.extension }?.second
            if (mode != null) {
                val manager = extensions ?: return BindResult.Failure(IllegalStateException("no extensions"))
                selector = manager.getExtensionEnabledCameraSelector(selector, mode)
            }

            // Im Videomodus ist die Vorschau 16:9, wie die Aufnahme.
            val ratio = if (request.mode == CaptureMode.Video) RatioSetting.R16x9 else request.ratio
            val strategy = when (ratio) {
                RatioSetting.R4x3 -> AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
                RatioSetting.R16x9 -> AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
            }
            val resolution = ResolutionSelector.Builder().setAspectRatioStrategy(strategy).build()

            val newPreview = Preview.Builder().setResolutionSelector(resolution).build()
            newPreview.surfaceProvider = view.surfaceProvider

            val bound = if (request.mode == CaptureMode.Photo) {
                val builder = ImageCapture.Builder()
                    .setResolutionSelector(resolution)
                    .setCaptureMode(
                        if (request.priority == PriorityMode.Speed) {
                            ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
                        } else {
                            ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
                        },
                    )
                    .setJpegQuality(request.jpegQuality.percent)
                CameraXCompat.applyPhotoFormat(builder, request.photoFormat)
                val capture = builder.build()
                capture.targetRotation = rotation
                val result = p.bindToLifecycle(owner, selector, newPreview, capture)
                imageCapture = capture
                result
            } else {
                val quality = CameraXCompat.qualityOf(request.videoQuality)
                val qualitySelector = if (request.videoQuality == VideoQualitySetting.Highest) {
                    QualitySelector.from(quality)
                } else {
                    QualitySelector.from(quality, FallbackStrategy.lowerQualityOrHigherThan(quality))
                }
                val recorder = Recorder.Builder().setQualitySelector(qualitySelector).build()
                val builder = VideoCapture.Builder(recorder)
                    .setMirrorMode(if (request.mirrorFront) MirrorMode.MIRROR_MODE_ON_FRONT_ONLY else MirrorMode.MIRROR_MODE_OFF)
                request.frameRate.fps?.let { builder.setTargetFrameRate(Range(it, it)) }
                if (request.stabilization) CameraXCompat.enableStabilization(builder)
                val capture = builder.build()
                capture.targetRotation = rotation
                val result = p.bindToLifecycle(owner, selector, newPreview, capture)
                videoCapture = capture
                result
            }
            preview = newPreview
            camera = bound
            BindResult.Success(liveCaps(bound))
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            releaseUseCases(provider)
            BindResult.Failure(error)
        }
    }

    private fun liveCaps(bound: Camera): LiveCaps {
        val zoom = bound.cameraInfo.zoomState.value
        val exposure = bound.cameraInfo.exposureState
        val range = exposure.exposureCompensationRange
        return LiveCaps(
            zoomMin = zoom?.minZoomRatio ?: 1f,
            zoomMax = zoom?.maxZoomRatio ?: 1f,
            evMin = if (exposure.isExposureCompensationSupported) range.lower else 0,
            evMax = if (exposure.isExposureCompensationSupported) range.upper else 0,
            evStep = if (exposure.isExposureCompensationSupported) exposure.exposureCompensationStep.toFloat() else 0f,
        )
    }

    private fun releaseUseCases(p: ProcessCameraProvider?) {
        val cases = listOfNotNull<UseCase>(preview, imageCapture, videoCapture)
        if (cases.isNotEmpty()) runCatching { p?.unbind(*cases.toTypedArray()) }
        preview = null
        imageCapture = null
        videoCapture = null
        camera = null
    }

    /** Gibt die Kamera frei. Eine laufende Aufnahme wird vorher beendet und gespeichert. */
    fun release() {
        stopRecording()
        releaseUseCases(provider)
    }

    // --- Live-Einstellungen -----------------------------------------------------------------------------------

    fun setZoom(ratio: Float) {
        camera?.cameraControl?.setZoomRatio(ratio)
    }

    fun setExposureIndex(index: Int) {
        camera?.cameraControl?.setExposureCompensationIndex(index)
    }

    /** Foto: Blitzmodus der Aufnahme. Video: [FlashSetting.On] schaltet die Taschenlampe ein. */
    fun setFlash(flash: FlashSetting, video: Boolean) {
        camera?.cameraControl?.enableTorch(video && flash == FlashSetting.On)
        imageCapture?.flashMode = when (flash) {
            FlashSetting.Off -> ImageCapture.FLASH_MODE_OFF
            FlashSetting.Auto -> ImageCapture.FLASH_MODE_AUTO
            FlashSetting.On -> ImageCapture.FLASH_MODE_ON
        }
    }

    /** Setzt die manuellen Werte; `null` gibt die Automatik wieder frei. */
    fun setManual(options: ManualOptions?) {
        val current = camera ?: return
        runCatching { CameraXCompat.applyManual(current, options) }
    }

    fun focusAt(view: PreviewView, x: Float, y: Float) {
        val current = camera ?: return
        val point = view.meteringPointFactory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(point).setAutoCancelDuration(4, TimeUnit.SECONDS).build()
        current.cameraControl.startFocusAndMetering(action)
    }

    // --- Drehung ----------------------------------------------------------------------------------------------

    /** Lässt die Aufnahmen der Geräteneigung folgen, solange die Seite sichtbar ist. */
    fun startOrientationUpdates() {
        if (orientationListener != null) return
        val listener = object : OrientationEventListener(appContext) {
            override fun onOrientationChanged(orientation: Int) {
                val surfaceRotation = CameraRules.surfaceRotation(orientation) ?: return
                if (surfaceRotation == rotation) return
                rotation = surfaceRotation
                imageCapture?.targetRotation = surfaceRotation
                videoCapture?.targetRotation = surfaceRotation
            }
        }
        if (listener.canDetectOrientation()) listener.enable()
        orientationListener = listener
    }

    fun stopOrientationUpdates() {
        orientationListener?.disable()
        orientationListener = null
    }

    // --- Foto -------------------------------------------------------------------------------------------------

    /** `false`, wenn gerade kein Foto aufgenommen werden kann (nicht im Fotomodus gebunden). */
    fun takePhoto(format: PhotoFormat, mirror: Boolean, onResult: (Result<Uri>) -> Unit): Boolean {
        val capture = imageCapture ?: return false
        val metadata = ImageCapture.Metadata().apply { isReversedHorizontal = mirror }
        val options = ImageCapture.OutputFileOptions.Builder(
            appContext.contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            CameraMedia.photoValues(format),
        ).setMetadata(metadata).build()
        capture.takePicture(
            options,
            mainExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val uri = output.savedUri
                    onResult(if (uri != null) Result.success(uri) else Result.failure(IllegalStateException("no uri")))
                }

                override fun onError(exception: ImageCaptureException) {
                    onResult(Result.failure(exception))
                }
            },
        )
        return true
    }

    // --- Video ------------------------------------------------------------------------------------------------

    /** `false`, wenn gerade kein Video aufgenommen werden kann (nicht im Videomodus gebunden oder schon aktiv). */
    @SuppressLint("MissingPermission")
    fun startRecording(withAudio: Boolean, listener: (VideoRecordEvent) -> Unit): Boolean {
        val capture = videoCapture ?: return false
        if (recording != null) return false
        val output = MediaStoreOutputOptions.Builder(appContext.contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(CameraMedia.videoValues())
            .build()
        var pending = capture.output.prepareRecording(appContext, output)
        // Der Aufrufer prüft die Berechtigung RECORD_AUDIO vor `withAudio = true`.
        if (withAudio) pending = pending.withAudioEnabled()
        recording = pending.start(mainExecutor) { event ->
            if (event is VideoRecordEvent.Finalize) recording = null
            listener(event)
        }
        return true
    }

    fun pauseRecording() {
        recording?.pause()
    }

    fun resumeRecording() {
        recording?.resume()
    }

    fun stopRecording() {
        recording?.stop()
    }

    val isRecording: Boolean get() = recording != null

    companion object {
        private val EXTENSION_MODES = listOf(
            ExtensionSetting.Auto to ExtensionMode.AUTO,
            ExtensionSetting.Hdr to ExtensionMode.HDR,
            ExtensionSetting.Night to ExtensionMode.NIGHT,
            ExtensionSetting.Bokeh to ExtensionMode.BOKEH,
            ExtensionSetting.FaceRetouch to ExtensionMode.FACE_RETOUCH,
        )

        /**
         * Ob nach dem Ende einer Aufnahme eine brauchbare Datei vorliegt. Bei einem Fehler wie „Quelle inaktiv“
         * (z. B. App im Hintergrund) oder einem erreichten Limit schreibt CameraX die bisherige Aufnahme trotzdem.
         */
        fun isUsable(event: VideoRecordEvent.Finalize): Boolean {
            if (!event.hasError()) return true
            return event.error == VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE ||
                event.error == VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED ||
                event.error == VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED
        }
    }
}
