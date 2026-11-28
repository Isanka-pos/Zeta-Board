package com.zeta.board

import android.app.Activity
import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import org.json.JSONObject
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.concurrent.Executors
import javax.net.ssl.SSLException

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
 *  - applyToKeyboard: downloads one wallpaper (with the progress window in DownloadFx.kt) and makes it the keyboard
 *                background (the same background the "Choose photo" button in the app sets)
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
            if (err == null) {
                try {
                    prefetchThumbs(app, items)
                } catch (e: Exception) {
                }
            }
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
            // the old "Art" section (Shinobi) was removed from the store; a saved copy of the list must not bring it back
            if (o.optString("category", "").equals("Art", ignoreCase = true) || id == "shinobi") continue
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

    /** Thumbnails are kept in the app's own files (not the cache folder the system can empty), so they show without internet. */
    private fun thumbDir(app: Context): File = File(app.filesDir, "wp_thumbs")

    private fun oldThumbDir(app: Context): File = File(app.cacheDir, "wp_thumbs")

    private fun thumbFile(app: Context, id: String): File {
        val dir = thumbDir(app)
        if (!dir.exists()) dir.mkdirs()
        val f = File(dir, "$id.img")
        if (!f.exists() || f.length() == 0L) {
            // thumbnail saved by an older version: move it over instead of downloading again
            try {
                val old = File(oldThumbDir(app), "$id.img")
                if (old.exists() && old.length() > 0L) old.copyTo(f, overwrite = true)
            } catch (e: Exception) {
            }
        }
        return f
    }

    /** Saves the picture of one wallpaper on the phone (does nothing when it is already there). */
    private fun ensureThumbFile(app: Context, w: Wallpaper): File? {
        if (isAsset(w.thumb)) return null
        val f = thumbFile(app, w.id)
        if (f.exists() && f.length() > 0L) return f
        val tmp = File(f.parentFile, w.id + "." + System.nanoTime() + ".tmp")
        try {
            download(w.thumb, tmp, 2L * 1024L * 1024L)
            if (!tmp.renameTo(f)) {
                tmp.delete()
                return null
            }
        } catch (e: Exception) {
            tmp.delete()
            return null
        }
        return f
    }

    /** After the list loads, saves every wallpaper picture on the phone, so the store looks complete even offline. */
    private fun prefetchThumbs(app: Context, items: List<Wallpaper>) {
        var misses = 0
        for (w in items) {
            if (isAsset(w.thumb)) continue
            val f = thumbFile(app, w.id)
            if (f.exists() && f.length() > 0L) continue
            if (ensureThumbFile(app, w) == null) {
                misses++
                if (misses >= 3) return
            } else {
                misses = 0
            }
        }
    }

    private fun writeCache(app: Context, text: String) {
        val tmp = File(app.filesDir, "wallpapers_cache.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(cacheFile(app))) tmp.delete()
    }

    /** Deletes saved thumbnails of wallpapers that are not in the store any more. */
    private fun prune(app: Context, items: List<Wallpaper>) {
        val keep = HashSet<String>()
        for (w in items) keep.add(w.id)
        // saved live wallpapers of wallpapers that are gone from the store are removed too
        try {
            for (f in videoDir(app).listFiles() ?: emptyArray()) {
                if (!keep.contains(f.name.substringBefore('.'))) f.delete()
            }
        } catch (e: Exception) {
        }
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
                val f = ensureThumbFile(app, w) ?: throw IllegalStateException("no picture")
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

    private fun videoDir(app: Context): File = File(app.filesDir, "wp_videos")

    private fun videoFile(app: Context, w: Wallpaper): File = File(videoDir(app), w.id + ".mp4")

    /** True when the live wallpaper is already saved on this phone (it then applies at once, without internet). */
    fun isDownloaded(c: Context, w: Wallpaper): Boolean {
        if (!w.video) return true
        val f = videoFile(c.applicationContext, w)
        return f.exists() && f.length() > 0L
    }

    /** What onDone gets when the user cancelled (no error should be shown for it). */
    const val CANCELLED = "Download cancelled"

    /** What onDone gets when another apply is already running. */
    const val BUSY = "Another wallpaper is being applied"

    private class Cancelled : Exception(CANCELLED)

    /** A failure with a ready-made friendly message; [retry] tells the window whether a Retry button makes sense. */
    private class WpFail(msg: String, val retry: Boolean = true) : Exception(msg)

    /** The one apply that is running now (main thread only), so a second tap or a second screen cannot start another. */
    private var current: ApplyJob? = null

    /**
     * One "download (if needed) + apply" run. Created by [applyToKeyboard]; the screen keeps it so it can call
     * [cancel] when the screen is closed. All state here is touched on the main thread only.
     */
    class ApplyJob internal constructor(
        private val app: Context,
        private val w: Wallpaper,
        private val onDone: (String?) -> Unit
    ) {
        private var flag = AtomicBoolean(false)
        private var win: ApplyWindow? = null
        private var over = false
        private var running = false
        private var detached = false
        private var lastErr: String? = null

        internal fun begin(c: Context) {
            if (w.video && c is Activity) {
                val wn = ApplyWindow(c, w, { flag.set(true) }, { retry() }, { finish(lastErr) })
                win = wn
                wn.show()
            }
            launch()
        }

        /** Stops a download that is still running and removes the window. Called when the screen is closed. */
        fun cancel() {
            if (over) return
            detached = true
            flag.set(true)
            win?.dismiss()
            win = null
            // nothing running (e.g. an error was on screen): release the "one apply at a time" lock right away
            if (!running) finish(CANCELLED)
        }

        private fun retry() {
            if (over || running) return
            win?.restart()
            launch()
        }

        private fun finish(err: String?) {
            if (over) return
            over = true
            if (current === this) current = null
            onDone(err)
        }

        private fun launch() {
            running = true
            val f = AtomicBoolean(false)
            flag = f
            val wn = win
            Thread {
                var err: String? = null
                var canRetry = true
                try {
                    runApply(app, w, f, wn)
                } catch (e: Exception) {
                    err = explain(e)
                    canRetry = e !is WpFail || e.retry
                }
                if (err == null) Stats.event(app, "wallpaper_apply", w.id)
                val msg = err
                val retryable = canRetry
                main.post { result(msg, retryable) }
            }.start()
        }

        private fun result(err: String?, canRetry: Boolean) {
            running = false
            if (over) return
            val wn = win
            if (wn == null || detached) {
                finish(err)
            } else if (err == null) {
                lastErr = null
                wn.applied()   // closes itself shortly and then reports success through finish()
            } else if (err == CANCELLED) {
                wn.dismiss()
                finish(err)
            } else {
                lastErr = err
                wn.error(err, canRetry)
            }
        }
    }

    /**
     * Makes the wallpaper the keyboard background. A live wallpaper is downloaded only the first time
     * (with a progress window when [c] is an Activity) and kept on the phone, so the next Apply is instant.
     * onDone(null) = done, otherwise a short error ([CANCELLED] when the user cancelled).
     * Returns the running job (call cancel() on it when the screen closes), or null when another apply is already
     * running (onDone then gets a message at once).
     */
    fun applyToKeyboard(c: Context, w: Wallpaper, onDone: (String?) -> Unit): ApplyJob? {
        if (current != null) {
            onDone(BUSY)
            return null
        }
        val job = ApplyJob(c.applicationContext, w, onDone)
        current = job
        job.begin(c)
        return job
    }

    /** Runs on a background thread. Throws when anything fails; the wallpaper that is on the keyboard is only replaced at the very end. */
    private fun runApply(app: Context, w: Wallpaper, cancelled: AtomicBoolean, win: ApplyWindow?) {
        if (!w.video) {
            val tmp = File(app.cacheDir, "wp_" + w.id + ".tmp")
            try {
                if (isAsset(w.full)) copyAsset(app, w.full, tmp) else download(w.full, tmp, 16L * 1024L * 1024L, cancelled)
                if (!KbBg.save(app, Uri.fromFile(tmp))) throw WpFail("The picture could not be read")
                Prefs.setS(app, KEY_ID, w.id)
                Prefs.setL(app, KEY_STAMP, KbBg.stamp(app))
            } finally {
                tmp.delete()
            }
            return
        }

        val dir = videoDir(app)
        if (!dir.exists()) dir.mkdirs()
        if (!dir.isDirectory) throw WpFail("Could not create the wallpaper folder")
        val saved = videoFile(app, w)

        // a saved copy that is damaged (from an older version) is replaced instead of being applied
        if (saved.exists() && (saved.length() == 0L || KbVideo.validate(saved) != null)) saved.delete()

        var fresh = false
        if (!saved.exists()) {
            val part = File(dir, w.id + ".part")
            try {
                var last = 0L
                download(w.full, part, KbVideo.MAX_BYTES, cancelled, dir) { done, total ->
                    val now = System.currentTimeMillis()
                    if (win != null && (now - last > 60L || (total > 0L && done == total))) {
                        last = now
                        main.post { win.progress(done, total) }
                    }
                }
                // complete: make sure it really is a playable video before it gets its real name
                if (KbVideo.validate(part) != null) throw WpFail("The downloaded file is damaged. Please try again.")
                if (cancelled.get()) throw Cancelled()
                // the finished file only gets its real name when it is complete and valid
                if (!part.renameTo(saved)) {
                    part.copyTo(saved, overwrite = true)
                    part.delete()
                }
            } catch (e: Exception) {
                part.delete()
                throw e
            }
            fresh = true
        }

        if (win != null && fresh) {
            main.post { win.downloaded() }
            sleepUnlessCancelled(900L, cancelled)   // lets the person see the success state
        }
        if (cancelled.get()) throw Cancelled()

        if (win != null) main.post { win.applying() }
        val t0 = System.currentTimeMillis()
        // KbVideo.save checks the video again and only replaces the current keyboard video when that worked
        val msg = KbVideo.save(app, Uri.fromFile(saved))
        if (msg != null) throw WpFail(msg, msg != "That file has no video")
        Prefs.setS(app, KEY_ID, w.id)
        Prefs.setL(app, KEY_STAMP, KbVideo.stamp(app))
        if (win != null) {
            val shownFor = System.currentTimeMillis() - t0
            if (shownFor < 600L) sleepUnlessCancelled(600L - shownFor, null)
        }
    }

    private fun sleepUnlessCancelled(ms: Long, cancelled: AtomicBoolean?) {
        var left = ms
        while (left > 0L && (cancelled == null || !cancelled.get())) {
            val s = minOf(left, 50L)
            try {
                Thread.sleep(s)
            } catch (e: InterruptedException) {
                return
            }
            left -= s
        }
    }

    /** Turns a failure into a short, friendly sentence for the person. */
    private fun explain(e: Exception): String = when (e) {
        is Cancelled -> CANCELLED
        is WpFail -> e.message ?: "Something went wrong"
        is UnknownHostException -> "No internet connection. Check your connection and try again."
        is SocketTimeoutException -> "The download timed out. Please try again."
        is ConnectException -> "Could not reach the server. Try again in a moment."
        is MalformedURLException -> "This wallpaper's download link is not valid."
        is SSLException -> "A secure connection to the server could not be made."
        is SocketException, is EOFException -> "The connection was interrupted. Please try again."
        is IOException -> if (noSpace(e)) "Not enough storage space. Free some space and try again." else "The download failed. Please try again."
        else -> e.message ?: "Something went wrong"
    }

    private fun noSpace(e: Throwable?): Boolean {
        var t = e
        var n = 0
        while (t != null && n < 5) {
            val m = t.message ?: ""
            if (m.contains("ENOSPC") || m.contains("No space left", ignoreCase = true)) return true
            t = t.cause
            n++
        }
        return false
    }

    // ---------------------------------------------------------------- network

    private fun open(url: String): HttpURLConnection {
        if (!url.startsWith("https://") && !url.startsWith("http://")) throw MalformedURLException("Not a web address")
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

    /**
     * Saves the file at [url] into [out], refusing anything bigger than [maxBytes].
     * [spaceDir]: when given, the free space of that folder is checked first (a video needs room for itself and for
     * the keyboard's own copy). [onProgress] gets (bytes so far, total or -1 when the server gave no size).
     */
    private fun download(
        url: String,
        out: File,
        maxBytes: Long,
        cancelled: AtomicBoolean? = null,
        spaceDir: File? = null,
        onProgress: ((Long, Long) -> Unit)? = null
    ) {
        val conn = open(url)
        try {
            val code = conn.responseCode
            if (code == 404 || code == 410 || code == 403) throw WpFail("This wallpaper is no longer available on the server.", false)
            if (code in 500..599) throw WpFail("The server is unavailable right now. Please try again later.")
            if (code != 200) throw WpFail("The server could not send this wallpaper (error $code).")
            val len = conn.contentLengthLong
            if (len > maxBytes) throw WpFail("The file is too big", false)
            if (spaceDir != null) {
                val need = (if (len > 0L) len * 2L else 24L * 1024L * 1024L) + 8L * 1024L * 1024L
                if (spaceDir.usableSpace < need) throw WpFail("Not enough storage space. Free some space and try again.")
            }
            val plain = conn.contentEncoding.isNullOrEmpty()
            var total = 0L
            conn.inputStream.use { input ->
                FileOutputStream(out).use { os ->
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        if (cancelled != null && cancelled.get()) throw Cancelled()
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > maxBytes) throw WpFail("The file is too big", false)
                        os.write(buf, 0, n)
                        onProgress?.invoke(total, if (len > 0L && plain) len else -1L)
                    }
                }
            }
            if (total == 0L) throw WpFail("The downloaded file was empty. Please try again.")
            // a cut-off download must never be kept as if it were complete
            if (len > 0L && plain && total != len) throw WpFail("The download was incomplete. Please try again.")
        } catch (e: Exception) {
            out.delete()
            throw e
        } finally {
            conn.disconnect()
        }
    }

    private fun friendly(e: Exception): String = when (e) {
        is Cancelled -> "Download cancelled"
        is UnknownHostException, is ConnectException, is SocketTimeoutException -> "No connection to the wallpaper server"
        else -> e.message ?: "Something went wrong"
    }
}
