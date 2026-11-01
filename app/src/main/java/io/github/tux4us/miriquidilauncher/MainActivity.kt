// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import io.github.tux4us.miriquidilauncher.calendar.AgendaViewModel
import io.github.tux4us.miriquidilauncher.calendar.CalendarViewModel
import io.github.tux4us.miriquidilauncher.camera.CameraViewModel
import io.github.tux4us.miriquidilauncher.feed.FeedViewModel
import io.github.tux4us.miriquidilauncher.markets.MarketsViewModel
import io.github.tux4us.miriquidilauncher.files.FilesViewModel
import io.github.tux4us.miriquidilauncher.gallery.GalleryViewModel
import io.github.tux4us.miriquidilauncher.notes.NotesViewModel
import io.github.tux4us.miriquidilauncher.podcasts.PodcastsViewModel
import io.github.tux4us.miriquidilauncher.calculator.CalculatorViewModel
import io.github.tux4us.miriquidilauncher.dogs.DogViewModel
import io.github.tux4us.miriquidilauncher.ui.LauncherScreen
import io.github.tux4us.miriquidilauncher.ui.LauncherTheme
import io.github.tux4us.miriquidilauncher.ui.LauncherViewModel
import io.github.tux4us.miriquidilauncher.system.SystemViewModel
import io.github.tux4us.miriquidilauncher.ui.SettingsViewModel
import io.github.tux4us.miriquidilauncher.weather.WeatherViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: LauncherViewModel by viewModels()
    private val weatherViewModel: WeatherViewModel by viewModels()
    private val calendarViewModel: CalendarViewModel by viewModels()
    private val feedViewModel: FeedViewModel by viewModels()
    private val marketsViewModel: MarketsViewModel by viewModels()
    private val podcastsViewModel: PodcastsViewModel by viewModels()
    private val filesViewModel: FilesViewModel by viewModels()
    private val galleryViewModel: GalleryViewModel by viewModels()
    private val notesViewModel: NotesViewModel by viewModels()
    private val systemViewModel: SystemViewModel by viewModels()
    private val calculatorViewModel: CalculatorViewModel by viewModels()
    private val cameraViewModel: CameraViewModel by viewModels()
    private val agendaViewModel: AgendaViewModel by viewModels()
    private val dogViewModel: DogViewModel by viewModels()
    private val settingsViewModel: SettingsViewModel by viewModels()

    private var contactsGranted by mutableStateOf(false)

    private val contactsPermissionRequest =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            contactsGranted = granted
            if (granted) viewModel.refreshSearch()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        contactsGranted = hasContactsPermission()
        setContent {
            LauncherTheme {
                LauncherScreen(
                    viewModel = viewModel,
                    weatherViewModel = weatherViewModel,
                    calendarViewModel = calendarViewModel,
                    feedViewModel = feedViewModel,
                    marketsViewModel = marketsViewModel,
                    podcastsViewModel = podcastsViewModel,
                    filesViewModel = filesViewModel,
                    galleryViewModel = galleryViewModel,
                    notesViewModel = notesViewModel,
                    systemViewModel = systemViewModel,
                    calculatorViewModel = calculatorViewModel,
                    cameraViewModel = cameraViewModel,
                    agendaViewModel = agendaViewModel,
                    dogViewModel = dogViewModel,
                    settingsViewModel = settingsViewModel,
                    contactsGranted = contactsGranted,
                    onRequestContacts = {
                        contactsPermissionRequest.launch(Manifest.permission.READ_CONTACTS)
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Termine der Startseite neu lesen (Kalender oder Berechtigung können sich geändert haben).
        agendaViewModel.refresh()
        // Die Berechtigung kann in den Systemeinstellungen geändert worden sein.
        val granted = hasContactsPermission()
        if (granted != contactsGranted) {
            contactsGranted = granted
            viewModel.refreshSearch()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_HOME)) {
            viewModel.onHomePressed()
        }
    }

    private fun hasContactsPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED
}
