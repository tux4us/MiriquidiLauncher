// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.chat

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatNotifyRunnerTest {
    private class Source(var rooms: List<ChatRoom>, val messages: MutableMap<String, List<ChatMessage>> = mutableMapOf(), val failing: MutableSet<String> = mutableSetOf(), var httpCode: Int? = null) : ChatNotifySource {
        val fetched = mutableListOf<String>()
        override suspend fun rooms(): List<ChatRoom> = rooms
        override suspend fun messages(token: String, limit: Int): List<ChatMessage> {
            fetched += token
            httpCode?.let { throw ChatHttpException(it) }
            if (token in failing) throw IOException("netz")
            return messages[token].orEmpty()
        }
    }

    private class Sink : ChatNotifySink {
        val shown = mutableListOf<Triple<String, List<String>, Int>>()
        val cleared = mutableListOf<String>()
        override fun show(room: ChatRoom, lines: List<String>, count: Int) { shown += Triple(room.token, lines, count) }
        override fun clear(token: String) { cleared += token }
    }

    private fun room(token: String, unread: Int, last: Long, level: Int = 0, mention: Boolean = false) =
        ChatRoom(token, ChatRoomType.GROUP, "Raum $token", unread, mention, 0, "", false, false, last, level)

    private fun msg(id: Long, who: String = "bob", text: String = "t$id") =
        ChatMessage(id, "users", who, who, id, text, ChatMessageKind.TEXT)

    @Test
    fun firstRunOnlySetsBaseline() = runBlocking {
        val src = Source(listOf(room("a", 3, 30)))
        val sink = Sink()
        assertEquals(mapOf("a" to 30L), ChatNotifyRunner.run(src, sink, null, "me", true))
        assertTrue(sink.shown.isEmpty() && src.fetched.isEmpty())
    }

    @Test
    fun notifiesOnceForNewMessagesOnly() = runBlocking {
        val src = Source(listOf(room("a", 2, 12)), mutableMapOf("a" to listOf(msg(10), msg(11, "me"), msg(12))))
        val sink = Sink()
        val next = ChatNotifyRunner.run(src, sink, mapOf("a" to 9L), "me", true)
        assertEquals(mapOf("a" to 12L), next)
        assertEquals(1, sink.shown.size)
        assertEquals(listOf("bob: t10", "bob: t12"), sink.shown[0].second)
        assertEquals(2, sink.shown[0].third)
        val again = ChatNotifyRunner.run(src, Sink().also { s -> assertTrue(s.shown.isEmpty()) }, next, "me", true)
        assertEquals(next, again)
        assertEquals(listOf("a"), src.fetched)
    }

    @Test
    fun mutedMentionOnlyAndReadRoomsStaySilent() = runBlocking {
        val rooms = listOf(room("m", 1, 5, level = 3), room("n", 1, 5, level = 2), room("r", 0, 5), room("y", 1, 5, level = 2, mention = true))
        val src = Source(rooms, mutableMapOf("y" to listOf(msg(5))))
        val sink = Sink()
        val next = ChatNotifyRunner.run(src, sink, mapOf("m" to 1L, "n" to 1L, "r" to 1L, "y" to 1L), "me", true)
        assertEquals(listOf("y"), sink.shown.map { it.first })
        assertEquals(listOf("m", "r"), sink.cleared)
        assertEquals(5L, next["n"])
    }

    @Test
    fun failedRoomIsRetriedNextRun() = runBlocking {
        val src = Source(listOf(room("a", 1, 7), room("b", 1, 8)), mutableMapOf("b" to listOf(msg(8))), failing = mutableSetOf("a"))
        val sink = Sink()
        val next = ChatNotifyRunner.run(src, sink, mapOf("a" to 6L, "b" to 7L), "me", true)
        assertEquals(mapOf("a" to 6L, "b" to 8L), next)
        assertEquals(listOf("b"), sink.shown.map { it.first })
    }

    @Test
    fun authErrorsPropagateOtherCodesDoNot() = runBlocking {
        val src = Source(listOf(room("a", 1, 7)), httpCode = 401)
        var thrown = false
        try { ChatNotifyRunner.run(src, Sink(), mapOf("a" to 1L), "me", true) } catch (e: ChatHttpException) { thrown = e.code == 401 }
        assertTrue(thrown)
        src.httpCode = 500
        assertEquals(mapOf("a" to 1L), ChatNotifyRunner.run(src, Sink(), mapOf("a" to 1L), "me", true))
    }

    @Test
    fun withoutPermissionBaselineAdvancesSilently() = runBlocking {
        val src = Source(listOf(room("a", 1, 7)), mutableMapOf("a" to listOf(msg(7))))
        val sink = Sink()
        assertEquals(mapOf("a" to 7L), ChatNotifyRunner.run(src, sink, mapOf("a" to 1L), "me", false))
        assertTrue(sink.shown.isEmpty() && src.fetched.isEmpty())
    }

    @Test
    fun newRoomAfterEnablingNotifies() = runBlocking {
        val src = Source(listOf(room("a", 1, 3), room("new", 1, 4)), mutableMapOf("new" to listOf(msg(4))))
        val sink = Sink()
        ChatNotifyRunner.run(src, sink, mapOf("a" to 3L), "me", true)
        assertEquals(listOf("new"), sink.shown.map { it.first })
    }
}
