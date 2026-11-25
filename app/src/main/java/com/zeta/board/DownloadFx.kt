package com.zeta.board

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Camera
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.CornerPathEffect
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * The big 3D download arrow used by the wallpaper download window.
 *
 * A thick arrow is extruded layer by layer with android.graphics.Camera, so it really turns in 3D, bobs into a glowing
 * tray, and fills with colour from the bottom as the download goes (like a tank filling up). Sparks orbit around it.
 * [spinning] = the arrow does full 360 turns (used while the wallpaper is being applied).
 */
class Download3DView(ctx: Context) : View(ctx) {

    private val d = ctx.resources.displayMetrics.density
    private val cam = Camera()
    private val mx = Matrix()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arrow = Path()
    private val tray = Path()
    private val rf = RectF()
    private var t = 0f
    private var shown = 0f
    private var target = -1f
    private var anim: ValueAnimator? = null

    /** true = full 360 turns, false = a gentle swing. */
    var spinning = false

    /** 0..1 = filled that much, below 0 = unknown size (the fill loops). */
    fun setProgress(p: Float) {
        target = if (p < 0f) -1f else p.coerceIn(0f, 1f)
    }

    private fun lerp(a: Int, b: Int, f: Float): Int {
        val x = f.coerceIn(0f, 1f)
        val r = (Color.red(a) + (Color.red(b) - Color.red(a)) * x).toInt()
        val g = (Color.green(a) + (Color.green(b) - Color.green(a)) * x).toInt()
        val bl = (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * x).toInt()
        return Color.rgb(r, g, bl)
    }

    private fun buildShapes(s: Float) {
        arrow.reset()
        arrow.moveTo(-0.36f * s, -0.95f * s)
        arrow.lineTo(0.36f * s, -0.95f * s)
        arrow.lineTo(0.36f * s, -0.08f * s)
        arrow.lineTo(0.86f * s, -0.08f * s)
        arrow.lineTo(0f, 0.92f * s)
        arrow.lineTo(-0.86f * s, -0.08f * s)
        arrow.lineTo(-0.36f * s, -0.08f * s)
        arrow.close()
        tray.reset()
        tray.addRoundRect(RectF(-1.12f * s, 1.12f * s, 1.12f * s, 1.42f * s), 0.14f * s, 0.14f * s, Path.Direction.CW)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val a = ValueAnimator.ofFloat(0f, 1f)
        a.duration = 2600
        a.repeatCount = ValueAnimator.INFINITE
        a.interpolator = LinearInterpolator()
        a.addUpdateListener {
            t = it.animatedValue as Float
            invalidate()
        }
        a.start()
        anim = a
    }

    override fun onDetachedFromWindow() {
        anim?.cancel()
        anim = null
        super.onDetachedFromWindow()
    }

    /** One extruded shape: back layers first, the lit front face last. */
    private fun extrude(
        c: Canvas, path: Path, layers: Int, step: Float, rotX: Float, rotY: Float,
        near: Int, far: Int, front: (Canvas) -> Unit
    ) {
        paint.style = Paint.Style.FILL
        paint.shader = null
        for (k in layers downTo 0) {
            val z = (k - layers / 2f) * step
            cam.save()
            cam.rotateX(rotX)
            cam.rotateY(rotY)
            cam.translate(0f, 0f, z)
            cam.getMatrix(mx)
            cam.restore()
            c.save()
            c.concat(mx)
            if (k == 0) {
                front(c)
            } else {
                paint.color = lerp(near, far, k / layers.toFloat())
                c.drawPath(path, paint)
                // thin lighter rim on every layer keeps the edges crisp
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = d * 0.6f
                paint.color = Color.argb(60, 255, 255, 255)
                c.drawPath(path, paint)
                paint.style = Paint.Style.FILL
            }
            c.restore()
        }
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val s = minOf(w, h) * 0.27f
        buildShapes(s)
        val cx = w / 2f
        val cy = h * 0.46f
        val tau = (2.0 * PI).toFloat()

        // follow the real progress smoothly
        if (target >= 0f) shown += (target - shown) * 0.12f else shown = (t * 1.15f) % 1f

        val bob = (sin(t * tau * 2f) * 0.5f + 0.5f)
        val rotY = if (spinning) t * 360f * 1.0f else sin(t * tau) * 32f
        val rotX = -14f + sin(t * tau * 2f) * 5f

        // glow behind everything
        val glowR = s * (2.1f + 0.15f * bob)
        paint.shader = RadialGradient(cx, cy, glowR, Color.argb(120, 139, 92, 246), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        paint.style = Paint.Style.FILL
        c.drawCircle(cx, cy, glowR, paint)
        paint.shader = RadialGradient(cx, cy + s * 0.3f, glowR * 0.6f, Color.argb(90, 34, 211, 238), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy + s * 0.3f, glowR * 0.6f, paint)
        paint.shader = null

        // shadow on the floor: smaller and lighter while the arrow is high
        val shW = s * (1.25f - 0.15f * bob)
        paint.color = Color.argb((110 - 40 * bob).toInt(), 0, 0, 0)
        rf.set(cx - shW, cy + s * 1.72f, cx + shW, cy + s * 1.72f + s * 0.26f)
        c.drawOval(rf, paint)

        sparks(c, cx, cy + s * 1.25f, s, tau, back = true)

        c.save()
        c.translate(cx, cy)

        // the tray the arrow drops into
        extrude(c, tray, 7, d * 1.5f, rotX, rotY, Color.parseColor("#0E7490"), Color.parseColor("#083344")) { cv ->
            paint.shader = LinearGradient(0f, 1.12f * s, 0f, 1.42f * s, Color.parseColor("#67E8F9"), Color.parseColor("#06B6D4"), Shader.TileMode.CLAMP)
            cv.drawPath(tray, paint)
            paint.shader = null
        }

        // the arrow
        val lift = (-0.10f + 0.20f * bob) * s
        c.save()
        c.translate(0f, lift)
        val fillTop = s * 0.95f - (s * 1.9f) * shown
        extrude(c, arrow, 14, d * 1.7f, rotX, rotY, Color.parseColor("#7C3AED"), Color.parseColor("#2E1065")) { cv ->
            // empty face
            paint.shader = LinearGradient(0f, -s, 0f, s, Color.parseColor("#5B4BA8"), Color.parseColor("#2E2466"), Shader.TileMode.CLAMP)
            cv.drawPath(arrow, paint)
            paint.shader = null
            // filled part (from the bottom up)
            cv.save()
            cv.clipRect(-s, fillTop, s, s)
            paint.shader = LinearGradient(-s, -s, s * 0.8f, s, Color.parseColor("#A5F3FC"), Color.parseColor("#22D3EE"), Shader.TileMode.CLAMP)
            cv.drawPath(arrow, paint)
            paint.shader = null
            // a bright band right at the fill line
            paint.color = Color.argb(150, 255, 255, 255)
            cv.drawRect(-s, fillTop, s, fillTop + d * 2.5f, paint)
            cv.restore()
            // glossy top-left shine
            paint.shader = LinearGradient(-s, -s, s * 0.2f, s * 0.2f, Color.argb(120, 255, 255, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            cv.drawPath(arrow, paint)
            paint.shader = null
            // bold rim
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = d * 2.6f
            paint.strokeJoin = Paint.Join.ROUND
            paint.color = Color.argb(235, 255, 255, 255)
            cv.drawPath(arrow, paint)
            paint.style = Paint.Style.FILL
        }
        c.restore()
        c.restore()

        sparks(c, cx, cy + s * 1.25f, s, tau, back = false)
    }

    /** Small glowing dots circling the tray; the ones behind it are drawn first (smaller and dimmer). */
    private fun sparks(c: Canvas, cx: Float, cy: Float, s: Float, tau: Float, back: Boolean) {
        val n = 9
        paint.style = Paint.Style.FILL
        paint.shader = null
        for (i in 0 until n) {
            val a = t * tau * 1.5f + i * tau / n
            val depth = sin(a)
            if ((depth < 0f) != back) continue
            val x = cx + cos(a) * s * 1.55f
            val y = cy + depth * s * 0.38f
            val r = d * (2.2f + 1.8f * (depth * 0.5f + 0.5f))
            val col = if (i % 2 == 0) Color.parseColor("#22D3EE") else Color.parseColor("#C4B5FD")
            paint.color = Color.argb(70, Color.red(col), Color.green(col), Color.blue(col))
            c.drawCircle(x, y, r * 2.2f, paint)
            paint.color = col
            c.drawCircle(x, y, r, paint)
        }
    }
}

/**
 * A fat, raised progress bar: a deep groove, a glossy gradient fill with a dark underside (so it looks thick),
 * moving light stripes and a glowing tip. [setProgress] below 0 shows a block sliding to and fro.
 */
class Chunky3DBar(ctx: Context) : View(ctx) {

    private val d = ctx.resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rf = RectF()
    private val clip = Path()
    private var t = 0f
    private var shown = 0f
    private var target = -1f
    private var anim: ValueAnimator? = null

    fun setProgress(v: Float) {
        target = if (v < 0f) -1f else v.coerceIn(0f, 1f)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val a = ValueAnimator.ofFloat(0f, 1f)
        a.duration = 1100
        a.repeatCount = ValueAnimator.INFINITE
        a.interpolator = LinearInterpolator()
        a.addUpdateListener {
            t = it.animatedValue as Float
            invalidate()
        }
        a.start()
        anim = a
    }

    override fun onDetachedFromWindow() {
        anim?.cancel()
        anim = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val depth = d * 5f
        val bh = h - depth
        val r = bh / 2f

        // groove
        p.style = Paint.Style.FILL
        p.shader = null
        p.color = Color.parseColor("#0A0719")
        rf.set(0f, depth, w, h)
        c.drawRoundRect(rf, r, r, p)
        p.color = Color.parseColor("#2B2350")
        rf.set(0f, 0f, w, bh)
        c.drawRoundRect(rf, r, r, p)
        p.shader = LinearGradient(0f, 0f, 0f, bh, Color.parseColor("#120D2B"), Color.parseColor("#241C4D"), Shader.TileMode.CLAMP)
        rf.set(d * 2f, d * 2f, w - d * 2f, bh - d * 1f)
        c.drawRoundRect(rf, r, r, p)
        p.shader = null

        // fill
        val l: Float
        val rt: Float
        if (target >= 0f) {
            shown += (target - shown) * 0.15f
            l = 0f
            rt = (w * shown).coerceAtLeast(bh)
        } else {
            val seg = w * 0.38f
            val pos = abs(((t * 2f) % 2f) - 1f)
            l = (w - seg) * pos
            rt = l + seg
        }
        if (target >= 0f && shown < 0.005f && target < 0.005f) return

        // underside (the thickness)
        p.color = Color.parseColor("#5B21B6")
        rf.set(l, depth * 0.8f, rt, bh + depth * 0.8f)
        c.drawRoundRect(rf, r, r, p)
        // top face
        rf.set(l, 0f, rt, bh)
        p.shader = LinearGradient(0f, 0f, 0f, bh, Color.parseColor("#67E8F9"), Color.parseColor("#8B5CF6"), Shader.TileMode.CLAMP)
        c.drawRoundRect(rf, r, r, p)
        p.shader = null

        // moving stripes + shine, kept inside the fill
        c.save()
        clip.reset()
        clip.addRoundRect(rf, r, r, Path.Direction.CW)
        c.clipPath(clip)
        p.color = Color.argb(55, 255, 255, 255)
        val gap = d * 22f
        val off = t * gap
        var x = l - gap * 2f + off
        while (x < rt + gap) {
            clip.reset()
            clip.moveTo(x, bh)
            clip.lineTo(x + d * 8f, bh)
            clip.lineTo(x + d * 8f + bh * 0.8f, 0f)
            clip.lineTo(x + bh * 0.8f, 0f)
            clip.close()
            c.drawPath(clip, p)
            x += gap
        }
        p.color = Color.argb(110, 255, 255, 255)
        rf.set(l + r * 0.5f, bh * 0.14f, rt - r * 0.5f, bh * 0.44f)
        c.drawRoundRect(rf, bh * 0.15f, bh * 0.15f, p)
        c.restore()

        // glowing tip
        if (target >= 0f) {
            p.shader = RadialGradient(rt - r, bh / 2f, bh, Color.argb(170, 255, 255, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            c.drawCircle(rt - r, bh / 2f, bh, p)
            p.shader = null
        }
    }
}
