// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.recents

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Geometrie der Fächeransicht. Die Karten stehen auf einem Kreisbogen um einen Drehpunkt unterhalb des Bildschirms;
 * `offset` ist der Abstand einer Karte zur Mitte in Karten (Kartenindex minus aktuelle Position, bei Zwischenwerten
 * des Wischens auch gebrochen). Ohne Android-Abhängigkeiten, damit sich die Werte testen lassen.
 */
object RecentsFan {

    /** Drehwinkel je Karte Abstand zur Mitte, in Grad (positiv: im Uhrzeigersinn). */
    const val ANGLE_PER_CARD = 8f

    /** Abstand des Drehpunkts von der Oberkante der Karte als Vielfaches der Kartenhöhe (1 = Unterkante). */
    const val PIVOT_Y_FRACTION = 1.6f

    /** Karten, die weiter als dieser Abstand von der Mitte entfernt sind, werden nicht gezeichnet. */
    const val VISIBLE_RANGE = 4f

    /** Verlängerung des Schwungs beim Loslassen, in Sekunden (Auslauf der Wischgeste). */
    private const val FLING_SECONDS = 0.22f

    data class CardTransform(
        val rotationDegrees: Float,
        val scale: Float,
        val alpha: Float,
        val visible: Boolean,
    )

    fun transform(offset: Float): CardTransform {
        val distance = abs(offset)
        val visible = distance < VISIBLE_RANGE
        val scale = (1f - 0.045f * distance).coerceIn(0.7f, 1f)
        val alpha = (1f - (distance - 2f).coerceAtLeast(0f) / (VISIBLE_RANGE - 2f)).coerceIn(0f, 1f)
        return CardTransform(
            rotationDegrees = offset * ANGLE_PER_CARD,
            scale = scale,
            alpha = alpha,
            visible = visible,
        )
    }

    /** Position nach einer Ziehbewegung: Ziehen nach links ([dragPx] negativ) blättert zu älteren Karten. */
    fun positionAfterDrag(position: Float, dragPx: Float, pxPerCard: Float, count: Int): Float {
        if (count <= 0 || pxPerCard <= 0f) return 0f
        return (position - dragPx / pxPerCard).coerceIn(0f, (count - 1).toFloat())
    }

    /**
     * Karte, auf der das Blättern nach dem Loslassen einrastet. [velocityPxPerSecond] ist die Geschwindigkeit der
     * Geste (negativ: nach links).
     */
    fun targetIndex(position: Float, velocityPxPerSecond: Float, pxPerCard: Float, count: Int): Int {
        if (count <= 0 || pxPerCard <= 0f) return 0
        val projected = position - velocityPxPerSecond * FLING_SECONDS / pxPerCard
        return projected.roundToInt().coerceIn(0, count - 1)
    }

    /** Ob das Wischen nach oben eine Karte entfernt: weit genug gezogen oder schnell genug gewischt. */
    fun shouldDismiss(dragUpPx: Float, velocityUpPxPerSecond: Float, thresholdPx: Float): Boolean =
        dragUpPx >= thresholdPx || (dragUpPx > thresholdPx * 0.25f && velocityUpPxPerSecond >= FLING_DISMISS_SPEED)

    private const val FLING_DISMISS_SPEED = 1500f
}
