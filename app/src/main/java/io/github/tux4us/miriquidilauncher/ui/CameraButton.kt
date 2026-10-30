// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.ui

import android.graphics.drawable.Drawable
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.tux4us.miriquidilauncher.R
import io.github.tux4us.miriquidilauncher.data.CameraApp
import io.github.tux4us.miriquidilauncher.data.CameraHelper
import io.github.tux4us.miriquidilauncher.data.CameraStore
import kotlinx.coroutines.launch

/**
 * Button neben dem Suchfeld, der die Kamera-App startet. Gibt es mehrere Kamera-Apps und noch keine gespeicherte
 * Wahl, erscheint eine Auswahl mit „Einmal“ und „Immer“. Langes Drücken öffnet die Auswahl erneut, dort lässt sich
 * eine gespeicherte Wahl auch wieder aufheben.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CameraButton(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { CameraStore(context) }
    val helper = remember { CameraHelper(context) }
    val preferred by store.preferred.collectAsStateWithLifecycle(initialValue = null)
    var choices by remember { mutableStateOf<List<CameraApp>?>(null) }
    val label = stringResource(R.string.camera_open)
    val missing = stringResource(R.string.camera_none)
    val failed = stringResource(R.string.camera_failed)

    fun start(app: CameraApp) {
        if (!helper.launch(app)) Toast.makeText(context, failed, Toast.LENGTH_SHORT).show()
    }

    Box(
        modifier = modifier
            .size(56.dp)
            .glass(CircleShape)
            .combinedClickable(
                onClick = {
                    val apps = helper.cameraApps()
                    val saved = CameraHelper.pickPreferred(apps, preferred)
                    when {
                        apps.isEmpty() -> Toast.makeText(context, missing, Toast.LENGTH_SHORT).show()
                        saved != null -> start(saved)
                        apps.size == 1 -> start(apps.first())
                        else -> choices = apps
                    }
                },
                onLongClick = {
                    val apps = helper.cameraApps()
                    if (apps.isEmpty()) Toast.makeText(context, missing, Toast.LENGTH_SHORT).show() else choices = apps
                },
            )
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_camera),
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.7f),
            modifier = Modifier.size(26.dp),
        )
    }

    choices?.let { apps ->
        CameraChooserDialog(
            apps = apps,
            helper = helper,
            savedPackage = preferred,
            onPick = { app, always ->
                choices = null
                if (always) scope.launch { store.setPreferred(app.packageName) }
                start(app)
            },
            onReset = {
                choices = null
                scope.launch { store.setPreferred(null) }
            },
            onDismiss = { choices = null },
        )
    }
}

@Composable
private fun CameraChooserDialog(
    apps: List<CameraApp>,
    helper: CameraHelper,
    savedPackage: String?,
    onPick: (CameraApp, Boolean) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.camera_choose_title)) },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.camera_choose_hint), style = MaterialTheme.typography.bodySmall)
                apps.forEach { app ->
                    val saved = app.packageName == savedPackage
                    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val icon = remember(app.packageName) { helper.icon(app)?.let(::drawableBitmap) }
                            if (icon != null) {
                                Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(40.dp))
                            } else {
                                Spacer(Modifier.size(40.dp))
                            }
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = if (saved) stringResource(R.string.camera_saved_marker, app.label) else app.label,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { onPick(app, false) }) { Text(stringResource(R.string.camera_once)) }
                            TextButton(onClick = { onPick(app, true) }) { Text(stringResource(R.string.camera_always)) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.camera_cancel)) } },
        dismissButton = if (savedPackage != null) {
            { TextButton(onClick = onReset) { Text(stringResource(R.string.camera_reset)) } }
        } else {
            null
        },
    )
}

private fun drawableBitmap(drawable: Drawable) = drawable.toBitmap(width = 96, height = 96).asImageBitmap()
