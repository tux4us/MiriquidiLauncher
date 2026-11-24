// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.gallery.edit

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Wendet [EditParams] auf ein Bild an. Die Quelle wird nie verändert; das Ergebnis ist ein eigenes, veränderbares Bild. */
object ImageRenderer {
    /** Drehen (in 90°-Schritten) und Spiegeln. Ohne Änderung wird die Quelle selbst zurückgegeben. */
    fun geometry(src: Bitmap, quarterTurns: Int, flipH: Boolean, flipV: Boolean): Bitmap {
        val turns = ((quarterTurns % 4) + 4) % 4
        if (turns == 0 && !flipH && !flipV) return src
        val m = Matrix()
        if (flipH || flipV) m.postScale(if (flipH) -1f else 1f, if (flipV) -1f else 1f, src.width / 2f, src.height / 2f)
        if (turns != 0) m.postRotate(turns * 90f, src.width / 2f, src.height / 2f)
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    private fun cropped(src: Bitmap, crop: CropRect): Bitmap {
        if (crop.isFull) return src
        val left = (crop.left * src.width).roundToInt().coerceIn(0, src.width - 1)
        val top = (crop.top * src.height).roundToInt().coerceIn(0, src.height - 1)
        val right = (crop.right * src.width).roundToInt().coerceIn(left + 1, src.width)
        val bottom = (crop.bottom * src.height).roundToInt().coerceIn(top + 1, src.height)
        return Bitmap.createBitmap(src, left, top, right - left, bottom - top)
    }

    /** Gesamte Kette: Geometrie, Zuschnitt, Farbe, Schärfe, Zeichnung und Text. Das Ergebnis ist immer ein neues Bild. */
    fun render(src: Bitmap, p: EditParams): Bitmap {
        val geo = geometry(src, p.quarterTurns, p.flipH, p.flipV)
        val cut = cropped(geo, p.crop)
        val out = Bitmap.createBitmap(cut.width, cut.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        if (p.hasColor) paint.colorFilter = ColorMatrixColorFilter(ColorMatrix(ColorMath.matrix(p)))
        canvas.drawBitmap(cut, 0f, 0f, paint)
        if (cut !== src && cut !== geo) cut.recycle()
        if (geo !== src) geo.recycle()
        if (p.sharpness > 0.001f) sharpen(out, p.sharpness)
        if (p.annotations.isNotEmpty()) drawAnnotations(canvas, out.width, out.height, p.annotations)
        return out
    }

    private fun sharpen(bitmap: Bitmap, amount: Float) {
        Sharpen.applyRows(
            bitmap.width, bitmap.height, amount,
            read = { y, row -> bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1) },
            write = { y, row -> bitmap.setPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1) },
        )
    }

    /** Zeichnet Striche und Texte (Koordinaten 0…1 bezogen auf das Ergebnisbild); auch für die Vorschau auf einem Overlay. */
    fun drawAnnotations(canvas: Canvas, width: Int, height: Int, annotations: List<Annotation>) {
        val side = min(width, height).toFloat()
        for (a in annotations) {
            when (a) {
                is Annotation.Stroke -> drawStroke(canvas, width, height, side, a)
                is Annotation.Label -> drawLabel(canvas, width, height, a)
            }
        }
    }

    private fun drawStroke(canvas: Canvas, w: Int, h: Int, side: Float, s: Annotation.Stroke) {
        if (s.points.isEmpty()) return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = s.color
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = max(1f, s.widthFraction * side)
        }
        if (s.points.size == 1) {
            val pt = s.points[0]
            paint.style = Paint.Style.FILL
            canvas.drawCircle(pt.x * w, pt.y * h, paint.strokeWidth / 2f, paint)
            return
        }
        val path = Path()
        path.moveTo(s.points[0].x * w, s.points[0].y * h)
        for (i in 1 until s.points.size) path.lineTo(s.points[i].x * w, s.points[i].y * h)
        canvas.drawPath(path, paint)
    }

    private fun drawLabel(canvas: Canvas, w: Int, h: Int, l: Annotation.Label) {
        if (l.text.isBlank()) return
        val size = max(8f, l.sizeFraction * h)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = l.color
            textSize = size
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        // Dunkle bzw. helle Kontur, damit der Text auf jedem Untergrund lesbar bleibt.
        val outline = Paint(fill).apply {
            style = Paint.Style.STROKE
            strokeWidth = size / 8f
            strokeJoin = Paint.Join.ROUND
            color = if (luminance(l.color) > 0.5f) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        val lines = l.text.split('\n').take(6)
        val lineHeight = size * 1.15f
        val x = l.x * w
        var y = l.y * h - (lines.size - 1) * lineHeight / 2f - (fill.ascent() + fill.descent()) / 2f
        for (line in lines) {
            canvas.drawText(line, x, y, outline)
            canvas.drawText(line, x, y, fill)
            y += lineHeight
        }
    }

    private fun luminance(color: Int): Float {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return (0.299f * r + 0.587f * g + 0.114f * b) / 255f
    }
}
