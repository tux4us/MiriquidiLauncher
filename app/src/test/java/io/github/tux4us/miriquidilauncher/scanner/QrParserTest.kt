// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QrParserTest {
    private fun p(s: String) = QrParser.parse(s)

    @Test fun urls() {
        assertEquals(QrContent.Url("https://example.org/a?b=1", "https://example.org/a?b=1"), p("https://example.org/a?b=1"))
        assertEquals(QrContent.Url("  http://localhost:8080/x ".trim(), "http://localhost:8080/x"), p("  http://localhost:8080/x "))
        assertEquals("https://www.example.org", (p("www.example.org") as QrContent.Url).url)
        assertTrue(p("https://exa mple.org") is QrContent.Text)
        assertTrue(p("https://") is QrContent.Text)
        assertTrue(p("https://nodot") is QrContent.Text)
    }

    @Test fun dangerousSchemesStayText() {
        assertTrue(p("javascript:alert(1)") is QrContent.Text)
        assertTrue(p("intent://scan/#Intent;scheme=zxing;end") is QrContent.Text)
        assertTrue(p("file:///sdcard/x") is QrContent.Text)
        assertTrue(p("market://details?id=x") is QrContent.Text)
    }

    @Test fun phoneAndSms() {
        assertEquals("+49 30 123456", (p("tel:+49 30 123456") as QrContent.Phone).number)
        assertTrue(p("tel:abc") is QrContent.Text)
        val a = p("smsto:+4915112345:Hallo: Welt") as QrContent.Sms
        assertEquals("+4915112345", a.number)
        assertEquals("Hallo: Welt", a.body)
        val b = p("sms:+4915112345?body=Hi%20du") as QrContent.Sms
        assertEquals("Hi du", b.body)
        assertNull((p("sms:12345") as QrContent.Sms).body)
    }

    @Test fun emails() {
        val a = p("mailto:a@b.de?subject=Hallo%20Welt&body=Text%C3%A4") as QrContent.Email
        assertEquals("a@b.de", a.address)
        assertEquals("Hallo Welt", a.subject)
        assertEquals("Textä", a.body)
        assertEquals("x@y.org", (p("x@y.org") as QrContent.Email).address)
        val m = p("MATMSG:TO:z@y.de;SUB:Betreff;BODY:Inhalt;;") as QrContent.Email
        assertEquals("z@y.de", m.address)
        assertEquals("Betreff", m.subject)
        assertTrue(p("mailto:kein-at") is QrContent.Text)
        assertTrue(p("nur@text und mehr") is QrContent.Text)
    }

    @Test fun geo() {
        val g = p("geo:48.137,11.575?q=Marienplatz") as QrContent.Geo
        assertEquals(48.137, g.latitude, 1e-9)
        assertEquals(11.575, g.longitude, 1e-9)
        assertEquals("Marienplatz", g.label)
        assertTrue(p("geo:100,11") is QrContent.Text)
        assertTrue(p("geo:abc") is QrContent.Text)
    }

    @Test fun wifi() {
        val w = p("WIFI:T:WPA;S:Mein\\;Netz;P:pa\\:ss\\\\wort;H:true;;") as QrContent.Wifi
        assertEquals("Mein;Netz", w.ssid)
        assertEquals("pa:ss\\wort", w.password)
        assertEquals("WPA", w.security)
        assertTrue(w.hidden)
        val open = p("WIFI:T:nopass;S:Cafe;P:;;") as QrContent.Wifi
        assertNull(open.password)
        assertFalse(open.hidden)
        assertTrue(p("WIFI:T:WPA;P:x;;") is QrContent.Text)
    }

    @Test fun vcard() {
        val v = p("BEGIN:VCARD\r\nVERSION:3.0\r\nN:Muster;Max;;;\r\nFN:Max Muster\r\nEND:VCARD") as QrContent.VCard
        assertEquals("Max Muster", v.name)
        val n = p("BEGIN:VCARD\nN:Muster;Max\nEND:VCARD") as QrContent.VCard
        assertEquals("Max Muster", n.name)
    }

    @Test fun plainText() {
        assertEquals(QrContent.Text("Hallo Welt"), p("Hallo Welt"))
        assertEquals(QrContent.Text(""), p(""))
    }

    @Test fun binaryDetectionAndFiles() {
        assertTrue(QrParser.isBinary("ab\u0000c"))
        assertTrue(QrParser.isBinary("a�b"))
        assertFalse(QrParser.isBinary("Zeile1\nZeile2\tÄ"))
        assertEquals(FileSuggestion("qr-1.bin", "application/octet-stream"), QrParser.fileSuggestion(QrContent.Text("x"), true, "1"))
        assertEquals(FileSuggestion("qr-1.vcf", "text/x-vcard"), QrParser.fileSuggestion(QrContent.VCard("x", null), false, "1"))
        assertEquals(FileSuggestion("qr-1.txt", "text/plain"), QrParser.fileSuggestion(QrContent.Url("u", "u"), false, "1"))
    }

    @Test fun percentDecoding() {
        assertEquals("a b", QrParser.percentDecode("a%20b"))
        assertEquals("100%", QrParser.percentDecode("100%"))
        assertEquals("%zz", QrParser.percentDecode("%zz"))
        assertEquals("ä", QrParser.percentDecode("%C3%A4"))
    }
}
