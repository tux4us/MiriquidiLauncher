// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.chat

import io.github.tux4us.miriquidilauncher.notes.NotesJson
import io.github.tux4us.miriquidilauncher.weather.MiniJson
import io.github.tux4us.miriquidilauncher.weather.asArray
import io.github.tux4us.miriquidilauncher.weather.asDouble
import io.github.tux4us.miriquidilauncher.weather.asObject
import io.github.tux4us.miriquidilauncher.weather.asString
import java.time.Instant
import java.time.ZoneId

enum class OutgoingCheck { OK, EMPTY, TOO_LONG }

/** Reine Logik des Messenger-Scopes ohne Android-Klassen. */
object ChatLogic {
    /** Nachrichten je Anfrage und im Zwischenspeicher. */
    const val PAGE_SIZE = 50
    const val CACHE_MESSAGES = 100
    const val CACHE_ROOMS = 200

    /** Obergrenze der im Arbeitsspeicher gehaltenen Nachrichten eines geöffneten Gesprächs (ältere lädt niemand nach). */
    const val MEMORY_MESSAGES = 2000

    /** Wartezeit des Long-Pollings; das Lesezeitlimit der Verbindung muss darüber liegen. */
    const val POLL_SECONDS = 25

    /** Prüft den Text vor dem Senden. */
    fun check(text: String): OutgoingCheck = when {
        text.isBlank() -> OutgoingCheck.EMPTY
        text.length > ChatApi.MAX_MESSAGE_CHARS -> OutgoingCheck.TOO_LONG
        else -> OutgoingCheck.OK
    }

    /** Text ohne Leerraum am Rand; innere Zeilenumbrüche bleiben. */
    fun clean(text: String): String = text.trim()

    /** Räume: Favoriten zuerst, dann nach letzter Aktivität (neueste zuerst), bei Gleichstand nach Name. */
    fun sortRooms(rooms: List<ChatRoom>): List<ChatRoom> =
        rooms.sortedWith(
            compareByDescending<ChatRoom> { it.favorite }
                .thenByDescending { it.lastActivity }
                .thenBy { it.name.lowercase() },
        )

    /** Räume, deren Name oder Vorschau [query] enthält (ohne Beachtung der Schreibweise). */
    fun filterRooms(rooms: List<ChatRoom>, query: String?): List<ChatRoom> {
        val needle = query?.trim().orEmpty()
        if (needle.isEmpty()) return rooms
        return rooms.filter { it.name.contains(needle, ignoreCase = true) || it.lastPreview.contains(needle, ignoreCase = true) }
    }

    fun totalUnread(rooms: List<ChatRoom>): Int = rooms.sumOf { it.unread }

    /** Vereinigt [existing] und [incoming] nach Id (Neues ersetzt Altes), aufsteigend sortiert, auf die neuesten [max] begrenzt. */
    fun merge(existing: List<ChatMessage>, incoming: List<ChatMessage>, max: Int = MEMORY_MESSAGES): List<ChatMessage> {
        if (incoming.isEmpty()) return existing
        val byId = LinkedHashMap<Long, ChatMessage>()
        existing.forEach { byId[it.id] = it }
        incoming.forEach { byId[it.id] = it }
        val sorted = byId.values.sortedBy { it.id }
        return if (sorted.size > max) sorted.takeLast(max) else sorted
    }

    /**
     * Verbindet die neuesten Nachrichten [latest] (Anfrage mit [limit]) mit dem Zwischenspeicher [cached]. Ist [latest] voll
     * und beginnt nach der neuesten gespeicherten Nachricht, kann dazwischen eine Lücke liegen; dann gilt nur [latest].
     */
    fun mergeLatest(cached: List<ChatMessage>, latest: List<ChatMessage>, limit: Int): List<ChatMessage> {
        if (latest.isEmpty()) return cached
        val newestCached = cached.maxOfOrNull { it.id } ?: return merge(emptyList(), latest)
        val oldestLatest = latest.minOf { it.id }
        val possibleGap = latest.size >= limit && oldestLatest > newestCached
        return if (possibleGap) merge(emptyList(), latest) else merge(cached, latest)
    }

    /** Setzt oder nimmt die eigene Reaktion [emoji] an [message] zurück und passt die Anzahl an. */
    fun applyReaction(message: ChatMessage, emoji: String, add: Boolean): ChatMessage {
        val counts = LinkedHashMap(message.reactions)
        val own = LinkedHashSet(message.reactionsSelf)
        if (add) {
            if (own.add(emoji)) counts[emoji] = (counts[emoji] ?: 0) + 1
        } else if (own.remove(emoji)) {
            val left = (counts[emoji] ?: 1) - 1
            if (left > 0) counts[emoji] = left else counts.remove(emoji)
        }
        return message.copy(reactions = counts, reactionsSelf = own)
    }

    /**
     * Text, der beim Weiterleiten oder für eine Erinnerung verwendet wird: der Nachrichtentext, darunter je Anhang eine
     * Zeile mit Name und (falls bekannt) Link. Leer bei gelöschten und Systemnachrichten.
     */
    fun forwardText(message: ChatMessage): String {
        if (message.kind != ChatMessageKind.TEXT) return ""
        val files = message.attachments.map { a -> if (a.link != null) "${a.name}: ${a.link}" else a.name }
        return (listOf(message.text.trim()) + files).filter { it.isNotEmpty() }.joinToString("\n").take(ChatApi.MAX_MESSAGE_CHARS)
    }

    /** Neueste Nachrichten für den Zwischenspeicher. */
    fun cacheSlice(messages: List<ChatMessage>): List<ChatMessage> = messages.takeLast(CACHE_MESSAGES)

    /** Id der neuesten Nachricht oder 0. */
    fun newestId(messages: List<ChatMessage>): Long = messages.maxOfOrNull { it.id } ?: 0L

    /** Id der ältesten Nachricht oder 0. */
    fun oldestId(messages: List<ChatMessage>): Long = messages.minOfOrNull { it.id } ?: 0L

    /** Wartezeit nach [failures] Fehlern in Folge: 2, 4, 8, … höchstens 60 Sekunden (in Millisekunden). */
    fun backoffMillis(failures: Int): Long {
        if (failures <= 0) return 0L
        val seconds = 1L shl failures.coerceAtMost(6)
        return seconds.coerceAtMost(60L) * 1000L
    }

    /** `true`, wenn [timestamp] und [previous] (Sekunden) in [zone] an verschiedenen Kalendertagen liegen. */
    fun isNewDay(previous: Long?, timestamp: Long, zone: ZoneId): Boolean {
        if (previous == null) return true
        val a = Instant.ofEpochSecond(previous).atZone(zone).toLocalDate()
        val b = Instant.ofEpochSecond(timestamp).atZone(zone).toLocalDate()
        return a != b
    }

    private val LINK = Regex("https://[^\\s<>\"]+")
    private const val TRAILING = ".,;:!?)]}'"

    /** Zerlegt [text] in Teile; HTTPS-Adressen werden als Links markiert, abschließende Satzzeichen gehören nicht dazu. */
    fun segments(text: String): List<ChatTextSegment> {
        val result = mutableListOf<ChatTextSegment>()
        var position = 0
        for (match in LINK.findAll(text)) {
            var url = match.value
            while (url.isNotEmpty() && url.last() in TRAILING) url = url.dropLast(1)
            if (!ChatApi.isHttps(url)) continue
            if (match.range.first > position) result += ChatTextSegment(text.substring(position, match.range.first))
            result += ChatTextSegment(url, url)
            position = match.range.first + url.length
        }
        if (position < text.length) result += ChatTextSegment(text.substring(position))
        return result
    }

    /** Neue Nachrichten, die schon als eigene, noch ausstehende Sendung bekannt sind, gelten als bestätigt. */
    fun confirmedLocalIds(outbox: List<Outgoing>, incoming: List<ChatMessage>): Set<String> {
        val refs = incoming.mapNotNull { it.referenceId.takeIf { r -> r.isNotEmpty() } }.toSet()
        return outbox.filter { it.referenceId in refs }.map { it.localId }.toSet()
    }
}

/** Textformat des verschlüsselten Zwischenspeichers (Klartext-JSON, wird erst danach verschlüsselt). */
object ChatCacheCodec {
    private const val VERSION = 1

    fun encodeRooms(rooms: List<ChatRoom>): String {
        val items = rooms.take(ChatLogic.CACHE_ROOMS).joinToString(",") {
            "{\"token\":${NotesJson.str(it.token)},\"type\":${it.type},\"name\":${NotesJson.str(it.name)}," +
                "\"unread\":${it.unread},\"mention\":${it.unreadMention},\"activity\":${it.lastActivity}," +
                "\"preview\":${NotesJson.str(it.lastPreview)},\"fav\":${it.favorite},\"ro\":${it.readOnly},\"lm\":${it.lastMessageId},\"nl\":${it.notificationLevel}}"
        }
        return "{\"v\":$VERSION,\"rooms\":[$items]}"
    }

    /** Leere Liste bei unlesbaren oder fremden Daten. */
    fun decodeRooms(text: String): List<ChatRoom> {
        val root = parse(text) ?: return emptyList()
        return root["rooms"].asArray()?.mapNotNull { e ->
            val o = e.asObject() ?: return@mapNotNull null
            ChatRoom(
                token = o["token"].asString()?.takeIf(ChatApi::isSafeToken) ?: return@mapNotNull null,
                type = o["type"].asDouble()?.toInt() ?: ChatRoomType.GROUP,
                name = o["name"].asString().orEmpty(),
                unread = o["unread"].asDouble()?.toInt() ?: 0,
                unreadMention = o["mention"] as? Boolean ?: false,
                lastActivity = o["activity"].asDouble()?.toLong() ?: 0L,
                lastPreview = o["preview"].asString().orEmpty(),
                favorite = o["fav"] as? Boolean ?: false,
                readOnly = o["ro"] as? Boolean ?: false,
                lastMessageId = o["lm"].asDouble()?.toLong() ?: 0L,
                notificationLevel = o["nl"].asDouble()?.toInt() ?: 0,
            )
        }.orEmpty()
    }

    fun encodeMessages(messages: List<ChatMessage>): String {
        val items = ChatLogic.cacheSlice(messages).joinToString(",") { m ->
            val reply = m.reply?.let { ",\"reply\":{\"id\":${it.id},\"author\":${NotesJson.str(it.author)},\"text\":${NotesJson.str(it.text)}}" }.orEmpty()
            val attachments = if (m.attachments.isEmpty()) "" else
                ",\"files\":[" + m.attachments.joinToString(",") { a ->
                    "{\"name\":${NotesJson.str(a.name)}" + (a.link?.let { l -> ",\"link\":${NotesJson.str(l)}" } ?: "") + "}"
                } + "]"
            "{\"id\":${m.id},\"at\":${NotesJson.str(m.actorType)},\"ai\":${NotesJson.str(m.actorId)},\"an\":${NotesJson.str(m.actorName)}," +
                "\"ts\":${m.timestamp},\"text\":${NotesJson.str(m.text)},\"kind\":${NotesJson.str(m.kind.name)}," +
                "\"ref\":${NotesJson.str(m.referenceId)}$reply$attachments}"
        }
        return "{\"v\":$VERSION,\"messages\":[$items]}"
    }

    /** Leere Liste bei unlesbaren oder fremden Daten. */
    fun decodeMessages(text: String): List<ChatMessage> {
        val root = parse(text) ?: return emptyList()
        return root["messages"].asArray()?.mapNotNull { e ->
            val o = e.asObject() ?: return@mapNotNull null
            val id = o["id"].asDouble()?.toLong()?.takeIf { it > 0 } ?: return@mapNotNull null
            val kind = ChatMessageKind.entries.firstOrNull { it.name == o["kind"].asString() } ?: ChatMessageKind.TEXT
            ChatMessage(
                id = id,
                actorType = o["at"].asString().orEmpty(),
                actorId = o["ai"].asString().orEmpty(),
                actorName = o["an"].asString().orEmpty(),
                timestamp = o["ts"].asDouble()?.toLong() ?: 0L,
                text = o["text"].asString().orEmpty().take(ChatApi.MAX_TEXT_CHARS),
                kind = kind,
                reply = o["reply"].asObject()?.let { r ->
                    ChatReply(r["id"].asDouble()?.toLong() ?: 0L, r["author"].asString().orEmpty(), r["text"].asString().orEmpty())
                },
                attachments = o["files"].asArray()?.mapNotNull { f ->
                    val fo = f.asObject() ?: return@mapNotNull null
                    ChatAttachment(fo["name"].asString() ?: return@mapNotNull null, fo["link"].asString()?.takeIf { ChatApi.isHttps(it) })
                }.orEmpty(),
                referenceId = o["ref"].asString().orEmpty(),
            )
        }.orEmpty()
    }

    private fun parse(text: String): Map<String, Any?>? {
        val root = try {
            MiniJson.parse(text).asObject()
        } catch (e: MiniJson.JsonException) {
            null
        } ?: return null
        return if (root["v"].asDouble()?.toInt() == VERSION) root else null
    }
}
