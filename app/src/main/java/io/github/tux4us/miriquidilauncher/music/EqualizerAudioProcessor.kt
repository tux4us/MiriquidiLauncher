// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.music

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Audio-Prozessor des Players: führt [EqChain] auf dem Ton aus (16-Bit-PCM und Float-PCM, ein bis acht Kanäle). Andere
 * Formate bleiben unverändert (Prozessor inaktiv). Einstellungen lassen sich jederzeit aus jedem Thread setzen; sie wirken
 * ab dem nächsten Puffer. Die Filter laufen in der Software des Players und sind damit unabhängig von Geräte-Effekten.
 */
@OptIn(UnstableApi::class)
class EqualizerAudioProcessor : BaseAudioProcessor() {
    @Volatile private var settings = EqSettings()
    @Volatile private var version = 0
    private var appliedVersion = -1
    private var chain: EqChain? = null
    private var samples = FloatArray(0)

    fun update(newSettings: EqSettings) {
        settings = newSettings.normalized()
        version++
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val supported = (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT || inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT) &&
            inputAudioFormat.channelCount in 1..MAX_CHANNELS && inputAudioFormat.sampleRate > 0
        return if (supported) inputAudioFormat else AudioProcessor.AudioFormat.NOT_SET
    }

    override fun onFlush() {
        chain = if (isActive) EqChain(inputAudioFormat.sampleRate, inputAudioFormat.channelCount) else null
        appliedVersion = -1
    }

    override fun onReset() {
        chain = null
        samples = FloatArray(0)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        // Media3 liefert Puffer in der Byte-Reihenfolge des Geräts; für andere Reihenfolgen wird eine Sicht darauf gelesen.
        val input = if (inputBuffer.order() == ByteOrder.nativeOrder()) {
            inputBuffer
        } else {
            inputBuffer.duplicate().order(ByteOrder.nativeOrder()).also { inputBuffer.position(inputBuffer.limit()) }
        }
        val output = replaceOutputBuffer(size)
        val eq = chain
        if (eq != null && appliedVersion != version) {
            val current = version
            eq.configure(settings)
            appliedVersion = current
        }
        if (eq == null || eq.bypass) {
            output.put(input)
            output.flip()
            return
        }
        val channels = inputAudioFormat.channelCount
        val isFloat = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        val bytesPerSample = if (isFloat) 4 else 2
        val frames = size / (bytesPerSample * channels)
        val count = frames * channels
        if (samples.size < count) samples = FloatArray(count)
        if (isFloat) {
            for (i in 0 until count) samples[i] = input.float
        } else {
            for (i in 0 until count) samples[i] = EqMath.toFloat(input.short)
        }
        eq.process(samples, frames)
        if (isFloat) {
            for (i in 0 until count) output.putFloat(samples[i])
        } else {
            for (i in 0 until count) output.putShort(EqMath.toShort(samples[i]))
        }
        output.put(input) // unvollständiges Vollbild am Ende (kommt praktisch nicht vor) unverändert durchreichen
        output.flip()
    }

    private companion object {
        const val MAX_CHANNELS = 8
    }
}

/**
 * Gemeinsamer Zustand zwischen Oberfläche und Wiedergabedienst, die im selben Prozess laufen: aktueller Equalizer und
 * Erlaubnis für unverschlüsselte Streams. Der Dienst liest beides beim Start auch aus dem Speicher.
 */
@OptIn(UnstableApi::class)
object MusicRuntime {
    @Volatile var eq: EqSettings = EqSettings()
        private set

    /** `true`, wenn HTTP-Streams erlaubt sind (Schalter in der Radio-Seite, standardmäßig aus). */
    @Volatile var allowHttp: Boolean = false
        private set

    @Volatile private var processor: EqualizerAudioProcessor? = null

    fun attach(target: EqualizerAudioProcessor) {
        processor = target
        target.update(eq)
    }

    fun detach(target: EqualizerAudioProcessor) {
        if (processor === target) processor = null
    }

    fun setEq(settings: EqSettings) {
        eq = settings
        processor?.update(settings)
    }

    fun setAllowHttp(allowed: Boolean) {
        allowHttp = allowed
    }
}
