package com.zeta.board

import android.content.ClipDescription
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

/** Endpoint of the image -> sticker converter. The key comes from BuildConfig (see app/build.gradle.kts). */
object StickerConfig {
    const val API = "https://apix.wolvarex.com/api/converter/img-to-sticker"
    val KEY: String get() = BuildConfig.STICKER_KEY
}

/** A photo that is ready to be uploaded (already rotated and scaled down). */
class StickerImg(val bytes: ByteArray, val mime: String, val ext: String)

class StickerResult(val file: File, val note: String?)

/** Sticker history, kept in the app's private storage (newest first). */
object StickerRepo {
    private const val MAX = 40

    fun dir(c: Context): File = File(c.filesDir, "stickers").also { it.mkdirs() }

    fun list(c: Context): List<File> {
        val all = dir(c).listFiles { f -> f.isFile && f.extension == "webp" } ?: return emptyList()
        return all.sortedByDescending { it.lastModified() }
    }

    fun save(c: Context, bytes: ByteArray): File {
        val f = File(dir(c), "zs_" + System.currentTimeMillis() + ".webp")
        f.writeBytes(bytes)
        list(c).drop(MAX).forEach { remove(it) }
        return f
    }

    fun remove(f: File) {
        try {
            f.delete()
            File(File(f.parentFile, "png"), f.nameWithoutExtension + ".png").delete()
        } catch (e: Exception) {
        }
    }

    /** PNG copy of a sticker, for apps that only accept PNG from keyboards. */
    fun png(c: Context, webp: File): File {
        val dir = File(dir(c), "png").also { it.mkdirs() }
        val out = File(dir, webp.nameWithoutExtension + ".png")
        if (out.exists() && out.length() > 0) return out
        val bmp = BitmapFactory.decodeFile(webp.path) ?: throw IOException("Bad sticker")
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return out
    }
}

/** Turns a photo into a WhatsApp-ready sticker: upload -> API -> clean 512x512 WebP (< 100 KB). */
object StickerMaker {
    private const val UA = "Mozilla/5.0 (Linux; Android 13; Zeta Board) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"
    private const val SIZE = 512
    private const val MAX_BYTES = 100 * 1024
    private const val MAX_SRC = 1280

    /** Reads a picked image, fixes its rotation and scales it down so the upload is small and fast. */
    fun prepare(c: Context, uri: Uri): StickerImg {
        val cr = c.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("Not an image")
        var sample = 1
        while (bounds.outWidth / sample > 2 * MAX_SRC || bounds.outHeight / sample > 2 * MAX_SRC) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val src = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: throw IOException("Can't read image")

        val orient = try {
            cr.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) } ?: 1
        } catch (e: Exception) {
            1
        }
        val m = Matrix()
        when (orient) {
            2 -> m.postScale(-1f, 1f)
            3 -> m.postRotate(180f)
            4 -> m.postScale(1f, -1f)
            5 -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            6 -> m.postRotate(90f)
            7 -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            8 -> m.postRotate(270f)
        }
        val big = maxOf(src.width, src.height)
        if (big > MAX_SRC) {
            val f = MAX_SRC.toFloat() / big
            m.postScale(f, f)
        }
        val bmp = if (m.isIdentity) src else Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)

        val out = ByteArrayOutputStream()
        return if (bmp.hasAlpha()) {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            StickerImg(out.toByteArray(), "image/png", "png")
        } else {
            bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
            StickerImg(out.toByteArray(), "image/jpeg", "jpg")
        }
    }

    fun make(c: Context, img: StickerImg, progress: (String) -> Unit): StickerResult {
        var note: String? = null
        val raw: ByteArray = try {
            progress("Uploading photo…")
            val url = upload(img)
            progress("Creating sticker…")
            convert(url)
        } catch (e: Exception) {
            // The online converter is not reachable: still give the user a sticker.
            note = "Made offline (" + brief(e.message ?: "no connection") + ")"
            progress("Creating sticker…")
            img.bytes
        }
        return StickerResult(StickerRepo.save(c, normalize(raw)), note)
    }

    // ---------- network ----------

    private fun upload(img: StickerImg): String {
        try {
            return post("https://catbox.moe/user/api.php", mapOf("reqtype" to "fileupload"), img)
        } catch (e: Exception) {
            return post(
                "https://litterbox.catbox.moe/resources/internals/api.php",
                mapOf("reqtype" to "fileupload", "time" to "1h"),
                img
            )
        }
    }

    private fun post(endpoint: String, fields: Map<String, String>, img: StickerImg): String {
        val b = "----ZetaBoard" + System.nanoTime()
        val head = StringBuilder()
        for ((k, v) in fields) head.append("--$b\r\nContent-Disposition: form-data; name=\"$k\"\r\n\r\n$v\r\n")
        head.append("--$b\r\nContent-Disposition: form-data; name=\"fileToUpload\"; filename=\"photo.${img.ext}\"\r\n")
        head.append("Content-Type: ${img.mime}\r\n\r\n")
        val hb = head.toString().toByteArray(Charsets.UTF_8)
        val tb = "\r\n--$b--\r\n".toByteArray(Charsets.UTF_8)
        val c = URL(endpoint).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = 15000
            c.readTimeout = 60000
            c.setRequestProperty("User-Agent", UA)
            c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$b")
            c.setFixedLengthStreamingMode(hb.size + img.bytes.size + tb.size)
            c.outputStream.use {
                it.write(hb)
                it.write(img.bytes)
                it.write(tb)
            }
            val code = c.responseCode
            val body = readCapped(if (code < 400) c.inputStream else c.errorStream, 64 * 1024)
                .toString(Charsets.UTF_8).trim()
            if (code >= 400 || !body.startsWith("http")) throw IOException("Upload failed " + code + " " + brief(body))
            return body.lines().first().trim()
        } finally {
            c.disconnect()
        }
    }

    private fun get(url: String): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15000
            c.readTimeout = 60000
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", UA)
            c.setRequestProperty("Accept", "image/webp,image/*,application/json,*/*")
            val code = c.responseCode
            val data = readCapped(if (code < 400) c.inputStream else c.errorStream, 12 * 1024 * 1024)
            if (code >= 400) throw IOException("Server " + code + " " + brief(String(data, Charsets.UTF_8)))
            return data
        } finally {
            c.disconnect()
        }
    }

    /** Calls the sticker API. It may answer with the image itself or with JSON that holds a link to it. */
    private fun convert(src: String): ByteArray {
        val q = StickerConfig.API + "?url=" + enc(src) + "&key=" + enc(StickerConfig.KEY)
        val r = get(q)
        if (isImage(r)) return r
        val text = String(r, Charsets.UTF_8).trim()
        val link = findLink(text, src) ?: throw IOException(brief(text))
        val r2 = get(link)
        if (!isImage(r2)) throw IOException("Bad sticker data")
        return r2
    }

    private fun findLink(text: String, skip: String): String? {
        if (text.startsWith("http")) return text.lines().first().trim()
        val found = ArrayList<String>()
        fun walk(o: Any?) {
            when (o) {
                is JSONObject -> o.keys().forEach { walk(o.opt(it)) }
                is JSONArray -> for (i in 0 until o.length()) walk(o.opt(i))
                is String -> if (o.startsWith("http") && o != skip) found.add(o)
            }
        }
        try {
            walk(JSONTokener(text).nextValue())
        } catch (e: Exception) {
            return null
        }
        return found.firstOrNull { it.contains(".webp", true) } ?: found.firstOrNull()
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun brief(s: String): String {
        val t = s.replace(Regex("\\s+"), " ").trim()
        return if (t.length > 70) t.substring(0, 70) + "…" else t
    }

    private fun readCapped(ins: InputStream?, cap: Int): ByteArray {
        if (ins == null) return ByteArray(0)
        ins.use {
            val out = ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = it.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > cap) throw IOException("Response too large")
            }
            return out.toByteArray()
        }
    }

    // ---------- image helpers ----------

    private fun isWebp(b: ByteArray) = b.size > 12 && b[0] == 'R'.code.toByte() && b[1] == 'I'.code.toByte() &&
        b[2] == 'F'.code.toByte() && b[3] == 'F'.code.toByte() && b[8] == 'W'.code.toByte() &&
        b[9] == 'E'.code.toByte() && b[10] == 'B'.code.toByte() && b[11] == 'P'.code.toByte()

    private fun isPng(b: ByteArray) = b.size > 8 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() &&
        b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte()

    private fun isJpeg(b: ByteArray) = b.size > 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte()

    private fun isImage(b: ByteArray) = isWebp(b) || isPng(b) || isJpeg(b)

    private fun isAnimatedWebp(b: ByteArray) = isWebp(b) && b.size > 21 && b[12] == 'V'.code.toByte() &&
        b[13] == 'P'.code.toByte() && b[14] == '8'.code.toByte() && b[15] == 'X'.code.toByte() &&
        (b[20].toInt() and 0x02) != 0

    /**
     * Makes sure the result is a valid WhatsApp sticker: 512x512, transparent padding, under 100 KB.
     * Animated stickers are left untouched. A sticker that already fits is kept as the API made it.
     */
    private fun normalize(raw: ByteArray): ByteArray {
        if (isAnimatedWebp(raw)) return raw
        val bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: throw IOException("Bad image")
        if (isWebp(raw) && bmp.width == SIZE && bmp.height == SIZE && raw.size <= MAX_BYTES) return raw

        val out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val cv = Canvas(out)
        val sc = minOf(SIZE.toFloat() / bmp.width, SIZE.toFloat() / bmp.height)
        val w = bmp.width * sc
        val h = bmp.height * sc
        val l = (SIZE - w) / 2f
        val t = (SIZE - h) / 2f
        cv.drawBitmap(bmp, null, RectF(l, t, l + w, t + h), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))

        val fmt = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else legacyWebp()
        var q = 92
        while (true) {
            val bo = ByteArrayOutputStream()
            out.compress(fmt, q, bo)
            if (bo.size() <= MAX_BYTES || q <= 30) return bo.toByteArray()
            q -= 10
        }
    }

    @Suppress("DEPRECATION")
    private fun legacyWebp(): Bitmap.CompressFormat = Bitmap.CompressFormat.WEBP
}

/** One place that knows about the running job, so the keyboard, the picker screen and the share target stay in sync. */
object StickerHub {
    interface Listener {
        fun onStatus(text: String)
        fun onDone(f: File, note: String?)
        fun onFail(msg: String)
        fun onIdle()
    }

    @Volatile var listener: Listener? = null

    /** True when the sticker panel should open the next time the keyboard shows up. */
    @Volatile var pendingShow = false

    @Volatile var status = ""
    @Volatile private var busyFlag = false
    @Volatile private var since = 0L
    @Volatile private var app: Context? = null

    val busy: Boolean get() = busyFlag && SystemClock.elapsedRealtime() - since < 180_000

    private val main = Handler(Looper.getMainLooper())
    private val exec = Executors.newSingleThreadExecutor()

    private fun post(r: () -> Unit) {
        main.post { r() }
    }

    fun begin(text: String) {
        busyFlag = true
        since = SystemClock.elapsedRealtime()
        status = text
        post { listener?.onStatus(text) }
    }

    fun cancel() {
        busyFlag = false
        post { listener?.onIdle() }
    }

    fun fail(msg: String) {
        busyFlag = false
        post {
            val l = listener
            if (l != null) l.onFail(msg) else app?.let { Toast.makeText(it, msg, Toast.LENGTH_LONG).show() }
        }
    }

    fun submitImg(ctx: Context, img: StickerImg) = launch(ctx) { img }

    fun submitUri(ctx: Context, uri: Uri) = launch(ctx) { StickerMaker.prepare(ctx.applicationContext, uri) }

    private fun launch(ctx: Context, produce: () -> StickerImg) {
        val c = ctx.applicationContext
        app = c
        begin("Reading photo…")
        exec.execute {
            val img = try {
                produce()
            } catch (e: Throwable) {
                fail("Couldn't read that image")
                return@execute
            }
            try {
                val r = StickerMaker.make(c, img) { s ->
                    status = s
                    post { listener?.onStatus(s) }
                }
                busyFlag = false
                pendingShow = true
                post {
                    val l = listener
                    if (l != null) l.onDone(r.file, r.note)
                    else app?.let { Toast.makeText(it, "Sticker ready — open the sticker panel on Zeta Board", Toast.LENGTH_LONG).show() }
                }
            } catch (e: Throwable) {
                fail("Sticker failed: " + (e.message ?: "unknown error"))
            }
        }
    }
}

/** Sends a finished sticker into the app that is being typed in (WhatsApp etc.). */
object StickerSender {
    fun send(c: Context, ic: InputConnection, ei: EditorInfo, webp: File): Boolean {
        val types = EditorInfoCompat.getContentMimeTypes(ei)
        if (types.isEmpty()) return false
        fun accepts(m: String) = types.any { ClipDescription.compareMimeTypes(m, it) }
        val file: File
        val mime: String
        when {
            accepts("image/webp.wasticker") -> { file = webp; mime = "image/webp.wasticker" }
            accepts("image/webp") -> { file = webp; mime = "image/webp" }
            accepts("image/png") -> { file = StickerRepo.png(c, webp); mime = "image/png" }
            else -> return false
        }
        val uri = uriFor(c, file)
        val info = InputContentInfoCompat(uri, ClipDescription("Sticker", arrayOf(mime)), null)
        val flags = if (Build.VERSION.SDK_INT >= 25) InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION else 0
        return InputConnectionCompat.commitContent(ic, ei, info, flags, null)
    }

    /** Photo or video (a saved status) straight into the chat box, when the app accepts that file type. */
    fun sendMedia(c: Context, ic: InputConnection, ei: EditorInfo, f: File, mime: String): Boolean {
        val types = EditorInfoCompat.getContentMimeTypes(ei)
        if (types.isEmpty()) return false
        if (!types.any { ClipDescription.compareMimeTypes(mime, it) }) return false
        val info = InputContentInfoCompat(uriFor(c, f), ClipDescription("Status", arrayOf(mime)), null)
        val flags = if (Build.VERSION.SDK_INT >= 25) InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION else 0
        return InputConnectionCompat.commitContent(ic, ei, info, flags, null)
    }

    fun uriFor(c: Context, f: File): Uri = FileProvider.getUriForFile(c, c.packageName + ".fileprovider", f)
}
