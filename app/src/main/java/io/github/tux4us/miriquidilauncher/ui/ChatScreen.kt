// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import java.time.LocalDateTime
import io.github.tux4us.miriquidilauncher.chat.ChatApi
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.horizontalScroll
import android.widget.Toast
import android.content.Context
import android.content.ClipboardManager
import android.content.ClipData
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.tux4us.miriquidilauncher.R
import io.github.tux4us.miriquidilauncher.chat.ChatAttachment
import io.github.tux4us.miriquidilauncher.chat.ChatLogic
import io.github.tux4us.miriquidilauncher.chat.ChatMessage
import io.github.tux4us.miriquidilauncher.chat.ChatMessageKind
import io.github.tux4us.miriquidilauncher.chat.ChatNoticeKind
import io.github.tux4us.miriquidilauncher.chat.ChatNotifier
import io.github.tux4us.miriquidilauncher.chat.ChatRoom
import io.github.tux4us.miriquidilauncher.chat.ChatUiState
import io.github.tux4us.miriquidilauncher.chat.ChatViewModel
import io.github.tux4us.miriquidilauncher.chat.Outgoing
import io.github.tux4us.miriquidilauncher.chat.OutgoingCheck
import io.github.tux4us.miriquidilauncher.chat.OutgoingState
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

private val ChatError = Color(0xFFFFB4B4)
private val ChatDialogColor = Color(0xFF141A22)
private val ChatLink = Color(0xFF9CC8FF)
private val ChatOwnBubble = SolidColor(Color(0x4D29B6F6))
private val ChatOwnLabel = Color(0xFF7FD3FA)

/** Eigene Nachrichten: Ecke unten rechts spitz (rechts ausgerichtet); empfangene: Ecke unten links spitz. */
private val ChatOwnShape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomEnd = 4.dp, bottomStart = 16.dp)
private val ChatOtherShape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomEnd = 16.dp, bottomStart = 4.dp)

/** Platz rechts neben dem Eingabefeld bei geöffneter Tastatur (dann ist die Navigation des Launchers verdeckt oder knapp darüber). */
private val ChatHomeButtonClearance = 72.dp

private sealed interface ChatDialog {
    data object Login : ChatDialog
    data object Account : ChatDialog
}

/**
 * Messenger: Nextcloud Talk mit Raumliste und Chatansicht. [active] ist `true`, solange die Seite sichtbar ist; Verbindungen
 * (Long-Polling, Aktualisierung der Raumliste) bestehen nur, solange die Seite sichtbar und die App im Vordergrund ist.
 */
@Composable
fun ChatScreen(viewModel: ChatViewModel, active: Boolean, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resumed by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var dialog by remember { mutableStateOf<ChatDialog?>(null) }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ -> resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(active, resumed) { viewModel.setActive(active && resumed) }
    DisposableEffect(Unit) { onDispose { viewModel.setActive(false) } }
    BackHandler(enabled = active && (state.openRoom != null || state.query != null || state.replyTo != null)) { viewModel.back() }

    val room = state.openRoom
    ScopePage(title = room?.name ?: stringResource(R.string.scope_chat), modifier = modifier) {
        Column(Modifier.fillMaxSize()) {
            state.notice?.let { NoticeBanner(it.kind, viewModel::dismissNotice) }
            if (state.offline && state.account != null) {
                Text(
                    stringResource(R.string.chat_offline),
                    color = WeatherTextMuted,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
                )
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    !state.ready -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = WeatherText)
                    state.account == null -> GlassCard(stringResource(R.string.scope_chat)) {
                        Text(stringResource(R.string.chat_intro), color = WeatherTextMuted, style = MaterialTheme.typography.bodyMedium)
                        PodcastButton(stringResource(R.string.chat_connect), { dialog = ChatDialog.Login }, enabled = !state.busy)
                    }
                    room == null -> RoomList(state, viewModel) { dialog = it }
                    else -> ChatView(state, room, viewModel)
                }
            }
            if (room == null) Spacer(Modifier.height(ScopeBottomClearance))
        }
    }

    when (dialog) {
        ChatDialog.Login -> LoginDialog(viewModel) { dialog = null }
        ChatDialog.Account -> AccountDialog(state, viewModel) { dialog = null }
        null -> Unit
    }
}

// region Raumliste ---------------------------------------------------------------------------------

@Composable
private fun RoomList(state: ChatUiState, viewModel: ChatViewModel, onDialog: (ChatDialog) -> Unit) {
    val rooms = remember(state.rooms, state.query) { ChatLogic.filterRooms(state.rooms, state.query) }
    val locale = remember { Locale.getDefault() }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = state.query.orEmpty(),
            onValueChange = { viewModel.setQuery(it.ifEmpty { null }) },
            singleLine = true,
            label = { Text(stringResource(R.string.chat_search_hint)) },
            colors = filesFieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            PodcastTextButton(stringResource(R.string.chat_refresh)) { viewModel.refresh() }
            PodcastTextButton(stringResource(R.string.chat_account)) { onDialog(ChatDialog.Account) }
            if (state.roomsLoading) CircularProgressIndicator(Modifier.padding(start = 8.dp).height(20.dp), color = WeatherText, strokeWidth = 2.dp)
        }
        if (rooms.isEmpty() && !state.roomsLoading) {
            ScopeEmptyHint(stringResource(if (state.query == null) R.string.chat_empty else R.string.chat_empty_search))
        }
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(rooms, key = { it.token }) { room -> RoomRow(room, locale) { viewModel.openRoom(room.token) } }
        }
    }
}

@Composable
private fun RoomRow(room: ChatRoom, locale: Locale, onClick: () -> Unit) {
    val unread = room.unread > 0
    Row(
        Modifier
            .fillMaxWidth()
            .glass(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = (if (room.favorite) "★ " else "") + room.name,
                color = WeatherText,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (unread) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (room.lastPreview.isNotEmpty()) {
                Text(room.lastPreview, color = WeatherTextMuted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            if (room.lastActivity > 0) {
                Text(formatChatTime(room.lastActivity, locale), color = WeatherTextMuted, style = MaterialTheme.typography.labelSmall)
            }
            if (unread || room.unreadMention) {
                Box(
                    Modifier.padding(top = 4.dp).background(WeatherText, CircleShape).padding(horizontal = 8.dp, vertical = 2.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    val count = if (room.unread > 99) "99+" else room.unread.toString()
                    Text(
                        text = if (room.unreadMention) (if (unread) "@ $count" else "@") else count,
                        color = Color.Black,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

// endregion

// region Chatansicht -------------------------------------------------------------------------------

@Composable
private fun ChatView(state: ChatUiState, room: ChatRoom, viewModel: ChatViewModel) {
    val locale = remember { Locale.getDefault() }
    val zone = remember { ZoneId.systemDefault() }
    val messages = state.messages
    val newestFirst = remember(messages) { messages.asReversed() }
    val outbox = remember(state.outbox, room.token) { state.outbox.filter { it.token == room.token }.asReversed() }
    val listState = rememberLazyListState()
    val context = LocalContext.current
    var forwardOf by remember(room.token) { mutableStateOf<ChatMessage?>(null) }
    val newestId = messages.lastOrNull()?.id ?: 0L
    var scrolledOnce by remember(room.token) { mutableStateOf(false) }
    var seenOutbox by remember(room.token) { mutableStateOf(0) }
    // Zur neuesten Nachricht (Index 0 der umgekehrten Liste) springen: beim Öffnen, nach eigenem Senden und bei neuen
    // Nachrichten, solange der Benutzer nicht weiter oben im Verlauf liest.
    LaunchedEffect(room.token, newestId, outbox.size) {
        if (newestId == 0L && outbox.isEmpty()) return@LaunchedEffect
        val justSent = outbox.size > seenOutbox
        seenOutbox = outbox.size
        val nearBottom = listState.firstVisibleItemIndex <= 2
        if (!scrolledOnce) {
            listState.scrollToItem(0)
            scrolledOnce = true
        } else if (justSent || nearBottom) {
            listState.animateScrollToItem(0)
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            PodcastTextButton(stringResource(R.string.chat_back)) { viewModel.closeRoom() }
            if (state.messagesLoading) CircularProgressIndicator(Modifier.height(20.dp), color = WeatherText, strokeWidth = 2.dp)
        }
        // Neueste Nachricht unten: Die Liste läuft von unten nach oben, Index 0 ist der unterste Eintrag.
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            reverseLayout = true,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(outbox, key = { "out-" + it.localId }) { o ->
                OutgoingBubble(o, viewModel)
            }
            itemsIndexed(newestFirst, key = { _, m -> m.id }) { index, message ->
                // `newestFirst[index + 1]` ist die davor gesendete Nachricht.
                val previous = newestFirst.getOrNull(index + 1)
                MessageItem(
                    message = message,
                    own = message.isOwn(state.ownId),
                    showAuthor = room.isGroup,
                    newDay = ChatLogic.isNewDay(previous?.timestamp, message.timestamp, zone),
                    canReply = !room.readOnly,
                    locale = locale,
                    onReply = { viewModel.setReply(message) },
                    onCopy = { copyToClipboard(context, ChatLogic.forwardText(message)) },
                    onForward = { forwardOf = message },
                    onReminder = { pickReminderTime(context) { millis -> viewModel.createReminder(message, millis) } },
                    onReact = { emoji -> viewModel.react(message, emoji) },
                )
            }
            if (state.hasOlder && messages.isNotEmpty()) {
                item(key = "older") {
                    LaunchedEffect(messages.firstOrNull()?.id) { viewModel.loadOlder() }
                    Text(
                        stringResource(R.string.chat_loading_older),
                        color = WeatherTextMuted,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                    )
                }
            }
        }
        if (room.readOnly) {
            Text(
                stringResource(R.string.chat_read_only_room),
                color = WeatherTextMuted,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 12.dp, horizontal = 4.dp),
            )
        } else {
            Composer(state, room, viewModel)
        }
    }
    forwardOf?.let { message ->
        ForwardDialog(
            rooms = state.rooms,
            onPick = { target ->
                forwardOf = null
                viewModel.forward(message, target.token)
            },
            onDismiss = { forwardOf = null },
        )
    }
}

/** Kopiert [text] in die Zwischenablage; ab Android 13 zeigt das System selbst eine Bestätigung. */
private fun copyToClipboard(context: Context, text: String) {
    if (text.isEmpty()) return
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    manager.setPrimaryClip(ClipData.newPlainText("", text))
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, R.string.chat_copied, Toast.LENGTH_SHORT).show()
    }
}

/** Fragt Datum und Uhrzeit der Erinnerung mit den Standarddialogen des Systems ab (Vorgabe: morgen 9:00 Uhr). */
private fun pickReminderTime(context: Context, onPicked: (Long) -> Unit) {
    val zone = ZoneId.systemDefault()
    val tomorrow = LocalDate.now(zone).plusDays(1)
    val dialog = android.app.DatePickerDialog(
        context,
        { _, year, month, day ->
            val is24 = android.text.format.DateFormat.is24HourFormat(context)
            android.app.TimePickerDialog(
                context,
                { _, hour, minute ->
                    onPicked(LocalDateTime.of(year, month + 1, day, hour, minute).atZone(zone).toInstant().toEpochMilli())
                },
                9, 0, is24,
            ).show()
        },
        tomorrow.year, tomorrow.monthValue - 1, tomorrow.dayOfMonth,
    )
    dialog.datePicker.minDate = System.currentTimeMillis() - 1_000L
    dialog.show()
}

@Composable
private fun ForwardDialog(rooms: List<ChatRoom>, onPick: (ChatRoom) -> Unit, onDismiss: () -> Unit) {
    val targets = remember(rooms) { rooms.filter { !it.readOnly } }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ChatDialogColor,
        titleContentColor = WeatherText,
        textContentColor = WeatherTextMuted,
        title = { Text(stringResource(R.string.chat_forward_title)) },
        text = {
            if (targets.isEmpty()) {
                Text(stringResource(R.string.chat_empty))
            } else {
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(targets, key = { it.token }) { target ->
                        Text(
                            text = target.name.ifBlank { stringResource(R.string.scope_chat) },
                            color = WeatherText,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth().clickable { onPick(target) }.padding(vertical = 12.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { DialogButton(stringResource(R.string.chat_cancel), onDismiss) },
    )
}

@Composable
private fun Composer(state: ChatUiState, room: ChatRoom, viewModel: ChatViewModel) {
    var draft by rememberSaveable(room.token) { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            viewModel.sendAttachment(uri, draft)
            if (ChatLogic.clean(draft).length <= io.github.tux4us.miriquidilauncher.chat.ChatApi.MAX_MESSAGE_CHARS) draft = ""
        }
    }
    // Ohne Tastatur liegt die Navigation des Launchers (Home-Taste, Nachbar-Scopes) unten rechts; das Eingabefeld rückt darüber.
    val keyboardOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val endClearance = if (keyboardOpen) ChatHomeButtonClearance else 0.dp
    Column(Modifier.fillMaxWidth().imePadding()) {
        state.replyTo?.let { reply ->
            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp, end = endClearance).glass(RoundedCornerShape(12.dp)).padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                    Text(
                        stringResource(R.string.chat_replying_to, reply.actorName.ifEmpty { "…" }),
                        color = WeatherText,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(reply.text.replace('\n', ' '), color = WeatherTextMuted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                PodcastTextButton(stringResource(R.string.chat_reply_cancel)) { viewModel.setReply(null) }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 8.dp, end = endClearance),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // Runde Glas-Schaltfläche mit Büroklammer, auf Höhe des Eingabefelds (Feldhöhe 56 dp).
            IconButton(
                onClick = { picker.launch(arrayOf("*/*")) },
                modifier = Modifier.size(56.dp).glass(CircleShape),
            ) {
                Icon(painterResource(R.drawable.ic_attach), stringResource(R.string.chat_attach), tint = WeatherText)
            }
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.chat_message_hint), color = WeatherTextMuted) },
                maxLines = 5,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                colors = filesFieldColors(),
            )
            // Kleine runde Schaltfläche mit Pfeil statt der Textschaltfläche; die Beschriftung bleibt für TalkBack.
            IconButton(
                onClick = {
                    // Der Entwurf bleibt stehen, wenn der Text nicht gesendet werden kann (leer, zu lang).
                    val sendable = ChatLogic.check(ChatLogic.clean(draft)) == OutgoingCheck.OK
                    viewModel.send(draft)
                    if (sendable) draft = ""
                },
                modifier = Modifier.size(48.dp).glass(CircleShape),
            ) {
                Icon(painterResource(R.drawable.ic_send), stringResource(R.string.chat_send), tint = WeatherText)
            }
        }
        if (!keyboardOpen) Spacer(Modifier.height(ScopeBottomClearance))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageItem(
    message: ChatMessage,
    own: Boolean,
    showAuthor: Boolean,
    newDay: Boolean,
    canReply: Boolean,
    locale: Locale,
    onReply: () -> Unit,
    onCopy: () -> Unit,
    onForward: () -> Unit,
    onReminder: () -> Unit,
    onReact: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        if (newDay) {
            Text(
                formatChatDay(message.timestamp, locale),
                color = WeatherTextMuted,
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            )
        }
        if (message.kind == ChatMessageKind.SYSTEM) {
            Text(
                message.text,
                color = WeatherTextMuted,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 2.dp),
            )
            return@Column
        }
        var menuOpen by remember { mutableStateOf(false) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = if (own) Arrangement.End else Arrangement.Start) {
            Box {
                Column(
                    Modifier
                        .widthIn(max = 300.dp)
                        .then(if (own) Modifier.glass(ChatOwnShape, ChatOwnBubble) else Modifier.glass(ChatOtherShape))
                        .combinedClickable(onClick = {}, onLongClick = { if (message.kind == ChatMessageKind.TEXT) menuOpen = true })
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (own) {
                        Text(
                            stringResource(R.string.chat_you),
                            color = ChatOwnLabel,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.align(Alignment.End),
                        )
                    } else if (showAuthor && message.actorName.isNotEmpty()) {
                        Text(message.actorName, color = WeatherText, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    }
                    message.reply?.let { reply ->
                        Column(Modifier.fillMaxWidth().background(Color(0x22FFFFFF), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 4.dp)) {
                            if (reply.author.isNotEmpty()) {
                                Text(reply.author, color = WeatherText, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Text(reply.text.ifEmpty { stringResource(R.string.chat_deleted) }, color = WeatherTextMuted, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (message.kind == ChatMessageKind.DELETED) {
                        Text(stringResource(R.string.chat_deleted), color = WeatherTextMuted, style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic)
                    } else {
                        if (message.text.isNotEmpty()) MessageText(message.text)
                        message.attachments.forEach { AttachmentRow(it) }
                    }
                    if (message.reactions.isNotEmpty()) {
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            message.reactions.forEach { (emoji, count) ->
                                val mine = emoji in message.reactionsSelf
                                Text(
                                    text = "$emoji $count",
                                    color = WeatherText,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = if (mine) FontWeight.Bold else FontWeight.Normal,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(50))
                                        .background(if (mine) Color(0x66FFFFFF) else Color(0x22FFFFFF))
                                        .clickable(enabled = canReply) { onReact(emoji) }
                                        .padding(horizontal = 8.dp, vertical = 2.dp),
                                )
                            }
                        }
                    }
                    Text(formatChatClock(message.timestamp, locale), color = WeatherTextMuted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.End))
                }
                val hasContent = remember(message) { ChatLogic.forwardText(message).isNotEmpty() }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    if (canReply) {
                        Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            ChatApi.QUICK_REACTIONS.forEach { emoji ->
                                Text(
                                    text = emoji,
                                    fontSize = 22.sp,
                                    modifier = Modifier
                                        .clip(CircleShape)
                                        .clickable(onClickLabel = emoji) { menuOpen = false; onReact(emoji) }
                                        .padding(8.dp),
                                )
                            }
                        }
                        DropdownMenuItem(text = { Text(stringResource(R.string.chat_reply)) }, onClick = { menuOpen = false; onReply() })
                    }
                    DropdownMenuItem(text = { Text(stringResource(R.string.chat_copy)) }, enabled = hasContent, onClick = { menuOpen = false; onCopy() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.chat_forward)) }, enabled = hasContent, onClick = { menuOpen = false; onForward() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.chat_reminder)) }, enabled = hasContent, onClick = { menuOpen = false; onReminder() })
                }
            }
        }
    }
}

/** Nachrichtentext; HTTPS-Adressen sind anklickbar. */
@Composable
private fun MessageText(text: String) {
    val annotated = remember(text) {
        buildAnnotatedString {
            ChatLogic.segments(text).forEach { part ->
                val url = part.url
                if (url == null) {
                    append(part.text)
                } else {
                    withLink(LinkAnnotation.Url(url, TextLinkStyles(SpanStyle(color = ChatLink, textDecoration = TextDecoration.Underline)))) { append(part.text) }
                }
            }
        }
    }
    Text(annotated, color = WeatherText, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun AttachmentRow(attachment: ChatAttachment) {
    val handler = LocalUriHandler.current
    val link = attachment.link
    val label = stringResource(R.string.chat_file, attachment.name)
    if (link == null) {
        Text(label, color = WeatherTextMuted, style = MaterialTheme.typography.bodyMedium)
    } else {
        Text(
            label,
            color = ChatLink,
            style = MaterialTheme.typography.bodyMedium,
            textDecoration = TextDecoration.Underline,
            modifier = Modifier.clickable {
                try {
                    handler.openUri(link)
                } catch (e: ActivityNotFoundException) {
                    // Kein Browser installiert: nichts zu tun.
                } catch (e: IllegalArgumentException) {
                    // Adresse nicht öffenbar: nichts zu tun.
                }
            },
        )
    }
}

@Composable
private fun OutgoingBubble(o: Outgoing, viewModel: ChatViewModel) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Column(
            Modifier.widthIn(max = 300.dp).glass(ChatOwnShape, ChatOwnBubble).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                stringResource(R.string.chat_you),
                color = ChatOwnLabel,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.End),
            )
            o.attachmentName?.let { Text(stringResource(R.string.chat_file, it), color = WeatherText, style = MaterialTheme.typography.bodyMedium) }
            if (o.text.isNotEmpty()) MessageText(o.text)
            if (o.state == OutgoingState.SENDING) {
                Text(stringResource(R.string.chat_sending), color = WeatherTextMuted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.End))
            } else {
                Text(stringResource(R.string.chat_failed), color = ChatError, style = MaterialTheme.typography.labelMedium)
                Row {
                    PodcastTextButton(stringResource(R.string.chat_retry)) { viewModel.retry(o.localId) }
                    PodcastTextButton(stringResource(R.string.chat_discard)) { viewModel.discard(o.localId) }
                }
            }
        }
    }
}

// endregion

// region Hinweise und Dialoge -------------------------------------------------------------------------

@Composable
private fun NoticeBanner(kind: ChatNoticeKind, onDismiss: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 8.dp).glass(RoundedCornerShape(14.dp)).padding(start = 14.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val info = kind == ChatNoticeKind.FORWARDED || kind == ChatNoticeKind.REMINDER_CREATED
        Text(stringResource(noticeText(kind)), color = if (info) WeatherText else ChatError, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        PodcastTextButton(stringResource(R.string.chat_notice_dismiss), onDismiss)
    }
}

private fun noticeText(kind: ChatNoticeKind): Int = when (kind) {
    ChatNoticeKind.LOGIN_INVALID_URL -> R.string.chat_notice_login_invalid_url
    ChatNoticeKind.LOGIN_NOT_HTTPS -> R.string.chat_notice_login_not_https
    ChatNoticeKind.LOGIN_AUTH -> R.string.chat_notice_login_auth
    ChatNoticeKind.LOGIN_NOT_FOUND -> R.string.chat_notice_login_not_found
    ChatNoticeKind.LOGIN_NETWORK -> R.string.chat_notice_login_network
    ChatNoticeKind.LOGIN_KEYSTORE -> R.string.chat_notice_login_keystore
    ChatNoticeKind.FEED_ACCOUNT_MISSING -> R.string.chat_notice_feed_missing
    ChatNoticeKind.NOTES_ACCOUNT_MISSING -> R.string.chat_notice_notes_missing
    ChatNoticeKind.AUTH_LOST -> R.string.chat_notice_auth_lost
    ChatNoticeKind.ROOM_GONE -> R.string.chat_notice_room_gone
    ChatNoticeKind.LOAD_FAILED -> R.string.chat_notice_load_failed
    ChatNoticeKind.SEND_TOO_LONG -> R.string.chat_notice_send_too_long
    ChatNoticeKind.SEND_EMPTY -> R.string.chat_notice_send_empty
    ChatNoticeKind.READ_ONLY -> R.string.chat_notice_read_only
    ChatNoticeKind.ATTACH_TOO_LARGE -> R.string.chat_notice_attach_too_large
    ChatNoticeKind.ATTACH_FAILED -> R.string.chat_notice_attach_failed
    ChatNoticeKind.NOTIFY_DENIED -> R.string.chat_notice_notify_denied
    ChatNoticeKind.FORWARDED -> R.string.chat_notice_forwarded
    ChatNoticeKind.FORWARD_FAILED -> R.string.chat_notice_forward_failed
    ChatNoticeKind.REACTION_FAILED -> R.string.chat_notice_reaction_failed
    ChatNoticeKind.REMINDER_CREATED -> R.string.chat_notice_reminder_created
    ChatNoticeKind.REMINDER_FAILED -> R.string.chat_notice_reminder_failed
}

@Composable
private fun NotifySwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(label, color = WeatherText, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = ChatDialogColor,
                checkedTrackColor = WeatherText,
                uncheckedThumbColor = WeatherText,
                uncheckedTrackColor = Color(0x33FFFFFF),
                uncheckedBorderColor = Color(0x99FFFFFF),
            ),
        )
    }
}

@Composable
private fun DialogButton(text: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, colors = ButtonDefaults.textButtonColors(contentColor = WeatherText)) { Text(text) }
}

@Composable
private fun LoginDialog(viewModel: ChatViewModel, onDismiss: () -> Unit) {
    var server by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ChatDialogColor,
        titleContentColor = WeatherText,
        textContentColor = WeatherTextMuted,
        title = { Text(stringResource(R.string.chat_connect)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.chat_login_hint), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(server, { server = it }, singleLine = true, label = { Text(stringResource(R.string.chat_login_server)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), colors = filesFieldColors())
                OutlinedTextField(user, { user = it }, singleLine = true, label = { Text(stringResource(R.string.chat_login_user)) }, colors = filesFieldColors())
                OutlinedTextField(
                    password,
                    { password = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.chat_login_password)) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    colors = filesFieldColors(),
                )
                PodcastTextButton(stringResource(R.string.chat_login_from_feed)) { viewModel.loginWithFeedAccount(); onDismiss() }
                PodcastTextButton(stringResource(R.string.chat_login_from_notes)) { viewModel.loginWithNotesAccount(); onDismiss() }
            }
        },
        confirmButton = { DialogButton(stringResource(R.string.chat_login_action)) { viewModel.login(server, user, password); onDismiss() } },
        dismissButton = { DialogButton(stringResource(R.string.chat_cancel), onDismiss) },
    )
}

@Composable
private fun AccountDialog(state: ChatUiState, viewModel: ChatViewModel, onDismiss: () -> Unit) {
    val account = state.account
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.setNotifications(true) else viewModel.notifyPermissionDenied()
    }
    fun toggleNotifications(on: Boolean) {
        val missing = Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        when {
            !on -> viewModel.setNotifications(false)
            missing -> permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            else -> viewModel.setNotifications(true)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ChatDialogColor,
        titleContentColor = WeatherText,
        textContentColor = WeatherTextMuted,
        title = { Text(stringResource(R.string.chat_account)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (account != null) {
                    Text(account.server, color = WeatherText)
                    Text(account.user, color = WeatherText)
                    NotifySwitch(stringResource(R.string.chat_notify_enable), state.notifyEnabled, ::toggleNotifications)
                    if (state.notifyEnabled) {
                        NotifySwitch(stringResource(R.string.chat_notify_show_text), state.notifyShowText, viewModel::setNotifyShowText)
                        DialogButton(stringResource(R.string.chat_notify_check), viewModel::checkNotificationsNow)
                        state.notifyCheck?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = WeatherText) }
                        if (!ChatNotifier.canNotify(context)) {
                            Text(stringResource(R.string.chat_notify_blocked), color = ChatError, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Text(stringResource(R.string.chat_notify_hint), style = MaterialTheme.typography.bodySmall)
                }
                Text(stringResource(R.string.chat_account_hint), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { DialogButton(stringResource(R.string.chat_close), onDismiss) },
        dismissButton = { DialogButton(stringResource(R.string.chat_logout)) { viewModel.logout(); onDismiss() } },
    )
}

// endregion

// region Zeitangaben ---------------------------------------------------------------------------------

private fun formatChatClock(seconds: Long, locale: Locale): String =
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
        .format(Instant.ofEpochSecond(seconds).atZone(ZoneId.systemDefault()))

private fun formatChatDay(seconds: Long, locale: Locale): String =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
        .format(Instant.ofEpochSecond(seconds).atZone(ZoneId.systemDefault()))

/** Heute die Uhrzeit, sonst das Datum. */
private fun formatChatTime(seconds: Long, locale: Locale): String {
    val at = Instant.ofEpochSecond(seconds).atZone(ZoneId.systemDefault())
    return if (at.toLocalDate() == LocalDate.now(at.zone)) {
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(at)
    } else {
        DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(locale).format(at)
    }
}

// endregion
