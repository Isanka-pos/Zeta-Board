package com.zeta.board

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.SystemClock
import java.util.Random

/**
 * Falling binary code ("hacker scene") drawn behind the keys of a Hacker theme.
 * Every column is a drop: a bright white-ish head with a fading trail of 0 and 1.
 * Typing makes the rain react: the column under the pressed key shoots down from the top
 * and the whole rain speeds up for a moment.
 */
class MatrixRain(private val dm: Float) {

    private val rnd = Random()
    private val charH = 13f * dm
    private val colW = 14f * dm
    private var w = 0
    private var h = 0
    private var cols = 0
    private var rows = 0

    private var head = FloatArray(0)      // y of the head, in px (can be negative = waiting)
    private var speed = FloatArray(0)     // px per second
    private var trail = IntArray(0)       // trail length in characters
    private var hot = BooleanArray(0)     // true for drops started by typing (brighter)
    private var glyph = CharArray(0)      // cols * rows, '0' or '1'

    private var last = 0L
    private var boost = 0f

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textAlign = Paint.Align.CENTER
        textSize = charH * 0.92f
    }

    fun resize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        if (width == w && height == h) return
        w = width
        h = height
        cols = (w / colW).toInt().coerceAtLeast(1)
        rows = (h / charH).toInt() + 3
        head = FloatArray(cols)
        speed = FloatArray(cols)
        trail = IntArray(cols)
        hot = BooleanArray(cols)
        glyph = CharArray(cols * rows) { if (rnd.nextBoolean()) '1' else '0' }
        for (i in 0 until cols) reset(i, true)
        last = 0L
    }

    private fun reset(i: Int, first: Boolean) {
        speed[i] = (45f + rnd.nextFloat() * 95f) * dm
        trail[i] = 6 + rnd.nextInt(11)
        hot[i] = false
        // spread the start so the rain does not fall as one wall
        head[i] = if (first) -rnd.nextFloat() * h * 1.2f + h * 0.15f else -rnd.nextFloat() * h * 0.7f
    }

    /** A key was pressed at [x]: a bright fast drop falls from the very top and the rain speeds up. */
    fun burst(x: Float) {
        if (cols == 0) return
        val i = (x / colW).toInt().coerceIn(0, cols - 1)
        head[i] = 0f
        speed[i] = 300f * dm
        trail[i] = 15
        hot[i] = true
        // a smaller neighbour drop, so a burst looks like a splash and not a single line
        val n = if (rnd.nextBoolean()) i - 1 else i + 1
        if (n in 0 until cols) {
            head[n] = -charH * 2f
            speed[n] = 230f * dm
            trail[n] = 10
            hot[n] = true
        }
        boost = 1f
    }

    private fun step(dt: Float) {
        val mul = 1f + boost * 1.4f
        boost = (boost - dt * 1.6f).coerceAtLeast(0f)
        for (i in 0 until cols) {
            head[i] += speed[i] * mul * dt
            if (head[i] - trail[i] * charH > h) reset(i, false)
        }
        // flicker: a few random characters flip every frame
        val flips = cols / 2 + 2
        for (k in 0 until flips) {
            val g = rnd.nextInt(glyph.size)
            glyph[g] = if (glyph[g] == '0') '1' else '0'
        }
    }

    /** Draws one frame of rain in [color]. The caller keeps invalidating while the theme is on. */
    fun draw(c: Canvas, color: Int) {
        if (cols == 0) return
        val now = SystemClock.uptimeMillis()
        val dt = if (last == 0L) 0.016f else ((now - last) / 1000f).coerceIn(0f, 0.05f)
        last = now
        step(dt)

        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        // head colour: the theme colour pushed towards white, so it glows
        val headCol = Color.rgb(r + (255 - r) * 6 / 10, g + (255 - g) * 6 / 10, b + (255 - b) * 6 / 10)

        for (i in 0 until cols) {
            val hr = Math.floor((head[i] / charH).toDouble()).toInt()
            val t = trail[i]
            val x = i * colW + colW / 2f
            val base = if (hot[i]) 1f else 0.55f
            for (j in 0 until t) {
                val row = hr - j
                if (row < 0 || row >= rows) continue
                val f = 1f - j.toFloat() / t
                if (j == 0) {
                    paint.color = headCol
                    paint.alpha = (255 * (if (hot[i]) 1f else 0.85f)).toInt()
                } else {
                    paint.color = color
                    paint.alpha = (230 * f * f * base).toInt().coerceIn(0, 255)
                }
                c.drawText(glyph, i * rows + row, 1, x, (row + 1) * charH, paint)
            }
        }
        paint.alpha = 255
    }
}

/** The typing banner on the toolbar of hacker themes: types a line, holds it, deletes it, next line. */
object TypeBanner {
    private val lines = arrayOf(
        "ZETA_BOARD",
        "wake_up_neo",
        "root@zeta",
        "hack_planet",
        "access_OK",
        "01011010",
        "type_fast"
    )
    private const val TYPE_MS = 90L
    private const val HOLD_MS = 1500L
    private const val DEL_MS = 40L
    private const val GAP_MS = 350L

    /** Typing speed setting: 1 = slowest, 5 = normal, 10 = fastest (twice as fast as normal). */
    const val SPEED_MIN = 1
    const val SPEED_MAX = 10
    const val SPEED_DEF = 5

    private fun typeMs(speed: Int): Long = (TYPE_MS * 5f / speed.coerceIn(SPEED_MIN, SPEED_MAX)).toLong().coerceAtLeast(1L)

    private fun delMs(speed: Int): Long = (DEL_MS * 5f / speed.coerceIn(SPEED_MIN, SPEED_MAX)).toLong().coerceAtLeast(1L)

    private fun span(s: String, speed: Int) = s.length * typeMs(speed) + HOLD_MS + s.length * delMs(speed) + GAP_MS

    /** Widest text the banner can show (prompt + longest line + cursor), for sizing the text to fit. */
    const val WIDEST = "> wake_up_neo_"

    class Frame(val text: String, val typing: Boolean)

    fun frame(now: Long, speed: Int = SPEED_DEF): Frame {
        val tm = typeMs(speed)
        val dm = delMs(speed)
        var total = 0L
        for (l in lines) total += span(l, speed)
        var t = now % total
        for (l in lines) {
            val sp = span(l, speed)
            if (t >= sp) {
                t -= sp
                continue
            }
            val typeEnd = l.length * tm
            val holdEnd = typeEnd + HOLD_MS
            val delEnd = holdEnd + l.length * dm
            return when {
                t < typeEnd -> Frame("> " + l.substring(0, (t / tm).toInt() + 1), true)
                t < holdEnd -> Frame("> $l", false)
                t < delEnd -> Frame("> " + l.substring(0, l.length - ((t - holdEnd) / dm).toInt() - 1), true)
                else -> Frame(">", false)
            }
        }
        return Frame(">", false)
    }
}

private val previewPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    typeface = Typeface.MONOSPACE
    textAlign = Paint.Align.CENTER
}

/** A still picture of the binary rain, used by the Theme Store tiles and preview of Hacker themes. */
fun drawBinaryPreview(c: Canvas, l: Float, t: Float, r: Float, b: Float, accent: Int, d: Float) {
    val ch = 11f * d
    val cw = 12f * d
    previewPaint.textSize = ch * 0.9f
    val cols = ((r - l) / cw).toInt()
    val rows = ((b - t) / ch).toInt()
    val x0 = l + ((r - l) - cols * cw) / 2f
    for (i in 0 until cols) {
        val len = 3 + (i * 7) % 6
        val start = (i * 5) % 4
        for (j in 0 until len) {
            val row = start + j
            if (row >= rows) break
            val head = j == len - 1
            previewPaint.color = if (head) Color.WHITE else accent
            previewPaint.alpha = if (head) 230 else 35 + 150 * j / len
            val ch01 = if (((i * 31 + row * 17) and 1) == 0) "0" else "1"
            c.drawText(ch01, x0 + i * cw + cw / 2f, t + (row + 1) * ch, previewPaint)
        }
    }
}
