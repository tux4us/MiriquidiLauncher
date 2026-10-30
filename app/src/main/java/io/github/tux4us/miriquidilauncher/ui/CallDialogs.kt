// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.tux4us.miriquidilauncher.R
import io.github.tux4us.miriquidilauncher.data.FavoriteContact
import io.github.tux4us.miriquidilauncher.data.SimOption

/**
 * Bestätigung vor dem Anruf. Bei mehreren SIM-Karten steht pro Karte eine Schaltfläche zur Wahl,
 * sonst eine einzelne "Anrufen"-Schaltfläche (ohne Berechtigung öffnet sie nur die Telefon-App).
 */
@Composable
fun CallConfirmDialog(
    contact: FavoriteContact,
    sims: List<SimOption>,
    canCallDirectly: Boolean,
    onCall: (SimOption?) -> Unit,
    onDismiss: () -> Unit,
) {
    val multipleSims = sims.size >= 2
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.call_title, contact.name)) },
        text = {
            Column {
                Text(contact.number)
                if (multipleSims) {
                    sims.forEach { sim ->
                        TextButton(onClick = { onCall(sim) }, modifier = Modifier.fillMaxWidth()) {
                            Text(sim.label)
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (!multipleSims) {
                TextButton(onClick = { onCall(null) }) {
                    Text(stringResource(if (canCallDirectly) R.string.call_action else R.string.call_action_dial))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.call_cancel)) }
        },
    )
}

/** Rückfrage vor dem Entfernen eines Kontakts aus den Kontakt-Favoriten. */
@Composable
fun RemoveContactDialog(
    contact: FavoriteContact,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.contact_remove_favorite_title, contact.name)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.contact_remove)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.call_cancel)) }
        },
    )
}
