// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.camera

import android.app.Application
import android.graphics.Bitmap
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Kurzmeldungen der Kamera-Scope; die Oberfläche ordnet ihnen Texte zu. */
enum class CameraMessage {
    PhotoSaved,
    VideoSaved,
    SaveFailed,
    BindFailed,
    FormatFallback,
    ExtensionFallback,
    NoAudio,
    NoViewer,
}

data class CameraUiState(
    val loaded: Boolean = false,
    /** `true`, sobald die Kameras des Geräts gelesen wurden (auch wenn keine gefunden wurde). */
    val prepared: Boolean = false,
    val settings: CameraSettings = CameraSettings(),
    val mode: CaptureMode = CaptureMode.Photo,
    val choices: List<CameraChoice> = emptyList(),
    val camera: CameraChoice? = null,
    val caps: CameraCaps = CameraCaps(),
    val manual: ManualState = ManualState(),
    val zoom: Float = 1f,
    val evIndex: Int = 0,
    val recording: RecordingState = RecordingState.Idle,
    /** Verbleibende Sekunden des Selbstauslösers, sonst `null`. */
    val countdown: Int? = null,
    /** `true`, solange ein Foto aufgenommen und gespeichert wird. */
    val capturing: Boolean = false,
    val thumbnail: Bitmap? = null,
    val message: CameraMessage? = null,
) {
    val isRecording: Boolean get() = recording is RecordingState.Active

    /** Während dieser Zeit darf weder die Seite gewechselt noch die Bindung geändert werden. */
    val busy: Boolean get() = isRecording || countdown != null || capturing
}

/**
 * Zustand der Kamera-Scope. Was gebunden wird, ergibt sich aus Einstellungen und Fähigkeiten über [CameraRules];
 * die Oberfläche bindet über [bind], sobald sich [bindRequest] ändert. Die Einstellungen werden gespeichert, die
 * manuellen Werte, Zoom und Belichtung gelten nur für die laufende Sitzung.
 */
class CameraViewModel(application: Application) : AndroidViewModel(application) {
    private val store = CameraSettingsStore(application)
    private val controller = CameraController(application)

    private val _state = MutableStateFlow(CameraUiState())
    val state: StateFlow<CameraUiState> = _state.asStateFlow()

    /** Aufnahme, Selbstauslöser oder Speichern läuft; getrennt, damit der Launcher nicht bei jedem Zeittakt neu zeichnet. */
    val busy: StateFlow<Boolean> = _state.map { it.busy }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private var preparing = false
    private var lastBackId: String? = null
    private var countdownJob: Job? = null

    init {
        viewModelScope.launch {
            val saved = store.load()
            _state.update { it.copy(loaded = true, settings = saved) }
            loadThumbnail(saved.lastUri, saved.lastIsVideo)
        }
    }

    // --- Vorbereitung und Binden -----------------------------------------------------------------------------

    /** Liest die Kameras und Fähigkeiten, sobald die Berechtigung vorliegt. Mehrfaches Aufrufen schadet nicht. */
    fun prepare() {
        if (preparing || _state.value.camera != null) return
        preparing = true
        viewModelScope.launch {
            try {
                val saved = store.load()
                val choices = controller.cameras()
                val initial = CameraChoices.initial(choices, saved.cameraId)
                val caps = initial?.let { controller.capsFor(it.id) } ?: CameraCaps()
                if (initial?.facing == LensFacing.Back) lastBackId = initial.id
                _state.update {
                    it.copy(
                        prepared = true,
                        settings = saved,
                        choices = choices,
                        camera = initial,
                        caps = caps,
                        manual = ManualControls.normalize(it.manual, caps.manual),
                    )
                }
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                _state.update { it.copy(prepared = true) }
                postMessage(CameraMessage.BindFailed)
            } finally {
                preparing = false
            }
        }
    }

    /** Die Bindung, die zum aktuellen Zustand gehört; `null`, solange keine Kamera gewählt ist. */
    fun bindRequest(s: CameraUiState): BindRequest? {
        val cam = s.camera ?: return null
        val wanted = BindRequest(
            mode = s.mode,
            cameraId = cam.id,
            ratio = s.settings.ratio,
            photoFormat = s.settings.photoFormat,
            extension = s.settings.extension,
            priority = s.settings.priority,
            jpegQuality = s.settings.jpegQuality,
            videoQuality = s.settings.videoQuality,
            frameRate = s.settings.frameRate,
            stabilization = s.settings.stabilization,
            mirrorFront = s.settings.mirrorFront,
            manualActive = s.manual.active,
        )
        return CameraRules.resolve(wanted, s.caps)
    }

    /** Bindet Vorschau und Aufnahmefunktion; wird von der Oberfläche aufgerufen, solange die Seite sichtbar ist. */
    suspend fun bind(owner: LifecycleOwner, view: PreviewView) {
        val request = bindRequest(_state.value) ?: return
        when (val result = controller.bind(owner, view, request)) {
            is BindResult.Success -> {
                val live = result.live
                _state.update {
                    val merged = if (it.camera?.id == request.cameraId) {
                        it.caps.copy(
                            zoomMin = live.zoomMin,
                            zoomMax = live.zoomMax,
                            evMin = live.evMin,
                            evMax = live.evMax,
                            evStep = live.evStep,
                        )
                    } else {
                        it.caps
                    }
                    it.copy(
                        caps = merged,
                        zoom = CameraRules.clampZoom(it.zoom, merged),
                        evIndex = CameraRules.clampEv(it.evIndex, merged),
                    )
                }
                applyLive()
            }

            is BindResult.Failure -> onBindFailed(request)
        }
    }

    private fun onBindFailed(request: BindRequest) {
        when {
            request.extension != ExtensionSetting.None -> {
                updateSettings { it.copy(extension = ExtensionSetting.None) }
                postMessage(CameraMessage.ExtensionFallback)
            }

            request.photoFormat != PhotoFormat.Jpeg -> {
                updateSettings { it.copy(photoFormat = PhotoFormat.Jpeg) }
                postMessage(CameraMessage.FormatFallback)
            }

            else -> postMessage(CameraMessage.BindFailed)
        }
    }

    /** Wendet Zoom, Belichtung, Blitz und manuelle Werte auf die frisch gebundene Kamera an. */
    private fun applyLive() {
        val s = _state.value
        controller.setZoom(s.zoom)
        controller.setExposureIndex(s.evIndex)
        controller.setFlash(effectiveFlash(s), s.mode == CaptureMode.Video)
        applyManual(s)
    }

    private fun effectiveFlash(s: CameraUiState): FlashSetting =
        CameraRules.effectiveFlash(s.settings.flash, s.caps, s.manual.exposureEnabled)

    private fun applyManual(s: CameraUiState) {
        val manualCaps = s.caps.manual
        val options = when {
            !s.manual.active -> null
            manualCaps != null -> ManualControls.options(s.manual, manualCaps)
            // Kamera ohne manuelle Sensorsteuerung: nur der Weißabgleich wirkt.
            else -> ManualOptions(false, 0, 0L, null, false, 0f, s.manual.whiteBalance)
        }
        controller.setManual(options)
    }

    /** Die Seite wird verlassen oder die Oberfläche verschwindet: Kamera freigeben. */
    fun unbind() {
        countdownJob?.cancel()
        countdownJob = null
        _state.update { it.copy(countdown = null) }
        controller.stopOrientationUpdates()
        controller.release()
    }

    fun startOrientationUpdates() = controller.startOrientationUpdates()

    fun stopOrientationUpdates() = controller.stopOrientationUpdates()

    /** Die App geht in den Hintergrund: laufende Aufnahme beenden, Selbstauslöser abbrechen. */
    fun onHostStopped() {
        countdownJob?.cancel()
        countdownJob = null
        _state.update { it.copy(countdown = null) }
        if (controller.isRecording) controller.stopRecording()
    }

    override fun onCleared() {
        controller.release()
        controller.stopOrientationUpdates()
    }

    // --- Einstellungen ---------------------------------------------------------------------------------------

    fun updateSettings(block: (CameraSettings) -> CameraSettings) {
        val old = _state.value.settings
        val new = block(old)
        if (new == old) return
        _state.update { it.copy(settings = new) }
        viewModelScope.launch { store.save(new) }
    }

    fun setMode(mode: CaptureMode) {
        val s = _state.value
        if (s.busy || s.mode == mode) return
        _state.update { it.copy(mode = mode) }
    }

    fun cycleFlash() {
        val s = _state.value
        val video = s.mode == CaptureMode.Video
        // Im Video gibt es nur die Lampe (aus/an), keinen Blitz „Auto“.
        val choices = CameraRules.flashChoices(s.caps, s.manual.exposureEnabled)
            .filter { !video || it != FlashSetting.Auto }
        val next = CameraRules.next(choices, effectiveFlash(s))
        updateSettings { it.copy(flash = next) }
        controller.setFlash(next, video)
    }

    fun cycleTimer() = updateSettings { it.copy(timer = CameraRules.next(TimerSetting.entries, it.timer)) }

    fun cycleGrid() = updateSettings { it.copy(grid = CameraRules.next(GridSetting.entries, it.grid)) }

    fun toggleRatio() {
        if (_state.value.busy) return
        updateSettings { it.copy(ratio = CameraRules.next(RatioSetting.entries, it.ratio)) }
    }

    fun setZoom(ratio: Float) {
        val s = _state.value
        val clamped = CameraRules.clampZoom(ratio, s.caps)
        if (clamped == s.zoom) return
        _state.update { it.copy(zoom = clamped) }
        controller.setZoom(clamped)
    }

    fun zoomBy(scale: Float) = setZoom(CameraRules.pinchZoom(_state.value.zoom, scale, _state.value.caps))

    fun setEv(index: Int) {
        val s = _state.value
        val clamped = CameraRules.clampEv(index, s.caps)
        if (clamped == s.evIndex) return
        _state.update { it.copy(evIndex = clamped) }
        controller.setExposureIndex(clamped)
    }

    fun setManual(manual: ManualState) {
        val s = _state.value
        val normalized = ManualControls.normalize(manual, s.caps.manual)
        // Ohne Daten der Kamera zur manuellen Steuerung bleibt nur der Weißabgleich übrig.
        val next = if (s.caps.manual == null) normalized.copy(whiteBalance = manual.whiteBalance) else normalized
        if (next == s.manual) return
        // Eine laufende Aufnahme darf nicht durch ein neues Binden unterbrochen werden.
        if (s.isRecording && next.active != s.manual.active) return
        _state.update { it.copy(manual = next) }
        applyManual(_state.value)
        if (next.exposureEnabled != s.manual.exposureEnabled) {
            // Blitz gibt es bei manueller Belichtung nicht.
            controller.setFlash(effectiveFlash(_state.value), _state.value.mode == CaptureMode.Video)
        }
    }

    fun resetManual() = setManual(ManualState())

    // --- Kamerawahl ------------------------------------------------------------------------------------------

    fun flip() {
        val s = _state.value
        if (s.busy) return
        val target = CameraChoices.flipTarget(s.choices, s.camera?.id, lastBackId) ?: return
        selectCamera(target)
    }

    fun selectCamera(choice: CameraChoice) {
        val s = _state.value
        if (s.busy || s.camera?.id == choice.id) return
        viewModelScope.launch {
            val caps = runCatching { controller.capsFor(choice.id) }.getOrElse { CameraCaps() }
            if (choice.facing == LensFacing.Back) lastBackId = choice.id
            _state.update {
                it.copy(
                    camera = choice,
                    caps = caps,
                    zoom = 1f,
                    evIndex = 0,
                    manual = ManualControls.normalize(it.manual, caps.manual),
                )
            }
            updateSettings { it.copy(cameraId = choice.id) }
        }
    }

    fun focusAt(view: PreviewView, x: Float, y: Float) = controller.focusAt(view, x, y)

    // --- Aufnahme --------------------------------------------------------------------------------------------

    /**
     * Auslöser. Foto: mit Selbstauslöser erst nach dem Countdown, ein zweiter Druck bricht ihn ab. Video: startet bzw.
     * beendet die Aufnahme. [audioPermitted] sagt, ob RECORD_AUDIO erteilt ist.
     */
    fun shutter(audioPermitted: Boolean) {
        val s = _state.value
        if (s.mode == CaptureMode.Video) {
            if (controller.isRecording) controller.stopRecording() else startRecording(s, audioPermitted)
            return
        }
        if (s.countdown != null) {
            countdownJob?.cancel()
            countdownJob = null
            _state.update { it.copy(countdown = null) }
            return
        }
        if (s.capturing) return
        val seconds = s.settings.timer.seconds
        if (seconds <= 0) {
            takePhoto()
            return
        }
        countdownJob = viewModelScope.launch {
            for (remaining in seconds downTo 1) {
                _state.update { it.copy(countdown = remaining) }
                delay(1000)
            }
            _state.update { it.copy(countdown = null) }
            takePhoto()
        }
    }

    private fun takePhoto() {
        val s = _state.value
        val request = bindRequest(s) ?: return
        val front = s.camera?.facing == LensFacing.Front
        _state.update { it.copy(capturing = true) }
        val started = controller.takePhoto(request.photoFormat, mirror = front && s.settings.mirrorFront) { result ->
            _state.update { it.copy(capturing = false) }
            result.onSuccess { uri ->
                rememberLast(uri.toString(), isVideo = false)
                postMessage(CameraMessage.PhotoSaved)
            }.onFailure { postMessage(CameraMessage.SaveFailed) }
        }
        if (!started) {
            _state.update { it.copy(capturing = false) }
            postMessage(CameraMessage.BindFailed)
        }
    }

    private fun startRecording(s: CameraUiState, audioPermitted: Boolean) {
        val withAudio = s.settings.audio && audioPermitted
        if (s.settings.audio && !audioPermitted) postMessage(CameraMessage.NoAudio)
        val started = controller.startRecording(withAudio) { event -> onRecordEvent(event) }
        if (!started) postMessage(CameraMessage.BindFailed)
    }

    fun togglePause() {
        val active = _state.value.recording as? RecordingState.Active ?: return
        if (active.paused) controller.resumeRecording() else controller.pauseRecording()
    }

    private fun onRecordEvent(event: VideoRecordEvent) {
        when (event) {
            is VideoRecordEvent.Start -> _state.update { it.copy(recording = RecordingState.Active(0L, false)) }
            is VideoRecordEvent.Pause -> _state.update {
                it.copy(recording = RecordingState.Active(event.recordingStats.recordedDurationNanos, true))
            }
            is VideoRecordEvent.Resume -> _state.update {
                it.copy(recording = RecordingState.Active(event.recordingStats.recordedDurationNanos, false))
            }
            is VideoRecordEvent.Finalize -> {
                _state.update { it.copy(recording = RecordingState.Idle) }
                if (CameraController.isUsable(event)) {
                    rememberLast(event.outputResults.outputUri.toString(), isVideo = true)
                    postMessage(CameraMessage.VideoSaved)
                } else {
                    postMessage(CameraMessage.SaveFailed)
                }
            }
            else -> {
                val paused = (_state.value.recording as? RecordingState.Active)?.paused ?: false
                _state.update {
                    it.copy(recording = RecordingState.Active(event.recordingStats.recordedDurationNanos, paused))
                }
            }
        }
    }

    // --- Letzte Aufnahme und Meldungen ----------------------------------------------------------------------

    private fun rememberLast(uri: String, isVideo: Boolean) {
        updateSettings { it.copy(lastUri = uri, lastIsVideo = isVideo) }
        loadThumbnail(uri, isVideo)
    }

    private fun loadThumbnail(uri: String?, isVideo: Boolean) {
        if (uri == null) {
            _state.update { it.copy(thumbnail = null) }
            return
        }
        viewModelScope.launch {
            val bitmap = withContext(Dispatchers.IO) { CameraMedia.thumbnail(getApplication(), uri, isVideo) }
            _state.update { it.copy(thumbnail = bitmap) }
        }
    }

    /** Öffnet die letzte Aufnahme in der System-Galerie. */
    fun openLast() {
        val last = _state.value.settings.lastUri ?: return
        if (!CameraMedia.open(getApplication(), last)) postMessage(CameraMessage.NoViewer)
    }

    private fun postMessage(message: CameraMessage) {
        _state.update { it.copy(message = message) }
    }

    fun clearMessage() {
        _state.update { it.copy(message = null) }
    }
}
