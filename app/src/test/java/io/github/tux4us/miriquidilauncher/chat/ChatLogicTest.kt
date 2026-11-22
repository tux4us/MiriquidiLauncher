// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.chat

import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatLogicTest {
    private fun msg(id: Long, text: String = "m$id", ref: String = "") = ChatMessage(
        id = id, actorType = "users", actorId = "a", actorName = "A", timestamp = id, text = text,
        kind = ChatMessageKind.TEXT, referenceId = ref,
    )

    private fun room(token: String, activity: Long, fav: Boolean = false, unread: Int = 0, name: String = token, preview: String = "") =
        ChatRoom(token, ChatRoomType.GROUP, name, unread, false, activity, preview, fav, false)

    @Test
    fun checksOutgoingText() {
        assertEquals(OutgoingCheck.EMPTY, ChatLogic.check("  \n "))
        assertEquals(OutgoingCheck.OK, ChatLogic.check("Hallo"))
        assertEquals(OutgoingCheck.TOO_LONG, ChatLogic.check("x".repeat(ChatApi.MAX_MESSAGE_CHARS + 1)))
        assertEquals("a\nb", ChatLogic.clean("  a\nb \n"))
    }

    @Test
    fun roomsAreSortedAndFiltered() {
        val rooms = listOf(room("a", 10), room("b", 30), room("c", 5, fav = true), room("d", 30, name = "Alpha"))
        assertEquals(listOf("c", "d", "b", "a"), ChatLogic.sortRooms(rooms).map { it.token })
        assertEquals(listOf("d"), ChatLogic.filterRooms(rooms, " alp ").map { it.token })
        assertEquals(4, ChatLogic.filterRooms(rooms, null).size)
        assertEquals(listOf("a"), ChatLogic.filterRooms(listOf(room("a", 1, preview = "Bob: Grillen am Samstag"), room("b", 1)), "grillen").map { it.token })
        assertEquals(5, ChatLogic.totalUnread(listOf(room("a", 1, unread = 2), room("b", 1, unread = 3))))
    }

    @Test
    fun mergeReplacesSortsAndLimits() {
        val merged = ChatLogic.merge(listOf(msg(3), msg(1)), listOf(msg(2), msg(3, "neu")))
        assertEquals(listOf(1L, 2L, 3L), merged.map { it.id })
        assertEquals("neu", merged.last().text)
        assertEquals(listOf(4L, 5L), ChatLogic.merge(listOf(msg(1), msg(2), msg(3)), listOf(msg(4), msg(5)), max = 2).map { it.id })
        val same = listOf(msg(1))
        assertTrue(ChatLogic.merge(same, emptyList()) === same)
    }

    @Test
    fun latestDropsCacheOnPossibleGap() {
        val cached = listOf(msg(1), msg(2), msg(3))
        // Volle Seite, die nach der neuesten gespeicherten Nachricht beginnt: dazwischen kann etwas fehlen.
        assertEquals(listOf(10L, 11L), ChatLogic.mergeLatest(cached, listOf(msg(10), msg(11)), limit = 2).map { it.id })
        // Nicht volle Seite: es fehlt nichts.
        assertEquals(listOf(1L, 2L, 3L, 10L), ChatLogic.mergeLatest(cached, listOf(msg(10)), limit = 2).map { it.id })
        // Volle Seite, die an den Zwischenspeicher anschließt.
        assertEquals(listOf(1L, 2L, 3L, 4L), ChatLogic.mergeLatest(cached, listOf(msg(3), msg(4)), limit = 2).map { it.id })
        assertEquals(cached, ChatLogic.mergeLatest(cached, emptyList(), limit = 2))
        assertEquals(listOf(5L), ChatLogic.mergeLatest(emptyList(), listOf(msg(5)), limit = 2).map { it.id })
    }

    @Test
    fun idsAndSlices() {
        assertEquals(0L, ChatLogic.newestId(emptyList()))
        assertEquals(0L, ChatLogic.oldestId(emptyList()))
        assertEquals(9L, ChatLogic.newestId(listOf(msg(9), msg(2))))
        assertEquals(2L, ChatLogic.oldestId(listOf(msg(9), msg(2))))
        val many = (1L..250L).map { msg(it) }
        assertEquals(ChatLogic.CACHE_MESSAGES, ChatLogic.cacheSlice(many).size)
        assertEquals(250L, ChatLogic.cacheSlice(many).last().id)
    }

    @Test
    fun backoffGrowsAndIsCapped() {
        assertEquals(0L, ChatLogic.backoffMillis(0))
        assertEquals(2_000L, ChatLogic.backoffMillis(1))
        assertEquals(4_000L, ChatLogic.backoffMillis(2))
        assertEquals(32_000L, ChatLogic.backoffMillis(5))
        assertEquals(60_000L, ChatLogic.backoffMillis(6))
        assertEquals(60_000L, ChatLogic.backoffMillis(50))
    }

    @Test
    fun dayChangeUsesZone() {
        val utc = ZoneId.of("UTC")
        assertTrue(ChatLogic.isNewDay(null, 100, utc))
        assertFalse(ChatLogic.isNewDay(0, 86_399, utc))
        assertTrue(ChatLogic.isNewDay(86_399, 86_400, utc))
        // Dieselben zwei Zeitpunkte liegen in Berlin (UTC+1) am selben Tag.
        assertFalse(ChatLogic.isNewDay(86_399, 86_400, ZoneId.of("Europe/Berlin")))
    }

    @Test
    fun linksAreSplitOut() {
        assertEquals(listOf(ChatTextSegment("Text")), ChatLogic.segments("Text"))
        assertEquals(
            listOf(ChatTextSegment("Schau "), ChatTextSegment("https://a.example/x?y=1", "https://a.example/x?y=1"), ChatTextSegment(", ok?")),
            ChatLogic.segments("Schau https://a.example/x?y=1, ok?"),
        )
        assertEquals(
            listOf(ChatTextSegment("("), ChatTextSegment("https://a.example", "https://a.example"), ChatTextSegment(")")),
            ChatLogic.segments("(https://a.example)"),
        )
        // http und andere Schemata werden nie zum Link.
        assertEquals(listOf(ChatTextSegment("http://a.example javascript:x")), ChatLogic.segments("http://a.example javascript:x"))
        assertEquals(emptyList<ChatTextSegment>(), ChatLogic.segments(""))
    }

    @Test
    fun confirmedByReferenceId() {
        val outbox = listOf(
            Outgoing("l1", "ref1", "tok", "a", null),
            Outgoing("l2", "ref2", "tok", "b", null),
        )
        assertEquals(setOf("l1"), ChatLogic.confirmedLocalIds(outbox, listOf(msg(1, ref = "ref1"), msg(2))))
        assertEquals(emptySet<String>(), ChatLogic.confirmedLocalIds(outbox, listOf(msg(1, ref = ""))))
    }

    @Test
    fun cacheRoundTrip() {
        val rooms = listOf(room("a", 10, fav = true, unread = 2, name = "Ä \"Q\"", preview = "Zeile\nZwei"))
        assertEquals(rooms, ChatCacheCodec.decodeRooms(ChatCacheCodec.encodeRooms(rooms)))
        val messages = listOf(
            msg(1),
            ChatMessage(
                2, "users", "b", "B", 5, "Text \\ \"x\"\n2", ChatMessageKind.TEXT,
                reply = ChatReply(1, "A", "m1"),
                attachments = listOf(ChatAttachment("f.txt", "https://c.example/f"), ChatAttachment("g.txt", null)),
                referenceId = "r",
            ),
            ChatMessage(3, "users", "b", "B", 6, "", ChatMessageKind.DELETED),
        )
        assertEquals(messages, ChatCacheCodec.decodeMessages(ChatCacheCodec.encodeMessages(messages)))
    }

    @Test
    fun cacheRejectsForeignData() {
        assertEquals(emptyList<ChatRoom>(), ChatCacheCodec.decodeRooms("{\"v\":99,\"rooms\":[]}"))
        assertEquals(emptyList<ChatRoom>(), ChatCacheCodec.decodeRooms("kaputt"))
        assertEquals(emptyList<ChatMessage>(), ChatCacheCodec.decodeMessages("[]"))
        // Unsichere Token und Links aus manipulierten Dateien werden verworfen.
        val rooms = ChatCacheCodec.decodeRooms("""{"v":1,"rooms":[{"token":"../x","name":"n"},{"token":"ok1","name":"n"}]}""")
        assertEquals(listOf("ok1"), rooms.map { it.token })
        val m = ChatCacheCodec.decodeMessages("""{"v":1,"messages":[{"id":1,"files":[{"name":"a","link":"javascript:x"}]}]}""").single()
        assertEquals(null, m.attachments.single().link)
    }

    @Test
    fun cacheKeepsOnlyNewestMessages() {
        val many = (1L..300L).map { msg(it) }
        val decoded = ChatCacheCodec.decodeMessages(ChatCacheCodec.encodeMessages(many))
        assertEquals(ChatLogic.CACHE_MESSAGES, decoded.size)
        assertEquals(300L, decoded.last().id)
    }
}
