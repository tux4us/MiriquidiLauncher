// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotesTest {
    private fun remote(id: Long, etag: String, content: String, category: String = "", fav: Boolean = false, title: String = NotesLogic.titleOf(content)) =
        RemoteNote(id, etag, title, content, category, fav, 100L)

    private fun entry(path: String, id: Long, etag: String, content: String, fav: Boolean = false) =
        IndexEntry(path, id, etag, NotesLogic.hash(content), fav)

    @Test
    fun titleAndFileNames() {
        assertEquals("Einkauf", NotesLogic.titleOf("\n\n# Einkauf\n- Milch"))
        assertEquals("Milch", NotesLogic.titleOf("- [ ] Milch"))
        assertEquals("", NotesLogic.titleOf("  \n \n"))
        assertEquals("a-b-c", NotesLogic.fileBase("a/b:c"))
        assertEquals("Notiz", NotesLogic.fileBase("..."))
        assertEquals(NotesLogic.MAX_NAME, NotesLogic.fileBase("x".repeat(300)).length)
    }

    @Test
    fun categoriesAreSanitized() {
        assertEquals("A/B", NotesLogic.sanitizeCategory("/A/../B/"))
        assertEquals("", NotesLogic.sanitizeCategory("../.."))
        assertEquals("a-b", NotesLogic.sanitizeCategory("a:b"))
        assertEquals(NotesLogic.MAX_DEPTH, NotesLogic.sanitizeCategory("1/2/3/4/5/6/7").split('/').size)
        assertTrue(NotesLogic.isSafePath("A/b.md"))
        assertFalse(NotesLogic.isSafePath("../b.md"))
        assertFalse(NotesLogic.isSafePath("/b.md"))
        assertFalse(NotesLogic.isSafePath("A//b.md"))
    }

    @Test
    fun uniquePathsAndRenameOnEdit() {
        val taken = NotesLogic.takenSet(listOf("Eins.md", "eins (1).md", "K/Zwei.md"))
        assertEquals("Eins (2).md", NotesLogic.uniquePath(taken, "", "Eins"))
        assertEquals("K/Drei.md", NotesLogic.uniquePath(taken, "K", "Drei"))
        // Titel unverändert (auch mit Zähler): kein Umbenennen
        assertEquals("Eins (1).md", NotesLogic.pathAfterEdit("Eins (1).md", "# Eins\nText", taken))
        // Titel geändert: neuer Name
        assertEquals("Neu.md", NotesLogic.pathAfterEdit("Eins.md", "Neu\nText", taken))
        // Kollision mit anderer Notiz
        assertEquals("Eins (2).md", NotesLogic.pathAfterEdit("Alt.md", "Eins", taken + "alt.md"))
        // leerer Inhalt: Pfad bleibt
        assertEquals("Eins.md", NotesLogic.pathAfterEdit("Eins.md", "", taken))
        assertEquals("K2/Zwei.md", NotesLogic.pathInCategory("K/Zwei.md", "K2", taken))
    }

    @Test
    fun visibleFiltersAndOrders() {
        val notes = listOf(
            Note("b.md", "Banane\ngelb", 10, false),
            Note("K/a.md", "Apfel\nrot", 30, false),
            Note("K/L/c.md", "Citrone", 20, true),
        )
        assertEquals(listOf("c.md", "a.md", "b.md"), NotesLogic.visible(notes, "", null, false, NoteSort.MODIFIED, false).map { NotesLogic.fileNameOf(it.path) })
        assertEquals(listOf("c.md", "a.md", "b.md"), NotesLogic.visible(notes, "", null, false, NoteSort.TITLE, true).map { NotesLogic.fileNameOf(it.path) }.let { it })
        assertEquals(2, NotesLogic.visible(notes, "", "K", false, NoteSort.TITLE, true).size)
        assertEquals(1, NotesLogic.visible(notes, "ROT", null, false, NoteSort.TITLE, true).size)
        assertEquals(listOf("c.md"), NotesLogic.visible(notes, "", null, true, NoteSort.TITLE, true).map { NotesLogic.fileNameOf(it.path) })
        assertEquals(listOf("K" to 1, "K/L" to 1), NotesLogic.categories(notes))
        assertEquals("gelb", NotesLogic.preview("Banane\n\n**gelb**"))
    }

    @Test
    fun syncDownloadsNewRemoteAndUploadsNewLocal() {
        val actions = NotesSync.plan(
            local = listOf(LocalNote("Lokal.md", "Lokal", false)),
            index = emptyList(),
            remote = listOf(remote(1, "e1", "Server\ntext", "Arbeit")),
        )
        assertTrue(actions.contains(SyncAction.Download(remote(1, "e1", "Server\ntext", "Arbeit"), "Arbeit/Server.md", null)))
        assertTrue(actions.contains(SyncAction.Upload("Lokal.md", null)))
        assertEquals(2, actions.size)
    }

    @Test
    fun syncNameCollisionGetsCounter() {
        val actions = NotesSync.plan(listOf(LocalNote("Gleich.md", "Gleich", false)), emptyList(), listOf(remote(5, "e", "Gleich\nanders")))
        val download = actions.filterIsInstance<SyncAction.Download>().single()
        assertEquals("Gleich (1).md", download.path)
    }

    @Test
    fun syncDetectsOneSidedChanges() {
        val base = "Titel\nalt"
        val idx = listOf(entry("Titel.md", 1, "e1", base))
        // nichts geändert
        assertTrue(NotesSync.plan(listOf(LocalNote("Titel.md", base, false)), idx, listOf(remote(1, "e1", base))).isEmpty())
        // nur lokal geändert
        assertEquals(
            listOf<SyncAction>(SyncAction.Upload("Titel.md", 1)),
            NotesSync.plan(listOf(LocalNote("Titel.md", "Titel\nneu", false)), idx, listOf(remote(1, "e1", base))),
        )
        // nur Favorit lokal geändert
        assertEquals(
            listOf<SyncAction>(SyncAction.Upload("Titel.md", 1)),
            NotesSync.plan(listOf(LocalNote("Titel.md", base, true)), idx, listOf(remote(1, "e1", base))),
        )
        // nur auf dem Server geändert, gleicher Titel: gleicher Pfad
        val changed = remote(1, "e2", "Titel\nserver")
        assertEquals(
            listOf<SyncAction>(SyncAction.Download(changed, "Titel.md", null)),
            NotesSync.plan(listOf(LocalNote("Titel.md", base, false)), idx, listOf(changed)),
        )
        // Server ändert Titel: Umbenennen
        val renamed = remote(1, "e3", "Anders\nalt")
        assertEquals(
            listOf<SyncAction>(SyncAction.Download(renamed, "Anders.md", "Titel.md")),
            NotesSync.plan(listOf(LocalNote("Titel.md", base, false)), idx, listOf(renamed)),
        )
        // beide gleich geändert: nur Zuordnung nachziehen
        val same = remote(1, "e4", "Titel\ngleich")
        assertEquals(
            listOf<SyncAction>(SyncAction.Adopt("Titel.md", same)),
            NotesSync.plan(listOf(LocalNote("Titel.md", "Titel\ngleich", false)), idx, listOf(same)),
        )
    }

    @Test
    fun syncConflictKeepsBoth() {
        val base = "Titel\nalt"
        val idx = listOf(entry("Titel.md", 1, "e1", base))
        val r = remote(1, "e2", "Titel\nserver")
        val actions = NotesSync.plan(listOf(LocalNote("Titel.md", "Titel\nlokal", false)), idx, listOf(r))
        assertEquals(listOf<SyncAction>(SyncAction.Conflict("Titel.md", r, "Titel (Konflikt).md")), actions)
    }

    @Test
    fun syncHandlesDeletions() {
        val base = "Titel\nalt"
        val idx = listOf(entry("Titel.md", 1, "e1", base))
        // lokal gelöscht, Server unverändert: Server löschen
        assertEquals(
            listOf<SyncAction>(SyncAction.DeleteRemote(1, "Titel.md")),
            NotesSync.plan(emptyList(), idx, listOf(remote(1, "e1", base))),
        )
        // lokal gelöscht, Server geändert: neu laden
        val changed = remote(1, "e2", "Titel\nneu")
        assertEquals(
            listOf<SyncAction>(SyncAction.Download(changed, "Titel.md", null)),
            NotesSync.plan(emptyList(), idx, listOf(changed)),
        )
        // Server gelöscht, lokal unverändert: lokal löschen
        assertEquals(
            listOf<SyncAction>(SyncAction.DeleteLocal("Titel.md")),
            NotesSync.plan(listOf(LocalNote("Titel.md", base, false)), idx, emptyList()),
        )
        // Server gelöscht, lokal geändert: neu hochladen
        assertEquals(
            listOf(SyncAction.DropIndex("Titel.md"), SyncAction.Upload("Titel.md", null)),
            NotesSync.plan(listOf(LocalNote("Titel.md", "Titel\nlokal", false)), idx, emptyList()),
        )
        // beides gelöscht: nur Zuordnung vergessen
        assertEquals(listOf<SyncAction>(SyncAction.DropIndex("Titel.md")), NotesSync.plan(emptyList(), idx, emptyList()))
    }

    @Test
    fun syncDoesNotUploadReadonly() {
        val base = "T\nalt"
        val idx = listOf(entry("T.md", 1, "e1", base))
        val r = remote(1, "e1", base).copy(readonly = true)
        assertTrue(NotesSync.plan(listOf(LocalNote("T.md", "T\nneu", false)), idx, listOf(r)).isEmpty())
    }

    @Test
    fun apiParsesAndEncodes() {
        val json = """[{"id":7,"etag":"abc","readonly":false,"content":"Titel\nText \"x\"","title":"Titel","category":"A/../B","favorite":true,"modified":1700000000},{"x":1}]"""
        val notes = NotesApi.parseNotes(json)
        assertEquals(1, notes.size)
        assertEquals(RemoteNote(7, "abc", "Titel", "Titel\nText \"x\"", "A/B", true, 1700000000), notes[0])
        assertNull(NotesApi.parseNote("[]"))
        val body = NotesApi.encodeNote("a\n\"b\"", "K", true, 5)
        val back = NotesApi.parseNote(body.replace("{", "{\"id\":1,"))
        assertEquals("a\n\"b\"", back?.content)
        assertEquals("https://c.example/index.php/apps/notes/api/v1/notes/3", NotesApi.url("https://c.example", NotesApi.notePath(3)))
    }

    @Test
    fun stateRoundTrip() {
        val state = NotesState(setOf("a.md", "K/b \"q\".md"), listOf(IndexEntry("a.md", 3, "e", "h", true)))
        assertEquals(state, NotesStateCodec.decode(NotesStateCodec.encode(state)))
        assertEquals(NotesState(), NotesStateCodec.decode("kaputt"))
        assertEquals(emptySet<String>(), NotesStateCodec.decode("""{"v":1,"favorites":["../x.md"],"index":[]}""").favorites)
    }
}
