package com.zeta.board

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.util.concurrent.atomic.AtomicBoolean
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

    private class Cancelled : Exception("Download cancelled")

    /**
     * Makes the wallpaper the keyboard background. A live wallpaper is downloaded only the first time
     * (with a progress window when [c] is an Activity) and kept on the phone, so the next Apply is instant.
     * onDone(null) = done, otherwise a short error.
     */
    fun applyToKeyboard(c: Context, w: Wallpaper, onDone: (String?) -> Unit) {
        val app = c.applicationContext
        val cancelled = AtomicBoolean(false)
        val needsDownload = w.video && !isDownloaded(app, w)
        var win: ApplyWindow? = null
        if (w.video && c is Activity) {
            win = ApplyWindow(c, w.name, needsDownload) { cancelled.set(true) }
            win.show()
        }
        val shown = win
        Thread {
            var err: String? = null
            val tmp = File(app.cacheDir, "wp_" + w.id + ".tmp")
            try {
                if (w.video) {
                    val dir = videoDir(app)
                    if (!dir.exists()) dir.mkdirs()
                    val saved = videoFile(app, w)
                    var attempt = 0
                    while (true) {
                        if (!saved.exists() || saved.length() == 0L) {
                            val part = File(dir, w.id + ".part")
                            try {
                                var last = 0L
                                download(w.full, part, KbVideo.MAX_BYTES, cancelled) { done, total ->
                                    val now = System.currentTimeMillis()
                                    if (shown != null && (now - last > 80L || done == total)) {
                                        last = now
                                        main.post { shown.progress(done, total) }
                                    }
                                }
                                // the finished file only gets its real name when it is complete
                                if (!part.renameTo(saved)) {
                                    part.copyTo(saved, overwrite = true)
                                    part.delete()
                                }
                            } catch (e: Exception) {
                                part.delete()
                                throw e
                            }
                        }
                        if (shown != null) main.post { shown.applying() }
                        val msg = KbVideo.save(app, Uri.fromFile(saved))
                        if (msg == null) break
                        // a damaged saved copy: remove it and download once more
                        saved.delete()
                        if (attempt >= 1) throw IllegalStateException(msg)
                        attempt++
                    }
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
            if (err == null) Stats.event(app, "wallpaper_apply", w.id)
            main.post {
                shown?.close()
                onDone(err)
            }
        }.start()
    }

    /** The download / apply window: a bouncing download icon, a progress bar, percent and megabytes. */
    private class ApplyWindow(
        private val act: Activity,
        name: String,
        downloading: Boolean,
        private val onCancel: () -> Unit
    ) {
        private val dialog = Dialog(act)
        private val title: TextView
        private val bar: ProgressBar
        private val pct: TextView
        private val mb: TextView
        private val cancel: TextView
        private val icon: ImageView
        private var bounce: ObjectAnimator? = null

        private fun dp(v: Int): Int = (v * act.resources.displayMetrics.density + 0.5f).toInt()

        private fun round(color: Int, stroke: Int, strokeColor: Int, r: Int): GradientDrawable {
            val g = GradientDrawable()
            g.setColor(color)
            g.cornerRadius = dp(r).toFloat()
            if (stroke > 0) g.setStroke(dp(stroke), strokeColor)
            return g
        }

        private fun tv(s: String, size: Float, color: Int, bold: Boolean): TextView {
            val t = TextView(act)
            t.text = s
            t.textSize = size
            t.setTextColor(color)
            if (bold) t.setTypeface(t.typeface, android.graphics.Typeface.BOLD)
            return t
        }

        init {
            val purple = Color.parseColor("#8B5CF6")
            val cyan = Color.parseColor("#22D3EE")
            val muted = Color.parseColor("#9A93BD")
            dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
            dialog.setCancelable(false)
            dialog.setCanceledOnTouchOutside(false)

            val root = LinearLayout(act)
            root.orientation = LinearLayout.VERTICAL
            root.gravity = Gravity.CENTER_HORIZONTAL
            root.setPadding(dp(24), dp(26), dp(24), dp(20))
            root.background = round(Color.parseColor("#1A1433"), 1, purple, 26)

            icon = ImageView(act)
            icon.setImageResource(R.drawable.ic_download)
            icon.setColorFilter(cyan)
            root.addView(icon, LinearLayout.LayoutParams(dp(54), dp(54)))

            title = tv(if (downloading) "Downloading wallpaper" else "Applying wallpaper", 18f, Color.WHITE, true)
            title.gravity = Gravity.CENTER
            val tlp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            tlp.topMargin = dp(14)
            root.addView(title, tlp)

            val nm = tv(name, 13f, muted, false)
            nm.gravity = Gravity.CENTER
            nm.setPadding(0, dp(4), 0, 0)
            root.addView(nm, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

            bar = ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal)
            bar.max = 1000
            bar.isIndeterminate = true
            bar.progressTintList = ColorStateList.valueOf(cyan)
            bar.indeterminateTintList = ColorStateList.valueOf(cyan)
            bar.progressBackgroundTintList = ColorStateList.valueOf(Color.parseColor("#2B2350"))
            val blp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8))
            blp.topMargin = dp(18)
            root.addView(bar, blp)

            val row = LinearLayout(act)
            row.orientation = LinearLayout.HORIZONTAL
            mb = tv("", 12f, muted, false)
            pct = tv("", 12f, purple, true)
            pct.gravity = Gravity.END
            row.addView(mb, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(pct, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            val rlp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            rlp.topMargin = dp(8)
            root.addView(row, rlp)

            cancel = tv("Cancel", 14f, Color.WHITE, true)
            cancel.gravity = Gravity.CENTER
            cancel.setPadding(dp(24), dp(10), dp(24), dp(10))
            cancel.background = round(Color.parseColor("#0F0B1E"), 1, Color.parseColor("#2B2350"), 16)
            cancel.visibility = if (downloading) android.view.View.VISIBLE else android.view.View.GONE
            cancel.setOnClickListener {
                cancel.isEnabled = false
                cancel.alpha = 0.5f
                title.text = "Cancelling..."
                onCancel()
            }
            val clp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            clp.topMargin = dp(18)
            root.addView(cancel, clp)

            dialog.setContentView(root)
            dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val w = (act.resources.displayMetrics.widthPixels * 0.86f).toInt().coerceAtMost(dp(360))
            dialog.window?.setLayout(w, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        private fun alive(): Boolean =
            !act.isFinishing && !(Build.VERSION.SDK_INT >= 17 && act.isDestroyed)

        fun show() {
            if (!alive()) return
            try {
                dialog.show()
                val a = ObjectAnimator.ofFloat(icon, "translationY", 0f, dp(8).toFloat())
                a.duration = 650
                a.repeatMode = ValueAnimator.REVERSE
                a.repeatCount = ValueAnimator.INFINITE
                a.interpolator = AccelerateDecelerateInterpolator()
                a.start()
                bounce = a
            } catch (e: Exception) {
            }
        }

        fun progress(done: Long, total: Long) {
            if (!dialog.isShowing) return
            if (total > 0L) {
                bar.isIndeterminate = false
                val p = (done * 1000L / total).toInt().coerceIn(0, 1000)
                bar.progress = p
                pct.text = (p / 10).toString() + "%"
                mb.text = String.format("%.1f / %.1f MB", done / 1048576.0, total / 1048576.0)
            } else {
                mb.text = String.format("%.1f MB", done / 1048576.0)
            }
        }

        fun applying() {
            if (!dialog.isShowing) return
            title.text = "Applying wallpaper"
            bar.isIndeterminate = true
            pct.text = ""
            mb.text = ""
            cancel.visibility = android.view.View.GONE
        }

        fun close() {
            try {
                bounce?.cancel()
                if (dialog.isShowing && alive()) dialog.dismiss()
            } catch (e: Exception) {
            }
        }
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
    private fun download(
        url: String,
        out: File,
        maxBytes: Long,
        cancelled: AtomicBoolean? = null,
        onProgress: ((Long, Long) -> Unit)? = null
    ) {
        val conn = open(url)
        try {
            val code = conn.responseCode
            if (code != 200) throw IllegalStateException("Server answered $code")
            val len = conn.contentLengthLong
            if (len > maxBytes) throw IllegalStateException("The file is too big")
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
                        if (total > maxBytes) throw IllegalStateException("The file is too big")
                        os.write(buf, 0, n)
                        onProgress?.invoke(total, len)
                    }
                }
            }
            if (total == 0L) throw IllegalStateException("The file is empty")
            // a cut-off download must never be kept as if it were complete
            if (len > 0L && plain && total != len) throw IllegalStateException("The download was cut off, try again")
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
