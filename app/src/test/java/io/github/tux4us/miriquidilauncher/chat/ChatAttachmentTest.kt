// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatAttachmentTest {
    @Test
    fun folderFromCapabilities() {
        val json = """{"ocs":{"data":{"capabilities":{"spreed":{"config":{"attachments":{"allowed":true,"folder":"/Talk"}}}}}}}"""
        assertEquals("/Talk", ChatApi.parseAttachmentFolder(json))
        assertEquals("Talk", ChatApi.sanitizeFolder("/Talk"))
        assertEquals("Anhänge/Chat", ChatApi.sanitizeFolder("/Anhänge/Chat/"))
        assertEquals("Talk", ChatApi.sanitizeFolder(null))
        assertEquals("Talk", ChatApi.sanitizeFolder("/a/../b"))
        assertEquals("Talk", ChatApi.sanitizeFolder("///"))
    }

    @Test
    fun unreadableCapabilitiesGiveNoFolder() {
        assertEquals(null, ChatApi.parseAttachmentFolder("kein json"))
        assertEquals(null, ChatApi.parseAttachmentFolder("""{"ocs":{"data":{}}}"""))
    }

    @Test
    fun fileNamesAreCleaned() {
        assertEquals("bild.jpg", ChatApi.safeFileName("/storage/emulated/0/bild.jpg"))
        assertEquals("bild.jpg", ChatApi.safeFileName("C:\\x\\bild.jpg"))
        assertEquals("Datei", ChatApi.safeFileName("..."))
        assertEquals("Datei", ChatApi.safeFileName(""))
        assertEquals("ab.txt", ChatApi.safeFileName("a:b*.txt".replace("*", "")))
        val long = "x".repeat(300) + ".pdf"
        val cut = ChatApi.safeFileName(long)
        assertEquals(120, cut.length)
        assertTrue(cut.endsWith(".pdf"))
    }

    @Test
    fun numberedNames() {
        assertEquals("Bild.jpg", ChatApi.numberedName("Bild.jpg", 1))
        assertEquals("Bild (2).jpg", ChatApi.numberedName("Bild.jpg", 2))
        assertEquals("Notiz (3)", ChatApi.numberedName("Notiz", 3))
    }

    @Test
    fun davUrlsAreEncoded() {
        val base = "https://cloud.example.org"
        assertEquals("$base/remote.php/dav/files/anna/Talk", ChatApi.davFolderUrl(base, "anna", "Talk"))
        assertEquals(
            "$base/remote.php/dav/files/anna%40x/A%20B/Bild%20%281%29.jpg",
            ChatApi.davFileUrl(base, "anna@x", "A B", "Bild (1).jpg"),
        )
        assertEquals(listOf("a", "a/b", "a/b/c"), ChatApi.folderChain("a/b/c"))
        assertEquals("$base/remote.php/dav/files/anna/Bild.jpg", ChatApi.davFileUrl(base, "anna", "", "Bild.jpg"))
        assertEquals("Talk/Bild.jpg", ChatApi.relativePath("Talk", "Bild.jpg"))
        assertEquals("Bild.jpg", ChatApi.relativePath("", "Bild.jpg"))
    }

    @Test
    fun shareForm() {
        val form = ChatApi.encodeShareForm("abc123", "Talk/Bild 1.jpg", "", "ref1")
        assertTrue(form.startsWith("shareType=10&shareWith=abc123&path="))
        assertTrue(form.contains("path=%2FTalk%2FBild+1.jpg"))
        assertTrue(!form.contains("talkMetaData"))
        val withCaption = ChatApi.encodeShareForm("abc123", "Talk/a.jpg", "Hallo \"Welt\"", "ref1")
        assertTrue(withCaption.contains("talkMetaData=" + java.net.URLEncoder.encode("{\"caption\":\"Hallo \\\"Welt\\\"\"}", "UTF-8")))
    }

    @Test
    fun fileOnlyMessageHidesPlaceholder() {
        val json = """{"ocs":{"data":[{"id":5,"actorType":"users","actorId":"anna","actorDisplayName":"Anna","timestamp":100,
            "message":"{file}","messageType":"comment","messageParameters":{"file":{"type":"file","name":"Bild.jpg","link":"https://cloud.example.org/f/1"}}}]}}"""
        val m = ChatApi.parseMessages(json)!!.single()
        assertEquals("", m.text)
        assertEquals("Bild.jpg", m.attachments.single().name)
        val captioned = json.replace("\"{file}\"", "\"Schau mal {file}\"")
        assertNotNull(ChatApi.parseMessages(captioned))
        assertEquals("Schau mal Bild.jpg", ChatApi.parseMessages(captioned)!!.single().text)
    }
}
