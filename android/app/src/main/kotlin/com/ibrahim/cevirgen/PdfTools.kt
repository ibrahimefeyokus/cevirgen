package com.ibrahim.cevirgen

import android.graphics.Bitmap
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import java.io.File
import java.util.IdentityHashMap
import kotlin.math.max
import kotlin.math.roundToInt

/** Tamamen çevrimdışı PDF işlemleri (PDFBox-Android). */
object PdfTools {

    fun open(path: String): PDDocument {
        val doc = try {
            PDDocument.load(File(path), MemoryUsageSetting.setupTempFileOnly())
        } catch (e: InvalidPasswordException) {
            throw IllegalStateException("Bu PDF şifreli. Şifreli PDF'ler henüz desteklenmiyor.")
        }
        // Yalnızca sahip şifresi olan (açılabilen) PDF'lerde kaydetmeye izin ver.
        if (doc.isEncrypted) doc.isAllSecurityToBeRemoved = true
        return doc
    }

    fun pageCount(path: String): Int = open(path).use { it.numberOfPages }

    fun merge(paths: List<String>, out: File, progress: (Double) -> Unit): File {
        val util = PDFMergerUtility()
        paths.forEach { util.addSource(File(it)) }
        util.destinationFileName = out.absolutePath
        progress(0.3)
        util.mergeDocuments(MemoryUsageSetting.setupTempFileOnly())
        progress(1.0)
        return out
    }

    /** Verilen sıradaki sayfalardan (0 tabanlı) yeni bir PDF oluşturur: seçme, sıralama, silme. */
    fun extract(path: String, pages: List<Int>, out: File): File {
        open(path).use { src ->
            require(pages.isNotEmpty()) { "Sayfa seçilmedi." }
            pages.forEach { require(it in 0 until src.numberOfPages) { "PDF'te ${it + 1}. sayfa yok." } }
            PDDocument().use { dst ->
                pages.forEach { dst.importPage(src.getPage(it)) }
                dst.save(out)
            }
        }
        return out
    }

    /** Her sayfayı ayrı bir PDF yapar. */
    fun splitEach(path: String, outDir: File, baseName: String, progress: (Double) -> Unit): List<File> {
        val result = mutableListOf<File>()
        open(path).use { src ->
            val n = src.numberOfPages
            for (i in 0 until n) {
                val f = File(outDir, "$baseName - sayfa ${i + 1}.pdf")
                PDDocument().use { dst ->
                    dst.importPage(src.getPage(i))
                    dst.save(f)
                }
                result += f
                progress((i + 1).toDouble() / n)
            }
        }
        return result
    }

    /**
     * Gömülü resimleri küçültüp JPEG olarak yeniden sıkıştırır.
     * level: 0 = hafif, 1 = dengeli, 2 = güçlü.
     */
    fun compress(path: String, out: File, level: Int, progress: (Double) -> Unit): File {
        val (maxSide, quality) = when (level) {
            0 -> 2200 to 0.82f
            2 -> 1100 to 0.5f
            else -> 1600 to 0.68f
        }
        open(path).use { doc ->
            val done = IdentityHashMap<COSBase, PDImageXObject>()
            val n = doc.numberOfPages
            for (i in 0 until n) {
                doc.getPage(i).resources?.let { shrink(doc, it, maxSide, quality, done, 0) }
                progress((i + 1).toDouble() / n * 0.9)
            }
            doc.save(out)
        }
        progress(1.0)
        // Sıkıştırma dosyayı büyüttüyse orijinali koru.
        val original = File(path)
        if (out.length() >= original.length()) original.copyTo(out, overwrite = true)
        return out
    }

    private fun shrink(
        doc: PDDocument, res: PDResources, maxSide: Int, quality: Float,
        done: IdentityHashMap<COSBase, PDImageXObject>, depth: Int,
    ) {
        if (depth > 5) return
        for (name in res.xObjectNames) {
            val xo = try { res.getXObject(name) } catch (_: Exception) { null } ?: continue
            when (xo) {
                is PDFormXObject -> xo.resources?.let { shrink(doc, it, maxSide, quality, done, depth + 1) }
                is PDImageXObject -> {
                    val key = xo.cosObject
                    done[key]?.let { res.put(name, it); continue }
                    // Küçük resimleri, maskeli/şeffaf resimleri ve 1 bitlik taramaları olduğu gibi bırak.
                    if (xo.width * xo.height < 250_000 || xo.bitsPerComponent == 1) continue
                    if (xo.cosObject.getDictionaryObject(com.tom_roush.pdfbox.cos.COSName.SMASK) != null) continue
                    val bmp: Bitmap = try { xo.image } catch (_: Exception) { null } ?: continue
                    if (bmp.hasAlpha()) { bmp.recycle(); continue }
                    val scale = maxSide.toDouble() / max(bmp.width, bmp.height)
                    val scaled = if (scale < 1.0) {
                        Bitmap.createScaledBitmap(bmp, (bmp.width * scale).roundToInt(), (bmp.height * scale).roundToInt(), true)
                    } else bmp
                    val replacement = JPEGFactory.createFromImage(doc, scaled, quality)
                    if (scaled !== bmp) scaled.recycle()
                    bmp.recycle()
                    done[key] = replacement
                    res.put(name, replacement)
                }
            }
        }
    }
}
