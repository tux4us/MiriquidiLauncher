// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.markets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketUrlTest {
    private fun ok(input: String) = (MarketUrl.check(input) as BaseUrlCheck.Ok).url

    @Test fun normalizesValidAddresses() {
        assertEquals("https://mempool.space", ok("https://mempool.space/"))
        assertEquals("https://mempool.space", ok("  mempool.space  "))
        assertEquals("https://mempool.space", ok("https://mempool.space/api"))
        assertEquals("https://mempool.space", ok("https://mempool.space/api/"))
        assertEquals("https://host.local:3006/mempool", ok("https://Host.Local:3006/mempool/api/"))
        assertEquals("https://192.168.1.5:4081", ok("https://192.168.1.5:4081"))
    }

    @Test fun rejectsClearTextAndGarbage() {
        assertEquals(BaseUrlCheck.Empty, MarketUrl.check("   "))
        assertEquals(BaseUrlCheck.NotHttps, MarketUrl.check("http://mempool.space"))
        assertEquals(BaseUrlCheck.NotHttps, MarketUrl.check("HTTP://mempool.space"))
        assertEquals(BaseUrlCheck.Invalid, MarketUrl.check("ftp://mempool.space"))
        assertEquals(BaseUrlCheck.Invalid, MarketUrl.check("https://user:pw@mempool.space"))
        assertEquals(BaseUrlCheck.Invalid, MarketUrl.check("https://mempool.space?x=1"))
        assertEquals(BaseUrlCheck.Invalid, MarketUrl.check("https://mempool.space#a"))
        assertEquals(BaseUrlCheck.Invalid, MarketUrl.check("https://mempool.space:99999"))
        assertEquals(BaseUrlCheck.Invalid, MarketUrl.check("https://mem pool.space"))
        assertEquals(BaseUrlCheck.Invalid, MarketUrl.check("https://"))
        assertEquals(BaseUrlCheck.Invalid, MarketUrl.check("https://" + "a".repeat(250)))
    }

    @Test fun defaultDetection() {
        assertTrue(MarketUrl.isDefault(null))
        assertTrue(MarketUrl.isDefault(MarketUrl.DEFAULT))
        assertFalse(MarketUrl.isDefault("https://example.org"))
    }
}
