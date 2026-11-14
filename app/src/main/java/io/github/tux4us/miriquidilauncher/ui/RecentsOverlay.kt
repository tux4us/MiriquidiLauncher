// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.ui

import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.tux4us.miriquidilauncher.R
import io.github.tux4us.miriquidilauncher.data.AppEntry
import io.github.tux4us.miriquidilauncher.recents.RecentItem
import io.github.tux4us.miriquidilauncher.recents.RecentsFan
import io.github.tux4us.miriquidilauncher.recents.RecentsUiState
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

private val CardShape = RoundedCornerShape(18.dp)
private val CardBase = Color(0xFF1B2029)

/**
 * App-Übersicht: die zuletzt verwendeten Apps als Fächer von Karten. Seitlich wischen blättert, Tippen auf die
 * mittlere Karte öffnet die App (Tippen auf eine seitliche Karte holt sie in die Mitte), Wischen nach oben blendet
 * eine Karte aus der Übersicht aus. Hintergrund oder „Zurück“ schließen die Übersicht.
 *
 * Die Gesten liegen auf einer Fläche über dem gesamten Launcher; der seitliche Scope-Pager kommt dadurch nicht ins
 * Gehege.
 */
@Composable
fun RecentsOverlay(
    state: RecentsUiState,
    launcherViewModel: LauncherViewModel,
    onLaunch: (AppEntry) -> Unit,
    onDismissApp: (String) -> Unit,
    onDismissAll: () -> Unit,
    onGrantUsageAccess: () -> Unit,
    onResume: () -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)

    // Nach der Rückkehr aus den Systemeinstellungen (Nutzungszugriff, Bedienungshilfen) neu lesen.
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnResume by rememberUpdatedState(onResume)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) currentOnResume() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.78f))
            .pointerInput(Unit) { detectTapGestures(onTap = { onClose() }) }
            .systemBarsPadding(),
    ) {
        Column(Modifier.fillMaxSize()) {
            Text(
                text = stringResource(R.string.recents_title),
                color = Color.White.copy(alpha = 0.85f),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 8.dp),
            )
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                when {
                    !state.usageAccess -> UsageAccessHint(onGrantUsageAccess)
                    state.items.isEmpty() && !state.loading -> Text(
                        text = stringResource(R.string.recents_empty),
                        color = Color.White.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(32.dp),
                    )
                    state.items.isNotEmpty() -> RecentsFanView(
                        items = state.items,
                        launcherViewModel = launcherViewModel,
                        onLaunch = onLaunch,
                        onDismissApp = onDismissApp,
                    )
                }
            }
            RecentsFooter(
                showClearAll = state.usageAccess && state.items.isNotEmpty(),
                onDismissAll = onDismissAll,
            )
        }
    }
}

@Composable
private fun UsageAccessHint(onGrant: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.padding(32.dp),
    ) {
        Text(
            text = stringResource(R.string.recents_permission),
            color = Color.White.copy(alpha = 0.85f),
            textAlign = TextAlign.Center,
        )
        Button(onClick = onGrant) { Text(stringResource(R.string.recents_permission_grant)) }
    }
}

@Composable
private fun RecentsFooter(
    showClearAll: Boolean,
    onDismissAll: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        if (showClearAll) {
            TextButton(onClick = onDismissAll) { Text(stringResource(R.string.recents_clear_all)) }
        }
    }
}

@Composable
private fun RecentsFanView(
    items: List<RecentItem>,
    launcherViewModel: LauncherViewModel,
    onLaunch: (AppEntry) -> Unit,
    onDismissApp: (String) -> Unit,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val position = remember { Animatable(0f) }
    val count = items.size

    // Wird die letzte Karte entfernt oder schrumpft die Liste, bleibt die Position gültig.
    LaunchedEffect(count) {
        val last = (count - 1).coerceAtLeast(0).toFloat()
        if (position.value > last) position.snapTo(last)
    }
    val center by remember(count) { derivedStateOf { position.value.roundToInt().coerceIn(0, (count - 1).coerceAtLeast(0)) } }

    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val cardHeight = maxHeight * 0.74f
            val aspect = maxWidth / maxHeight
            val cardWidth = minOf(cardHeight * aspect, maxWidth * 0.7f)
            val cardHeightPx = with(density) { cardHeight.toPx() }
            // Abstand zweier Nachbarkarten auf dem Bogen; so folgen die Karten dem Finger.
            val pxPerCard = cardHeightPx * 0.16f
            val dismissThresholdPx = cardHeightPx * 0.22f

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .draggable(
                        orientation = Orientation.Horizontal,
                        state = rememberDraggableState { delta ->
                            scope.launch {
                                position.snapTo(RecentsFan.positionAfterDrag(position.value, delta, pxPerCard, count))
                            }
                        },
                        onDragStopped = { velocity ->
                            val target = RecentsFan.targetIndex(position.value, velocity, pxPerCard, count)
                            position.animateTo(
                                target.toFloat(),
                                spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow),
                            )
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                items.forEachIndexed { index, item ->
                    key(item.entry.key) {
                        FanCard(
                            item = item,
                            launcherViewModel = launcherViewModel,
                            cardWidth = cardWidth,
                            cardHeight = cardHeight,
                            cardHeightPx = cardHeightPx,
                            dismissThresholdPx = dismissThresholdPx,
                            zOrder = -abs(index - center).toFloat(),
                            offsetOf = { index - position.value },
                            onClick = {
                                if (abs(position.value - index) < 0.5f) {
                                    onLaunch(item.entry)
                                } else {
                                    scope.launch { position.animateTo(index.toFloat(), spring(stiffness = Spring.StiffnessMediumLow)) }
                                }
                            },
                            onDismiss = { onDismissApp(item.entry.packageName) },
                        )
                    }
                }
            }
        }
        val front = items[center.coerceIn(0, count - 1)]
        Text(
            text = front.entry.label,
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        Text(
            text = DateUtils.getRelativeTimeSpanString(
                front.lastUsedMillis,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS,
                DateUtils.FORMAT_ABBREV_RELATIVE,
            ).toString(),
            color = Color.White.copy(alpha = 0.6f),
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun FanCard(
    item: RecentItem,
    launcherViewModel: LauncherViewModel,
    cardWidth: androidx.compose.ui.unit.Dp,
    cardHeight: androidx.compose.ui.unit.Dp,
    cardHeightPx: Float,
    dismissThresholdPx: Float,
    zOrder: Float,
    offsetOf: () -> Float,
    onClick: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    // Negativ = nach oben gezogen.
    val lift = remember { Animatable(0f) }
    val label = item.entry.label
    Box(
        modifier = Modifier
            .zIndex(zOrder)
            .size(cardWidth, cardHeight)
            .graphicsLayer {
                val transform = RecentsFan.transform(offsetOf())
                transformOrigin = TransformOrigin(0.5f, RecentsFan.PIVOT_Y_FRACTION)
                rotationZ = transform.rotationDegrees
                scaleX = transform.scale
                scaleY = transform.scale
                translationY = lift.value
                val fade = (1f + lift.value / (cardHeightPx * 0.6f)).coerceIn(0f, 1f)
                alpha = if (transform.visible) transform.alpha * fade else 0f
            }
            .draggable(
                orientation = Orientation.Vertical,
                state = rememberDraggableState { delta ->
                    scope.launch { lift.snapTo((lift.value + delta).coerceAtMost(0f)) }
                },
                onDragStopped = { velocity ->
                    if (RecentsFan.shouldDismiss(-lift.value, -velocity, dismissThresholdPx)) {
                        lift.animateTo(-cardHeightPx * 1.1f, tween(durationMillis = 160))
                        onDismiss()
                    } else {
                        lift.animateTo(0f, spring(stiffness = Spring.StiffnessMedium))
                    }
                },
            )
            .semantics { contentDescription = label },
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .clip(CardShape)
                .clickable(onClick = onClick)
                .background(CardBase)
                .border(1.dp, Color.White.copy(alpha = 0.28f), CardShape),
        ) {
            AppIcon(item.entry, launcherViewModel, Modifier.align(Alignment.Center).size(72.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppIcon(item.entry, launcherViewModel, Modifier.size(24.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = label,
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
