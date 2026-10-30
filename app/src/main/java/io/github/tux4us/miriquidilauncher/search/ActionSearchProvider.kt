// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.search

import android.content.Context
import androidx.annotation.DrawableRes
import io.github.tux4us.miriquidilauncher.R
import java.text.DecimalFormatSymbols

/** Was beim Antippen eines [ActionResult] geschieht. */
sealed interface ResultAction {
    /** Öffnet eine Seite der Android-Einstellungen über ihre Intent-Aktion. */
    data class OpenSettings(val action: String) : ResultAction
    data class Call(val number: String) : ResultAction
    data class Sms(val number: String) : ResultAction
    data class Email(val address: String) : ResultAction
    data class OpenUrl(val url: String) : ResultAction
    data class WebSearch(val query: String) : ResultAction

    /** Übernimmt die Rechnung [expression] in die Scope „Rechner“. */
    data class OpenCalculator(val expression: String) : ResultAction

    /** Kopiert den Text in die Zwischenablage (Ergebnis einer Rechnung oder Umrechnung). */
    data class Copy(val text: String) : ResultAction
}

/** Treffer, der eine Aktion auslöst: Einstellungen, Rechnung, Telefonnummer, Web-Adresse oder Websuche. */
data class ActionResult(
    override val id: String,
    override val title: String,
    val subtitle: String?,
    override val score: Int,
    val action: ResultAction,
    @DrawableRes val icon: Int,
) : SearchResult

/**
 * Erkennt Rechnungen, Einheitenumrechnungen, Telefonnummern, E-Mail- und Web-Adressen in der Eingabe und
 * bietet zusätzlich "Im Web suchen" an. Es findet kein Netzwerkzugriff statt; die Websuche übergibt den Begriff
 * erst beim Antippen an den Browser des Geräts.
 */
class ActionSearchProvider(private val context: Context) : SearchProvider {
    override suspend fun search(query: SearchQuery): List<SearchResult> {
        val separator = DecimalFormatSymbols.getInstance().decimalSeparator
        return InputActions.detect(query.raw, separator).flatMap { detected ->
            val res = context.resources
            val main = when (detected.kind) {
                ActionKind.Calculation, ActionKind.Conversion -> ActionResult(
                    id = "action:calc:${detected.title}",
                    title = detected.title,
                    subtitle = res.getString(R.string.search_action_copy_hint),
                    score = detected.score,
                    action = ResultAction.Copy(detected.payload),
                    icon = R.drawable.ic_equals,
                )
                ActionKind.Call -> ActionResult(
                    id = "action:call:${detected.payload}",
                    title = res.getString(R.string.search_action_call, detected.payload),
                    subtitle = null,
                    score = detected.score,
                    action = ResultAction.Call(detected.payload),
                    icon = R.drawable.ic_call,
                )
                ActionKind.Sms -> ActionResult(
                    id = "action:sms:${detected.payload}",
                    title = res.getString(R.string.search_action_sms, detected.payload),
                    subtitle = null,
                    score = detected.score,
                    action = ResultAction.Sms(detected.payload),
                    icon = R.drawable.ic_message,
                )
                ActionKind.Email -> ActionResult(
                    id = "action:mail:${detected.payload}",
                    title = res.getString(R.string.search_action_email, detected.payload),
                    subtitle = null,
                    score = detected.score,
                    action = ResultAction.Email(detected.payload),
                    icon = R.drawable.ic_mail,
                )
                ActionKind.OpenUrl -> ActionResult(
                    id = "action:url:${detected.payload}",
                    title = res.getString(R.string.search_action_open_url, detected.title),
                    subtitle = detected.payload,
                    score = detected.score,
                    action = ResultAction.OpenUrl(detected.payload),
                    icon = R.drawable.ic_open_link,
                )
                ActionKind.WebSearch -> ActionResult(
                    id = "action:web:${detected.payload}",
                    title = res.getString(R.string.search_action_web, detected.payload),
                    subtitle = null,
                    score = detected.score,
                    action = ResultAction.WebSearch(detected.payload),
                    icon = R.drawable.ic_search,
                )
            }
            // Zu einer Rechnung kommt ein zweiter Treffer, der sie im Rechner öffnet.
            val expression = detected.expression
            if (detected.kind == ActionKind.Calculation && expression != null) {
                listOf(
                    main,
                    ActionResult(
                        id = "action:calc-open:$expression",
                        title = res.getString(R.string.search_action_open_calculator),
                        subtitle = res.getString(R.string.search_action_open_calculator_hint, expression),
                        score = detected.score - 1,
                        action = ResultAction.OpenCalculator(expression),
                        icon = R.drawable.ic_calculator,
                    ),
                )
            } else {
                listOf(main)
            }
        }
    }
}

/** Findet Seiten der Android-Einstellungen (WLAN, Display, Akku …) nach Name oder Stichwort. */
class SettingsSearchProvider(private val context: Context) : SearchProvider {
    override suspend fun search(query: SearchQuery): List<SearchResult> =
        SettingsCatalog.match(query.normalized).mapNotNull { (target, score) ->
            val label = labelFor(target.key) ?: return@mapNotNull null
            ActionResult(
                id = "settings:${target.key}",
                title = label,
                subtitle = context.getString(R.string.search_action_settings_hint),
                score = score,
                action = ResultAction.OpenSettings(target.action),
                icon = R.drawable.ic_settings,
            )
        }

    private fun labelFor(key: String): String? {
        val id = context.resources.getIdentifier("settings_target_$key", "string", context.packageName)
        return if (id != 0) context.getString(id) else null
    }
}
