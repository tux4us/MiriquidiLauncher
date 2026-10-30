// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppGroupCodecTest {
    private fun group(id: String, name: String, vararg keys: String) = AppGroup(id, name, keys.toList())

    @Test
    fun roundTripKeepsOrderAndContent() {
        val groups = listOf(group("1", "Messenger", "a/.A#0", "b/.B#0"), group("2", "Finanzen"))
        assertEquals(groups, AppGroupCodec.decode(AppGroupCodec.encode(groups)))
    }

    @Test
    fun emptyAndDamagedInputGivesEmptyOrSkipsEntries() {
        assertEquals(emptyList<AppGroup>(), AppGroupCodec.decode(null))
        assertEquals(emptyList<AppGroup>(), AppGroupCodec.decode(""))
        assertEquals(emptyList<AppGroup>(), AppGroupCodec.decode("nur Text"))
        val raw = "\u001E1\u001F\u001F\u001E2\u001FGut\u001Fx\u001E\u001FOhneId\u001Fy"
        assertEquals(listOf(group("2", "Gut", "x")), AppGroupCodec.decode(raw))
    }

    @Test
    fun controlCharactersAreRemovedFromNamesAndKeys() {
        val groups = listOf(group("1", "A\u001EB\u001FC", "k\u001D1"))
        val decoded = AppGroupCodec.decode(AppGroupCodec.encode(groups))
        assertEquals(listOf(group("1", "ABC", "k1")), decoded)
    }

    @Test
    fun decodeDropsDuplicateIdsAndKeysAndLimits() {
        val raw = "\u001E1\u001FA\u001Fk\u001Dk\u001E1\u001FB\u001F"
        assertEquals(listOf(group("1", "A", "k")), AppGroupCodec.decode(raw))
        val many = (1..50).map { group("g$it", "G$it") }
        assertEquals(AppGroupCodec.MAX_GROUPS, AppGroupCodec.decode(AppGroupCodec.encode(many)).size)
    }

    @Test
    fun nameChecks() {
        val groups = listOf(group("1", "Messenger"))
        assertEquals(GroupNameCheck.Blank, AppGroupCodec.checkName(groups, "   "))
        assertEquals(GroupNameCheck.Taken, AppGroupCodec.checkName(groups, " messenger "))
        assertEquals(GroupNameCheck.Ok("Messenger"), AppGroupCodec.checkName(groups, "Messenger", exceptId = "1"))
        assertEquals(GroupNameCheck.Ok("Banking"), AppGroupCodec.checkName(groups, "  Banking "))
        val long = "x".repeat(100)
        assertEquals(AppGroupCodec.MAX_NAME_LENGTH, (AppGroupCodec.checkName(groups, long) as GroupNameCheck.Ok).name.length)
        val full = (1..AppGroupCodec.MAX_GROUPS).map { group("g$it", "G$it") }
        assertEquals(GroupNameCheck.LimitReached, AppGroupCodec.checkName(full, "Neu"))
        // Umbenennen bleibt auch bei voller Liste möglich
        assertTrue(AppGroupCodec.checkName(full, "Anders", exceptId = "g1") is GroupNameCheck.Ok)
    }

    @Test
    fun addRenameDelete() {
        var groups = AppGroupCodec.add(emptyList(), "1", " Messenger ")
        assertEquals(listOf(group("1", "Messenger")), groups)
        assertEquals(groups, AppGroupCodec.add(groups, "2", "messenger"))
        groups = AppGroupCodec.add(groups, "2", "Finanzen")
        groups = AppGroupCodec.rename(groups, "2", "Banking")
        assertEquals(listOf("Messenger", "Banking"), groups.map { it.name })
        assertEquals(groups, AppGroupCodec.rename(groups, "2", "Messenger"))
        assertEquals(listOf("Messenger"), AppGroupCodec.delete(groups, "2").map { it.name })
    }

    @Test
    fun toggleAndMoveApps() {
        var groups = listOf(group("1", "A"), group("2", "B"))
        groups = AppGroupCodec.toggleApp(groups, "1", "x")
        groups = AppGroupCodec.toggleApp(groups, "1", "y")
        groups = AppGroupCodec.toggleApp(groups, "1", "z")
        assertEquals(listOf("x", "y", "z"), groups[0].appKeys)
        assertEquals(emptyList<String>(), groups[1].appKeys)
        groups = AppGroupCodec.moveApp(groups, "1", "z", -2)
        assertEquals(listOf("z", "x", "y"), groups[0].appKeys)
        groups = AppGroupCodec.toggleApp(groups, "1", "x")
        assertEquals(listOf("z", "y"), groups[0].appKeys)
        // dieselbe App darf in mehreren Gruppen stehen
        groups = AppGroupCodec.toggleApp(groups, "2", "z")
        assertEquals(listOf("z"), groups[1].appKeys)
    }

    @Test
    fun appCountPerGroupIsLimited() {
        var groups = listOf(group("1", "A"))
        for (i in 1..AppGroupCodec.MAX_APPS_PER_GROUP + 5) groups = AppGroupCodec.toggleApp(groups, "1", "k$i")
        assertEquals(AppGroupCodec.MAX_APPS_PER_GROUP, groups[0].appKeys.size)
    }

    @Test
    fun moveGroupAndSanitize() {
        val groups = listOf(group("1", "A"), group("2", "B"), group("3", "C"))
        assertEquals(listOf("2", "3", "1"), AppGroupCodec.moveGroup(groups, "1", 2).map { it.id })
        assertEquals(listOf("1", "2", "3"), AppGroupCodec.moveGroup(groups, "1", -1).map { it.id })
        val dirty = listOf(group("1", " A "), group("1", "Doppelt"), group("", "OhneId"), group("2", "  "))
        assertEquals(listOf(group("1", "A")), AppGroupCodec.sanitize(dirty))
    }
}
