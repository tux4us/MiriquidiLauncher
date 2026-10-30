// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.backup

import io.github.tux4us.miriquidilauncher.data.AppGroup
import io.github.tux4us.miriquidilauncher.data.FavoriteContact
import io.github.tux4us.miriquidilauncher.weather.GeoPlace
import io.github.tux4us.miriquidilauncher.weather.ShadowHeights
import java.security.SecureRandom
import java.util.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupQrTest {
    private fun backup(apps: Int, contacts: Int, groups: Int): String {
        val rnd = Random(42)
        fun key() = "org.example${rnd.nextInt(9999)}/org.example${rnd.nextInt(9999)}.ui.MainActivity#0"
        val data = BackupData(
            appFavorites = List(apps) { key() },
            contactFavorites = List(contacts) { FavoriteContact("Müller $it \"Mutti\"", "+49151123${1000 + it}") },
            weatherFavorites = listOf(GeoPlace("Köln, Nordrhein-Westfalen", 50.94, 6.96)),
            selectedPlace = GeoPlace("Berlin", 52.52, 13.405),
            shadowHeights = ShadowHeights(1.8, 0.5),
            appGroups = List(groups) { AppGroup("g$it", "Gruppe $it", List(10) { key() }) },
        )
        return BackupCodec.encode(data, "2026-10-18T00:00:00Z")
    }

    private fun restore(parts: List<String>, password: CharArray? = null): String {
        val collector = BackupQr.Collector()
        var last: BackupQr.Collector.Status? = null
        parts.forEach { last = collector.add(it) }
        val complete = last as BackupQr.Collector.Status.Complete
        return BackupQr.open(complete.collected, password)
    }

    // ---- Base45 -----------------------------------------------------------------------------------------------

    @Test fun base45MatchesRfcVectors() {
        assertEquals("BB8", BackupQr.Base45.encode("AB".toByteArray()))
        assertEquals("%69 VD92EX0", BackupQr.Base45.encode("Hello!!".toByteArray()))
        assertEquals("QED8WEX0", BackupQr.Base45.encode("ietf!".toByteArray()))
        assertEquals("ietf!", String(BackupQr.Base45.decode("QED8WEX0")))
        assertEquals("Hello!!", String(BackupQr.Base45.decode("%69 VD92EX0")))
    }

    @Test fun base45RoundTripAllLengthsAndValues() {
        val rnd = Random(1)
        for (len in 0..70) {
            val data = ByteArray(len).also(rnd::nextBytes)
            assertArrayEquals(data, BackupQr.Base45.decode(BackupQr.Base45.encode(data)))
        }
        val all = ByteArray(256) { it.toByte() }
        assertArrayEquals(all, BackupQr.Base45.decode(BackupQr.Base45.encode(all)))
    }

    @Test fun base45RejectsInvalidInput() {
        for (bad in listOf("A", "GGW", "abc", "!!!", "::")) {
            val result = runCatching { BackupQr.Base45.decode(bad) }
            assertTrue("sollte abgelehnt werden: $bad", result.isFailure)
        }
    }

    @Test fun partsUseOnlyAlphanumericQrCharacters() {
        val parts = BackupQr.createParts(backup(20, 15, 5), null)
        val allowed = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ \$%*+-./:"
        parts.forEach { part -> assertTrue(part.all { it in allowed }) }
    }

    // ---- Teilen und Zusammensetzen ------------------------------------------------------------------------------

    @Test fun smallBackupFitsIntoOneCode() {
        val json = backup(8, 5, 2)
        val parts = BackupQr.createParts(json, null)
        assertEquals(1, parts.size)
        assertEquals(json, restore(parts))
    }

    @Test fun largeBackupIsSplitAndRestoredInAnyOrder() {
        val json = backup(40, 40, 8)
        val parts = BackupQr.createParts(json, null)
        assertTrue(parts.size in 2..BackupQr.MAX_PARTS)
        parts.forEach { assertTrue(it.length <= BackupQr.PART_CHARS + 32) }
        assertEquals(json, restore(parts))
        assertEquals(json, restore(parts.reversed()))
        assertEquals(json, restore(parts.shuffled(Random(7))))
        // Doppeltes Scannen schadet nicht.
        assertEquals(json, restore(parts.flatMap { listOf(it, it) }))
    }

    @Test fun restoredJsonDecodesToTheSameBackup() {
        val json = backup(20, 15, 5)
        val parts = BackupQr.createParts(json, null)
        assertEquals(BackupCodec.decode(json), BackupCodec.decode(restore(parts)))
    }

    @Test fun collectorReportsProgress() {
        val parts = BackupQr.createParts(backup(40, 40, 8), null)
        val collector = BackupQr.Collector()
        val first = collector.add(parts[0]) as BackupQr.Collector.Status.Progress
        assertEquals(setOf(1), first.received)
        assertEquals(parts.size, first.total)
        assertFalse(first.newSet)
        val again = collector.add(parts[0]) as BackupQr.Collector.Status.Progress
        assertEquals(setOf(1), again.received)
    }

    @Test fun codeOfAnotherBackupStartsOver() {
        val a = BackupQr.createParts(backup(40, 40, 8), null)
        val b = BackupQr.createParts(backup(41, 40, 8), null)
        // Zwei Sätze mit gleicher ID sind mit 1/65536 möglich; den Test davon unabhängig halten.
        val bFixed = if (a[0].substring(5, 9) == b[0].substring(5, 9)) return else b
        val collector = BackupQr.Collector()
        collector.add(a[0])
        val status = collector.add(bFixed[0]) as BackupQr.Collector.Status.Progress
        assertTrue(status.newSet)
        assertEquals(setOf(1), status.received)
    }

    @Test fun foreignCodesAreIgnored() {
        val collector = BackupQr.Collector()
        for (text in listOf(
            "", "https://example.org", "WIFI:S:x;;", "MQB1", "MQB1:ZZZZ:1:1:P:ABC", "MQB1:0001:0:1:P:ABC",
            "MQB1:0001:2:1:P:ABC", "MQB1:0001:1:99:P:ABC", "MQB1:0001:1:1:X:ABC", "MQB1:0001:1:1:P:", "MQB1:0001:1:1:P:abc",
        )) {
            assertEquals(text, BackupQr.Collector.Status.Foreign, collector.add(text))
        }
        assertTrue(collector.received.isEmpty())
    }

    @Test fun corruptPayloadIsReportedAndDiscarded() {
        val collector = BackupQr.Collector()
        // "GGW" ergibt einen Wert über 65535 und ist kein gültiges Base45.
        assertEquals(BackupQr.Collector.Status.Corrupt, collector.add("MQB1:0001:1:1:P:GGW"))
        assertTrue(collector.received.isEmpty())
    }

    @Test fun backupTooLargeForQrIsRefused() {
        val rnd = Random(3)
        val big = (1..40_000).joinToString("") { Integer.toHexString(rnd.nextInt()) }
        val result = runCatching { BackupQr.createParts("{\"x\":\"$big\"}", null) }
        assertTrue(result.exceptionOrNull() is BackupQr.TooLargeException)
    }

    // ---- Passwort -----------------------------------------------------------------------------------------------

    @Test fun encryptedRoundTrip() {
        val json = backup(20, 15, 5)
        val parts = BackupQr.createParts(json, "geheim123".toCharArray())
        assertTrue(parts.all { it.startsWith("MQB1:") && it.split(':')[4] == "E" })
        assertEquals(json, restore(parts, "geheim123".toCharArray()))
    }

    @Test fun wrongOrMissingPasswordIsRejected() {
        val parts = BackupQr.createParts(backup(8, 5, 2), "geheim123".toCharArray())
        assertTrue(runCatching { restore(parts, "falsch1234".toCharArray()) }.exceptionOrNull() is BackupQr.WrongPasswordException)
        assertTrue(runCatching { restore(parts, null) }.exceptionOrNull() is BackupQr.WrongPasswordException)
    }

    @Test fun encryptedPartsDoNotContainPlainText() {
        val json = backup(8, 5, 2)
        val plain = BackupQr.createParts(json, null).joinToString("")
        val encrypted = BackupQr.createParts(json, "geheim123".toCharArray()).joinToString("")
        assertNotEquals(plain, encrypted)
    }

    @Test fun sameInputGivesDifferentCiphertext() {
        val a = BackupQr.createParts("{\"a\":1}", "geheim123".toCharArray(), SecureRandom())
        val b = BackupQr.createParts("{\"a\":1}", "geheim123".toCharArray(), SecureRandom())
        assertNotEquals(a.last().substringAfterLast(':'), b.last().substringAfterLast(':'))
    }

    @Test fun tamperedEncryptedDataIsRejected() {
        val parts = BackupQr.createParts(backup(8, 5, 2), "geheim123".toCharArray()).toMutableList()
        val fields = parts[0].split(':', limit = 6).toMutableList()
        val payload = fields[5].toCharArray()
        payload[payload.size / 2] = if (payload[payload.size / 2] == 'A') 'B' else 'A'
        fields[5] = String(payload)
        parts[0] = fields.joinToString(":")
        val result = runCatching { restore(parts, "geheim123".toCharArray()) }
        assertTrue(result.isFailure)
    }

    // ---- Entpacken ----------------------------------------------------------------------------------------------

    @Test fun decompressionBombIsStopped() {
        // 8 MB Nullen komprimieren auf wenige KB; open() muss bei 2 MB abbrechen.
        val bomb = java.io.ByteArrayOutputStream().also { out ->
            val deflater = java.util.zip.Deflater(9, true)
            deflater.setInput(ByteArray(8 * 1024 * 1024))
            deflater.finish()
            val buf = ByteArray(4096)
            while (!deflater.finished()) out.write(buf, 0, deflater.deflate(buf))
            deflater.end()
        }.toByteArray()
        val result = runCatching { BackupQr.open(BackupQr.Collected(bomb, false), null) }
        assertTrue(result.exceptionOrNull() is BackupCodec.BackupException)
    }

    @Test fun garbageDataIsRejected() {
        val result = runCatching { BackupQr.open(BackupQr.Collected(ByteArray(50) { 0x55 }, false), null) }
        assertTrue(result.exceptionOrNull() is BackupCodec.BackupException)
    }
}
