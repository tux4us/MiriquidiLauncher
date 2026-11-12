// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.music

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EqualizerTest {
    private val rate = 48_000

    private fun sine(frequency: Double, amplitude: Float, frames: Int, channels: Int = 1): FloatArray {
        val out = FloatArray(frames * channels)
        for (i in 0 until frames) {
            val v = (amplitude * sin(2 * PI * frequency * i / rate)).toFloat()
            for (c in 0 until channels) out[i * channels + c] = v
        }
        return out
    }

    /** Effektivwert der zweiten Hälfte (eingeschwungen), Kanal 0. */
    private fun rms(buffer: FloatArray, channels: Int = 1): Double {
        val frames = buffer.size / channels
        var sum = 0.0
        var n = 0
        for (i in frames / 2 until frames) {
            val v = buffer[i * channels].toDouble()
            sum += v * v
            n++
        }
        return sqrt(sum / n)
    }

    private fun gainDb(settings: EqSettings, frequency: Double, channels: Int = 1): Double {
        val chain = EqChain(rate, channels)
        chain.configure(settings)
        val input = sine(frequency, 0.05f, rate / 2, channels)
        val reference = rms(input, channels)
        val output = input.copyOf()
        chain.process(output, output.size / channels)
        return 20 * log10(rms(output, channels) / reference)
    }

    @Test
    fun neutralSettingsLeaveTheSignalUntouched() {
        val chain = EqChain(rate, 2)
        chain.configure(EqSettings(enabled = true))
        assertTrue(chain.bypass)
        val input = sine(440.0, 0.5f, 1000, 2)
        val output = input.copyOf()
        chain.process(output, 1000)
        assertTrue(input.contentEquals(output))
    }

    @Test
    fun disabledEqualizerIsNeutralEvenWithBands() {
        val s = EqSettings(enabled = false, bandsDb = List(10) { 6f })
        assertTrue(s.isNeutral)
    }

    @Test
    fun boostAndCutAtABandFrequencyMatchTheSetGain() {
        val boost = EqSettings(enabled = true, protect = false).withBand(5, 6f)
        assertEquals(6.0, gainDb(boost, 1000.0), 0.15)
        val cut = EqSettings(enabled = true, protect = false).withBand(5, -6f)
        assertEquals(-6.0, gainDb(cut, 1000.0), 0.15)
    }

    @Test
    fun farAwayFrequenciesStayNearlyUnchanged() {
        val boost = EqSettings(enabled = true, protect = false).withBand(5, 12f)
        assertEquals(0.0, gainDb(boost, 60.0), 0.6)
        assertEquals(0.0, gainDb(boost, 12000.0), 0.6)
    }

    @Test
    fun responseFunctionAgreesWithMeasuredGain() {
        val s = EqSettings(enabled = true, protect = false).withBand(3, 8f).withBand(8, -5f)
        for (f in listOf(100.0, 250.0, 700.0, 3000.0, 8000.0)) {
            assertEquals("bei $f Hz", EqMath.responseDb(s, f, rate), gainDb(s, f), 0.2)
        }
    }

    @Test
    fun bassShelfRaisesLowFrequenciesOnly() {
        val s = EqSettings(enabled = true, protect = false).withBass(9f)
        assertEquals(9.0, EqMath.responseDb(s, 40.0, rate), 1.0)
        assertEquals(0.0, EqMath.responseDb(s, 5000.0, rate), 0.3)
        assertEquals(9.0, gainDb(s, 40.0), 1.0)
    }

    @Test
    fun protectionKeepsThePeakResponseAtOrBelowZeroDb() {
        val s = EqSettings(enabled = true, bandsDb = List(10) { 12f }, protect = true)
        val total = EqMath.totalGainDb(s, rate)
        assertTrue("Absenkung erwartet, war $total", total < -10.0)
        var peak = -100.0
        for (i in 0..200) {
            val f = 20.0 * Math.pow(1000.0, i / 200.0)
            peak = maxOf(peak, EqMath.responseDb(s, f, rate) + total)
        }
        assertTrue("Spitze $peak dB", peak <= 0.05)
        val unprotected = s.copy(protect = false)
        assertEquals(0.0, EqMath.totalGainDb(unprotected, rate), 1e-9)
    }

    @Test
    fun protectionDoesNotLowerCutsOrFlatSettings() {
        assertEquals(0.0, EqMath.totalGainDb(EqSettings(enabled = true), rate), 1e-9)
        val cut = EqSettings(enabled = true).withBand(4, -6f)
        assertEquals(0.0, EqMath.totalGainDb(cut, rate), 0.01)
    }

    @Test
    fun preampAddsToTheTotalGain() {
        val s = EqSettings(enabled = true).withPreamp(-3f)
        assertEquals(-3.0, EqMath.totalGainDb(s, rate), 1e-9)
        assertEquals(-3.0, gainDb(s, 1000.0), 0.1)
    }

    @Test
    fun softClipIsIdentityBelowTheKneeAndBoundedAbove() {
        assertEquals(0.5f, EqMath.softClip(0.5f), 0f)
        assertEquals(-0.88f, EqMath.softClip(-0.88f), 0f)
        var last = EqMath.softClip(0f)
        var x = 0f
        while (x < 20f) {
            x += 0.01f
            val y = EqMath.softClip(x)
            assertTrue("x=$x y=$y", y <= 1f && y >= last)
            last = y
            assertEquals(-y, EqMath.softClip(-x), 0f)
        }
        assertTrue(EqMath.softClip(1.5f) > EqMath.KNEE)
    }

    @Test
    fun extremeSettingsStayFiniteAndWithinFullScale() {
        val s = EqSettings(enabled = true, bandsDb = List(10) { 12f }, bassDb = 12f, preampDb = 12f, protect = false)
        val chain = EqChain(44_100, 2)
        chain.configure(s)
        val random = java.util.Random(7)
        val buffer = FloatArray(2 * 20_000) { (random.nextFloat() * 2f - 1f) * 0.9f }
        chain.process(buffer, 20_000)
        for (v in buffer) {
            assertFalse(v.isNaN())
            assertTrue(abs(v) <= 1f)
        }
    }

    @Test
    fun bandsAboveTheUsableRangeAreSkippedForLowSampleRates() {
        val s = EqSettings(enabled = true, bandsDb = List(10) { 6f })
        val sections = EqMath.sections(s, 8000)
        assertEquals(11, sections.size)
        assertEquals(Biquad.IDENTITY, sections[7]) // 4 kHz ≥ 0,45 · 8 kHz
        assertEquals(Biquad.IDENTITY, sections[9])
        assertTrue(sections[5] != Biquad.IDENTITY)
    }

    @Test
    fun channelsAreProcessedIndependently() {
        val s = EqSettings(enabled = true, protect = false).withBand(5, 6f)
        val chain = EqChain(rate, 2)
        chain.configure(s)
        val frames = rate / 2
        val left = sine(1000.0, 0.05f, frames)
        val buffer = FloatArray(frames * 2)
        for (i in 0 until frames) buffer[i * 2] = left[i] // rechts bleibt still
        chain.process(buffer, frames)
        var maxRight = 0f
        for (i in 0 until frames) maxRight = maxOf(maxRight, abs(buffer[i * 2 + 1]))
        assertEquals(0f, maxRight, 0f)
        assertEquals(6.0, 20 * log10(rms(buffer, 2) / rms(left)), 0.2)
    }

    @Test
    fun reconfiguringKeepsProcessingStable() {
        val chain = EqChain(rate, 1)
        val buffer = sine(1000.0, 0.1f, 4800)
        chain.configure(EqSettings(enabled = true, protect = false).withBand(5, 6f))
        chain.process(buffer, 4800)
        chain.configure(EqSettings(enabled = true, protect = false).withBand(5, -6f))
        val next = sine(1000.0, 0.1f, 4800)
        chain.process(next, 4800)
        for (v in next) assertTrue(abs(v) <= 1f && !v.isNaN())
        chain.configure(EqSettings(enabled = false))
        assertTrue(chain.bypass)
    }

    @Test
    fun presetsAreComplete() {
        assertEquals(EqPresets.all.size, EqPresets.all.map { it.id }.toSet().size)
        for (p in EqPresets.all) {
            assertEquals(p.id, EqMath.BAND_COUNT, p.bandsDb.size)
            assertTrue(p.bandsDb.all { it in EqMath.MIN_DB..EqMath.MAX_DB })
        }
        assertEquals(List(10) { 0f }, EqPresets.byId("flat")!!.bandsDb)
        assertNull(EqPresets.byId("gibtsnicht"))
        val applied = EqPresets.apply(EqSettings(preampDb = 4f), EqPresets.byId("rock")!!)
        assertTrue(applied.enabled)
        assertEquals("rock", applied.preset)
        assertEquals(0f, applied.preampDb, 0f)
    }

    @Test
    fun editingABandMarksTheSettingsAsCustom() {
        val s = EqSettings(preset = "rock").withBand(2, 20f)
        assertNull(s.preset)
        assertEquals(EqMath.MAX_DB, s.bandsDb[2], 0f)
        assertEquals(s, s.withBand(99, 3f).let { if (it == s) s else it })
        assertEquals("flat", s.reset().preset)
        assertTrue(s.reset().bandsDb.all { it == 0f })
    }

    @Test
    fun normalizedPadsAndClamps() {
        val s = EqSettings(bandsDb = listOf(30f, -30f), preampDb = 50f, bassDb = -3f).normalized()
        assertEquals(10, s.bandsDb.size)
        assertEquals(12f, s.bandsDb[0], 0f)
        assertEquals(-12f, s.bandsDb[1], 0f)
        assertEquals(0f, s.bandsDb[9], 0f)
        assertEquals(12f, s.preampDb, 0f)
        assertEquals(0f, s.bassDb, 0f)
    }

    @Test
    fun sampleConversionRoundTrips() {
        for (v in listOf<Short>(0, 1, -1, 12345, -12345, Short.MAX_VALUE, Short.MIN_VALUE)) {
            assertEquals(v, EqMath.toShort(EqMath.toFloat(v)))
        }
        assertEquals(Short.MAX_VALUE, EqMath.toShort(2f))
        assertEquals(Short.MIN_VALUE, EqMath.toShort(-2f))
        assertNotNull(EqMath.toShort(0.5f))
    }
}
