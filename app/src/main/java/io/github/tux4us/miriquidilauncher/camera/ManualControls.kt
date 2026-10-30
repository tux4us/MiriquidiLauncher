// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.camera

import kotlin.math.abs

/** Werte, die an die Kamera gehen, wenn manuelle Steuerung aktiv ist (siehe [ManualControls.options]). */
data class ManualOptions(
    val autoExposureOff: Boolean,
    val iso: Int,
    val exposureNs: Long,
    /** Bildzeit in ns; nur bei langen Belichtungen gesetzt, damit die Belichtung nicht auf die Bildzeit gekürzt wird. */
    val frameDurationNs: Long?,
    val autoFocusOff: Boolean,
    val focusDiopters: Float,
    val whiteBalance: WhiteBalanceSetting,
)

/**
 * Auswahlstufen und Umrechnung für die manuelle Steuerung. Die Regler laufen über feste Stufen innerhalb der
 * Grenzen, die die Kamera meldet; dadurch bleiben ISO und Belichtungszeit gängige Werte.
 */
object ManualControls {
    private val ISO_STOPS = listOf(25, 50, 100, 200, 400, 800, 1600, 3200, 6400, 12800)

    /** Gängige Belichtungszeiten von 1/8000 s bis 1 s in Nanosekunden. */
    private val SHUTTER_STOPS_NS = listOf(
        125_000L, 250_000L, 500_000L, 1_000_000L, 2_000_000L, 4_000_000L, 8_000_000L,
        16_666_667L, 33_333_333L, 66_666_667L, 125_000_000L, 250_000_000L, 500_000_000L, 1_000_000_000L,
    )

    /** Ab dieser Belichtungszeit wird die Bildzeit mitgesetzt (unter 1/30 s reicht die Standardbildzeit). */
    private const val LONG_EXPOSURE_NS = 33_333_334L

    fun isoStops(caps: ManualCaps): List<Int> {
        val inRange = ISO_STOPS.filter { it in caps.isoMin..caps.isoMax }
        return inRange.ifEmpty { listOf(caps.isoMin, caps.isoMax).distinct() }
    }

    /** Belichtungszeiten innerhalb der Grenzen der Kamera, höchstens eine Sekunde. */
    fun shutterStops(caps: ManualCaps): List<Long> {
        val inRange = SHUTTER_STOPS_NS.filter { it >= caps.exposureMinNs && it <= caps.exposureMaxNs }
        return inRange.ifEmpty { listOf(caps.exposureMinNs, caps.exposureMaxNs).distinct() }
    }

    /** Index des Werts in [stops], der [value] am nächsten liegt; −1 bei leerer Liste. */
    fun nearestIndex(stops: List<Long>, value: Long): Int =
        stops.indices.minByOrNull { abs(stops[it] - value) } ?: -1

    fun nearestIsoIndex(stops: List<Int>, value: Int): Int =
        stops.indices.minByOrNull { abs(stops[it] - value) } ?: -1

    /** Fokus in Dioptrien: 0 = unendlich, [ManualCaps.minFocusDiopters] = Naheinstellung. */
    fun focusDiopters(fraction: Float, caps: ManualCaps): Float =
        fraction.coerceIn(0f, 1f) * caps.minFocusDiopters

    /** Bringt die Werte in [state] auf gültige Stufen der Kamera (z. B. nach dem Wechsel der Kamera). */
    fun normalize(state: ManualState, caps: ManualCaps?): ManualState {
        if (caps == null) return ManualState()
        val isoStops = isoStops(caps)
        val shutterStops = shutterStops(caps)
        val iso = isoStops.getOrNull(nearestIsoIndex(isoStops, state.iso)) ?: state.iso
        val exposure = shutterStops.getOrNull(nearestIndex(shutterStops, state.exposureNs)) ?: state.exposureNs
        return state.copy(
            iso = iso,
            exposureNs = exposure,
            focusEnabled = state.focusEnabled && caps.hasManualFocus,
            focusFraction = state.focusFraction.coerceIn(0f, 1f),
        )
    }

    fun options(state: ManualState, caps: ManualCaps): ManualOptions {
        val normalized = normalize(state, caps)
        return ManualOptions(
            autoExposureOff = normalized.exposureEnabled,
            iso = normalized.iso,
            exposureNs = normalized.exposureNs,
            frameDurationNs = normalized.exposureNs.takeIf { it >= LONG_EXPOSURE_NS },
            autoFocusOff = normalized.focusEnabled,
            focusDiopters = focusDiopters(normalized.focusFraction, caps),
            whiteBalance = normalized.whiteBalance,
        )
    }
}
