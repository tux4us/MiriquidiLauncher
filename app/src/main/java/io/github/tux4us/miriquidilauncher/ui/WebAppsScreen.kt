// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.ui

import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.tux4us.miriquidilauncher.R
import io.github.tux4us.miriquidilauncher.webapps.IconChange
import io.github.tux4us.miriquidilauncher.webapps.UrlCheck
import io.github.tux4us.miriquidilauncher.webapps.WebApp
import io.github.tux4us.miriquidilauncher.webapps.WebAppLogic
import io.github.tux4us.miriquidilauncher.webapps.WebAppOpener
import io.github.tux4us.miriquidilauncher.webapps.WebAppsViewModel

/**
 * Scope „Webapps“: Name, HTTPS-Adresse und Symbol speichern; Antippen öffnet die Adresse im Standardbrowser oder in
 * einem Custom Tab. Langes Drücken öffnet das Menü (bearbeiten, verschieben, löschen). Der Launcher lädt die Seiten
 * nicht selbst und braucht dafür keine Zustimmung zum Netzwerkzugriff.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WebAppsScreen(viewModel: WebAppsViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val apps by viewModel.apps.collectAsStateWithLifecycle()
    val revision by viewModel.iconRevision.collectAsStateWithLifecycle()
    var creating by rememberSaveable { mutableStateOf(false) }
    var editing by remember { mutableStateOf<WebApp?>(null) }
    var deleting by remember { mutableStateOf<WebApp?>(null) }
    var menuFor by remember { mutableStateOf<String?>(null) }

    ScopePage(title = stringResource(R.string.scope_webapps), modifier = modifier) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(88.dp),
            contentPadding = PaddingValues(bottom = ScopeBottomClearance),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (apps.isEmpty()) {
                item(key = "empty", span = { GridItemSpan(maxLineSpan) }) { ScopeEmptyHint(stringResource(R.string.webapps_empty)) }
            }
            items(apps, key = { it.id }) { app ->
                Box {
                    CellBody(
                        label = app.name,
                        modifier = Modifier.combinedClickable(
                            onClick = {
                                if (!WebAppOpener.open(context, app)) {
                                    val message = if (app.pin != null) R.string.webapp_pin_open_failed else R.string.webapp_open_failed
                                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                                }
                            },
                            onLongClick = { menuFor = app.id },
                        ),
                    ) { WebAppIcon(app, revision, viewModel, 52.dp) }
                    DropdownMenu(expanded = menuFor == app.id, onDismissRequest = { menuFor = null }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.webapp_edit)) },
                            onClick = {
                                menuFor = null
                                editing = app
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.scope_move_earlier)) },
                            onClick = {
                                menuFor = null
                                viewModel.move(app.id, -1)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.scope_move_later)) },
                            onClick = {
                                menuFor = null
                                viewModel.move(app.id, 1)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.webapp_delete)) },
                            onClick = {
                                menuFor = null
                                deleting = app
                            },
                        )
                    }
                }
            }
            if (WebAppLogic.canAdd(apps)) {
                item(key = "add") {
                    CellBody(
                        label = stringResource(R.string.webapp_add),
                        modifier = Modifier.clickable { creating = true },
                    ) {
                        Box(
                            modifier = Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)).background(Color(0x33FFFFFF)),
                            contentAlignment = Alignment.Center,
                        ) { Text("+", color = WeatherText, style = MaterialTheme.typography.headlineMedium) }
                    }
                }
            }
        }
    }

    if (creating) WebAppDialog(viewModel = viewModel, initial = null, onDismiss = { creating = false })
    editing?.let { app -> WebAppDialog(viewModel = viewModel, initial = app, onDismiss = { editing = null }) }
    deleting?.let { app ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.webapp_delete_title, app.name), maxLines = 2, overflow = TextOverflow.Ellipsis) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(app.id)
                    deleting = null
                }) { Text(stringResource(R.string.webapp_delete)) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.scope_cancel)) } },
        )
    }
}

/** Zelle mit Symbol und Name (zwei Zeilen), wie die App-Zellen der übrigen Scopes. */
@Composable
private fun CellBody(label: String, modifier: Modifier = Modifier, icon: @Composable () -> Unit) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        icon()
        Spacer(Modifier.height(6.dp))
        Text(
            text = label,
            color = WeatherText,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 2,
            minLines = 2,
            textAlign = TextAlign.Center,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Eigenes Symbol der Webapp oder, ohne Symbol, eine Kachel mit dem Anfangsbuchstaben. */
@Composable
private fun WebAppIcon(app: WebApp, revision: Int, viewModel: WebAppsViewModel, size: Dp, modifier: Modifier = Modifier) {
    val bitmap by produceState<Bitmap?>(initialValue = null, app.id, app.hasIcon, revision) {
        value = if (app.hasIcon) viewModel.loadIcon(app.id) else null
    }
    IconBox(name = app.name, bitmap = bitmap, size = size, modifier = modifier)
}

@Composable
private fun IconBox(name: String, bitmap: Bitmap?, size: Dp, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(size * 0.22f)
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = modifier.size(size).clip(shape),
        )
    } else {
        Box(
            modifier = modifier.size(size).clip(shape).background(Color.hsv(WebAppLogic.hue(name), 0.5f, 0.55f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(WebAppLogic.initial(name), color = Color.White, style = MaterialTheme.typography.titleLarge, maxLines = 1)
        }
    }
}

/** Dialog zum Anlegen ([initial] `null`) und Bearbeiten einer Webapp. Das Symbol wird erst beim Speichern übernommen. */
@Composable
private fun WebAppDialog(viewModel: WebAppsViewModel, initial: WebApp?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var name by rememberSaveable { mutableStateOf(initial?.name.orEmpty()) }
    var url by rememberSaveable { mutableStateOf(initial?.url.orEmpty()) }
    var customTab by rememberSaveable { mutableStateOf(initial?.customTab ?: false) }
    var pickedUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var removeIcon by rememberSaveable { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var iconFailed by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var stored by remember { mutableStateOf<Bitmap?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            pickedUri = uri
            removeIcon = false
            iconFailed = false
        }
    }
    LaunchedEffect(initial?.id) {
        if (initial?.hasIcon == true) stored = viewModel.loadIcon(initial.id)
    }
    LaunchedEffect(pickedUri) {
        val uri = pickedUri
        preview = if (uri == null) null else viewModel.renderIcon(uri)
        if (uri != null && preview == null) {
            iconFailed = true
            pickedUri = null
        }
    }

    val check = WebAppLogic.checkUrl(url)
    val urlError = when (check) {
        UrlCheck.NotHttps -> stringResource(R.string.webapp_url_not_https)
        UrlCheck.Invalid -> stringResource(R.string.webapp_url_invalid)
        else -> null
    }
    // Bei einer Verknüpfung aus dem Browser sind nur Name und Symbol änderbar.
    val isPin = initial?.pin != null
    val canSave = !saving && WebAppLogic.cleanName(name) != null && (isPin || check is UrlCheck.Ok)
    val shownIcon = preview ?: if (removeIcon) null else stored

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (initial == null) R.string.webapp_new_title else R.string.webapp_edit_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.webapp_name_hint)) },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                if (isPin) {
                    Text(
                        text = stringResource(R.string.webapp_pin_info),
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.webapp_url_hint)) },
                    isError = urlError != null,
                    supportingText = urlError?.let { message -> { Text(message) } },
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Uri,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (!isPin) {
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { customTab = !customTab }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Das Ankreuzfeld nimmt keine eigenen Berührungen an; die ganze Zeile schaltet um.
                        Checkbox(checked = customTab, onCheckedChange = null)
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.webapp_custom_tab), modifier = Modifier.weight(1f))
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBox(name = name, bitmap = shownIcon, size = 48.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        TextButton(onClick = { picker.launch(arrayOf("image/*")) }) { Text(stringResource(R.string.webapp_icon_choose)) }
                        if (shownIcon != null) {
                            TextButton(onClick = {
                                pickedUri = null
                                removeIcon = true
                            }) { Text(stringResource(R.string.webapp_icon_remove)) }
                        }
                    }
                }
                if (iconFailed) {
                    Text(
                        text = stringResource(R.string.webapp_icon_failed),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSave,
                onClick = {
                    saving = true
                    val icon = when {
                        preview != null -> IconChange.Set(preview!!)
                        removeIcon -> IconChange.Remove
                        else -> IconChange.Keep
                    }
                    viewModel.save(initial?.id, name, url, customTab, icon) { ok ->
                        if (ok) {
                            onDismiss()
                        } else {
                            saving = false
                            Toast.makeText(context, R.string.webapp_save_failed, Toast.LENGTH_SHORT).show()
                        }
                    }
                },
            ) { Text(stringResource(R.string.webapp_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.scope_cancel)) } },
    )
}
