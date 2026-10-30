// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.ui

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.tux4us.miriquidilauncher.R
import io.github.tux4us.miriquidilauncher.feed.FeedHtml
import io.github.tux4us.miriquidilauncher.feed.FeedImages
import io.github.tux4us.miriquidilauncher.feed.FeedItem
import io.github.tux4us.miriquidilauncher.feed.FeedLogic
import io.github.tux4us.miriquidilauncher.feed.FeedProblem
import io.github.tux4us.miriquidilauncher.feed.FeedText
import io.github.tux4us.miriquidilauncher.feed.FeedUiState
import io.github.tux4us.miriquidilauncher.feed.ImportSummary
import io.github.tux4us.miriquidilauncher.feed.LocalFeed
import io.github.tux4us.miriquidilauncher.feed.LocalFeeds
import io.github.tux4us.miriquidilauncher.feed.FeedViewModel
import io.github.tux4us.miriquidilauncher.feed.ReaderColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

// Feste Farben wie auf Wetter- und Kalenderseite: Die Seite liegt über beliebigen Hintergrundbildern.
private val PageScrim = Color(0x66000000)
private val DialogColor = Color(0xFF141A22)
private val UnreadDot = Color(0xFF8AB4F8)
private val ErrorColor = Color(0xFFFF9E9E)

// Die Leseansicht ist eine geschlossene, dunkle Fläche; sie ist unabhängig vom Hintergrundbild gut lesbar.
private val ReaderBackground = Color(0xFF141A22)
private val ReaderColorsDark = ReaderColors(
    background = "#141a22",
    text = "#e8eaed",
    muted = "#a9b0b8",
    link = "#8ab4f8",
)

/** Platz unten, damit der Home-Button keine Karte überdeckt. */
private val HomeButtonClearance = 88.dp

/** Zeichenanzahl am Anfang des Artikels, die für Vorschautext und Vorschaubild ausgewertet wird. */
private const val PREVIEW_SCAN_CHARS = 12_000

/**
 * FeedReader: zeigt Artikel als Cards. Tippen öffnet den Artikel in einer Leseansicht. Die Artikel stammen aus
 * der Nextcloud-News-App oder, ohne Nextcloud, aus lokal abonnierten Feeds (Adresse, OPML- oder JSON-Feed-Import).
 * [active] ist `true`, solange die Seite sichtbar ist; dann werden neue Artikel geladen.
 */
@Composable
fun FeedScreen(viewModel: FeedViewModel, active: Boolean, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showAccount by remember { mutableStateOf(false) }
    // Der Systemdialog zur Dateiauswahl braucht keine Berechtigung; gelesen wird nur die gewählte Datei.
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importFile(uri)
    }
    val pickFile = { filePicker.launch(arrayOf("*/*")) }

    LaunchedEffect(active, state.account, state.ready) {
        if (active) viewModel.refresh() else viewModel.close()
    }
    // Zurück schließt zuerst den Artikel; erst danach (Handler in LauncherScreen) geht es zur Startseite.
    BackHandler(enabled = active && state.openItemId != null) { viewModel.close() }

    if (showAccount && state.account != null) {
        AccountDialog(
            state = state,
            onLoadImages = viewModel::setLoadImages,
            onLogout = {
                showAccount = false
                viewModel.logout()
            },
            onDismiss = { showAccount = false },
        )
    } else if (showAccount && state.localMode) {
        SourcesDialog(
            state = state,
            onAdd = viewModel::addFeedUrl,
            onPickFile = pickFile,
            onRemove = viewModel::removeFeed,
            onLoadImages = viewModel::setLoadImages,
            onNextcloud = {
                showAccount = false
                viewModel.requestNextcloudLogin()
            },
            onEdit = viewModel::clearSourceMessages,
            onDismiss = {
                showAccount = false
                viewModel.clearSourceMessages()
            },
        )
    }

    CompositionLocalProvider(LocalContentColor provides WeatherText) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(PageScrim)
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            val article = state.openItem
            when {
                !state.ready -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = WeatherText)
                state.showSetup -> LoginForm(
                    busy = state.loginBusy,
                    problem = state.loginProblem,
                    onLogin = viewModel::login,
                    onEdit = viewModel::clearLoginProblem,
                    // Im lokalen Modus kann die Anmeldung wieder verlassen werden.
                    onCancel = if (state.localFeeds.isNotEmpty()) viewModel::cancelNextcloudLogin else null,
                ) {
                    if (state.localFeeds.isEmpty()) {
                        LocalSourceTools(
                            busy = state.sourceBusy,
                            problem = state.sourceProblem,
                            summary = state.importSummary,
                            onAdd = viewModel::addFeedUrl,
                            onPickFile = pickFile,
                            onEdit = viewModel::clearSourceMessages,
                        )
                    }
                }
                article != null -> ArticleView(
                    item = article,
                    feedTitle = state.feeds[article.feedId]?.title.orEmpty(),
                    loadImages = state.loadImages,
                    onBack = viewModel::close,
                )
                else -> FeedList(
                    state = state,
                    onRefresh = { viewModel.refresh(force = true) },
                    onAccount = { showAccount = true },
                    onUnreadOnly = viewModel::setUnreadOnly,
                    onSelectFeed = viewModel::selectFeed,
                    onOpen = viewModel::open,
                    onLoadMore = viewModel::loadMore,
                )
            }
        }
    }
}

// region Anmeldung -----------------------------------------------------------------------------

@Composable
private fun LoginForm(
    busy: Boolean,
    problem: FeedProblem?,
    onLogin: (server: String, user: String, password: String) -> Unit,
    onEdit: () -> Unit,
    onCancel: (() -> Unit)?,
    extra: @Composable () -> Unit,
) {
    var server by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = WeatherText,
        unfocusedTextColor = WeatherText,
        focusedBorderColor = WeatherText,
        unfocusedBorderColor = WeatherTextMuted,
        focusedLabelColor = WeatherText,
        unfocusedLabelColor = WeatherTextMuted,
        cursorColor = WeatherText,
        focusedPlaceholderColor = WeatherTextMuted,
        unfocusedPlaceholderColor = WeatherTextMuted,
    )
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.feed_login_title), style = MaterialTheme.typography.titleLarge)
        Text(
            stringResource(R.string.feed_login_text),
            style = MaterialTheme.typography.bodyMedium,
            color = WeatherTextMuted,
        )
        OutlinedTextField(
            value = server,
            onValueChange = { server = it; onEdit() },
            label = { Text(stringResource(R.string.feed_server_label)) },
            placeholder = { Text("cloud.example.org") },
            singleLine = true,
            enabled = !busy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            colors = fieldColors,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = user,
            onValueChange = { user = it; onEdit() },
            label = { Text(stringResource(R.string.feed_user_label)) },
            singleLine = true,
            enabled = !busy,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
            colors = fieldColors,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it; onEdit() },
            label = { Text(stringResource(R.string.feed_password_label)) },
            singleLine = true,
            enabled = !busy,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            colors = fieldColors,
            modifier = Modifier.fillMaxWidth(),
        )
        problem?.let {
            Text(stringResource(problemText(it)), color = ErrorColor, style = MaterialTheme.typography.bodyMedium)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { onLogin(server, user, password) }, enabled = !busy) {
                Text(stringResource(R.string.feed_login_action))
            }
            if (onCancel != null) {
                TextButton(onClick = onCancel, colors = ButtonDefaults.textButtonColors(contentColor = WeatherText)) {
                    Text(stringResource(R.string.feed_cancel))
                }
            }
            if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = WeatherText)
        }
        extra()
        Spacer(Modifier.size(HomeButtonClearance))
    }
}

/**
 * Eingabefeld für Feed-Adressen und Dateiimport. Wird auf der Startseite des Scopes (ohne Konto) und im
 * Dialog der Feed-Quellen verwendet.
 */
@Composable
private fun LocalSourceTools(
    busy: Boolean,
    problem: FeedProblem?,
    summary: ImportSummary?,
    onAdd: (String) -> Unit,
    onPickFile: () -> Unit,
    onEdit: () -> Unit,
) {
    var address by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HorizontalDivider(color = WeatherTextMuted.copy(alpha = 0.4f))
        Text(stringResource(R.string.feed_local_title), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.feed_local_text),
            style = MaterialTheme.typography.bodyMedium,
            color = WeatherTextMuted,
        )
        OutlinedTextField(
            value = address,
            onValueChange = { address = it; onEdit() },
            label = { Text(stringResource(R.string.feed_add_label)) },
            placeholder = { Text("example.org/feed.xml") },
            singleLine = true,
            enabled = !busy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
            colors = feedFieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { onAdd(address) }, enabled = !busy) { Text(stringResource(R.string.feed_add_action)) }
            if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = WeatherText)
        }
        OutlinedButton(
            onClick = onPickFile,
            enabled = !busy,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = WeatherText),
        ) { Text(stringResource(R.string.feed_import_file)) }
        problem?.let {
            Text(stringResource(problemText(it)), color = ErrorColor, style = MaterialTheme.typography.bodyMedium)
        }
        summary?.let { SummaryText(it) }
    }
}

@Composable
private fun SummaryText(summary: ImportSummary) {
    val parts = buildList {
        if (summary.added > 0) add(stringResource(R.string.feed_import_added, summary.added))
        if (summary.duplicates > 0) add(stringResource(R.string.feed_import_duplicates, summary.duplicates))
        if (summary.skipped > 0) add(stringResource(R.string.feed_import_skipped, summary.skipped))
        if (summary.overLimit > 0) add(stringResource(R.string.feed_import_over_limit, summary.overLimit))
    }
    if (parts.isNotEmpty()) {
        Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = WeatherTextMuted)
    }
}

@Composable
private fun feedFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = WeatherText,
    unfocusedTextColor = WeatherText,
    focusedBorderColor = WeatherText,
    unfocusedBorderColor = WeatherTextMuted,
    focusedLabelColor = WeatherText,
    unfocusedLabelColor = WeatherTextMuted,
    cursorColor = WeatherText,
    focusedPlaceholderColor = WeatherTextMuted,
    unfocusedPlaceholderColor = WeatherTextMuted,
)

/** Verwaltung der lokalen Abos: hinzufügen, importieren, entfernen, Bilder, Wechsel zu Nextcloud. */
@Composable
private fun SourcesDialog(
    state: FeedUiState,
    onAdd: (String) -> Unit,
    onPickFile: () -> Unit,
    onRemove: (Long) -> Unit,
    onLoadImages: (Boolean) -> Unit,
    onNextcloud: () -> Unit,
    onEdit: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DialogColor,
        titleContentColor = WeatherText,
        textContentColor = WeatherText,
        title = { Text(stringResource(R.string.feed_sources_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    stringResource(R.string.feed_sources_list, state.localFeeds.size),
                    style = MaterialTheme.typography.titleSmall,
                )
                Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
                    state.localFeeds.forEach { feed ->
                        SourceRow(feed, failed = feed.id in state.failedFeedIds, onRemove = { onRemove(feed.id) })
                    }
                }
                LocalSourceTools(
                    busy = state.sourceBusy,
                    problem = state.sourceProblem,
                    summary = state.importSummary,
                    onAdd = onAdd,
                    onPickFile = onPickFile,
                    onEdit = onEdit,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.feed_load_images))
                        Text(
                            stringResource(R.string.feed_load_images_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = WeatherTextMuted,
                        )
                    }
                    Switch(checked = state.loadImages, onCheckedChange = onLoadImages)
                }
                TextButton(onClick = onNextcloud, colors = ButtonDefaults.textButtonColors(contentColor = WeatherText)) {
                    Text(stringResource(R.string.feed_use_nextcloud))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, colors = ButtonDefaults.textButtonColors(contentColor = WeatherText)) {
                Text(stringResource(R.string.feed_close))
            }
        },
    )
}

@Composable
private fun SourceRow(feed: LocalFeed, failed: Boolean, onRemove: () -> Unit) {
    val title = LocalFeeds.displayTitle(feed)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            val detail = when {
                failed -> stringResource(R.string.feed_source_failed)
                feed.isStatic -> stringResource(R.string.feed_source_static)
                else -> feed.url.removePrefix("https://")
            }
            Text(
                detail,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = if (failed) ErrorColor else WeatherTextMuted,
            )
        }
        IconButton(onClick = onRemove) {
            Icon(painterResource(R.drawable.ic_close), stringResource(R.string.feed_source_remove, title))
        }
    }
}

@Composable
private fun AccountDialog(
    state: FeedUiState,
    onLoadImages: (Boolean) -> Unit,
    onLogout: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DialogColor,
        titleContentColor = WeatherText,
        textContentColor = WeatherText,
        title = { Text(stringResource(R.string.feed_account_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                state.account?.let {
                    Text(stringResource(R.string.feed_account_server, it.baseUrl), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.feed_account_user, it.user), style = MaterialTheme.typography.bodyMedium)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.feed_load_images))
                        Text(
                            stringResource(R.string.feed_load_images_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = WeatherTextMuted,
                        )
                    }
                    Switch(checked = state.loadImages, onCheckedChange = onLoadImages)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, colors = ButtonDefaults.textButtonColors(contentColor = WeatherText)) {
                Text(stringResource(R.string.feed_close))
            }
        },
        dismissButton = {
            TextButton(onClick = onLogout, colors = ButtonDefaults.textButtonColors(contentColor = ErrorColor)) {
                Text(stringResource(R.string.feed_logout))
            }
        },
    )
}

// endregion

// region Cardlist ------------------------------------------------------------------------------

@Composable
private fun FeedList(
    state: FeedUiState,
    onRefresh: () -> Unit,
    onAccount: () -> Unit,
    onUnreadOnly: (Boolean) -> Unit,
    onSelectFeed: (Long?) -> Unit,
    onOpen: (Long) -> Unit,
    onLoadMore: () -> Unit,
) {
    var showFeeds by remember { mutableStateOf(false) }
    val visible = state.visibleItems
    val context = LocalContext.current
    val now = remember(state.fetchedAtMillis, visible.size) { System.currentTimeMillis() }
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.feed_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            if (state.refreshing) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = WeatherText)
            }
            IconButton(onClick = onRefresh, enabled = !state.refreshing) {
                Icon(painterResource(R.drawable.ic_refresh), stringResource(R.string.feed_refresh))
            }
            IconButton(onClick = onAccount) {
                Icon(
                    painterResource(R.drawable.ic_settings),
                    stringResource(if (state.account == null) R.string.feed_sources_title else R.string.feed_account),
                )
            }
        }
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            listOf(true to R.string.feed_filter_unread, false to R.string.feed_filter_all).forEach { (unreadOnly, label) ->
                FilterChip(
                    selected = state.unreadOnly == unreadOnly,
                    onClick = { onUnreadOnly(unreadOnly) },
                    label = { Text(stringResource(label)) },
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = WeatherCard,
                        labelColor = WeatherText,
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                )
            }
            Spacer(Modifier.weight(1f))
            if (state.fetchedAtMillis > 0L) {
                Text(
                    stringResource(
                        R.string.feed_updated,
                        DateUtils.getRelativeTimeSpanString(state.fetchedAtMillis, now, DateUtils.MINUTE_IN_MILLIS).toString(),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = WeatherTextMuted,
                    maxLines = 1,
                )
            }
        }
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val selected = state.selectedFeed
            FilterChip(
                selected = selected != null,
                onClick = { showFeeds = true },
                enabled = state.feeds.isNotEmpty(),
                label = {
                    Text(
                        selected?.title?.ifBlank { null } ?: stringResource(R.string.feed_subscriptions),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 220.dp),
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = WeatherCard,
                    labelColor = WeatherText,
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            )
            if (selected != null) {
                TextButton(
                    onClick = { onSelectFeed(null) },
                    colors = ButtonDefaults.textButtonColors(contentColor = WeatherText),
                ) { Text(stringResource(R.string.feed_subscription_all)) }
            }
        }
        state.problem?.let { problem ->
            ProblemBanner(problem, onRetry = onRefresh, onAccount = onAccount)
        }
        if (showFeeds) {
            SubscriptionsDialog(
                state = state,
                onSelect = { id ->
                    showFeeds = false
                    onSelectFeed(id)
                },
                onDismiss = { showFeeds = false },
            )
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = HomeButtonClearance),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(visible, key = { it.id }) { item ->
                FeedCard(
                    item = item,
                    feedTitle = state.feeds[item.feedId]?.title.orEmpty(),
                    loadImages = state.loadImages,
                    now = now,
                    context = context,
                    onClick = { onOpen(item.id) },
                )
            }
            if (visible.isEmpty() && !state.refreshing && state.problem == null) {
                item {
                    Text(
                        stringResource(if (state.unreadOnly) R.string.feed_empty_unread else R.string.feed_empty_all),
                        color = WeatherTextMuted,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                    )
                }
            }
            if (visible.isNotEmpty() && state.canLoadMore) {
                item {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        if (state.loadingMore) {
                            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = WeatherText)
                        } else {
                            TextButton(
                                onClick = onLoadMore,
                                colors = ButtonDefaults.textButtonColors(contentColor = WeatherText),
                            ) { Text(stringResource(R.string.feed_load_more)) }
                        }
                    }
                }
            }
        }
    }
}

/** Liste der Abonnements mit der Zahl ungelesener Artikel (bezogen auf die geladenen Artikel). */
@Composable
private fun SubscriptionsDialog(state: FeedUiState, onSelect: (Long?) -> Unit, onDismiss: () -> Unit) {
    val counts = remember(state.items) { FeedLogic.unreadCounts(state.items) }
    val feeds = remember(state.feeds) {
        state.feeds.values.sortedBy { it.title.lowercase(Locale.getDefault()) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.feed_subscriptions)) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                SubscriptionRow(
                    title = stringResource(R.string.feed_subscription_all),
                    unread = counts.values.sum(),
                    selected = state.selectedFeedId == null,
                    onClick = { onSelect(null) },
                )
                HorizontalDivider()
                feeds.forEach { feed ->
                    SubscriptionRow(
                        title = feed.title.ifBlank { feed.link.orEmpty() },
                        unread = counts[feed.id] ?: 0,
                        selected = state.selectedFeedId == feed.id,
                        onClick = { onSelect(feed.id) },
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.feed_close)) } },
    )
}

@Composable
private fun SubscriptionRow(title: String, unread: Int, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (unread > 0) {
            Text(
                unread.toString(),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    }
}

@Composable
private fun ProblemBanner(problem: FeedProblem, onRetry: () -> Unit, onAccount: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .glass(RoundedCornerShape(16.dp))
            .padding(start = 12.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(problemText(problem)),
            style = MaterialTheme.typography.bodySmall,
            color = ErrorColor,
            modifier = Modifier.weight(1f),
        )
        // Bei Anmeldeproblemen führt die Schaltfläche zum Konto, bei teilweise fehlgeschlagenen Feeds zu den Quellen.
        val label = when (problem) {
            FeedProblem.AUTH -> R.string.feed_account
            FeedProblem.PARTIAL -> R.string.feed_sources_title
            else -> R.string.feed_retry
        }
        TextButton(
            onClick = if (problem == FeedProblem.AUTH || problem == FeedProblem.PARTIAL) onAccount else onRetry,
            colors = ButtonDefaults.textButtonColors(contentColor = WeatherText),
        ) {
            Text(stringResource(label))
        }
    }
}

@Composable
private fun FeedCard(
    item: FeedItem,
    feedTitle: String,
    loadImages: Boolean,
    now: Long,
    context: Context,
    onClick: () -> Unit,
) {
    // Vorschau nur aus dem Anfang des Artikels: schnell, auch bei sehr langen Texten.
    val preview = remember(item.id) {
        val head = FeedText.head(item.body, PREVIEW_SCAN_CHARS)
        FeedText.excerpt(head, 220) to FeedText.firstImageUrl(head)
    }
    val (excerpt, imageUrl) = preview
    val titleColor = if (item.unread) WeatherText else WeatherTextMuted
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .glass(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (item.unread) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(UnreadDot))
                }
                Text(
                    text = listOf(feedTitle, relativeDate(context, item.publishedSeconds, now)).filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = WeatherTextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = item.title.ifBlank { stringResource(R.string.feed_untitled) },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (item.unread) FontWeight.Bold else FontWeight.Normal,
                color = titleColor,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            if (excerpt.isNotBlank()) {
                Text(
                    text = excerpt,
                    style = MaterialTheme.typography.bodySmall,
                    color = WeatherTextMuted,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (loadImages && imageUrl != null) {
            Thumbnail(imageUrl, Modifier.size(84.dp))
        }
    }
}

@Composable
private fun Thumbnail(url: String, modifier: Modifier) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, url) { value = FeedImages.load(url) }
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(Color(0x22FFFFFF))) {
        bitmap?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private fun relativeDate(context: Context, seconds: Long, now: Long): String {
    if (seconds <= 0L) return ""
    val millis = seconds * 1000L
    return if (now - millis in 0..(7 * DateUtils.DAY_IN_MILLIS)) {
        DateUtils.getRelativeTimeSpanString(millis, now, DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()
    } else {
        DateUtils.formatDateTime(context, millis, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_SHOW_YEAR)
    }
}

private fun problemText(problem: FeedProblem): Int = when (problem) {
    FeedProblem.NETWORK -> R.string.feed_problem_network
    FeedProblem.AUTH -> R.string.feed_problem_auth
    FeedProblem.NOT_FOUND -> R.string.feed_problem_not_found
    FeedProblem.SERVER -> R.string.feed_problem_server
    FeedProblem.DATA -> R.string.feed_problem_data
    FeedProblem.STORAGE -> R.string.feed_problem_storage
    FeedProblem.URL_EMPTY -> R.string.feed_problem_url_empty
    FeedProblem.URL_NOT_HTTPS -> R.string.feed_problem_url_not_https
    FeedProblem.URL_INVALID -> R.string.feed_problem_url_invalid
    FeedProblem.INPUT_MISSING -> R.string.feed_problem_input_missing
    FeedProblem.FEED_URL_EMPTY -> R.string.feed_problem_feed_url_empty
    FeedProblem.FEED_URL_NOT_HTTPS -> R.string.feed_problem_feed_url_not_https
    FeedProblem.FEED_URL_INVALID -> R.string.feed_problem_feed_url_invalid
    FeedProblem.FEED_NOT_FOUND -> R.string.feed_problem_feed_not_found
    FeedProblem.NOT_A_FEED -> R.string.feed_problem_not_a_feed
    FeedProblem.FILE_READ -> R.string.feed_problem_file_read
    FeedProblem.ALREADY_ADDED -> R.string.feed_problem_already_added
    FeedProblem.LIMIT -> R.string.feed_problem_limit
    FeedProblem.PARTIAL -> R.string.feed_problem_partial
}

// endregion

// region Leseansicht ---------------------------------------------------------------------------

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun ArticleView(item: FeedItem, feedTitle: String, loadImages: Boolean, onBack: () -> Unit) {
    val context = LocalContext.current
    val locale = remember { Locale.getDefault() }
    val untitled = stringResource(R.string.feed_untitled)
    val document = remember(item.id, loadImages, feedTitle) {
        val published = if (item.publishedSeconds > 0L) {
            DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(locale)
                .format(Instant.ofEpochSecond(item.publishedSeconds).atZone(ZoneId.systemDefault()))
        } else {
            null
        }
        FeedHtml.readerDocument(
            title = item.title.ifBlank { untitled },
            meta = listOfNotNull(feedTitle.ifBlank { null }, item.author, published).joinToString(" · "),
            bodyHtml = item.body,
            colors = ReaderColorsDark,
            loadImages = loadImages,
        )
    }
    // Relative Adressen im Artikel beziehen sich auf die Seite des Artikels (nur HTTPS).
    val baseUrl = item.url?.takeIf { it.startsWith("https://", ignoreCase = true) }
    val webViewHolder = remember { arrayOfNulls<WebView>(1) }
    DisposableEffect(Unit) {
        onDispose {
            webViewHolder[0]?.apply {
                stopLoading()
                (parent as? ViewGroup)?.removeView(this)
                destroy()
            }
            webViewHolder[0] = null
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.padding(start = 4.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(painterResource(R.drawable.ic_chevron_left), stringResource(R.string.feed_back))
            }
            Text(
                text = feedTitle,
                style = MaterialTheme.typography.labelLarge,
                color = WeatherTextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            item.url?.let { url ->
                TextButton(
                    onClick = { openExternally(context, url) },
                    colors = ButtonDefaults.textButtonColors(contentColor = WeatherText),
                ) { Text(stringResource(R.string.feed_open_browser)) }
            }
        }
        AndroidView(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            factory = { ctx ->
                WebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    setBackgroundColor(ReaderBackground.toArgb())
                    settings.apply {
                        // Der Artikel ist fremder Inhalt: kein JavaScript, kein Zugriff auf Dateien oder Speicher.
                        javaScriptEnabled = false
                        allowFileAccess = false
                        allowContentAccess = false
                        domStorageEnabled = false
                        javaScriptCanOpenWindowsAutomatically = false
                        setSupportMultipleWindows(false)
                        setSupportZoom(false)
                        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        cacheMode = WebSettings.LOAD_DEFAULT
                    }
                    // Links öffnen im Browser; die Leseansicht navigiert nie selbst weg.
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            if (request.isForMainFrame) openExternally(view.context, request.url.toString())
                            return true
                        }
                    }
                    webViewHolder[0] = this
                }
            },
            update = { view ->
                view.settings.blockNetworkImage = !loadImages
                if (view.tag != document) {
                    view.tag = document
                    view.loadDataWithBaseURL(baseUrl, document, "text/html", "utf-8", null)
                }
            },
        )
    }
}

/** Öffnet [url] im Browser, sofern es eine Web- oder Mail-Adresse ist. */
private fun openExternally(context: Context, url: String) {
    val uri = Uri.parse(url)
    if (uri.scheme?.lowercase(Locale.ROOT) !in setOf("http", "https", "mailto")) return
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (ignored: ActivityNotFoundException) {
        Toast.makeText(context, context.getString(R.string.launch_failed), Toast.LENGTH_SHORT).show()
    }
}

// endregion
