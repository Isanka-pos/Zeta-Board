package com.zeta.board

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import kotlin.math.sin

/**
 * Hold the backspace key and flowers and leaves rain down from the top edge of the screen and drift all across it.
 *
 * With the "Display over other apps" permission the effect covers the whole screen (drawn in a click-through window).
 * Without it the same effect plays inside the keyboard. It is switched on or off in the app (Typing -> Delete effect).
 * The emoji are drawn once into small pictures, so every frame only moves a few pictures: no text drawing, no allocation.
 */
object DeleteFx {
    private val CODES = intArrayOf(0x1FABD, 0x1F338, 0x1F339, 0x2618, 0x1F33F, 0x1F343, 0x1F342)
    private const val SIZE = 128

    @Volatile private var pics: List<Bitmap> = emptyList()
    private var overlay: DelFxView? = null
    private var inside: DelFxView? = null

    /** Draws the emoji pictures in the background when the keyboard starts. */
    fun warm() {
        if (pics.isNotEmpty()) return
        Thread { pictures() }.start()
    }

    @Synchronized
    fun pictures(): List<Bitmap> {
        if (pics.isNotEmpty()) return pics
        val out = ArrayList<Bitmap>()
        try {
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            p.typeface = Typeface.DEFAULT
            p.textSize = SIZE * 0.74f
            p.textAlign = Paint.Align.CENTER
            for (cp in CODES) {
                var s = String(Character.toChars(cp))
                if (cp == 0x2618) s += "\uFE0F"
                // an emoji this phone cannot draw (the newest ones on old Android) is simply left out
                if (!p.hasGlyph(s)) continue
                val b = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
                val c = Canvas(b)
                c.drawText(s, SIZE / 2f, SIZE / 2f - (p.ascent() + p.descent()) / 2f, p)
                out.add(b)
            }
        } catch (e: Throwable) {
            Crash.log("deleteFxPics", e)
        }
        pics = out
        return out
    }

    /** The view that plays inside the keyboard when the phone has not allowed the full-screen effect. */
    fun attachInside(v: DelFxView) {
        inside = v
        v.onIdle = { if (!v.isActive()) v.visibility = View.GONE }
    }

    fun canCoverScreen(c: Context): Boolean = try {
        Settings.canDrawOverlays(c.applicationContext)
    } catch (e: Throwable) {
        false
    }

    fun start(ctx: Context) {
        try {
            if (!Prefs.getB(ctx, "delfx", true)) return
            val app = ctx.applicationContext
            if (canCoverScreen(app)) {
                try {
                    val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                    if (overlay == null) {
                        val nv = DelFxView(app)
                        nv.onIdle = { if (!nv.isActive()) removeOverlay(app) }
                        val lp = WindowManager.LayoutParams(
                            WindowManager.LayoutParams.MATCH_PARENT,
                            WindowManager.LayoutParams.MATCH_PARENT,
                            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                            PixelFormat.TRANSLUCENT
                        )
                        lp.gravity = Gravity.TOP or Gravity.START
                        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                        wm.addView(nv, lp)
                        overlay = nv
                    }
                    overlay?.begin()
                    return
                } catch (e: Throwable) {
                    // the phone refused the window: play inside the keyboard instead
                    removeOverlay(app)
                }
            }
            val v = inside ?: return
            v.visibility = View.VISIBLE
            v.begin()
        } catch (e: Throwable) {
            Crash.log("deleteFxStart", e)
        }
    }

    /** The key was let go: no new flowers, the ones in the air finish their fall. */
    fun stop() {
        overlay?.end()
        inside?.end()
    }

    private fun removeOverlay(app: Context) {
        val v = overlay ?: return
        overlay = null
        try {
            (app.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeViewImmediate(v)
        } catch (e: Throwable) {
        }
    }

    /** The keyboard service is closing: everything goes away at once. */
    fun release(c: Context) {
        inside?.clear()
        inside = null
        overlay?.clear()
        removeOverlay(c.applicationContext)
    }
}

/** Draws the falling flowers and leaves. Up to [MAX] at a time, moved with plain arrays. */
class DelFxView(ctx: Context) : View(ctx) {
    companion object {
        private const val MAX = 46
    }

    var onIdle: (() -> Unit)? = null

    private val d = resources.displayMetrics.density
    private val rnd = java.util.Random()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private val px = FloatArray(MAX)
    private val py = FloatArray(MAX)
    private val vx = FloatArray(MAX)
    private val vy = FloatArray(MAX)
    private val rot = FloatArray(MAX)
    private val vrot = FloatArray(MAX)
    private val sc = FloatArray(MAX)
    private val ph = FloatArray(MAX)
    private val fq = FloatArray(MAX)
    private val am = FloatArray(MAX)
    private val age = FloatArray(MAX)
    private val life = FloatArray(MAX)
    private val kind = IntArray(MAX)

    private var spawning = false
    private var burst = false
    private var running = false
    private var last = 0L
    private var acc = 0f

    init {
        // nothing is clickable: touches go to whatever is below
        isClickable = false
        isFocusable = false
        setWillNotDraw(false)
    }

    /** A hold started: a first burst across the top edge, then a steady rain while the key stays down. */
    fun begin() {
        spawning = true
        burst = true
        if (!running) {
            running = true
            last = 0L
            postInvalidateOnAnimation()
        }
    }

    fun end() {
        spawning = false
    }

    fun clear() {
        spawning = false
        for (i in 0 until MAX) life[i] = 0f
        running = false
    }

    private fun spawn(n: Int, w: Int, y0: Float) {
        var i = 0
        while (i < MAX && age[i] < life[i]) i++
        if (i >= MAX) return
        kind[i] = rnd.nextInt(n)
        px[i] = rnd.nextFloat() * w
        py[i] = y0 - rnd.nextFloat() * 36f * d
        // they fan out to both sides while they fall, so the rain reaches every corner
        vx[i] = (rnd.nextFloat() - 0.5f) * 170f * d
        vy[i] = (150f + rnd.nextFloat() * 230f) * d
        rot[i] = rnd.nextFloat() * 360f
        vrot[i] = (rnd.nextFloat() - 0.5f) * 260f
        sc[i] = 0.6f + rnd.nextFloat() * 0.8f
        ph[i] = rnd.nextFloat() * 6.2832f
        fq[i] = 1.4f + rnd.nextFloat() * 2.4f
        am[i] = (14f + rnd.nextFloat() * 38f) * d
        age[i] = 0f
        life[i] = 3.4f + rnd.nextFloat() * 2.2f
    }

    override fun onDraw(c: Canvas) {
        val now = SystemClock.uptimeMillis()
        val dt = if (last == 0L) 0.016f else ((now - last) / 1000f).coerceIn(0.001f, 0.05f)
        last = now
        val pics = DeleteFx.pictures()
        val w = width
        val h = height
        var alive = false
        if (pics.isNotEmpty() && w > 0) {
            if (spawning) {
                if (burst) {
                    burst = false
                    for (k in 0 until 18) spawn(pics.size, w, 0f)
                }
                acc += dt * 24f
                while (acc >= 1f) {
                    acc -= 1f
                    spawn(pics.size, w, 0f)
                }
            }
            val base = 34f * d / pics[0].width
            for (i in 0 until MAX) {
                if (age[i] >= life[i]) continue
                age[i] += dt
                val a = age[i]
                vy[i] += 70f * d * dt
                vx[i] *= (1f - 0.5f * dt)
                px[i] += vx[i] * dt
                py[i] += vy[i] * dt
                rot[i] += vrot[i] * dt
                if (a >= life[i] || py[i] > h + 60f * d) {
                    life[i] = 0f
                    continue
                }
                alive = true
                // pops in with a little overshoot, fades out at the end
                val t = (a / 0.3f).coerceAtMost(1f)
                val e = 1f + 2.2f * (t - 1f) * (t - 1f) * (t - 1f) + 1.2f * (t - 1f) * (t - 1f)
                val rest = life[i] - a
                val fade = if (rest < 0.8f) rest / 0.8f else 1f
                paint.alpha = (255f * fade * t.coerceAtLeast(0.2f)).toInt().coerceIn(0, 255)
                val s = base * sc[i] * e
                val x = px[i] + sin(ph[i] + a * fq[i]) * am[i]
                val b = pics[kind[i] % pics.size]
                c.save()
                c.translate(x, py[i])
                c.rotate(rot[i])
                c.scale(s, s)
                c.drawBitmap(b, -b.width / 2f, -b.height / 2f, paint)
                c.restore()
            }
        }
        if (alive || spawning) {
            postInvalidateOnAnimation()
        } else {
            running = false
            last = 0L
            acc = 0f
            // removing a window while it is being drawn is not safe: do it right after this frame
            post { onIdle?.invoke() }
        }
    }

    fun isActive(): Boolean = running || spawning
}
