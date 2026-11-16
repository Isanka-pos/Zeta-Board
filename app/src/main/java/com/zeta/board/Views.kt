package com.zeta.board

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.RadialGradient
import android.graphics.Typeface
import android.os.SystemClock
import android.view.View
import kotlin.math.max
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

class SplashView(ctx: Context) : View(ctx) {
    var onDone: (() -> Unit)? = null
    private val t0 = SystemClock.uptimeMillis()
    private val total = 2400f
    private var fired = false
    private val d = ctx.resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
        typeface = Typeface.DEFAULT_BOLD
    }
    private val rf = RectF()
    private val logo = ctx.getDrawable(R.drawable.ic_logo)!!.mutate()
    private val title = "ZETA BOARD"

    private fun back(x: Float): Float {
        val c1 = 1.70158f
        val c3 = c1 + 1f
        val y = x - 1f
        return 1f + c3 * y * y * y + c1 * y * y
    }

    private fun inOut(x: Float): Float = if (x < 0.5f) 2f * x * x else 1f - (-2f * x + 2f) * (-2f * x + 2f) / 2f

    override fun onDraw(c: Canvas) {
        val el = (SystemClock.uptimeMillis() - t0).toFloat()
        val t = (el / total).coerceIn(0f, 1f)
        val w = width.toFloat()
        val h = height.toFloat()
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(0f, 0f, w, h, Pal.BG2, Pal.BG, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p)

        val cx = w / 2f
        val cy = h * 0.40f
        val pulse = 0.5f + 0.5f * sin(el / 260f)
        val gr = d * (150f + 30f * pulse)
        p.shader = RadialGradient(cx, cy, gr, intArrayOf(Color.argb(95, 139, 92, 246), Color.argb(0, 34, 211, 238)), null, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, gr, p)
        p.shader = null

        val pop = back(min(1f, el / 650f)).coerceAtLeast(0f)
        val s = d * 92f * pop
        rf.set(cx - s / 2f, cy - s / 2f, cx + s / 2f, cy + s / 2f)
        p.color = Pal.TILE
        c.drawRoundRect(rf, s * 0.28f, s * 0.28f, p)
        val ls = s * 1.4f
        logo.setBounds((cx - ls / 2f).toInt(), (cy - ls / 2f).toInt(), (cx + ls / 2f).toInt(), (cy + ls / 2f).toInt())
        logo.draw(c)

        val rr = d * 70f
        rf.set(cx - rr, cy - rr, cx + rr, cy + rr)
        p.style = Paint.Style.STROKE
        p.strokeWidth = d * 3f
        p.strokeCap = Paint.Cap.ROUND
        p.color = Pal.CYAN
        p.alpha = (255 * min(1f, pop)).toInt()
        c.drawArc(rf, el / 4f, 110f, false, p)
        p.color = Pal.PURPLE
        p.alpha = (255 * min(1f, pop)).toInt()
        c.drawArc(rf, -el / 3f + 180f, 80f, false, p)
        p.style = Paint.Style.FILL

        tp.textSize = d * 26f
        tp.letterSpacing = 0.1f
        var total2 = 0f
        for (ch in title) total2 += tp.measureText(ch.toString())
        var x = cx - total2 / 2f
        val ty = cy + d * 130f
        for (i in title.indices) {
            val a = ((t - (0.18f + i * 0.035f)) / 0.12f).coerceIn(0f, 1f)
            tp.color = Color.WHITE
            tp.alpha = (255 * a).toInt()
            c.drawText(title[i].toString(), x, ty + (1f - a) * d * 14f, tp)
            x += tp.measureText(title[i].toString())
        }
        tp.letterSpacing = 0f
        val sa = ((t - 0.5f) / 0.15f).coerceIn(0f, 1f)
        tp.textAlign = Paint.Align.CENTER
        tp.textSize = d * 13f
        tp.color = Pal.MUTED
        tp.alpha = (255 * sa).toInt()
        c.drawText("Premium Keyboard", cx, ty + d * 28f, tp)

        val bw = w * 0.58f
        val by = h * 0.82f
        val bh = d * 6f
        p.color = Color.argb(40, 255, 255, 255)
        rf.set(cx - bw / 2f, by, cx + bw / 2f, by + bh)
        c.drawRoundRect(rf, bh, bh, p)
        val prog = inOut(t)
        if (prog > 0.01f) {
            p.shader = LinearGradient(cx - bw / 2f, 0f, cx + bw / 2f, 0f, Pal.CYAN, Pal.PURPLE, Shader.TileMode.CLAMP)
            rf.set(cx - bw / 2f, by, cx - bw / 2f + bw * prog, by + bh)
            c.drawRoundRect(rf, bh, bh, p)
            p.shader = null
        }
        val msg = if (t < 0.35f) "Warming up keys" else if (t < 0.7f) "Loading themes" else "Almost ready"
        tp.textSize = d * 12f
        tp.color = Pal.MUTED
        tp.alpha = 220
        c.drawText(msg + "  " + (prog * 100f).toInt() + "%", cx, by + d * 30f, tp)

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
