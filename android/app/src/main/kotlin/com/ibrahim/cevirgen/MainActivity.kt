package com.ibrahim.cevirgen

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.io.File
import java.util.concurrent.Executors

/**
 * Flutter arayüzü ile yerel (Android) araçlar arasındaki köprü: "cevirgen/native" kanalı.
 * Ağır işler tek bir arka plan iş parçacığında sırayla çalışır; her iş iptal edilebilir ve
 * uygulamadan çıkılsa da ön plan servisi sayesinde sürer.
 */
class MainActivity : FlutterActivity() {

    private lateinit var channel: MethodChannel
    private lateinit var files: FileBridge
    private lateinit var media: MediaTools
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var pendingPick: MethodChannel.Result? = null
    private var pendingShared: List<Map<String, Any?>>? = null
    private var dartAsked = false

    companion object {
        private const val PICK = 7101
        private const val NOTIF_PERM = 7102
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        PDFBoxResourceLoader.init(applicationContext)
        files = FileBridge(applicationContext)
        media = MediaTools(applicationContext)
        channel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "cevirgen/native")
        channel.setMethodCallHandler(::handle)
        io.execute { try { files.cleanup() } catch (_: Exception) {} }
        importShared(intent, notify = false)
    }

    override fun onResume() { super.onResume(); Jobs.appVisible = true }
    override fun onPause() { super.onPause(); Jobs.appVisible = false }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        importShared(intent, notify = true)
    }

    /** "Paylaş → Çevirgen" ile gelen dosyaları içe aktarır. */
    private fun importShared(intent: Intent?, notify: Boolean) {
        val uris = files.urisFromShareIntent(intent)
        if (uris.isEmpty()) return
        intent?.action = Intent.ACTION_MAIN // aynı paylaşım iki kez işlenmesin
        io.execute {
            val list = uris.mapNotNull { u ->
                try { files.importUri(u) } catch (e: Exception) { android.util.Log.w("Cevirgen", "Paylaşılan dosya alınamadı: $u", e); null }
            }
            val failed = uris.size - list.size
            main.post {
                // Arayüz hazırsa doğrudan gönder; değilse arayüz sorduğunda (takeShared) teslim edilir.
                if (notify || dartAsked) channel.invokeMethod("shared", list) else pendingShared = list
                if (failed > 0) channel.invokeMethod("sharedFailed", failed)
            }
        }
    }

    /** Bildirim izni (Android 13+): ilk uzun işte bir kez sorulur. */
    private fun askNotificationPermissionOnce() {
        if (Build.VERSION.SDK_INT < 33) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        val prefs = getSharedPreferences("native", MODE_PRIVATE)
        if (prefs.getBoolean("notifAsked", false)) return
        prefs.edit().putBoolean("notifAsked", true).apply()
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIF_PERM)
    }

    private fun progress(job: String, value: Double) {
        Jobs.progress(applicationContext, job, value)
        main.post { channel.invokeMethod("progress", mapOf("job" to job, "value" to value)) }
    }

    private fun fail(result: MethodChannel.Result, msg: String?) {
        if (msg == Jobs.CANCELLED) result.error("CANCELLED", "İptal edildi", null)
        else result.error("FAILED", msg ?: "Dönüştürülemedi", null)
    }

    /**
     * Arka planda çalışan bir dönüştürme işi: yeni çıktı klasörü açar, ilerlemeyi bildirir,
     * iptali denetler; hata ya da iptalde yarım kalan çıktıyı siler.
     */
    private fun work(
        result: MethodChannel.Result, job: String, title: String,
        extras: () -> Map<String, Any?> = { emptyMap() },
        block: (dir: File, p: (Double) -> Unit) -> List<File>,
    ) {
        askNotificationPermissionOnce()
        Jobs.begin(applicationContext, job, title)
        io.execute {
            val dir = files.newOutDir()
            try {
                Jobs.checkCancelled(job)
                val out = block(dir) { v -> Jobs.checkCancelled(job); progress(job, v) }
                val ex = extras()
                val base = dir.canonicalFile
                // rel: çıktı klasörüne göre yol (ZIP'ten çıkan dosyaların klasör yapısı için)
                val list = out.map { f ->
                    files.describe(f) + ex + ("rel" to (f.canonicalFile.relativeToOrNull(base)?.path ?: f.name))
                }
                Jobs.end(applicationContext, job, title)
                main.post { result.success(list) }
            } catch (e: Throwable) {
                dir.deleteRecursively()
                Jobs.end(applicationContext, job, null)
                val msg = if (e is Jobs.Cancelled) Jobs.CANCELLED else (e.message ?: e.javaClass.simpleName)
                main.post { fail(result, msg) }
            }
        }
    }

    /** Ana iş parçacığında, geri çağrıyla biten işler (video, Word → PDF). */
    private fun asyncWork(
        result: MethodChannel.Result, job: String, title: String,
        start: (dir: File, p: (Double) -> Unit, done: (File?, String?) -> Unit) -> Unit,
    ) {
        askNotificationPermissionOnce()
        Jobs.begin(applicationContext, job, title)
        val dir = files.newOutDir()
        var answered = false
        start(dir, { v -> progress(job, v) }) { f, err ->
            main.post {
                if (answered) return@post
                answered = true
                if (f != null) {
                    Jobs.end(applicationContext, job, title)
                    result.success(listOf(files.describe(f)))
                } else {
                    dir.deleteRecursively()
                    Jobs.end(applicationContext, job, null)
                    fail(result, err)
                }
            }
        }
    }

    /** Kısa işler (sayfa sayısı, medya bilgisi, kaydetme). */
    private fun bg(result: MethodChannel.Result, block: () -> Any?) {
        io.execute {
            try {
                val v = block()
                main.post { result.success(v) }
            } catch (e: Throwable) {
                main.post { result.error("FAILED", e.message ?: e.javaClass.simpleName, null) }
            }
        }
    }

    private fun MethodCall.s(key: String) = argument<String>(key)!!

    private fun handle(call: MethodCall, result: MethodChannel.Result) {
        val job = call.argument<String>("job") ?: "job"
        val name = call.argument<String>("name") ?: "cikti"
        when (call.method) {
            "takeShared" -> { dartAsked = true; result.success(pendingShared ?: emptyList<Any>()); pendingShared = null }

            "pick" -> {
                if (pendingPick != null) { result.error("BUSY", "Seçim penceresi zaten açık", null); return }
                val mimes = (call.argument<List<String>>("mimes") ?: listOf("*/*")).toTypedArray()
                val multiple = call.argument<Boolean>("multiple") ?: false
                val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = if (mimes.size == 1) mimes[0] else "*/*"
                    if (mimes.size > 1) putExtra(Intent.EXTRA_MIME_TYPES, mimes)
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple)
                }
                pendingPick = result
                startActivityForResult(i, PICK)
            }

            "cancel" -> { Jobs.cancel(job); result.success(true) }

            "save" -> bg(result) {
                files.saveToDownloads(call.s("path"), call.s("name"), call.s("mime"), call.argument<String>("folder") ?: "")
            }
            "share" -> {
                try {
                    startActivity(files.shareIntent(call.argument<List<String>>("items")!!, call.argument<String>("mime") ?: "*/*"))
                    result.success(true)
                } catch (e: Exception) { result.error("FAILED", "Paylaşılamadı: ${e.message}", null) }
            }
            "open" -> {
                try {
                    startActivity(files.openIntent(call.s("item"), call.argument<String>("mime") ?: "*/*"))
                    result.success(true)
                } catch (e: Exception) { result.error("FAILED", "Bu dosyayı açabilecek bir uygulama bulunamadı.", null) }
            }

            // ---- PDF ----
            "pdfPages" -> bg(result) { PdfTools.pageCount(call.s("path")) }
            "pdfMerge" -> work(result, job, "PDF'ler birleştiriliyor") { dir, p ->
                listOf(PdfTools.merge(call.argument<List<String>>("paths")!!, File(dir, name), p))
            }
            "pdfExtract" -> work(result, job, "Sayfalar ayrılıyor") { dir, _ ->
                listOf(PdfTools.extract(call.s("path"), call.argument<List<Int>>("pages")!!, File(dir, name)))
            }
            "pdfSplitEach" -> work(result, job, "PDF sayfalara bölünüyor") { dir, p ->
                PdfTools.splitEach(call.s("path"), dir, call.s("base"), p)
            }
            "pdfCompress" -> work(result, job, "PDF küçültülüyor") { dir, p ->
                listOf(PdfTools.compress(call.s("path"), File(dir, name), call.argument<Int>("level") ?: 1, p))
            }
            "pdfToDocx" -> work(result, job, "PDF, Word'e dönüştürülüyor", { mapOf("ocrPages" to pdfOcrPages) }) { dir, p ->
                val r = PdfToDocx.convert(call.s("path"), File(dir, name), p)
                pdfOcrPages = r.ocrPages
                listOf(r.file)
            }

            // ---- ZIP ----
            "zip" -> work(result, job, "ZIP hazırlanıyor") { dir, p ->
                listOf(ZipTools.zip(call.argument<List<String>>("paths")!!, File(dir, name), p))
            }
            "unzip" -> work(result, job, "ZIP açılıyor") { dir, p ->
                ZipTools.unzip(call.argument<List<String>>("paths")!!, dir, p)
            }

            // ---- Fotoğraf ----
            "imageConvert" -> work(result, job, "Fotoğraflar dönüştürülüyor") { dir, p ->
                ImageTools.convert(
                    call.argument<List<String>>("paths")!!, call.argument<String>("format") ?: "jpg",
                    call.argument<Int>("quality") ?: 85, call.argument<Int>("maxSide") ?: 0, dir, p)
            }
            "imagesToPdf" -> work(result, job, "Fotoğraflar PDF yapılıyor") { dir, p ->
                listOf(ImageTools.toPdf(call.argument<List<String>>("paths")!!, call.argument<String>("pageSize") ?: "a4", File(dir, name), p))
            }

            // ---- Word → PDF ----
            "docxToPdf" -> asyncWork(result, job, "Word belgesi PDF yapılıyor") { dir, p, done ->
                p(0.2)
                val conv = DocxToPdf(this)
                conv.convert(File(call.s("path")), File(dir, name)) { f, err -> if (f != null) p(1.0); done(f, err) }
                conv.cancel?.let { c -> Jobs.setCancelAction(job, c) }
            }

            // ---- Video ve ses ----
            "mediaInfo" -> bg(result) { media.info(call.s("path")) }
            "mediaTransform" -> asyncWork(result, job, if (call.argument<Boolean>("audioOnly") == true) "Ses çıkarılıyor" else "Video dönüştürülüyor") { dir, p, done ->
                media.transform(
                    call.s("path"), File(dir, name),
                    call.argument<Int>("height") ?: 0,
                    (call.argument<Number>("startMs") ?: -1).toLong(),
                    (call.argument<Number>("endMs") ?: -1).toLong(),
                    call.argument<Boolean>("audioOnly") ?: false, p, done,
                ) { cancel -> Jobs.setCancelAction(job, cancel) }
            }
            "toMp3" -> work(result, job, "MP3'e dönüştürülüyor") { dir, p ->
                listOf(media.toMp3(call.s("path"), File(dir, name), call.argument<Int>("kbps") ?: 192, p))
            }

            else -> result.notImplemented()
        }
    }

    /** PDF → Word sonucundaki yazı tanıma sayfa sayısı (sonuç haritasına eklenir). */
    @Volatile private var pdfOcrPages = 0

    @Deprecated("startActivityForResult kullanılıyor")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PICK) return
        val res = pendingPick ?: return
        pendingPick = null
        if (resultCode != Activity.RESULT_OK || data == null) { res.success(emptyList<Any>()); return }
        val uris = mutableListOf<android.net.Uri>()
        data.clipData?.let { cd -> for (i in 0 until cd.itemCount) uris += cd.getItemAt(i).uri }
        if (uris.isEmpty()) data.data?.let { uris += it }
        bg(res) { uris.map { files.importUri(it) } }
    }
}
