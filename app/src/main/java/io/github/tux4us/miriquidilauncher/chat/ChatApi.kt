// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.chat

import io.github.tux4us.miriquidilauncher.notes.NotesJson
import io.github.tux4us.miriquidilauncher.weather.MiniJson
import io.github.tux4us.miriquidilauncher.weather.asArray
import io.github.tux4us.miriquidilauncher.weather.asDouble
import io.github.tux4us.miriquidilauncher.weather.asObject
import io.github.tux4us.miriquidilauncher.weather.asString

/**
 * Adressen, Anfragekörper und Auswertung der Antworten der Nextcloud-Talk-Schnittstelle (OCS, Räume v4, Chat v1).
 * Ohne Android-Klassen, damit die Auswertung auf der JVM testbar ist.
 */
object ChatApi {
    /** Obergrenze für den Text einer Nachricht (Standard von Talk: 32 000 Zeichen). */
    const val MAX_MESSAGE_CHARS = 32_000

    /** Obergrenze für gespeicherte und angezeigte Nachrichtentexte. */
    const val MAX_TEXT_CHARS = 10_000

    private const val PREVIEW_CHARS = 120

    /** Systemnachrichten, die Talk selbst nicht als Zeile anzeigt (Reaktionen, Löschhinweise, Abstimmungen, Anrufbeitritte). */
    private val HIDDEN_SYSTEM = setOf(
        "reaction", "reaction_deleted", "reaction_revoked", "message_deleted", "message_edited",
        "poll_voted", "call_joined", "call_left", "call_started", "call_ended",
    )

    private fun ocs(baseUrl: String, path: String) = "$baseUrl/ocs/v2.php/$path"

    fun roomsUrl(baseUrl: String): String = ocs(baseUrl, "apps/spreed/api/v4/room")

    fun userUrl(baseUrl: String): String = ocs(baseUrl, "cloud/user")

    /** Adresse des Gesprächs-Chats; [query] ist die fertige Abfragezeichenkette mit führendem `?` oder leer. */
    fun chatUrl(baseUrl: String, token: String, query: String = ""): String =
        ocs(baseUrl, "apps/spreed/api/v1/chat/$token") + query

    fun readUrl(baseUrl: String, token: String): String = chatUrl(baseUrl, token) + "/read"

    /** Adresse der Reaktionen auf die Nachricht [messageId] (POST fügt hinzu, DELETE mit `reaction` in der Abfrage entfernt). */
    fun reactionUrl(baseUrl: String, token: String, messageId: Long): String =
        ocs(baseUrl, "apps/spreed/api/v1/reaction/$token/$messageId")

    fun reactionDeleteUrl(baseUrl: String, token: String, messageId: Long, emoji: String): String =
        reactionUrl(baseUrl, token, messageId) + "?reaction=" + java.net.URLEncoder.encode(emoji, "UTF-8")

    fun encodeReaction(emoji: String): String = "{\"reaction\":${NotesJson.str(emoji)}}"

    /** Emojis, die das Menü zur schnellen Reaktion anbietet. */
    val QUICK_REACTIONS: List<String> = listOf("👍", "❤️", "😂", "😮", "😢", "🙏")

    /** Ein Emoji als Reaktion: kurz, ohne Steuerzeichen und Leerraum (Talk prüft zusätzlich selbst). */
    fun isValidReaction(emoji: String): Boolean =
        emoji.isNotEmpty() && emoji.length <= 16 && emoji.none { it.isWhitespace() || it.code < 32 }

    fun capabilitiesUrl(baseUrl: String): String = ocs(baseUrl, "cloud/capabilities")

    fun sharesUrl(baseUrl: String): String = ocs(baseUrl, "apps/files_sharing/api/v1/shares")

    /** Größtmögliche Datei für einen Anhang (der Server kann enger begrenzen). */
    const val MAX_ATTACHMENT_BYTES = 100L * 1024 * 1024

    private const val DEFAULT_FOLDER = "Talk"

    /** Ordner für Anhänge laut Server (`spreed.config.attachments.folder`); `null`, wenn die Antwort ihn nicht nennt. */
    fun parseAttachmentFolder(json: String): String? {
        val root = try {
            MiniJson.parse(json).asObject()
        } catch (e: MiniJson.JsonException) {
            null
        } ?: return null
        return root["ocs"].asObject()?.get("data").asObject()?.get("capabilities").asObject()?.get("spreed").asObject()
            ?.get("config").asObject()?.get("attachments").asObject()?.get("folder").asString()
    }

    /** Ordnerpfad ohne führende und abschließende Schrägstriche; bei `..`, leeren oder zu tiefen Pfaden der Standard „Talk“. */
    fun sanitizeFolder(raw: String?): String {
        val parts = raw.orEmpty().split('/').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty() || parts.size > 5 || parts.any { it == "." || it == ".." || it.any { c -> c.code < 32 } }) return DEFAULT_FOLDER
        return parts.joinToString("/")
    }

    /** Dateiname ohne Pfadanteile und Steuerzeichen, höchstens 120 Zeichen (Endung bleibt), leer wird zu „Datei“. */
    fun safeFileName(raw: String): String {
        val base = raw.substringAfterLast('/').substringAfterLast('\\')
            .filter { it.code >= 32 && it != ':' && it != '*' && it != '?' && it != '"' && it != '<' && it != '>' && it != '|' }
            .trim().trimStart('.')
        if (base.isEmpty()) return "Datei"
        if (base.length <= 120) return base
        val dot = base.lastIndexOf('.')
        val ext = if (dot > 0 && base.length - dot <= 10) base.substring(dot) else ""
        return base.substring(0, 120 - ext.length) + ext
    }

    /** Name für den [attempt]. Versuch (1 = unverändert): `Bild (2).jpg`. */
    fun numberedName(name: String, attempt: Int): String {
        if (attempt <= 1) return name
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(0, dot) + " ($attempt)" + name.substring(dot) else "$name ($attempt)"
    }

    private fun encodeSegment(s: String): String = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /** WebDAV-Adresse eines Ordners ([folder] mit `/` zwischen den Teilen) im Dateibereich von [userId]. */
    fun davFolderUrl(baseUrl: String, userId: String, folder: String): String =
        "$baseUrl/remote.php/dav/files/${encodeSegment(userId)}/" + folder.split('/').filter { it.isNotEmpty() }.joinToString("/") { encodeSegment(it) }

    /** Adresse der Datei [name] im [folder] (leer = Wurzelordner) von [userId]. */
    fun davFileUrl(baseUrl: String, userId: String, folder: String, name: String): String =
        if (folder.isEmpty()) "$baseUrl/remote.php/dav/files/${encodeSegment(userId)}/${encodeSegment(name)}"
        else davFolderUrl(baseUrl, userId, folder) + "/" + encodeSegment(name)

    /** Pfad der Datei relativ zum Dateibereich des Benutzers, ohne führenden Schrägstrich. */
    fun relativePath(folder: String, name: String): String = if (folder.isEmpty()) name else "$folder/$name"

    /** Alle Ordner des Pfades von oben nach unten (`a`, `a/b`, …), die vor dem Hochladen vorhanden sein müssen. */
    fun folderChain(folder: String): List<String> {
        val parts = folder.split('/').filter { it.isNotEmpty() }
        return parts.indices.map { parts.subList(0, it + 1).joinToString("/") }
    }

    /** Formularkörper, der die hochgeladene Datei im Gespräch teilt (Freigabeart 10 = Talk-Gespräch). */
    fun encodeShareForm(token: String, relativePath: String, caption: String, referenceId: String): String {
        fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
        val meta = if (caption.isEmpty()) "" else "&talkMetaData=" + enc("{\"caption\":${NotesJson.str(caption)}}")
        return "shareType=10&shareWith=${enc(token)}&path=${enc("/$relativePath")}&referenceId=${enc(referenceId)}$meta"
    }

    /** Talk-Token bestehen aus Buchstaben und Ziffern; alles andere darf nie in eine Adresse gelangen. */
    fun isSafeToken(token: String): Boolean =
        token.isNotEmpty() && token.length <= 64 && token.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }

    /**
     * Abfrage für Nachrichten. [future] = `true`: wartet bis zu [timeoutSeconds] auf neue Nachrichten nach [lastKnownId]
     * (Long-Polling). Sonst werden bis zu [limit] Nachrichten vor [lastKnownId] geliefert (0 = die neuesten).
     */
    fun messagesQuery(future: Boolean, lastKnownId: Long, limit: Int, timeoutSeconds: Int = 0): String {
        val parts = mutableListOf("limit=$limit", "setReadMarker=0", "includeLastKnown=0")
        parts += if (future) "lookIntoFuture=1" else "lookIntoFuture=0"
        if (lastKnownId > 0) parts += "lastKnownMessageId=$lastKnownId"
        if (future) parts += "timeout=$timeoutSeconds"
        return "?" + parts.joinToString("&")
    }

    /** JSON-Rumpf zum Senden. */
    fun encodeSend(text: String, replyToId: Long?, referenceId: String): String {
        val reply = if (replyToId != null && replyToId > 0) ",\"replyTo\":$replyToId" else ""
        return "{\"message\":${NotesJson.str(text)},\"referenceId\":${NotesJson.str(referenceId)}$reply}"
    }

    /** JSON-Rumpf der Gelesen-Markierung. */
    fun encodeRead(lastReadId: Long): String = "{\"lastReadMessage\":$lastReadId}"

    private fun data(json: String): Any? {
        val root = try {
            MiniJson.parse(json).asObject()
        } catch (e: MiniJson.JsonException) {
            null
        } ?: return null
        return root["ocs"].asObject()?.get("data")
    }

    /** Benutzer-Id aus der Antwort von `cloud/user`; `null`, wenn sie fehlt. */
    fun parseUserId(json: String): String? =
        data(json).asObject()?.get("id").asString()?.takeIf { it.isNotBlank() }

    /** Raumliste; `null`, wenn die Antwort keine Liste ist (Fehlerseite, Wartungsmodus). */
    fun parseRooms(json: String): List<ChatRoom>? =
        data(json).asArray()?.mapNotNull { parseRoom(it.asObject()) }

    /** Nachrichtenliste; `null`, wenn die Antwort keine Liste ist. Systemnachrichten ohne Anzeige sind entfernt. */
    fun parseMessages(json: String): List<ChatMessage>? =
        data(json).asArray()?.mapNotNull { parseMessage(it.asObject()) }

    /** Eine einzelne Nachricht, z. B. die Antwort auf das Senden. */
    fun parseSingleMessage(json: String): ChatMessage? = parseMessage(data(json).asObject())

    private fun parseRoom(o: Map<String, Any?>?): ChatRoom? {
        o ?: return null
        val token = o["token"].asString()?.takeIf(::isSafeToken) ?: return null
        val name = o["displayName"].asString()?.takeIf { it.isNotBlank() } ?: o["name"].asString().orEmpty()
        // Ohne letzte Nachricht liefert Talk eine leere Liste statt eines Objekts.
        val last = o["lastMessage"].asObject()?.let { parseMessage(it, keepHidden = true) }
        val preview = last?.let {
            val who = if (it.kind == ChatMessageKind.TEXT && it.actorName.isNotEmpty()) it.actorName + ": " else ""
            who + it.text.replace('\n', ' ').take(PREVIEW_CHARS)
        }.orEmpty()
        return ChatRoom(
            token = token,
            type = o["type"].asDouble()?.toInt() ?: ChatRoomType.GROUP,
            name = name,
            unread = o["unreadMessages"].asDouble()?.toInt()?.coerceAtLeast(0) ?: 0,
            unreadMention = o["unreadMention"] as? Boolean ?: false,
            lastActivity = o["lastActivity"].asDouble()?.toLong() ?: 0L,
            lastPreview = preview,
            favorite = o["isFavorite"] as? Boolean ?: false,
            readOnly = (o["readOnly"].asDouble()?.toInt() ?: 0) == 1,
            lastMessageId = last?.id ?: 0L,
            notificationLevel = o["notificationLevel"].asDouble()?.toInt() ?: 0,
        )
    }

    private fun parseMessage(o: Map<String, Any?>?, keepHidden: Boolean = false): ChatMessage? {
        o ?: return null
        val id = o["id"].asDouble()?.toLong()?.takeIf { it > 0 } ?: return null
        val system = o["systemMessage"].asString().orEmpty()
        if (system in HIDDEN_SYSTEM && !keepHidden) return null
        val params = o["messageParameters"].asObject()
        val deleted = o["messageType"].asString() == "comment_deleted" || o["deleted"] as? Boolean == true
        val kind = when {
            deleted -> ChatMessageKind.DELETED
            system.isNotEmpty() -> ChatMessageKind.SYSTEM
            else -> ChatMessageKind.TEXT
        }
        val raw = o["message"].asString().orEmpty()
        val files = if (deleted) emptyList() else attachments(params)
        // Eine Nachricht, die nur aus dem Datei-Platzhalter besteht, zeigt den Dateinamen schon als Anhang.
        val text = when {
            deleted -> ""
            files.isNotEmpty() && FILE_ONLY.matches(raw.trim()) -> ""
            else -> render(raw, params).take(MAX_TEXT_CHARS)
        }
        val reply = o["parent"].asObject()?.let { p ->
            val parentId = p["id"].asDouble()?.toLong()?.takeIf { it > 0 } ?: return@let null
            val parentDeleted = p["messageType"].asString() == "comment_deleted" || p["deleted"] as? Boolean == true
            ChatReply(
                id = parentId,
                author = p["actorDisplayName"].asString().orEmpty(),
                text = if (parentDeleted) "" else render(p["message"].asString().orEmpty(), p["messageParameters"].asObject()).replace('\n', ' ').take(PREVIEW_CHARS),
            )
        }
        return ChatMessage(
            id = id,
            actorType = o["actorType"].asString().orEmpty(),
            actorId = o["actorId"].asString().orEmpty(),
            actorName = o["actorDisplayName"].asString().orEmpty(),
            timestamp = o["timestamp"].asDouble()?.toLong() ?: 0L,
            text = text,
            kind = kind,
            reply = reply,
            attachments = files,
            referenceId = o["referenceId"].asString().orEmpty(),
            reactions = if (deleted) emptyMap() else parseReactions(o["reactions"].asObject()),
            reactionsSelf = if (deleted) emptySet() else (o["reactionsSelf"].asArray()?.mapNotNull { it.asString() }?.toSet() ?: emptySet()),
        )
    }

    /** Ohne Reaktionen liefert Talk eine leere Liste statt eines Objekts. */
    private fun parseReactions(map: Map<String, Any?>?): Map<String, Int> {
        map ?: return emptyMap()
        val out = LinkedHashMap<String, Int>()
        for ((emoji, count) in map) {
            val n = count.asDouble()?.toInt() ?: continue
            if (n > 0 && isValidReaction(emoji) && out.size < MAX_REACTION_KINDS) out[emoji] = n
        }
        return out
    }

    private const val MAX_REACTION_KINDS = 12

    private val FILE_ONLY = Regex("(\\{file[0-9]*\\}\\s*)+")
    private val PLACEHOLDER = Regex("\\{([A-Za-z0-9_\\-]+)\\}")

    /**
     * Ersetzt Platzhalter im Nachrichtentext durch die Namen aus `messageParameters`: Erwähnungen (`mention-…`) als
     * `@Name`, Dateien und übrige Objekte mit ihrem Namen. Unbekannte Platzhalter bleiben stehen.
     */
    fun render(message: String, params: Map<String, Any?>?): String {
        if (params == null || params.isEmpty() || !message.contains('{')) return message
        return PLACEHOLDER.replace(message) { match ->
            val key = match.groupValues[1]
            val p = params[key].asObject() ?: return@replace match.value
            val name = p["name"].asString()?.takeIf { it.isNotEmpty() } ?: p["id"].asString()?.takeIf { it.isNotEmpty() } ?: return@replace match.value
            if (key.startsWith("mention-") && !name.startsWith("@")) "@$name" else name
        }
    }

    private fun attachments(params: Map<String, Any?>?): List<ChatAttachment> {
        params ?: return emptyList()
        return params.values.mapNotNull { v ->
            val p = v.asObject() ?: return@mapNotNull null
            if (p["type"].asString() != "file") return@mapNotNull null
            val name = p["name"].asString()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            ChatAttachment(name, p["link"].asString()?.takeIf { isHttps(it) })
        }.take(MAX_ATTACHMENTS)
    }

    private const val MAX_ATTACHMENTS = 10

    /** Nur `https://` mit Host; Links ohne Schema oder mit anderem Schema werden nie geöffnet. */
    fun isHttps(url: String): Boolean {
        if (!url.startsWith("https://", ignoreCase = true)) return false
        val rest = url.substring(8)
        val host = rest.substringBefore('/').substringBefore('?').substringBefore('#')
        return host.isNotEmpty() && !host.contains('@') && host.none { it.isWhitespace() }
    }
}
