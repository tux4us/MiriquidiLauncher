// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.ui

import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat

/**
 * Stellt das Fenster eines Vollbild-Dialogs auf die tatsächliche Bildschirmgröße ein. Ohne diese Angaben kann das
 * Fenster unter neueren Android-Versionen (Edge-to-Edge) über den sichtbaren Bereich hinausragen; Leisten am Rand
 * liegen dann außerhalb des Bildschirms. Muss innerhalb des Dialog-Inhalts aufgerufen werden.
 */
@Composable
internal fun FitDialogWindow() {
    val view = LocalView.current
    SideEffect {
        val window = (view.parent as? DialogWindowProvider)?.window ?: return@SideEffect
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        window.clearFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        WindowCompat.setDecorFitsSystemWindows(window, false)
    }
}

/**
 * Misst, wie weit das Dialogfenster unten über den Bildschirm hinausragt (manche Geräte setzen es unterhalb der
 * Statusleiste an, geben ihm aber die volle Höhe). Liefert einen Modifier für den Wurzelcontainer und den Überstand
 * als Abstand, um den untere Leisten angehoben werden. Ist das Fenster richtig platziert, ist der Abstand 0.
 */
@Composable
internal fun rememberDialogOvershoot(): Pair<Modifier, Dp> {
    val view = LocalView.current
    val context = LocalContext.current
    val density = LocalDensity.current
    var overshootPx by remember { mutableIntStateOf(0) }
    val measure = Modifier.onGloballyPositioned {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        val manager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val screenHeight = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            manager.currentWindowMetrics.bounds.height()
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            manager.defaultDisplay.getRealMetrics(metrics)
            metrics.heightPixels
        }
        overshootPx = (location[1] + view.height - screenHeight).coerceAtLeast(0)
    }
    return measure to with(density) { overshootPx.toDp() }
}
