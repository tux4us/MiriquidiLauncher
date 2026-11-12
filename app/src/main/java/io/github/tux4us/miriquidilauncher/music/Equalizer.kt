// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.music

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Einstellungen des Equalizers: zehn Oktavbänder ([EqMath.BANDS_HZ]) in dB, Vorverstärkung, Bassanhebung (Low-Shelf bei
 * [EqMath.BASS_HZ]) und Übersteuerungsschutz. [preset] ist die Kennung des gewählten Presets oder `null` bei eigenen Werten.
 */
data class EqSettings(
    val enabled: Boolean = false,
    val bandsDb: List<Float> = List(EqMath.BAND_COUNT) { 0f },
    val preampDb: Float = 0f,
    val bassDb: Float = 0f,
    val protect: Boolean = true,
    val preset: String? = "flat",
) {
    /** Werte in den zulässigen Bereich bringen und auf zehn Bänder auffüllen bzw. kürzen. */
    fun normalized(): EqSettings = copy(
        bandsDb = List(EqMath.BAND_COUNT) { (bandsDb.getOrNull(it) ?: 0f).coerceIn(EqMath.MIN_DB, EqMath.MAX_DB) },
        preampDb = preampDb.coerceIn(EqMath.MIN_DB, EqMath.MAX_DB),
        bassDb = bassDb.coerceIn(0f, EqMath.MAX_DB),
    )

    /** Setzt ein Band; die Einstellung gilt danach als eigene (kein Preset). */
    fun withBand(index: Int, db: Float): EqSettings {
        if (index !in 0 until EqMath.BAND_COUNT) return this
        val bands = normalized().bandsDb.toMutableList()
        bands[index] = db.coerceIn(EqMath.MIN_DB, EqMath.MAX_DB)
        return copy(bandsDb = bands, preset = null)
    }

    fun withBass(db: Float): EqSettings = copy(bassDb = db.coerceIn(0f, EqMath.MAX_DB), preset = null)

    fun withPreamp(db: Float): EqSettings = copy(preampDb = db.coerceIn(EqMath.MIN_DB, EqMath.MAX_DB))

    /** Alle Bänder, Bass und Vorverstärkung auf 0 dB; die Einschaltung bleibt unverändert. */
    fun reset(): EqSettings = copy(bandsDb = List(EqMath.BAND_COUNT) { 0f }, preampDb = 0f, bassDb = 0f, preset = "flat")

    /** `true`, wenn der Equalizer den Ton nicht verändert. */
    val isNeutral: Boolean
        get() = !enabled || (bandsDb.all { abs(it) < EqMath.NEUTRAL_DB } && abs(preampDb) < EqMath.NEUTRAL_DB && bassDb < EqMath.NEUTRAL_DB)
}

/** Ein Preset: Bandwerte in dB und Bassanhebung. Die Namen stehen in den Ressourcen (`music_preset_<id>`). */
data class EqPreset(val id: String, val bandsDb: List<Float>, val bassDb: Float = 0f)

object EqPresets {
    // Bänder: 31, 62, 125, 250, 500 Hz, 1, 2, 4, 8, 16 kHz. Richtwerte als Ausgangspunkt, nicht gemessen.
    val all: List<EqPreset> = listOf(
        EqPreset("flat", listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)),
        EqPreset("bass", listOf(6f, 5f, 4f, 2f, 0f, 0f, 0f, 0f, 0f, 0f)),
        EqPreset("treble", listOf(0f, 0f, 0f, 0f, 0f, 1f, 2f, 4f, 5f, 6f)),
        EqPreset("rock", listOf(4f, 3f, 2f, -1f, -2f, -1f, 1f, 3f, 4f, 5f)),
        EqPreset("pop", listOf(-1f, 1f, 3f, 4f, 3f, 0f, -1f, -1f, 1f, 2f)),
        EqPreset("jazz", listOf(3f, 2f, 1f, 2f, -2f, -2f, 0f, 1f, 2f, 3f)),
        EqPreset("classical", listOf(3f, 2f, 1f, 0f, 0f, 0f, -1f, -1f, 2f, 3f)),
        EqPreset("vocal", listOf(-3f, -2f, -1f, 1f, 3f, 4f, 3f, 2f, 0f, -1f)),
        EqPreset("electronic", listOf(5f, 4f, 1f, 0f, -2f, 2f, 1f, 1f, 4f, 5f)),
        EqPreset("acoustic", listOf(4f, 3f, 2f, 1f, 2f, 1f, 2f, 3f, 3f, 2f)),
        EqPreset("loudness", listOf(5f, 3f, 0f, 0f, -1f, 0f, 0f, 1f, 3f, 5f)),
    )

    fun byId(id: String?): EqPreset? = all.firstOrNull { it.id == id }

    /** Wendet ein Preset an und schaltet den Equalizer ein; die Vorverstärkung wird zurückgesetzt. */
    fun apply(settings: EqSettings, preset: EqPreset): EqSettings = settings.copy(
        enabled = true,
        bandsDb = preset.bandsDb,
        bassDb = preset.bassDb,
        preampDb = 0f,
        preset = preset.id,
    )
}

/** Koeffizienten eines Biquad-Filters (normiert, `a0 = 1`). */
data class Biquad(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {
    /** Betragsquadrat des Frequenzgangs bei der normierten Kreisfrequenz [w]. */
    fun magnitudeSquared(w: Double): Double {
        val c1 = cos(w)
        val c2 = cos(2 * w)
        val num = b0 * b0 + b1 * b1 + b2 * b2 + 2 * (b0 * b1 + b1 * b2) * c1 + 2 * b0 * b2 * c2
        val den = 1 + a1 * a1 + a2 * a2 + 2 * (a1 + a1 * a2) * c1 + 2 * a2 * c2
        return num / den
    }

    companion object {
        val IDENTITY = Biquad(1.0, 0.0, 0.0, 0.0, 0.0)
    }
}

/** Filterentwurf und Frequenzgang nach den Formeln des „Audio EQ Cookbook“ (RBJ) und die Schutzfunktionen. */
object EqMath {
    const val BAND_COUNT = 10
    const val MIN_DB = -12f
    const val MAX_DB = 12f

    /** Unterhalb dieser Verstärkung (dB) gilt ein Regler als neutral. */
    const val NEUTRAL_DB = 0.05f

    val BANDS_HZ = doubleArrayOf(31.25, 62.5, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0)
    const val BASS_HZ = 100.0

    /** Güte der Bandfilter: etwa eine Oktave Bandbreite, damit benachbarte Regler sich sanft überlagern. */
    const val Q = 1.41

    /** Bänder oberhalb dieses Anteils der Abtastrate entfallen (Filter würden dort instabil bzw. wirkungslos). */
    private const val MAX_BAND_FRACTION = 0.45

    /** Ab dieser Aussteuerung beginnt der weiche Begrenzer (≈ −1 dBFS). */
    const val KNEE = 0.89f

    fun peaking(sampleRate: Int, frequency: Double, gainDb: Double, q: Double = Q): Biquad {
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2 * PI * frequency / sampleRate
        val alpha = sin(w0) / (2 * q)
        val cosW0 = cos(w0)
        val a0 = 1 + alpha / a
        return Biquad(
            b0 = (1 + alpha * a) / a0,
            b1 = (-2 * cosW0) / a0,
            b2 = (1 - alpha * a) / a0,
            a1 = (-2 * cosW0) / a0,
            a2 = (1 - alpha / a) / a0,
        )
    }

    fun lowShelf(sampleRate: Int, frequency: Double, gainDb: Double, slope: Double = 1.0): Biquad {
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2 * PI * frequency / sampleRate
        val cosW0 = cos(w0)
        val alpha = sin(w0) / 2 * sqrt((a + 1 / a) * (1 / slope - 1) + 2)
        val beta = 2 * sqrt(a) * alpha
        val a0 = (a + 1) + (a - 1) * cosW0 + beta
        return Biquad(
            b0 = a * ((a + 1) - (a - 1) * cosW0 + beta) / a0,
            b1 = 2 * a * ((a - 1) - (a + 1) * cosW0) / a0,
            b2 = a * ((a + 1) - (a - 1) * cosW0 - beta) / a0,
            a1 = -2 * ((a - 1) + (a + 1) * cosW0) / a0,
            a2 = ((a + 1) + (a - 1) * cosW0 - beta) / a0,
        )
    }

    /**
     * Die elf Filterstufen (zehn Bänder, dann Bass) für [sampleRate]. Neutrale oder wegen der Abtastrate nicht darstellbare
     * Stufen sind [Biquad.IDENTITY]; die Anzahl ändert sich nie, damit Filterzustände beim Verstellen erhalten bleiben.
     */
    fun sections(settings: EqSettings, sampleRate: Int): List<Biquad> {
        val s = settings.normalized()
        val out = ArrayList<Biquad>(BAND_COUNT + 1)
        for (i in 0 until BAND_COUNT) {
            val gain = s.bandsDb[i]
            out += if (abs(gain) < NEUTRAL_DB || BANDS_HZ[i] >= sampleRate * MAX_BAND_FRACTION) {
                Biquad.IDENTITY
            } else {
                peaking(sampleRate, BANDS_HZ[i], gain.toDouble())
            }
        }
        out += if (s.bassDb < NEUTRAL_DB) Biquad.IDENTITY else lowShelf(sampleRate, BASS_HZ, s.bassDb.toDouble())
        return out
    }

    /** Frequenzgang aller Bänder und des Basses in dB bei [frequency] (ohne Vorverstärkung und Schutz). */
    fun responseDb(settings: EqSettings, frequency: Double, sampleRate: Int = 48_000): Double {
        val w = 2 * PI * frequency / sampleRate
        var magnitudeSquared = 1.0
        for (section in sections(settings, sampleRate)) magnitudeSquared *= section.magnitudeSquared(w)
        return 10 * log10(magnitudeSquared)
    }

    /** Größter Wert des Frequenzgangs zwischen 20 Hz und 20 kHz in dB (mindestens 0 bei neutraler Einstellung). */
    fun peakDb(settings: EqSettings, sampleRate: Int = 48_000): Double {
        var peak = 0.0
        val steps = 96
        for (i in 0..steps) {
            val f = 20.0 * (1000.0).pow(i.toDouble() / steps) // 20 Hz … 20 kHz
            if (f >= sampleRate / 2.0) break
            peak = maxOf(peak, responseDb(settings, f, sampleRate))
        }
        return peak
    }

    /**
     * Gesamtverstärkung in dB: Vorverstärkung, bei eingeschaltetem Schutz zusätzlich die Absenkung um den höchsten Anstieg
     * des Frequenzgangs, damit eine Anhebung den Pegel nicht über 0 dBFS treibt.
     */
    fun totalGainDb(settings: EqSettings, sampleRate: Int = 48_000): Double {
        val s = settings.normalized()
        val protection = if (s.protect) -maxOf(0.0, peakDb(s, sampleRate)) else 0.0
        return s.preampDb + protection
    }

    fun dbToLinear(db: Double): Double = 10.0.pow(db / 20.0)

    /** Weicher Begrenzer: unterhalb von [KNEE] unverändert, darüber stetig gegen ±1 verlaufend. */
    fun softClip(x: Float): Float {
        val a = abs(x)
        if (a <= KNEE) return x
        val shaped = KNEE + (1f - KNEE) * tanh((a - KNEE) / (1f - KNEE))
        return if (x < 0f) -shaped else shaped
    }

    fun toFloat(sample: Short): Float = sample / 32768f

    fun toShort(x: Float): Short = (x * 32768f).roundToInt().coerceIn(-32768, 32767).toShort()
}

/**
 * Filterkette für einen Stream mit [sampleRate] und [channels] (verschachtelte Abtastwerte). Reine Rechenlogik ohne
 * Android-Klassen; der Wiedergabedienst ruft sie aus dem Audio-Prozessor auf. Nicht threadsicher: [configure] und
 * [process] müssen vom selben Thread kommen.
 */
class EqChain(private val sampleRate: Int, private val channels: Int) {
    private var coefficients: List<Biquad> = List(EqMath.BAND_COUNT + 1) { Biquad.IDENTITY }
    private var used = BooleanArray(EqMath.BAND_COUNT + 1)
    private val z1 = Array(EqMath.BAND_COUNT + 1) { DoubleArray(channels) }
    private val z2 = Array(EqMath.BAND_COUNT + 1) { DoubleArray(channels) }
    private var gain = 1.0

    /** `true`, wenn [process] den Ton unverändert lässt. */
    var bypass = true
        private set

    fun configure(settings: EqSettings) {
        if (settings.isNeutral) {
            bypass = true
            return
        }
        val normalized = settings.normalized()
        coefficients = EqMath.sections(normalized, sampleRate)
        for (i in coefficients.indices) {
            val active = coefficients[i] != Biquad.IDENTITY
            if (!active && used[i]) {
                z1[i].fill(0.0)
                z2[i].fill(0.0)
            }
            used[i] = active
        }
        gain = EqMath.dbToLinear(EqMath.totalGainDb(normalized, sampleRate))
        bypass = false
    }

    /** Verarbeitet [frames] Vollbilder in [buffer] (verschachtelt) an Ort und Stelle. */
    fun process(buffer: FloatArray, frames: Int) {
        if (bypass) return
        for (frame in 0 until frames) {
            for (channel in 0 until channels) {
                val index = frame * channels + channel
                var x = buffer[index].toDouble() * gain
                for (s in coefficients.indices) {
                    if (!used[s]) continue
                    val c = coefficients[s]
                    val y = c.b0 * x + z1[s][channel]
                    z1[s][channel] = c.b1 * x - c.a1 * y + z2[s][channel]
                    z2[s][channel] = c.b2 * x - c.a2 * y
                    x = y
                }
                buffer[index] = EqMath.softClip(x.toFloat())
            }
        }
    }
}
