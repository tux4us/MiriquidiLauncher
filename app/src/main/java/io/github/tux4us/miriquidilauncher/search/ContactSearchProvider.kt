// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.search

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.CancellationSignal
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext

data class ContactResult(
    val lookupUri: Uri,
    override val title: String,
    override val score: Int,
    /** Foto des Kontakts (verkleinert) oder `null`; nur mit der Berechtigung READ_CONTACTS lesbar. */
    val photoUri: Uri? = null,
) : SearchResult {
    override val id: String = "contact:$lookupUri"
}

/**
 * Durchsucht Kontakte über den Contacts-Provider des Geräts. Ohne die Berechtigung
 * READ_CONTACTS liefert der Provider keine Treffer. Es findet keinerlei Netzwerkzugriff statt.
 */
class ContactSearchProvider(context: Context) : SearchProvider {
    private val appContext = context.applicationContext

    override suspend fun search(query: SearchQuery): List<SearchResult> {
        val granted = ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return emptyList()
        return withContext(Dispatchers.IO) { queryContacts(query) }
    }

    private suspend fun queryContacts(query: SearchQuery): List<SearchResult> {
        val uri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_FILTER_URI, Uri.encode(query.raw))
        val projection = arrayOf(
            ContactsContract.Contacts._ID,
            ContactsContract.Contacts.LOOKUP_KEY,
            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
            ContactsContract.Contacts.PHOTO_THUMBNAIL_URI,
        )

        // Die blockierende Datenbankabfrage wird mit der Coroutine abgebrochen.
        val signal = CancellationSignal()
        val handle = currentCoroutineContext().job.invokeOnCompletion { cause ->
            if (cause != null) signal.cancel()
        }

        val results = ArrayList<SearchResult>()
        try {
            appContext.contentResolver.query(
                uri,
                projection,
                null,
                null,
                "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} COLLATE LOCALIZED ASC",
                signal,
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(ContactsContract.Contacts._ID)
                val lookupColumn = cursor.getColumnIndexOrThrow(ContactsContract.Contacts.LOOKUP_KEY)
                val nameColumn = cursor.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
                val photoColumn = cursor.getColumnIndexOrThrow(ContactsContract.Contacts.PHOTO_THUMBNAIL_URI)
                while (cursor.moveToNext() && results.size < MAX_CONTACTS) {
                    val name = cursor.getString(nameColumn)?.takeIf { it.isNotBlank() } ?: continue
                    val lookupKey = cursor.getString(lookupColumn) ?: continue
                    val lookupUri = ContactsContract.Contacts.getLookupUri(cursor.getLong(idColumn), lookupKey)
                        ?: continue
                    val score = Matcher
                        .score(query.normalized, TextNormalizer.normalize(name))
                        .coerceAtLeast(MIN_CONTACT_SCORE)
                    val photoUri = cursor.getString(photoColumn)?.let(Uri::parse)
                    results += ContactResult(lookupUri, name, score, photoUri)
                }
            }
        } finally {
            handle.dispose()
        }
        return results
    }

    private companion object {
        const val MAX_CONTACTS = 20

        /** Der Provider hat den Kontakt als Treffer geliefert, auch wenn der Matcher ihn niedrig bewertet. */
        const val MIN_CONTACT_SCORE = 100
    }
}
