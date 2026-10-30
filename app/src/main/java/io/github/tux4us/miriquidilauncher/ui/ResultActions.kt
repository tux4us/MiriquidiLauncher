// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.ui

import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import io.github.tux4us.miriquidilauncher.R
import io.github.tux4us.miriquidilauncher.search.ResultAction

/**
 * Führt die Aktion eines Suchtreffers aus. Anruf und SMS öffnen nur die Wähl- bzw. Nachrichten-App mit
 * vorbelegter Nummer; die Websuche übergibt den Begriff an den Browser des Geräts. Gibt `false` zurück, wenn keine
 * App die Aktion ausführen kann.
 */
internal fun runResultAction(context: Context, action: ResultAction): Boolean {
    val intent = when (action) {
        is ResultAction.OpenSettings -> Intent(action.action)
        is ResultAction.Call -> Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", action.number, null))
        is ResultAction.Sms -> Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", action.number, null))
        is ResultAction.Email -> Intent(Intent.ACTION_SENDTO, Uri.fromParts("mailto", action.address, null))
        is ResultAction.OpenUrl -> Intent(Intent.ACTION_VIEW, Uri.parse(action.url))
        is ResultAction.WebSearch -> Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, action.query)
        // Wird von der Startseite behandelt (öffnet die Scope „Rechner“), nicht über einen Intent.
        is ResultAction.OpenCalculator -> return false
        is ResultAction.Copy -> {
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            clipboard?.setPrimaryClip(ClipData.newPlainText("result", action.text))
            Toast.makeText(context, context.getString(R.string.search_action_copied), Toast.LENGTH_SHORT).show()
            return clipboard != null
        }
    }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
        context.startActivity(intent)
        true
    } catch (ignored: ActivityNotFoundException) {
        // Ohne Browser mit Websuche: Suchseite direkt öffnen.
        if (action is ResultAction.WebSearch) {
            try {
                val fallback = Uri.parse("https://duckduckgo.com/").buildUpon().appendQueryParameter("q", action.query).build()
                context.startActivity(Intent(Intent.ACTION_VIEW, fallback).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            } catch (ignoredToo: ActivityNotFoundException) {
                false
            }
        } else {
            false
        }
    } catch (ignored: SecurityException) {
        false
    }
}
