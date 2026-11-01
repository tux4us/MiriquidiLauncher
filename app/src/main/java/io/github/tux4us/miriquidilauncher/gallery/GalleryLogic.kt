// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.gallery

import java.time.Instant
import java.time.ZoneId

/** Reine Auswahl-, Sortier- und Gruppierfunktionen der Galerie. Ohne Android-Klassen, auf der JVM testbar. */
object GalleryLogic {
    /**
     * Die angezeigte Liste. Neueste zuerst, bei [ascending] umgekehrt. Alben in [hiddenBuckets] fehlen, außer
     * [showHidden] ist gesetzt oder das Album ist mit [bucketId] ausdrücklich geöffnet.
     */
    fun visible(
        items: List<MediaEntry>,
        filter: MediaFilter,
        query: String,
        bucketId: Long?,
        favoritesOnly: Boolean,
        favorites: Set<String>,
        hiddenBuckets: Set<Long>,
        showHidden: Boolean,
        ascending: Boolean,
    ): List<MediaEntry> {
        val needle = query.trim().lowercase()
        val filtered = items.filter { e ->
            (bucketId == null || e.bucketId == bucketId) &&
                (showHidden || bucketId != null || e.bucketId !in hiddenBuckets) &&
                when (filter) {
                    MediaFilter.ALL -> true
                    MediaFilter.PHOTOS -> !e.isVideo
                    MediaFilter.VIDEOS -> e.isVideo
                } &&
                (!favoritesOnly || (e.path.isNotEmpty() && e.path in favorites)) &&
                (needle.isEmpty() || e.name.lowercase().contains(needle) || e.bucketName.lowercase().contains(needle))
        }
        val newestFirst = filtered.sortedWith(compareByDescending<MediaEntry> { it.takenMillis }.thenByDescending { it.id })
        return if (ascending) newestFirst.reversed() else newestFirst
    }

    /** Alben der Liste [items], das neueste zuerst. Das Titelbild ist die jüngste Aufnahme. */
    fun albums(items: List<MediaEntry>, hiddenBuckets: Set<Long>, showHidden: Boolean): List<Album> =
        items.filter { showHidden || it.bucketId !in hiddenBuckets }
            .groupBy { it.bucketId }
            .map { (bucket, list) ->
                val newest = list.maxWith(compareBy<MediaEntry> { it.takenMillis }.thenBy { it.id })
                Album(
                    bucketId = bucket,
                    name = list.firstOrNull { it.bucketName.isNotBlank() }?.bucketName ?: newest.dir.substringAfterLast('/'),
                    dir = newest.dir,
                    count = list.size,
                    photos = list.count { !it.isVideo },
                    videos = list.count { it.isVideo },
                    cover = newest,
                    newestMillis = newest.takenMillis,
                )
            }
            .sortedWith(compareByDescending<Album> { it.newestMillis }.thenBy { it.name.lowercase() })

    /** Gruppiert eine bereits sortierte Liste nach Kalendertag in [zone]; aufeinanderfolgende Tage bleiben getrennt. */
    fun groupByDay(items: List<MediaEntry>, zone: ZoneId): List<DaySection> {
        val sections = ArrayList<DaySection>()
        var currentDay: java.time.LocalDate? = null
        var bucket = ArrayList<MediaEntry>()
        for (e in items) {
            val day = Instant.ofEpochMilli(e.takenMillis).atZone(zone).toLocalDate()
            if (day != currentDay) {
                if (currentDay != null) sections.add(DaySection(currentDay, bucket))
                currentDay = day
                bucket = ArrayList()
            }
            bucket.add(e)
        }
        if (currentDay != null) sections.add(DaySection(currentDay, bucket))
        return sections
    }

    /** `3:05` oder `1:02:03`. */
    fun formatDuration(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    /** Tage, die das System Einträge im Papierkorb der Mediathek aufbewahrt. */
    const val TRASH_RETENTION_DAYS = 30

    /**
     * Relativer Ordner (`Pictures/Urlaub/`) eines Albumordners [absoluteDir] auf dem Speicher mit der Wurzel [storageRoot];
     * `null`, wenn der Ordner nicht darin liegt (anderer Speicher) oder die Wurzel selbst ist.
     */
    fun relativeDir(absoluteDir: String, storageRoot: String): String? {
        val root = storageRoot.trimEnd('/') + "/"
        if (!absoluteDir.startsWith(root)) return null
        val rel = absoluteDir.removePrefix(root).trim('/')
        return if (rel.isEmpty()) null else "$rel/"
    }

    /**
     * Zielordner für ein Foto oder Video in einem Album [albumRelative] (`Pictures/Urlaub/`). Die Mediathek erlaubt Fotos
     * nur unter `Pictures` und `DCIM`, Videos nur unter `Movies` und `DCIM`; liegt das Album woanders, wird der letzte
     * Ordnername unter dem passenden Standardordner verwendet.
     */
    fun targetRelativePath(albumRelative: String, isVideo: Boolean): String {
        val clean = albumRelative.trim('/')
        val top = clean.substringBefore('/')
        val allowed = if (isVideo) setOf("Movies", "DCIM") else setOf("Pictures", "DCIM")
        if (top in allowed) return "$clean/"
        val name = clean.substringAfterLast('/')
        return (if (isVideo) "Movies" else "Pictures") + "/" + name + "/"
    }

    /** Nächster Index bei der Diashow; am Ende geht es von vorn los. `-1` bei leerer Liste. */
    fun nextIndex(current: Int, size: Int): Int = if (size <= 0) -1 else (current + 1) % size

    /** Mögliche Orte für „In Album verschieben“: Alben mit bekanntem Ordner, nach Namen sortiert. */
    fun targetAlbums(albums: List<Album>): List<Album> =
        albums.filter { it.dir.isNotEmpty() }.sortedBy { it.name.lowercase() }
}
