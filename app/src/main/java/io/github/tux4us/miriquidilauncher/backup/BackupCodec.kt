// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.backup

import io.github.tux4us.miriquidilauncher.data.AppGroup
import io.github.tux4us.miriquidilauncher.data.AppGroupCodec
import io.github.tux4us.miriquidilauncher.data.FavoriteContact
import io.github.tux4us.miriquidilauncher.feed.FeedText
import io.github.tux4us.miriquidilauncher.markets.BaseUrlCheck
import io.github.tux4us.miriquidilauncher.markets.ChartChoice
import io.github.tux4us.miriquidilauncher.markets.ChartCurrency
import io.github.tux4us.miriquidilauncher.markets.ChartRange
import io.github.tux4us.miriquidilauncher.markets.CryptoRange
import io.github.tux4us.miriquidilauncher.markets.FxLogic
import io.github.tux4us.miriquidilauncher.markets.MarketUrl
import io.github.tux4us.miriquidilauncher.markets.Position
import io.github.tux4us.miriquidilauncher.markets.PositionKind
import io.github.tux4us.miriquidilauncher.markets.PositionsLogic
import io.github.tux4us.miriquidilauncher.markets.SeriesRange
import io.github.tux4us.miriquidilauncher.notes.NotesLogic
import io.github.tux4us.miriquidilauncher.podcasts.Episode
import io.github.tux4us.miriquidilauncher.podcasts.Podcast
import io.github.tux4us.miriquidilauncher.podcasts.PodcastCodec
import io.github.tux4us.miriquidilauncher.podcasts.PodcastData
import io.github.tux4us.miriquidilauncher.podcasts.PodcastLogic
import io.github.tux4us.miriquidilauncher.weather.GeoPlace
import io.github.tux4us.miriquidilauncher.weather.MiniJson
import io.github.tux4us.miriquidilauncher.weather.ShadowHeights
import io.github.tux4us.miriquidilauncher.weather.asArray
import io.github.tux4us.miriquidilauncher.weather.asDouble
import io.github.tux4us.miriquidilauncher.weather.asObject
import io.github.tux4us.miriquidilauncher.weather.asString

/** Lesbares JSON-Format für Backups. Frei von Android-Klassen, damit es auf der JVM testbar ist. */
object BackupCodec {
    const val FORMAT = "miriquidilauncher-backup"

    /** Format-Kennung der Version 1.2.0 (vor der Umbenennung); wird beim Import weiterhin akzeptiert. */
    const val LEGACY_FORMAT = "bc-launcher-backup"
    const val VERSION = 1
    const val MAX_ENTRIES = 1000
    const val MAX_BYTES = 2 * 1024 * 1024

    /** Obergrenze für Backup-Dateien (mit Notizen); QR-Codes bleiben bei [MAX_BYTES]. */
    const val MAX_FILE_BYTES = 16 * 1024 * 1024

    class BackupException(message: String) : Exception(message)

    fun encode(data: BackupData, createdAtIso: String): String {
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"format\": ").append(str(FORMAT)).append(",\n")
        sb.append("  \"version\": ").append(VERSION).append(",\n")
        sb.append("  \"createdAt\": ").append(str(createdAtIso)).append(",\n")
        sb.append("  \"appFavorites\": [").append(data.appFavorites.joinToString(", ") { str(it) }).append("]")
        data.contactFavorites?.let { contacts ->
            sb.append(",\n  \"contactFavorites\": [")
            sb.append(contacts.joinToString(", ") { "{\"name\": ${str(it.name)}, \"number\": ${str(it.number)}}" })
            sb.append("]")
        }
        sb.append(",\n  \"weatherFavorites\": [").append(data.weatherFavorites.joinToString(", ") { place(it) }).append("]")
        data.selectedPlace?.let { sb.append(",\n  \"selectedPlace\": ").append(place(it)) }
        data.shadowHeights?.let {
            sb.append(",\n  \"shadowHeights\": {\"person\": ${num(it.personM)}, \"object\": ${num(it.objectM)}}")
        }
        data.appGroups?.let { groups ->
            sb.append(",\n  \"appGroups\": [")
            sb.append(groups.joinToString(", ") { g ->
                "{\"id\": ${str(g.id)}, \"name\": ${str(g.name)}, \"apps\": [${g.appKeys.joinToString(", ") { str(it) }}]}"
            })
            sb.append("]")
        }
        data.notes?.let { notes ->
            sb.append(",\n  \"notes\": [")
            sb.append(notes.joinToString(",\n    ", prefix = "\n    ", postfix = "\n  ") { n ->
                "{\"path\": ${str(n.path)}, \"favorite\": ${n.favorite}, \"modified\": ${n.modifiedMillis}, \"content\": ${str(n.content)}}"
            }.let { if (notes.isEmpty()) "" else it })
            sb.append("]")
        }
        data.podcasts?.let { sb.append(",\n  \"podcasts\": ").append(PodcastCodec.encodeBackup(it)) }
        data.markets?.let { m ->
            sb.append(",\n  \"markets\": {")
            sb.append("\"positions\": ").append(PositionsLogic.encode(m.positions))
            sb.append(", \"indexId\": ").append(str(m.choice.indexId))
            sb.append(", \"indexRange\": ").append(str(m.choice.indexRange.key))
            sb.append(", \"commodityId\": ").append(str(m.choice.commodityId))
            sb.append(", \"commodityRange\": ").append(str(m.choice.commodityRange.key))
            sb.append(", \"cryptoRange\": ").append(str(m.choice.cryptoRange.key))
            sb.append(", \"range\": ").append(str(m.range.key))
            sb.append(", \"chartCurrency\": ").append(str(m.chartCurrency.key))
            sb.append(", \"fxFrom\": ").append(str(m.fxFrom)).append(", \"fxTo\": ").append(str(m.fxTo))
            sb.append(", \"yahoo\": ").append(m.yahooEnabled).append(", \"stooq\": ").append(m.stooqEnabled)
            m.baseUrl?.let { sb.append(", \"baseUrl\": ").append(str(it)) }
            sb.append(", \"holdingsVisible\": ").append(m.holdingsVisible)
            sb.append("}")
        }
        sb.append("\n}\n")
        return sb.toString()
    }

    fun decode(text: String, maxBytes: Int = MAX_BYTES): BackupData {
        if (text.length > maxBytes) throw BackupException("Datei zu groß")
        val root = try {
            MiniJson.parse(text).asObject()
        } catch (e: MiniJson.JsonException) {
            null
        } ?: throw BackupException("Kein JSON-Objekt")
        if (root["format"].asString() !in setOf(FORMAT, LEGACY_FORMAT)) throw BackupException("Unbekanntes Format")
        val version = root["version"].asDouble()?.toInt() ?: throw BackupException("Version fehlt")
        if (version != VERSION) throw BackupException("Nicht unterstützte Version $version")

        val apps = root["appFavorites"].asArray()
            ?.mapNotNull { it.asString()?.takeIf { s -> s.isNotBlank() } }
            ?.distinct()
            .orEmpty()
        val contacts = root["contactFavorites"].asArray()?.let { array ->
            val seen = HashSet<String>()
            array.mapNotNull { entry ->
                val o = entry.asObject() ?: return@mapNotNull null
                val number = o["number"].asString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val contact = FavoriteContact(o["name"].asString().orEmpty(), number)
                if (contact.key.isEmpty() || !seen.add(contact.key)) null else contact
            }
        }
        val places = root["weatherFavorites"].asArray()?.mapNotNull { parsePlace(it) }.orEmpty()
        val selected = parsePlace(root["selectedPlace"])
        val heights = root["shadowHeights"].asObject()?.let { o ->
            val person = o["person"].asDouble()
            val obj = o["object"].asDouble()
            if (person != null && obj != null && person in ShadowHeights.PERSON_RANGE && obj in ShadowHeights.OBJECT_RANGE) {
                ShadowHeights(person, obj)
            } else {
                null
            }
        }
        if (apps.size > MAX_ENTRIES || (contacts?.size ?: 0) > MAX_ENTRIES || places.size > MAX_ENTRIES) {
            throw BackupException("Zu viele Einträge")
        }
        val groups = root["appGroups"].asArray()?.let { array ->
            AppGroupCodec.sanitize(
                array.mapNotNull { entry ->
                    val o = entry.asObject() ?: return@mapNotNull null
                    val id = o["id"].asString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val name = o["name"].asString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val keys = o["apps"].asArray()?.mapNotNull { it.asString()?.takeIf { s -> s.isNotBlank() } }.orEmpty()
                    AppGroup(id, name, keys)
                },
            )
        }
        val notes = root["notes"].asArray()?.let { array ->
            if (array.size > NotesLogic.MAX_NOTES) throw BackupException("Zu viele Notizen")
            val seen = HashSet<String>()
            array.mapNotNull { entry ->
                val o = entry.asObject() ?: return@mapNotNull null
                val path = o["path"].asString()?.takeIf { NotesLogic.isSafePath(it) && NotesLogic.isNotePath(it) } ?: return@mapNotNull null
                if (!seen.add(path.lowercase())) return@mapNotNull null
                val content = (o["content"].asString() ?: return@mapNotNull null).take(NotesLogic.MAX_NOTE_CHARS)
                BackupNote(path, content, o["favorite"] as? Boolean ?: false, o["modified"].asDouble()?.toLong() ?: 0L)
            }
        }
        val podcasts = root["podcasts"].asObject()?.let { sanitizePodcasts(PodcastCodec.decodeBackup(it)) }
        val markets = root["markets"].asObject()?.let { parseMarkets(it) }
        return BackupData(apps, contacts, places, selected, heights, groups, notes, podcasts, markets)
    }

    private const val MAX_TEXT = 2_000
    private const val MAX_POSITION_ENTRIES = 5_000

    private fun isHttps(url: String?): Boolean =
        url != null && url.length <= MAX_TEXT && url.startsWith("https://", ignoreCase = true) && url.none { it.isWhitespace() }

    /** Behält nur gültige, begrenzte Einträge; Shownotes werden erneut bereinigt, weil die Datei von außen kommen kann. */
    internal fun sanitizePodcasts(data: PodcastData?): PodcastData? {
        data ?: return null
        val subs = LinkedHashMap<String, Podcast>()
        for (p in data.subscriptions) {
            if (isHttps(p.feedUrl) && p.feedUrl !in subs) {
                subs[p.feedUrl] = p.copy(
                    title = p.title.take(MAX_TEXT), author = p.author.take(MAX_TEXT),
                    imageUrl = p.imageUrl?.takeIf { isHttps(it) },
                )
            }
        }
        val favorites = LinkedHashMap<String, Episode>()
        for (e in data.favorites) {
            if (e.id.isNotBlank() && e.id.length <= MAX_TEXT && isHttps(e.audioUrl) && isHttps(e.podcastUrl) && e.id !in favorites) {
                favorites[e.id] = e.copy(
                    podcastTitle = e.podcastTitle.take(MAX_TEXT), title = e.title.take(MAX_TEXT),
                    mime = e.mime.take(100), notesHtml = FeedText.sanitize(e.notesHtml.take(200_000)),
                    link = e.link?.takeIf { isHttps(it) },
                    imageUrl = e.imageUrl?.takeIf { isHttps(it) },
                )
            }
            if (favorites.size >= PodcastLogic.MAX_FAVORITES) break
        }
        val positions = data.positionsMs.entries.filter { it.key.length <= MAX_TEXT && it.value > 0 }.take(MAX_POSITION_ENTRIES)
            .associate { it.key to it.value }
        return PodcastData(
            subscriptions = subs.values.take(PodcastLogic.MAX_SUBSCRIPTIONS),
            favorites = favorites.values.toList(),
            positionsMs = positions,
            played = data.played.filter { it.length <= MAX_TEXT }.take(MAX_POSITION_ENTRIES * 2).toSet(),
            provider = data.provider,
            speed = data.speed,
        )
    }

    private fun parseMarkets(o: Map<String, Any?>): BackupMarkets {
        val positions = o["positions"].asArray().orEmpty().mapNotNull { entry ->
            val e = entry.asObject() ?: return@mapNotNull null
            val symbol = e["s"].asString()?.takeIf { it.isNotBlank() && it.length <= 100 } ?: return@mapNotNull null
            val quantity = e["q"].asDouble()?.takeIf { it.isFinite() && it > 0.0 && it <= 1_000_000_000.0 } ?: return@mapNotNull null
            Position(
                symbol, (e["n"].asString() ?: symbol).take(200), e["i"].asString().orEmpty().take(100), quantity,
                PositionKind.fromKey(e["k"].asString()),
            )
        }.distinctBy { it.kind to it.symbol }.take(PositionsLogic.MAX_POSITIONS)
        val defaults = ChartChoice()
        fun id(key: String, default: String) = o[key].asString()?.takeIf { it.isNotBlank() && it.length <= 40 } ?: default
        val currency = { code: String?, default: String -> code?.takeIf { FxLogic.isSupported(it) } ?: default }
        return BackupMarkets(
            positions = positions,
            choice = ChartChoice(
                indexId = id("indexId", defaults.indexId),
                indexRange = SeriesRange.fromKey(o["indexRange"].asString()),
                commodityId = id("commodityId", defaults.commodityId),
                commodityRange = SeriesRange.fromKey(o["commodityRange"].asString()),
                cryptoRange = CryptoRange.fromKey(o["cryptoRange"].asString()),
            ),
            range = ChartRange.fromKey(o["range"].asString()),
            chartCurrency = ChartCurrency.fromKey(o["chartCurrency"].asString()),
            fxFrom = currency(o["fxFrom"].asString(), "EUR"),
            fxTo = currency(o["fxTo"].asString(), "USD"),
            yahooEnabled = o["yahoo"] as? Boolean ?: true,
            stooqEnabled = o["stooq"] as? Boolean ?: false,
            baseUrl = o["baseUrl"].asString()?.let { (MarketUrl.check(it) as? BaseUrlCheck.Ok)?.url },
            holdingsVisible = o["holdingsVisible"] as? Boolean ?: false,
        )
    }

    private fun parsePlace(value: Any?): GeoPlace? {
        val o = value.asObject() ?: return null
        val name = o["name"].asString()?.takeIf { it.isNotBlank() } ?: return null
        val lat = o["lat"].asDouble() ?: return null
        val lon = o["lon"].asDouble() ?: return null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        return GeoPlace(name, lat, lon)
    }

    private fun place(p: GeoPlace) = "{\"name\": ${str(p.name)}, \"lat\": ${num(p.latitude)}, \"lon\": ${num(p.longitude)}}"

    private fun num(v: Double): String = java.lang.Double.toString(v)

    private fun str(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' -> sb.append(String.format("\\u%04x", c.code))
                else -> sb.append(c)
            }
        }
        return sb.append('"').toString()
    }
}
