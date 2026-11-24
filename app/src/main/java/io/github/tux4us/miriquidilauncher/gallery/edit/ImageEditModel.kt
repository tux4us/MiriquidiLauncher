// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.gallery.edit

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Farbfilter der Bildbearbeitung. */
enum class ImageFilter { NONE, MONO, SEPIA, WARM, COOL, VINTAGE, DRAMA }

/** Punkt in Anteilen der Bildbreite und -höhe (0 bis 1). */
data class NormPoint(val x: Float, val y: Float)

/** Zuschnitt als Anteile der Bildfläche; Standard ist das ganze Bild. */
data class CropRect(val left: Float = 0f, val top: Float = 0f, val right: Float = 1f, val bottom: Float = 1f) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val isFull: Boolean get() = left <= EPS && top <= EPS && right >= 1f - EPS && bottom >= 1f - EPS

    private companion object {
        const val EPS = 0.0005f
    }
}

/** Eine Zeichnung der Anmerkungen: Freihandlinie oder Text. Maße sind Anteile der kleineren Bildseite (Linie) bzw. der Bildhöhe (Text). */
sealed interface Annotation {
    data class Stroke(val color: Int, val widthFraction: Float, val points: List<NormPoint>) : Annotation
    data class Label(val text: String, val x: Float, val y: Float, val sizeFraction: Float, val color: Int) : Annotation
}

/**
 * Alle Einstellungen der Bildbearbeitung. Reihenfolge der Anwendung: Drehen und Spiegeln, Zuschnitt, Farben, Schärfe,
 * Anmerkungen. Der Zuschnitt bezieht sich auf das bereits gedrehte und gespiegelte Bild; Anmerkungen auf den Zuschnitt.
 */
data class EditParams(
    /** Viertelumdrehungen im Uhrzeigersinn (0 bis 3). */
    val quarterTurns: Int = 0,
    val flipH: Boolean = false,
    val flipV: Boolean = false,
    val crop: CropRect = CropRect(),
    /** Helligkeit, Kontrast, Sättigung von -1 bis 1. */
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    /** Schärfe von 0 bis 1. */
    val sharpness: Float = 0f,
    val filter: ImageFilter = ImageFilter.NONE,
    val annotations: List<Annotation> = emptyList(),
) {
    val hasGeometry: Boolean get() = quarterTurns % 4 != 0 || flipH || flipV
    val hasColor: Boolean get() = abs(brightness) > 0.001f || abs(contrast) > 0.001f || abs(saturation) > 0.001f || filter != ImageFilter.NONE
    val isIdentity: Boolean get() = !hasGeometry && crop.isFull && !hasColor && sharpness <= 0.001f && annotations.isEmpty()

    /** Maße des Ergebnisses für ein Ausgangsbild der Größe [width] × [height]. */
    fun outputSize(width: Int, height: Int): Pair<Int, Int> {
        val swap = quarterTurns % 2 != 0
        val w = if (swap) height else width
        val h = if (swap) width else height
        return max(1, Math.round(w * crop.width)) to max(1, Math.round(h * crop.height))
    }
}

/** Farbmatrizen (4 × 5, zeilenweise, Werte 0 bis 255 wie bei `android.graphics.ColorMatrix`). */
object ColorMath {
    private const val LR = 0.2126f
    private const val LG = 0.7152f
    private const val LB = 0.0722f

    fun identity(): FloatArray = floatArrayOf(
        1f, 0f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f, 0f,
        0f, 0f, 1f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )

    /** Matrix, die erst [first] und dann [second] anwendet. */
    fun concat(first: FloatArray, second: FloatArray): FloatArray {
        val out = FloatArray(20)
        for (row in 0 until 4) {
            for (col in 0 until 5) {
                var sum = 0f
                for (k in 0 until 4) sum += second[row * 5 + k] * first[k * 5 + col]
                if (col == 4) sum += second[row * 5 + 4]
                out[row * 5 + col] = sum
            }
        }
        return out
    }

    fun brightness(amount: Float): FloatArray = identity().also {
        val t = amount.coerceIn(-1f, 1f) * 128f
        it[4] = t
        it[9] = t
        it[14] = t
    }

    /** Kontrast um den Mittelwert 128; bei -1 bleibt noch ein Fünftel des Kontrasts. */
    fun contrast(amount: Float): FloatArray {
        val c = amount.coerceIn(-1f, 1f)
        val scale = if (c >= 0f) 1f + c else 1f + c * 0.8f
        val shift = 128f * (1f - scale)
        return floatArrayOf(
            scale, 0f, 0f, 0f, shift,
            0f, scale, 0f, 0f, shift,
            0f, 0f, scale, 0f, shift,
            0f, 0f, 0f, 1f, 0f,
        )
    }

    /** [sat] 1 = unverändert, 0 = Graustufen, 2 = doppelte Sättigung. */
    fun saturationFactor(sat: Float): FloatArray {
        val inv = 1f - sat
        return floatArrayOf(
            LR * inv + sat, LG * inv, LB * inv, 0f, 0f,
            LR * inv, LG * inv + sat, LB * inv, 0f, 0f,
            LR * inv, LG * inv, LB * inv + sat, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )
    }

    private fun scaleChannels(r: Float, g: Float, b: Float): FloatArray = floatArrayOf(
        r, 0f, 0f, 0f, 0f,
        0f, g, 0f, 0f, 0f,
        0f, 0f, b, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )

    private val SEPIA = floatArrayOf(
        0.393f, 0.769f, 0.189f, 0f, 0f,
        0.349f, 0.686f, 0.168f, 0f, 0f,
        0.272f, 0.534f, 0.131f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )

    private fun blend(a: FloatArray, b: FloatArray, t: Float): FloatArray = FloatArray(20) { a[it] * (1f - t) + b[it] * t }

    fun filter(filter: ImageFilter): FloatArray = when (filter) {
        ImageFilter.NONE -> identity()
        ImageFilter.MONO -> saturationFactor(0f)
        ImageFilter.SEPIA -> SEPIA.copyOf()
        ImageFilter.WARM -> scaleChannels(1.10f, 1.0f, 0.88f)
        ImageFilter.COOL -> scaleChannels(0.90f, 1.0f, 1.12f)
        ImageFilter.VINTAGE -> concat(blend(identity(), SEPIA, 0.45f), concat(contrast(-0.15f), brightness(0.06f)))
        ImageFilter.DRAMA -> concat(saturationFactor(0.75f), contrast(0.35f))
    }

    /** Gesamtmatrix aus Helligkeit, Kontrast, Sättigung und Filter (in dieser Reihenfolge). */
    fun matrix(p: EditParams): FloatArray {
        var m = identity()
        if (abs(p.brightness) > 0.001f) m = concat(m, brightness(p.brightness))
        if (abs(p.contrast) > 0.001f) m = concat(m, contrast(p.contrast))
        if (abs(p.saturation) > 0.001f) m = concat(m, saturationFactor(1f + p.saturation.coerceIn(-1f, 1f)))
        if (p.filter != ImageFilter.NONE) m = concat(m, filter(p.filter))
        return m
    }

    /** Wendet [m] auf einen Farbwert an (Werte 0 bis 255, begrenzt). */
    fun apply(m: FloatArray, r: Int, g: Int, b: Int): Triple<Int, Int, Int> {
        fun channel(row: Int): Int {
            val v = m[row * 5] * r + m[row * 5 + 1] * g + m[row * 5 + 2] * b + m[row * 5 + 4]
            return Math.round(v).coerceIn(0, 255)
        }
        return Triple(channel(0), channel(1), channel(2))
    }
}

/** Schärfen mit einem 3 × 3-Kern (Unscharfmaske), zeilenweise, damit nur wenige Zeilen im Speicher liegen. */
object Sharpen {
    /**
     * Schärft ein Bild der Größe [width] × [height] an Ort und Stelle. [read] füllt die Zeile `y` (ARGB), [write] schreibt
     * sie zurück. Der Rand bleibt unverändert; der Alphakanal wird nie verändert.
     */
    fun applyRows(width: Int, height: Int, amount: Float, read: (Int, IntArray) -> Unit, write: (Int, IntArray) -> Unit) {
        val a = amount.coerceIn(0f, 1f) * 1.2f
        if (a <= 0f || width < 3 || height < 3) return
        var prev = IntArray(width).also { read(0, it) }
        var cur = IntArray(width).also { read(1, it) }
        var next = IntArray(width)
        val out = IntArray(width)
        for (y in 1 until height - 1) {
            read(y + 1, next)
            out[0] = cur[0]
            out[width - 1] = cur[width - 1]
            for (x in 1 until width - 1) {
                val c = cur[x]
                out[x] = (c and ALPHA) or
                    (channel(c, prev[x], next[x], cur[x - 1], cur[x + 1], 16, a) shl 16) or
                    (channel(c, prev[x], next[x], cur[x - 1], cur[x + 1], 8, a) shl 8) or
                    channel(c, prev[x], next[x], cur[x - 1], cur[x + 1], 0, a)
            }
            write(y, out)
            val recycled = prev
            prev = cur
            cur = next
            next = recycled
        }
    }

    private const val ALPHA = 0xFF shl 24

    private fun channel(c: Int, up: Int, down: Int, left: Int, right: Int, shift: Int, a: Float): Int {
        val center = (c shr shift) and 0xFF
        val sum = ((up shr shift) and 0xFF) + ((down shr shift) and 0xFF) + ((left shr shift) and 0xFF) + ((right shr shift) and 0xFF)
        val v = center * (1f + 4f * a) - a * sum
        return Math.round(v).coerceIn(0, 255)
    }
}

/** Griffe des Zuschnitts. */
enum class CropHandle { NONE, MOVE, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT, LEFT, TOP, RIGHT, BOTTOM }

/** Rechenhilfen für die Zuschnittsfläche; alle Koordinaten sind Anteile (0 bis 1) der Bildfläche. */
object CropMath {
    const val MIN_SIZE = 0.08f

    /**
     * Größtes zentriertes Rechteck mit dem Seitenverhältnis [aspect] (Breite : Höhe in Pixeln) in einem Bild der Größe
     * [imgW] × [imgH].
     */
    fun centered(aspect: Float, imgW: Int, imgH: Int): CropRect {
        val imageAspect = imgW.toFloat() / imgH
        return if (aspect >= imageAspect) {
            val h = imageAspect / aspect
            CropRect(0f, (1f - h) / 2f, 1f, (1f + h) / 2f)
        } else {
            val w = aspect / imageAspect
            CropRect((1f - w) / 2f, 0f, (1f + w) / 2f, 1f)
        }
    }

    /** Welcher Griff liegt bei ([x], [y]) in einem Abstand bis [slop] (in Anteilen) von den Ecken und Kanten? */
    fun hit(r: CropRect, x: Float, y: Float, slop: Float): CropHandle {
        val nearL = abs(x - r.left) <= slop
        val nearR = abs(x - r.right) <= slop
        val nearT = abs(y - r.top) <= slop
        val nearB = abs(y - r.bottom) <= slop
        val insideX = x >= r.left - slop && x <= r.right + slop
        val insideY = y >= r.top - slop && y <= r.bottom + slop
        return when {
            nearL && nearT -> CropHandle.TOP_LEFT
            nearR && nearT -> CropHandle.TOP_RIGHT
            nearL && nearB -> CropHandle.BOTTOM_LEFT
            nearR && nearB -> CropHandle.BOTTOM_RIGHT
            nearL && insideY -> CropHandle.LEFT
            nearR && insideY -> CropHandle.RIGHT
            nearT && insideX -> CropHandle.TOP
            nearB && insideX -> CropHandle.BOTTOM
            x > r.left && x < r.right && y > r.top && y < r.bottom -> CropHandle.MOVE
            else -> CropHandle.NONE
        }
    }

    /**
     * Zieht den Griff [handle] um ([dx], [dy]) (Anteile). Mit [aspect] (Breite : Höhe in Pixeln, `null` = frei) bleibt das
     * Seitenverhältnis erhalten; dann zählt bei Kanten nur die Richtung der Kante.
     */
    fun drag(r: CropRect, handle: CropHandle, dx: Float, dy: Float, aspect: Float?, imgW: Int, imgH: Int): CropRect {
        if (handle == CropHandle.NONE) return r
        if (handle == CropHandle.MOVE) {
            val w = r.width
            val h = r.height
            val l = (r.left + dx).coerceIn(0f, 1f - w)
            val t = (r.top + dy).coerceIn(0f, 1f - h)
            return CropRect(l, t, l + w, t + h)
        }
        if (aspect == null) return dragFree(r, handle, dx, dy)
        // Breite = k × Höhe in Anteilen.
        val k = aspect * imgH / imgW
        return when (handle) {
            CropHandle.TOP_LEFT -> locked(r.right, r.bottom, -1, -1, max(r.right - (r.left + dx), (r.bottom - (r.top + dy)) * k), k)
            CropHandle.TOP_RIGHT -> locked(r.left, r.bottom, 1, -1, max((r.right + dx) - r.left, (r.bottom - (r.top + dy)) * k), k)
            CropHandle.BOTTOM_LEFT -> locked(r.right, r.top, -1, 1, max(r.right - (r.left + dx), ((r.bottom + dy) - r.top) * k), k)
            CropHandle.BOTTOM_RIGHT -> locked(r.left, r.top, 1, 1, max((r.right + dx) - r.left, ((r.bottom + dy) - r.top) * k), k)
            CropHandle.LEFT -> locked(r.right, r.top, -1, 1, r.right - (r.left + dx), k)
            CropHandle.RIGHT -> locked(r.left, r.top, 1, 1, (r.right + dx) - r.left, k)
            CropHandle.TOP -> locked(r.left, r.bottom, 1, -1, (r.bottom - (r.top + dy)) * k, k)
            CropHandle.BOTTOM -> locked(r.left, r.top, 1, 1, ((r.bottom + dy) - r.top) * k, k)
            else -> r
        }
    }

    /** Rechteck mit der festen Ecke ([ax], [ay]), das in Richtung ([sx], [sy]) wächst, Breite [wanted], Breite = [k] × Höhe. */
    private fun locked(ax: Float, ay: Float, sx: Int, sy: Int, wanted: Float, k: Float): CropRect {
        val roomX = if (sx < 0) ax else 1f - ax
        val roomY = if (sy < 0) ay else 1f - ay
        val maxW = min(roomX, roomY * k)
        val minW = min(MIN_SIZE, maxW)
        val w = wanted.coerceIn(minW, maxW)
        val h = w / k
        val x2 = ax + sx * w
        val y2 = ay + sy * h
        return CropRect(min(ax, x2), min(ay, y2), max(ax, x2), max(ay, y2))
    }

    private fun dragFree(r: CropRect, handle: CropHandle, dx: Float, dy: Float): CropRect {
        var l = r.left
        var t = r.top
        var rt = r.right
        var b = r.bottom
        when (handle) {
            CropHandle.TOP_LEFT -> { l += dx; t += dy }
            CropHandle.TOP_RIGHT -> { rt += dx; t += dy }
            CropHandle.BOTTOM_LEFT -> { l += dx; b += dy }
            CropHandle.BOTTOM_RIGHT -> { rt += dx; b += dy }
            CropHandle.LEFT -> l += dx
            CropHandle.RIGHT -> rt += dx
            CropHandle.TOP -> t += dy
            CropHandle.BOTTOM -> b += dy
            else -> Unit
        }
        l = l.coerceIn(0f, r.right - MIN_SIZE)
        rt = rt.coerceIn(r.left + MIN_SIZE, 1f)
        t = t.coerceIn(0f, r.bottom - MIN_SIZE)
        b = b.coerceIn(r.top + MIN_SIZE, 1f)
        // Eine Kante darf die gegenüberliegende nicht unterschreiten, auch wenn beide gezogen werden.
        if (rt - l < MIN_SIZE) { if (handle == CropHandle.LEFT || handle == CropHandle.TOP_LEFT || handle == CropHandle.BOTTOM_LEFT) l = rt - MIN_SIZE else rt = l + MIN_SIZE }
        if (b - t < MIN_SIZE) { if (handle == CropHandle.TOP || handle == CropHandle.TOP_LEFT || handle == CropHandle.TOP_RIGHT) t = b - MIN_SIZE else b = t + MIN_SIZE }
        return CropRect(l, t, rt, b)
    }
}

/** Lage des angezeigten Bildes in einem Behälter. */
data class FitRect(val left: Float, val top: Float, val width: Float, val height: Float)

object FitMath {
    /** Bild der Größe [imgW] × [imgH] in einen Behälter [boxW] × [boxH] einpassen (zentriert, ohne Verzerrung). */
    fun fit(boxW: Float, boxH: Float, imgW: Int, imgH: Int): FitRect {
        if (imgW <= 0 || imgH <= 0 || boxW <= 0f || boxH <= 0f) return FitRect(0f, 0f, 0f, 0f)
        val scale = min(boxW / imgW, boxH / imgH)
        val w = imgW * scale
        val h = imgH * scale
        return FitRect((boxW - w) / 2f, (boxH - h) / 2f, w, h)
    }
}

/** Dateinamen und Format der gespeicherten Kopie. */
object EditNaming {
    private const val SUFFIX = "_bearbeitet"

    /** PNG bleibt PNG (Transparenz), alles andere wird JPEG. */
    fun isPng(mime: String): Boolean = mime.equals("image/png", ignoreCase = true)

    /** Name der Kopie: `IMG_1.jpg` → `IMG_1_bearbeitet.jpg`; die Endung folgt dem Ausgabeformat. */
    fun copyName(originalName: String, png: Boolean): String {
        val dot = originalName.lastIndexOf('.')
        val stem = (if (dot > 0) originalName.substring(0, dot) else originalName).ifBlank { "Bild" }
        val base = if (stem.endsWith(SUFFIX)) stem else stem + SUFFIX
        return base + if (png) ".png" else ".jpg"
    }
}
