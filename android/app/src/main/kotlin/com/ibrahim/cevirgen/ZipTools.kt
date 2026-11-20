package com.ibrahim.cevirgen

import java.io.File
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Dosyaları tek bir ZIP'te toplar. Telefonda, internetsiz. */
object ZipTools {

    /** Zaten sıkıştırılmış biçimler: tekrar sıkıştırmak boyutu küçültmez, sadece yavaşlatır. */
    private val alreadyCompressed = setOf(
        "jpg", "jpeg", "png", "webp", "heic", "heif", "gif",
        "mp4", "mov", "mkv", "webm", "3gp", "mp3", "m4a", "aac", "ogg", "opus", "flac",
        "zip", "rar", "7z", "gz", "apk", "docx", "xlsx", "pptx", "odt",
    )

    fun zip(paths: List<String>, out: File, progress: (Double) -> Unit): File {
        val files = paths.map { File(it) }
        val total = files.sumOf { it.length() }.coerceAtLeast(1)
        var done = 0L
        val used = HashSet<String>()
        val buf = ByteArray(1 shl 16)
        ZipOutputStream(out.outputStream().buffered(1 shl 16)).use { z ->
            for (f in files) {
                // Aynı adlı iki dosya varsa: "rapor.pdf", "rapor (2).pdf"
                var name = f.name
                var n = 2
                while (!used.add(name.lowercase())) {
                    name = f.nameWithoutExtension + " ($n)" + (if (f.extension.isNotEmpty()) "." + f.extension else "")
                    n++
                }
                z.setLevel(if (f.extension.lowercase() in alreadyCompressed) Deflater.NO_COMPRESSION else Deflater.DEFAULT_COMPRESSION)
                z.putNextEntry(ZipEntry(name).apply { time = System.currentTimeMillis() })
                f.inputStream().use { input ->
                    while (true) {
                        val r = input.read(buf)
                        if (r < 0) break
                        z.write(buf, 0, r)
                        done += r
                        progress((done.toDouble() / total).coerceAtMost(0.99))
                    }
                }
                z.closeEntry()
            }
        }
        progress(1.0)
        return out
    }

    // ------------------------------------------------------------------ ZIP'i aç

    private const val MAX_ENTRIES = 5000
    private const val MAX_TOTAL = 4L * 1024 * 1024 * 1024 // açılmış toplam en fazla 4 GB
    private const val MAX_RATIO = 200                     // sıkıştırma oranı bundan büyükse "ZIP bombası" say

    /** Arşiv içi yolu güvenli hale getirir. Klasör dışına çıkmaya çalışan yol (../) null döner. */
    private fun safePath(name: String): String? {
        val parts = name.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty() || parts.any { it == ".." }) return null
        return parts.joinToString("/") { it.replace(Regex("[:*?\"<>|\\u0000-\\u001f]"), "_").trim().trimEnd('.').ifEmpty { "_" } }
    }

    private fun encrypted(msg: String?) = msg != null && (msg.contains("encrypt", true) || msg.contains("Bit Flag", true))

    private fun skip(path: String): Boolean {
        val p = path.lowercase()
        return p.startsWith("__macosx/") || p.endsWith("/.ds_store") || p == ".ds_store" || p.endsWith("thumbs.db") || p.endsWith("desktop.ini")
    }

    /** Windows'ta oluşturulmuş eski ZIP'lerde adlar UTF-8 değil (Türkçe DOS kod sayfası) olabilir. */
    private fun open(f: File): java.util.zip.ZipFile {
        for (cs in listOf("UTF-8", "IBM857", "CP437")) {
            try {
                val charset = java.nio.charset.Charset.forName(cs)
                val z = java.util.zip.ZipFile(f, charset)
                // Android çözemediği baytları hata vermeden "�" yapar: öyle ad varsa sonraki kod sayfasını dene
                if (cs != "CP437" && z.entries().toList().any { it.name.contains('�') }) { z.close(); continue }
                return z
            } catch (e: java.util.zip.ZipException) {
                if (encrypted(e.message)) throw IllegalStateException("Şifreli ZIP dosyaları açılamıyor.")
                // Android 14+: klasör dışına yazmaya çalışan girdiler ("../") olan ZIP'leri kendisi reddeder
                if (e.message?.contains("entry path", true) == true || e.message?.contains("..") == true)
                    throw IllegalStateException("Bu ZIP güvensiz dosya yolları içeriyor (klasör dışına yazmaya çalışıyor); güvenlik için açılmadı.")
                if (cs == "CP437") throw IllegalStateException("Bu dosya bozuk ya da geçerli bir ZIP değil.")
            } catch (_: IllegalArgumentException) {
                // ad çözülemedi: sonraki kod sayfasını dene
            } catch (_: java.nio.charset.UnsupportedCharsetException) {
            }
        }
        throw IllegalStateException("Bu dosya bozuk ya da geçerli bir ZIP değil.")
    }

    /**
     * ZIP'leri outDir altına açar: her arşiv kendi adında bir klasöre.
     * Klasör dışına yazmaya çalışan girdiler ve sistem çöpleri (__MACOSX vb.) atlanır.
     */
    fun unzip(paths: List<String>, outDir: File, progress: (Double) -> Unit): List<File> {
        val out = mutableListOf<File>()
        val base = outDir.canonicalFile
        val zips = paths.map { File(it) }
        val totalCompressed = zips.sumOf { it.length() }.coerceAtLeast(1)
        var readCompressed = 0L
        var written = 0L
        var entries = 0
        val usedTop = HashSet<String>()
        val buf = ByteArray(1 shl 16)
        for (zf in zips) {
            // her arşiv kendi klasörüne: "arsiv", aynı adlı ikinci arşiv "arsiv (2)"
            var top = safePath(zf.nameWithoutExtension) ?: "arsiv"
            var n = 2
            while (!usedTop.add(top.lowercase())) { top = safePath(zf.nameWithoutExtension) + " ($n)"; n++ }
            open(zf).use { z ->
                for (e in z.entries()) {
                    if (e.isDirectory) continue
                    val rel = safePath(e.name) ?: continue // "../" ile kaçmaya çalışan girdi
                    if (skip(rel)) continue
                    if (++entries > MAX_ENTRIES) throw IllegalStateException("ZIP'te çok fazla dosya var (en fazla $MAX_ENTRIES).")
                    val dest = File(base, "$top/$rel").canonicalFile
                    if (!dest.path.startsWith(base.path + File.separator)) continue
                    dest.parentFile?.mkdirs()
                    var entryBytes = 0L
                    val compressed = e.compressedSize.coerceAtLeast(1)
                    try {
                        z.getInputStream(e).use { input ->
                            dest.outputStream().use { o ->
                                while (true) {
                                    val r = input.read(buf)
                                    if (r < 0) break
                                    entryBytes += r
                                    written += r
                                    if (written > MAX_TOTAL) throw IllegalStateException("ZIP açılınca 4 GB'ı aşıyor; telefonda açılamaz.")
                                    if (entryBytes > 50L * 1024 * 1024 && entryBytes / compressed > MAX_RATIO)
                                        throw IllegalStateException("Bu ZIP şüpheli görünüyor (aşırı sıkıştırılmış); güvenlik için açılmadı.")
                                    o.write(buf, 0, r)
                                }
                            }
                        }
                    } catch (ex: java.util.zip.ZipException) {
                        if (encrypted(ex.message)) throw IllegalStateException("Şifreli ZIP dosyaları açılamıyor.")
                        throw IllegalStateException("ZIP bozuk: ${e.name} açılamadı.")
                    }
                    if (e.time > 0) dest.setLastModified(e.time)
                    out += dest
                    readCompressed += e.compressedSize.coerceAtLeast(0)
                    progress((readCompressed.toDouble() / totalCompressed).coerceIn(0.0, 0.99))
                }
            }
        }
        if (out.isEmpty()) throw IllegalStateException("ZIP'in içinde açılacak dosya yok.")
        progress(1.0)
        return out
    }
}
