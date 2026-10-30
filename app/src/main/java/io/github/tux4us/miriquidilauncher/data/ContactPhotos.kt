// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.ContactsContract
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Kontaktfotos aus dem Adressbuch. Es findet kein Netzwerkzugriff statt.
 *
 * Favoriten-Kontakte werden über ihre Telefonnummer gefunden:
 * 1. Mit der Berechtigung READ_CONTACTS wird das Foto live aus dem Adressbuch gelesen (so folgt es
 *    Änderungen) und zugleich lokal zwischengespeichert.
 * 2. Ohne Berechtigung (oder wenn der Kontakt nicht mehr auffindbar ist) dient die lokale Kopie, die
 *    beim Hinzufügen über die System-Kontaktauswahl angelegt wurde ([savePicked]).
 *
 * Die Kopien liegen im privaten App-Speicher (`files/contact_photos`) und sind nicht Teil des Backups.
 */
class ContactPhotos(context: Context) {
    private val appContext = context.applicationContext
    private val directory = File(appContext.filesDir, DIRECTORY)

    /** Foto eines Favoriten-Kontakts oder `null`, wenn keines vorhanden ist. */
    suspend fun load(contact: FavoriteContact): ImageBitmap? = withContext(Dispatchers.IO) {
        if (hasContactsPermission()) {
            val lookup = lookupPhotoUri(contact.number)
            if (lookup.found) {
                val bitmap = lookup.uri?.let { decode(it) }
                if (bitmap != null) {
                    store(contact, bitmap)
                    return@withContext bitmap.asImageBitmap()
                }
                // Der Kontakt existiert, hat aber kein (lesbares) Foto mehr: veraltete Kopie entfernen.
                if (lookup.uri == null) cacheFile(contact).delete()
            }
        }
        readCache(contact)?.asImageBitmap()
    }

    /** Foto zu einer Foto-Adresse aus der Kontaktsuche (nur mit READ_CONTACTS lesbar). */
    suspend fun load(uri: Uri): ImageBitmap? = withContext(Dispatchers.IO) { decode(uri)?.asImageBitmap() }

    /** Legt beim Hinzufügen eines Kontakts eine lokale Kopie des Fotos an, solange der Zugriff der Auswahl gilt. */
    suspend fun savePicked(contact: FavoriteContact, photoUris: List<Uri>) {
        withContext(Dispatchers.IO) {
            for (uri in photoUris) {
                val bitmap = decode(uri)
                if (bitmap != null) {
                    store(contact, bitmap)
                    return@withContext
                }
            }
        }
    }

    /** Entfernt die lokale Kopie, wenn der Kontakt aus den Favoriten entfernt wird. */
    suspend fun delete(contact: FavoriteContact) {
        withContext(Dispatchers.IO) { cacheFile(contact).delete() }
    }

    private fun hasContactsPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    private class Lookup(val found: Boolean, val uri: Uri?)

    private fun lookupPhotoUri(number: String): Lookup {
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        return try {
            appContext.contentResolver.query(
                uri,
                arrayOf(ContactsContract.Contacts.PHOTO_URI, ContactsContract.Contacts.PHOTO_THUMBNAIL_URI),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val full = cursor.getString(0)
                    val thumbnail = cursor.getString(1)
                    Lookup(found = true, uri = (full ?: thumbnail)?.let(Uri::parse))
                } else {
                    Lookup(found = false, uri = null)
                }
            } ?: Lookup(found = false, uri = null)
        } catch (ignored: SecurityException) {
            Lookup(found = false, uri = null)
        } catch (ignored: IllegalArgumentException) {
            Lookup(found = false, uri = null)
        }
    }

    /** Liest ein Bild und verkleinert es beim Dekodieren auf höchstens [MAX_EDGE_PX] Pixel Kantenlänge. */
    private fun decode(uri: Uri): Bitmap? = try {
        val resolver = appContext.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) {
            null
        } else {
            var sample = 1
            while (longest / (sample * 2) >= MAX_EDGE_PX) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        }
    } catch (ignored: IOException) {
        null
    } catch (ignored: SecurityException) {
        null
    } catch (ignored: IllegalArgumentException) {
        null
    }

    private fun cacheFile(contact: FavoriteContact) = File(directory, "${contact.key}.jpg")

    private fun readCache(contact: FavoriteContact): Bitmap? {
        val file = cacheFile(contact)
        return if (file.isFile) BitmapFactory.decodeFile(file.path) else null
    }

    private fun store(contact: FavoriteContact, bitmap: Bitmap) {
        try {
            directory.mkdirs()
            val target = cacheFile(contact)
            val temporary = File(directory, "${contact.key}.tmp")
            temporary.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
            if (!temporary.renameTo(target)) temporary.delete()
        } catch (ignored: IOException) {
            // Nur Zwischenspeicher: ohne Kopie wird das Foto beim nächsten Mal erneut gelesen.
        }
    }

    private companion object {
        const val DIRECTORY = "contact_photos"
        const val MAX_EDGE_PX = 256
        const val JPEG_QUALITY = 90
    }
}
