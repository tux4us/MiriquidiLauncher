// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.gallery

import io.github.tux4us.miriquidilauncher.files.TrashItem
import java.io.File
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryTest {
    private val utc = ZoneId.of("UTC")

    private fun media(id: Long, bucket: Long, name: String, at: Long, video: Boolean = false, bucketName: String = "B$bucket") =
        MediaEntry(
            id = id, isVideo = video, name = name, path = "/storage/emulated/0/Pictures/$bucketName/$name",
            bucketId = bucket, bucketName = bucketName, takenMillis = at, sizeBytes = 10, width = 4, height = 3,
            durationMs = if (video) 65_000 else 0, mime = if (video) "video/mp4" else "image/jpeg",
        )

    private val day = 24L * 3600 * 1000
    private val items = listOf(
        media(1, 10, "a.jpg", 5 * day + 1000),
        media(2, 10, "b.jpg", 5 * day + 2000),
        media(3, 20, "c.mp4", 4 * day, video = true),
        media(4, 30, "geheim.jpg", 3 * day),
        media(5, 20, "Urlaub.jpg", 1 * day),
    )

    @Test
    fun keysAndDirs() {
        assertEquals("i1", items[0].key)
        assertEquals("v3", items[2].key)
        assertEquals("/storage/emulated/0/Pictures/B10", items[0].dir)
    }

    @Test
    fun visibleSortsAndFilters() {
        val none = emptySet<String>()
        fun v(filter: MediaFilter = MediaFilter.ALL, q: String = "", b: Long? = null, fav: Boolean = false, favs: Set<String> = none, hidden: Set<Long> = emptySet(), show: Boolean = false, asc: Boolean = false) =
            GalleryLogic.visible(items, filter, q, b, fav, favs, hidden, show, asc).map { it.id }
        assertEquals(listOf(2L, 1L, 3L, 4L, 5L), v())
        assertEquals(listOf(5L, 4L, 3L, 1L, 2L), v(asc = true))
        assertEquals(listOf(3L), v(MediaFilter.VIDEOS))
        assertEquals(listOf(2L, 1L, 4L, 5L), v(MediaFilter.PHOTOS))
        assertEquals(listOf(5L), v(q = "URLAUB"))
        assertEquals(listOf(3L, 5L), v(q = "b20"))
        assertEquals(listOf(3L, 5L), v(b = 20))
        assertEquals(listOf(2L, 1L, 3L, 5L), v(hidden = setOf(30L)))
        assertEquals(listOf(2L, 1L, 3L, 4L, 5L), v(hidden = setOf(30L), show = true))
        assertEquals(listOf(4L), v(b = 30, hidden = setOf(30L)))
        assertEquals(listOf(4L), v(fav = true, favs = setOf(items[3].path)))
    }

    @Test
    fun albums() {
        val a = GalleryLogic.albums(items, emptySet(), false)
        assertEquals(listOf(10L, 20L, 30L), a.map { it.bucketId })
        assertEquals(2, a[0].count)
        assertEquals(2L, a[0].cover.id)
        assertEquals(1, a[1].videos)
        assertEquals(1, a[1].photos)
        assertEquals(listOf(10L, 20L), GalleryLogic.albums(items, setOf(30L), false).map { it.bucketId })
        assertEquals(3, GalleryLogic.albums(items, setOf(30L), true).size)
        assertEquals(listOf("B10", "B20", "B30"), GalleryLogic.targetAlbums(a).map { it.name })
    }

    @Test
    fun groupsByDay() {
        val sorted = GalleryLogic.visible(items, MediaFilter.ALL, "", null, false, emptySet(), emptySet(), false, false)
        val sections = GalleryLogic.groupByDay(sorted, utc)
        assertEquals(4, sections.size)
        assertEquals(2, sections[0].items.size)
        assertEquals(1970, sections[0].day.year)
        assertEquals(6, sections[0].day.dayOfMonth)
        // Zeitzone verschiebt die Tagesgrenze
        val late = listOf(media(9, 1, "x.jpg", 23L * 3600 * 1000), media(10, 1, "y.jpg", 25L * 3600 * 1000))
        assertEquals(2, GalleryLogic.groupByDay(late.sortedByDescending { it.takenMillis }, utc).size)
        assertEquals(1, GalleryLogic.groupByDay(late.sortedByDescending { it.takenMillis }, ZoneId.of("Pacific/Auckland")).size)
    }

    @Test
    fun durationAndSlideshow() {
        assertEquals("1:05", GalleryLogic.formatDuration(65_000))
        assertEquals("1:02:03", GalleryLogic.formatDuration(3_723_000))
        assertEquals("0:00", GalleryLogic.formatDuration(-5))
        assertEquals(0, GalleryLogic.nextIndex(2, 3))
        assertEquals(2, GalleryLogic.nextIndex(1, 3))
        assertEquals(-1, GalleryLogic.nextIndex(0, 0))
    }

    @Test
    fun trashedMedia() {
        fun t(path: String, dir: Boolean = false) = TrashItem(File("/t/x"), File("/t/x.info"), path, 1L, dir)
        val list = GalleryLogic.trashedMedia(listOf(t("/a/b.JPG"), t("/a/c.txt"), t("/a/d.mp4"), t("/a/e.jpg", dir = true)))
        assertEquals(listOf("b.JPG", "d.mp4"), list.map { it.displayName })
        assertTrue(GalleryLogic.trashedMedia(emptyList()).isEmpty())
    }
}
