package com.zeta.board

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Matrix
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.os.SystemClock
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

object Pal {
    val BG: Int = Color.parseColor("#0C0919")
    val BG2: Int = Color.parseColor("#1A1040")
    val CARD: Int = Color.parseColor("#16122A")
    val STROKE: Int = Color.parseColor("#2A2350")
    val MUTED: Int = Color.parseColor("#A59BC9")
    val CYAN: Int = Color.parseColor("#22D3EE")
    val PURPLE: Int = Color.parseColor("#A855F7")
    val GREEN: Int = Color.parseColor("#22C55E")
    val TILE: Int = Color.parseColor("#1B1538")
}

class LogoBadge(ctx: Context) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rf = RectF()
    private val logo = ctx.getDrawable(R.drawable.ic_logo)!!.mutate()
    private val d = ctx.resources.displayMetrics.density

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = w * 0.3f
        rf.set(d, d, w - d, h - d)
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(0f, 0f, w, h, Pal.BG2, Pal.TILE, Shader.TileMode.CLAMP)
        c.drawRoundRect(rf, r, r, p)
        p.shader = LinearGradient(0f, 0f, w, h, Pal.CYAN, Pal.PURPLE, Shader.TileMode.CLAMP)
        p.style = Paint.Style.STROKE
        p.strokeWidth = d * 2f
        c.drawRoundRect(rf, r, r, p)
        p.shader = null
        p.style = Paint.Style.FILL
        val s = w * 1.4f
        logo.setBounds(((w - s) / 2).toInt(), ((h - s) / 2).toInt(), ((w + s) / 2).toInt(), ((h + s) / 2).toInt())
        logo.draw(c)
    }
}

class ThemeChip(ctx: Context, val th: KbTheme, var sel: Boolean) : View(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val rf = RectF()
    private val check = ctx.getDrawable(R.drawable.ic_check)!!.mutate()

    fun select(v: Boolean) {
        sel = v
        invalidate()
    }

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension((104 * d).toInt(), (128 * d).toInt())
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = d * 16f
        rf.set(d * 3f, d * 3f, w - d * 3f, h - d * 3f)
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(0f, 0f, 0f, h, th.bgTop, th.bgBottom, Shader.TileMode.CLAMP)
        c.drawRoundRect(rf, r, r, p)
        p.shader = null
        val gap = d * 4f
        val kw = (w - d * 24f - gap * 5f) / 6f
        val kh = d * 12f
        for (row in 0..2) {
            for (col in 0..5) {
                val x = d * 12f + col * (kw + gap)
                val y = d * 16f + row * (kh + d * 6f)
                p.color = if (row == 2 && col == 5) th.accent else if (row == 2 && col == 0) th.special else th.key
                c.drawRoundRect(x, y, x + kw, y + kh, d * 3f, d * 3f, p)
            }
        }
        tp.color = th.text
        tp.textSize = d * 12f
        c.drawText(th.name, w / 2f, h - d * 16f, tp)

        p.style = Paint.Style.STROKE
        if (sel) {
            p.strokeWidth = d * 2.5f
            p.color = th.accent
            c.drawRoundRect(rf, r, r, p)
            p.style = Paint.Style.FILL
            val cx = w - d * 18f
            val cy = d * 18f
            p.color = th.accent
            c.drawCircle(cx, cy, d * 10f, p)
            check.setTint(th.accentText)
            check.setBounds((cx - d * 6f).toInt(), (cy - d * 6f).toInt(), (cx + d * 6f).toInt(), (cy + d * 6f).toInt())
            check.draw(c)
        } else {
            p.strokeWidth = d
            p.color = Color.argb(50, 255, 255, 255)
            c.drawRoundRect(rf, r, r, p)
        }
        p.style = Paint.Style.FILL
    }
}

object KeyShape {
    val names = listOf("Rounded", "Pill", "Square", "Cut", "Outline")

    fun draw(c: Canvas, l: Float, t: Float, r: Float, b: Float, shape: Int, d: Float, p: Paint, path: Path) {
        when (shape) {
            1 -> {
                val rad = (b - t) / 2f
                c.drawRoundRect(l, t, r, b, rad, rad, p)
            }
            2 -> c.drawRoundRect(l, t, r, b, d * 3f, d * 3f, p)
            3 -> {
                val k = d * 7f
                path.reset()
                path.moveTo(l + k, t)
                path.lineTo(r - k, t)
                path.lineTo(r, t + k)
                path.lineTo(r, b - k)
                path.lineTo(r - k, b)
                path.lineTo(l + k, b)
                path.lineTo(l, b - k)
                path.lineTo(l, t + k)
                path.close()
                c.drawPath(path, p)
            }
            else -> c.drawRoundRect(l, t, r, b, d * 9f, d * 9f, p)
        }
    }
}

class ShapeChip(ctx: Context, val idx: Int, var sel: Boolean) : View(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val rf = RectF()
    private val path = Path()

    fun select(v: Boolean) {
        sel = v
        invalidate()
    }

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension((96 * d).toInt(), (88 * d).toInt())
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        rf.set(d * 2f, d * 2f, w - d * 2f, h - d * 2f)
        p.style = Paint.Style.FILL
        p.color = Pal.BG
        c.drawRoundRect(rf, d * 16f, d * 16f, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = if (sel) d * 2.5f else d
        p.color = if (sel) Pal.PURPLE else Pal.STROKE
        c.drawRoundRect(rf, d * 16f, d * 16f, p)

        val kw = d * 22f
        val kh = d * 22f
        val gap = d * 6f
        val x0 = (w - (kw * 3f + gap * 2f)) / 2f
        val y0 = d * 14f
        for (i in 0..2) {
            val l = x0 + i * (kw + gap)
            if (idx == 4) {
                p.style = Paint.Style.FILL
                p.color = Color.argb(60, 139, 92, 246)
                KeyShape.draw(c, l, y0, l + kw, y0 + kh, idx, d, p, path)
                p.style = Paint.Style.STROKE
                p.strokeWidth = d * 1.5f
                p.color = Pal.CYAN
                KeyShape.draw(c, l, y0, l + kw, y0 + kh, idx, d, p, path)
            } else {
                p.style = Paint.Style.FILL
                p.color = if (i == 1) Pal.PURPLE else Color.parseColor("#3B2D68")
                KeyShape.draw(c, l, y0, l + kw, y0 + kh, idx, d, p, path)
            }
        }
        p.style = Paint.Style.FILL
        tp.color = if (sel) Color.WHITE else Pal.MUTED
        tp.textSize = d * 12f
        c.drawText(KeyShape.names[idx], w / 2f, h - d * 14f, tp)
    }
}

class SplashView(ctx: Context) : View(ctx) {
    var onDone: (() -> Unit)? = null
    private val t0 = SystemClock.uptimeMillis()
    private val total = 3400f
    private var fired = false
    private val d = ctx.resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
        typeface = Typeface.DEFAULT_BOLD
    }
    private val rf = RectF()
    private val rf2 = RectF()
    private val path = Path()
    private val m = Matrix()
    private val hsv = FloatArray(3)
    private val logo = ctx.getDrawable(R.drawable.ic_logo)!!.mutate()
    private val title = "ZETA BOARD"
    private val rainbow = IntArray(7) { Color.HSVToColor(floatArrayOf(it * 60f, 0.85f, 1f)) }
    private val blobR = d * 230f
    private val blobA = RadialGradient(0f, 0f, blobR, intArrayOf(Color.argb(90, 168, 85, 247), Color.argb(0, 168, 85, 247)), null, Shader.TileMode.CLAMP)
    private val blobB = RadialGradient(0f, 0f, blobR, intArrayOf(Color.argb(70, 34, 211, 238), Color.argb(0, 34, 211, 238)), null, Shader.TileMode.CLAMP)
    private val blobC = RadialGradient(0f, 0f, blobR, intArrayOf(Color.argb(70, 236, 72, 153), Color.argb(0, 236, 72, 153)), null, Shader.TileMode.CLAMP)
    private var bgShader: Shader? = null
    private var ringShader: SweepGradient? = null

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        bgShader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), Pal.BG2, Pal.BG, Shader.TileMode.CLAMP)
        ringShader = SweepGradient(w / 2f, h * 0.28f, rainbow, null)
    }

    private fun seg(t: Float, a: Float, b: Float): Float = ((t - a) / (b - a)).coerceIn(0f, 1f)
    private fun outCubic(x: Float): Float = 1f - (1f - x) * (1f - x) * (1f - x)
    private fun back(x: Float): Float {
        val c1 = 1.70158f
        val c3 = c1 + 1f
        val y = x - 1f
        return 1f + c3 * y * y * y + c1 * y * y
    }
    private fun inOut(x: Float): Float = if (x < 0.5f) 2f * x * x else 1f - (-2f * x + 2f) * (-2f * x + 2f) / 2f
    private fun withA(color: Int, a: Int): Int = (color and 0x00FFFFFF) or (a.coerceIn(0, 255) shl 24)
    private fun lerpC(a: Int, b: Int, f: Float): Int = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * f).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * f).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * f).toInt()
    )

    private fun blob(c: Canvas, sh: Shader, x: Float, y: Float) {
        c.save()
        c.translate(x, y)
        p.shader = sh
        c.drawCircle(0f, 0f, blobR, p)
        p.shader = null
        c.restore()
    }

    override fun onDraw(c: Canvas) {
        val el = (SystemClock.uptimeMillis() - t0).toFloat()
        val t = (el / total).coerceIn(0f, 1f)
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        p.style = Paint.Style.FILL
        p.shader = bgShader
        c.drawRect(0f, 0f, w, h, p)
        p.shader = null

        blob(c, blobA, w * (0.25f + 0.12f * sin(el / 1700f)), h * (0.22f + 0.06f * cos(el / 2100f)))
        blob(c, blobB, w * (0.80f + 0.10f * cos(el / 1900f)), h * (0.62f + 0.07f * sin(el / 1500f)))
        blob(c, blobC, w * (0.50f + 0.15f * sin(el / 2300f)), h * 0.97f)

        // ---- logo tile with rainbow ring ----
        val ly = h * 0.28f
        val pop = back(seg(t, 0.02f, 0.22f)).coerceAtLeast(0f)
        val s = d * 88f * pop
        rf.set(cx - s / 2f, ly - s / 2f, cx + s / 2f, ly + s / 2f)
        p.color = Pal.TILE
        c.drawRoundRect(rf, s * 0.28f, s * 0.28f, p)
        val ls = s * 1.4f
        logo.setBounds((cx - ls / 2f).toInt(), (ly - ls / 2f).toInt(), (cx + ls / 2f).toInt(), (ly + ls / 2f).toInt())
        logo.draw(c)

        val shine = seg(t, 0.42f, 0.64f)
        if (shine > 0f && shine < 1f) {
            c.save()
            path.reset()
            path.addRoundRect(rf, s * 0.28f, s * 0.28f, Path.Direction.CW)
            c.clipPath(path)
            val sx = rf.left - s * 0.6f + shine * s * 2.2f
            p.shader = LinearGradient(sx, rf.top, sx + s * 0.5f, rf.bottom, intArrayOf(0x00FFFFFF, 0x66FFFFFF, 0x00FFFFFF), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
            c.drawRect(rf, p)
            p.shader = null
            c.restore()
        }

        val rr = s * 0.5f + d * 16f
        val prog = outCubic(seg(t, 0.08f, 0.5f))
        m.setRotate(el / 5f, cx, ly)
        ringShader?.setLocalMatrix(m)
        rf2.set(cx - rr, ly - rr, cx + rr, ly + rr)
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        p.shader = ringShader
        p.strokeWidth = d * 9f
        p.alpha = 45
        c.drawArc(rf2, -90f, 360f * prog, false, p)
        p.strokeWidth = d * 3f
        p.alpha = 255
        c.drawArc(rf2, -90f, 360f * prog, false, p)
        p.shader = null
        p.style = Paint.Style.FILL

        // ---- title ----
        val ty = ly + s / 2f + d * 66f
        tp.textAlign = Paint.Align.LEFT
        tp.textSize = d * 26f
        tp.letterSpacing = 0.12f
        var tw = 0f
        for (ch in title) tw += tp.measureText(ch.toString())
        var x = cx - tw / 2f
        for (i in title.indices) {
            val a = seg(t, 0.28f + i * 0.03f, 0.42f + i * 0.03f)
            tp.color = lerpC(Pal.CYAN, Pal.PURPLE, i / (title.length - 1f))
            tp.alpha = (255 * a).toInt()
            c.drawText(title[i].toString(), x, ty + (1f - outCubic(a)) * d * 18f, tp)
            x += tp.measureText(title[i].toString())
        }
        tp.textAlign = Paint.Align.CENTER
        tp.letterSpacing = 0.35f
        tp.textSize = d * 11f
        tp.color = Pal.MUTED
        tp.alpha = (255 * seg(t, 0.55f, 0.7f)).toInt()
        c.drawText("PREMIUM KEYBOARD", cx, ty + d * 28f, tp)
        tp.letterSpacing = 0f

        // ---- mini keyboard assembling with RGB wave ----
        val kbW = min(w * 0.82f, d * 340f)
        val gap = d * 5f
        val kw = (kbW - gap * 9f) / 10f
        val kh = kw * 1.15f
        val x0 = cx - kbW / 2f
        val y0 = h * 0.57f
        val counts = intArrayOf(10, 9, 7, 1)
        val flash = if (t > 0.62f) (el / 110f).toInt() % 26 else -1
        var idx = 0
        for (r in 0..3) {
            val offKeys = when (r) {
                0 -> 0f
                1 -> 0.5f
                2 -> 1.5f
                else -> 2.5f
            }
            for (ci in 0 until counts[r]) {
                val st = 0.20f + idx * 0.014f
                val pr = seg(t, st, st + 0.16f)
                if (pr > 0f) {
                    val e = back(pr).coerceAtLeast(0f)
                    val kwid = if (r == 3) kw * 5f + gap * 4f else kw
                    val left = x0 + offKeys * (kw + gap) + ci * (kw + gap)
                    val top = y0 + r * (kh + gap) + (1f - pr) * d * 26f
                    val sc = 0.6f + 0.4f * e
                    val kcx = left + kwid / 2f
                    val kcy = top + kh / 2f
                    rf.set(kcx - kwid * sc / 2f, kcy - kh * sc / 2f, kcx + kwid * sc / 2f, kcy + kh * sc / 2f)
                    val a = (255 * pr).toInt()
                    hsv[0] = ((left / w) * 360f + el * 0.12f) % 360f
                    hsv[1] = 0.85f
                    hsv[2] = 1f
                    val col = Color.HSVToColor(hsv)
                    val lit = idx == flash
                    p.style = Paint.Style.FILL
                    p.color = if (lit) withA(col, 170) else withA(0xFF241B48.toInt(), a)
                    c.drawRoundRect(rf, d * 7f, d * 7f, p)
                    p.style = Paint.Style.STROKE
                    if (lit) {
                        p.strokeWidth = d * 6f
                        p.color = withA(col, 70)
                        c.drawRoundRect(rf, d * 7f, d * 7f, p)
                    }
                    p.strokeWidth = d * 1.6f
                    p.color = withA(col, (a * (0.3f + 0.7f * seg(t, 0.45f, 0.7f))).toInt())
                    c.drawRoundRect(rf, d * 7f, d * 7f, p)
                    p.style = Paint.Style.FILL
                }
                idx++
            }
        }

        // ---- progress ----
        val bw = w * 0.56f
        val by = h * 0.89f
        val bh = d * 5f
        val pg = inOut(t)
        p.color = Color.argb(40, 255, 255, 255)
        rf.set(cx - bw / 2f, by, cx + bw / 2f, by + bh)
        c.drawRoundRect(rf, bh, bh, p)
        if (pg > 0.01f) {
            p.shader = LinearGradient(cx - bw / 2f, 0f, cx + bw / 2f, 0f, intArrayOf(Pal.CYAN, Pal.PURPLE, Color.parseColor("#EC4899")), null, Shader.TileMode.CLAMP)
            rf.set(cx - bw / 2f, by, cx - bw / 2f + bw * pg, by + bh)
            c.drawRoundRect(rf, bh, bh, p)
            p.shader = null
            p.color = Color.argb(70, 255, 255, 255)
            c.drawCircle(cx - bw / 2f + bw * pg, by + bh / 2f, d * 6f, p)
        }
        val msg = if (t < 0.3f) "Starting engine" else if (t < 0.6f) "Assembling keys" else if (t < 0.85f) "Loading themes" else "Almost ready"
        tp.textAlign = Paint.Align.CENTER
        tp.textSize = d * 12f
        tp.color = Pal.MUTED
        tp.alpha = 220
        c.drawText(msg + "  " + (pg * 100f).toInt() + "%", cx, by + d * 28f, tp)

        if (t >= 1f) {
            if (!fired) {
                fired = true
                post { onDone?.invoke() }
            }
        } else {
            postInvalidateOnAnimation()
        }
    }
}
