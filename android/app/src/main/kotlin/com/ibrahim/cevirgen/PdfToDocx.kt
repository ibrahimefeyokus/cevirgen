package com.ibrahim.cevirgen

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PointF
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tom_roush.pdfbox.contentstream.PDFGraphicsStreamEngine
import com.tom_roush.pdfbox.contentstream.operator.color.SetNonStrokingColor
import com.tom_roush.pdfbox.contentstream.operator.color.SetNonStrokingColorN
import com.tom_roush.pdfbox.contentstream.operator.color.SetNonStrokingColorSpace
import com.tom_roush.pdfbox.contentstream.operator.color.SetNonStrokingDeviceCMYKColor
import com.tom_roush.pdfbox.contentstream.operator.color.SetNonStrokingDeviceGrayColor
import com.tom_roush.pdfbox.contentstream.operator.color.SetNonStrokingDeviceRGBColor
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImage
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.StringWriter
import java.util.IdentityHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * PDF → Word (.docx), görünümü olabildiğince koruyarak:
 * satırlar paragraflara birleştirilir; ortalama/iki yana yaslama, girinti, kalın/italik,
 * yazı tipi, boyut, renk, üst/alt simge, çerçeveli kutular, yatay çizgiler ve resimler aktarılır.
 * Yazısı olmayan (taranmış) sayfalar telefondaki yazı tanımayla okunur.
 */
object PdfToDocx {

    class Result(val file: File, val pages: Int, val ocrPages: Int)

    // ------------------------------------------------------------------ veri yapıları

    private class Style(val bold: Boolean, val italic: Boolean, val family: String?)

    private class Glyph(
        val text: String, val x0: Float, val x1: Float, val base: Float, val size: Float,
        val style: Style, val color: Int,
        val img: Img? = null,      // satır içi resim (formül vb.)
        val drop: Float = 0f,      // resmin taban çizgisinin altına sarkan kısmı (pt)
    )

    private class Line(val page: Int, val glyphs: List<Glyph>, val pageW: Float, val pageH: Float) {
        val x0 = glyphs.minOf { it.x0 }
        val x1 = glyphs.filter { it.text.isNotBlank() }.maxOfOrNull { it.x1 } ?: glyphs.maxOf { it.x1 }
        val size: Float = run {
            // en çok karakterin kullandığı boyut (üst/alt simgeler hariç)
            val m = HashMap<Int, Int>()
            for (g in glyphs) if (g.text.isNotBlank()) m.merge((g.size * 2).roundToInt(), 1, Int::plus)
            (m.maxByOrNull { it.value }?.key ?: 22) / 2f
        }
        val base: Float = glyphs.filter { it.text.isNotBlank() && it.img == null && abs(it.size - size) < 0.6f }.map { it.base }
            .ifEmpty { glyphs.map { it.base } }.sorted().let { it[it.size / 2] }
        // satır içi resim (formül) satırı yukarı/aşağı genişletir
        val top: Float = min(base - size * 0.85f, glyphs.filter { it.img != null }.minOfOrNull { it.img!!.t } ?: Float.MAX_VALUE)
        val bottom: Float = max(base + size * 0.25f, glyphs.filter { it.img != null }.maxOfOrNull { it.img!!.t + it.img.h } ?: -Float.MAX_VALUE)
        val text get() = glyphs.joinToString("") { it.text }.trim()
        val bold get() = glyphs.count { it.style.bold && it.text.isNotBlank() } * 2 > glyphs.count { it.text.isNotBlank() }
        var box: Box? = null
        var centered = false
        var col = 0 // 0 = tam genişlik, 1 = sol sütun, 2 = sağ sütun
    }

    /** İki sütunlu sayfada sütunların sınırları. */
    private class Cols(val leftL: Float, val leftR: Float, val rightL: Float, val rightR: Float)

    private class Box(val l: Float, val t: Float, val r: Float, val b: Float, val stroke: Boolean, val fill: Int?) {
        var contentL = Float.MAX_VALUE
        var contentR = 0f
        fun contains(x0: Float, y0: Float, x1: Float, y1: Float) =
            x0 >= l - 2 && x1 <= r + 2 && y0 >= t - 3 && y1 <= b + 3
    }

    private class HLine(val l: Float, val r: Float, val y: Float, val color: Int)

    private class Img(val page: Int, val l: Float, val t: Float, val w: Float, val h: Float, val bytes: ByteArray, val png: Boolean)

    private class Para(val page: Int) {
        val lines = mutableListOf<Line>()
        var align = "left"
        var bottomRule: HLine? = null
        val top get() = lines.first().top
    }

    private class PageGfx(
        val boxes: MutableList<Box>, val rules: MutableList<HLine>, val images: List<Img>,
        val vlines: List<FloatArray> = emptyList(), // x, top, bottom
    )

    private class Cell(val lines: List<Line>, val fill: Int?, val align: String)
    private class Row(val cells: List<Cell>, val header: Boolean)
    private class Table(
        val page: Int, val top: Float, val l: Float, val r: Float, val cols: List<Float>, val rows: List<Row>,
        val hBorders: Boolean, val vBorders: Boolean, val borderColor: Int,
    )

    // ------------------------------------------------------------------ metin toplama

    private class Collector : PDFTextStripper() {
        val lines = mutableListOf<Line>()
        var pageIndex = 0
        var pageW = 595f
        var pageH = 842f
        private val cur = mutableListOf<Glyph>()
        private val colors = IdentityHashMap<TextPosition, Int>()
        private val styles = IdentityHashMap<PDFont, Style>()

        init {
            // konuma göre sırala: bazı PDF'ler soru numarasını/şık harfini metinden sonra yazar ("32A)")
            sortByPosition = true
            addOperator(SetNonStrokingColorSpace())
            addOperator(SetNonStrokingColor())
            addOperator(SetNonStrokingColorN())
            addOperator(SetNonStrokingDeviceRGBColor())
            addOperator(SetNonStrokingDeviceGrayColor())
            addOperator(SetNonStrokingDeviceCMYKColor())
        }

        override fun processTextPosition(text: TextPosition) {
            // Eğik yazılar (çapraz filigran, damga) satır oluşturmaya hiç katılmaz; aksi hâlde
            // dev filigran harfleri aynı yükseklikteki satırları kelime ortasından böler
            if (slanted(text)) return
            colors[text] = try { graphicsState.nonStrokingColor.toRGB() } catch (_: Exception) { 0 }
            super.processTextPosition(text)
        }

        private fun styleOf(f: PDFont?): Style {
            if (f == null) return Style(false, false, null)
            return styles.getOrPut(f) {
                val d = f.fontDescriptor
                // Type3 yazı tiplerinde (Chrome çıktıları) ad, yazı tipi tanımlayıcısında durur
                val raw = (f.name?.takeIf { it.isNotBlank() } ?: d?.fontName ?: "").substringAfter('+')
                val n = raw.lowercase()
                val bold = Regex("bold|black|heavy|semibold|demi").containsMatchIn(n) ||
                    (d?.isForceBold == true) || ((d?.fontWeight ?: 0f) >= 600f)
                val italic = Regex("italic|oblique").containsMatchIn(n) || (d?.isItalic == true) ||
                    abs(d?.italicAngle ?: 0f) > 0.5f
                val fam = d?.fontFamily?.takeIf { it.isNotBlank() }?.let { familyOf(it) } ?: familyOf(raw)
                Style(bold, italic, fam)
            }
        }

        override fun writeString(text: String, positions: MutableList<TextPosition>) {
            for (p in positions) {
                // Eğik yazılar (çapraz filigran, damga) metin akışına alınmaz
                if (slanted(p)) continue
                // Karşılığı olmayan küçük simge (ör. Chrome'un liste noktası) yer tutucu olarak tutulur
                val u = p.unicode?.takeIf { it.isNotEmpty() }
                    // PDF'te görünen her şey çizilmiştir: "yumuşak tire" aslında görünen tiredir; bölünmez boşluk Word'de satır kaydırmayı bozar
                    ?.replace('\u00AD', '-')?.replace('\u00A0', ' ')
                    ?: if (p.widthDirAdj in 0.5f..(p.yScale * 0.7f)) "\u0000" else continue
                // gerçek punto: yazı boyutu × metin matrisi × sayfa dönüşümü (yScale); fontSizeInPt sayfa ölçeğini atlar
                val size = p.yScale.takeIf { it in 1f..200f } ?: p.fontSizeInPt.takeIf { it in 1f..200f }
                    ?: (p.heightDir / 0.7f).coerceIn(4f, 100f)
                cur += Glyph(u, p.xDirAdj, p.xDirAdj + p.widthDirAdj, p.yDirAdj, size, styleOf(p.font), colors[p] ?: 0)
            }
        }

        private fun slanted(p: TextPosition): Boolean {
            val m = p.textMatrix ?: return false
            val a = m.getValue(0, 0); val b = m.getValue(0, 1)
            if (abs(a) + abs(b) < 1e-3f) return false
            val angle = Math.toDegrees(atan2(b.toDouble(), a.toDouble()))
            var d = (angle - p.dir) % 360.0
            if (d < 0) d += 360.0
            return min(d, 360.0 - d) > 4.0
        }

        override fun writeWordSeparator() {
            val last = cur.lastOrNull() ?: return
            if (last.text != " ") cur += Glyph(" ", last.x1, last.x1, last.base, last.size, last.style, last.color)
        }

        private fun endLine() {
            // yer tutucular: satır başındaysa madde işareti (•), değilse atılır
            if (cur.any { it.text == "\u0000" }) {
                val real = cur.count { it.text.isNotBlank() && it.text != "\u0000" }
                val firstSolid = cur.indexOfFirst { it.text.isNotBlank() }
                val fixed = cur.mapIndexedNotNull { i, g ->
                    when {
                        g.text != "\u0000" -> g
                        real >= 3 && i == firstSolid -> Glyph("•", g.x0, g.x1, g.base, g.size, g.style, g.color)
                        else -> null
                    }
                }
                cur.clear(); cur += fixed
                if (fixed.firstOrNull { it.text.isNotBlank() }?.text == "•" && cur.getOrNull(1)?.text?.isBlank() == false) {
                    cur.add(1, Glyph(" ", cur[0].x1, cur[0].x1, cur[0].base, cur[0].size, cur[0].style, cur[0].color))
                }
            }
            while (cur.isNotEmpty() && cur.last().text.isBlank()) cur.removeAt(cur.size - 1)
            if (cur.any { it.text.isNotBlank() }) for (part in splitByBaseline(cur)) lines += Line(pageIndex, part, pageW, pageH)
            cur.clear()
        }

        /**
         * Birbirine çok yakın iki satır (ör. tablo satırları, büyük satır yüksekliği olan yazı tipleri)
         * tek satır gibi gelebilir; taban çizgileri belirgin farklı olan yazıları ayrı satırlara böler.
         * Üst/alt simgeler (küçük kayma) aynı satırda kalır.
         */
        private fun splitByBaseline(gs: List<Glyph>): List<List<Glyph>> {
            val solid = gs.filter { it.text.isNotBlank() }
            if (solid.size < 2) return listOf(gs.toList())
            val bases = solid.map { it.base }.distinct().sorted()
            if (bases.last() - bases.first() < solid.maxOf { it.size } * 0.7f) return listOf(gs.toList())
            // taban çizgisi kümeleri
            val groups = mutableListOf(mutableListOf(bases.first()))
            for (b in bases.drop(1)) {
                val g = groups.last()
                val sz = solid.filter { it.base == b || it.base == g.last() }.maxOf { it.size }
                if (b - g.last() < sz * 0.7f) g += b else groups += mutableListOf(b)
            }
            if (groups.size < 2) return listOf(gs.toList())
            fun groupOf(base: Float) = groups.indexOfFirst { base >= it.first() - 0.01f && base <= it.last() + 0.01f }.coerceAtLeast(0)
            val parts = List(groups.size) { mutableListOf<Glyph>() }
            var lastGroup = 0
            for (g in gs) {
                val k = if (g.text.isBlank()) lastGroup else groupOf(g.base)
                parts[k] += g
                lastGroup = k
            }
            return parts.map { p -> p.dropWhile { it.text.isBlank() }.dropLastWhile { it.text.isBlank() } }
                .filter { p -> p.any { it.text.isNotBlank() } }
        }

        override fun writeLineSeparator() = endLine()
        override fun writeParagraphStart() = endLine()
        override fun writeParagraphEnd() = endLine()
        override fun writePageEnd() = endLine()
    }

    // ------------------------------------------------------------------ çizim (kutu, çizgi, resim)

    private class Gfx(page: PDPage, private val pageNo: Int) : PDFGraphicsStreamEngine(page) {
        val boxes = mutableListOf<Box>()
        val rules = mutableListOf<HLine>()
        val images = mutableListOf<Img>()
        val vlines = mutableListOf<FloatArray>()
        val dots = mutableListOf<FloatArray>() // madde işareti olarak çizilmiş küçük daire/kare: cx, cy(üstten), çap
        private var pMinX = Float.MAX_VALUE
        private var pMinY = Float.MAX_VALUE
        private var pMaxX = -Float.MAX_VALUE
        private var pMaxY = -Float.MAX_VALUE
        private var hasCurve = false
        private fun grow(x: Float, y: Float) { pMinX = min(pMinX, x); pMaxX = max(pMaxX, x); pMinY = min(pMinY, y); pMaxY = max(pMaxY, y) }
        private val crop = page.cropBox
        private val ph = crop.height
        private val rects = mutableListOf<FloatArray>() // pdf koordinatı: minX, minY, maxX, maxY
        private val segs = mutableListOf<FloatArray>()
        private val cur = PointF()

        override fun appendRectangle(p0: PointF, p1: PointF, p2: PointF, p3: PointF) {
            val xs = floatArrayOf(p0.x, p1.x, p2.x, p3.x)
            val ys = floatArrayOf(p0.y, p1.y, p2.y, p3.y)
            rects += floatArrayOf(xs.min(), ys.min(), xs.max(), ys.max())
            grow(xs.min(), ys.min()); grow(xs.max(), ys.max())
            cur.set(p0.x, p0.y)
        }

        override fun moveTo(x: Float, y: Float) { cur.set(x, y); grow(x, y) }
        override fun lineTo(x: Float, y: Float) { segs += floatArrayOf(cur.x, cur.y, x, y); cur.set(x, y); grow(x, y) }
        override fun curveTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
            hasCurve = true; grow(x1, y1); grow(x2, y2); grow(x3, y3); cur.set(x3, y3)
        }
        override fun getCurrentPoint(): PointF = cur
        override fun closePath() {}
        override fun endPath() = clear()
        override fun clip(windingRule: android.graphics.Path.FillType) {}
        override fun shadingFill(shadingName: COSName) {}
        override fun strokePath() = commit(stroke = true, fill = false)
        override fun fillPath(windingRule: android.graphics.Path.FillType) = commit(stroke = false, fill = true)
        override fun fillAndStrokePath(windingRule: android.graphics.Path.FillType) = commit(stroke = true, fill = true)

        private fun clear() {
            rects.clear(); segs.clear(); hasCurve = false
            pMinX = Float.MAX_VALUE; pMinY = Float.MAX_VALUE; pMaxX = -Float.MAX_VALUE; pMaxY = -Float.MAX_VALUE
        }

        private fun rgb(stroking: Boolean): Int = try {
            if (stroking) graphicsState.strokingColor.toRGB() else graphicsState.nonStrokingColor.toRGB()
        } catch (_: Exception) { 0 }

        private fun topOf(y: Float) = ph - (y - crop.lowerLeftY)
        private fun leftOf(x: Float) = x - crop.lowerLeftX

        private fun commit(stroke: Boolean, fill: Boolean) {
            val fillColor = if (fill) rgb(false) else null
            // küçük dolu daire / kare = madde işareti
            if (fill && pMaxX > pMinX) {
                val w = pMaxX - pMinX; val h = pMaxY - pMinY
                if (w in 1.2f..9f && h in 1.2f..9f && w / h in 0.6f..1.6f && (hasCurve || w < 6f)) {
                    dots += floatArrayOf(leftOf((pMinX + pMaxX) / 2), topOf((pMinY + pMaxY) / 2), max(w, h))
                    clear(); return
                }
            }
            for (r in rects) {
                val l = leftOf(r[0]); val rr = leftOf(r[2])
                val t = topOf(r[3]); val b = topOf(r[1])
                val w = rr - l; val h = b - t
                when {
                    h < 3f && w > 30f -> rules += HLine(l, rr, (t + b) / 2, if (fill) fillColor!! else rgb(true))
                    w < 3f -> if (h > 8f) vlines += floatArrayOf((l + rr) / 2, t, b)
                    w > 40f && h > 12f -> {
                        val visibleFill = fillColor?.takeIf { lum(it) < 250 }
                        if (stroke || visibleFill != null) boxes += Box(l, t, rr, b, stroke, visibleFill)
                    }
                    // küçük çerçeveli hücreler (ör. cevap anahtarı tablosu): tablo bulma bunları yan yana arar
                    stroke && w >= 8f && h >= 8f && h <= 40f -> boxes += Box(l, t, rr, b, true, fillColor?.takeIf { lum(it) < 250 })
                }
            }
            if (stroke) for (s in segs) {
                if (abs(s[1] - s[3]) < 1f && abs(s[2] - s[0]) > 30f) {
                    rules += HLine(leftOf(min(s[0], s[2])), leftOf(max(s[0], s[2])), topOf(s[1]), rgb(true))
                } else if (abs(s[0] - s[2]) < 1f && abs(s[3] - s[1]) > 8f) {
                    vlines += floatArrayOf(leftOf(s[0]), topOf(max(s[1], s[3])), topOf(min(s[1], s[3])))
                }
            }
            clear()
        }

        override fun drawImage(pdImage: PDImage) {
            if (images.size >= 300) return
            try {
                val m = graphicsState.currentTransformationMatrix
                val w = abs(m.scalingFactorX); val h = abs(m.scalingFactorY)
                // küçük resimler de alınır: formüller ve şıklar sık sık resim olarak gömülür
                if (w < 4f || h < 4f) return
                val src = pdImage.image ?: return
                val scale = min(1f, 1600f / max(src.width, src.height))
                val bmp = if (scale < 1f) Bitmap.createScaledBitmap(src, (src.width * scale).toInt().coerceAtLeast(1), (src.height * scale).toInt().coerceAtLeast(1), true) else src
                val out = ByteArrayOutputStream()
                val png = bmp.hasAlpha()
                if (png) bmp.compress(Bitmap.CompressFormat.PNG, 100, out) else bmp.compress(Bitmap.CompressFormat.JPEG, 88, out)
                images += Img(pageNo, leftOf(m.translateX), topOf(m.translateY) - h, w, h, out.toByteArray(), png)
            } catch (_: Throwable) {
                // çözülemeyen resim atlanır
            }
        }
    }

    // ------------------------------------------------------------------ ana işlem

    fun convert(path: String, out: File, progress: (Double) -> Unit): Result {
        val lines = mutableListOf<Line>()
        val gfx = mutableListOf<PageGfx>()
        val textChars: IntArray
        var pageW = 595f
        var pageH = 842f
        PdfTools.open(path).use { doc ->
            val n = doc.numberOfPages
            textChars = IntArray(n)
            val c = Collector()
            val sink = StringWriter()
            for (i in 0 until n) {
                val page = doc.getPage(i)
                val box = page.cropBox
                if (i == 0) { pageW = box.width; pageH = box.height }
                c.pageIndex = i; c.pageW = box.width; c.pageH = box.height
                c.startPage = i + 1; c.endPage = i + 1
                val before = c.lines.size
                c.writeText(doc, sink)
                sink.buffer.setLength(0)
                textChars[i] = c.lines.subList(before, c.lines.size).sumOf { it.text.length }
                val g = Gfx(page, i)
                try { g.processPage(page) } catch (_: Throwable) {}
                // çizimle yapılmış madde işaretlerini (•) satırın başına ekle
                for (dot in g.dots) {
                    val k = (before until c.lines.size).firstOrNull { k ->
                        val l = c.lines[k]
                        dot[1] in (l.top - 1)..(l.bottom + 1) && (l.x0 - dot[0]) in 0f..22f
                    } ?: continue
                    val l = c.lines[k]
                    val g0 = l.glyphs.first()
                    val st = Style(false, false, g0.style.family)
                    val bul = Glyph("•", dot[0] - dot[2] / 2, dot[0] + dot[2] / 2, l.base, l.size, st, g0.color)
                    val sp = Glyph(" ", bul.x1, bul.x1, l.base, l.size, st, g0.color)
                    c.lines[k] = Line(l.page, listOf(bul, sp) + l.glyphs, l.pageW, l.pageH)
                }
                val bigImages = attachInlineImages(c.lines, before, g.images, i, box.width, box.height)
                gfx += PageGfx(g.boxes, g.rules, bigImages, g.vlines)
                progress(0.45 * (i + 1) / n)
            }
            lines += c.lines
        }

        // Taranmış sayfalar: yazı tanıma
        val ocr = HashMap<Int, List<Pair<String, Float>>>()
        val scanned = textChars.indices.filter { textChars[it] < 15 }
        if (scanned.isNotEmpty()) {
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            try {
                ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                    PdfRenderer(pfd).use { r ->
                        scanned.forEachIndexed { k, i ->
                            if (i < r.pageCount) ocrPage(r, i, recognizer).takeIf { it.isNotEmpty() }?.let { ocr[i] = it }
                            progress(0.45 + 0.4 * (k + 1) / scanned.size)
                        }
                    }
                }
            } catch (_: Exception) {
            } finally {
                recognizer.close()
            }
            // yazı tanınan sayfaların resimleri (taranmış sayfanın kendisi) Word'e konmaz
            for (i in ocr.keys) gfx[i] = PageGfx(mutableListOf(), mutableListOf(), emptyList())
        }

        if (lines.isEmpty() && ocr.isEmpty() && gfx.all { it.images.isEmpty() }) {
            throw IllegalStateException("Bu PDF'te okunabilir yazı bulunamadı.")
        }

        val noHf = removeHeadersFooters(lines, textChars.size)
        val tables = mutableListOf<Table>()
        val body = mutableListOf<Line>()
        val cols = HashMap<Int, Cols>()
        for (pg in textChars.indices) {
            val pageLines = noHf.filter { it.page == pg }
            val (t, rest) = try { detectTables(pg, pageLines, gfx[pg]) } catch (_: Exception) { emptyList<Table>() to pageLines }
            tables += t
            // tablolar ayrıldıktan sonra kalan yazıda iki sütun ara
            val ordered = try {
                splitColumns(rest, gfx[pg].vlines).let { (ls, c) -> if (c != null) cols[pg] = c; ls }
            } catch (_: Exception) { rest }
            body += try { mergeLeadNumbers(ordered) } catch (_: Exception) { ordered }
        }
        val paras = layout(body, gfx, cols)
        writeDocx(paras, tables, gfx, ocr, textChars.size, pageW, pageH, body, cols, out)
        progress(1.0)
        return Result(out, textChars.size, ocr.size)
    }

    /**
     * Satır boyundaki küçük resimleri (formül, şık) bulundukları satırın içine, x konumuna yerleştirir;
     * yanında yazı olmayanlar kendi satırını oluşturur. Büyük resimler ayrı paragraf olarak kalır (döndürülür).
     */
    private fun attachInlineImages(lines: MutableList<Line>, from: Int, images: List<Img>, page: Int, pageW: Float, pageH: Float): List<Img> {
        if (images.isEmpty()) return images
        val pageSizes = (from until lines.size).map { lines[it].size }.sorted()
        val body = if (pageSizes.isEmpty()) 10f else pageSizes[pageSizes.size / 2]
        val big = mutableListOf<Img>()
        for (im in images) {
            if (im.h > max(60f, body * 6) || im.w > pageW * 0.45f) { big += im; continue }
            val bottom = im.t + im.h
            val k = (from until lines.size).filter { k ->
                val l = lines[k]
                l.base in (im.t - 1)..(bottom + l.size * 0.4f) && im.l <= l.x1 + 60 && im.l + im.w >= l.x0 - 60
            }.minByOrNull { k -> abs(lines[k].base - (bottom - im.h * 0.2f)) }
            if (k != null) {
                val l = lines[k]
                val ref = l.glyphs.firstOrNull { it.text.isNotBlank() } ?: l.glyphs.first()
                val g = Glyph("\uFFFC", im.l, im.l + im.w, l.base, l.size, ref.style, ref.color, im, (bottom - l.base).coerceAtLeast(0f))
                val gs = l.glyphs.toMutableList()
                var at = gs.indexOfFirst { it.x0 >= im.l + im.w / 2 }
                if (at < 0) at = gs.size
                val sp = { x: Float -> Glyph(" ", x, x, l.base, l.size, ref.style, ref.color) }
                val ins = mutableListOf<Glyph>()
                if (at > 0 && gs[at - 1].text.isNotBlank()) ins += sp(im.l)
                ins += g
                if (at < gs.size && gs[at].text.isNotBlank()) ins += sp(im.l + im.w)
                gs.addAll(at, ins)
                lines[k] = Line(l.page, gs, l.pageW, l.pageH)
            } else {
                // yanında yazı yok: resim tek başına bir satır olur (sırası taban çizgisine göre)
                val base = bottom - im.h * 0.2f
                val st = Style(false, false, null)
                val line = Line(page, listOf(Glyph("\uFFFC", im.l, im.l + im.w, base, body, st, 0, im, im.h * 0.2f)), pageW, pageH)
                val at = (from until lines.size).firstOrNull { lines[it].base > base } ?: lines.size
                lines.add(at, line)
            }
        }
        return big
    }

    // ------------------------------------------------------------------ yardımcılar

    private fun lum(c: Int) = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000

    private fun hex(c: Int) = String.format("%02X%02X%02X", Color.red(c), Color.green(c), Color.blue(c))

    private fun isBlackish(c: Int) = max(Color.red(c), max(Color.green(c), Color.blue(c))) < 70

    fun familyOf(raw: String): String? {
        val n = raw.lowercase()
        return when {
            n.isEmpty() -> null
            "times" in n || "liberationserif" in n || "nimbusrom" in n -> "Times New Roman"
            "arial" in n || "helvetica" in n || "liberationsans" in n || "nimbussans" in n -> "Arial"
            "calibri" in n || "carlito" in n -> "Calibri"
            "cambria" in n || "caladea" in n -> "Cambria"
            "georgia" in n -> "Georgia"
            "garamond" in n -> "Garamond"
            "verdana" in n -> "Verdana"
            "tahoma" in n -> "Tahoma"
            "segoe" in n -> "Segoe UI"
            "courier" in n -> "Courier New"
            "consolas" in n -> "Consolas"
            "roboto" in n -> "Roboto"
            "opensans" in n || "open sans" in n -> "Open Sans"
            "dejavusans" in n -> "DejaVu Sans"
            "dejavuserif" in n -> "DejaVu Serif"
            "symbol" in n || "wingding" in n || "cmsy" in n || "cmmi" in n -> null
            else -> raw.split('-', ',').first()
                .replace(Regex("(PS|MT|PSMT|Std|Pro)$"), "")
                .replace(Regex("([a-z])([A-Z])"), "$1 $2")
                .trim().takeIf { it.length in 2..40 }
        }
    }

    private fun ocrPage(r: PdfRenderer, index: Int, recognizer: TextRecognizer): List<Pair<String, Float>> {
        r.openPage(index).use { page ->
            val scale = 2200f / max(page.width, page.height)
            val w = (page.width * scale).toInt().coerceAtLeast(1)
            val h = (page.height * scale).toInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            Canvas(bmp).drawColor(Color.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            try {
                val text = Tasks.await(recognizer.process(InputImage.fromBitmap(bmp, 0)))
                return text.textBlocks.mapNotNull { b ->
                    val t = b.lines.joinToString(" ") { it.text }.trim()
                    if (t.isEmpty()) return@mapNotNull null
                    val lh = b.lines.mapNotNull { it.boundingBox?.height() }.average().takeIf { !it.isNaN() } ?: 0.0
                    t to (lh / scale * 0.72).toFloat().coerceIn(8f, 40f)
                }
            } finally {
                bmp.recycle()
            }
        }
    }

    /** Her sayfada tekrarlanan üst/alt bilgi satırlarını (sayfa numarası vb.) çıkarır. */
    private fun removeHeadersFooters(lines: List<Line>, pageCount: Int): List<Line> {
        fun inZone(l: Line) = l.top < l.pageH * 0.09f || l.bottom > l.pageH * 0.92f
        fun key(l: Line) = l.text.lowercase().replace(Regex("\\d+"), "#").replace(Regex("\\s+"), " ").trim()
        val pagesByKey = HashMap<String, MutableSet<Int>>()
        for (l in lines) if (inZone(l)) pagesByKey.getOrPut(key(l)) { mutableSetOf() } += l.page
        val need = max(2, (pageCount + 1) / 2)
        val pageNo = Regex("^[-–—\\s#/|.]*$|^(page|sayfa|s\\.)\\s*#(\\s*(of|/|\\|)\\s*#)?$|^#\\s*(of|/)\\s*#$")
        return lines.filter { l ->
            if (!inZone(l)) return@filter true
            val k = key(l)
            if (pageNo.matches(k)) return@filter false
            val pages = pagesByKey[k] ?: return@filter true
            // tekrarlanan üst/alt bilgi (ör. sınav başlığı) yalnız ilk göründüğü sayfada kalır
            !(pageCount >= 2 && pages.size >= need) || (l.page == pages.min() && l.top < l.pageH * 0.09f)
        }
    }

    // ------------------------------------------------------------------ sütunlar

    /** Satırın yazıyla dolu yatay aralıkları (kelime araları birleştirilir). */
    private fun segments(l: Line): List<FloatArray> {
        val out = mutableListOf<FloatArray>()
        val join = max(4f, l.size * 1.1f)
        for (g in l.glyphs.filter { it.text.isNotBlank() }.sortedBy { it.x0 }) {
            val s = out.lastOrNull()
            if (s != null && g.x0 - s[1] < join) s[1] = max(s[1], g.x1) else out += floatArrayOf(g.x0, g.x1)
        }
        return out
    }

    /**
     * Sayfa iki sütunluysa satırları sütunlara ayırır (aynı hizadaki sol+sağ satırı ikiye böler)
     * ve okuma sırasına dizer: sol sütun, sonra sağ sütun. Tek sütunluysa satırlar olduğu gibi döner.
     */
    private fun splitColumns(lines: List<Line>, vlines: List<FloatArray> = emptyList()): Pair<List<Line>, Cols?> {
        if (lines.size < 8) return lines to null
        val pageW = lines.first().pageW
        val segs = lines.map { segments(it) }
        val from = (pageW * 0.28f).toInt(); val to = (pageW * 0.72f).toInt()
        val cover = IntArray(to - from + 1)
        for (ss in segs) for (s in ss) {
            val a = max(from, floor(s[0]).toInt()); val b = min(to, ceil(s[1]).toInt())
            for (x in a..b) cover[x - from]++
        }
        // sütun arası: ortadaki, yazının neredeyse hiç geçmediği şerit. Eşik en az kesilen noktadan başlar ve
        // ancak şerit çok darsa gevşer; aksi hâlde kısa satırlı sütunda şerit sütunun içine kadar uzar.
        val minCover = cover.min()
        if (minCover > lines.size * 0.3f) return lines to null
        fun widest(t: Int): IntArray? {
            var best: IntArray? = null
            var i = 0
            while (i < cover.size) {
                if (cover[i] > t) { i++; continue }
                var j = i
                while (j + 1 < cover.size && cover[j + 1] <= t) j++
                if (i > 0 && j < cover.size - 1 && (best == null || j - i > best[1] - best[0])) best = intArrayOf(i, j)
                i = j + 1
            }
            return best
        }
        val gap = listOf(minCover, minCover + 1, minCover + max(1, lines.size / 20)).distinct()
            .firstNotNullOfOrNull { t -> widest(t)?.takeIf { it[1] - it[0] + 1 >= 6 } } ?: return lines to null
        val gL = (from + gap[0]).toFloat(); val gR = (from + gap[1] + 1).toFloat()
        // sayfa boyu dikey ayırıcı çizgi şeridin içindeyse sütunları o ayırır
        val pageH = lines.first().pageH
        val dividerX = vlines.firstOrNull { v -> v[0] in (gL - 3)..(gR + 3) && v[2] - v[1] > pageH * 0.35f }?.get(0)
        val mid = dividerX ?: ((gL + gR) / 2)

        // iki yanda da yeterince, düzgün hizalı ve sütun genişliğini dolduran satır olmalı
        val leftEnds = segs.mapNotNull { ss -> ss.filter { it[1] <= gL + 1 }.maxOfOrNull { it[1] } }
        val rightStarts = segs.mapNotNull { ss -> ss.filter { it[0] >= gR - 1 }.minOfOrNull { it[0] } }
        // sütun arasında sayfa boyu dikey çizgi (sınav kâğıtlarında olur): sütun olduğu kesin, sağ sütun seyrek olabilir
        val divider = dividerX != null
        if (leftEnds.isEmpty() || rightStarts.isEmpty()) return lines to null
        if (!divider) {
            val need = max(3, (lines.size * 0.2f).toInt())
            if (leftEnds.size < need || rightStarts.size < need) return lines to null
            // sağ sütunda en az 3 satır aynı x'ten başlamalı (soru, devam satırı, şık girintileri farklı olabilir)
            val aligned = rightStarts.maxOf { s -> rightStarts.count { abs(it - s) < 2.5f } }
            if (aligned < 3) return lines to null
            // iki yanda da sütunu dolduran uzun metin satırları olmalı (rastgele kısa satırlar / tablo hücreleri sütun değildir)
            val lMin = segs.flatten().filter { it[1] <= gL + 1 }.minOf { it[0] }
            val rMax = segs.flatten().filter { it[0] >= gR - 1 }.maxOf { it[1] }
            val longL = segs.count { ss -> ss.any { it[1] <= gL + 1 && it[1] - it[0] >= (gL - lMin) * 0.55f } }
            val longR = segs.count { ss -> ss.any { it[0] >= gR - 1 && it[1] - it[0] >= (rMax - gR) * 0.55f } }
            if (longL < 1 || longR < 1 || longL + longR < 3) return lines to null
        }

        // aynı hizada iki sütundan yazı taşıyan satırları ikiye böl
        fun part(l: Line, gs: List<Glyph>): Line? {
            val t = gs.dropWhile { it.text.isBlank() }.dropLastWhile { it.text.isBlank() }
            return if (t.any { it.text.isNotBlank() }) Line(l.page, t, l.pageW, l.pageH) else null
        }
        fun classify(l: Line) {
            l.col = when {
                l.x1 <= gR + 2 -> 1
                l.x0 >= gL - 2 -> 2
                else -> 0
            }
        }
        val out = mutableListOf<Line>()
        for (l in lines) {
            val ss = segments(l)
            val crossing = ss.any { it[0] < mid - 1 && it[1] > mid + 1 }
            if (!crossing) {
                val lp = part(l, l.glyphs.filter { (it.x0 + it.x1) / 2 < mid })
                val rp = part(l, l.glyphs.filter { (it.x0 + it.x1) / 2 >= mid })
                if (lp != null && rp != null) {
                    out += lp.also { it.col = 1 }
                    out += rp.also { it.col = 2 }
                    continue
                }
                classify(l); out += l
                continue
            }
            // sütun arasını kesen satır (başlık vb.): aynı hizadaki uzak parçaları (ör. sağdaki "A") ayır
            val cuts = ss.zipWithNext().filter { (a, b) -> b[0] - a[1] > max(30f, 3 * l.size) }.map { (a, b) -> (a[1] + b[0]) / 2 }
            if (cuts.isEmpty()) { classify(l); out += l; continue }
            val bounds = listOf(-Float.MAX_VALUE) + cuts + Float.MAX_VALUE
            for (k in 0 until bounds.size - 1) {
                part(l, l.glyphs.filter { (it.x0 + it.x1) / 2 in bounds[k]..bounds[k + 1] })?.let { classify(it); out += it }
            }
        }
        val left = out.filter { it.col == 1 }; val right = out.filter { it.col == 2 }
        if (left.isEmpty() || right.isEmpty()) return lines to null
        val cols = Cols(left.minOf { it.x0 }, left.maxOf { it.x1 }, right.minOf { it.x0 }, right.maxOf { it.x1 })
        return columnOrder(out, { it.base }, { it.col }) to cols
    }

    /**
     * Tek başına kalmış soru numarasını ("5.", "12)") sağındaki metin/formül satırıyla birleştirir.
     * Formül yüksek olduğunda numara satırın üstüne hizalanır ve ayrı satır gibi görünür.
     */
    private fun mergeLeadNumbers(lines: List<Line>): List<Line> {
        val num = Regex("^\\(?\\d{1,3}[.)]$")
        val out = lines.toMutableList()
        var i = 0
        while (i < out.size) {
            val n = out[i]
            if (!num.matches(n.text)) { i++; continue }
            var best = -1
            var bestD = Float.MAX_VALUE
            for (j in out.indices) {
                if (j == i) continue
                val m = out[j]
                if (m.page != n.page || m.col != n.col || m.box !== n.box) continue
                val dx = m.x0 - n.x1
                val dy = m.base - n.base
                if (dx < -1f || dx > 40f || dy < -n.size * 0.6f || dy > n.size * 2.2f) continue
                if (abs(dy) < bestD) { bestD = abs(dy); best = j }
            }
            if (best < 0) { i++; continue }
            val m = out[best]
            val g0 = n.glyphs.last()
            val sp = Glyph(" ", g0.x1, g0.x1, g0.base, g0.size, g0.style, g0.color)
            val merged = Line(n.page, n.glyphs + sp + m.glyphs, n.pageW, n.pageH).also { it.col = m.col }
            out[i] = merged
            out.removeAt(best)
            if (best < i) i--
            i++
        }
        return out
    }

    /** Yukarıdan aşağı; tam genişlikteki öğeler arasında önce sol sütun, sonra sağ sütun. */
    private fun <T> columnOrder(items: List<T>, top: (T) -> Float, col: (T) -> Int): List<T> {
        val out = ArrayList<T>(items.size)
        val l = mutableListOf<T>(); val r = mutableListOf<T>()
        fun flush() { out += l; out += r; l.clear(); r.clear() }
        for (it in items.sortedBy(top)) when (col(it)) {
            1 -> l += it
            2 -> r += it
            else -> { flush(); out += it }
        }
        flush()
        return out
    }

    // ------------------------------------------------------------------ tablolar

    private class Edge(val y: Float, var l: Float, var r: Float, val rule: Boolean)

    /**
     * Yatay çizgiler ve gölgeli hücre kutularından tablo bölgelerini bulur; bölgedeki yazıları
     * sütun boşluklarına göre hücrelere dağıtır. Tabloya giren yazılar normal akıştan çıkarılır.
     */
    private fun detectTables(page: Int, pageLines: List<Line>, g: PageGfx): Pair<List<Table>, List<Line>> {
        if (pageLines.isEmpty()) return emptyList<Table>() to pageLines
        val pageW = pageLines.first().pageW

        // hücre kutuları: aynı satırda yan yana duran en az iki küçük kutu
        val small = g.boxes.filter { it.b - it.t < 90f && it.r - it.l < pageW * 0.75f }
        val cellBoxes = mutableSetOf<Box>()
        for (b in small) {
            val sameRow = small.filter { abs(it.t - b.t) < 2.5f && abs(it.b - b.b) < 2.5f }
            if (sameRow.size >= 2) cellBoxes += sameRow
        }

        val edges = mutableListOf<Edge>()
        for (r in g.rules) edges += Edge(r.y, r.l, r.r, true)
        for (b in cellBoxes) { edges += Edge(b.t, b.l, b.r, false); edges += Edge(b.b, b.l, b.r, false) }
        if (edges.size < 2) return emptyList<Table>() to pageLines
        edges.sortBy { it.y }
        // aynı yükseklikteki kenarları birleştir
        val merged = mutableListOf<Edge>()
        for (e in edges) {
            val m = merged.lastOrNull()
            if (m != null && abs(m.y - e.y) < 2.5f && e.l <= m.r + 5 && e.r >= m.l - 5) {
                m.l = min(m.l, e.l); m.r = max(m.r, e.r)
            } else merged += Edge(e.y, e.l, e.r, e.rule)
        }

        // dikey olarak ardışık, yatayda örtüşen kenar grupları = aday tablolar
        val groups = mutableListOf<MutableList<Edge>>()
        for (e in merged) {
            val gr = groups.lastOrNull()
            val last = gr?.last()
            val ok = gr != null && last != null && e.y - last.y <= 130f && run {
                val gl = gr.minOf { it.l }; val grr = gr.maxOf { it.r }
                val ov = min(grr, e.r) - max(gl, e.l)
                ov >= 0.7f * min(grr - gl, e.r - e.l)
            }
            if (ok) gr!! += e else groups += mutableListOf(e)
        }

        val used = java.util.Collections.newSetFromMap(IdentityHashMap<Glyph, Boolean>())
        val tables = mutableListOf<Table>()
        val allGlyphs = pageLines.flatMap { it.glyphs }
        val removeRules = mutableListOf<HLine>()
        val removeBoxes = mutableListOf<Box>()

        fun bandGlyphs(a: Float, b: Float, tl: Float, tr: Float) = allGlyphs.filter { gl ->
            gl.text.isNotBlank() && (gl.x0 + gl.x1) / 2 in (tl - 2)..(tr + 2) && gl.base > a && gl.base - gl.size * 0.6f < b
        }

        /** Bant içinde, tüm satırlar boyunca boş kalan dikey şerit (sütun arası) var mı? null = bant boş. */
        fun bandTabular(a: Float, b: Float, tl: Float, tr: Float): Boolean? {
            val gs = bandGlyphs(a, b, tl, tr)
            if (gs.isEmpty()) return null
            if (cellBoxes.count { it.t >= a - 3 && it.b <= b + 3 } >= 2) return true
            val w = (tr - tl).roundToInt() + 1
            val occ = BooleanArray(w)
            for (gl in gs) {
                val s = ((gl.x0 - tl).roundToInt()).coerceIn(0, w - 1)
                val e = ((gl.x1 - tl).roundToInt()).coerceIn(0, w - 1)
                for (x in s..e) occ[x] = true
            }
            val f = occ.indexOfFirst { it }; val l = occ.indexOfLast { it }
            var run = 0
            for (x in f..l) { if (!occ[x]) { run++; if (run >= 7) return true } else run = 0 }
            return false
        }

        // her kenar grubunu, sütun yapısı olan ardışık bantlara böl
        val candidates = mutableListOf<Triple<List<Float>, Float, Float>>()
        for (gr in groups) {
            val ys0 = gr.map { it.y }
            val tl0 = gr.minOf { it.l }; val tr0 = gr.maxOf { it.r }
            if (ys0.size < 2 || tr0 - tl0 < 120f) continue
            val bands = ys0.zipWithNext()
            val tab = bands.map { (a, b) -> bandTabular(a, b, tl0, tr0) }
            var start = -1
            var end = -1
            fun close() {
                if (start >= 0 && end >= start) candidates += Triple(ys0.subList(start, end + 2), tl0, tr0)
                start = -1; end = -1
            }
            for (i in bands.indices) {
                when (tab[i]) {
                    true -> { if (start < 0) start = i; end = i }
                    null -> {}
                    false -> {
                        // sütunların çoğu boş olan tek bir ara satıra izin ver (iki yanı tabloysa)
                        val (a, b) = bands[i]
                        val gs = bandGlyphs(a, b, tl0, tr0)
                        val narrow = gs.isNotEmpty() && gs.maxOf { it.x1 } - gs.minOf { it.x0 } < (tr0 - tl0) * 0.45f
                        if (start >= 0 && narrow && tab.getOrNull(i + 1) == true) end = i else close()
                    }
                }
            }
            close()
        }

        for ((ys, tl, tr) in candidates) {
            val hasCells = cellBoxes.any { b -> b.t >= ys.first() - 3 && b.b <= ys.last() + 3 }
            if (ys.size < 3 && !(ys.size >= 2 && hasCells)) continue
            val top = ys.first(); val bot = ys.last()
            val inside = allGlyphs.filter { gl ->
                gl !in used && (gl.x0 + gl.x1) / 2 in (tl - 2)..(tr + 2) && gl.base > top && gl.base - gl.size * 0.6f < bot
            }
            val solid = inside.filter { it.text.isNotBlank() }
            if (solid.size < 4) continue

            // sütun sınırları: başlık hücre kutularından, yoksa boş dikey şeritlerden
            val headerBoxes = cellBoxes.filter { it.t >= top - 3 && it.b <= bot + 3 }
                .groupBy { (it.t / 3).roundToInt() }.values.maxByOrNull { it.size }?.sortedBy { it.l }
            val bounds = mutableListOf(tl)
            if (headerBoxes != null && headerBoxes.size >= 2) {
                for (i in 0 until headerBoxes.size - 1) bounds += (headerBoxes[i].r + headerBoxes[i + 1].l) / 2
            } else {
                val w = (tr - tl).roundToInt() + 1
                val occ = BooleanArray(w)
                for (gl in solid) {
                    val a = ((gl.x0 - tl).roundToInt()).coerceIn(0, w - 1)
                    val b = ((gl.x1 - tl).roundToInt()).coerceIn(0, w - 1)
                    for (x in a..b) occ[x] = true
                }
                val firstOcc = occ.indexOfFirst { it }; val lastOcc = occ.indexOfLast { it }
                var x = firstOcc
                while (x in 0 until lastOcc) {
                    if (!occ[x]) {
                        var e = x
                        while (e < lastOcc && !occ[e]) e++
                        if (e - x >= 7) bounds += tl + (x + e) / 2f
                        x = e
                    } else x++
                }
            }
            bounds += tr
            if (bounds.size < 3) continue // en az iki sütun yoksa tablo değil
            val nCols = bounds.size - 1

            // satırlar
            val bands = ys.zipWithNext().filter { (a, b) -> b - a > 4f && inside.any { gl -> gl.text.isNotBlank() && gl.base > a && gl.base - gl.size * 0.6f < b } }
            val rowsY = mutableListOf<Pair<Float, Float>>()
            if (bands.size >= 3) rowsY += bands
            else for ((a, b) in bands) {
                val levels = inside.filter { it.text.isNotBlank() && it.base > a && it.base - it.size * 0.6f < b }
                    .map { it.base }.sorted().fold(mutableListOf<Float>()) { acc, y -> if (acc.isEmpty() || y - acc.last() > 1.5f) acc += y; acc }
                if (levels.size <= 1) { rowsY += a to b; continue }
                var start = a
                for (i in 0 until levels.size - 1) {
                    val mid = (levels[i] + levels[i + 1]) / 2
                    rowsY += start to mid; start = mid
                }
                rowsY += start to b
            }

            val rows = mutableListOf<Row>()
            for ((ri, rb) in rowsY.withIndex()) {
                val (ya, yb) = rb
                val cells = mutableListOf<Cell>()
                var anyFill = false
                for (c in 0 until nCols) {
                    val cl = bounds[c]; val cr = bounds[c + 1]
                    val gs = inside.filter { gl -> (gl.x0 + gl.x1) / 2 in cl..cr && gl.base > ya && gl.base - gl.size * 0.6f <= yb }
                    used += gs
                    val cellLines = gs.sortedBy { it.base }
                        .fold(mutableListOf<MutableList<Glyph>>()) { acc, gl ->
                            val cur = acc.lastOrNull()
                            if (cur != null && abs(cur.first().base - gl.base) < max(gl.size, cur.first().size) * 0.45f) cur += gl else acc += mutableListOf(gl)
                            acc
                        }
                        .map { it.sortedBy { gl -> gl.x0 }.dropWhile { gl -> gl.text.isBlank() }.dropLastWhile { gl -> gl.text.isBlank() } }
                        .filter { l -> l.any { it.text.isNotBlank() } }
                        .map { Line(page, it, pageW, pageLines.first().pageH) }
                    val cx = (cl + cr) / 2; val cy = (ya + yb) / 2
                    val fill = g.boxes.filter { it.fill != null && cx in it.l..it.r && cy in it.t..it.b && (it.r - it.l) < (tr - tl) + 4 }
                        .minByOrNull { (it.r - it.l) * (it.b - it.t) }?.fill
                    if (fill != null) anyFill = true
                    val align = if (cellLines.isEmpty()) "left" else {
                        val x0 = cellLines.minOf { it.x0 }; val x1 = cellLines.maxOf { it.x1 }
                        val lg = x0 - cl; val rg = cr - x1
                        when {
                            lg > 6 && abs(lg - rg) < 5 -> "center"
                            lg > 15 && rg < lg * 0.4f -> "right"
                            else -> "left"
                        }
                    }
                    cells += Cell(cellLines, fill, align)
                }
                if (cells.all { it.lines.isEmpty() }) continue
                val header = ri == 0 && (anyFill || cells.filter { it.lines.isNotEmpty() }.all { c -> c.lines.all { it.bold } })
                rows += Row(cells, header)
            }
            if (rows.size < 2) { inside.forEach { used.remove(it) }; continue }

            val tableRules = g.rules.filter { it.y >= top - 3 && it.y <= bot + 3 && it.l >= tl - 5 && it.r <= tr + 5 }
            val vb = g.vlines.count { it[0] in (tl - 3)..(tr + 3) && it[2] > top && it[1] < bot } >= 2
            tables += Table(page, top, tl, tr, bounds, rows, tableRules.size >= 2 || hasCells, vb,
                tableRules.firstOrNull()?.color ?: Color.rgb(160, 160, 160))
            removeRules += tableRules
            removeBoxes += g.boxes.filter { it.t >= top - 3 && it.b <= bot + 3 && it.l >= tl - 5 && it.r <= tr + 5 }
        }
        if (tables.isEmpty()) return tables to pageLines
        g.rules.removeAll(removeRules.toSet())
        g.boxes.removeAll(removeBoxes.toSet())

        val rest = pageLines.mapNotNull { l ->
            if (l.glyphs.none { it in used }) l
            else {
                val keep = l.glyphs.filter { it !in used }.dropWhile { it.text.isBlank() }.dropLastWhile { it.text.isBlank() }
                if (keep.any { it.text.isNotBlank() }) Line(l.page, keep, l.pageW, l.pageH).also { it.col = l.col } else null
            }
        }
        return tables to rest
    }

    // ------------------------------------------------------------------ satırlardan paragraflara

    private val bullet = Regex("^([•●▪◦■□–—\\-*]|\\(?[0-9]{1,2}[.)]|\\(?[a-zA-Z][.)]|[ivx]{1,4}[.)])\\s")

    private fun layout(lines: List<Line>, gfx: List<PageGfx>, cols: Map<Int, Cols>): List<Para> {
        // kutulara yerleştir
        for (l in lines) {
            l.box = gfx[l.page].boxes.filter { it.contains(l.x0, l.top, l.x1, l.bottom) }
                .minByOrNull { (it.r - it.l) * (it.b - it.t) }
            l.box?.let { it.contentL = min(it.contentL, l.x0); it.contentR = max(it.contentR, l.x1) }
        }
        val pageL = HashMap<Int, Float>()
        val pageR = HashMap<Int, Float>()
        for (l in lines) if (l.box == null) {
            pageL[l.page] = min(pageL[l.page] ?: Float.MAX_VALUE, l.x0)
            pageR[l.page] = max(pageR[l.page] ?: 0f, l.x1)
        }
        fun colL(l: Line) = cols[l.page]?.let { c -> if (l.col == 1) c.leftL else if (l.col == 2) c.rightL else null }
        fun colR(l: Line) = cols[l.page]?.let { c -> if (l.col == 1) c.leftR else if (l.col == 2) c.rightR else null }
        fun regionL(l: Line) = l.box?.contentL ?: colL(l) ?: pageL[l.page] ?: l.x0
        fun regionR(l: Line) = l.box?.contentR ?: colR(l) ?: pageR[l.page] ?: l.x1

        for (l in lines) {
            val rl = regionL(l); val rr = regionR(l)
            val c = (l.x0 + l.x1) / 2; val rc = (rl + rr) / 2
            // ortalanmış: iki yanda da boşluk var ve boşluklar eşit (iki yana yaslı tam satırlar hariç)
            l.centered = abs(c - rc) < max(4f, (rr - rl) * 0.02f) && l.x0 > rl + 4f && l.x1 < rr - 4f
        }

        fun full(a: Line) = a.x1 >= regionR(a) - max(2 * a.size, (regionR(a) - regionL(a)) * 0.12f)

        fun wide(a: Line) = a.x1 - a.x0 >= (regionR(a) - regionL(a)) * 0.8f

        val paras = mutableListOf<Para>()
        var p: Para? = null
        for (l in lines) {
            val cur = p
            if (cur != null && canJoin(cur, l, ::regionL, ::full, ::wide)) {
                cur.lines += l
            } else {
                p = Para(l.page).also { it.lines += l; paras += it }
            }
        }

        for (para in paras) {
            val ls = para.lines
            val f = ls.first()
            para.align = when {
                ls.all { it.centered } -> "center"
                ls.size >= 2 && ls.dropLast(1).all { it.x1 >= regionR(it) - 1.5f * it.size } -> "both"
                ls.all { it.x1 >= regionR(it) - 2f && it.x0 > regionL(it) + (regionR(it) - regionL(it)) * 0.3f } -> "right"
                else -> "left"
            }
            if (f.centered && ls.size == 1 && f.box == null && f.x1 - f.x0 < 20) para.align = "center"
        }

        // yatay çizgiler: hemen üstündeki paragrafın alt kenarlığı olur
        for ((pageNo, g) in gfx.withIndex()) {
            val onPage = paras.filter { it.lines.last().page == pageNo }
            for (rule in g.rules) {
                val ph = onPage.firstOrNull()?.lines?.last()?.pageH ?: continue
                if (rule.y > ph * 0.92f || rule.y < ph * 0.06f) continue
                val target = onPage.filter { q ->
                    val last = q.lines.last()
                    val gap = rule.y - last.bottom
                    val qw = q.lines.maxOf { it.x1 } - q.lines.minOf { it.x0 }
                    // kısa çizgi kelime altı çizgisidir, paragraf alt çizgisi değil
                    gap in -2f..16f && min(rule.r, q.lines.maxOf { it.x1 }) - max(rule.l, q.lines.minOf { it.x0 }) > 0 &&
                        rule.r - rule.l >= qw * 0.6f
                }.minByOrNull { rule.y - it.lines.last().bottom }
                if (target != null && target.bottomRule == null && target.lines.last().box == null) target.bottomRule = rule
            }
        }
        return paras
    }

    private fun canJoin(
        p: Para, l: Line, regionL: (Line) -> Float, full: (Line) -> Boolean, wide: (Line) -> Boolean,
    ): Boolean {
        val a = p.lines.last()
        if (a.box !== l.box) return false
        val sz = max(a.size, l.size)
        if (abs(a.size - l.size) > sz * 0.2f) return false
        if (a.page != l.page) {
            if (l.page != a.page + 1 || a.centered || l.centered || a.bold != l.bold) return false
        } else {
            val gap = l.base - a.base
            if (gap < sz * 0.4f || gap > sz * 1.85f) return false
        }
        // ortalanmış çok satırlı başlık: üst satır neredeyse tam genişlikteyse (taşmış) devam eder
        if (l.centered && (a.centered || wide(a))) return a.bold == l.bold && wide(a)
        if (a.centered || l.centered) return false
        if (bullet.containsMatchIn(l.text)) return false
        if (!full(a)) return false
        // girinti kontrolü: yeni satır, paragrafın devam satırlarıyla aynı hizada olmalı
        val contLeft = if (p.lines.size >= 2) p.lines[1].x0 else null
        if (contLeft != null) {
            if (a.page == l.page && abs(l.x0 - contLeft) > l.size) return false
        } else {
            val first = p.lines.first()
            val hang = bulletTextX(first)
            if (hang != null) {
                // madde işaretli satırın devamı: madde metninin hizasından başlar
                if (abs(l.x0 - hang) > l.size && l.x0 > first.x0 + 0.6f * l.size) return false
            } else {
                if (l.x0 > a.x0 + 0.6f * l.size) return false
                if (a.page != l.page && l.x0 > regionL(l) + l.size) return false
            }
        }
        return true
    }

    /** Madde işaretli satırda ("1. Metin", "• Metin") metnin başladığı x konumu. */
    private fun bulletTextX(l: Line): Float? {
        if (!bullet.containsMatchIn(l.text)) return null
        val g = l.glyphs
        val sp = g.indexOfFirst { it.text.isBlank() }.takeIf { it > 0 } ?: return null
        return g.drop(sp).firstOrNull { it.text.isNotBlank() }?.x0
    }

    // ------------------------------------------------------------------ .docx yazımı

    private fun esc(s: String): String {
        val sb = StringBuilder(s.length + 16)
        for (ch in s) {
            when {
                ch == '&' -> sb.append("&amp;")
                ch == '<' -> sb.append("&lt;")
                ch == '>' -> sb.append("&gt;")
                ch == '"' -> sb.append("&quot;")
                ch.code < 0x20 && ch != '\t' -> {}
                ch == '￾' || ch == '￿' -> {}
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun tw(pt: Float) = (pt * 20).roundToInt()

    private class RunKey(val bold: Boolean, val italic: Boolean, val family: String?, val halfPts: Int, val color: String?, val vert: String?) {
        override fun equals(other: Any?) = other is RunKey && bold == other.bold && italic == other.italic &&
            family == other.family && halfPts == other.halfPts && color == other.color && vert == other.vert
        override fun hashCode() = listOf(bold, italic, family, halfPts, color, vert).hashCode()
    }

    /** Belgeye eklenen resimler (writeDocx sırasında; satır içi resimler de buraya yazılır). */
    private val mediaSink = ThreadLocal<MutableList<Pair<String, ByteArray>>>()

    /** Satır içi resim: <w:r><w:drawing>…; drop > 0 ise resim taban çizgisinin altına indirilir. */
    private fun drawingRun(im: Img, maxW: Float, drop: Float = 0f): String {
        val media = mediaSink.get() ?: return ""
        val id = media.size + 1
        val name = "image$id.${if (im.png) "png" else "jpeg"}"
        media += name to im.bytes
        val k = if (maxW > 0) min(1f, maxW / im.w) else 1f
        val cx = (im.w * k * 12700).toLong(); val cy = (im.h * k * 12700).toLong()
        val sb = StringBuilder("<w:r>")
        val pos = (drop * k * 2).roundToInt()
        if (pos >= 2) sb.append("""<w:rPr><w:position w:val="-$pos"/></w:rPr>""")
        sb.append("<w:drawing><wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\">")
        sb.append("""<wp:extent cx="$cx" cy="$cy"/><wp:docPr id="$id" name="Resim $id"/>""")
        sb.append("""<a:graphic><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/picture"><pic:pic>""")
        sb.append("""<pic:nvPicPr><pic:cNvPr id="$id" name="$name"/><pic:cNvPicPr/></pic:nvPicPr>""")
        sb.append("""<pic:blipFill><a:blip r:embed="rImg$id"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>""")
        sb.append("""<pic:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="$cx" cy="$cy"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></pic:spPr>""")
        sb.append("</pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r>")
        return sb.toString()
    }

    private fun runsXml(lines: List<Line>, defaultFamily: String?, defaultHalf: Int, maxW: Float = 0f): String {
        val pieces = mutableListOf<Pair<RunKey, StringBuilder>>()
        val images = HashMap<Int, Glyph>() // parça sırası -> satır içi resim
        fun add(k: RunKey, s: String) {
            val last = pieces.lastOrNull()
            if (last != null && last.first == k && pieces.size - 1 !in images) last.second.append(s) else pieces += k to StringBuilder(s)
        }
        lines.forEachIndexed { li, line ->
            if (li > 0) {
                val last = pieces.lastOrNull()?.second
                val first = line.glyphs.firstOrNull { it.text.isNotBlank() }?.text?.firstOrNull()
                if (last != null && last.length >= 2 && last.last() == '-' && last[last.length - 2].isLetter() && first?.isLowerCase() == true) {
                    last.setLength(last.length - 1) // satır sonu tirelemesi
                } else if (last != null && last.isNotEmpty() && last.last() != ' ') {
                    last.append(' ')
                }
            }
            for (g in line.glyphs) {
                if (g.img != null) {
                    // sarkma, satırın son hâlinin taban çizgisine göre (satır sütunlara bölünmüş olabilir)
                    val drop = (g.img.t + g.img.h - line.base).coerceIn(0f, g.img.h * 0.6f)
                    images[pieces.size] = Glyph(g.text, g.x0, g.x1, g.base, g.size, g.style, g.color, g.img, drop)
                    pieces += RunKey(false, false, null, 0, null, null) to StringBuilder()
                    continue
                }
                var vert: String? = null
                if (g.size < line.size * 0.82f && g.text.isNotBlank()) {
                    if (g.base < line.base - line.size * 0.15f) vert = "superscript"
                    else if (g.base > line.base + line.size * 0.08f) vert = "subscript"
                }
                val half = ((if (vert != null) line.size else g.size) * 2).roundToInt()
                val color = if (isBlackish(g.color)) null else hex(g.color)
                add(RunKey(g.style.bold, g.style.italic, g.style.family ?: defaultFamily, half, color, vert), g.text)
            }
        }
        val sb = StringBuilder()
        for ((pi, piece) in pieces.withIndex()) {
            val (k, t) = piece
            val ig = images[pi]
            if (ig != null) {
                sb.append(drawingRun(ig.img!!, maxW, ig.drop))
                // satır birleştirirken resimden sonra eklenen boşluk
                if (t.isNotEmpty()) sb.append("""<w:r><w:t xml:space="preserve">""").append(esc(t.toString())).append("</w:t></w:r>")
                continue
            }
            if (t.isEmpty()) continue
            sb.append("<w:r><w:rPr>")
            if (k.family != null && k.family != defaultFamily) {
                val f = esc(k.family)
                sb.append("""<w:rFonts w:ascii="$f" w:hAnsi="$f" w:cs="$f"/>""")
            }
            if (k.bold) sb.append("<w:b/><w:bCs/>")
            if (k.italic) sb.append("<w:i/><w:iCs/>")
            if (k.color != null) sb.append("""<w:color w:val="${k.color}"/>""")
            if (k.halfPts != defaultHalf) sb.append("""<w:sz w:val="${k.halfPts}"/><w:szCs w:val="${k.halfPts}"/>""")
            if (k.vert != null) sb.append("""<w:vertAlign w:val="${k.vert}"/>""")
            sb.append("""</w:rPr><w:t xml:space="preserve">""").append(esc(t.toString())).append("</w:t></w:r>")
        }
        return sb.toString()
    }

    private fun writeDocx(
        paras: List<Para>, tables: List<Table>, gfx: List<PageGfx>, ocr: Map<Int, List<Pair<String, Float>>>, pageCount: Int,
        pageW: Float, pageH: Float, lines: List<Line>, cols: Map<Int, Cols>, out: File,
    ) {
        // Sayfa kenar boşlukları PDF'teki yazı alanından
        val free = lines.filter { it.box == null }
        val ml = (free.minOfOrNull { it.x0 } ?: 72f).coerceIn(18f, pageW / 3)
        val mr = (pageW - (free.maxOfOrNull { it.x1 } ?: (pageW - 72f))).coerceIn(18f, pageW / 3)
        val mt = (lines.minOfOrNull { it.top } ?: 72f).coerceIn(18f, pageH / 4)
        val mbPdf = (pageH - (lines.maxOfOrNull { it.bottom } ?: (pageH - 72f))).coerceIn(18f, pageH / 4)
        // iki sütunlu belgede sayfalar açık sayfa sonlarıyla ayrılır: alt boşluğu küçült ki Word'de biraz
        // uzayan bir sütun yeni sayfaya taşmasın (sayfa düzeni değişmez)
        val mb = if (cols.isNotEmpty()) min(mbPdf, 28f) else mbPdf

        // Gövde yazı boyutu ve yazı tipi (en çok kullanılan)
        val sizeCount = HashMap<Int, Int>()
        val famCount = HashMap<String, Int>()
        for (l in lines) {
            sizeCount.merge((l.size * 2).roundToInt(), l.text.length, Int::plus)
            for (g in l.glyphs) g.style.family?.let { famCount.merge(it, 1, Int::plus) }
        }
        val bodyHalf = sizeCount.maxByOrNull { it.value }?.key ?: 22
        val bodySize = bodyHalf / 2f
        val bodyFamily = famCount.maxByOrNull { it.value }?.key

        // Sayfa sayfa öğeler: paragraf, resim, yazı tanıma bloğu
        class Item(val top: Float, val col: Int, val para: Para? = null, val img: Img? = null, val ocr: Pair<String, Float>? = null, val table: Table? = null)
        fun colOf(page: Int, l: Float, r: Float): Int = cols[page]?.let { c ->
            val mid = (c.leftR + c.rightL) / 2
            if (r <= mid + 2) 1 else if (l >= mid - 2) 2 else 0
        } ?: 0
        val pages = (0 until pageCount).map { mutableListOf<Item>() }
        for (p in paras) pages[p.page] += Item(p.top, p.lines.first().col, para = p)
        for (t in tables) pages[t.page] += Item(t.top, colOf(t.page, t.l, t.r), table = t)
        for (g in gfx) for (im in g.images) pages[im.page] += Item(im.t, colOf(im.page, im.l, im.l + im.w), img = im)
        for ((i, blocks) in ocr) blocks.forEachIndexed { k, b -> pages[i] += Item(k.toFloat(), 0, ocr = b) }
        for ((pi, items) in pages.withIndex()) {
            // sütunlu sayfada satırlarla aynı ölçüt (taban çizgisi): büyük harfli "A" başlığın önüne geçmesin
            val sorted = if (cols[pi] != null) columnOrder(items, { it.para?.lines?.first()?.base ?: it.top }, { it.col })
                else items.sortedBy { it.top }
            items.clear(); items += sorted
        }

        // İki sütunlu bölgeler Word'de iki sütunlu bölüm olur; sütun/sayfa geçişleri sütun sonu ile yapılır
        fun colsXml(c: Cols?): String {
            if (c == null) return ""
            // Word'ün yazı ölçüleri PDF'ten biraz farklı olabilir: sol sütuna sütun arasından küçük pay ver
            val gutter = c.rightL - c.leftR
            val slack = min(8f, gutter * 0.35f).coerceAtLeast(0f)
            val w1 = c.leftR - ml + slack; val space = gutter - slack; val w2 = pageW - mr - c.rightL
            return if (w1 < 60 || w2 < 60 || space < 4) """<w:cols w:num="2" w:space="360"/>"""
            else """<w:cols w:num="2" w:space="${tw(space)}" w:equalWidth="0"><w:col w:w="${tw(w1)}" w:space="${tw(space)}"/><w:col w:w="${tw(w2)}"/></w:cols>"""
        }
        fun sectPr(c: Cols?) = """<w:sectPr><w:type w:val="continuous"/><w:pgSz w:w="${tw(pageW)}" w:h="${tw(pageH)}"/>""" +
            """<w:pgMar w:top="${tw(mt)}" w:right="${tw(mr)}" w:bottom="${tw(mb)}" w:left="${tw(ml)}" w:header="360" w:footer="360" w:gutter="0"/>""" +
            colsXml(c) + "</w:sectPr>"

        val media = mutableListOf<Pair<String, ByteArray>>()
        mediaSink.set(media)
        val body = StringBuilder()
        var first = true
        var lastWasTable = false
        var sec: Cols? = null      // açık iki sütunlu bölüm
        var secPage = 0
        var secCol = 1
        var since = false          // son bölüm sonundan beri içerik yazıldı mı
        fun endSection(c: Cols?) {
            body.append("""<w:p><w:pPr><w:spacing w:before="0" w:after="0" w:line="20" w:lineRule="exact"/>""").append(sectPr(c)).append("</w:pPr></w:p>")
            since = false
        }
        fun withBreaks(xml: String, n: Int, page: Boolean = false): String {
            if (n <= 0 && !page) return xml
            val br = (if (page) """<w:r><w:br w:type="page"/></w:r>""" else "") + """<w:r><w:br w:type="column"/></w:r>""".repeat(n.coerceAtLeast(0))
            if (xml.startsWith("<w:p>")) {
                val e = xml.indexOf("</w:pPr>")
                if (e >= 0 && e < xml.indexOf("</w:p>").let { if (it < 0) Int.MAX_VALUE else it }) {
                    return xml.substring(0, e + 8) + br + xml.substring(e + 8)
                }
                return "<w:p>" + br + xml.substring(5)
            }
            return """<w:p><w:pPr><w:spacing w:before="0" w:after="0"/></w:pPr>$br</w:p>""" + xml
        }

        for ((pi, items) in pages.withIndex()) {
            if (pi in ocr && !first) {
                sec?.let { endSection(it); sec = null }
                body.append("""<w:p><w:r><w:br w:type="page"/></w:r></w:p>""")
                since = true
            }
            val pc = cols[pi]
            items.forEachIndexed { ii, item ->
                first = false
                val next = items.getOrNull(ii + 1)?.takeIf { it.col == item.col }
                lastWasTable = item.table != null
                val ic = if (pc != null) item.col else 0
                var breaks = 0
                // sütunlu belgelerde her PDF sayfası yeni Word sayfasında başlar (bir sütun taşsa bile sonrakiler kaymaz)
                val newPage = cols.isNotEmpty() && ii == 0 && since && pi > 0
                var pageBreak = false
                if (ic == 0) {
                    sec?.let { endSection(it); sec = null }
                    pageBreak = newPage
                } else {
                    val open = sec
                    // yakın geometrili sayfalar aynı bölümde kalır
                    val same = open != null && abs((open.leftR + open.rightL) - (pc!!.leftR + pc.rightL)) / 2 < 30 &&
                        pc.leftR < open.rightL && open.leftR < pc.rightL
                    if (same) {
                        sec = Cols(min(open!!.leftL, pc!!.leftL), max(open.leftR, pc.leftR), min(open.rightL, pc.rightL), max(open.rightR, pc.rightR))
                    } else {
                        if (open != null) endSection(open) else if (since) endSection(null)
                        sec = pc; secPage = pi; secCol = 1
                    }
                    if (newPage || pi > secPage) { pageBreak = true; breaks = ic - 1 }
                    else breaks = (ic - secCol).coerceAtLeast(0)
                    secPage = pi; secCol = ic
                }
                val g = sec
                val docL = if (ic == 2 && g != null) g.rightL else ml
                val docR = if (ic == 1 && g != null) g.leftR else pageW - mr
                val regionW = docR - docL
                val xml = StringBuilder()
                when {
                    item.table != null -> xml.append(tableXml(item.table, docL, bodyHalf, bodyFamily))
                    item.para != null -> xml.append(paraXml(item.para, next?.top, docL, docR, bodySize, bodyHalf, bodyFamily))
                    item.img != null -> {
                        val im = item.img
                        val centered = abs((im.l + im.w / 2) - (docL + regionW / 2)) < regionW * 0.08f
                        xml.append("<w:p><w:pPr><w:spacing w:before=\"60\" w:after=\"120\"/>")
                        if (centered) xml.append("""<w:jc w:val="center"/>""")
                        else if (im.l - docL > 4) xml.append("""<w:ind w:left="${tw(im.l - docL)}"/>""")
                        xml.append("</w:pPr>").append(drawingRun(im, regionW)).append("</w:p>")
                    }
                    item.ocr != null -> {
                        val (t, s) = item.ocr
                        val half = (s * 2).roundToInt()
                        xml.append("<w:p>")
                        if (s >= bodySize * 1.4f && t.length < 120) xml.append("""<w:pPr><w:pStyle w:val="Heading1"/></w:pPr>""")
                        xml.append("<w:r>")
                        if (half != bodyHalf) xml.append("""<w:rPr><w:sz w:val="$half"/><w:szCs w:val="$half"/></w:rPr>""")
                        xml.append("""<w:t xml:space="preserve">""").append(esc(t)).append("</w:t></w:r></w:p>")
                    }
                }
                body.append(withBreaks(xml.toString(), breaks, pageBreak))
                since = true
            }
        }
        if (body.isEmpty() || lastWasTable) body.append("<w:p/>")
        body.append(sectPr(sec).replace("""<w:type w:val="continuous"/>""", if (cols.isEmpty()) "" else """<w:type w:val="continuous"/>"""))

        val ns = """xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture""""
        val document = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:document $ns><w:body>$body</w:body></w:document>"""

        val fam = esc(bodyFamily ?: "Calibri")
        val styles = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii="$fam" w:hAnsi="$fam" w:cs="$fam" w:eastAsia="$fam"/><w:sz w:val="$bodyHalf"/><w:szCs w:val="$bodyHalf"/><w:lang w:val="tr-TR"/></w:rPr></w:rPrDefault><w:pPrDefault><w:pPr><w:spacing w:after="0" w:line="240" w:lineRule="auto"/></w:pPr></w:pPrDefault></w:docDefaults><w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/><w:qFormat/></w:style><w:style w:type="paragraph" w:styleId="Heading1"><w:name w:val="heading 1"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:qFormat/><w:pPr><w:keepNext/><w:outlineLvl w:val="0"/></w:pPr></w:style><w:style w:type="paragraph" w:styleId="Heading2"><w:name w:val="heading 2"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:qFormat/><w:pPr><w:keepNext/><w:outlineLvl w:val="1"/></w:pPr></w:style></w:styles>"""

        val rels = StringBuilder("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rStyles" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>""")
        media.forEachIndexed { i, (name, _) ->
            rels.append("""<Relationship Id="rImg${i + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="media/$name"/>""")
        }
        rels.append("</Relationships>")

        ZipOutputStream(out.outputStream().buffered()).use { z ->
            fun put(name: String, bytes: ByteArray) { z.putNextEntry(ZipEntry(name)); z.write(bytes); z.closeEntry() }
            fun put(name: String, s: String) = put(name, s.toByteArray(Charsets.UTF_8))
            put("[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Default Extension="png" ContentType="image/png"/><Default Extension="jpeg" ContentType="image/jpeg"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/><Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/></Types>""")
            put("_rels/.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>""")
            put("word/_rels/document.xml.rels", rels.toString())
            put("word/styles.xml", styles)
            put("word/document.xml", document)
            for ((name, bytes) in media) put("word/media/$name", bytes)
        }
        mediaSink.remove()
    }

    private fun tableXml(t: Table, docL: Float, bodyHalf: Int, bodyFamily: String?): String {
        val widths = t.cols.zipWithNext { a, b -> tw(b - a) }
        val sb = StringBuilder("<w:tbl><w:tblPr>")
        sb.append("""<w:tblW w:w="${widths.sum()}" w:type="dxa"/>""")
        if (abs(t.l - docL) > 2) sb.append("""<w:tblInd w:w="${tw(t.l - docL)}" w:type="dxa"/>""")
        val c = if (isBlackish(t.borderColor)) "auto" else hex(t.borderColor)
        sb.append("<w:tblBorders>")
        val h = if (t.hBorders) """w:val="single" w:sz="4" w:space="0" w:color="$c"""" else """w:val="nil""""
        val v = if (t.vBorders) """w:val="single" w:sz="4" w:space="0" w:color="$c"""" else """w:val="nil""""
        sb.append("<w:top $h/><w:left $v/><w:bottom $h/><w:right $v/><w:insideH $h/><w:insideV $v/>")
        sb.append("</w:tblBorders>")
        sb.append("""<w:tblLayout w:type="fixed"/><w:tblCellMar><w:top w:w="40" w:type="dxa"/><w:left w:w="80" w:type="dxa"/><w:bottom w:w="40" w:type="dxa"/><w:right w:w="80" w:type="dxa"/></w:tblCellMar>""")
        sb.append("""<w:tblLook w:val="0000" w:firstRow="0" w:lastRow="0" w:firstColumn="0" w:lastColumn="0" w:noHBand="1" w:noVBand="1"/>""")
        sb.append("</w:tblPr><w:tblGrid>")
        for (w in widths) sb.append("""<w:gridCol w:w="$w"/>""")
        sb.append("</w:tblGrid>")
        for (row in t.rows) {
            sb.append("<w:tr>")
            if (row.header) sb.append("<w:trPr><w:tblHeader/><w:cantSplit/></w:trPr>") else sb.append("<w:trPr><w:cantSplit/></w:trPr>")
            row.cells.forEachIndexed { i, cell ->
                sb.append("""<w:tc><w:tcPr><w:tcW w:w="${widths[i]}" w:type="dxa"/>""")
                cell.fill?.let { sb.append("""<w:shd w:val="clear" w:color="auto" w:fill="${hex(it)}"/>""") }
                sb.append("""<w:vAlign w:val="center"/></w:tcPr><w:p><w:pPr><w:spacing w:before="0" w:after="0"/>""")
                if (cell.align != "left") sb.append("""<w:jc w:val="${cell.align}"/>""")
                sb.append("</w:pPr>")
                if (cell.lines.isNotEmpty()) sb.append(runsXml(cell.lines, bodyFamily, bodyHalf))
                sb.append("</w:p></w:tc>")
            }
            sb.append("</w:tr>")
        }
        sb.append("</w:tbl>")
        return sb.toString()
    }

    private fun paraXml(p: Para, nextTop: Float?, docL: Float, docR: Float, bodySize: Float, bodyHalf: Int, bodyFamily: String?): String {
        val ls = p.lines
        val f = ls.first()
        val last = ls.last()
        val size = f.size
        val text = ls.joinToString(" ") { it.text }
        val sb = StringBuilder("<w:p><w:pPr>")

        val heading = when {
            text.length > 160 || ls.size > 3 -> null
            size >= bodySize * 1.45f -> "Heading1"
            size >= bodySize * 1.18f && f.bold -> "Heading2"
            else -> null
        }
        if (heading != null) sb.append("""<w:pStyle w:val="$heading"/>""")

        val box = f.box
        if (box != null || p.bottomRule != null) {
            sb.append("<w:pBdr>")
            if (box != null && box.stroke) {
                val sideSpace = (box.contentL - box.l).roundToInt().coerceIn(1, 31)
                sb.append("""<w:top w:val="single" w:sz="4" w:space="4" w:color="A6A6A6"/>""")
                sb.append("""<w:left w:val="single" w:sz="4" w:space="$sideSpace" w:color="A6A6A6"/>""")
                sb.append("""<w:bottom w:val="single" w:sz="4" w:space="4" w:color="A6A6A6"/>""")
                sb.append("""<w:right w:val="single" w:sz="4" w:space="$sideSpace" w:color="A6A6A6"/>""")
            } else p.bottomRule?.let { r ->
                val c = if (isBlackish(r.color)) "auto" else hex(r.color)
                sb.append("""<w:bottom w:val="single" w:sz="6" w:space="4" w:color="$c"/>""")
            }
            sb.append("</w:pBdr>")
        }
        box?.fill?.let { sb.append("""<w:shd w:val="clear" w:color="auto" w:fill="${hex(it)}"/>""") }

        // satır aralığı ve paragraf sonrası boşluk
        val after = if (nextTop != null && nextTop > last.bottom) tw(nextTop - last.bottom - size * 0.15f).coerceIn(0, 720) else 120
        val lineVal = if (ls.size >= 2) {
            val avg = (last.base - f.base) / (ls.size - 1)
            (avg / (size * 1.17f) * 240).roundToInt().coerceIn(240, 480)
        } else 240
        val before = if (heading != null) 60 else 0
        sb.append("""<w:spacing w:before="$before" w:after="$after" w:line="$lineVal" w:lineRule="auto"/>""")

        // girintiler
        if (p.align != "center" || box != null) {
            val contLeft = if (box != null) box.contentL else if (ls.size >= 2) ls[1].x0 else f.x0
            val left = if (p.align == "center" && box != null) box.contentL - docL else contLeft - docL
            val right = if (box != null) docR - box.contentR else 0f
            val firstLine = if (p.align == "center") 0f else f.x0 - contLeft
            val attrs = StringBuilder()
            if (abs(left) > 2) attrs.append(""" w:left="${tw(left)}"""")
            if (right > 2) attrs.append(""" w:right="${tw(right)}"""")
            if (firstLine > size * 0.5f) attrs.append(""" w:firstLine="${tw(firstLine)}"""")
            else if (firstLine < -size * 0.5f) attrs.append(""" w:hanging="${tw(-firstLine)}"""")
            if (attrs.isNotEmpty()) sb.append("<w:ind").append(attrs).append("/>")
        }
        if (p.align != "left") sb.append("""<w:jc w:val="${p.align}"/>""")
        sb.append("</w:pPr>")
        sb.append(runsXml(ls, bodyFamily, bodyHalf, docR - docL))
        sb.append("</w:p>")
        return sb.toString()
    }
}
