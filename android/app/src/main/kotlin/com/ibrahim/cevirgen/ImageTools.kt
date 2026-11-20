package com.ibrahim.cevirgen

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import android.os.Build
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Fotoğraf dönüştürme, küçültme ve fotoğraflardan PDF.
 * Yeniden kodlama, konum gibi EXIF bilgilerini de temizler.
 */
object ImageTools {

    /** Fotoğrafı açar; maxSide > 0 ise uzun kenarı en fazla maxSide olacak şekilde küçültür. Yönü düzeltir. */
    fun decode(path: String, maxSide: Int): Bitmap {
        val file = File(path)
        if (Build.VERSION.SDK_INT >= 28) {
            val src = ImageDecoder.createSource(file)
            return ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val w = info.size.width
                val h = info.size.height
                if (maxSide > 0 && max(w, h) > maxSide) {
                    val s = maxSide.toDouble() / max(w, h)
                    decoder.setTargetSize(max(1, (w * s).roundToInt()), max(1, (h * s).roundToInt()))
                }
            }
        }
        // Android 8: HEIC desteklenmez; JPG/PNG/WEBP için BitmapFactory
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        if (maxSide > 0) while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val bmp = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IllegalStateException("Bu fotoğraf açılamadı: ${file.name}")
        if (maxSide > 0 && max(bmp.width, bmp.height) > maxSide) {
            val s = maxSide.toDouble() / max(bmp.width, bmp.height)
            return Bitmap.createScaledBitmap(bmp, (bmp.width * s).roundToInt(), (bmp.height * s).roundToInt(), true)
        }
        return bmp
    }

    /** format: jpg | png | webp, quality: 1-100 */
    fun convert(paths: List<String>, format: String, quality: Int, maxSide: Int, outDir: File, progress: (Double) -> Unit): List<File> {
        val out = mutableListOf<File>()
        paths.forEachIndexed { i, p ->
            var bmp = decode(p, maxSide)
            val (fmt, ext) = when (format) {
                "png" -> Bitmap.CompressFormat.PNG to "png"
                "webp" -> (if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP) to "webp"
                else -> Bitmap.CompressFormat.JPEG to "jpg"
            }
            if (fmt == Bitmap.CompressFormat.JPEG && bmp.hasAlpha()) bmp = flatten(bmp)
            val base = File(p).name.substringBeforeLast('.')
            var dest = File(outDir, "$base.$ext")
            var n = 2
            while (dest.exists()) { dest = File(outDir, "$base ($n).$ext"); n++ }
            dest.outputStream().use { bmp.compress(fmt, quality.coerceIn(1, 100), it) }
            bmp.recycle()
            out += dest
            progress((i + 1).toDouble() / paths.size)
        }
        return out
    }

    private fun flatten(src: Bitmap): Bitmap {
        val b = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Canvas(b).apply { drawColor(Color.WHITE); drawBitmap(src, 0f, 0f, null) }
        src.recycle()
        return b
    }

    /**
     * Fotoğrafları tek PDF yapar. pageSize: "a4" (A4'e sığdır) ya da "fit" (fotoğraf boyutunda sayfa).
     * Fotoğraflar PDF'e JPEG olarak gömülür (saydam olanlar kayıpsız); Android'in PdfDocument'i
     * resimleri sıkıştırmadan yazdığı için dosya fotoğraflardan büyük çıkıyordu.
     */
    fun toPdf(paths: List<String>, pageSize: String, out: File, progress: (Double) -> Unit): File {
        com.tom_roush.pdfbox.pdmodel.PDDocument().use { doc ->
            paths.forEachIndexed { i, p ->
                val bmp = decode(p, 2400) // A4'te ~290 dpi: baskı için yeterli
                val landscape = bmp.width > bmp.height
                val (pw, ph) = if (pageSize == "fit") {
                    val s = min(1.0, 1190.0 / max(bmp.width, bmp.height))
                    max(1, (bmp.width * s).roundToInt()).toFloat() to max(1, (bmp.height * s).roundToInt()).toFloat()
                } else if (landscape) 842f to 595f else 595f to 842f
                val page = com.tom_roush.pdfbox.pdmodel.PDPage(com.tom_roush.pdfbox.pdmodel.common.PDRectangle(pw, ph))
                doc.addPage(page)
                val margin = if (pageSize == "fit") 0f else 24f
                val s = min((pw - 2 * margin) / bmp.width, (ph - 2 * margin) / bmp.height)
                val dw = bmp.width * s
                val dh = bmp.height * s
                val img = if (bmp.hasAlpha()) {
                    com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory.createFromImage(doc, bmp)
                } else {
                    com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory.createFromImage(doc, bmp, 0.85f)
                }
                com.tom_roush.pdfbox.pdmodel.PDPageContentStream(doc, page).use { cs ->
                    cs.drawImage(img, (pw - dw) / 2, (ph - dh) / 2, dw, dh)
                }
                bmp.recycle()
                progress((i + 1).toDouble() / paths.size * 0.95)
            }
            doc.save(out)
        }
        progress(1.0)
        return out
    }
}
