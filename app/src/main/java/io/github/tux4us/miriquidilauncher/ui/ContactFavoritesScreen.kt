// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.ui

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.tux4us.miriquidilauncher.R
import io.github.tux4us.miriquidilauncher.data.ContactWay
import io.github.tux4us.miriquidilauncher.data.ContactWayLauncher
import io.github.tux4us.miriquidilauncher.data.ContactWays
import io.github.tux4us.miriquidilauncher.data.FavoriteContact

private const val CONTACT_GRID_COLUMNS = 3

/**
 * Scope "Kontakt-Favoriten": die Lieblingskontakte als Raster. Antippen zeigt alle hinterlegten Kontaktwege
 * (Anruf, SMS, E-Mail, Messenger), langes Drücken öffnet ein Menü zum Verschieben und Entfernen.
 *
 * @param onCall startet den Anruf über den Bestätigungsdialog der Startseite (SIM-Auswahl)
 * @param onAddContact öffnet die Kontaktauswahl des Systems
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ContactFavoritesScreen(
    viewModel: LauncherViewModel,
    contacts: List<FavoriteContact>,
    contactsGranted: Boolean,
    onRequestContacts: () -> Unit,
    onCall: (FavoriteContact) -> Unit,
    onAddContact: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var selected by remember { mutableStateOf<FavoriteContact?>(null) }
    var menuFor by remember { mutableStateOf<FavoriteContact?>(null) }
    var removing by remember { mutableStateOf<FavoriteContact?>(null) }

    ScopePage(title = stringResource(R.string.scope_contact_favorites), modifier = modifier) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(CONTACT_GRID_COLUMNS),
            contentPadding = PaddingValues(bottom = ScopeBottomClearance),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (contacts.isEmpty()) {
                item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                    ScopeEmptyHint(stringResource(R.string.contact_favorites_empty))
                }
            }
            items(contacts, key = { it.key }) { contact ->
                Box {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.medium)
                            .combinedClickable(onClick = { selected = contact }, onLongClick = { menuFor = contact })
                            .padding(horizontal = 4.dp, vertical = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        FavoriteContactAvatar(contact, viewModel, reloadKey = contactsGranted, size = 64.dp)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = contact.name.ifBlank { contact.number },
                            color = WeatherText,
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 2,
                            minLines = 2,
                            textAlign = TextAlign.Center,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    ReorderMenu(
                        expanded = menuFor == contact,
                        onDismiss = { menuFor = null },
                        onEarlier = { viewModel.moveFavoriteContact(contact, -1) },
                        onLater = { viewModel.moveFavoriteContact(contact, 1) },
                        onRemove = { removing = contact },
                    )
                }
            }
            item(key = "add") {
                AddTile(label = stringResource(R.string.contact_add), onClick = onAddContact)
            }
        }
    }

    selected?.let { contact ->
        ContactWaysDialog(
            contact = contact,
            viewModel = viewModel,
            contactsGranted = contactsGranted,
            onRequestContacts = onRequestContacts,
            onCall = { number ->
                selected = null
                onCall(contact.copy(number = number))
            },
            onDismiss = { selected = null },
        )
    }
    removing?.let { contact ->
        RemoveContactDialog(
            contact = contact,
            onConfirm = {
                removing = null
                viewModel.removeFavoriteContact(contact)
            },
            onDismiss = { removing = null },
        )
    }
}

/**
 * Dialog mit allen Wegen zu einem Kontakt. Die Wege werden beim Öffnen aus dem Adressbuch gelesen; ohne
 * Berechtigung stehen nur Anruf und SMS mit der gespeicherten Nummer zur Auswahl.
 */
@Composable
private fun ContactWaysDialog(
    contact: FavoriteContact,
    viewModel: LauncherViewModel,
    contactsGranted: Boolean,
    onRequestContacts: () -> Unit,
    onCall: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val failedMessage = stringResource(R.string.contact_ways_failed)
    val ways by produceState<ContactWays?>(initialValue = null, contact.key, contactsGranted) {
        value = viewModel.loadContactWays(contact)
    }

    fun open(way: ContactWay) {
        if (way is ContactWay.Call) {
            onCall(way.number)
        } else if (!ContactWayLauncher.open(context, way)) {
            Toast.makeText(context, failedMessage, Toast.LENGTH_SHORT).show()
        } else {
            onDismiss()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.contact_ways_title, contact.name.ifBlank { contact.number }), maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            val loaded = ways
            if (loaded == null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.contact_ways_loading))
                }
            } else {
                Column {
                    LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                        items(loaded.ways) { way ->
                            WayRow(way) { open(way) }
                        }
                    }
                    if (!loaded.fromContacts && !contactsGranted) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.contact_ways_hint_no_permission),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(onClick = onRequestContacts) { Text(stringResource(R.string.contact_ways_allow)) }
                    }
                }
            }
        },
        confirmButton = {
            val uri = ways?.lookupUri
            if (uri != null) {
                TextButton(onClick = {
                    if (ContactWayLauncher.openContact(context, uri)) {
                        onDismiss()
                    } else {
                        Toast.makeText(context, failedMessage, Toast.LENGTH_SHORT).show()
                    }
                }) { Text(stringResource(R.string.contact_ways_open_contact)) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.scope_cancel)) } },
    )
}

@Composable
private fun WayRow(way: ContactWay, onClick: () -> Unit) {
    val (icon, title, detail) = when (way) {
        is ContactWay.Call -> Triple(R.drawable.ic_call, stringResource(R.string.contact_ways_call), "${way.number} · ${way.label}".trimEnd(' ', '·'))
        is ContactWay.Sms -> Triple(R.drawable.ic_message, stringResource(R.string.contact_ways_sms), "${way.number} · ${way.label}".trimEnd(' ', '·'))
        is ContactWay.Email -> Triple(R.drawable.ic_mail, stringResource(R.string.contact_ways_email), way.address)
        is ContactWay.ViaApp -> Triple(R.drawable.ic_person, way.label, null)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (detail != null) {
                Text(detail, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
