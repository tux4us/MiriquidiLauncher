// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import io.github.tux4us.miriquidilauncher.R
import io.github.tux4us.miriquidilauncher.backup.BackupCodec
import io.github.tux4us.miriquidilauncher.backup.BackupData
import io.github.tux4us.miriquidilauncher.backup.BackupQr
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val PanelColor = Color(0xFF141A22)

/** Zielbreite des QR-Bitmaps in Pixeln; es wird um einen ganzzahligen Faktor vergrößert, damit die Module scharf bleiben. */
private const val QR_BITMAP_PX = 960

/** Wechselintervall der Codes bei mehreren Teilen. */
private const val AUTO_SWITCH_MS = 2500L

/** Pause nach einem gelesenen Code, damit derselbe Code nicht in jedem Kamerabild verarbeitet wird. */
private const val RESCAN_DELAY_MS = 350L

/**
 * Zeigt das aktuelle Backup als QR-Code(s). Ob Kontakte enthalten sind, bestimmt der Schalter im Backup-Abschnitt.
 * Das Passwort wird nur im Speicher gehalten und nicht in den Code übernommen.
 */
@Composable
internal fun BackupQrShowDialog(settingsViewModel: SettingsViewModel, includeContacts: Boolean, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var usePassword by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var parts by remember { mutableStateOf<List<String>?>(null) }
    var creating by remember { mutableStateOf(false) }
    var errorRes by remember { mutableStateOf<Int?>(null) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        KeepScreenOnAndSecure()
        Box(Modifier.fillMaxSize().background(PanelColor).dialogInsets().padding(16.dp)) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.backup_qr_show_title), color = WeatherText, style = MaterialTheme.typography.titleLarge)
                val shown = parts
                if (shown == null) {
                    Text(stringResource(R.string.backup_qr_show_hint), color = WeatherTextMuted)
                    Text(
                        stringResource(if (includeContacts) R.string.backup_qr_contacts_in else R.string.backup_qr_contacts_out),
                        color = WeatherText,
                    )
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = !creating) { usePassword = !usePassword },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = usePassword,
                            onCheckedChange = null,
                            enabled = !creating,
                            colors = CheckboxDefaults.colors(
                                checkedColor = WeatherText,
                                checkmarkColor = PanelColor,
                                uncheckedColor = WeatherText,
                            ),
                        )
                        Spacer(Modifier.size(8.dp))
                        Text(stringResource(R.string.backup_qr_password_check), color = WeatherText)
                    }
                    if (usePassword) {
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text(stringResource(R.string.backup_qr_password_label, BackupQr.MIN_PASSWORD_LENGTH)) },
                            singleLine = true,
                            enabled = !creating,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            colors = backupFieldColors(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Text(
                        stringResource(R.string.backup_qr_password_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = WeatherTextMuted,
                    )
                    errorRes?.let { res ->
                        val text = if (res == R.string.backup_qr_too_large) {
                            stringResource(res, BackupQr.MAX_PARTS)
                        } else {
                            stringResource(res)
                        }
                        Text(text, color = Color(0xFFFF9E9E))
                    }
                    if (creating) Text(stringResource(R.string.backup_qr_creating), color = WeatherTextMuted)
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        TextButton(onClick = onDismiss, colors = ButtonDefaults.textButtonColors(contentColor = WeatherText)) {
                            Text(stringResource(R.string.weather_cancel))
                        }
                        TextButton(
                            enabled = !creating && (!usePassword || password.length >= BackupQr.MIN_PASSWORD_LENGTH),
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = WeatherText,
                                disabledContentColor = Color(0x66FFFFFF),
                            ),
                            onClick = {
                                creating = true
                                errorRes = null
                                scope.launch {
                                    val chars = if (usePassword) password.toCharArray() else null
                                    try {
                                        parts = settingsViewModel.createQrParts(chars)
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: BackupQr.TooLargeException) {
                                        errorRes = R.string.backup_qr_too_large
                                    } catch (e: Exception) {
                                        errorRes = R.string.backup_qr_failed
                                    } finally {
                                        chars?.fill('\u0000')
                                        creating = false
                                    }
                                }
                            },
                        ) { Text(stringResource(R.string.backup_qr_create)) }
                    }
                } else {
                    QrPartsView(shown, onClose = onDismiss)
                }
            }
        }
    }
}

@Composable
private fun QrPartsView(parts: List<String>, onClose: () -> Unit) {
    var index by remember { mutableStateOf(0) }
    var auto by remember { mutableStateOf(parts.size > 1) }
    val count = parts.size

    LaunchedEffect(auto, count) {
        while (auto && count > 1) {
            delay(AUTO_SWITCH_MS)
            index = (index + 1) % count
        }
    }

    val bitmap by produceState<Bitmap?>(null, parts, index) {
        value = withContext(Dispatchers.Default) { renderQr(parts[index]) }
    }

    Text(stringResource(R.string.backup_qr_part, index + 1, count), color = WeatherText, style = MaterialTheme.typography.titleMedium)
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .background(Color.White),
        contentAlignment = Alignment.Center,
    ) {
        bitmap?.let { bmp ->
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = stringResource(R.string.backup_qr_part_description, index + 1, count),
                filterQuality = FilterQuality.None,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
    if (count > 1) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            TextButton(
                onClick = { auto = false; index = (index - 1 + count) % count },
                colors = ButtonDefaults.textButtonColors(contentColor = WeatherText),
            ) { Text(stringResource(R.string.backup_qr_prev)) }
            TextButton(
                onClick = { auto = false; index = (index + 1) % count },
                colors = ButtonDefaults.textButtonColors(contentColor = WeatherText),
            ) { Text(stringResource(R.string.backup_qr_next)) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = auto,
                onCheckedChange = { auto = it },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = PanelColor,
                    checkedTrackColor = WeatherText,
                    uncheckedThumbColor = WeatherText,
                    uncheckedTrackColor = Color(0x33FFFFFF),
                    uncheckedBorderColor = Color(0x99FFFFFF),
                ),
            )
            Spacer(Modifier.size(12.dp))
            Text(stringResource(R.string.backup_qr_auto), color = WeatherText)
        }
    }
    Text(stringResource(R.string.backup_qr_secure_note), style = MaterialTheme.typography.bodySmall, color = WeatherTextMuted)
    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
        TextButton(onClick = onClose, colors = ButtonDefaults.textButtonColors(contentColor = WeatherText)) {
            Text(stringResource(R.string.qr_close))
        }
    }
}

/**
 * Liest die Codes eines Backups mit der Kamera, fragt bei Bedarf das Passwort ab, zeigt den Inhalt zur Bestätigung
 * und spielt das Backup dann über [SettingsViewModel.importText] ein.
 */
@Composable
internal fun BackupQrScanDialog(settingsViewModel: SettingsViewModel, onRestored: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val collector = remember { BackupQr.Collector() }
    val paused = remember { AtomicBoolean(false) }
    var received by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var total by remember { mutableStateOf(0) }
    var noticeRes by remember { mutableStateOf<Int?>(null) }
    var collected by remember { mutableStateOf<BackupQr.Collected?>(null) }
    var password by remember { mutableStateOf("") }
    var wrongPassword by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var opened by remember { mutableStateOf<Pair<String, BackupData>?>(null) }
    var permissionAsked by remember { mutableStateOf(false) }
    var hasCamera by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasCamera = granted
    }
    LaunchedEffect(Unit) {
        if (!hasCamera && !permissionAsked) {
            permissionAsked = true
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    fun restartScan() {
        collector.reset()
        received = emptySet()
        total = 0
        collected = null
        password = ""
        wrongPassword = false
        paused.set(false)
    }

    fun unlock(data: BackupQr.Collected, pw: String?) {
        working = true
        wrongPassword = false
        scope.launch {
            val chars = pw?.toCharArray()
            try {
                val text = withContext(Dispatchers.Default) { BackupQr.open(data, chars) }
                opened = text to BackupCodec.decode(text)
            } catch (e: CancellationException) {
                throw e
            } catch (e: BackupQr.WrongPasswordException) {
                wrongPassword = true
            } catch (e: BackupCodec.BackupException) {
                restartScan()
                noticeRes = R.string.backup_qr_scan_corrupt
            } catch (e: Exception) {
                restartScan()
                noticeRes = R.string.backup_qr_scan_corrupt
            } finally {
                chars?.fill('\u0000')
                working = false
            }
        }
    }

    val onScan: (io.github.tux4us.miriquidilauncher.scanner.ScanResult) -> Unit = { scan ->
        when (val status = collector.add(scan.text)) {
            BackupQr.Collector.Status.Foreign -> noticeRes = R.string.backup_qr_scan_foreign
            BackupQr.Collector.Status.Corrupt -> {
                received = emptySet()
                total = 0
                noticeRes = R.string.backup_qr_scan_corrupt
            }
            is BackupQr.Collector.Status.Progress -> {
                received = status.received
                total = status.total
                noticeRes = if (status.newSet) R.string.backup_qr_scan_new_set else null
            }
            is BackupQr.Collector.Status.Complete -> {
                received = collector.received
                total = collector.totalParts
                noticeRes = null
                collected = status.collected
                if (!status.collected.encrypted) unlock(status.collected, null)
            }
        }
        scope.launch {
            delay(RESCAN_DELAY_MS)
            if (collected == null) paused.set(false)
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        KeepScreenOnAndSecure()
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            val parsed = opened
            val pending = collected
            when {
                parsed != null -> ConfirmPanel(
                    data = parsed.second,
                    onConfirm = {
                        settingsViewModel.importText(parsed.first, onRestored)
                        onDismiss()
                    },
                    onCancel = onDismiss,
                )

                pending != null && pending.encrypted -> PasswordPanel(
                    password = password,
                    onPasswordChange = { password = it; wrongPassword = false },
                    wrong = wrongPassword,
                    working = working,
                    onDecrypt = { unlock(pending, password) },
                    onCancel = onDismiss,
                )

                pending != null -> Box(Modifier.fillMaxSize())

                else -> ScanPanel(
                    hasCamera = hasCamera,
                    paused = paused,
                    received = received,
                    total = total,
                    noticeRes = noticeRes,
                    onScan = onScan,
                    onRequestPermission = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                    onClose = onDismiss,
                )
            }
        }
    }
}

@Composable
private fun ScanPanel(
    hasCamera: Boolean,
    paused: AtomicBoolean,
    received: Set<Int>,
    total: Int,
    noticeRes: Int?,
    onScan: (io.github.tux4us.miriquidilauncher.scanner.ScanResult) -> Unit,
    onRequestPermission: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    Box(Modifier.fillMaxSize()) {
        if (hasCamera) {
            CameraPreview(
                paused = paused,
                torchOn = false,
                onTorchSupported = {},
                onResult = onScan,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(260.dp)
                    .border(2.dp, Color.White.copy(alpha = 0.85f), RoundedCornerShape(24.dp)),
            )
        } else {
            Column(
                Modifier.align(Alignment.Center).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.qr_camera_permission), color = WeatherText, textAlign = TextAlign.Center)
                TextButton(onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")),
                        )
                    }
                }) { Text(stringResource(R.string.qr_camera_settings)) }
                TextButton(onClick = onRequestPermission) { Text(stringResource(R.string.qr_camera_grant)) }
            }
        }
        Column(Modifier.fillMaxSize().dialogInsets().padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.backup_qr_scan_title),
                    color = WeatherText,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                )
                TextButton(onClick = onClose) { Text(stringResource(R.string.qr_close)) }
            }
            // Zähler, fehlende Codes, Meldung und Hilfetext stehen gemeinsam oben unter der Titelleiste in einem
            // begrenzten, scrollbaren Block. Unten bleibt nichts stehen, was bei großer Anzeige- oder Schriftgröße
            // aus dem Bild rutschen könnte.
            val maxInfoHeight = (LocalConfiguration.current.screenHeightDp * 0.4f).dp
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxInfoHeight)
                    .background(Color(0xCC000000), RoundedCornerShape(16.dp))
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (total > 0) {
                    Text(
                        stringResource(R.string.backup_qr_scan_progress, received.size, total),
                        color = WeatherText,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    val missing = (1..total).filter { it !in received }
                    if (missing.isNotEmpty()) {
                        Text(
                            stringResource(R.string.backup_qr_scan_missing, missing.joinToString(", ")),
                            color = WeatherTextMuted,
                        )
                    }
                }
                noticeRes?.let { Text(stringResource(it), color = Color(0xFFFFC38E)) }
                Text(stringResource(R.string.backup_qr_scan_hint), color = WeatherText)
            }
        }
    }
}

@Composable
private fun PasswordPanel(
    password: String,
    onPasswordChange: (String) -> Unit,
    wrong: Boolean,
    working: Boolean,
    onDecrypt: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().background(PanelColor).dialogInsets().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.backup_qr_scan_title), color = WeatherText, style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.backup_qr_password_needed), color = WeatherText)
        OutlinedTextField(
            value = password,
            onValueChange = onPasswordChange,
            label = { Text(stringResource(R.string.backup_qr_password_label, BackupQr.MIN_PASSWORD_LENGTH)) },
            singleLine = true,
            enabled = !working,
            isError = wrong,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            colors = backupFieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
        if (wrong) Text(stringResource(R.string.backup_qr_password_wrong), color = Color(0xFFFF9E9E))
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onCancel, colors = ButtonDefaults.textButtonColors(contentColor = WeatherText)) {
                Text(stringResource(R.string.weather_cancel))
            }
            TextButton(
                onClick = onDecrypt,
                enabled = !working && password.isNotEmpty(),
                colors = ButtonDefaults.textButtonColors(contentColor = WeatherText, disabledContentColor = Color(0x66FFFFFF)),
            ) { Text(stringResource(R.string.backup_qr_decrypt)) }
        }
    }
}

@Composable
private fun ConfirmPanel(data: BackupData, onConfirm: () -> Unit, onCancel: () -> Unit) {
    val contacts = data.contactFavorites?.let { stringResource(R.string.settings_backup_imported_contacts, it.size) }
        ?: stringResource(R.string.settings_backup_imported_no_contacts)
    Column(
        Modifier.fillMaxSize().background(PanelColor).dialogInsets().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.settings_backup_import_confirm_title), color = WeatherText, style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.backup_qr_confirm_text), color = WeatherText)
        Text(
            stringResource(R.string.backup_qr_contents, data.appFavorites.size, contacts, data.weatherFavorites.size),
            color = WeatherTextMuted,
        )
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onCancel, colors = ButtonDefaults.textButtonColors(contentColor = WeatherText)) {
                Text(stringResource(R.string.weather_cancel))
            }
            TextButton(onClick = onConfirm, colors = ButtonDefaults.textButtonColors(contentColor = WeatherText)) {
                Text(stringResource(R.string.settings_backup_import_confirm_action))
            }
        }
    }
}

/**
 * Abstand zu Statusleiste, Navigationsleiste und Tastatur. Fenster von Dialogen erhalten die Systemleisten-Insets je
 * nach Android-Version und Gerät nicht zuverlässig; die Mindestabstände verhindern, dass Inhalt unter den Leisten
 * liegt oder unten aus dem Bild rutscht.
 */
@Composable
private fun Modifier.dialogInsets(): Modifier =
    windowInsetsPadding(WindowInsets.safeDrawing.union(WindowInsets(top = 32.dp, bottom = 24.dp)))

/** Hält den Bildschirm an und sperrt Screenshots, solange der Dialog offen ist (Code bzw. Passwort sichtbar). */
@Composable
private fun KeepScreenOnAndSecure() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.parent as? DialogWindowProvider)?.window
        val flags = WindowManager.LayoutParams.FLAG_SECURE or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        window?.addFlags(flags)
        onDispose { window?.clearFlags(flags) }
    }
}

@Composable
private fun backupFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = WeatherText,
    unfocusedTextColor = WeatherText,
    disabledTextColor = WeatherTextMuted,
    focusedBorderColor = WeatherText,
    unfocusedBorderColor = Color(0x99FFFFFF),
    focusedLabelColor = WeatherText,
    unfocusedLabelColor = WeatherTextMuted,
    cursorColor = WeatherText,
)

/** QR-Code (Stufe M, 4 Module Rand) als schwarz-weißes Bitmap; unabhängig vom App-Thema. */
private fun renderQr(content: String): Bitmap {
    val hints = mapOf<EncodeHintType, Any>(
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        EncodeHintType.MARGIN to 4,
    )
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0, hints)
    val w = matrix.width
    val h = matrix.height
    val pixels = IntArray(w * h) { i -> if (matrix.get(i % w, i / w)) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
    val small = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    val scale = (QR_BITMAP_PX / w).coerceAtLeast(1)
    return if (scale == 1) small else Bitmap.createScaledBitmap(small, w * scale, h * scale, false)
}