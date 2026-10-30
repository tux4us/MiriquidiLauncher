// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.ui

import android.Manifest
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.tux4us.miriquidilauncher.R
import io.github.tux4us.miriquidilauncher.scanner.QrContent
import io.github.tux4us.miriquidilauncher.scanner.QrDecoder
import io.github.tux4us.miriquidilauncher.scanner.QrParser
import io.github.tux4us.miriquidilauncher.scanner.ScanResult
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MAX_IMAGE_SIDE = 2048

/** Vertrag für "Datei speichern unter": Eingabe sind Dateiname und Medientyp; die Zielordner-Auswahl macht das System. */
private class CreateDocumentWithType : ActivityResultContract<Pair<String, String>, Uri?>() {
    override fun createIntent(context: Context, input: Pair<String, String>): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(input.second)
            .putExtra(Intent.EXTRA_TITLE, input.first)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        if (resultCode == android.app.Activity.RESULT_OK) intent?.data else null
}

/**
 * QR-Scanner als Vollbild über dem Launcher. Die Kamera läuft nur, solange die Seite offen ist und kein Ergebnis
 * angezeigt wird. Ein gelesener Code wird nie automatisch geöffnet: Erst der Tipp auf eine Aktion führt sie aus.
 */
@Composable
fun QrScannerScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf<ScanResult?>(null) }
    var torchOn by remember { mutableStateOf(false) }
    var torchSupported by remember { mutableStateOf(false) }
    var permissionAsked by remember { mutableStateOf(false) }
    var hasCamera by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val paused = remember { AtomicBoolean(false) }
    LaunchedEffect(result) { paused.set(result != null) }

    BackHandler {
        if (result != null) result = null else onClose()
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

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val found = withContext(Dispatchers.IO) { readQrFromImage(context, uri) }
                if (found != null) {
                    result = found
                } else {
                    Toast.makeText(context, context.getString(R.string.qr_no_code_in_image), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    var pendingSave by remember { mutableStateOf<ScanResult?>(null) }
    val savePicker = rememberLauncherForActivityResult(CreateDocumentWithType()) { uri ->
        val data = pendingSave
        pendingSave = null
        if (uri != null && data != null) {
            scope.launch {
                val ok = withContext(Dispatchers.IO) { writeResult(context, uri, data) }
                Toast.makeText(
                    context,
                    context.getString(if (ok) R.string.qr_saved else R.string.qr_save_failed),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (hasCamera) {
            CameraPreview(
                paused = paused,
                torchOn = torchOn,
                onTorchSupported = { torchSupported = it },
                onResult = { result = it },
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
                    // Nach einer dauerhaften Ablehnung öffnet die Anfrage keinen Dialog mehr; dann hilft nur die App-Info.
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")),
                        )
                    }
                }) { Text(stringResource(R.string.qr_camera_settings)) }
                TextButton(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                    Text(stringResource(R.string.qr_camera_grant))
                }
            }
        }

        Column(Modifier.fillMaxSize().systemBarsPadding().padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.qr_title),
                    color = WeatherText,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                )
                if (hasCamera && torchSupported) {
                    TextButton(onClick = { torchOn = !torchOn }) {
                        Text(stringResource(if (torchOn) R.string.qr_torch_off else R.string.qr_torch_on))
                    }
                }
                TextButton(onClick = onClose) { Text(stringResource(R.string.qr_close)) }
            }
            Box(Modifier.weight(1f))
            if (result == null) {
                Text(
                    stringResource(R.string.qr_hint),
                    color = WeatherText,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                )
                TextButton(
                    onClick = { imagePicker.launch(arrayOf("image/*")) },
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                ) { Text(stringResource(R.string.qr_from_image)) }
            }
        }

        result?.let { scanned ->
            ResultPanel(
                scanned = scanned,
                onScanAgain = { result = null },
                onSave = { name, mime ->
                    pendingSave = scanned
                    savePicker.launch(name to mime)
                },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

@Composable
internal fun CameraPreview(
    paused: AtomicBoolean,
    torchOn: Boolean,
    onTorchSupported: (Boolean) -> Unit,
    onResult: (ScanResult) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val decoder = remember { QrDecoder() }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val currentOnResult by rememberUpdatedState(onResult)
    val currentOnTorch by rememberUpdatedState(onTorchSupported)
    var camera by remember { mutableStateOf<Camera?>(null) }

    DisposableEffect(lifecycleOwner) {
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        future.addListener(
            {
                runCatching {
                    val p = future.get()
                    provider = p
                    val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                    analysis.setAnalyzer(executor) { image ->
                        try {
                            if (!paused.get()) {
                                val plane = image.planes[0]
                                val buffer = plane.buffer
                                val bytes = ByteArray(buffer.remaining())
                                buffer.get(bytes)
                                val found = decoder.decodeLuminance(bytes, plane.rowStride, image.width, image.height)
                                if (found != null && paused.compareAndSet(false, true)) {
                                    mainHandler.post { currentOnResult(found) }
                                }
                            }
                        } finally {
                            image.close()
                        }
                    }
                    p.unbindAll()
                    val bound = p.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                    camera = bound
                    currentOnTorch(bound.cameraInfo.hasFlashUnit())
                }
            },
            ContextCompat.getMainExecutor(context),
        )
        onDispose {
            runCatching { provider?.unbindAll() }
            camera = null
            executor.shutdown()
        }
    }
    LaunchedEffect(torchOn, camera) { camera?.cameraControl?.enableTorch(torchOn) }

    AndroidView(factory = { previewView }, modifier = modifier)
}

@Composable
private fun ResultPanel(
    scanned: ScanResult,
    onScanAgain: () -> Unit,
    onSave: (name: String, mime: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val content = remember(scanned) { QrParser.parse(scanned.text) }
    val binary = remember(scanned) { scanned.bytes != null && QrParser.isBinary(scanned.text) }
    val suggestion = remember(scanned) {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
        QrParser.fileSuggestion(content, binary, stamp)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .systemBarsPadding()
            .padding(12.dp)
            .glass(RoundedCornerShape(24.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            stringResource(titleFor(content)),
            color = WeatherText,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Column(
            Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Details(content, binary)
        }
        if (content is QrContent.Url) {
            Text(stringResource(R.string.qr_link_warning), color = WeatherTextMuted, style = MaterialTheme.typography.labelMedium)
        }
        Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
            PrimaryActions(context, content)
            TextButton(onClick = { copyText(context, scanned.text) }) { Text(stringResource(R.string.qr_copy_text)) }
            TextButton(onClick = { onSave(suggestion.name, suggestion.mime) }) { Text(stringResource(R.string.qr_save_file)) }
            TextButton(onClick = onScanAgain) { Text(stringResource(R.string.qr_scan_again)) }
        }
    }
}

private fun titleFor(content: QrContent): Int = when (content) {
    is QrContent.Url -> R.string.qr_type_url
    is QrContent.Phone -> R.string.qr_type_phone
    is QrContent.Sms -> R.string.qr_type_sms
    is QrContent.Email -> R.string.qr_type_email
    is QrContent.Geo -> R.string.qr_type_geo
    is QrContent.Wifi -> R.string.qr_type_wifi
    is QrContent.VCard -> R.string.qr_type_vcard
    is QrContent.Text -> R.string.qr_type_text
}

@Composable
private fun Details(content: QrContent, binary: Boolean) {
    @Composable
    fun line(text: String) = Text(text, color = WeatherText, style = MaterialTheme.typography.bodyMedium)

    when (content) {
        is QrContent.Url -> line(content.url)
        is QrContent.Phone -> line(content.number)
        is QrContent.Sms -> {
            line(content.number)
            content.body?.let { line(it) }
        }
        is QrContent.Email -> {
            line(content.address)
            content.subject?.let { line(it) }
            content.body?.let { line(it) }
        }
        is QrContent.Geo -> {
            line("${content.latitude}, ${content.longitude}")
            content.label?.let { line(it) }
        }
        is QrContent.Wifi -> {
            line(stringResource(R.string.qr_wifi_network, content.ssid))
            content.password?.let { line(stringResource(R.string.qr_wifi_password, it)) }
            content.security?.let { line(stringResource(R.string.qr_wifi_security, it)) }
            if (content.hidden) line(stringResource(R.string.qr_wifi_hidden))
        }
        is QrContent.VCard -> {
            content.name?.let { line(it) }
            line(stringResource(R.string.qr_vcard_hint))
        }
        is QrContent.Text -> line(if (binary) stringResource(R.string.qr_binary_hint) else content.raw)
    }
}

@Composable
private fun PrimaryActions(context: Context, content: QrContent) {
    when (content) {
        is QrContent.Url -> TextButton(onClick = { startSafely(context, Intent(Intent.ACTION_VIEW, Uri.parse(content.url))) }) {
            Text(stringResource(R.string.qr_open_link))
        }
        is QrContent.Phone -> TextButton(onClick = {
            startSafely(context, Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", content.number, null)))
        }) { Text(stringResource(R.string.qr_call)) }
        is QrContent.Sms -> TextButton(onClick = {
            val intent = Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", content.number, null))
            content.body?.let { intent.putExtra("sms_body", it) }
            startSafely(context, intent)
        }) { Text(stringResource(R.string.qr_sms)) }
        is QrContent.Email -> TextButton(onClick = {
            val intent = Intent(Intent.ACTION_SENDTO, Uri.fromParts("mailto", content.address, null))
            content.subject?.let { intent.putExtra(Intent.EXTRA_SUBJECT, it) }
            content.body?.let { intent.putExtra(Intent.EXTRA_TEXT, it) }
            startSafely(context, intent)
        }) { Text(stringResource(R.string.qr_email)) }
        is QrContent.Geo -> TextButton(onClick = {
            val label = content.label?.let { "(" + Uri.encode(it) + ")" }.orEmpty()
            val uri = Uri.parse("geo:${content.latitude},${content.longitude}?q=${content.latitude},${content.longitude}$label")
            startSafely(context, Intent(Intent.ACTION_VIEW, uri))
        }) { Text(stringResource(R.string.qr_map)) }
        is QrContent.Wifi -> content.password?.let { password ->
            TextButton(onClick = { copyText(context, password) }) { Text(stringResource(R.string.qr_copy_password)) }
        }
        is QrContent.VCard -> Unit
        is QrContent.Text -> TextButton(onClick = {
            startSafely(context, Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, content.raw))
        }) { Text(stringResource(R.string.qr_search_web)) }
    }
}

private fun startSafely(context: Context, intent: Intent) {
    try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (ignored: ActivityNotFoundException) {
        Toast.makeText(context, context.getString(R.string.qr_no_app), Toast.LENGTH_SHORT).show()
    } catch (ignored: SecurityException) {
        Toast.makeText(context, context.getString(R.string.qr_no_app), Toast.LENGTH_SHORT).show()
    }
}

private fun copyText(context: Context, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("qr", text))
    Toast.makeText(context, context.getString(R.string.search_action_copied), Toast.LENGTH_SHORT).show()
}

/** Schreibt Text (UTF-8) oder, bei Binärdaten, die rohen Bytes in die vom Nutzer gewählte Datei. */
private fun writeResult(context: Context, uri: Uri, data: ScanResult): Boolean = runCatching {
    val bytes = if (data.bytes != null && QrParser.isBinary(data.text)) data.bytes else data.text.toByteArray(Charsets.UTF_8)
    context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } != null
}.getOrDefault(false)

/** Liest einen QR-Code aus einem Bild; große Bilder werden auf höchstens [MAX_IMAGE_SIDE] Pixel Kantenlänge verkleinert geladen. */
private fun readQrFromImage(context: Context, uri: Uri): ScanResult? = runCatching {
    val resolver = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_IMAGE_SIDE) sample *= 2
    val options = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    val bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    bitmap?.let { QrDecoder().decodeBitmap(it) }
}.getOrNull()
