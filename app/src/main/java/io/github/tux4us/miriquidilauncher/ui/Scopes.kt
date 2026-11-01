// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import io.github.tux4us.miriquidilauncher.R

/**
 * Eine Scope ist eine Seite neben der Startseite, die per Wischen oder über den Scopes-Button erreichbar ist.
 * Die Reihenfolge hier ist die Standardreihenfolge im Scopes-Menü und im seitlichen Pager (rechts neben der Startseite);
 * in den Einstellungen lässt sie sich ändern (siehe `ScopeVisibility.orderOf`).
 * Scopes mit [canHide] lassen sich in den Einstellungen ausblenden; die Einstellungen selbst bleiben immer sichtbar.
 * Eine neue Scope braucht einen Eintrag hier und einen Zweig im seitlichen Pager von `LauncherScreen`.
 */
enum class Scope(@StringRes val label: Int, @DrawableRes val icon: Int, val canHide: Boolean = true) {
    AppFavorites(R.string.scope_app_favorites, R.drawable.ic_star),
    ContactFavorites(R.string.scope_contact_favorites, R.drawable.ic_person),
    AppGroups(R.string.scope_app_groups, R.drawable.ic_folder),
    Weather(R.string.scope_weather, R.drawable.ic_weather),
    Calendar(R.string.scope_calendar, R.drawable.ic_calendar),
    Feed(R.string.scope_feed, R.drawable.ic_feed),
    Podcasts(R.string.scope_podcasts, R.drawable.ic_podcasts),
    Markets(R.string.scope_markets, R.drawable.ic_markets),
    Calculator(R.string.scope_calculator, R.drawable.ic_calculator),
    System(R.string.scope_system, R.drawable.ic_system),
    Files(R.string.scope_files, R.drawable.ic_files),
    Gallery(R.string.scope_gallery, R.drawable.ic_gallery),
    Notes(R.string.scope_notes, R.drawable.ic_notes),
    Settings(R.string.scope_settings, R.drawable.ic_settings, canHide = false),
    Camera(R.string.scope_camera, R.drawable.ic_camera),
}

/** Namen aller Scopes in Standardreihenfolge und die der nicht ausblendbaren. */
internal val ScopeNames: List<String> = Scope.entries.map { it.name }
internal val PinnedScopeNames: Set<String> = Scope.entries.filter { !it.canHide }.map { it.name }.toSet()
