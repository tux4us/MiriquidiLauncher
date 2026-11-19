// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.support

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupportPromptTest {
    @Test
    fun showsAfterUpdateWithSavedVersion() {
        assertTrue(SupportPrompt.shouldShow(lastSeen = 43L, current = 44L, updatedSinceInstall = true))
        assertTrue(SupportPrompt.shouldShow(lastSeen = 43L, current = 44L, updatedSinceInstall = false))
    }

    @Test
    fun doesNotShowTwiceForSameVersion() {
        assertFalse(SupportPrompt.shouldShow(lastSeen = 44L, current = 44L, updatedSinceInstall = true))
    }

    @Test
    fun doesNotShowOnDowngrade() {
        assertFalse(SupportPrompt.shouldShow(lastSeen = 45L, current = 44L, updatedSinceInstall = true))
    }

    @Test
    fun freshInstallDoesNotShow() {
        assertFalse(SupportPrompt.shouldShow(lastSeen = null, current = 44L, updatedSinceInstall = false))
    }

    @Test
    fun firstVersionWithPromptShowsOnExistingInstall() {
        assertTrue(SupportPrompt.shouldShow(lastSeen = null, current = 44L, updatedSinceInstall = true))
    }

    @Test
    fun unknownVersionNeverShows() {
        assertFalse(SupportPrompt.shouldShow(lastSeen = null, current = 0L, updatedSinceInstall = true))
        assertFalse(SupportPrompt.shouldShow(lastSeen = 43L, current = 0L, updatedSinceInstall = true))
    }

    @Test
    fun updateIsDetectedByInstallTimes() {
        assertFalse(SupportPrompt.isUpdate(1_000L, 1_000L))
        assertFalse(SupportPrompt.isUpdate(1_000L, 1_000L + SupportPrompt.UPDATE_TOLERANCE_MS))
        assertTrue(SupportPrompt.isUpdate(1_000L, 1_001L + SupportPrompt.UPDATE_TOLERANCE_MS))
    }

    @Test
    fun donateUrlIsHttpsAndFixed() {
        assertEquals("https://buymeacoffee.com/tux4us", SupportPrompt.DONATE_URL)
    }
}
