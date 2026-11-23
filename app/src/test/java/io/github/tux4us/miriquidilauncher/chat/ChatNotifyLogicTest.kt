// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatNotifyLogicTest {
    private fun room(token: String, unread: Int = 1, last: Long = 10, level: Int = 0, mention: Boolean = false, type: Int = ChatRoomType.GROUP) =
        ChatRoom(token, type, token, unread, mention, 0, "", false, false, last, level)

    private fun msg(id: Long, who: String = "bob", text: String = "t$id", kind: ChatMessageKind = ChatMessageKind.TEXT, actorType: String = "users") =
        ChatMessage(id, actorType, who, who.uppercase(), id, text, kind)

    @Test
    fun levelsDecideEligibility() {
        assertTrue(ChatNotifyLogic.levelAllows(room("a", level = 0)))
        assertTrue(ChatNotifyLogic.levelAllows(room("a", level = 1)))
        assertFalse(ChatNotifyLogic.levelAllows(room("a", level = 2)))
        assertTrue(ChatNotifyLogic.levelAllows(room("a", level = 2, mention = true)))
        assertFalse(ChatNotifyLogic.levelAllows(room("a", level = 3, mention = true)))
    }

    @Test
    fun fetchesOnlyRoomsWithNewerUnreadMessages() {
        val rooms = listOf(room("a", last = 10), room("b", last = 10), room("c", unread = 0, last = 50), room("d", level = 3, last = 99), room("e", last = 5))
        val baseline = mapOf("a" to 9L, "b" to 10L, "c" to 1L, "d" to 1L)
        assertEquals(listOf("a", "e"), ChatNotifyLogic.roomsToFetch(rooms, baseline).map { it.token })
    }

    @Test
    fun filtersOwnSystemDeletedAndBotMessages() {
        val list = listOf(msg(5), msg(6, who = "me"), msg(7, kind = ChatMessageKind.SYSTEM), msg(8, kind = ChatMessageKind.DELETED), msg(9, actorType = "bots"), msg(4), msg(10))
        assertEquals(listOf(5L, 10L), ChatNotifyLogic.newMessages(list, 4, "me").map { it.id })
    }

    @Test
    fun linesShowSenderOnlyInGroups() {
        val group = ChatNotifyLogic.lines(room("g"), listOf(msg(1, text = "Hallo\nWelt")))
        assertEquals(listOf("BOB: Hallo Welt"), group)
        assertEquals(listOf("Hallo"), ChatNotifyLogic.lines(room("o", type = ChatRoomType.ONE_TO_ONE), listOf(msg(1, text = "Hallo"))))
        assertEquals(ChatNotifyLogic.MAX_LINES, ChatNotifyLogic.lines(room("g"), (1L..9L).map { msg(it) }).size)
        assertEquals("BOB: t9", ChatNotifyLogic.lines(room("g"), (1L..9L).map { msg(it) }).last())
        assertTrue(ChatNotifyLogic.lines(room("o", type = ChatRoomType.ONE_TO_ONE), listOf(msg(1, text = "x".repeat(500))))[0].length <= 200)
    }

    @Test
    fun baselineAdvancesExceptForFailures() {
        val rooms = listOf(room("a", last = 20), room("b", last = 30), room("n", last = 7))
        val next = ChatNotifyLogic.advance(rooms, mapOf("a" to 10L, "b" to 10L, "gone" to 3L), setOf("b"))
        assertEquals(mapOf("a" to 20L, "b" to 10L, "n" to 7L), next)
        assertEquals(mapOf("a" to 20L, "b" to 30L, "n" to 7L), ChatNotifyLogic.initialBaseline(rooms))
    }

    @Test
    fun clearsReadAndMutedRooms() {
        assertEquals(listOf("r", "m"), ChatNotifyLogic.roomsToClear(listOf(room("r", unread = 0), room("u"), room("m", level = 3))))
    }

    @Test
    fun baselineRoundTripIsTolerant() {
        val map = mapOf("abc123" to 5L, "def456" to 77L)
        assertEquals(map, ChatNotifyLogic.decodeBaseline(ChatNotifyLogic.encodeBaseline(map)))
        assertTrue(ChatNotifyLogic.decodeBaseline("kaputt{").isEmpty())
        assertTrue(ChatNotifyLogic.decodeBaseline(null).isEmpty())
        assertEquals(map, ChatNotifyLogic.decodeBaseline("{\"abc123\":5,\"def456\":77,\"../x\":1,\"zz\":\"x\"}"))
    }

    @Test
    fun notificationIdsAreStableAndPositive() {
        assertEquals(ChatNotifyLogic.notificationId("abc"), ChatNotifyLogic.notificationId("abc"))
        assertTrue(ChatNotifyLogic.notificationId("zzzzzzzzzzzz") > 0)
    }
}
