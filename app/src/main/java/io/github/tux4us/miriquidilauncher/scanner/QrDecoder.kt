// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.scanner

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.Result
import com.google.zxing.ResultMetadataType
import com.google.zxing.common.HybridBinarizer

/** Ergebnis eines gelesenen QR-Codes. [bytes] sind die rohen Daten, falls der Code Bytes statt Text enthielt. */
class ScanResult(val text: String, val bytes: ByteArray?)

/**
 * Liest QR-Codes mit ZXing (Apache-2.0, ohne Google-Dienste, vollständig offline). Eine Instanz ist nicht
 * threadsicher und gehört einem einzigen Analyse-Thread.
 */
class QrDecoder {
    private val reader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                DecodeHintType.TRY_HARDER to true,
            ),
        )
    }

    /** Ein Kamerabild aus der Y-Ebene (Helligkeit). [rowStride] ist die Zeilenlänge im Puffer. */
    fun decodeLuminance(data: ByteArray, rowStride: Int, width: Int, height: Int): ScanResult? =
        decode(PlanarYUVLuminanceSource(data, rowStride, height, 0, 0, width, height, false))

    /** Ein Bild aus einer Datei; versucht auch invertierte Codes (hell auf dunkel). */
    fun decodeBitmap(bitmap: Bitmap): ScanResult? {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
        return decode(source) ?: decode(source.invert())
    }

    private fun decode(source: LuminanceSource): ScanResult? =
        try {
            toResult(reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))))
        } catch (ignored: ReaderException) {
            null
        } finally {
            reader.reset()
        }

    @Suppress("UNCHECKED_CAST")
    private fun toResult(result: Result): ScanResult {
        val segments = result.resultMetadata?.get(ResultMetadataType.BYTE_SEGMENTS) as? List<ByteArray>
        val bytes = segments?.takeIf { it.isNotEmpty() }?.let { list ->
            val out = java.io.ByteArrayOutputStream()
            list.forEach { out.write(it) }
            out.toByteArray()
        }
        return ScanResult(result.text.orEmpty(), bytes)
    }
}
