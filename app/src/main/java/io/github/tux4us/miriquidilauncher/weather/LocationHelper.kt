// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.weather

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.os.CancellationSignal as CompatSignal
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Grober Standort über den Netzwerk-Anbieter des Systems (ACCESS_COARSE_LOCATION), keine Google-Dienste. */
class LocationHelper(context: Context) {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** Liefert den letzten bekannten oder einen frischen groben Standort, oder `null`. */
    @Suppress("MissingPermission")
    suspend fun currentLocation(): Location? {
        val lm = manager ?: return null
        if (!hasPermission()) return null
        val providers = lm.getProviders(true).filter { it != LocationManager.GPS_PROVIDER }
        val lastKnown = providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
        if (lastKnown != null && System.currentTimeMillis() - lastKnown.time < FRESH_MS) return lastKnown
        val provider = providers.firstOrNull { it == LocationManager.NETWORK_PROVIDER } ?: providers.firstOrNull()
            ?: return lastKnown
        val fresh = withTimeoutOrNull(TIMEOUT_MS) {
            suspendCancellableCoroutine<Location?> { cont ->
                val signal = CompatSignal()
                cont.invokeOnCancellation { signal.cancel() }
                LocationManagerCompat.getCurrentLocation(
                    lm, provider, signal, ContextCompat.getMainExecutor(appContext),
                ) { location -> if (cont.isActive) cont.resume(location) }
            }
        }
        return fresh ?: lastKnown
    }

    private companion object {
        const val FRESH_MS = 30 * 60 * 1000L
        const val TIMEOUT_MS = 15_000L
    }
}
