// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.webapps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebAppLogicTest {
    private fun app(id: String, name: String = "N$id", url: String = "https://example.org/$id") = WebApp(id, name, url)

    private fun ok(input: String): String = (WebAppLogic.checkUrl(input) as UrlCheck.Ok).url

    // --- Adresse ---

    @Test
    fun httpsAddressIsKeptAndHostLowercased() {
        assertEquals("https://example.org/Pfad?x=1#a", ok("https://Example.ORG/Pfad?x=1#a"))
        assertEquals("https://example.org", ok("  HTTPS://example.org  "))
    }

    @Test
    fun missingSchemeGetsHttps() {
        assertEquals("https://example.org/app", ok("example.org/app"))
        assertEquals("https://example.org:8443/app", ok("example.org:8443/app"))
        assertEquals("https://192.168.1.10:5000", ok("192.168.1.10:5000"))
        assertEquals("https://nas", ok("nas"))
    }

    @Test
    fun explicitHttpIsRejectedAsNotHttps() {
        assertEquals(UrlCheck.NotHttps, WebAppLogic.checkUrl("http://example.org"))
        assertEquals(UrlCheck.NotHttps, WebAppLogic.checkUrl("HTTP://example.org"))
    }

    @Test
    fun otherSchemesAreInvalid() {
        for (bad in listOf(
            "javascript:alert(1)", "data:text/html,<b>x</b>", "file:///sdcard/x.html", "content://media/x", "intent://x#Intent;end",
            "ftp://example.org", "mailto:a@b.de", "tel:+49123", "market://details?id=x",
        )) {
            assertEquals(bad, UrlCheck.Invalid, WebAppLogic.checkUrl(bad))
        }
    }

    @Test
    fun credentialsBracketsAndBackslashAreInvalid() {
        assertEquals(UrlCheck.Invalid, WebAppLogic.checkUrl("https://user:pw@example.org"))
        assertEquals(UrlCheck.Invalid, WebAppLogic.checkUrl("https://example.org@evil.example"))
        assertEquals(UrlCheck.Invalid, WebAppLogic.checkUrl("https://[::1]/"))
        assertEquals(UrlCheck.Invalid, WebAppLogic.checkUrl("https://example.org\\evil"))
    }

    @Test
    fun blankWhitespaceAndControlCharactersAreRejected() {
        assertEquals(UrlCheck.Blank, WebAppLogic.checkUrl(""))
        assertEquals(UrlCheck.Blank, WebAppLogic.checkUrl("   "))
        assertEquals(UrlCheck.Invalid, WebAppLogic.checkUrl("https://exa mple.org"))
        assertEquals(UrlCheck.Invalid, WebAppLogic.checkUrl("https://example.org/\u0001"))
        assertEquals(UrlCheck.Invalid, WebAppLogic.checkUrl("https://"))
        assertEquals(UrlCheck.Invalid, WebAppLogic.checkUrl("https:///pfad"))
    }

    @Test
    fun invalidHostsAndPortsAreRejected() {
        for (bad in listOf("https://-a.org", "https://a..org", "https://a_b.org", "https://example.org:0", "https://example.org:65536", "https://example.org:", "https://example.org:80a")) {
            assertEquals(bad, UrlCheck.Invalid, WebAppLogic.checkUrl(bad))
        }
        assertEquals("https://example.org:65535", ok("https://example.org:65535"))
    }

    @Test
    fun umlautHostBecomesPunycode() {
        assertEquals("https://xn--mller-kva.de/x", ok("https://müller.de/x"))
    }

    @Test
    fun overlongAddressIsInvalid() {
        assertEquals(UrlCheck.Invalid, WebAppLogic.checkUrl("https://example.org/" + "a".repeat(WebAppLogic.MAX_URL_LENGTH)))
    }

    @Test
    fun uriIllegalCharactersInPathAreInvalid() {
        assertEquals(UrlCheck.Invalid, WebAppLogic.checkUrl("https://example.org/a|b"))
        assertEquals(UrlCheck.Invalid, WebAppLogic.checkUrl("https://example.org/{x}"))
    }

    // --- Name und Kachel ---

    @Test
    fun cleanNameTrimsLimitsAndRemovesControlCharacters() {
        assertEquals("Mein Dienst", WebAppLogic.cleanName("  Mein Dienst  "))
        assertEquals("AB", WebAppLogic.cleanName("A\u001EB\u001F"))
        assertEquals(null, WebAppLogic.cleanName("   "))
        assertEquals(null, WebAppLogic.cleanName("\u001E\u001F"))
        assertEquals(WebAppLogic.MAX_NAME_LENGTH, WebAppLogic.cleanName("x".repeat(100))!!.length)
    }

    @Test
    fun initialUsesWholeCodePointAndUppercase() {
        assertEquals("M", WebAppLogic.initial("  mail"))
        assertEquals("?", WebAppLogic.initial("   "))
        assertEquals("🌿", WebAppLogic.initial("🌿 Garten"))
    }

    @Test
    fun hueIsStableAndInRange() {
        assertEquals(WebAppLogic.hue("Mail"), WebAppLogic.hue("  mail "), 0f)
        for (name in listOf("a", "Zürich", "x".repeat(500), "🌿")) {
            assertTrue(WebAppLogic.hue(name) in 0f..359f)
        }
    }

    // --- Liste ---

    @Test
    fun addAppendsAndRejectsDuplicateIdsUnsafeIdsAndOverflow() {
        val one = WebAppLogic.add(emptyList(), app("1"))
        assertEquals(listOf(app("1")), one)
        assertEquals(one, WebAppLogic.add(one, app("1", name = "anders")))
        assertEquals(one, WebAppLogic.add(one, app("../x")))
        val full = (1..WebAppLogic.MAX_APPS).map { app("a$it") }
        assertFalse(WebAppLogic.canAdd(full))
        assertEquals(full, WebAppLogic.add(full, app("neu")))
    }

    @Test
    fun replaceDeleteAndMove() {
        val list = listOf(app("1"), app("2"), app("3"))
        assertEquals("Neu", WebAppLogic.replace(list, app("2", name = "Neu"))[1].name)
        assertEquals(list, WebAppLogic.replace(list, app("9", name = "Neu")))
        assertEquals(listOf("1", "3"), WebAppLogic.delete(list, "2").map { it.id })
        assertEquals(listOf("2", "1", "3"), WebAppLogic.move(list, "2", -1).map { it.id })
        assertEquals(listOf("1", "3", "2"), WebAppLogic.move(list, "2", 1).map { it.id })
        assertEquals(listOf("2", "1", "3"), WebAppLogic.move(list, "1", 1).map { it.id })
        assertEquals(list, WebAppLogic.move(list, "1", -1))
        assertEquals(list, WebAppLogic.move(list, "9", 1))
    }

    // --- Speicherform ---

    @Test
    fun roundTripKeepsAllFields() {
        val list = listOf(
            WebApp("a-1", "Mail", "https://mail.example.org/", customTab = true, hasIcon = true),
            WebApp("b2", "Notizen", "https://example.org:8443/n?x=1#y"),
        )
        assertEquals(list, WebAppLogic.decode(WebAppLogic.encode(list)))
    }

    @Test
    fun decodeSkipsDamagedEntries() {
        assertEquals(emptyList<WebApp>(), WebAppLogic.decode(null))
        assertEquals(emptyList<WebApp>(), WebAppLogic.decode(""))
        assertEquals(emptyList<WebApp>(), WebAppLogic.decode("nur Text"))
        val good = "g1\u001FGut\u001Fhttps://example.org\u001F0\u001F0"
        val raw = listOf(
            "bad id\u001FName\u001Fhttps://example.org\u001F0\u001F0", // Leerzeichen in der Kennung
            "../x\u001FName\u001Fhttps://example.org\u001F0\u001F0", // Pfadanteil
            "n1\u001F \u001Fhttps://example.org\u001F0\u001F0", // leerer Name
            "u1\u001FName\u001Fhttp://example.org\u001F0\u001F0", // kein HTTPS
            "u2\u001FName\u001Fjavascript:alert(1)\u001F0\u001F0",
            "f1\u001FName\u001Fhttps://example.org", // zu wenige Felder
            good,
            "g1\u001FDoppelt\u001Fhttps://example.org\u001F0\u001F0", // doppelte Kennung
        ).joinToString("\u001E")
        assertEquals(listOf(WebApp("g1", "Gut", "https://example.org")), WebAppLogic.decode(raw))
    }

    @Test
    fun decodeNormalizesStoredAddressAndLimitsCount() {
        val raw = "a1\u001FX\u001FEXAMPLE.org\u001F0\u001F0"
        assertEquals("https://example.org", WebAppLogic.decode(raw).single().url)
        val many = (1..150).map { app("id$it") }
        assertEquals(WebAppLogic.MAX_APPS, WebAppLogic.decode(WebAppLogic.encode(many)).size)
    }

    @Test
    fun encodeRemovesSeparatorsFromNames() {
        val decoded = WebAppLogic.decode(WebAppLogic.encode(listOf(WebApp("a", "A\u001EB\u001FC", "https://example.org"))))
        assertEquals("ABC", decoded.single().name)
    }

    @Test
    fun sanitizeDropsInvalidAndKeepsValid() {
        val list = listOf(app("1"), WebApp("2", "Unsicher", "http://example.org"), WebApp("../3", "X", "https://example.org"))
        assertEquals(listOf("1"), WebAppLogic.sanitize(list).map { it.id })
    }

    @Test
    fun safeIdAllowsOnlyFileNameCharacters() {
        assertTrue(WebAppLogic.isSafeId("123e4567-e89b-12d3-a456-426614174000"))
        assertFalse(WebAppLogic.isSafeId(""))
        assertFalse(WebAppLogic.isSafeId("a/b"))
        assertFalse(WebAppLogic.isSafeId("a.b"))
        assertFalse(WebAppLogic.isSafeId("x".repeat(41)))
    }

    // --- Verknüpfungen aus dem Browser ---

    private val pin = PinnedShortcut("org.mozilla.fennec_fdroid", "web-shortcut-1", 0L)

    @Test
    fun pinRoundTripKeepsPinAndEmptyUrl() {
        val list = listOf(
            WebApp("p1", "Karten", "", customTab = false, hasIcon = true, pin = pin),
            WebApp("a2", "Mail", "https://mail.example.org/"),
        )
        assertEquals(list, WebAppLogic.decode(WebAppLogic.encode(list)))
    }

    @Test
    fun decodeStillReadsFormerFiveFieldFormat() {
        val raw = "a1\u001FMail\u001Fhttps://mail.example.org/\u001F1\u001F0"
        assertEquals(listOf(WebApp("a1", "Mail", "https://mail.example.org/", customTab = true)), WebAppLogic.decode(raw))
    }

    @Test
    fun decodeIgnoresStoredUrlOfPinAndRejectsInvalidPins() {
        val f = "\u001F"
        val good = "p1${f}Gut${f}http://unsicher.example${f}1${f}0${f}org.example.app${f}sc1${f}10"
        assertEquals(listOf(WebApp("p1", "Gut", "", pin = PinnedShortcut("org.example.app", "sc1", 10L))), WebAppLogic.decode(good))
        for (bad in listOf(
            "p2${f}N${f}${f}0${f}0${f}kein Paket${f}sc1${f}0",
            "p3${f}N${f}${f}0${f}0${f}org.example.app${f}${f}0",
            "p4${f}N${f}${f}0${f}0${f}org.example.app${f}sc1${f}x",
            "p5${f}N${f}${f}0${f}0${f}org.example.app${f}sc1${f}-1",
            "p6${f}N${f}${f}0${f}0${f}org.example.app${f}sc\u0001${f}0",
            "p7${f}N${f}${f}0${f}0${f}../x${f}sc1${f}0",
        )) {
            assertEquals(bad, emptyList<WebApp>(), WebAppLogic.decode(bad))
        }
    }

    @Test
    fun entryWithoutPinStillNeedsValidUrlInEightFieldFormat() {
        val f = "\u001F"
        assertEquals(emptyList<WebApp>(), WebAppLogic.decode("a1${f}N${f}${f}0${f}0${f}${f}${f}"))
        assertEquals(emptyList<WebApp>(), WebAppLogic.decode("a1${f}N${f}http://example.org${f}0${f}0${f}${f}${f}"))
    }

    @Test
    fun findPinMatchesPackageIdAndUser() {
        val list = listOf(WebApp("p1", "A", "", pin = pin), WebApp("a2", "B", "https://example.org"))
        assertEquals("p1", WebAppLogic.findPin(list, pin)?.id)
        assertEquals(null, WebAppLogic.findPin(list, pin.copy(shortcutId = "anders")))
        assertEquals(null, WebAppLogic.findPin(list, pin.copy(userSerial = 10L)))
        assertEquals(null, WebAppLogic.findPin(list, pin.copy(packageName = "org.other.app")))
    }

    @Test
    fun shortcutIdAndPackageChecks() {
        assertTrue(WebAppLogic.isSafeShortcutId("web-shortcut_1:abc/def"))
        assertFalse(WebAppLogic.isSafeShortcutId(""))
        assertFalse(WebAppLogic.isSafeShortcutId("a\u001Fb"))
        assertFalse(WebAppLogic.isSafeShortcutId("x".repeat(256)))
        assertTrue(WebAppLogic.isSafePackage("org.mozilla.fennec_fdroid"))
        assertFalse(WebAppLogic.isSafePackage("keinpunkt"))
        assertFalse(WebAppLogic.isSafePackage("a b.c"))
        assertFalse(WebAppLogic.isValidPin(pin.copy(userSerial = -1L)))
        assertTrue(WebAppLogic.isValidPin(pin))
    }
}
