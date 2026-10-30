// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.system

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageLogicTest {
    private fun app(label: String, cache: Long, data: Long = cache, apk: Long = 100) =
        AppStorage("pkg.$label", label, apk, data, cache)

    @Test fun topByCacheSortsFiltersAndLimits() {
        val apps = listOf(app("b", 50), app("a", 50), app("c", 500), app("d", 0), app("e", 10))
        val top = AppStorageLogic.topByCache(apps, 3)
        assertEquals(listOf("c", "a", "b"), top.map { it.label })
        assertEquals(610L, AppStorageLogic.totalCache(apps))
        assertEquals(0, AppStorageLogic.topByCache(emptyList(), 5).size)
    }

    @Test fun appTotal() {
        assertEquals(300L, app("x", 10, data = 200, apk = 100).totalBytes)
    }

    private fun tempTree(): File {
        val root = Files.createTempDirectory("scan").toFile()
        File(root, "a/b").mkdirs()
        File(root, "c").mkdirs()
        File(root, "top.bin").writeBytes(ByteArray(10))
        File(root, "a/one.bin").writeBytes(ByteArray(100))
        File(root, "a/b/two.bin").writeBytes(ByteArray(1000))
        File(root, "c/three.bin").writeBytes(ByteArray(500))
        return root
    }

    @Test fun scanSumsAndSorts() {
        val root = tempTree()
        try {
            val node = DirectoryScanner.scan(root)!!
            assertEquals(1610L, node.totalBytes)
            assertEquals(10L, node.ownBytes)
            assertEquals(4L, node.totalFiles)
            assertEquals(listOf("a", "c"), node.children.map { it.name })
            val a = node.children[0]
            assertEquals(1100L, a.totalBytes)
            assertEquals(100L, a.ownBytes)
            assertEquals(1000L, a.children.single().totalBytes)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun cancelledScanReturnsNull() {
        val root = tempTree()
        try {
            assertNull(DirectoryScanner.scan(root, isCancelled = { true }))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun nonDirectoryReturnsNull() {
        val file = Files.createTempFile("x", ".bin").toFile()
        try {
            assertNull(DirectoryScanner.scan(file))
        } finally {
            file.delete()
        }
    }

    @Test fun maxDirsFoldsIntoParentButKeepsTotal() {
        val root = tempTree()
        try {
            val node = DirectoryScanner.scan(root, maxDirs = 2)!!
            assertEquals(1610L, node.totalBytes)
            assertTrue(node.children.size < 2 || node.children.sumOf { it.children.size } < 2)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun symlinksAreNotFollowed() {
        val root = tempTree()
        try {
            val link = File(root, "link")
            val created = runCatching { Files.createSymbolicLink(link.toPath(), File(root, "a").toPath()) }.isSuccess
            val node = DirectoryScanner.scan(root)!!
            assertEquals(1610L, node.totalBytes)
            assertNotNull(node)
            if (created) assertEquals(2, node.children.size)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun progressIsReported() {
        val root = tempTree()
        try {
            var last = -1L
            DirectoryScanner.scan(root, onProgress = { _, files -> last = files })
            assertEquals(4L, last)
        } finally {
            root.deleteRecursively()
        }
    }
}
