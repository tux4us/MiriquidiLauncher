// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.files

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class FilesTest {
    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("files-test").toFile()
    }

    @After
    fun tearDown() {
        FileOps.deleteTree(root)
    }

    private fun write(path: String, text: String = "x"): File {
        val f = File(root, path)
        f.parentFile.mkdirs()
        f.writeText(text)
        return f
    }

    @Test
    fun names() {
        assertEquals(NameCheck.OK, FilePaths.checkName("a.txt"))
        assertEquals(NameCheck.EMPTY, FilePaths.checkName("  "))
        assertEquals(NameCheck.RESERVED, FilePaths.checkName(".."))
        assertEquals(NameCheck.INVALID_CHARS, FilePaths.checkName("a/b"))
        assertEquals(NameCheck.TOO_LONG, FilePaths.checkName("a".repeat(256)))
        assertEquals("b" to ".tar.gz", FilePaths.splitExtension("b.tar.gz"))
        assertEquals(".bashrc" to "", FilePaths.splitExtension(".bashrc"))
    }

    @Test
    fun uniqueNames() {
        write("a.txt")
        assertEquals("a (1).txt", FilePaths.uniqueName(root, "a.txt"))
        write("a (1).txt")
        assertEquals("a (2).txt", FilePaths.uniqueName(root, "a.txt"))
        assertEquals("neu.txt", FilePaths.uniqueName(root, "neu.txt"))
    }

    @Test
    fun pathsAndBreadcrumbs() {
        val crumbs = FilePaths.breadcrumbs("/storage/emulated/0/DCIM/Camera", "/storage/emulated/0", "Intern")
        assertEquals(listOf("Intern", "DCIM", "Camera"), crumbs.map { it.name })
        assertEquals("/storage/emulated/0/DCIM", crumbs[1].path)
        assertEquals("/storage/emulated/0/DCIM", FilePaths.parentWithin("/storage/emulated/0/DCIM/Camera", "/storage/emulated/0"))
        assertNull(FilePaths.parentWithin("/storage/emulated/0", "/storage/emulated/0"))
        assertEquals("/storage/ABCD-1234", FilePaths.rootOf("/storage/ABCD-1234/x", listOf("/storage/emulated/0", "/storage/ABCD-1234")))
        assertTrue(FilePaths.isInside(root, File(root, "a/../b")))
        assertFalse(FilePaths.isInside(File(root, "a"), File(root, "ab")))
    }

    @Test
    fun naturalSort() {
        val names = listOf("b10", "B2", "a", "b1").sortedWith(NaturalOrder)
        assertEquals(listOf("a", "b1", "B2", "b10"), names)
        val entries = listOf(
            FileEntry("/x/z", "z", true, 0, 0, false, FileKind.FOLDER),
            FileEntry("/x/a.txt", "a.txt", false, 10, 5, false, FileKind.TEXT),
            FileEntry("/x/b.txt", "b.txt", false, 5, 9, false, FileKind.TEXT),
        )
        assertEquals(listOf("z", "a.txt", "b.txt"), FileSorter.sort(entries, SortOrder()).map { it.name })
        assertEquals(listOf("z", "a.txt", "b.txt"), FileSorter.sort(entries, SortOrder(SortField.SIZE, false)).map { it.name })
        assertEquals(listOf("z", "b.txt", "a.txt"), FileSorter.sort(entries, SortOrder(SortField.DATE, false)).map { it.name })
    }

    @Test
    fun types() {
        assertEquals(FileKind.IMAGE, FileTypes.kindOf("A.JPG", false))
        assertEquals(FileKind.FOLDER, FileTypes.kindOf("x.jpg", true))
        assertEquals(FileKind.ARCHIVE, FileTypes.kindOf("a.zip", false))
        assertEquals("application/pdf", FileTypes.mimeOf("a.pdf"))
        assertEquals("application/octet-stream", FileTypes.mimeOf("a.unknown"))
        assertEquals("", FileTypes.extension(".nomedia"))
    }

    @Test
    fun copyKeepsSourceAndNeverOverwrites() {
        write("src/a.txt", "hello")
        write("src/sub/b.txt", "world")
        val dest = File(root, "dest").also { it.mkdir() }
        write("dest/src/old.txt", "old")
        var last: FileProgress? = null
        val result = FileOps.copy(listOf(File(root, "src")), dest, { last = it })
        assertTrue(result.ok)
        assertEquals("hello", File(dest, "src (1)/a.txt").readText())
        assertEquals("world", File(dest, "src (1)/sub/b.txt").readText())
        assertEquals("old", File(dest, "src/old.txt").readText())
        assertTrue(File(root, "src/a.txt").exists())
        assertEquals(10L, last!!.totalBytes)
    }

    @Test
    fun copyIntoItselfFails() {
        write("src/a.txt")
        val result = FileOps.copy(listOf(File(root, "src")), File(root, "src/sub").also { it.mkdirs() })
        assertEquals(listOf("src"), result.failed)
        assertFalse(File(root, "src/sub/src").exists())
    }

    @Test
    fun cancelRemovesPartialCopy() {
        write("big.bin", "x".repeat(200_000))
        val dest = File(root, "d").also { it.mkdir() }
        var calls = 0
        try {
            FileOps.copy(listOf(File(root, "big.bin")), dest, cancelled = { ++calls > 3 })
            fail()
        } catch (expected: OperationCancelled) {
        }
        assertEquals(0, dest.listFiles()!!.size)
    }

    @Test
    fun moveAndRenameAndDelete() {
        write("a/x.txt")
        val dest = File(root, "b").also { it.mkdir() }
        assertTrue(FileOps.move(listOf(File(root, "a")), dest).ok)
        assertTrue(File(dest, "a/x.txt").exists())
        assertFalse(File(root, "a").exists())
        assertFalse(FileOps.move(listOf(dest), File(dest, "a")).ok)
        val renamed = FileOps.rename(File(dest, "a"), "c")
        assertEquals("c", renamed.name)
        write("d.txt")
        try {
            FileOps.rename(File(root, "d.txt"), "b")
            fail()
        } catch (expected: IOException) {
        }
        assertTrue(FileOps.delete(listOf(dest)).ok)
        assertFalse(dest.exists())
    }

    @Test
    fun symlinksAreNotFollowed() {
        write("real/f.txt")
        val link = File(root, "link")
        try {
            Files.createSymbolicLink(link.toPath(), File(root, "real").toPath())
        } catch (e: Exception) {
            return
        }
        val dest = File(root, "d").also { it.mkdir() }
        val result = FileOps.copy(listOf(link), dest)
        assertEquals(1, result.skipped)
        assertTrue(FileOps.deleteTree(link))
        assertTrue(File(root, "real/f.txt").exists())
    }

    @Test
    fun searchAndSize() {
        write("a/Report.txt")
        write("a/b/report2.txt")
        write("a/.hidden/report3.txt")
        assertEquals(2, FileOps.search(root, "REPORT", includeHidden = false, limit = 10).size)
        assertEquals(3, FileOps.search(root, "report", includeHidden = true, limit = 10).size)
        assertEquals(1, FileOps.search(root, "report", includeHidden = true, limit = 1).size)
        val info = FileOps.treeSize(File(root, "a"))
        assertEquals(3, info.files)
        assertEquals(3L, info.bytes)
        assertEquals(2, info.folders)
    }

    @Test
    fun zipRoundTripAndSafety() {
        write("p/a.txt", "A")
        write("p/q/b.txt", "B")
        val zip = File(root, "p.zip")
        assertEquals(2, FileZip.create(listOf(File(root, "p")), zip))
        val out = File(root, "out").also { it.mkdir() }
        val result = FileZip.extract(zip, out)
        assertEquals("p", result.folder.name)
        assertEquals(2, result.files)
        assertEquals("B", File(out, "p/p/q/b.txt").readText())
        assertEquals("p (1)", FileZip.extract(zip, out).folder.name)

        val evil = File(root, "evil.zip")
        ZipOutputStream(evil.outputStream()).use { z ->
            for (name in listOf("../escape.txt", "/abs.txt", "ok/fine.txt", "a\\..\\..\\b.txt")) {
                z.putNextEntry(ZipEntry(name)); z.write(1); z.closeEntry()
            }
        }
        val r = FileZip.extract(evil, out)
        assertEquals(1, r.files)
        assertEquals(3, r.skipped)
        assertFalse(File(root, "escape.txt").exists())
        assertTrue(File(r.folder, "ok/fine.txt").exists())
        assertNull(FileZip.safeEntryPath("a/../../x"))
        assertEquals("a/b", FileZip.safeEntryPath("./a//b"))
    }

    @Test
    fun zipLimits() {
        write("big.txt", "x".repeat(1000))
        val zip = File(root, "big.zip")
        FileZip.create(listOf(File(root, "big.txt")), zip)
        val out = File(root, "o").also { it.mkdir() }
        try {
            FileZip.extract(zip, out, UnzipLimits(maxEntries = 10, maxTotalBytes = 100))
            fail()
        } catch (expected: ZipLimitException) {
        }
        assertEquals(0, out.listFiles()!!.size)
        try {
            FileZip.extract(File(root, "big.txt"), out)
            fail()
        } catch (expected: IOException) {
        }
    }

    @Test
    fun trashRestoreAndExpire() {
        val file = write("docs/a.txt", "A")
        val roots = listOf(root)
        val now = 1_000_000_000_000L
        val result = FileTrash.trash(listOf(file), roots, now)
        assertTrue(result.ok)
        assertFalse(file.exists())
        val items = FileTrash.list(roots)
        assertEquals(1, items.size)
        assertEquals(file.absolutePath, items[0].originalPath)
        assertTrue(FileTrash.isInTrash(items[0].item, roots))
        write("docs/a.txt", "new")
        val restored = FileTrash.restore(items[0])
        assertEquals("a (1).txt", restored.name)
        assertEquals("A", restored.readText())
        assertTrue(FileTrash.list(roots).isEmpty())

        FileTrash.trash(listOf(restored), roots, now)
        assertEquals(0, FileTrash.purgeExpired(roots, now + 10L * 24 * 3600 * 1000))
        assertEquals(1, FileTrash.purgeExpired(roots, now + 31L * 24 * 3600 * 1000))
        assertTrue(FileTrash.list(roots).isEmpty())
        val outside = Files.createTempDirectory("other").toFile()
        val stray = File(outside, "s.txt").also { it.writeText("s") }
        assertEquals(listOf("s.txt"), FileTrash.trash(listOf(stray), roots, now).failed)
        FileOps.deleteTree(outside)
        assertNotNull(FileTrash.trashDir(root))
    }
}
