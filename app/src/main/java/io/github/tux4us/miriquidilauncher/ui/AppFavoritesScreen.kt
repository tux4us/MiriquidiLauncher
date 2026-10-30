// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.tux4us.miriquidilauncher.R
import io.github.tux4us.miriquidilauncher.data.AppEntry

/** Anzahl der Spalten der Raster in den Scope-Seiten. */
internal const val SCOPE_GRID_COLUMNS = 4

/**
 * Scope "App-Favoriten": die Lieblings-Apps als Raster. Antippen startet die App, langes Drücken öffnet ein
 * Menü zum Verschieben und Entfernen. Die letzte Zelle öffnet die Auswahl aller Apps.
 */
@Composable
fun AppFavoritesScreen(
    viewModel: LauncherViewModel,
    apps: List<AppEntry>,
    favorites: List<AppEntry>,
    favoriteKeys: Set<String>,
    onLaunch: (AppEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    var picking by rememberSaveable { mutableStateOf(false) }
    ScopePage(title = stringResource(R.string.scope_app_favorites), modifier = modifier) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(SCOPE_GRID_COLUMNS),
            contentPadding = PaddingValues(bottom = ScopeBottomClearance),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (favorites.isEmpty()) {
                item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                    ScopeEmptyHint(stringResource(R.string.app_favorites_empty))
                }
            }
            items(favorites, key = { it.key }) { entry ->
                AppCell(
                    entry = entry,
                    viewModel = viewModel,
                    onClick = { onLaunch(entry) },
                    menu = { expanded, dismiss ->
                        ReorderMenu(
                            expanded = expanded,
                            onDismiss = dismiss,
                            onEarlier = { viewModel.moveFavoriteApp(entry, -1) },
                            onLater = { viewModel.moveFavoriteApp(entry, 1) },
                            onRemove = { viewModel.toggleFavorite(entry) },
                        )
                    },
                )
            }
            item(key = "add") {
                AddTile(label = stringResource(R.string.scope_add), onClick = { picking = true })
            }
        }
    }
    if (picking) {
        AppPickerDialog(
            title = stringResource(R.string.scope_app_favorites),
            apps = apps,
            selectedKeys = favoriteKeys,
            viewModel = viewModel,
            onToggle = viewModel::toggleFavorite,
            onDismiss = { picking = false },
        )
    }
}

/** Zelle mit Plus-Symbol am Ende eines Rasters. */
@Composable
internal fun AddTile(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_add),
            contentDescription = null,
            tint = WeatherTextMuted,
            modifier = Modifier.size(52.dp).padding(10.dp),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = label,
            color = WeatherTextMuted,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 2,
            minLines = 2,
            textAlign = TextAlign.Center,
        )
    }
}
