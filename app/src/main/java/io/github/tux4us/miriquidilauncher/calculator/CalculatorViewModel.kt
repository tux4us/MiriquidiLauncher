// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.calculator

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.text.DecimalFormatSymbols
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Zustand der Scope „Rechner“. Die Rechenlogik liegt in [CalcSession]; hier werden Aktionen darauf angewendet und
 * die dauerhaften Teile (Winkelmodus, Speicher, letztes Ergebnis, Verlauf) gespeichert. Die Eingabe selbst bleibt
 * nur im Arbeitsspeicher.
 */
class CalculatorViewModel(application: Application) : AndroidViewModel(application) {
    private val store = CalculatorStore(application)

    private val _state = MutableStateFlow(CalcSession())
    val state: StateFlow<CalcSession> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val saved = store.load()
            _state.update { it.copy(angle = saved.angle, memory = saved.memory, ans = saved.ans, history = saved.history) }
        }
    }

    /** Liest den gespeicherten Stand neu, nachdem ein Backup eingespielt wurde. */
    fun reloadFromStore() {
        viewModelScope.launch {
            val saved = store.load()
            _state.update { it.copy(angle = saved.angle, memory = saved.memory, ans = saved.ans, history = saved.history) }
        }
    }

    private val decimalSeparator: Char get() = DecimalFormatSymbols.getInstance().decimalSeparator

    private fun mutate(block: (CalcSession) -> CalcSession) {
        val old = _state.value
        val new = block(old)
        if (new == old) return
        _state.value = new
        val persisted = new.persisted()
        if (persisted != old.persisted()) viewModelScope.launch { store.save(persisted) }
    }

    fun type(token: String) = mutate { it.type(token, decimalSeparator) }

    fun backspace() = mutate { it.backspace() }

    fun clear() = mutate { it.clear() }

    fun evaluate() = mutate { it.evaluate() }

    fun moveLeft() = mutate { it.moveLeft() }

    fun moveRight() = mutate { it.moveRight() }

    fun toggleAngle() = mutate { it.toggleAngle() }

    fun memoryAdd(subtract: Boolean) = mutate { it.memoryAdd(subtract) }

    fun memoryClear() = mutate { it.memoryClear() }

    fun memoryRecall() = mutate { it.memoryRecall(decimalSeparator) }

    /** Übernimmt eine Rechnung, z. B. aus der Suche oder dem Verlauf. */
    fun loadExpression(expression: String) = mutate { it.loadExpression(expression) }

    fun insertValue(value: Double) = mutate { it.insertValue(value, decimalSeparator) }

    fun clearHistory() = mutate { it.clearHistory() }
}
