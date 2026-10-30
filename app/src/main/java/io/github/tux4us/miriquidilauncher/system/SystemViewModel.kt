// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.system

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SystemUiState(
    val snapshot: SystemSnapshot? = null,
    /** Belegter Anteil des Arbeitsspeichers (0..1), ältester Wert zuerst. */
    val netRxRate: Long? = null,
    val netTxRate: Long? = null,
    val batterySamples: List<BatterySample> = emptyList(),
    val batteryStats: BatteryStatsSummary = BatteryStatsLogic.summarize(emptyList()),
)

/**
 * Hält die Systemwerte der System-Scope. Die Werte werden nur gelesen, solange die Seite sichtbar ist ([setActive]).
 * Die Akku-Aufzeichnung läuft dagegen während der gesamten Lebensdauer des Prozesses, damit die Statistik auch Zeiten
 * ohne geöffnete Seite abdeckt. Wird der Prozess beendet, wird in dieser Zeit nichts aufgezeichnet.
 */
class SystemViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = SystemRepository(application)
    private val store = BatteryHistoryStore(File(application.filesDir, "battery_history.csv"))

    /** Cache je App und Speicherbaum (Sonderzugriffe, nur auf Wunsch). */
    val analysis = StorageAnalysis(application, viewModelScope)

    private val _state = MutableStateFlow(SystemUiState())
    val state: StateFlow<SystemUiState> = _state.asStateFlow()

    private var pollJob: Job? = null
    private var samples: List<BatterySample> = emptyList()
    private var lastNetRx: Long? = null
    private var lastNetTx: Long? = null
    private var lastNetTime = 0L
    private var receiverRegistered = false

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val manager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            val info = SystemRepository.parseBattery(intent, manager) ?: return
            record(info)
        }
    }

    init {
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) { store.load() }
            samples = BatteryStatsLogic.prune(loaded, System.currentTimeMillis())
            publishSamples()
            // Erst nach dem Laden registrieren, damit keine Messpunkte überschrieben werden.
            ContextCompat.registerReceiver(
                getApplication(),
                batteryReceiver,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            receiverRegistered = true
        }
    }

    private fun record(info: BatteryInfo) {
        val now = BatterySample(System.currentTimeMillis(), info.level, info.tempTenthsC, info.charging)
        if (!BatteryStatsLogic.shouldRecord(samples.lastOrNull(), now)) return
        samples = BatteryStatsLogic.prune(samples + now, now.timeMillis)
        publishSamples()
        val copy = samples
        viewModelScope.launch(Dispatchers.IO) { store.save(copy) }
    }

    private fun publishSamples() {
        val current = samples
        _state.update { it.copy(batterySamples = current, batteryStats = BatteryStatsLogic.summarize(current)) }
    }

    /** Beginnt oder beendet das Lesen der Werte im Abstand von zwei Sekunden. */
    fun setActive(active: Boolean) {
        if (!active) {
            pollJob?.cancel()
            pollJob = null
            return
        }
        analysis.refreshAccess()
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            while (isActive) {
                val snapshot = withContext(Dispatchers.IO) { repository.read() }
                publish(snapshot)
                delay(POLL_MS)
            }
        }
    }

    private fun publish(snapshot: SystemSnapshot) {
        val now = SystemClock.elapsedRealtime()
        val rx = snapshot.netRxBytes
        val tx = snapshot.netTxBytes
        var rxRate: Long? = null
        var txRate: Long? = null
        val seconds = (now - lastNetTime) / 1000.0
        if (seconds > 0 && lastNetRx != null && lastNetTx != null && rx != null && tx != null) {
            rxRate = ((rx - lastNetRx!!) / seconds).toLong().coerceAtLeast(0)
            txRate = ((tx - lastNetTx!!) / seconds).toLong().coerceAtLeast(0)
        }
        lastNetRx = rx
        lastNetTx = tx
        lastNetTime = now
        _state.update { old ->
            old.copy(
                snapshot = snapshot,
                netRxRate = rxRate,
                netTxRate = txRate,
            )
        }
        snapshot.battery?.let { record(it) }
    }

    /** Löscht die Akku-Aufzeichnung. */
    fun clearBatteryHistory() {
        samples = emptyList()
        publishSamples()
        viewModelScope.launch(Dispatchers.IO) { store.clear() }
    }

    override fun onCleared() {
        if (receiverRegistered) runCatching { getApplication<Application>().unregisterReceiver(batteryReceiver) }
        super.onCleared()
    }

    private companion object {
        const val POLL_MS = 2000L
        const val RAM_HISTORY_MAX = 90
    }
}
