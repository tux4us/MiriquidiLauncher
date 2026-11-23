// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.chat

import io.github.tux4us.miriquidilauncher.weather.MiniJson
import io.github.tux4us.miriquidilauncher.weather.asDouble
import io.github.tux4us.miriquidilauncher.weather.asObject

/**
 * Entscheidungen der Hintergrundabfrage für Chat-Benachrichtigungen, ohne Android-Abhängigkeiten.
 *
 * Der Zustand ist eine Zuordnung Gesprächs-Token → Id der zuletzt berücksichtigten Nachricht („Grundlinie“).
 * Nur Nachrichten mit größerer Id lösen eine Benachrichtigung aus. Beim ersten Lauf nach dem Einschalten wird die
 * Grundlinie auf den aktuellen Stand gesetzt, damit keine alten ungelesenen Nachrichten gemeldet werden.
 */
object ChatNotifyLogic {
    const val LEVEL_ALWAYS = 1
    const val LEVEL_MENTION = 2
    const val LEVEL_NEVER = 3

    /** Höchstzahl der je Gespräch abgerufenen und angezeigten Nachrichten. */
    const val MAX_FETCH = 10
    const val MAX_LINES = 5
    private const val LINE_CHARS = 200

    /** Grundlinie für das Einschalten: aktueller Stand aller Gespräche. */
    fun initialBaseline(rooms: List<ChatRoom>): Map<String, Long> = rooms.associate { it.token to it.lastMessageId }

    /** Erlaubt die Benachrichtigungsstufe des Gesprächs eine Meldung für den aktuellen Zustand? */
    fun levelAllows(room: ChatRoom): Boolean = when (room.notificationLevel) {
        LEVEL_NEVER -> false
        LEVEL_MENTION -> room.unreadMention
        else -> true
    }

    /** Gespräche, für die Nachrichten abzurufen sind. */
    fun roomsToFetch(rooms: List<ChatRoom>, baseline: Map<String, Long>): List<ChatRoom> =
        rooms.filter { it.unread > 0 && levelAllows(it) && it.lastMessageId > (baseline[it.token] ?: 0L) }

    /** Anzahl der abzurufenden Nachrichten: so viele wie ungelesen, mindestens 1, höchstens [MAX_FETCH]. */
    fun fetchLimit(room: ChatRoom): Int = room.unread.coerceIn(1, MAX_FETCH)

    /** Neue, fremde, sichtbare Textnachrichten in aufsteigender Reihenfolge. */
    fun newMessages(messages: List<ChatMessage>, after: Long, ownId: String): List<ChatMessage> =
        messages.filter { it.id > after && it.kind == ChatMessageKind.TEXT && !it.isOwn(ownId) && it.actorType != "bots" }
            .sortedBy { it.id }

    /** Zeilen für die Benachrichtigung („Absender: Text“, bei Einzelgesprächen nur der Text), die neuesten zuletzt. */
    fun lines(room: ChatRoom, messages: List<ChatMessage>): List<String> =
        messages.takeLast(MAX_LINES).map { m ->
            val text = m.text.replace('\n', ' ').trim().take(LINE_CHARS)
            if (room.isGroup && m.actorName.isNotEmpty()) "${m.actorName}: $text" else text
        }

    /** Stabile Benachrichtigungs-Id je Gespräch. */
    fun notificationId(token: String): Int = (token.hashCode() and 0x3fffffff) + 1000

    /**
     * Neue Grundlinie nach einem Lauf. Gespräche, die abgerufen werden sollten, aber in [failed] stehen, behalten den
     * alten Wert und werden beim nächsten Lauf erneut versucht; alle anderen rücken auf den aktuellen Stand vor.
     * Gespräche, die der Server nicht mehr meldet, entfallen.
     */
    fun advance(rooms: List<ChatRoom>, old: Map<String, Long>, failed: Set<String>): Map<String, Long> =
        rooms.associate { r ->
            val before = old[r.token] ?: 0L
            r.token to if (r.token in failed) before else maxOf(before, r.lastMessageId)
        }

    /** Tokens, deren Benachrichtigung zu entfernen ist: gelesene oder stummgeschaltete Gespräche. */
    fun roomsToClear(rooms: List<ChatRoom>): List<String> = rooms.filter { it.unread == 0 || it.notificationLevel == LEVEL_NEVER }.map { it.token }

    fun encodeBaseline(map: Map<String, Long>): String =
        "{" + map.entries.joinToString(",") { "\"${it.key}\":${it.value}" } + "}"

    /** Leere Zuordnung bei unlesbaren Daten. */
    fun decodeBaseline(text: String?): Map<String, Long> {
        if (text.isNullOrBlank()) return emptyMap()
        val root = try {
            MiniJson.parse(text).asObject()
        } catch (e: MiniJson.JsonException) {
            null
        } ?: return emptyMap()
        return root.mapNotNull { (k, v) ->
            val n = v.asDouble()?.toLong() ?: return@mapNotNull null
            if (ChatApi.isSafeToken(k)) k to n else null
        }.toMap()
    }
}
