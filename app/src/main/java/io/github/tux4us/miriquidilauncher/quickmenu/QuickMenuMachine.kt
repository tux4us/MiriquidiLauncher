// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.quickmenu

/**
 * Zustand des Schnellmenüs.
 *
 * - [Closed]: Menü geschlossen.
 * - [Open]: Menü per Tippen auf den Button geöffnet und bleibt offen; ein Eintrag wird durch Antippen gewählt.
 *   Das Scrollen übernimmt die Oberfläche selbst (Wischen in der Liste).
 *
 * Es gibt bewusst nur diesen einen Weg: Tippen öffnet, Tippen auf einen Eintrag wählt. Eine Geste, bei der der
 * Finger auf dem Button liegen bleibt und über das Raster gleitet, gibt es nicht.
 */
sealed interface QuickMenuState {
    data object Closed : QuickMenuState
    data object Open : QuickMenuState
}

val QuickMenuState.isOpen: Boolean get() = this !is QuickMenuState.Closed

sealed interface QuickMenuEvent {
    /** Der Button wurde angetippt (öffnet das Menü bzw. schließt es, wenn es offen ist). */
    data object ShortPress : QuickMenuEvent

    /** Eintrag wurde im offenen Menü angetippt. */
    data class ItemTapped(val index: Int) : QuickMenuEvent

    /** Menü soll schließen (Tippen daneben, Zurück-Taste, Home-Taste). */
    data object Dismiss : QuickMenuEvent
}

sealed interface QuickMenuEffect {
    /** Den Eintrag mit diesem Index starten. */
    data class Launch(val index: Int) : QuickMenuEffect
}

data class QuickMenuTransition(
    val state: QuickMenuState,
    val effect: QuickMenuEffect? = null,
)

/** Reine Zustandsübergänge des Schnellmenüs, ohne Android-Abhängigkeiten. */
object QuickMenuMachine {

    fun reduce(state: QuickMenuState, event: QuickMenuEvent, layout: QuickMenuLayout): QuickMenuTransition =
        when (state) {
            QuickMenuState.Closed -> when (event) {
                QuickMenuEvent.ShortPress -> QuickMenuTransition(QuickMenuState.Open)
                else -> QuickMenuTransition(state)
            }

            QuickMenuState.Open -> when (event) {
                is QuickMenuEvent.ItemTapped ->
                    if (event.index in 0 until layout.itemCount) {
                        QuickMenuTransition(QuickMenuState.Closed, QuickMenuEffect.Launch(event.index))
                    } else {
                        QuickMenuTransition(state)
                    }

                QuickMenuEvent.ShortPress, QuickMenuEvent.Dismiss -> QuickMenuTransition(QuickMenuState.Closed)
            }
        }
}
