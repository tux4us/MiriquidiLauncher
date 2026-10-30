// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.camera

/** Auswahl und Wechsel zwischen den Kameras eines Geräts. Die Kennungen stammen aus Camera2 (`cameraId`). */
object CameraChoices {

    /** Camera2-Kennzahlen für `LENS_FACING`: 0 = vorne, 1 = hinten, 2 = extern. */
    private fun facingOf(code: Int): LensFacing = when (code) {
        0 -> LensFacing.Front
        1 -> LensFacing.Back
        else -> LensFacing.External
    }

    private fun order(facing: LensFacing): Int = when (facing) {
        LensFacing.Back -> 0
        LensFacing.Front -> 1
        LensFacing.External -> 2
    }

    /**
     * Baut die Liste aus Paaren (Kennung, Camera2-`LENS_FACING`). Reihenfolge: hinten, vorne, extern; innerhalb einer
     * Blickrichtung nach Kennung (numerisch, sofern möglich). Doppelte Kennungen zählen einmal.
     */
    fun build(raw: List<Pair<String, Int>>): List<CameraChoice> {
        val sorted = raw.distinctBy { it.first }
            .map { (id, code) -> id to facingOf(code) }
            .sortedWith(
                compareBy<Pair<String, LensFacing>>({ order(it.second) })
                    .thenBy { it.first.toIntOrNull() ?: Int.MAX_VALUE }
                    .thenBy { it.first },
            )
        val counters = HashMap<LensFacing, Int>()
        return sorted.map { (id, facing) ->
            val ordinal = (counters[facing] ?: 0) + 1
            counters[facing] = ordinal
            CameraChoice(id, facing, ordinal)
        }
    }

    /** Die gespeicherte Kamera, sonst die erste Rückkamera, sonst irgendeine. */
    fun initial(choices: List<CameraChoice>, preferredId: String?): CameraChoice? =
        choices.firstOrNull { it.id == preferredId }
            ?: choices.firstOrNull { it.facing == LensFacing.Back }
            ?: choices.firstOrNull()

    /**
     * Ziel des Wechsel-Buttons: von vorne zurück zur zuletzt benutzten Rückkamera ([lastBackId]), von hinten (oder
     * extern) zur ersten Frontkamera. Gibt es keine Gegenseite, wechselt der Button zur nächsten Kamera der Liste.
     */
    fun flipTarget(choices: List<CameraChoice>, currentId: String?, lastBackId: String?): CameraChoice? {
        if (choices.size < 2) return null
        val current = choices.firstOrNull { it.id == currentId } ?: return choices.firstOrNull()
        val opposite = if (current.facing == LensFacing.Front) {
            choices.firstOrNull { it.id == lastBackId && it.facing == LensFacing.Back }
                ?: choices.firstOrNull { it.facing == LensFacing.Back }
        } else {
            choices.firstOrNull { it.facing == LensFacing.Front }
        }
        if (opposite != null) return opposite
        return CameraRules.next(choices, current)
    }

    /** `true`, wenn mehrere Rückkameras zur Auswahl stehen und sich eine eigene Auswahlzeile lohnt. */
    fun hasMultipleBack(choices: List<CameraChoice>): Boolean = choices.count { it.facing == LensFacing.Back } > 1
}
