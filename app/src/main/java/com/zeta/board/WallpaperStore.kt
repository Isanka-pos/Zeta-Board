package com.zeta.board

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.concurrent.Executors

/** One wallpaper from the Zeta wallpaper server. [thumb] and [full] are complete https links. */
class Wallpaper(
    val id: String,
    val name: String,
    val category: String,
    val thumb: String,
    val full: String,
    val width: Int,
    val height: Int,
    val size: Long,
    /** True for a live (video) wallpaper: [full] is an .mp4 that plays behind the keys. */
    val video: Boolean = false
)

/**
 * Talks to the Zeta wallpaper server (see the zeta-wallpaper-server folder).
 *  - fetchList:  the list of wallpapers (saved on the phone, so it still shows without internet)
 *  - loadThumb:  small preview pictures (kept in memory and on disk)
 *  - applyToKeyboard: downloads one wallpaper and makes it the keyboard background (the same background the
 *                "Choose photo" button in the app sets)
 * Every network call runs on a background thread and answers on the main thread.
 */
object WallpaperStore {

    private const val KEY_ID = "wp_id"
    private const val KEY_STAMP = "wp_stamp"

    private val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newFixedThreadPool(3)

    private val thumbCache = object : LruCache<String, Bitmap>(
        ((Runtime.getRuntime().maxMemory() / 1024L / 8L).toInt()).coerceIn(2048, 65536)
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    // ---------------------------------------------------------------- settings

    /**
     * Where the online wallpapers are downloaded from:
     *  1. your own wallpaper server, when an https address is set in app/build.gradle.kts (-PwallpaperUrl), else
     *  2. the "wallpapers" folder of this app's GitHub repository (raw.githubusercontent.com). No server needed:
     *     add a picture to that folder, list it in wallpapers.json, push, and every phone sees it.
     */
    private fun baseUrl(): String {
        val u = RemoteCfg.wallpaperUrl().trim().trimEnd('/')
        if (u.startsWith("https://") && u.length > 12 && !u.contains("YOUR-WALLPAPER-SERVER")) return u
        val repo = BuildConfig.REPO.trim()
        if (repo.contains("/")) return GITHUB_RAW + repo + "/main/wallpapers"
        return u
    }

    private const val GITHUB_RAW = "https://raw.githubusercontent.com/"

    /** The list lives at /api/wallpapers on a server, and in wallpapers.json on GitHub. */
    private fun listUrl(base: String): String =
        if (base.startsWith(GITHUB_RAW)) "$base/wallpapers.json" else "$base/api/wallpapers"

    /** False when there is neither a server address nor a GitHub repo (a build made on a computer, not by GitHub Actions). */
    fun configured(): Boolean {
        val u = baseUrl()
        return u.startsWith("https://") && u.length > 12 && !u.contains("YOUR-WALLPAPER-SERVER")
    }

    // ---------------------------------------------------------------- what is on the keyboard now

    /** True when this wallpaper is the keyboard background right now (a photo chosen later makes this false). */
    fun isActive(c: Context, w: Wallpaper): Boolean {
        if (Prefs.getS(c, KEY_ID, "") != w.id) return false
        if (w.video) return KbVideo.has(c) && Prefs.getL(c, KEY_STAMP, -1L) == KbVideo.stamp(c)
        return KbBg.has(c) && Prefs.getL(c, KEY_STAMP, -1L) == KbBg.stamp(c)
    }

    /** Removes the keyboard background picture (themes show again). */
    fun reset(c: Context) {
        KbBg.clear(c)
        KbVideo.clear(c)
        Prefs.setS(c, KEY_ID, "")
    }

    // ---------------------------------------------------------------- built-in wallpapers (packed inside the app)

    private const val ASSET = "asset:"
    private const val BUILTIN_COUNT = 14

    /** Wallpapers shipped inside the APK (app/src/main/assets/wallpapers/zeta01.jpg ...). They work offline. */
    fun builtin(): List<Wallpaper> {
        val out = ArrayList<Wallpaper>()
        for (n in 1..BUILTIN_COUNT) {
            val file = String.format("zeta%02d.jpg", n)
            out.add(Wallpaper("b_zeta" + String.format("%02d", n), "Zeta " + n, "Zeta", ASSET + "wallpapers/" + file, ASSET + "wallpapers/" + file, 0, 0, 0L))
        }
        return out
    }

    private fun isAsset(url: String) = url.startsWith(ASSET)

    private fun copyAsset(app: Context, url: String, out: File) {
        app.assets.open(url.removePrefix(ASSET)).use { input ->
            FileOutputStream(out).use { os -> input.copyTo(os) }
        }
    }

    // ---------------------------------------------------------------- list

    /** onResult(items, fromSavedCopy, error). When the server cannot be reached the saved list is used if there is one. */
    fun fetchList(c: Context, onResult: (List<Wallpaper>, Boolean, String?) -> Unit) {
        val app = c.applicationContext
        val base = baseUrl()
        Thread {
            var items: List<Wallpaper> = emptyList()
            var cached = false
            var err: String? = null
            try {
                val text = getText(listUrl(base))
                items = builtin() + parse(text, base)
                try {
                    writeCache(app, text)
                } catch (e: Exception) {
                    // not being able to save the copy is not a problem
                }
                prune(app, items)
            } catch (e: Exception) {
                err = friendly(e)
                try {
                    val f = cacheFile(app)
                    if (f.exists()) {
                        items = builtin() + parse(f.readText(), base)
                        cached = items.isNotEmpty()
                    } else {
                        items = builtin()
                    }
                } catch (e2: Exception) {
                    items = builtin()
                    cached = false
                }
            }
            main.post { onResult(items, cached, err) }
        }.start()
    }

    private fun parse(text: String, base: String): List<Wallpaper> {
        val arr = JSONObject(text).getJSONArray("items")
        val out = ArrayList<Wallpaper>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id", "")
            val thumb = o.optString("thumb", "")
            val full = o.optString("full", "")
            val isVideo = o.optString("type", "").equals("video", ignoreCase = true) || full.lowercase().endsWith(".mp4")
            // only the server's own /files/ paths are accepted
            if (id.isEmpty() || !thumb.startsWith("/files/") || !full.startsWith("/files/")) continue
            out.add(
                Wallpaper(
                    id,
                    o.optString("name", "Wallpaper").ifBlank { "Wallpaper" },
                    o.optString("category", "General").ifBlank { "General" },
                    base + thumb,
                    base + full,
                    o.optInt("width", 0),
                    o.optInt("height", 0),
                    o.optLong("size", 0L),
                    isVideo
                )
            )
        }
        return out
    }

    private fun cacheFile(app: Context): File = File(app.filesDir, "wallpapers_cache.json")

    private fun thumbDir(app: Context): File = File(app.cacheDir, "wp_thumbs")

    private fun writeCache(app: Context, text: String) {
        val tmp = File(app.filesDir, "wallpapers_cache.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(cacheFile(app))) tmp.delete()
    }

    /** Deletes saved thumbnails of wallpapers that are not in the store any more. */
    private fun prune(app: Context, items: List<Wallpaper>) {
        val keep = HashSet<String>()
        for (w in items) keep.add(w.id)
        val files = thumbDir(app).listFiles() ?: return
        for (f in files) {
            if (!keep.contains(f.name.substringBefore('.'))) f.delete()
        }
    }

    // ---------------------------------------------------------------- thumbnails

    /** The thumbnail if it is already in memory (no loading). */
    fun peekThumb(id: String): Bitmap? = thumbCache.get(id)

    /** onDone gets the thumbnail, or null when it could not be loaded. May be called at once when it is in memory. */
    fun loadThumb(c: Context, w: Wallpaper, onDone: (Bitmap?) -> Unit) {
        val hit = thumbCache.get(w.id)
        if (hit != null) {
            onDone(hit)
            return
        }
        val app = c.applicationContext
        pool.execute {
            var bmp: Bitmap? = null
            try {
                if (isAsset(w.thumb)) {
                    bmp = app.assets.open(w.thumb.removePrefix(ASSET)).use { BitmapFactory.decodeStream(it) }
                    if (bmp != null) thumbCache.put(w.id, bmp)
                    val ready = bmp
                    main.post { onDone(ready) }
                    return@execute
                }
                val dir = thumbDir(app)
                if (!dir.exists()) dir.mkdirs()
                val f = File(dir, w.id + ".img")
                if (!f.exists() || f.length() == 0L) {
                    val tmp = File(dir, w.id + "." + System.nanoTime() + ".tmp")
                    try {
                        download(w.thumb, tmp, 2L * 1024L * 1024L)
                        if (!tmp.renameTo(f)) tmp.delete()
                    } catch (e: Exception) {
                        tmp.delete()
                        throw e
                    }
                }
                bmp = decodeThumb(f)
                if (bmp == null) f.delete()
            } catch (e: Exception) {
                bmp = null
            }
            if (bmp != null) thumbCache.put(w.id, bmp)
            val out = bmp
            main.post { onDone(out) }
        }
    }

    private fun decodeThumb(f: File): Bitmap? {
        val o = BitmapFactory.Options()
        o.inJustDecodeBounds = true
        BitmapFactory.decodeFile(f.path, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) return null
        var ss = 1
        while (maxOf(o.outWidth, o.outHeight) / ss > 720) ss *= 2
        val o2 = BitmapFactory.Options()
        o2.inSampleSize = ss
        o2.inPreferredConfig = Bitmap.Config.RGB_565
        return BitmapFactory.decodeFile(f.path, o2)
    }

    // ---------------------------------------------------------------- apply

    /** Downloads the wallpaper and sets it as the keyboard background. onDone(null) = done, otherwise a short error. */
    fun applyToKeyboard(c: Context, w: Wallpaper, onDone: (String?) -> Unit) {
        val app = c.applicationContext
        Thread {
            var err: String? = null
            val tmp = File(app.cacheDir, "wp_" + w.id + ".tmp")
            try {
                if (w.video) {
                    // live wallpaper: download the video, then let KbVideo check it and make it the keyboard background
                    download(w.full, tmp, KbVideo.MAX_BYTES)
                    val msg = KbVideo.save(app, Uri.fromFile(tmp))
                    if (msg != null) throw IllegalStateException(msg)
                    Prefs.setS(app, KEY_ID, w.id)
                    Prefs.setL(app, KEY_STAMP, KbVideo.stamp(app))
                } else {
                    if (isAsset(w.full)) copyAsset(app, w.full, tmp) else download(w.full, tmp, 16L * 1024L * 1024L)
                    if (!KbBg.save(app, Uri.fromFile(tmp))) throw IllegalStateException("The picture could not be read")
                    Prefs.setS(app, KEY_ID, w.id)
                    Prefs.setL(app, KEY_STAMP, KbBg.stamp(app))
                }
            } catch (e: Exception) {
                err = friendly(e)
            } finally {
                tmp.delete()
            }
            main.post { onDone(err) }
        }.start()
    }

    // ---------------------------------------------------------------- network

    private fun open(url: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        // generous: free hosting plans can take ~30 s to wake up
        conn.connectTimeout = 15000
        conn.readTimeout = 40000
        conn.useCaches = false
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "ZetaBoard")
        return conn
    }

    private fun getText(url: String): String {
        val conn = open(url)
        try {
            conn.setRequestProperty("Accept", "application/json")
            val code = conn.responseCode
            if (code != 200) throw IllegalStateException("Server answered $code")
            if (conn.contentLengthLong > 8L * 1024L * 1024L) throw IllegalStateException("The list is too big")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /** Saves the file at [url] into [out], refusing anything bigger than [maxBytes]. */
    private fun download(url: String, out: File, maxBytes: Long) {
        val conn = open(url)
        try {
            val code = conn.responseCode
            if (code != 200) throw IllegalStateException("Server answered $code")
            if (conn.contentLengthLong > maxBytes) throw IllegalStateException("The file is too big")
            var total = 0L
            conn.inputStream.use { input ->
                FileOutputStream(out).use { os ->
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > maxBytes) throw IllegalStateException("The file is too big")
                        os.write(buf, 0, n)
                    }
                }
            }
            if (total == 0L) throw IllegalStateException("The file is empty")
        } catch (e: Exception) {
            out.delete()
            throw e
        } finally {
            conn.disconnect()
        }
    }

    private fun friendly(e: Exception): String = when (e) {
        is UnknownHostException, is ConnectException, is SocketTimeoutException -> "No connection to the wallpaper server"
        else -> e.message ?: "Something went wrong"
    }
}
