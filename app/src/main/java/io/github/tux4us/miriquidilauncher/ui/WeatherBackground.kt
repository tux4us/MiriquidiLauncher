// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.tux4us.miriquidilauncher.weather.CurrentWeather

/**
 * Animierter Himmel hinter der Wetterseite, abhängig von der aktuellen Wetterlage. [active] ist `true`, solange
 * die Wetterseite angezeigt wird; die Animation läuft zusätzlich nur, solange die App im Vordergrund ist.
 * Die Bewölkung in Prozent wird derzeit nicht abgerufen; die Darstellung folgt dem Wettercode.
 */
@Composable
internal fun WeatherBackground(current: CurrentWeather, active: Boolean, modifier: Modifier = Modifier) {
    var view by remember { mutableStateOf<WeatherBackgroundView?>(null) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, view, active) {
        val v = view
        if (v == null) {
            onDispose { }
        } else {
            val sync = { v.setHostActive(active && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
            val observer = LifecycleEventObserver { _, _ -> sync() }
            owner.lifecycle.addObserver(observer)
            sync()
            onDispose {
                owner.lifecycle.removeObserver(observer)
                v.setHostActive(false)
            }
        }
    }
    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { context -> WeatherBackgroundView(context).also { view = it } },
        update = { v ->
            v.setCondition(current.weatherCode, current.isDay, null, current.windSpeed, current.windDirection)
        },
    )
}
