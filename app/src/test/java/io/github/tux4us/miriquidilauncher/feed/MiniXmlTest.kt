// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MiniXmlTest {
    @Test
    fun parsesElementsAttributesAndText() {
        val root = MiniXml.parse("<?xml version=\"1.0\"?><a x=\"1\" y='2'><b>Hallo &amp; &#228;</b><c/></a>")
        assertEquals("a", root.name)
        assertEquals("1", root.attributes["x"])
        assertEquals("2", root.attributes["y"])
        assertEquals("Hallo & ä", root.child("b")?.text)
        assertNotNull(root.child("c"))
        assertEquals(2, root.children.size)
    }

    @Test
    fun cdataIsTakenLiterally() {
        val root = MiniXml.parse("<a><b><![CDATA[<p>1 &amp; 2</p>]]></b></a>")
        assertEquals("<p>1 &amp; 2</p>", root.child("b")?.text)
    }

    @Test
    fun commentsAndProcessingInstructionsAreSkipped() {
        val root = MiniXml.parse("<!-- x --><?xml-stylesheet href=\"a\"?><a><!-- <b>no</b> --><c>1</c></a>")
        assertEquals(1, root.children.size)
        assertEquals("1", root.child("c")?.text)
    }

    @Test
    fun doctypeIsIgnoredAndEntitiesAreNotExpanded() {
        val xml = "<?xml version=\"1.0\"?><!DOCTYPE a [<!ENTITY x \"AAAA\"><!ENTITY y \"&x;&x;\">]><a>&y;</a>"
        val root = MiniXml.parse(xml)
        assertEquals("&y;", root.text)
    }

    @Test
    fun prefixedNamesKeepPrefixAndLocalName() {
        val root = MiniXml.parse("<r xmlns:dc=\"x\"><dc:creator>Ann</dc:creator></r>")
        val node = root.child("dc:creator")
        assertEquals("Ann", node?.text)
        assertEquals("creator", node?.local)
        assertNull(root.child("creator"))
        assertEquals("Ann", root.childLocal("creator")?.text)
    }

    @Test
    fun innerXmlReturnsMarkup() {
        val root = MiniXml.parse("<e><content type=\"xhtml\"><div><p>Text <b>fett</b></p></div></content></e>")
        assertEquals("<div><p>Text <b>fett</b></p></div>", root.child("content")?.innerXml)
    }

    @Test
    fun toleratesBomUnclosedTagsAndStrayEndTags() {
        val root = MiniXml.parse("﻿<a><b>1</c><d>2</a>")
        // Das unpassende </c> wird ignoriert; b bleibt offen und enthält d, bis </a> beide schließt.
        assertEquals("1", root.child("b")?.text)
        assertEquals("2", root.child("b")?.child("d")?.text)
    }

    @Test
    fun unquotedAndEntityAttributes() {
        val root = MiniXml.parse("<a href=x.html t=\"a&amp;b\"/>")
        assertEquals("x.html", root.attributes["href"])
        assertEquals("a&b", root.attributes["t"])
    }

    @Test
    fun rejectsDocumentsWithoutRootAndTooDeepNesting() {
        assertTrue(runCatching { MiniXml.parse("nur Text") }.exceptionOrNull() is XmlException)
        val deep = "<a>".repeat(200)
        assertTrue(runCatching { MiniXml.parse(deep) }.exceptionOrNull() is XmlException)
    }
}
