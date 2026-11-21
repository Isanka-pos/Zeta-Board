package com.zeta.board

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import java.io.File

/**
 * Apple-style emoji for the keyboard's emoji panel.
 *
 * assets/ZetaEmoji.ttf is a colour-bitmap (CBDT) font with real GSUB ligatures, so flags, families,
 * skin tones, keycaps and hearts all draw as ONE glyph. It is copied once to private storage and loaded
 * from there on a background thread, so opening the keyboard is never slowed down. If anything goes wrong
 * (copy fails, font cannot load) the keyboard silently keeps using the phone's normal emoji.
 */
object AppleEmoji {
    private const val ASSET = "ZetaEmoji.ttf"
    private const val VERSION = 1 // bump together with a new assets/ZetaEmoji.ttf

    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private val waiting = ArrayList<() -> Unit>()

    @Volatile private var tf: Typeface? = null
    @Volatile private var loading = false
    @Volatile private var failed = false

    val ready: Boolean get() = tf != null

    fun enabled(c: Context): Boolean = Prefs.getB(c, "apple_emoji", true)

    /** The Apple emoji font, or null when it is switched off, still loading or failed to load. */
    fun typeface(c: Context): Typeface? = if (enabled(c)) tf else null

    /** Loads the font in the background and calls [onReady] on the main thread once it is usable. */
    fun load(c: Context, onReady: () -> Unit) {
        val app = c.applicationContext
        synchronized(lock) {
            if (tf != null) {
                main.post(onReady)
                return
            }
            if (failed) return
            waiting.add(onReady)
            if (loading) return
            loading = true
        }
        Thread {
            val t = try {
                prepare(app)
            } catch (e: Throwable) {
                null
            }
            val cbs: List<() -> Unit>
            synchronized(lock) {
                tf = t
                failed = t == null
                loading = false
                cbs = ArrayList(waiting)
                waiting.clear()
            }
            if (t != null) cbs.forEach { main.post(it) }
        }.start()
    }

    private fun prepare(c: Context): Typeface? {
        val dir = File(c.filesDir, "fonts")
        dir.mkdirs()
        val f = File(dir, "ZetaEmoji-v$VERSION.ttf")
        val want = try {
            c.assets.openFd(ASSET).use { it.length }
        } catch (e: Exception) {
            -1L
        }
        if (!f.exists() || f.length() < 1_000_000L || (want > 0 && f.length() != want)) {
            val tmp = File(dir, "ZetaEmoji-v$VERSION.tmp")
            c.assets.open(ASSET).use { i -> tmp.outputStream().use { o -> i.copyTo(o, 64 * 1024) } }
            f.delete()
            if (!tmp.renameTo(f)) {
                tmp.delete()
                return null
            }
            // remove fonts of older versions
            dir.listFiles()?.forEach { if (it.name != f.name && it.name.startsWith("ZetaEmoji")) it.delete() }
        }
        val t = Typeface.createFromFile(f)
        if (t == null || t === Typeface.DEFAULT) {
            f.delete()
            return null
        }
        val p = Paint()
        p.typeface = t
        if (!p.hasGlyph("\uD83D\uDE00")) {
            f.delete()
            return null
        }
        return t
    }
}
