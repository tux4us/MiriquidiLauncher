// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.backup

import java.io.ByteArrayOutputStream
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Übertragung eines Backups als QR-Code(s) zwischen zwei Geräten. Frei von Android-Klassen, damit es auf der JVM
 * testbar ist.
 *
 * Ablauf: Backup-JSON → UTF-8 → Deflate → optional AES-256-GCM (Schlüssel per PBKDF2-HMAC-SHA256 aus dem Passwort)
 * → Base45 (RFC 9285) → in Stücke zu höchstens [PART_CHARS] Zeichen geteilt. Base45 nutzt nur Zeichen des
 * QR-Modus "alphanumerisch" (rund 3 % Mehraufwand gegenüber Rohbytes, unabhängig von Zeichensatz-Erkennung beim Lesen).
 *
 * Jeder Code trägt eine Kopfzeile `MQB1:<Satz-ID>:<Nr>:<Anzahl>:<P|E>:<Nutzdaten>`. Die Reihenfolge beim Scannen ist
 * beliebig; die Satz-ID trennt Codes verschiedener Backups.
 */
object BackupQr {
    const val MAGIC = "MQB1"

    /** Höchstzahl Nutzdaten-Zeichen je Code (QR-Stufe M, Version etwa 28). Auf Geräten gegenprüfen. */
    const val PART_CHARS = 1100

    /** Mehr Codes werden weder erzeugt noch angenommen. */
    const val MAX_PARTS = 16
    const val MIN_PASSWORD_LENGTH = 6

    private const val PBKDF2_ITERATIONS = 150_000
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val KEY_BITS = 256

    /** Das Backup braucht mehr als [MAX_PARTS] Codes. */
    class TooLargeException : Exception("Backup zu groß für QR-Codes")

    /** Passwort falsch oder Daten verändert (GCM unterscheidet beides nicht). */
    class WrongPasswordException : Exception("Falsches Passwort")

    /** Gesammelte, noch nicht entschlüsselte und entpackte Nutzdaten. */
    class Collected(val data: ByteArray, val encrypted: Boolean)

    /** Erzeugt die Texte der QR-Codes für [json]. [password] `null` = unverschlüsselt. */
    fun createParts(json: String, password: CharArray?, random: SecureRandom = SecureRandom()): List<String> {
        var bytes = deflate(json.toByteArray(Charsets.UTF_8))
        if (password != null) bytes = encrypt(bytes, password, random)
        val payload = Base45.encode(bytes)
        val chunks = payload.chunked(PART_CHARS)
        if (chunks.size > MAX_PARTS) throw TooLargeException()
        val setId = String.format("%04X", random.nextInt(0x10000))
        val flag = if (password != null) 'E' else 'P'
        return chunks.mapIndexed { i, chunk -> "$MAGIC:$setId:${i + 1}:${chunks.size}:$flag:$chunk" }
    }

    /** Entschlüsselt und entpackt [collected]; Ergebnis ist der JSON-Text für [BackupCodec.decode]. */
    fun open(collected: Collected, password: CharArray?): String {
        var bytes = collected.data
        if (collected.encrypted) {
            if (password == null) throw WrongPasswordException()
            bytes = decrypt(bytes, password)
        }
        return String(inflate(bytes), Charsets.UTF_8)
    }

    /** Sammelt die Codes eines Backups. Nicht threadsicher; gehört der Oberfläche. */
    class Collector {
        sealed interface Status {
            /** Kein Code dieses Formats; Zustand unverändert. */
            data object Foreign : Status

            /** Teil aufgenommen (oder schon bekannt). [newSet]: ein Code eines anderen Backups hat den Stand ersetzt. */
            data class Progress(val received: Set<Int>, val total: Int, val newSet: Boolean) : Status

            /** Alle Teile liegen vor. */
            data class Complete(val collected: Collected) : Status

            /** Nutzdaten zerstört; der Stand wurde verworfen. */
            data object Corrupt : Status
        }

        private var setId: String? = null
        private var total = 0
        private var encrypted = false
        private val parts = HashMap<Int, String>()

        val received: Set<Int> get() = parts.keys.toSortedSet()
        val totalParts: Int get() = total

        fun reset() {
            setId = null
            total = 0
            parts.clear()
        }

        fun add(text: String): Status {
            val fields = text.split(':', limit = 6)
            if (fields.size != 6 || fields[0] != MAGIC) return Status.Foreign
            val id = fields[1]
            val index = fields[2].toIntOrNull()
            val count = fields[3].toIntOrNull()
            val flag = fields[4]
            val payload = fields[5]
            if (id.length != 4 || !id.all { it in HEX } || index == null || count == null) return Status.Foreign
            if (count !in 1..MAX_PARTS || index !in 1..count || (flag != "P" && flag != "E")) return Status.Foreign
            if (payload.isEmpty() || !Base45.isValid(payload)) return Status.Foreign

            var newSet = false
            if (id != setId || count != total) {
                newSet = setId != null
                reset()
                setId = id
                total = count
                encrypted = flag == "E"
            }
            parts[index] = payload
            if (parts.size < total) return Status.Progress(received, total, newSet)
            val joined = (1..total).joinToString("") { parts.getValue(it) }
            val data = try {
                Base45.decode(joined)
            } catch (e: IllegalArgumentException) {
                reset()
                return Status.Corrupt
            }
            return Status.Complete(Collected(data, encrypted))
        }
    }

    // ---- Verschlüsselung -------------------------------------------------------------------------------------

    private fun deriveKey(password: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, PBKDF2_ITERATIONS, KEY_BITS)
        try {
            val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return SecretKeySpec(key, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    private fun encrypt(plain: ByteArray, password: CharArray, random: SecureRandom): ByteArray {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(password, salt), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(MAGIC.toByteArray(Charsets.US_ASCII))
        return salt + iv + cipher.doFinal(plain)
    }

    private fun decrypt(data: ByteArray, password: CharArray): ByteArray {
        if (data.size < SALT_BYTES + IV_BYTES + TAG_BITS / 8) throw BackupCodec.BackupException("Daten zu kurz")
        val salt = data.copyOfRange(0, SALT_BYTES)
        val iv = data.copyOfRange(SALT_BYTES, SALT_BYTES + IV_BYTES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, deriveKey(password, salt), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(MAGIC.toByteArray(Charsets.US_ASCII))
        return try {
            cipher.doFinal(data, SALT_BYTES + IV_BYTES, data.size - SALT_BYTES - IV_BYTES)
        } catch (e: GeneralSecurityException) {
            throw WrongPasswordException()
        }
    }

    // ---- Kompression -----------------------------------------------------------------------------------------

    private fun deflate(input: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION, true)
        try {
            deflater.setInput(input)
            deflater.finish()
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
            return out.toByteArray()
        } finally {
            deflater.end()
        }
    }

    /** Entpackt mit Obergrenze [BackupCodec.MAX_BYTES], damit manipulierte Daten keinen Speicher füllen. */
    private fun inflate(input: ByteArray): ByteArray {
        val inflater = Inflater(true)
        try {
            inflater.setInput(input)
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (!inflater.finished()) {
                val n = inflater.inflate(buffer)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    throw BackupCodec.BackupException("Daten unvollständig")
                }
                out.write(buffer, 0, n)
                if (out.size() > BackupCodec.MAX_BYTES) throw BackupCodec.BackupException("Datei zu groß")
            }
            return out.toByteArray()
        } catch (e: DataFormatException) {
            throw BackupCodec.BackupException("Daten beschädigt")
        } finally {
            inflater.end()
        }
    }

    private val HEX = "0123456789ABCDEF".toSet()

    /** Base45 nach RFC 9285. */
    object Base45 {
        private const val ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ \$%*+-./:"

        fun isValid(s: String): Boolean = s.all { ALPHABET.indexOf(it) >= 0 }

        fun encode(data: ByteArray): String {
            val sb = StringBuilder(data.size * 3 / 2 + 3)
            var i = 0
            while (i + 1 < data.size) {
                val n = (data[i].toInt() and 0xFF) * 256 + (data[i + 1].toInt() and 0xFF)
                sb.append(ALPHABET[n % 45]).append(ALPHABET[n / 45 % 45]).append(ALPHABET[n / 2025])
                i += 2
            }
            if (i < data.size) {
                val n = data[i].toInt() and 0xFF
                sb.append(ALPHABET[n % 45]).append(ALPHABET[n / 45])
            }
            return sb.toString()
        }

        /** @throws IllegalArgumentException bei ungültigen Zeichen, Länge oder Werten außerhalb des Bereichs. */
        fun decode(text: String): ByteArray {
            require(text.length % 3 != 1) { "Ungültige Länge" }
            val out = ByteArrayOutputStream(text.length * 2 / 3)
            var i = 0
            while (i + 2 < text.length) {
                val n = value(text[i]) + value(text[i + 1]) * 45 + value(text[i + 2]) * 2025
                require(n <= 0xFFFF) { "Wert zu groß" }
                out.write(n / 256)
                out.write(n % 256)
                i += 3
            }
            if (i < text.length) {
                val n = value(text[i]) + value(text[i + 1]) * 45
                require(n <= 0xFF) { "Wert zu groß" }
                out.write(n)
            }
            return out.toByteArray()
        }

        private fun value(c: Char): Int {
            val v = ALPHABET.indexOf(c)
            require(v >= 0) { "Ungültiges Zeichen" }
            return v
        }
    }
}
