// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.feed

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Verschlüsselt kurze Geheimnisse (hier das App-Passwort) mit einem AES-256-Schlüssel im Android-Keystore.
 * Der Schlüssel verlässt den Keystore nicht; die gespeicherten Daten sind ohne ihn unbrauchbar.
 */
class SecretBox(private val alias: String) {

    /** Format: `<iv>:<chiffrat>`, beides Base64. */
    fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, obtainKey())
        val data = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return encode(cipher.iv) + ":" + encode(data)
    }

    /** Entschlüsselt [stored]; `null`, wenn die Daten oder der Schlüssel fehlen oder beschädigt sind. */
    fun decrypt(stored: String): String? {
        val parts = stored.split(':')
        if (parts.size != 2) return null
        return try {
            val key = existingKey() ?: return null
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, decode(parts[0])))
            String(cipher.doFinal(decode(parts[1])), Charsets.UTF_8)
        } catch (ignored: GeneralSecurityException) {
            null
        } catch (ignored: IOException) {
            null
        } catch (ignored: IllegalArgumentException) {
            null
        }
    }

    /** Löscht den Schlüssel; vorhandene Chiffrate sind danach nicht mehr lesbar. */
    fun deleteKey() {
        try {
            keyStore().deleteEntry(alias)
        } catch (ignored: GeneralSecurityException) {
            // Kein Schlüssel vorhanden oder Keystore nicht erreichbar: nichts zu löschen.
        } catch (ignored: IOException) {
            // Wie oben.
        }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun existingKey(): SecretKey? = keyStore().getKey(alias, null) as? SecretKey

    private fun obtainKey(): SecretKey {
        existingKey()?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun decode(text: String): ByteArray = Base64.decode(text, Base64.NO_WRAP)

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
    }
}
