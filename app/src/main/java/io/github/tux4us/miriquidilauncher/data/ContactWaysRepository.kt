// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.data

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Phone
import androidx.core.content.ContextCompat

/**
 * Liest die Kontaktwege eines Favoriten aus dem Adressbuch: alle Telefonnummern (Anruf und SMS), E-Mail-Adressen
 * und die Einträge, die Messenger-Apps (Signal, Telegram, WhatsApp, Threema …) dort hinterlegt haben. Der
 * Kontakt wird über die gespeicherte Nummer gefunden. Ohne `READ_CONTACTS` oder ohne Treffer bleibt nur die
 * gespeicherte Nummer (Anruf und SMS). Es wird nur gelesen; es findet kein Netzwerkzugriff statt.
 */
class ContactWaysRepository(context: Context) {
    private val appContext = context.applicationContext

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * @param installed Paketname → Name der Apps, die der Launcher kennt (nur deren Einträge sind nutzbar)
     */
    fun load(contact: FavoriteContact, installed: Map<String, String>): ContactWays {
        val basic = basicWays(listOf(contact.number to ""))
        if (!hasPermission()) return ContactWays(basic, null, fromContacts = false)
        return try {
            val found = lookup(contact.number) ?: return ContactWays(basic, null, fromContacts = false)
            val ways = readWays(found.contactId, contact.number, installed)
            ContactWays(ways, found.lookupUri, fromContacts = true)
        } catch (ignored: SecurityException) {
            ContactWays(basic, null, fromContacts = false)
        } catch (ignored: IllegalArgumentException) {
            ContactWays(basic, null, fromContacts = false)
        }
    }

    private class Found(val contactId: Long, val lookupUri: String)

    private fun lookup(number: String): Found? {
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        appContext.contentResolver.query(
            uri,
            arrayOf(ContactsContract.PhoneLookup.CONTACT_ID, ContactsContract.PhoneLookup.LOOKUP_KEY),
            null,
            null,
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return null
            val id = cursor.getLong(0)
            val key = cursor.getString(1) ?: return null
            return Found(id, ContactsContract.Contacts.getLookupUri(id, key).toString())
        }
        return null
    }

    private fun readWays(contactId: Long, storedNumber: String, installed: Map<String, String>): List<ContactWay> {
        val numbers = ArrayList<Pair<String, String>>()
        val emails = ArrayList<ContactWay>()
        val viaApp = ArrayList<ContactWay>()
        val seenEmails = HashSet<String>()
        val seenApp = HashSet<String>()
        val resources = appContext.resources
        appContext.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(
                ContactsContract.Data._ID,
                ContactsContract.Data.MIMETYPE,
                ContactsContract.Data.DATA1,
                ContactsContract.Data.DATA2,
                ContactsContract.Data.DATA3,
            ),
            "${ContactsContract.Data.CONTACT_ID} = ?",
            arrayOf(contactId.toString()),
            null,
        )?.use { cursor ->
            var rows = 0
            while (cursor.moveToNext() && rows++ < MAX_ROWS) {
                val id = cursor.getLong(0)
                val mime = cursor.getString(1).orEmpty()
                val data1 = cursor.getString(2).orEmpty()
                // DATA2 ist bei Telefon und E-Mail die Art (Zahl), bei Apps ein beliebiger Text.
                val type = cursor.getString(3)?.toIntOrNull() ?: 0
                val customLabel = cursor.getString(4)
                when {
                    mime == Phone.CONTENT_ITEM_TYPE && data1.isNotBlank() -> {
                        val label = Phone.getTypeLabel(resources, type, customLabel).toString()
                        numbers.add(data1 to label)
                    }
                    mime == Email.CONTENT_ITEM_TYPE && data1.isNotBlank() -> {
                        if (seenEmails.add(data1.lowercase())) {
                            val label = Email.getTypeLabel(resources, type, customLabel).toString()
                            emails.add(ContactWay.Email(data1, label))
                        }
                    }
                    ContactWaysLogic.isAppMime(mime) -> {
                        val pkg = ContactWaysLogic.packageOf(mime, installed.keys) ?: continue
                        if (!seenApp.add("$mime|$data1")) continue
                        val label = ContactWaysLogic.appLabel(customLabel, installed[pkg], pkg)
                        viaApp.add(ContactWay.ViaApp(id, mime, pkg, label))
                    }
                }
            }
        }
        val ordered = ContactWaysLogic.withNumberFirst(numbers.map { it.first }, storedNumber)
        val labelOf = numbers.associate { it.first to it.second }
        val phoneWays = basicWays(ordered.map { it to labelOf[it].orEmpty() })
        return phoneWays + viaApp + emails
    }

    /** Anruf und SMS für jede Nummer; doppelte Nummern entfallen. */
    private fun basicWays(numbers: List<Pair<String, String>>): List<ContactWay> {
        val seen = HashSet<String>()
        val unique = numbers.filter { seen.add(ContactWaysLogic.normalizeNumber(it.first)) }
        return unique.map { (number, label) -> ContactWay.Call(number, label.ifBlank { number }) } +
            unique.map { (number, label) -> ContactWay.Sms(number, label.ifBlank { number }) }
    }

    private companion object {
        const val MAX_ROWS = 300
    }
}

/** Öffnet einen [ContactWay] (außer Anruf, der über den Anruf-Dialog läuft). */
object ContactWayLauncher {
    /** Gibt `false` zurück, wenn keine App den Weg öffnen konnte. */
    fun open(context: Context, way: ContactWay): Boolean {
        val intent = when (way) {
            is ContactWay.Call -> return false
            is ContactWay.Sms -> Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", way.number, null))
            is ContactWay.Email -> Intent(Intent.ACTION_SENDTO, Uri.fromParts("mailto", way.address, null))
            is ContactWay.ViaApp -> Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(ContentUris.withAppendedId(ContactsContract.Data.CONTENT_URI, way.dataId), way.mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                way.packageName?.let { setPackage(it) }
            }
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (ignored: ActivityNotFoundException) {
            false
        } catch (ignored: SecurityException) {
            false
        }
    }

    /** Öffnet den Kontakt in der Kontakte-App. */
    fun openContact(context: Context, lookupUri: String): Boolean = try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(lookupUri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (ignored: ActivityNotFoundException) {
        false
    } catch (ignored: SecurityException) {
        false
    }
}
