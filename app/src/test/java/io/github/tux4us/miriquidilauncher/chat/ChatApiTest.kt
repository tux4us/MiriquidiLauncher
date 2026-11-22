// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatApiTest {
    private val base = "https://cloud.example.org/nextcloud"

    @Test
    fun urlsAndQueries() {
        assertEquals("$base/ocs/v2.php/apps/spreed/api/v4/room", ChatApi.roomsUrl(base))
        assertEquals("$base/ocs/v2.php/cloud/user", ChatApi.userUrl(base))
        assertEquals("$base/ocs/v2.php/apps/spreed/api/v1/chat/abc123", ChatApi.chatUrl(base, "abc123"))
        assertEquals("$base/ocs/v2.php/apps/spreed/api/v1/chat/abc123/read", ChatApi.readUrl(base, "abc123"))
        assertEquals(
            "?limit=50&setReadMarker=0&includeLastKnown=0&lookIntoFuture=0",
            ChatApi.messagesQuery(future = false, lastKnownId = 0, limit = 50),
        )
        assertEquals(
            "?limit=50&setReadMarker=0&includeLastKnown=0&lookIntoFuture=0&lastKnownMessageId=77",
            ChatApi.messagesQuery(future = false, lastKnownId = 77, limit = 50),
        )
        assertEquals(
            "?limit=50&setReadMarker=0&includeLastKnown=0&lookIntoFuture=1&lastKnownMessageId=9&timeout=25",
            ChatApi.messagesQuery(future = true, lastKnownId = 9, limit = 50, timeoutSeconds = 25),
        )
    }

    @Test
    fun tokensAreChecked() {
        assertTrue(ChatApi.isSafeToken("a1B2c3d4"))
        assertFalse(ChatApi.isSafeToken(""))
        assertFalse(ChatApi.isSafeToken("../etc"))
        assertFalse(ChatApi.isSafeToken("abc?x=1"))
        assertFalse(ChatApi.isSafeToken("a".repeat(65)))
    }

    @Test
    fun sendBodyIsEscaped() {
        assertEquals("{\"message\":\"Hi \\\"du\\\"\\nZeile\",\"referenceId\":\"r1\"}", ChatApi.encodeSend("Hi \"du\"\nZeile", null, "r1"))
        assertEquals("{\"message\":\"x\",\"referenceId\":\"r\",\"replyTo\":42}", ChatApi.encodeSend("x", 42L, "r"))
        assertEquals("{\"lastReadMessage\":12}", ChatApi.encodeRead(12))
    }

    @Test
    fun parsesUserId() {
        assertEquals("anna", ChatApi.parseUserId("""{"ocs":{"meta":{"status":"ok"},"data":{"id":"anna","enabled":true}}}"""))
        assertNull(ChatApi.parseUserId("""{"ocs":{"data":{}}}"""))
        assertNull(ChatApi.parseUserId("<html>"))
    }

    @Test
    fun parsesRooms() {
        val json = """
            {"ocs":{"meta":{"statuscode":200},"data":[
              {"token":"tok1","type":2,"name":"team","displayName":"Team","unreadMessages":3,"unreadMention":true,
               "lastActivity":1700000100,"isFavorite":true,"readOnly":0,
               "lastMessage":{"id":5,"actorType":"users","actorId":"bob","actorDisplayName":"Bob","timestamp":1700000100,
                              "message":"Hallo {mention-user1}","messageParameters":{"mention-user1":{"type":"user","id":"anna","name":"Anna"}}}},
              {"token":"tok2","type":1,"name":"bob","displayName":"Bob","unreadMessages":0,"unreadMention":false,
               "lastActivity":1700000000,"readOnly":1,"lastMessage":[]},
              {"token":"../bad","type":2,"name":"x"},
              {"type":2}
            ]}}
        """.trimIndent()
        val rooms = ChatApi.parseRooms(json)
        assertNotNull(rooms)
        assertEquals(2, rooms!!.size)
        val team = rooms[0]
        assertEquals("Team", team.name)
        assertEquals(3, team.unread)
        assertTrue(team.unreadMention)
        assertTrue(team.favorite)
        assertFalse(team.readOnly)
        assertTrue(team.isGroup)
        assertEquals("Bob: Hallo @Anna", team.lastPreview)
        val bob = rooms[1]
        assertEquals("", bob.lastPreview)
        assertTrue(bob.readOnly)
        assertFalse(bob.isGroup)
    }

    @Test
    fun roomsFromErrorPageAreNull() {
        assertNull(ChatApi.parseRooms("<html>Wartungsmodus</html>"))
        assertNull(ChatApi.parseRooms("""{"ocs":{"data":{"message":"x"}}}"""))
        assertEquals(emptyList<ChatRoom>(), ChatApi.parseRooms("""{"ocs":{"data":[]}}"""))
    }

    @Test
    fun parsesMessagesWithReplyFileAndSystem() {
        val json = """
            {"ocs":{"data":[
              {"id":10,"actorType":"users","actorId":"anna","actorDisplayName":"Anna","timestamp":1700000000,"message":"Siehe {file}",
               "messageType":"comment","systemMessage":"","referenceId":"ref1",
               "messageParameters":{"file":{"type":"file","name":"Plan.pdf","link":"https://cloud.example.org/f/123"}},
               "parent":{"id":9,"actorDisplayName":"Bob","message":"Hast du den {mention-user1}?\nDanke",
                         "messageParameters":{"mention-user1":{"type":"user","id":"anna","name":"Anna"}}}},
              {"id":11,"actorType":"users","actorId":"bob","actorDisplayName":"Bob","timestamp":1700000050,"message":"x","messageType":"comment_deleted","systemMessage":""},
              {"id":12,"actorType":"users","actorId":"bob","actorDisplayName":"Bob","timestamp":1700000060,"message":"{actor} hat das Gespräch verlassen","systemMessage":"user_removed",
               "messageParameters":{"actor":{"type":"user","id":"bob","name":"Bob"}}},
              {"id":13,"actorType":"users","actorId":"bob","timestamp":1700000070,"message":"","systemMessage":"reaction"},
              {"id":0,"message":"ohne Id"}
            ]}}
        """.trimIndent()
        val messages = ChatApi.parseMessages(json)!!
        assertEquals(listOf(10L, 11L, 12L), messages.map { it.id })
        val m = messages[0]
        assertEquals("Siehe Plan.pdf", m.text)
        assertEquals(ChatMessageKind.TEXT, m.kind)
        assertEquals("ref1", m.referenceId)
        assertEquals(listOf(ChatAttachment("Plan.pdf", "https://cloud.example.org/f/123")), m.attachments)
        assertEquals(ChatReply(9, "Bob", "Hast du den @Anna? Danke"), m.reply)
        assertTrue(m.isOwn("anna"))
        assertFalse(m.isOwn("bob"))
        assertFalse(m.isOwn(""))
        assertEquals(ChatMessageKind.DELETED, messages[1].kind)
        assertEquals("", messages[1].text)
        assertEquals(ChatMessageKind.SYSTEM, messages[2].kind)
        assertEquals("Bob hat das Gespräch verlassen", messages[2].text)
    }

    @Test
    fun attachmentLinksMustBeHttps() {
        val json = """{"ocs":{"data":[{"id":1,"actorType":"users","actorId":"a","message":"{file}",
            "messageParameters":{"file":{"type":"file","name":"x.txt","link":"javascript:alert(1)"}}}]}}"""
        val m = ChatApi.parseMessages(json)!!.single()
        assertEquals(listOf(ChatAttachment("x.txt", null)), m.attachments)
        assertTrue(ChatApi.isHttps("https://a.example/x"))
        assertFalse(ChatApi.isHttps("http://a.example/x"))
        assertFalse(ChatApi.isHttps("https://"))
        assertFalse(ChatApi.isHttps("https://user@evil.example/x"))
        assertFalse(ChatApi.isHttps("ftp://a.example"))
    }

    @Test
    fun parsesSingleMessageAndRejectsGarbage() {
        val one = ChatApi.parseSingleMessage("""{"ocs":{"data":{"id":7,"actorType":"users","actorId":"a","actorDisplayName":"A","timestamp":5,"message":"ok","referenceId":"r"}}}""")
        assertEquals(7L, one!!.id)
        assertNull(ChatApi.parseSingleMessage("nope"))
        assertNull(ChatApi.parseMessages("""{"ocs":{"data":{}}}"""))
    }

    @Test
    fun renderKeepsUnknownPlaceholders() {
        assertEquals("a {x} b", ChatApi.render("a {x} b", mapOf("y" to mapOf("name" to "Y"))))
        assertEquals("plain", ChatApi.render("plain", null))
        val params = mapOf(
            "mention-user1" to mapOf("type" to "user", "id" to "anna", "name" to "Anna"),
            "mention-user2" to mapOf("type" to "user", "id" to "bob", "name" to "@Bob"),
            "actor" to mapOf("type" to "user", "id" to "carl"),
        )
        assertEquals("@Anna, @Bob und carl", ChatApi.render("{mention-user1}, {mention-user2} und {actor}", params))
    }
}
