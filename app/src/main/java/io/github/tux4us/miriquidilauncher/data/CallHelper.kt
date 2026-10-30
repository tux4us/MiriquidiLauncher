// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.data

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import androidx.core.content.ContextCompat

/** Eine SIM-Karte (genauer: ein Anruf-Konto), mit der ein Anruf gestartet werden kann. */
data class SimOption(val handle: PhoneAccountHandle, val label: String)

/**
 * Startet Anrufe. Mit CALL_PHONE wird sofort gewählt, ohne die Berechtigung öffnet sich die
 * Telefon-App mit der vorbelegten Nummer. Die Liste der SIM-Karten braucht READ_PHONE_STATE;
 * fehlt sie, ist die Liste leer und das System wählt die Standard-SIM (oder fragt selbst nach).
 */
class CallHelper(context: Context) {
    private val context = context.applicationContext

    val canCallDirectly: Boolean get() = granted(Manifest.permission.CALL_PHONE)

    /** SIM-Karten, die telefonieren können. Leer bei fehlender Berechtigung oder ohne Telefonfunktion. */
    fun simOptions(): List<SimOption> {
        if (!granted(Manifest.permission.READ_PHONE_STATE)) return emptyList()
        return try {
            val telecom = context.getSystemService(TelecomManager::class.java) ?: return emptyList()
            telecom.callCapablePhoneAccounts.mapIndexed { index, handle ->
                val label = telecom.getPhoneAccount(handle)?.label?.toString().orEmpty()
                SimOption(handle, if (label.isBlank()) "SIM ${index + 1}" else "SIM ${index + 1} · $label")
            }
        } catch (ignored: SecurityException) {
            emptyList()
        }
    }

    /** Gibt `false` zurück, wenn der Anruf nicht gestartet werden konnte. */
    fun call(number: String, sim: SimOption?): Boolean {
        val action = if (canCallDirectly) Intent.ACTION_CALL else Intent.ACTION_DIAL
        val intent = Intent(action, Uri.fromParts("tel", number, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (sim != null) intent.putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, sim.handle)
        return try {
            context.startActivity(intent)
            true
        } catch (ignored: ActivityNotFoundException) {
            false
        } catch (ignored: SecurityException) {
            false
        }
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
