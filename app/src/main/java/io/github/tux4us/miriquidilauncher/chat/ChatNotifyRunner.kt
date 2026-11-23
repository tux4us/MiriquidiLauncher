// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.chat

import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/** Quelle der Hintergrundabfrage; für Tests ersetzbar. */
interface ChatNotifySource {
    suspend fun rooms(): List<ChatRoom>

    /** Die neuesten [limit] Nachrichten des Gesprächs. */
    suspend fun messages(token: String, limit: Int): List<ChatMessage>
}

/** Ziel der Hintergrundabfrage; für Tests ersetzbar. */
interface ChatNotifySink {
    /** [count] ist die Zahl der neuen Nachrichten, [lines] zeigt die letzten davon. */
    fun show(room: ChatRoom, lines: List<String>, count: Int)

    fun clear(token: String)
}

/** Ein Lauf der Hintergrundabfrage ohne Android-Abhängigkeiten. */
object ChatNotifyRunner {
    /**
     * Liefert die neue Grundlinie. Ohne gespeicherte Grundlinie ([baseline] `null`) wird nur der aktuelle Stand
     * übernommen. Ein Fehler bei einem einzelnen Gespräch (Netz) lässt dessen Grundlinie stehen; 401 und 403 werden
     * weitergereicht.
     */
    suspend fun run(source: ChatNotifySource, sink: ChatNotifySink, baseline: Map<String, Long>?, ownId: String, notify: Boolean): Map<String, Long> {
        val rooms = source.rooms()
        if (baseline == null) return ChatNotifyLogic.initialBaseline(rooms)
        ChatNotifyLogic.roomsToClear(rooms).forEach(sink::clear)
        val failed = mutableSetOf<String>()
        if (notify) {
            for (room in ChatNotifyLogic.roomsToFetch(rooms, baseline)) {
                try {
                    val fresh = ChatNotifyLogic.newMessages(source.messages(room.token, ChatNotifyLogic.fetchLimit(room)), baseline[room.token] ?: 0L, ownId)
                    if (fresh.isNotEmpty()) sink.show(room, ChatNotifyLogic.lines(room, fresh), maxOf(fresh.size, room.unread))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ChatHttpException) {
                    if (e.code == 401 || e.code == 403) throw e
                    failed += room.token
                } catch (e: IOException) {
                    failed += room.token
                }
            }
        }
        return ChatNotifyLogic.advance(rooms, baseline, failed)
    }
}
