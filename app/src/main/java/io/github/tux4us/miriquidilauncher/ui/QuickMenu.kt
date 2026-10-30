// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.tux4us.miriquidilauncher.R
import io.github.tux4us.miriquidilauncher.data.FavoriteContact
import io.github.tux4us.miriquidilauncher.quickmenu.Bounds
import io.github.tux4us.miriquidilauncher.quickmenu.QuickMenuEvent
import io.github.tux4us.miriquidilauncher.quickmenu.QuickMenuLayout
import kotlin.math.roundToInt

/**
 * Welches der Schnellmenüs gemeint ist. Derzeit gibt es nur das Scopes-Menü; die Favoriten (Apps, Kontakte)
 * und die App-Gruppen sind eigene Scopes. Die Menü-Mechanik bleibt für weitere Menüs erhalten.
 */
enum class QuickMenuKind { Scopes }

/**
 * Ein Eintrag im Schnellmenü, unabhängig davon, ob es eine App oder ein Kontakt ist.
 *
 * @property label voller Name; dient als Beschreibung für Bedienhilfen
 * @property caption kurzer Name unter dem Symbol; `null` zeigt nur das Symbol
 */
class QuickMenuEntry(
    val label: String,
    val caption: String? = null,
    val leading: @Composable () -> Unit,
)

private class CoordinatesHolder {
    var value: LayoutCoordinates? = null
}

/** Höhe der Button-Leiste über dem Suchfeld; entspricht der Höhe des Suchfelds. */
private val ButtonHeight = 56.dp

/** Kantenlänge des Symbols (App-Icon bzw. Kontaktbild) in einer Rasterzelle. */
private val CellIconSize = 48.dp

private const val PanelAppearMillis = 140

/**
 * Button des Schnellmenüs: eine abgerundete Fläche, die sich per `Modifier.weight(1f)` die Breite der
 * Button-Leiste mit den übrigen Buttons teilt.
 *
 * Der Button reagiert nur auf einen Klick (Tippen) und sendet dann [QuickMenuEvent.ShortPress]. Berühren und
 * Halten, Wischen oder Gleiten über das Menü haben keine Wirkung. Koordinaten werden nur für die Position
 * des Menüs gemessen (Bildschirmkoordinaten, Ursprung = linke obere Ecke des Fensters). Für Bedienhilfen
 * (TalkBack) gilt dieselbe Klick-Aktion.
 */
@Composable
fun QuickMenuButton(
    kind: QuickMenuKind,
    onEvent: (QuickMenuEvent) -> Unit,
    onAnchorChanged: (Bounds) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentOnEvent by rememberUpdatedState(onEvent)
    val currentOnAnchorChanged by rememberUpdatedState(onAnchorChanged)
    val holder = remember { CoordinatesHolder() }
    val label = stringResource(
        when (kind) {
            QuickMenuKind.Scopes -> R.string.quick_menu_scopes
        },
    )

    // onGloballyPositioned meldet die Position nicht zuverlässig, wenn der Pager scrollt (veraltete
    // Werte). Deshalb wird die Position unmittelbar vor dem Öffnen des Menüs frisch gemessen.
    fun reportFreshAnchor() {
        val coordinates = holder.value
        if (coordinates != null && coordinates.isAttached) {
            val position = coordinates.positionInRoot()
            currentOnAnchorChanged(
                Bounds(
                    left = position.x,
                    top = position.y,
                    right = position.x + coordinates.size.width,
                    bottom = position.y + coordinates.size.height,
                ),
            )
        }
    }
    val glyphColor = MaterialTheme.colorScheme.primary

    Box(
        modifier = modifier
            .height(ButtonHeight)
            .glass(RoundedCornerShape(ButtonHeight / 2))
            .onGloballyPositioned { coordinates ->
                holder.value = coordinates
                val position = coordinates.positionInRoot()
                currentOnAnchorChanged(
                    Bounds(
                        left = position.x,
                        top = position.y,
                        right = position.x + coordinates.size.width,
                        bottom = position.y + coordinates.size.height,
                    ),
                )
            }
            .clickable(onClickLabel = label, role = Role.Button) {
                reportFreshAnchor()
                currentOnEvent(QuickMenuEvent.ShortPress)
            }
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(
                    when (kind) {
                        QuickMenuKind.Scopes -> R.drawable.ic_scopes
                    },
                ),
                contentDescription = null,
                tint = glyphColor,
                modifier = Modifier.size(26.dp),
            )
            Spacer(Modifier.width(10.dp))
            // Beschriftung neben dem Symbol; für Bedienhilfen steht sie bereits als Beschreibung am Button.
            Text(
                text = label,
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Darstellung des geöffneten Schnellmenüs über dem gesamten Bildschirm als Pop-up mit Raster.
 *
 * Das Overlay muss im Wurzel-Layout liegen, das das ganze Fenster ausfüllt, weil [layout] in
 * Fensterkoordinaten berechnet ist. Es wird nur gezeichnet, solange das Menü offen ist. Die Einträge sind
 * antippbar; die Zeilen lassen sich bei mehr Einträgen als sichtbarem Platz mit dem Finger scrollen
 * (Zeile 0 liegt unten). Tippen neben das Panel schließt das Menü.
 *
 * Die Kopfzeile zeigt den [title] des Menüs.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun QuickMenuOverlay(
    layout: QuickMenuLayout,
    entries: List<QuickMenuEntry>,
    title: String,
    emptyHint: String,
    actionLabel: String?,
    onEvent: (QuickMenuEvent) -> Unit,
    onItemLongClick: ((Int) -> Unit)? = null,
) {
    val density = LocalDensity.current
    val currentOnEvent by rememberUpdatedState(onEvent)
    val cellWidth = with(density) { layout.cellWidth.toDp() }
    val cellHeight = with(density) { layout.cellHeight.toDp() }
    val headerHeight = with(density) { layout.headerHeight.toDp() }
    val paddingDp = with(density) { layout.padding.toDp() }

    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, tween(PanelAppearMillis)) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .pointerInput(Unit) {
                detectTapGestures { currentOnEvent(QuickMenuEvent.Dismiss) }
            },
    ) {
        if (layout.showsEmptyHint || layout.visibleRows > 0) {
            Column(
                modifier = Modifier
                    .offset { IntOffset(layout.panel.left.roundToInt(), layout.panel.top.roundToInt()) }
                    .size(
                        width = with(density) { layout.panel.width.toDp() },
                        height = with(density) { layout.panel.height.toDp() },
                    )
                    .graphicsLayer {
                        val progress = appear.value
                        alpha = progress
                        scaleX = 0.92f + 0.08f * progress
                        scaleY = 0.92f + 0.08f * progress
                        transformOrigin = TransformOrigin(0.5f, 1f)
                    }
                    .clip(RoundedCornerShape(28.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    // Tippen auf die Kopfzeile oder das Polster darf das Menü nicht schließen.
                    .pointerInput(Unit) { detectTapGestures { } },
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(headerHeight)
                        .padding(horizontal = 20.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        text = title,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(start = paddingDp, end = paddingDp, bottom = paddingDp),
                ) {
                    if (layout.showsEmptyHint) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 8.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            Text(
                                text = emptyHint,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    } else {
                        // Zeilen mit dem Finger scrollbar. Zeile 0 liegt unten.
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            reverseLayout = true,
                        ) {
                            items(layout.totalRows, key = { it }) { row ->
                                Row {
                                    for (column in 0 until layout.columns) {
                                        val index = row * layout.columns + column
                                        MenuCell(
                                            index = index,
                                            entries = entries,
                                            actionLabel = actionLabel,
                                            width = cellWidth,
                                            height = cellHeight,
                                            onClick = { currentOnEvent(QuickMenuEvent.ItemTapped(index)) },
                                            onLongClick = onItemLongClick
                                                ?.takeIf { index < entries.size }
                                                ?.let { handler -> { handler(index) } },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Eine antippbare Zelle des Rasters. Ist [index] kein Eintrag, aber die Stelle für die zusätzliche Aktion
 * (z. B. "Kontakt hinzufügen", nur bei gesetztem [actionLabel]), erscheint diese; sonst bleibt die Zelle leer,
 * damit die Positionen nicht verrutschen.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MenuCell(
    index: Int,
    entries: List<QuickMenuEntry>,
    actionLabel: String?,
    width: Dp,
    height: Dp,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
) {
    val entry = entries.getOrNull(index)
        ?: if (index == entries.size && actionLabel != null) {
            QuickMenuEntry(label = actionLabel, caption = null) {
                Icon(
                    painter = painterResource(R.drawable.ic_add),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp),
                )
            }
        } else {
            null
        }
    if (entry == null) {
        Spacer(Modifier.size(width, height))
        return
    }
    Box(
        modifier = Modifier
            .size(width, height)
            .padding(3.dp)
            .clip(RoundedCornerShape(20.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .semantics(mergeDescendants = true) { contentDescription = entry.label },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(CellIconSize), contentAlignment = Alignment.Center) { entry.leading() }
            if (entry.caption != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = entry.caption,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 2.dp),
                )
            }
        }
    }
}

/** Rundes Kontaktbild: das Foto aus dem Adressbuch, sonst eine Fläche mit dem Anfangsbuchstaben. */
@Composable
internal fun ContactAvatar(
    name: String,
    photo: ImageBitmap?,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        if (photo != null) {
            Image(
                bitmap = photo,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                text = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

/**
 * Kontaktbild eines Favoriten. [reloadKey] lädt das Foto erneut, wenn sich etwas ändert, das den Zugriff
 * betrifft (z. B. die erteilte Kontaktberechtigung).
 */
@Composable
internal fun FavoriteContactAvatar(
    contact: FavoriteContact,
    viewModel: LauncherViewModel,
    reloadKey: Any?,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val photo by produceState<ImageBitmap?>(initialValue = null, contact.key, reloadKey) {
        value = viewModel.loadContactPhoto(contact)
    }
    ContactAvatar(contact.name, photo, size, modifier)
}
