package com.ibrahim.cevirgen

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID

/**
 * Dosya seçme, paylaşımdan gelen dosyaları alma, İndirilenler'e kaydetme,
 * paylaşma ve açma. Gelen her dosya önce uygulamanın kendi önbelleğine kopyalanır;
 * hiçbir dosya internete gönderilmez.
 */
class FileBridge(private val ctx: Context) {

    val inDir: File get() = File(ctx.cacheDir, "in").apply { mkdirs() }
    val outRoot: File get() = File(ctx.cacheDir, "out").apply { mkdirs() }

    fun newOutDir(): File = File(outRoot, UUID.randomUUID().toString()).apply { mkdirs() }

    /** content:// adresindeki dosyayı önbelleğe kopyalar ve bilgisini döndürür. */
    fun importUri(uri: Uri): Map<String, Any?> {
        var name: String? = null
        var size: Long = -1
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { c ->
                if (c.moveToFirst()) {
                    name = c.getString(0)
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
        val mime = ctx.contentResolver.getType(uri)
        var safe = (name ?: uri.lastPathSegment ?: "dosya").replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").take(150)
        if (!safe.contains('.')) {
            MimeTypeMap.getSingleton().getExtensionFromMimeType(mime ?: "")?.let { safe = "$safe.$it" }
        }
        val dir = File(inDir, UUID.randomUUID().toString()).apply { mkdirs() }
        val dest = File(dir, safe)
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { input.copyTo(it, 1 shl 16) }
        } ?: throw IllegalStateException("Dosya okunamadı: $safe")
        return describe(dest, mime)
    }

    fun describe(f: File, mime: String? = null): Map<String, Any?> = mapOf(
        "path" to f.absolutePath,
        "name" to f.name,
        "size" to f.length(),
        "mime" to (mime ?: mimeOf(f.name)),
    )

    fun mimeOf(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "doc" -> "application/msword"
            "heic", "heif" -> "image/heic"
            "m4a" -> "audio/mp4"
            "zip" -> "application/zip"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        }
    }

    /** Paylaş menüsünden gelen niyetteki tüm dosyaları içe aktarır. */
    fun urisFromShareIntent(intent: Intent?): List<Uri> {
        if (intent == null) return emptyList()
        val list = mutableListOf<Uri>()
        when (intent.action) {
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                (intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))?.let { list += it }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { list += it }
            }
        }
        if (list.isEmpty()) intent.clipData?.let { cd -> for (i in 0 until cd.itemCount) cd.getItemAt(i).uri?.let { list += it } }
        return list.distinct()
    }

    /** Klasör adını güvenli hale getirir: "a/b" → yalnızca izinli karakterler, ".." yok. */
    private fun safeFolder(folder: String): String = folder.replace('\\', '/').split('/')
        .map { it.replace(Regex("[:*?\"<>|\\u0000-\\u001f]"), "_").trim().trimEnd('.') }
        .filter { it.isNotEmpty() && it != "." && it != ".." }
        .joinToString("/")

    /** Çıktıyı İndirilenler/Çevirgen klasörüne (istenirse alt klasörüne) kaydeder. */
    fun saveToDownloads(path: String, displayName: String, mime: String, folder: String = ""): Map<String, Any?> {
        val src = File(path)
        val sub = safeFolder(folder)
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Çevirgen" + (if (sub.isEmpty()) "" else "/$sub"))
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val resolver = ctx.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("İndirilenler klasörüne yazılamadı")
            resolver.openOutputStream(uri)?.use { out -> src.inputStream().use { it.copyTo(out, 1 shl 16) } }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            var finalName = displayName
            resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) finalName = c.getString(0) ?: displayName
            }
            val loc = "İndirilenler/Çevirgen" + (if (sub.isEmpty()) "" else "/" + sub.substringBefore('/'))
            return mapOf("uri" to uri.toString(), "name" to finalName, "location" to loc)
        } else {
            val dir = File(File(ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "Çevirgen"), sub).apply { mkdirs() }
            var dest = File(dir, displayName)
            var n = 1
            while (dest.exists()) {
                val base = displayName.substringBeforeLast('.')
                val ext = displayName.substringAfterLast('.', "")
                dest = File(dir, "$base ($n)${if (ext.isEmpty()) "" else ".$ext"}"); n++
            }
            src.copyTo(dest)
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", dest)
            return mapOf("uri" to uri.toString(), "name" to dest.name, "location" to "Uygulama klasörü/Çevirgen")
        }
    }

    private fun uriFor(pathOrUri: String): Uri =
        if (pathOrUri.startsWith("content://")) Uri.parse(pathOrUri)
        else FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", File(pathOrUri))

    fun shareIntent(items: List<String>, mime: String): Intent {
        val uris = ArrayList(items.map { uriFor(it) })
        val send = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply { putExtra(Intent.EXTRA_STREAM, uris[0]) }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply { putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris) }
        }
        send.type = mime
        send.clipData = ClipData.newRawUri("", uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, "Paylaş").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun openIntent(item: String, mime: String): Intent =
        Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uriFor(item), mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    /** Bir günden eski geçici dosyaları siler. */
    fun cleanup() {
        val cutoff = System.currentTimeMillis() - 24 * 3600 * 1000L
        listOf(inDir, outRoot).forEach { root ->
            root.listFiles()?.forEach { if (it.lastModified() < cutoff) it.deleteRecursively() }
        }
    }
}
