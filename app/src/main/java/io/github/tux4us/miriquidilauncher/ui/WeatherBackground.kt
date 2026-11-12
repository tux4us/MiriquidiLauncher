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
import io.github.tux4us.miriquidilauncher.weather.WeatherMath
import io.github.tux4us.miriquidilauncher.weather.WeatherScene
import java.time.Instant
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.tux4us.miriquidilauncher.weather.CurrentWeather

/**
 * Animierter Himmel hinter der Wetterseite, abhängig von der aktuellen Wetterlage. [active] ist `true`, solange
 * die Wetterseite angezeigt wird; die Animation läuft zusätzlich nur, solange die App im Vordergrund ist.
 * Die Bewölkung in Prozent stammt aus dem Abruf (fehlt sie, folgt die Darstellung dem Wettercode). Der Mond zeigt
 * die aktuelle Mondphase; [latitude] bestimmt die beleuchtete Seite (Nord-/Südhalbkugel).
 */
@Composable
internal fun WeatherBackground(current: CurrentWeather, latitude: Double, active: Boolean, modifier: Modifier = Modifier) {
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
    val moon = remember(current.weatherCode, java.time.LocalDate.now()) { WeatherMath.moon(Instant.now()) }
    val litRight = WeatherScene.moonLitRight(moon.ageDays, latitude)
    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { context -> WeatherBackgroundView(context).also { view = it } },
        update = { v ->
            v.setCondition(
                current.weatherCode, current.isDay, current.cloudCover, current.windSpeed, current.windDirection,
                (moon.illuminationPercent / 100.0).toFloat(), litRight,
            )
        },
    )
}
