// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatReactionTest {
    private fun msg(text: String = "Hallo", attachments: List<ChatAttachment> = emptyList(), kind: ChatMessageKind = ChatMessageKind.TEXT) =
        ChatMessage(5, "users", "bob", "Bob", 10, text, kind, attachments = attachments)

    @Test
    fun addsAndRemovesOwnReaction() {
        val added = ChatLogic.applyReaction(msg(), "👍", add = true)
        assertEquals(mapOf("👍" to 1), added.reactions)
        assertEquals(setOf("👍"), added.reactionsSelf)
        // Doppeltes Hinzufügen zählt nicht doppelt.
        assertEquals(1, ChatLogic.applyReaction(added, "👍", add = true).reactions["👍"])
        val removed = ChatLogic.applyReaction(added, "👍", add = false)
        assertTrue(removed.reactions.isEmpty())
        assertTrue(removed.reactionsSelf.isEmpty())
    }

    @Test
    fun keepsOthersReactionsWhenRemovingOwn() {
        val m = msg().copy(reactions = mapOf("❤️" to 3), reactionsSelf = setOf("❤️"))
        val r = ChatLogic.applyReaction(m, "❤️", add = false)
        assertEquals(mapOf("❤️" to 2), r.reactions)
        assertFalse("❤️" in r.reactionsSelf)
    }

    @Test
    fun forwardTextJoinsTextAndAttachments() {
        val m = msg("Schau mal", listOf(ChatAttachment("a.jpg", "https://c.example/f/1"), ChatAttachment("b.pdf", null)))
        assertEquals("Schau mal\na.jpg: https://c.example/f/1\nb.pdf", ChatLogic.forwardText(m))
        assertEquals("", ChatLogic.forwardText(msg(kind = ChatMessageKind.DELETED)))
        assertEquals("", ChatLogic.forwardText(msg(text = "  ")))
    }

    @Test
    fun parsesReactionsFromServerJson() {
        val json = """{"ocs":{"data":[{"id":1,"actorType":"users","actorId":"a","actorDisplayName":"A","timestamp":1,"message":"x",
            "messageType":"comment","reactions":{"👍":2,"😂":1},"reactionsSelf":["👍"]},
            {"id":2,"actorType":"users","actorId":"a","actorDisplayName":"A","timestamp":2,"message":"y","messageType":"comment","reactions":[]}]}}"""
        val list = ChatApi.parseMessages(json)!!
        assertEquals(mapOf("👍" to 2, "😂" to 1), list[0].reactions)
        assertEquals(setOf("👍"), list[0].reactionsSelf)
        assertTrue(list[1].reactions.isEmpty())
    }

    @Test
    fun reactionUrlsAndBodies() {
        assertEquals("https://c.example/ocs/v2.php/apps/spreed/api/v1/reaction/tok/12", ChatApi.reactionUrl("https://c.example", "tok", 12))
        assertEquals(
            "https://c.example/ocs/v2.php/apps/spreed/api/v1/reaction/tok/12?reaction=%F0%9F%91%8D",
            ChatApi.reactionDeleteUrl("https://c.example", "tok", 12, "👍"),
        )
        assertEquals("{\"reaction\":\"👍\"}", ChatApi.encodeReaction("👍"))
        assertTrue(ChatApi.isValidReaction("👍"))
        assertFalse(ChatApi.isValidReaction(""))
        assertFalse(ChatApi.isValidReaction("a b"))
        assertFalse(ChatApi.isValidReaction("x".repeat(40)))
        assertTrue(ChatApi.QUICK_REACTIONS.all(ChatApi::isValidReaction))
    }
}
