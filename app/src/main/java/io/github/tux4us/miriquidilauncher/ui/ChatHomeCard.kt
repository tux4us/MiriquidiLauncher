// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.tux4us.miriquidilauncher.R
import io.github.tux4us.miriquidilauncher.chat.ChatRoom
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.delay

/** Höchstens so viele Gespräche stehen in der Card; weitere werden gezählt. */
private const val MAX_ROOMS = 3
private const val REFRESH_MS = 30_000L

private val CardText = Color.White
private val CardTextMuted = Color.White.copy(alpha = 0.72f)

/**
 * Glas-Card der Startseite mit den Gesprächen, die ungelesene Nachrichten haben. Tippen auf eine Zeile öffnet das
 * Gespräch im Messenger. Solange die Card im Bild ist, ruft sie [onRefresh] einmal pro Minute auf. Mit
 * [showText] false erscheinen nur Gesprächsname und Anzahl (keine Vorschau des Textes).
 */
@Composable
fun ChatHomeCard(
    rooms: List<ChatRoom>,
    showText: Boolean,
    visible: Boolean,
    onRefresh: () -> Unit,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Fragt nur nach, solange die Startseite sichtbar ist und die App im Vordergrund steht. Mit jedem Zurückkehren in den
    // Vordergrund (z. B. nach dem Wechsel in eine andere App) beginnt die Schleife neu mit einer sofortigen Abfrage.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(visible, lifecycle) {
        if (!visible) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                onRefresh()
                delay(REFRESH_MS)
            }
        }
    }
    if (rooms.isEmpty()) return
    val total = rooms.sumOf { it.unread }
    val shape = RoundedCornerShape(24.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .glass(shape)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.home_messages_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = CardText,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = pluralStringResource(R.plurals.home_messages_count, total, total),
                style = MaterialTheme.typography.labelMedium,
                color = CardTextMuted,
            )
        }
        rooms.take(MAX_ROOMS).forEach { room -> RoomRow(room, showText, onOpen) }
        if (rooms.size > MAX_ROOMS) {
            Text(
                text = stringResource(R.string.home_messages_more, rooms.size - MAX_ROOMS),
                style = MaterialTheme.typography.labelMedium,
                color = CardTextMuted,
            )
        }
    }
}

@Composable
private fun RoomRow(room: ChatRoom, showText: Boolean, onOpen: (String) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onOpen(room.token) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                text = room.name.ifBlank { stringResource(R.string.scope_chat) },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = CardText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (showText && room.lastPreview.isNotBlank()) {
                Text(
                    text = room.lastPreview,
                    style = MaterialTheme.typography.bodySmall,
                    color = CardTextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(
            text = (if (room.unreadMention) "@ " else "") + (if (room.unread > 99) "99+" else room.unread.toString()),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFF141A22),
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(Color.White.copy(alpha = 0.9f))
                .padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}
