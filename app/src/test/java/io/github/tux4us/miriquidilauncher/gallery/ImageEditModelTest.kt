// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.gallery

import io.github.tux4us.miriquidilauncher.gallery.edit.Annotation
import io.github.tux4us.miriquidilauncher.gallery.edit.ColorMath
import io.github.tux4us.miriquidilauncher.gallery.edit.CropHandle
import io.github.tux4us.miriquidilauncher.gallery.edit.CropMath
import io.github.tux4us.miriquidilauncher.gallery.edit.CropRect
import io.github.tux4us.miriquidilauncher.gallery.edit.EditNaming
import io.github.tux4us.miriquidilauncher.gallery.edit.EditParams
import io.github.tux4us.miriquidilauncher.gallery.edit.FitMath
import io.github.tux4us.miriquidilauncher.gallery.edit.ImageFilter
import io.github.tux4us.miriquidilauncher.gallery.edit.NormPoint
import io.github.tux4us.miriquidilauncher.gallery.edit.Sharpen
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageEditModelTest {
    private val eps = 0.0001f

    @Test
    fun identityParamsAreIdentity() {
        assertTrue(EditParams().isIdentity)
        assertFalse(EditParams(quarterTurns = 1).isIdentity)
        assertFalse(EditParams(brightness = 0.2f).isIdentity)
        assertFalse(EditParams(annotations = listOf(Annotation.Label("x", 0.5f, 0.5f, 0.1f, 0))).isIdentity)
        assertFalse(EditParams(crop = CropRect(0.1f, 0f, 1f, 1f)).isIdentity)
        assertArrayEquals(ColorMath.identity(), ColorMath.matrix(EditParams()), 0f)
    }

    @Test
    fun outputSizeFollowsRotationAndCrop() {
        assertEquals(4000 to 3000, EditParams().outputSize(4000, 3000))
        assertEquals(3000 to 4000, EditParams(quarterTurns = 1).outputSize(4000, 3000))
        assertEquals(2000 to 1500, EditParams(crop = CropRect(0f, 0f, 0.5f, 0.5f)).outputSize(4000, 3000))
        assertEquals(1500 to 4000, EditParams(quarterTurns = 3, crop = CropRect(0f, 0f, 0.5f, 1f)).outputSize(4000, 3000))
    }

    @Test
    fun brightnessAndContrastMoveValues() {
        val up = ColorMath.matrix(EditParams(brightness = 0.5f))
        assertEquals(Triple(164, 164, 164), ColorMath.apply(up, 100, 100, 100))
        val low = ColorMath.matrix(EditParams(contrast = -1f))
        val (r, _, _) = ColorMath.apply(low, 0, 0, 0)
        assertEquals(102, r) // 0 * 0,2 + 128 * (1 - 0,2)
        val high = ColorMath.matrix(EditParams(contrast = 1f))
        assertEquals(Triple(128, 128, 128), ColorMath.apply(high, 128, 128, 128))
        assertEquals(Triple(255, 255, 255), ColorMath.apply(high, 200, 200, 200))
    }

    @Test
    fun monoFilterGivesEqualChannels() {
        val m = ColorMath.matrix(EditParams(filter = ImageFilter.MONO))
        val (r, g, b) = ColorMath.apply(m, 200, 40, 90)
        assertEquals(r, g)
        assertEquals(g, b)
        val full = ColorMath.matrix(EditParams(saturation = -1f))
        val (r2, g2, b2) = ColorMath.apply(full, 200, 40, 90)
        assertEquals(r2, g2)
        assertEquals(g2, b2)
    }

    @Test
    fun saturationOfGrayStaysGray() {
        val m = ColorMath.matrix(EditParams(saturation = 1f))
        assertEquals(Triple(100, 100, 100), ColorMath.apply(m, 100, 100, 100))
    }

    @Test
    fun sepiaAndWarmFilters() {
        val sepia = ColorMath.apply(ColorMath.filter(ImageFilter.SEPIA), 100, 100, 100)
        assertTrue(sepia.first > sepia.second && sepia.second > sepia.third)
        val warm = ColorMath.apply(ColorMath.filter(ImageFilter.WARM), 100, 100, 100)
        assertTrue(warm.first > warm.third)
        val cool = ColorMath.apply(ColorMath.filter(ImageFilter.COOL), 100, 100, 100)
        assertTrue(cool.third > cool.first)
    }

    @Test
    fun concatAppliesFirstThenSecond() {
        val a = ColorMath.brightness(0.5f) // +64
        val b = ColorMath.contrast(1f) // x2 um 128
        // erst heller, dann Kontrast: (100+64-128)*2+128 = 200
        assertEquals(Triple(200, 200, 200), ColorMath.apply(ColorMath.concat(a, b), 100, 100, 100))
        // erst Kontrast, dann heller: (100-128)*2+128+64 = 136
        assertEquals(Triple(136, 136, 136), ColorMath.apply(ColorMath.concat(b, a), 100, 100, 100))
    }

    @Test
    fun sharpenKeepsFlatAreasAndBoostsEdges() {
        val w = 5
        val h = 5
        val gray = 0xFF808080.toInt()
        val flat = IntArray(w * h) { gray }
        Sharpen.applyRows(w, h, 1f, { y, row -> System.arraycopy(flat, y * w, row, 0, w) }, { y, row -> System.arraycopy(row, 0, flat, y * w, w) })
        assertTrue(flat.all { it == gray })

        // Senkrechte Kante: links dunkel, rechts hell.
        val img = IntArray(w * h) { i -> if (i % w < 2) 0xFF404040.toInt() else 0xFFC0C0C0.toInt() }
        val copy = img.copyOf()
        Sharpen.applyRows(w, h, 1f, { y, row -> System.arraycopy(copy, y * w, row, 0, w) }, { y, row -> System.arraycopy(row, 0, copy, y * w, w) })
        val dark = (copy[2 * w + 1] shr 16) and 0xFF
        val light = (copy[2 * w + 2] shr 16) and 0xFF
        assertTrue(dark < 0x40)
        assertTrue(light > 0xC0)
        // Alpha bleibt, Rand bleibt.
        assertEquals(0xFF, (copy[2 * w + 1] shr 24) and 0xFF)
        assertEquals(img[0], copy[0])
        assertEquals(img[4 * w + 4], copy[4 * w + 4])
    }

    @Test
    fun sharpenReadsOriginalRowsNotAlreadySharpened() {
        val w = 4
        val h = 4
        val img = IntArray(w * h) { i -> if (i / w == 1) 0xFFFFFFFF.toInt() else 0xFF000000.toInt() }
        val reference = img.copyOf()
        Sharpen.applyRows(w, h, 1f, { y, row -> System.arraycopy(img, y * w, row, 0, w) }, { y, row -> System.arraycopy(row, 0, img, y * w, w) })
        // Zeile 2 hängt nur von den Originalzeilen 1 und 3 ab (nicht von der schon geschärften Zeile 1).
        val expectedRow2 = 0 // dunkel, Nachbar oben hell: (0 * (1+4a) - a*255) < 0 -> 0
        assertEquals(expectedRow2, (img[2 * w + 1] shr 16) and 0xFF)
        assertEquals(reference[0], img[0])
    }

    @Test
    fun centeredCropForAspect() {
        val square = CropMath.centered(1f, 4000, 3000)
        assertEquals(0.125f, square.left, eps)
        assertEquals(0.875f, square.right, eps)
        assertEquals(0f, square.top, eps)
        val wide = CropMath.centered(16f / 9f, 4000, 3000)
        assertEquals(0f, wide.left, eps)
        assertEquals(1f, wide.right, eps)
        assertEquals(0.5f * (1f - (4f / 3f) / (16f / 9f)), wide.top, eps)
    }

    @Test
    fun hitFindsHandles() {
        val r = CropRect(0.2f, 0.2f, 0.8f, 0.8f)
        assertEquals(CropHandle.TOP_LEFT, CropMath.hit(r, 0.21f, 0.19f, 0.05f))
        assertEquals(CropHandle.BOTTOM_RIGHT, CropMath.hit(r, 0.8f, 0.8f, 0.05f))
        assertEquals(CropHandle.LEFT, CropMath.hit(r, 0.2f, 0.5f, 0.05f))
        assertEquals(CropHandle.TOP, CropMath.hit(r, 0.5f, 0.22f, 0.05f))
        assertEquals(CropHandle.MOVE, CropMath.hit(r, 0.5f, 0.5f, 0.05f))
        assertEquals(CropHandle.NONE, CropMath.hit(r, 0.05f, 0.05f, 0.05f))
    }

    @Test
    fun freeDragKeepsMinimumSizeAndBounds() {
        val r = CropRect(0.2f, 0.2f, 0.8f, 0.8f)
        val moved = CropMath.drag(r, CropHandle.MOVE, 0.5f, -0.5f, null, 100, 100)
        assertEquals(0.4f, moved.left, eps)
        assertEquals(1f, moved.right, eps)
        assertEquals(0f, moved.top, eps)
        assertEquals(0.6f, moved.height, eps)

        val shrunk = CropMath.drag(r, CropHandle.RIGHT, -0.9f, 0f, null, 100, 100)
        assertEquals(CropMath.MIN_SIZE, shrunk.width, eps)
        assertEquals(0.2f, shrunk.left, eps)

        val grown = CropMath.drag(r, CropHandle.TOP_LEFT, -0.5f, -0.5f, null, 100, 100)
        assertEquals(0f, grown.left, eps)
        assertEquals(0f, grown.top, eps)
    }

    @Test
    fun lockedAspectKeepsRatioAndStaysInside() {
        // Bild 4000 x 3000, 1:1 gesperrt: Breite = k * Höhe mit k = 1 * 3000 / 4000 = 0,75
        val start = CropMath.centered(1f, 4000, 3000)
        val dragged = CropMath.drag(start, CropHandle.BOTTOM_RIGHT, -0.1f, -0.1f, 1f, 4000, 3000)
        val pxW = dragged.width * 4000
        val pxH = dragged.height * 3000
        assertEquals(pxW, pxH, 1f)
        assertTrue(dragged.left >= 0f && dragged.top >= 0f && dragged.right <= 1f && dragged.bottom <= 1f)
        assertEquals(start.left, dragged.left, eps)
        assertEquals(start.top, dragged.top, eps)

        val big = CropMath.drag(start, CropHandle.TOP_LEFT, -1f, -1f, 1f, 4000, 3000)
        assertEquals(big.width * 4000, big.height * 3000, 1f)
        assertTrue(big.left >= 0f && big.top >= 0f)
        assertEquals(start.right, big.right, eps)
        assertEquals(start.bottom, big.bottom, eps)
    }

    @Test
    fun fitCentersImage() {
        val f = FitMath.fit(1000f, 500f, 400, 400)
        assertEquals(500f, f.width, 0.01f)
        assertEquals(500f, f.height, 0.01f)
        assertEquals(250f, f.left, 0.01f)
        assertEquals(0f, f.top, 0.01f)
        assertEquals(0f, FitMath.fit(0f, 10f, 5, 5).width, 0f)
    }

    @Test
    fun copyNames() {
        assertEquals("IMG_1_bearbeitet.jpg", EditNaming.copyName("IMG_1.jpg", png = false))
        assertEquals("IMG_1_bearbeitet.jpg", EditNaming.copyName("IMG_1.heic", png = false))
        assertEquals("shot_bearbeitet.png", EditNaming.copyName("shot.PNG", png = true))
        assertEquals("a_bearbeitet.jpg", EditNaming.copyName("a_bearbeitet.jpg", png = false))
        assertEquals("Bild_bearbeitet.jpg", EditNaming.copyName("", png = false))
        assertTrue(EditNaming.isPng("image/png"))
        assertFalse(EditNaming.isPng("image/jpeg"))
    }

    @Test
    fun annotationsAreValueObjects() {
        val s = Annotation.Stroke(0xFFFF0000.toInt(), 0.01f, listOf(NormPoint(0f, 0f), NormPoint(1f, 1f)))
        assertEquals(s, s.copy())
    }
}
