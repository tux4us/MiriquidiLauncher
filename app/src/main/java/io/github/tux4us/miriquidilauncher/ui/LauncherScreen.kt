// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.text.format.DateFormat
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import io.github.tux4us.miriquidilauncher.R
import io.github.tux4us.miriquidilauncher.calendar.AgendaUiState
import io.github.tux4us.miriquidilauncher.calendar.AgendaView
import io.github.tux4us.miriquidilauncher.calendar.AgendaViewModel
import io.github.tux4us.miriquidilauncher.calendar.CalendarViewModel
import io.github.tux4us.miriquidilauncher.feed.FeedViewModel
import io.github.tux4us.miriquidilauncher.markets.MarketsViewModel
import io.github.tux4us.miriquidilauncher.files.FilesViewModel
import io.github.tux4us.miriquidilauncher.gallery.GalleryViewModel
import io.github.tux4us.miriquidilauncher.notes.NotesViewModel
import io.github.tux4us.miriquidilauncher.podcasts.PodcastsViewModel
import io.github.tux4us.miriquidilauncher.weather.WeatherViewModel
import androidx.core.content.ContextCompat
import io.github.tux4us.miriquidilauncher.data.AppEntry
import io.github.tux4us.miriquidilauncher.data.CallHelper
import io.github.tux4us.miriquidilauncher.data.ContactPicker
import io.github.tux4us.miriquidilauncher.data.FavoriteContact
import io.github.tux4us.miriquidilauncher.quickmenu.Bounds
import io.github.tux4us.miriquidilauncher.quickmenu.QuickMenuEffect
import io.github.tux4us.miriquidilauncher.quickmenu.QuickMenuEvent
import io.github.tux4us.miriquidilauncher.quickmenu.QuickMenuLayout
import io.github.tux4us.miriquidilauncher.quickmenu.QuickMenuMachine
import io.github.tux4us.miriquidilauncher.quickmenu.QuickMenuMetrics
import io.github.tux4us.miriquidilauncher.quickmenu.QuickMenuState
import io.github.tux4us.miriquidilauncher.quickmenu.isOpen
import io.github.tux4us.miriquidilauncher.search.ActionResult
import io.github.tux4us.miriquidilauncher.calculator.CalculatorViewModel
import io.github.tux4us.miriquidilauncher.dogs.DogLogic
import io.github.tux4us.miriquidilauncher.dogs.DogViewModel
import io.github.tux4us.miriquidilauncher.dogs.DueEntry
import io.github.tux4us.miriquidilauncher.scopes.ScopePages
import io.github.tux4us.miriquidilauncher.scopes.ScopeVisibility
import io.github.tux4us.miriquidilauncher.camera.CameraViewModel
import io.github.tux4us.miriquidilauncher.system.SystemViewModel
import io.github.tux4us.miriquidilauncher.search.AppResult
import io.github.tux4us.miriquidilauncher.search.ContactResult
import io.github.tux4us.miriquidilauncher.search.ResultAction
import io.github.tux4us.miriquidilauncher.search.SearchResult
import java.util.Date

private const val PAGE_COUNT = 2
private const val PAGE_HOME = 0
private const val SIDE_PAGE_LAUNCHER = ScopePages.LAUNCHER

private val Scrim = Color.Black.copy(alpha = 0.55f)
private val TextPrimary = Color.White
private val TextSecondary = Color.White.copy(alpha = 0.7f)

/**
 * Hauptbildschirm mit zwei vertikal angeordneten Seiten:
 * 0 = Home (Uhr, Datum, Scopes-Button, Suchfeld über die ganze Breite am unteren Rand), 1 = App-Grid.
 * Seitlich liegen die Scopes (siehe [Scope]): Einstellungen, Startseite, App-Favoriten, Kontakt-Favoriten,
 * App-Gruppen, Wetter, Kalender, Feed, System, Kurse, Rechner.
 *
 * Zurück-Taste und Home-Taste führen immer zuerst zur Home-Seite; eine laufende Suche wird dabei
 * geleert und ein offenes Schnellmenü geschlossen. Während einer Suche oder bei offenem Schnellmenü
 * ist das Blättern gesperrt.
 *
 * Das Scopes-Menü (Raster, das sich durch Tippen auf den Button öffnet) ist in
 * [io.github.tux4us.miriquidilauncher.quickmenu] beschrieben. Zustand und Ereignisverarbeitung liegen hier, weil das
 * Overlay über der gesamten Fläche liegen muss.
 */
@Composable
fun LauncherScreen(
    viewModel: LauncherViewModel,
    weatherViewModel: WeatherViewModel,
    calendarViewModel: CalendarViewModel,
    feedViewModel: FeedViewModel,
    marketsViewModel: MarketsViewModel,
    podcastsViewModel: PodcastsViewModel,
    filesViewModel: FilesViewModel,
    galleryViewModel: GalleryViewModel,
    notesViewModel: NotesViewModel,
    systemViewModel: SystemViewModel,
    calculatorViewModel: CalculatorViewModel,
    cameraViewModel: CameraViewModel,
    agendaViewModel: AgendaViewModel,
    dogViewModel: DogViewModel,
    settingsViewModel: SettingsViewModel,
    contactsGranted: Boolean,
    onRequestContacts: () -> Unit,
) {
    val context = LocalContext.current
    val apps by viewModel.apps.collectAsStateWithLifecycle()
    val favorites by viewModel.favorites.collectAsStateWithLifecycle()
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    val favoriteKeys by viewModel.favoriteKeys.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val searchState by viewModel.searchState.collectAsStateWithLifecycle()

    val pagerState = rememberPagerState(pageCount = { PAGE_COUNT })
    val feedArticleOpen by feedViewModel.articleOpen.collectAsStateWithLifecycle()
    val podcastDetailOpen by podcastsViewModel.detailOpen.collectAsStateWithLifecycle()
    val cameraBusy by cameraViewModel.busy.collectAsStateWithLifecycle()
    val agendaState by agendaViewModel.state.collectAsStateWithLifecycle()
    val dogState by dogViewModel.state.collectAsStateWithLifecycle()
    val dogDue = remember(dogState.data) { DogLogic.upcoming(dogState.data.dogs, java.time.LocalDate.now(), DogLogic.HOME_DAYS) }
    val hiddenScopes by settingsViewModel.hiddenScopes.collectAsStateWithLifecycle()
    val scopeOrder by settingsViewModel.scopeOrder.collectAsStateWithLifecycle()
    val visibleScopes = remember(hiddenScopes, scopeOrder) {
        ScopeVisibility.visible(scopeOrder, hiddenScopes, PinnedScopeNames).map { Scope.valueOf(it) }
    }
    val pages = remember(visibleScopes) { ScopePages(visibleScopes.map { it.name }) }
    val sidePagerState = rememberPagerState(initialPage = SIDE_PAGE_LAUNCHER, pageCount = { pages.count })
    val currentScope = pages.idAt(sidePagerState.currentPage)?.let { Scope.valueOf(it) }
    // Werden Scopes ein- oder ausgeblendet, verschieben sich die Seiten; die gezeigte Scope bleibt stehen.
    val pageTracker = remember { PageTracker(pages) }
    if (pageTracker.pages != pages) {
        pageTracker.target = pages.pageOf(pageTracker.pages.idAt(sidePagerState.currentPage).orEmpty())
        pageTracker.pages = pages
    }
    LaunchedEffect(pages) {
        val target = pageTracker.target
        pageTracker.target = null
        if (target != null && target != sidePagerState.currentPage) sidePagerState.scrollToPage(target)
    }
    fun pageOf(target: Scope): Int? = pages.pageOf(target.name)
    var scannerOpen by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val density = LocalDensity.current
    val launchFailedMessage = stringResource(R.string.launch_failed)

    val contacts by viewModel.favoriteContacts.collectAsStateWithLifecycle()
    val callHelper = remember { CallHelper(context) }

    var menuState by remember { mutableStateOf<QuickMenuState>(QuickMenuState.Closed) }
    var activeKind by remember { mutableStateOf(QuickMenuKind.Scopes) }
    val menuAnchors = remember { mutableStateMapOf<QuickMenuKind, Bounds>() }
    var rootSize by remember { mutableStateOf(IntSize.Zero) }
    var pendingCall by remember { mutableStateOf<FavoriteContact?>(null) }
    var callAfterPermission by remember { mutableStateOf<FavoriteContact?>(null) }
    var permissionsAsked by rememberSaveable { mutableStateOf(false) }
    val menuMetrics = remember(density.density) {
        mapOf(
            // Breitere Zellen, damit Namen wie "Einstellungen" in eine Zeile passen.
            QuickMenuKind.Scopes to QuickMenuMetrics.fromDensity(density.density, showLabels = true, cellWidthDp = 96f),
        )
    }
    val statusBarHeight = WindowInsets.statusBars.getTop(density)

    fun computeMenuLayout(kind: QuickMenuKind): QuickMenuLayout? =
        menuAnchors[kind]?.let { anchor ->
            val count = when (kind) {
                QuickMenuKind.Scopes -> visibleScopes.size
            }
            QuickMenuLayout.compute(
                anchor = anchor,
                containerWidth = rootSize.width.toFloat(),
                topInset = statusBarHeight.toFloat(),
                itemCount = count,
                metrics = menuMetrics.getValue(kind),
            )
        }
    val menuLayout = computeMenuLayout(activeKind)

    val pickContactLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.data?.let { uri ->
            ContactPicker.read(context, uri)?.let(viewModel::addFavoriteContact)
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        // Ob erteilt oder nicht: Der Anruf wird danach mit den verfügbaren Möglichkeiten angeboten.
        pendingCall = callAfterPermission
        callAfterPermission = null
    }

    fun reportFailure() {
        Toast.makeText(context, launchFailedMessage, Toast.LENGTH_SHORT).show()
    }

    fun launchApp(entry: AppEntry) {
        if (viewModel.launchApp(entry)) {
            focusManager.clearFocus()
            viewModel.setQuery("")
        } else {
            reportFailure()
        }
    }

    fun openResult(result: SearchResult) {
        when (result) {
            is AppResult -> launchApp(result.entry)
            is ContactResult -> try {
                context.startActivity(Intent(Intent.ACTION_VIEW, result.lookupUri))
                focusManager.clearFocus()
                viewModel.setQuery("")
            } catch (ignored: ActivityNotFoundException) {
                reportFailure()
            } catch (ignored: SecurityException) {
                reportFailure()
            }
            is ActionResult -> {
                val action = result.action
                if (action is ResultAction.OpenCalculator) {
                    calculatorViewModel.loadExpression(action.expression)
                    focusManager.clearFocus()
                    viewModel.setQuery("")
                    pageOf(Scope.Calculator)?.let { page -> scope.launch { sidePagerState.animateScrollToPage(page) } }
                } else if (runResultAction(context, action)) {
                    focusManager.clearFocus()
                    viewModel.setQuery("")
                } else {
                    reportFailure()
                }
            }
            else -> Unit
        }
    }

    fun addContact() {
        try {
            pickContactLauncher.launch(ContactPicker.pickIntent())
        } catch (ignored: ActivityNotFoundException) {
            Toast.makeText(context, context.getString(R.string.contact_pick_failed), Toast.LENGTH_SHORT).show()
        }
    }

    fun startCall(contact: FavoriteContact) {
        val missing = listOf(Manifest.permission.CALL_PHONE, Manifest.permission.READ_PHONE_STATE).filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty() && !permissionsAsked) {
            permissionsAsked = true
            callAfterPermission = contact
            permissionLauncher.launch(missing.toTypedArray())
        } else {
            pendingCall = contact
        }
    }

    fun launchFromMenu(kind: QuickMenuKind, index: Int) {
        when (kind) {
            QuickMenuKind.Scopes -> visibleScopes.getOrNull(index)?.let { target ->
                pageOf(target)?.let { page -> scope.launch { sidePagerState.animateScrollToPage(page) } }
            }
        }
    }

    fun dispatchMenuEvent(kind: QuickMenuKind, event: QuickMenuEvent) {
        // Ein Ereignis des anderen Buttons schließt das offene Menü und gilt dem neuen Menü.
        var state = menuState
        if (state.isOpen && kind != activeKind) state = QuickMenuState.Closed
        if (state is QuickMenuState.Closed) activeKind = kind
        // Frisch berechnet: Die Position des Buttons wurde unmittelbar vor diesem Ereignis aktualisiert.
        val layout = computeMenuLayout(activeKind) ?: return
        val transition = QuickMenuMachine.reduce(state, event, layout)
        menuState = transition.state
        val effect = transition.effect
        if (effect is QuickMenuEffect.Launch) launchFromMenu(activeKind, effect.index)
    }

    fun dispatchActiveMenuEvent(event: QuickMenuEvent) = dispatchMenuEvent(activeKind, event)

    LaunchedEffect(viewModel, pagerState, sidePagerState) {
        viewModel.homePressed.collect {
            menuState = QuickMenuState.Closed
            focusManager.clearFocus()
            keyboardController?.hide()
            if (sidePagerState.currentPage != SIDE_PAGE_LAUNCHER) sidePagerState.animateScrollToPage(SIDE_PAGE_LAUNCHER)
            pagerState.animateScrollToPage(PAGE_HOME)
        }
    }

    BackHandler(enabled = query.isNotEmpty() || pagerState.currentPage != PAGE_HOME) {
        if (query.isNotEmpty()) {
            viewModel.setQuery("")
        } else {
            scope.launch { pagerState.animateScrollToPage(PAGE_HOME) }
        }
    }
    // Auf der Wetter- und der Einstellungsseite führt Zurück zum Launcher.
    BackHandler(enabled = sidePagerState.currentPage != SIDE_PAGE_LAUNCHER) {
        scope.launch { sidePagerState.animateScrollToPage(SIDE_PAGE_LAUNCHER) }
    }
    // Später deklariert, daher vorrangig: Zurück schließt zuerst das Schnellmenü.
    BackHandler(enabled = menuState.isOpen) {
        menuState = QuickMenuState.Closed
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Scrim)
            .onSizeChanged { rootSize = it },
    ) {
        HorizontalPager(
            state = sidePagerState,
            modifier = Modifier.fillMaxSize(),
            // Seitlich wischen nur auf der Home-Seite: Die App-Übersicht und die Suche bleiben davon unberührt.
            // Ein geöffneter Feed-Artikel hat eigene Gesten (WebView), daher wischt der Pager dort nicht.
            // Während einer Aufnahme oder eines Selbstauslösers bleibt die Kamera-Seite stehen.
            userScrollEnabled = query.isEmpty() && !menuState.isOpen && !cameraBusy &&
                !(feedArticleOpen && currentScope == Scope.Feed) &&
                !(podcastDetailOpen && currentScope == Scope.Podcasts) &&
                (sidePagerState.currentPage != SIDE_PAGE_LAUNCHER || pagerState.currentPage == PAGE_HOME),
        ) { sidePage ->
            if (sidePage == SIDE_PAGE_LAUNCHER) {
                VerticalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    userScrollEnabled = query.isEmpty() && !menuState.isOpen,
                ) { page ->
                    if (page == PAGE_HOME) {
                        HomePage(
                            onScanQr = {
                                focusManager.clearFocus()
                                scannerOpen = true
                            },
                            query = query,
                            searchState = searchState,
                            favoriteKeys = favoriteKeys,
                            contactsGranted = contactsGranted,
                            viewModel = viewModel,
                            onQueryChange = viewModel::setQuery,
                            onLaunch = ::launchApp,
                            onOpenResult = ::openResult,
                            onRequestContacts = onRequestContacts,
                            agenda = agendaState,
                            onAgendaRefresh = agendaViewModel::refresh,
                            dogDue = dogDue,
                            onAgendaOpen = { view ->
                                calendarViewModel.openDay(view.day)
                                pageOf(Scope.Calendar)?.let { page -> scope.launch { sidePagerState.animateScrollToPage(page) } }
                            },
                            onOpenScope = { target ->
                                pageOf(target)?.let { page -> scope.launch { sidePagerState.animateScrollToPage(page) } }
                            },
                            // Die Sichtbarkeit enthält immer die Einstellungen, die Liste ist also nie leer.
                            firstScope = visibleScopes.first(),
                            onQuickMenuEvent = ::dispatchMenuEvent,
                            onQuickMenuAnchorChanged = { kind, bounds ->
                                if (!(menuState.isOpen && activeKind == kind)) menuAnchors[kind] = bounds
                            },
                        )
                    } else {
                        AppGridPage(
                            apps = apps,
                            favoriteKeys = favoriteKeys,
                            viewModel = viewModel,
                            onLaunch = ::launchApp,
                        )
                    }
                }
            } else {
                when (pages.idAt(sidePage)?.let { Scope.valueOf(it) }) {
                    Scope.AppFavorites -> AppFavoritesScreen(
                        viewModel = viewModel,
                        apps = apps,
                        favorites = favorites,
                        favoriteKeys = favoriteKeys,
                        onLaunch = ::launchApp,
                    )
                    Scope.ContactFavorites -> ContactFavoritesScreen(
                        viewModel = viewModel,
                        contacts = contacts,
                        contactsGranted = contactsGranted,
                        onRequestContacts = onRequestContacts,
                        onCall = ::startCall,
                        onAddContact = ::addContact,
                    )
                    Scope.AppGroups -> AppGroupsScreen(
                        viewModel = viewModel,
                        apps = apps,
                        groups = groups,
                        onLaunch = ::launchApp,
                    )
                    Scope.Settings -> SettingsScreen(
                        launcherViewModel = viewModel,
                        settingsViewModel = settingsViewModel,
                        agendaViewModel = agendaViewModel,
                        onRestored = {
                            weatherViewModel.reloadFromStore()
                            podcastsViewModel.reloadFromStore()
                            marketsViewModel.reloadFromStore()
                            feedViewModel.reloadFromStore()
                            calculatorViewModel.reloadFromStore()
                            dogViewModel.reloadFromStore()
                            cameraViewModel.reloadFromStore()
                            filesViewModel.invalidateSettings()
                            galleryViewModel.invalidateSettings()
                            notesViewModel.invalidateSettings()
                            settingsViewModel.reloadScopes()
                        },
                    )
                    Scope.Weather -> WeatherScreen(
                        viewModel = weatherViewModel,
                        active = currentScope == Scope.Weather,
                    )
                    Scope.Calendar -> CalendarScreen(
                        viewModel = calendarViewModel,
                        active = currentScope == Scope.Calendar,
                    )
                    Scope.Feed -> FeedScreen(
                        viewModel = feedViewModel,
                        active = currentScope == Scope.Feed,
                    )
                    Scope.Podcasts -> PodcastsScreen(
                        viewModel = podcastsViewModel,
                        active = currentScope == Scope.Podcasts,
                    )
                    Scope.Files -> FilesScreen(
                        viewModel = filesViewModel,
                        active = currentScope == Scope.Files,
                    )
                    Scope.Gallery -> GalleryScreen(
                        viewModel = galleryViewModel,
                        active = currentScope == Scope.Gallery,
                    )
                    Scope.Notes -> NotesScreen(
                        viewModel = notesViewModel,
                        active = currentScope == Scope.Notes,
                    )
                    Scope.Markets -> MarketsScreen(
                        viewModel = marketsViewModel,
                        active = currentScope == Scope.Markets,
                    )
                    Scope.System -> SystemScreen(
                        viewModel = systemViewModel,
                        active = currentScope == Scope.System,
                    )
                    Scope.Camera -> CameraScreen(
                        viewModel = cameraViewModel,
                        active = currentScope == Scope.Camera,
                    )
                    Scope.Calculator -> CalculatorScreen(viewModel = calculatorViewModel)
                    Scope.Dogs -> DogsScreen(viewModel = dogViewModel)
                    null -> Unit
                }
            }
        }

        // Jede Scope-Seite hat einen Home-Button, der zurück zur Startseite wischt.
        if (sidePagerState.currentPage != SIDE_PAGE_LAUNCHER) {
            // Nachbar-Scopes in der Reihenfolge der sichtbaren Seiten.
            val page = sidePagerState.currentPage
            val leftTarget = pages.idAt(page - 1)?.let { Scope.valueOf(it) }
            val rightTarget = pages.idAt(page + 1)?.let { Scope.valueOf(it) }
            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .systemBarsPadding()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (leftTarget != null) {
                    NeighborScopeButton(
                        target = leftTarget,
                        toRight = false,
                        onClick = { scope.launch { sidePagerState.animateScrollToPage(page - 1) } },
                    )
                }
                HomeButton(
                    onClick = { scope.launch { sidePagerState.animateScrollToPage(SIDE_PAGE_LAUNCHER) } },
                )
                if (rightTarget != null) {
                    NeighborScopeButton(
                        target = rightTarget,
                        toRight = true,
                        onClick = { scope.launch { sidePagerState.animateScrollToPage(page + 1) } },
                    )
                }
            }
        }

        if (scannerOpen) {
            QrScannerScreen(onClose = { scannerOpen = false })
        }

        val overlayLayout = menuLayout
        if (menuState.isOpen && overlayLayout != null) {
            val entries = when (activeKind) {
                QuickMenuKind.Scopes -> visibleScopes.map { target ->
                    val label = stringResource(target.label)
                    QuickMenuEntry(label = label, caption = label) {
                        Icon(
                            painter = painterResource(target.icon),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(32.dp),
                        )
                    }
                }
            }
            QuickMenuOverlay(
                layout = overlayLayout,
                entries = entries,
                title = stringResource(
                    when (activeKind) {
                        QuickMenuKind.Scopes -> R.string.quick_menu_scopes
                    },
                ),
                // Die Scope-Liste ist nie leer.
                emptyHint = stringResource(R.string.quick_menu_scopes),
                actionLabel = null,
                onEvent = ::dispatchActiveMenuEvent,
                onItemLongClick = null,
            )
        }
    }

    pendingCall?.let { contact ->
        val sims = remember(contact) { callHelper.simOptions() }
        CallConfirmDialog(
            contact = contact,
            sims = sims,
            canCallDirectly = callHelper.canCallDirectly,
            onCall = { sim ->
                pendingCall = null
                if (!callHelper.call(contact.number, sim)) {
                    Toast.makeText(context, context.getString(R.string.call_failed), Toast.LENGTH_SHORT).show()
                }
            },
            onDismiss = { pendingCall = null },
        )
    }
}

/** Merkt sich die vorige Seitenfolge, damit die gezeigte Scope beim Ein- und Ausblenden von Scopes stehen bleibt. */
private class PageTracker(var pages: ScopePages, var target: Int? = null)

/**
 * Button zur links bzw. rechts benachbarten Scope: Pfeil in Blickrichtung und Symbol der Ziel-Scope.
 */
@Composable
private fun NeighborScopeButton(target: Scope, toRight: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val label = stringResource(target.label)
    val arrow = painterResource(if (toRight) R.drawable.ic_chevron_right else R.drawable.ic_chevron_left)
    val targetIcon = painterResource(target.icon)
    val tint = MaterialTheme.colorScheme.primary
    Row(
        modifier = modifier
            .height(56.dp)
            .glass(RoundedCornerShape(28.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp)
            .semantics { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (!toRight) Icon(arrow, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Icon(targetIcon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
        if (toRight) Icon(arrow, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
    }
}

/** Runder Button mit Haus-Symbol, schwebt unten rechts über den Scope-Seiten. */
@Composable
private fun HomeButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val label = stringResource(R.string.scope_home)
    Box(
        modifier = modifier
            .size(56.dp)
            .glass(CircleShape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_home),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(26.dp),
        )
    }
}

@Composable
private fun HomePage(
    query: String,
    searchState: SearchState,
    favoriteKeys: Set<String>,
    contactsGranted: Boolean,
    viewModel: LauncherViewModel,
    onQueryChange: (String) -> Unit,
    onLaunch: (AppEntry) -> Unit,
    onOpenResult: (SearchResult) -> Unit,
    onRequestContacts: () -> Unit,
    agenda: AgendaUiState,
    onAgendaRefresh: () -> Unit,
    dogDue: List<DueEntry>,
    onAgendaOpen: (AgendaView) -> Unit,
    onOpenScope: (Scope) -> Unit,
    firstScope: Scope,
    onQuickMenuEvent: (QuickMenuKind, QuickMenuEvent) -> Unit,
    onQuickMenuAnchorChanged: (QuickMenuKind, Bounds) -> Unit,
    onScanQr: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .imePadding()
            .padding(horizontal = 16.dp),
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
        ) {
            if (query.isEmpty()) {
                Column(Modifier.padding(top = 32.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    ClockHeader()
                    val view = agenda.view
                    if (agenda.enabled && agenda.permissionGranted && view != null) {
                        AgendaCard(view = view, onRefresh = onAgendaRefresh, onOpen = onAgendaOpen)
                    }
                    if (Scope.Dogs.name !in hiddenScopes) DogsHomeCard(entries = dogDue, onOpen = { onOpenScope(Scope.Dogs) })
                }
            } else {
                SearchResultsContent(
                    query = query,
                    searchState = searchState,
                    favoriteKeys = favoriteKeys,
                    contactsGranted = contactsGranted,
                    viewModel = viewModel,
                    onLaunch = onLaunch,
                    onOpenResult = onOpenResult,
                    onRequestContacts = onRequestContacts,
                )
            }
        }
        // Während der Suche bekommen die Ergebnisse den Platz (vor allem bei geöffneter Tastatur).
        Row(verticalAlignment = Alignment.CenterVertically) {
            SearchField(
                query = query,
                onQueryChange = onQueryChange,
                onSearch = {
                    // Die Ergebnisse gehören nur dann zur aktuellen Eingabe, wenn die Anfrage übereinstimmt.
                    if (searchState.query == query) {
                        searchState.results.firstOrNull()?.let(onOpenResult)
                    }
                },
                onScanQr = onScanQr,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            CameraButton()
        }
        Spacer(Modifier.height(12.dp))
        // Der Scopes-Button steht unter dem Suchfeld; das Pop-up öffnet nach oben über dem Suchfeld.
        if (query.isEmpty()) {
            QuickMenuBar(
                firstScope = firstScope,
                onOpenScope = onOpenScope,
                onQuickMenuEvent = onQuickMenuEvent,
                onQuickMenuAnchorChanged = onQuickMenuAnchorChanged,
            )
            Spacer(Modifier.height(12.dp))
        }
    }
}

/**
 * Button-Leiste unter dem Suchfeld: Pfeil-Button zu den Einstellungen (links), Scopes-Button (nimmt die
 * übrige Breite, `Modifier.weight(1f)`), Pfeil-Button zur ersten sichtbaren Scope (rechts; standardmäßig die
 * App-Favoriten).
 */
@Composable
private fun QuickMenuBar(
    firstScope: Scope,
    onOpenScope: (Scope) -> Unit,
    onQuickMenuEvent: (QuickMenuKind, QuickMenuEvent) -> Unit,
    onQuickMenuAnchorChanged: (QuickMenuKind, Bounds) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Direkt zu den Einstellungen (links) und zur ersten Scope in der eingestellten Reihenfolge (rechts).
        NeighborScopeButton(target = Scope.Settings, toRight = false, onClick = { onOpenScope(Scope.Settings) })
        QuickMenuButton(
            kind = QuickMenuKind.Scopes,
            onEvent = { onQuickMenuEvent(QuickMenuKind.Scopes, it) },
            onAnchorChanged = { onQuickMenuAnchorChanged(QuickMenuKind.Scopes, it) },
            modifier = Modifier.weight(1f),
        )
        NeighborScopeButton(target = firstScope, toRight = true, onClick = { onOpenScope(firstScope) })
    }
}

@Composable
private fun SearchResultsContent(
    query: String,
    searchState: SearchState,
    favoriteKeys: Set<String>,
    contactsGranted: Boolean,
    viewModel: LauncherViewModel,
    onLaunch: (AppEntry) -> Unit,
    onOpenResult: (SearchResult) -> Unit,
    onRequestContacts: () -> Unit,
) {
    val resultsAreCurrent = searchState.query == query
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 16.dp),
    ) {
        items(searchState.results, key = { it.id }) { result ->
            when (result) {
                is AppResult -> AppRow(
                    entry = result.entry,
                    isFavorite = result.entry.key in favoriteKeys,
                    viewModel = viewModel,
                    onLaunch = { onLaunch(result.entry) },
                )
                is ContactResult -> ContactRow(result, viewModel) { onOpenResult(result) }
                is ActionResult -> ActionRow(result) { onOpenResult(result) }
                else -> Unit
            }
        }
        if (resultsAreCurrent && searchState.results.isEmpty()) {
            item(key = "no-results") {
                Text(
                    text = stringResource(R.string.search_no_results),
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        }
        if (!contactsGranted) {
            item(key = "contacts-hint") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                ) {
                    Text(
                        text = stringResource(R.string.search_contacts_rationale),
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = onRequestContacts) {
                        Text(stringResource(R.string.search_enable_contacts))
                    }
                }
            }
        }
    }
}

/** Anzahl der Spalten im App-Grid. */
private const val GRID_COLUMNS = 4

@Composable
private fun AppGridPage(
    apps: List<AppEntry>,
    favoriteKeys: Set<String>,
    viewModel: LauncherViewModel,
    onLaunch: (AppEntry) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(GRID_COLUMNS),
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp),
        contentPadding = WindowInsets.systemBars.asPaddingValues(),
    ) {
        item(key = "header", span = { GridItemSpan(maxLineSpan) }) {
            SectionLabel(
                text = stringResource(R.string.all_apps),
                modifier = Modifier.padding(start = 12.dp, top = 16.dp, bottom = 8.dp),
            )
        }
        gridItems(apps, key = { it.key }) { entry ->
            AppGridCell(
                entry = entry,
                isFavorite = entry.key in favoriteKeys,
                viewModel = viewModel,
                onLaunch = { onLaunch(entry) },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppGridCell(
    entry: AppEntry,
    isFavorite: Boolean,
    viewModel: LauncherViewModel,
    onLaunch: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Box {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onLaunch, onLongClick = { menuOpen = true })
                .padding(horizontal = 4.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AppIcon(entry, viewModel, Modifier.size(52.dp))
            Spacer(Modifier.height(6.dp))
            Text(
                text = entry.label,
                color = TextPrimary,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 2,
                minLines = 2,
                textAlign = TextAlign.Center,
                overflow = TextOverflow.Ellipsis,
            )
        }
        AppContextMenu(
            expanded = menuOpen,
            onDismiss = { menuOpen = false },
            entry = entry,
            isFavorite = isFavorite,
            viewModel = viewModel,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppRow(
    entry: AppEntry,
    isFavorite: Boolean,
    viewModel: LauncherViewModel,
    onLaunch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Box(modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onLaunch, onLongClick = { menuOpen = true })
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(entry, viewModel, Modifier.size(48.dp))
            Spacer(Modifier.width(16.dp))
            Text(
                text = entry.label,
                color = TextPrimary,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        AppContextMenu(
            expanded = menuOpen,
            onDismiss = { menuOpen = false },
            entry = entry,
            isFavorite = isFavorite,
            viewModel = viewModel,
        )
    }
}

@Composable
private fun AppContextMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    entry: AppEntry,
    isFavorite: Boolean,
    viewModel: LauncherViewModel,
) {
    val context = LocalContext.current
    val launchFailedMessage = stringResource(R.string.launch_failed)
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = {
                Text(
                    stringResource(
                        if (isFavorite) R.string.menu_remove_favorite else R.string.menu_add_favorite,
                    ),
                )
            },
            onClick = {
                onDismiss()
                viewModel.toggleFavorite(entry)
            },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.menu_app_info)) },
            onClick = {
                onDismiss()
                if (!viewModel.openAppInfo(entry)) {
                    Toast.makeText(context, launchFailedMessage, Toast.LENGTH_SHORT).show()
                }
            },
        )
        val canUninstall = remember(entry.key) { viewModel.canUninstall(entry) }
        if (canUninstall) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.menu_uninstall)) },
                onClick = {
                    onDismiss()
                    if (!viewModel.uninstall(entry)) {
                        Toast.makeText(context, launchFailedMessage, Toast.LENGTH_SHORT).show()
                    }
                },
            )
        }
    }
}

@Composable
internal fun AppIcon(entry: AppEntry, viewModel: LauncherViewModel, modifier: Modifier = Modifier) {
    val icon by produceState<ImageBitmap?>(initialValue = null, entry.key) {
        value = viewModel.loadIcon(entry)
    }
    val bitmap = icon
    if (bitmap != null) {
        Image(bitmap = bitmap, contentDescription = null, modifier = modifier)
    } else {
        Spacer(modifier)
    }
}

/** Zeile für Einstellungen, Rechnungen, Telefonnummern, Adressen und die Websuche. */
@Composable
private fun ActionRow(result: ActionResult, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(result.icon),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(24.dp),
            )
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = result.title,
                color = TextPrimary,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            result.subtitle?.let {
                Text(
                    text = it,
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ContactRow(result: ContactResult, viewModel: LauncherViewModel, onClick: () -> Unit) {
    val photo by produceState<ImageBitmap?>(initialValue = null, result.photoUri) {
        value = result.photoUri?.let { viewModel.loadContactPhoto(it) }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ContactAvatar(name = result.title, photo = photo, size = 48.dp)
        Spacer(Modifier.width(16.dp))
        Text(
            text = result.title,
            color = TextPrimary,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onScanQr: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val clearLabel = stringResource(R.string.search_clear)
    val scanLabel = stringResource(R.string.qr_title)
    val trailing: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (query.isNotEmpty()) {
                TextButton(
                    onClick = { onQueryChange("") },
                    modifier = Modifier.semantics { contentDescription = clearLabel },
                ) {
                    Text("✕")
                }
            }
            IconButton(onClick = onScanQr) {
                Icon(painterResource(R.drawable.ic_qr_scanner), contentDescription = scanLabel)
            }
        }
    }

    val shape = RoundedCornerShape(28.dp)
    TextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier.fillMaxWidth().glass(shape),
        singleLine = true,
        shape = shape,
        placeholder = { Text(stringResource(R.string.search_hint)) },
        leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null) },
        trailingIcon = trailing,
        colors = TextFieldDefaults.colors(
            // Die Fläche zeichnet der Glas-Stil; das Feld selbst bleibt durchsichtig.
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            disabledContainerColor = Color.Transparent,
            focusedTextColor = TextPrimary,
            unfocusedTextColor = TextPrimary,
            cursorColor = TextPrimary,
            focusedPlaceholderColor = TextSecondary,
            unfocusedPlaceholderColor = TextSecondary,
            focusedLeadingIconColor = TextSecondary,
            unfocusedLeadingIconColor = TextSecondary,
            focusedTrailingIconColor = TextSecondary,
            unfocusedTrailingIconColor = TextSecondary,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
            errorIndicatorColor = Color.Transparent,
        ),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            imeAction = ImeAction.Search,
        ),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
    )
}

@Composable
private fun ClockHeader(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val timeFormat = remember(context) { DateFormat.getTimeFormat(context) }
    val dateFormat = remember(context) { DateFormat.getLongDateFormat(context) }

    val nowMillis by produceState(initialValue = System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            val intoMinute = value % 60_000L
            delay((60_000L - intoMinute).coerceAtLeast(1_000L))
        }
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = timeFormat.format(Date(nowMillis)),
            color = TextPrimary,
            style = MaterialTheme.typography.displayLarge,
        )
        Text(
            text = dateFormat.format(Date(nowMillis)),
            color = TextSecondary,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = TextSecondary,
        style = MaterialTheme.typography.labelLarge,
        modifier = modifier,
    )
}
