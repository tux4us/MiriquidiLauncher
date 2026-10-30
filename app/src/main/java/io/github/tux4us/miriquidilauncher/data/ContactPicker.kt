// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone

/** Ergebnis der Kontaktauswahl: der Favorit und die Adressen seiner Fotos (größtes zuerst, falls vorhanden). */
class PickedContact(val contact: FavoriteContact, val photoUris: List<Uri>)

/**
 * Auswahl einer Telefonnummer über die System-Kontaktauswahl. Die Auswahl gibt der App einen
 * temporären Lesezugriff auf genau den gewählten Eintrag; die Berechtigung READ_CONTACTS ist dafür
 * nicht nötig. Name, Nummer und Foto-Adressen werden sofort ausgelesen; Name und Nummer werden lokal
 * gespeichert, das Foto als verkleinerte Kopie (siehe [ContactPhotos.savePicked]).
 */
object ContactPicker {
    fun pickIntent(): Intent = Intent(Intent.ACTION_PICK, Phone.CONTENT_URI)

    fun read(context: Context, uri: Uri): PickedContact? = try {
        context.contentResolver.query(
            uri,
            arrayOf(
                Phone.DISPLAY_NAME,
                Phone.NUMBER,
                ContactsContract.Contacts.PHOTO_URI,
                ContactsContract.Contacts.PHOTO_THUMBNAIL_URI,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val name = cursor.getString(0).orEmpty()
                val number = cursor.getString(1).orEmpty()
                val photos = listOfNotNull(cursor.getString(2), cursor.getString(3)).map(Uri::parse)
                if (number.isBlank()) null else PickedContact(FavoriteContact(name.ifBlank { number }, number), photos)
            } else {
                null
            }
        }
    } catch (ignored: SecurityException) {
        null
    } catch (ignored: IllegalArgumentException) {
        null
    }
}
