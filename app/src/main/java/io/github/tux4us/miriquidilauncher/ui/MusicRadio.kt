// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.tux4us.miriquidilauncher.R
import io.github.tux4us.miriquidilauncher.music.MusicLogic
import io.github.tux4us.miriquidilauncher.music.MusicUiState
import io.github.tux4us.miriquidilauncher.music.MusicViewModel
import io.github.tux4us.miriquidilauncher.music.RadioMode
import io.github.tux4us.miriquidilauncher.music.RadioSearch
import io.github.tux4us.miriquidilauncher.music.Station

/** Radio-Tab: gespeicherte Sender und (nach Zustimmung) die Sendersuche im Verzeichnis radio-browser.info. */
@Composable
internal fun RadioTab(
    state: MusicUiState,
    viewModel: MusicViewModel,
    play: (Station) -> Unit,
    onDialog: (MusicDialog) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SegmentChip(stringResource(R.string.music_radio_saved), state.radioMode == RadioMode.SAVED) { viewModel.setRadioMode(RadioMode.SAVED) }
            SegmentChip(stringResource(R.string.music_radio_search), state.radioMode == RadioMode.SEARCH) { viewModel.setRadioMode(RadioMode.SEARCH) }
        }
        Spacer(Modifier.height(8.dp))
        when (state.radioMode) {
            RadioMode.SAVED -> SavedStations(state, viewModel, play, onDialog)
            RadioMode.SEARCH -> if (state.data.radioEnabled) StationSearch(state, viewModel, play) else RadioConsent(viewModel)
        }
    }
}

@Composable
private fun StationRow(
    station: Station,
    state: MusicUiState,
    onClick: () -> Unit,
    trailing: @Composable () -> Unit,
) {
    val playing = state.player.station?.id == station.id
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(horizontal = 4.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MusicCover(null, station, state.data.showLogos, 128, Modifier.size(48.dp), corner = 8.dp)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(station.name, color = if (playing) MusicAccent else WeatherText, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val codec = if (station.codec.isNotEmpty()) station.codec + (if (station.bitrate > 0) " ${station.bitrate} kbit/s" else "") else ""
            val details = listOf(station.country, station.tags.split(',').firstOrNull().orEmpty(), codec).filter { it.isNotEmpty() }.joinToString(" · ")
            if (details.isNotEmpty()) Text(details, color = WeatherTextMuted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        trailing()
    }
}

@Composable
private fun SavedStations(state: MusicUiState, viewModel: MusicViewModel, play: (Station) -> Unit, onDialog: (MusicDialog) -> Unit) {
    val stations = remember(state.data.stations) { MusicLogic.sortedStations(state.data.stations) }
    Column(Modifier.fillMaxSize()) {
        // Die Systemdialoge zur Dateiwahl brauchen keine Berechtigung; gelesen oder geschrieben wird nur die gewählte Datei.
        val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) viewModel.importStations(uri)
        }
        val exportSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) viewModel.exportStations(uri)
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PodcastButton(stringResource(R.string.music_radio_add), { onDialog(MusicDialog.AddStation) })
            PodcastTextButton(stringResource(R.string.music_radio_import)) {
                importPicker.launch(arrayOf("application/json", "text/*", "application/octet-stream"))
            }
            if (stations.isNotEmpty()) {
                PodcastTextButton(stringResource(R.string.music_radio_export)) { exportSaver.launch("radiosender.json") }
            }
        }
        Spacer(Modifier.height(4.dp))
        if (stations.isEmpty()) {
            ScopeEmptyHint(stringResource(R.string.music_empty_stations))
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(stations, key = { it.id }) { station ->
                    var menu by remember { mutableStateOf(false) }
                    StationRow(station, state, onClick = { play(station) }) {
                        Box {
                            IconButton(onClick = { menu = true }) {
                                Icon(painterResource(R.drawable.ic_more_vert), stringResource(R.string.music_more), tint = WeatherTextMuted)
                            }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.music_rename)) }, onClick = { menu = false; onDialog(MusicDialog.RenameStation(station.id, station.name)) })
                                DropdownMenuItem(text = { Text(stringResource(R.string.music_station_remove)) }, onClick = { menu = false; viewModel.removeStation(station.id) })
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Zustimmung zur Netzwerknutzung der Sendersuche; ohne sie verbindet sich die Scope nicht mit dem Verzeichnis. */
@Composable
private fun RadioConsent(viewModel: MusicViewModel) {
    GlassCard(stringResource(R.string.music_consent_title)) {
        Text(stringResource(R.string.music_consent_text), color = WeatherTextMuted, style = MaterialTheme.typography.bodyMedium)
        PodcastButton(stringResource(R.string.music_consent_enable), { viewModel.setRadioEnabled(true) })
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = WeatherText, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(end = 12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.Black,
                checkedTrackColor = WeatherText,
                uncheckedThumbColor = WeatherTextMuted,
                uncheckedTrackColor = Color(0x33FFFFFF),
                uncheckedBorderColor = WeatherTextMuted,
            ),
        )
    }
}

@Composable
private fun StationSearch(state: MusicUiState, viewModel: MusicViewModel, play: (Station) -> Unit) {
    val saved = remember(state.data.stations) { state.data.stations.map { it.id }.toSet() }
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        item(key = "form") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SegmentChip(stringResource(R.string.music_radio_type_top), state.radioType == RadioSearch.TOP) {
                        viewModel.setRadioType(RadioSearch.TOP)
                        viewModel.searchStations(RadioSearch.TOP)
                    }
                    SegmentChip(stringResource(R.string.music_radio_type_name), state.radioType == RadioSearch.NAME) { viewModel.setRadioType(RadioSearch.NAME) }
                    SegmentChip(stringResource(R.string.music_radio_type_tag), state.radioType == RadioSearch.TAG) { viewModel.setRadioType(RadioSearch.TAG) }
                    SegmentChip(stringResource(R.string.music_radio_type_country), state.radioType == RadioSearch.COUNTRY) { viewModel.setRadioType(RadioSearch.COUNTRY) }
                }
                if (state.radioType != RadioSearch.TOP) {
                    OutlinedTextField(
                        value = state.radioQuery,
                        onValueChange = viewModel::setRadioQuery,
                        singleLine = true,
                        label = { Text(stringResource(R.string.music_radio_query_hint)) },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { viewModel.searchStations() }),
                        colors = musicFieldColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(Modifier.fillMaxWidth()) {
                        PodcastButton(stringResource(R.string.music_radio_search_button), { viewModel.searchStations() }, enabled = state.radioQuery.isNotBlank() && !state.radioSearching)
                    }
                }
            }
        }
        when {
            state.radioSearching -> item(key = "busy") {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = WeatherText) }
            }
            state.radioHits.isNotEmpty() -> items(state.radioHits, key = { "hit-" + it.id }) { station ->
                StationRow(station, state, onClick = { play(station) }) {
                    if (station.id in saved) {
                        Text(stringResource(R.string.music_radio_is_saved), color = WeatherTextMuted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(end = 8.dp))
                    } else {
                        IconButton(onClick = { viewModel.saveStation(station) }) {
                            Icon(painterResource(R.drawable.ic_add), stringResource(R.string.music_station_save), tint = WeatherText)
                        }
                    }
                }
            }
            state.radioSearched -> item(key = "none") { ScopeEmptyHint(stringResource(R.string.music_radio_no_results)) }
        }
        item(key = "settings") {
            Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                GlassCard(stringResource(R.string.music_radio_settings)) {
                    SwitchRow(stringResource(R.string.music_radio_allow_http), state.data.allowHttp, viewModel::setAllowHttp)
                    Text(stringResource(R.string.music_radio_allow_http_hint), color = WeatherTextMuted, style = MaterialTheme.typography.bodySmall)
                    SwitchRow(stringResource(R.string.music_radio_show_logos), state.data.showLogos, viewModel::setShowLogos)
                    PodcastTextButton(stringResource(R.string.music_consent_disable)) { viewModel.setRadioEnabled(false) }
                }
            }
        }
    }
}
