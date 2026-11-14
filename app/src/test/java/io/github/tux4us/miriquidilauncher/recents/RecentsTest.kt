// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.recents

import io.github.tux4us.miriquidilauncher.recents.RecentsLogic.RecentApp
import io.github.tux4us.miriquidilauncher.recents.RecentsLogic.UsageSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentsLogicTest {
    private val own = "io.github.tux4us.miriquidilauncher"
    private val launchable = setOf("a", "b", "c", "d", own)

    private fun sample(pkg: String, time: Long) = UsageSample(pkg, time)

    @Test
    fun newestEventPerPackageDecidesAndOrderIsNewestFirst() {
        val result = RecentsLogic.recentApps(
            listOf(sample("a", 100), sample("b", 300), sample("a", 200), sample("c", 250)),
            own, launchable, emptyMap(),
        )
        assertEquals(listOf(RecentApp("b", 300), RecentApp("c", 250), RecentApp("a", 200)), result)
    }

    @Test
    fun ownPackageAndNonLaunchablePackagesAreSkipped() {
        val result = RecentsLogic.recentApps(
            listOf(sample(own, 500), sample("systemui", 400), sample("a", 100)),
            own, launchable, emptyMap(),
        )
        assertEquals(listOf(RecentApp("a", 100)), result)
    }

    @Test
    fun dismissedAppStaysHiddenUntilUsedAgain() {
        val samples = listOf(sample("a", 100), sample("b", 200))
        assertEquals(listOf("b"), RecentsLogic.recentApps(samples, own, launchable, mapOf("a" to 150)).map { it.packageName })
        // Ereignis zum Zeitpunkt der Entfernung zählt noch als vor der Entfernung.
        assertEquals(listOf("b"), RecentsLogic.recentApps(samples, own, launchable, mapOf("a" to 100)).map { it.packageName })
        // Nach der Entfernung erneut benutzt: wieder sichtbar.
        val again = samples + sample("a", 300)
        assertEquals(listOf("a", "b"), RecentsLogic.recentApps(again, own, launchable, mapOf("a" to 150)).map { it.packageName })
    }

    @Test
    fun limitCutsTheListAndEqualTimesSortByName() {
        val samples = listOf(sample("d", 10), sample("c", 10), sample("b", 10), sample("a", 10))
        assertEquals(listOf("a", "b", "c"), RecentsLogic.recentApps(samples, own, launchable, emptyMap(), limit = 3).map { it.packageName })
        assertEquals(emptyList<RecentApp>(), RecentsLogic.recentApps(samples, own, launchable, emptyMap(), limit = 0))
        assertEquals(emptyList<RecentApp>(), RecentsLogic.recentApps(emptyList(), own, launchable, emptyMap()))
    }

    @Test
    fun dismissMarksAreMergedAndPruned() {
        val day = 24L * 60 * 60 * 1000
        val now = 10 * day
        val merged = RecentsLogic.withDismissed(mapOf("old" to now - 4 * day, "keep" to now - day), listOf("a", "keep"), now)
        assertEquals(setOf("a", "keep"), merged.keys)
        assertEquals(now, merged["keep"])
        val many = (1..100).associate { "p$it" to now - it.toLong() }
        assertEquals(RecentsLogic.MAX_DISMISSED, RecentsLogic.prune(many, now).size)
        assertTrue("p1" in RecentsLogic.prune(many, now))
    }
}

class RecentsCodecTest {
    @Test
    fun roundTrip() {
        val marks = linkedMapOf("a.b" to 5L, "c.d" to 99L)
        assertEquals(marks, RecentsCodec.decode(RecentsCodec.encode(marks)))
        assertEquals("", RecentsCodec.encode(emptyMap()))
    }

    @Test
    fun damagedLinesAreSkipped() {
        val text = "ok\t1\nbroken\nalso\tnot-a-number\n\t5\nneg\t-3\nfine\t7\ntoo\tmany\tparts"
        assertEquals(mapOf("ok" to 1L, "fine" to 7L), RecentsCodec.decode(text))
        assertEquals(emptyMap<String, Long>(), RecentsCodec.decode(null))
        assertEquals(emptyMap<String, Long>(), RecentsCodec.decode("  "))
    }
}

class RecentsFanTest {
    @Test
    fun centerCardIsUprightFullSizeAndOpaque() {
        val t = RecentsFan.transform(0f)
        assertEquals(0f, t.rotationDegrees, 0f)
        assertEquals(1f, t.scale, 0f)
        assertEquals(1f, t.alpha, 0f)
        assertTrue(t.visible)
    }

    @Test
    fun sideCardsTurnAwayShrinkAndFade() {
        val right = RecentsFan.transform(2f)
        val left = RecentsFan.transform(-2f)
        assertEquals(2 * RecentsFan.ANGLE_PER_CARD, right.rotationDegrees, 0.001f)
        assertEquals(-right.rotationDegrees, left.rotationDegrees, 0.001f)
        assertTrue(right.scale < 1f)
        val far = RecentsFan.transform(3f)
        assertTrue(far.alpha < 1f && far.alpha > 0f)
        assertFalse(RecentsFan.transform(RecentsFan.VISIBLE_RANGE).visible)
        assertEquals(0f, RecentsFan.transform(RecentsFan.VISIBLE_RANGE - 0.0001f).alpha, 0.001f)
        assertTrue(RecentsFan.transform(50f).scale >= 0.7f)
    }

    @Test
    fun dragLeftMovesToOlderCardsAndStaysInRange() {
        assertEquals(1f, RecentsFan.positionAfterDrag(0f, -100f, 100f, 5), 0.0001f)
        assertEquals(0f, RecentsFan.positionAfterDrag(0f, 500f, 100f, 5), 0f)
        assertEquals(4f, RecentsFan.positionAfterDrag(3.5f, -500f, 100f, 5), 0f)
        assertEquals(0f, RecentsFan.positionAfterDrag(2f, -10f, 100f, 0), 0f)
    }

    @Test
    fun releaseSnapsToTheNearestCardAndFlingsFurther() {
        assertEquals(2, RecentsFan.targetIndex(1.6f, 0f, 100f, 5))
        assertEquals(1, RecentsFan.targetIndex(1.4f, 0f, 100f, 5))
        // Schnell nach links wischen: weiter zu älteren Karten.
        assertTrue(RecentsFan.targetIndex(1f, -2000f, 100f, 10) > 1)
        // Schnell nach rechts: zurück.
        assertTrue(RecentsFan.targetIndex(3f, 2000f, 100f, 10) < 3)
        assertEquals(4, RecentsFan.targetIndex(3.9f, -99999f, 100f, 5))
        assertEquals(0, RecentsFan.targetIndex(0.1f, 99999f, 100f, 5))
        assertEquals(0, RecentsFan.targetIndex(1f, 0f, 100f, 0))
    }

    @Test
    fun dismissNeedsDistanceOrSpeed() {
        assertTrue(RecentsFan.shouldDismiss(200f, 0f, 150f))
        assertFalse(RecentsFan.shouldDismiss(50f, 0f, 150f))
        assertTrue(RecentsFan.shouldDismiss(60f, 3000f, 150f))
        assertFalse(RecentsFan.shouldDismiss(10f, 3000f, 150f))
        assertFalse(RecentsFan.shouldDismiss(0f, 0f, 150f))
    }
}
