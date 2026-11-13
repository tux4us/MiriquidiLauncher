// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp

/** Farbton der Glasfläche (ohne Deckkraft). */
private val GlassTone = Color(0xFF14181F)

/** Grundfläche des Glas-Stils: dunkel, zu 50 % durchsichtig, damit das Hintergrundbild durchscheint. */
private val GlassBase = GlassTone.copy(alpha = 0.50f)

/**
 * Grundfläche mit Verlauf: unten fast deckend, nach oben zunehmend durchsichtig. Für Panels, die direkt über
 * anderen Bedienelementen (z. B. dem Suchfeld) aufklappen, damit die unterste Reihe gut lesbar bleibt.
 */
internal val GlassBaseFadingUp: Brush = Brush.verticalGradient(
    0f to GlassTone.copy(alpha = 0.30f),
    0.55f to GlassTone.copy(alpha = 0.55f),
    1f to GlassTone.copy(alpha = 0.88f),
)

/** Glanzlicht oben, Schatten unten: lässt die Fläche leicht erhaben wirken. */
private val GlassSheen = Brush.verticalGradient(
    0f to Color.White.copy(alpha = 0.18f),
    0.45f to Color.Transparent,
    1f to Color.Black.copy(alpha = 0.16f),
)

/** Randlinie: oben hell, unten fast unsichtbar, als fiele das Licht von oben auf die Kante. */
private val GlassRim = Brush.verticalGradient(
    0f to Color.White.copy(alpha = 0.60f),
    1f to Color.White.copy(alpha = 0.12f),
)

/**
 * Halbtransparente Fläche mit leichtem 3D-Effekt: 50 % Deckkraft, Glanzverlauf, helle Randlinie und weicher
 * Schlagschatten. Der Schatten wird von Android nur außerhalb der Form gezeichnet, die Fläche bleibt also
 * durchsichtig. Für Suchfeld, Button-Leiste und Karten auf der Startseite.
 */
fun Modifier.glass(shape: Shape): Modifier = glass(shape, SolidColor(GlassBase))

/** Glas-Stil mit eigener Grundfläche [base], z. B. [GlassBaseFadingUp]; Glanz, Rand und Schatten wie oben. */
fun Modifier.glass(shape: Shape, base: Brush): Modifier = this
    .shadow(elevation = 6.dp, shape = shape, clip = false, ambientColor = Color.Black, spotColor = Color.Black)
    .clip(shape)
    .background(base)
    .background(GlassSheen)
    .border(width = 1.dp, brush = GlassRim, shape = shape)
