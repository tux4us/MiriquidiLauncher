// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.tux4us.miriquidilauncher.R
import io.github.tux4us.miriquidilauncher.gallery.GalleryMetaEdit
import io.github.tux4us.miriquidilauncher.gallery.edit.MetadataFields
import io.github.tux4us.miriquidilauncher.gallery.edit.MetadataLogic

/**
 * Bearbeitung der Metadaten (EXIF) einer JPEG-Datei. Beim Schreiben wird die Originaldatei geändert; Android fragt davor
 * um Erlaubnis. [onSave] erhält die Eingaben in der Reihenfolge Beschreibung, Urheber, Copyright, Datum, Standort entfernen.
 */
@Composable
internal fun GalleryMetadataDialog(
    edit: GalleryMetaEdit,
    error: String?,
    onDismiss: () -> Unit,
    onSave: (old: MetadataFields, description: String, artist: String, copyright: String, date: String, removeGps: Boolean) -> Unit,
) {
    val fields = edit.fields
    var description by remember(fields) { mutableStateOf(fields?.description.orEmpty()) }
    var artist by remember(fields) { mutableStateOf(fields?.artist.orEmpty()) }
    var copyright by remember(fields) { mutableStateOf(fields?.copyright.orEmpty()) }
    var date by remember(fields) { mutableStateOf(MetadataLogic.displayDate(fields?.dateOriginal.orEmpty())) }
    var removeGps by remember(fields) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = GalleryDialogColor,
        titleContentColor = WeatherText,
        textContentColor = WeatherTextMuted,
        title = { Text(stringResource(R.string.gallery_meta_title)) },
        text = {
            if (fields == null) {
                CircularProgressIndicator(color = WeatherText)
            } else {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = description, onValueChange = { description = it },
                        label = { Text(stringResource(R.string.gallery_meta_description)) }, maxLines = 3, colors = filesFieldColors(),
                    )
                    OutlinedTextField(
                        value = artist, onValueChange = { artist = it }, singleLine = true,
                        label = { Text(stringResource(R.string.gallery_meta_artist)) }, colors = filesFieldColors(),
                    )
                    OutlinedTextField(
                        value = copyright, onValueChange = { copyright = it }, singleLine = true,
                        label = { Text(stringResource(R.string.gallery_meta_copyright)) }, colors = filesFieldColors(),
                    )
                    OutlinedTextField(
                        value = date, onValueChange = { date = it }, singleLine = true,
                        label = { Text(stringResource(R.string.gallery_meta_date)) },
                        placeholder = { Text(stringResource(R.string.gallery_meta_date_hint)) },
                        colors = filesFieldColors(),
                    )
                    if (fields.hasGps) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = removeGps,
                                onCheckedChange = { removeGps = it },
                                colors = CheckboxDefaults.colors(checkedColor = WeatherText, checkmarkColor = GalleryDialogColor, uncheckedColor = WeatherTextMuted),
                            )
                            Text(stringResource(R.string.gallery_meta_gps_remove), color = WeatherText, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    Text(stringResource(R.string.gallery_meta_note), color = WeatherTextMuted, style = MaterialTheme.typography.bodySmall)
                    if (error != null) Text(error, color = Color(0xFFFFB4B4), style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (fields != null) onSave(fields, description, artist, copyright, date, removeGps) },
                enabled = fields != null,
                colors = ButtonDefaults.textButtonColors(contentColor = WeatherText, disabledContentColor = Color(0x66FFFFFF)),
            ) { Text(stringResource(R.string.gallery_meta_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, colors = ButtonDefaults.textButtonColors(contentColor = WeatherText)) {
                Text(stringResource(R.string.gallery_ed_cancel))
            }
        },
    )
}
