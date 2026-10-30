// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsCatalogTest {
    private fun keys(q: String) = SettingsCatalog.match(TextNormalizer.normalize(q)).map { it.first.key }

    @Test fun findsByGermanAndEnglishNames() {
        assertEquals("wifi", keys("wlan").first())
        assertEquals("wifi", keys("WiFi").first())
        assertEquals("bluetooth", keys("blue").first())
        assertEquals("battery", keys("akku").first())
        assertEquals("airplane", keys("flug").first())
    }

    @Test fun umlautsAreNormalized() {
        assertEquals("sound", keys("lautstärke").first())
        assertEquals("privacy", keys("privatsphäre").first())
    }

    @Test fun prefixMatchesAndShortInputIsIgnored() {
        assertTrue(SettingsCatalog.match("a").isEmpty())
        assertTrue(keys("xyzzy").isEmpty())
    }

    @Test fun keysAndActionsAreUnique() {
        assertEquals(SettingsCatalog.targets.size, SettingsCatalog.targets.map { it.key }.toSet().size)
        assertTrue(SettingsCatalog.targets.all { it.action.isNotBlank() && it.keywords.isNotEmpty() })
    }
}
