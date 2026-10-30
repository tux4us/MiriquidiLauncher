// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactWaysLogicTest {
    private val installed = listOf(
        "org.thoughtcrime.securesms",
        "com.whatsapp",
        "org.telegram.messenger",
        "org.telegram.messenger.web",
        "ch.threema.app",
    )

    @Test
    fun standardTypesAreNotAppTypes() {
        assertFalse(ContactWaysLogic.isAppMime("vnd.android.cursor.item/phone_v2"))
        assertFalse(ContactWaysLogic.isAppMime("vnd.android.cursor.item/email_v2"))
        assertFalse(ContactWaysLogic.isAppMime("vnd.android.cursor.item/name"))
        assertFalse(ContactWaysLogic.isAppMime("vnd.com.google.cursor.item/contact_misc"))
        assertTrue(ContactWaysLogic.isAppMime("vnd.android.cursor.item/vnd.com.whatsapp.profile"))
    }

    @Test
    fun packageIsRecognizedFromMimeType() {
        assertEquals(
            "org.thoughtcrime.securesms",
            ContactWaysLogic.packageOf("vnd.android.cursor.item/vnd.org.thoughtcrime.securesms.contact", installed),
        )
        assertEquals(
            "com.whatsapp",
            ContactWaysLogic.packageOf("vnd.android.cursor.item/vnd.com.whatsapp.voip.call", installed),
        )
        assertEquals(
            "ch.threema.app",
            ContactWaysLogic.packageOf("vnd.android.cursor.item/vnd.ch.threema.app.profile", installed),
        )
    }

    @Test
    fun longestMatchingPackageWins() {
        assertEquals(
            "org.telegram.messenger.web",
            ContactWaysLogic.packageOf("vnd.android.cursor.item/vnd.org.telegram.messenger.web.profile", installed),
        )
        assertEquals(
            "org.telegram.messenger",
            ContactWaysLogic.packageOf("vnd.android.cursor.item/vnd.org.telegram.messenger.android.profile", installed),
        )
    }

    @Test
    fun uninstalledOrUnrelatedTypesGiveNull() {
        assertNull(ContactWaysLogic.packageOf("vnd.android.cursor.item/vnd.com.unknown.app.profile", installed))
        assertNull(ContactWaysLogic.packageOf("vnd.android.cursor.item/phone_v2", installed))
        // ein Paketname, der nur als Wortanfang übereinstimmt, zählt nicht
        assertNull(ContactWaysLogic.packageOf("vnd.android.cursor.item/vnd.com.whatsappfake.profile", installed))
    }

    @Test
    fun labelFallbacks() {
        assertEquals("Nachricht +49 170", ContactWaysLogic.appLabel(" Nachricht +49 170 ", "Signal", "x"))
        assertEquals("Signal", ContactWaysLogic.appLabel("  ", "Signal", "x"))
        assertEquals("x", ContactWaysLogic.appLabel(null, null, "x"))
    }

    @Test
    fun sameNumberIgnoresFormattingAndCountryCode() {
        assertTrue(ContactWaysLogic.sameNumber("+49 170 1234567", "01701234567"))
        assertTrue(ContactWaysLogic.sameNumber("0170/1234567", "+491701234567"))
        assertFalse(ContactWaysLogic.sameNumber("+49 170 1234567", "+49 170 7654321"))
        assertFalse(ContactWaysLogic.sameNumber("", "0170"))
        // zu kurze Nummern werden nur bei vollständiger Übereinstimmung gleichgesetzt
        assertFalse(ContactWaysLogic.sameNumber("112", "0049112"))
        assertTrue(ContactWaysLogic.sameNumber("112", "1 1 2"))
    }

    @Test
    fun storedNumberComesFirstWithoutDuplicates() {
        val numbers = listOf("0301111", "0170 1234567", "0302222")
        assertEquals(
            listOf("0170 1234567", "0301111", "0302222"),
            ContactWaysLogic.withNumberFirst(numbers, "+49 170 1234567"),
        )
        assertEquals(listOf("0999") + numbers, ContactWaysLogic.withNumberFirst(numbers, "0999"))
        assertEquals(numbers, ContactWaysLogic.withNumberFirst(numbers, ""))
    }
}
