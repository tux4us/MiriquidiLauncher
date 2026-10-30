// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotesMarkdownTest {
    @Test
    fun blocks() {
        val md = "# Titel\n\nText **fett** und *kursiv* mit `code`\nzweite Zeile\n\n- Punkt\n  - Unter\n1. Eins\n- [ ] offen\n- [x] fertig\n> Zitat\n---\n```\nx = 1\n```"
        val blocks = NotesMarkdown.parse(md)
        assertEquals(MdBlock.Heading(1, listOf(MdSpan("Titel"))), blocks[0])
        val p = blocks[1] as MdBlock.Paragraph
        assertEquals(listOf(MdSpan("Text "), MdSpan("fett", bold = true), MdSpan(" und "), MdSpan("kursiv", italic = true), MdSpan(" mit "), MdSpan("code", code = true), MdSpan("\nzweite Zeile")), p.spans)
        assertEquals(MdBlock.Bullet(0, listOf(MdSpan("Punkt"))), blocks[2])
        assertEquals(MdBlock.Bullet(1, listOf(MdSpan("Unter"))), blocks[3])
        assertEquals(MdBlock.Numbered("1", listOf(MdSpan("Eins"))), blocks[4])
        assertEquals(MdBlock.Check(8, false, listOf(MdSpan("offen"))), blocks[5])
        assertEquals(MdBlock.Check(9, true, listOf(MdSpan("fertig"))), blocks[6])
        assertEquals(MdBlock.Quote(listOf(MdSpan("Zitat"))), blocks[7])
        assertEquals(MdBlock.Rule, blocks[8])
        assertEquals(MdBlock.Code("x = 1"), blocks[9])
    }

    @Test
    fun linksOnlyForSafeSchemes() {
        assertEquals(listOf(MdSpan("Seite", link = "https://a.example/x")), NotesMarkdown.parseInline("[Seite](https://a.example/x)"))
        val unsafe = NotesMarkdown.parseInline("[x](javascript:alert(1))")
        assertTrue(unsafe.all { it.link == null })
        assertEquals("[x](javascript:alert(1))", unsafe.joinToString("") { it.text })
        assertEquals("a * b * c", NotesMarkdown.parseInline("a * b * c").joinToString("") { it.text })
    }

    @Test
    fun toggleCheck() {
        val text = "Liste\n- [ ] eins\n- [x] zwei\nText"
        assertEquals("Liste\n- [x] eins\n- [x] zwei\nText", NotesMarkdown.toggleCheck(text, 1))
        assertEquals("Liste\n- [ ] eins\n- [ ] zwei\nText", NotesMarkdown.toggleCheck(text, 2))
        assertEquals(text, NotesMarkdown.toggleCheck(text, 3))
        assertEquals(text, NotesMarkdown.toggleCheck(text, 99))
    }
}
