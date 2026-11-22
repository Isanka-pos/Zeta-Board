package com.zeta.board

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View

/**
 * Text Editor panel: arrow pad with a SELECT switch (arrows then select text), Cut / Copy / Paste / Select all,
 * Home / End and Backspace. Arrows and Backspace repeat while held. ABC (or the back arrow) returns to the keys.
 */
class TextEditorPanel(ctx: Context, private val cb: Callback) : View(ctx) {

    interface Callback {
        /** Moves the cursor ([code] is a KeyEvent key code); with [select] the selection grows instead. */
        fun onEditorKey(code: Int, select: Boolean)

        /** Cut / copy / paste / select all ([id] is android.R.id.cut, copy, paste or selectAll). */
        fun onEditorAction(id: Int)

        fun onEditorDelete()
        fun onEditorClose()
    }

    private companion object {
        const val BACK = 0
        const val ABC = 1
        const val UP = 2
        const val DOWN = 3
        const val LEFT = 4
        const val RIGHT = 5
        const val SELECT = 6
        const val CUT = 7
        const val COPY = 8
        const val PASTE = 9
        const val ALL = 10
        const val HOME = 11
        const val END = 12
        const val DEL = 13
        const val N = 14
    }

    private val dm = ctx.resources.displayMetrics.density
    private fun dp(v: Float) = v * dm

    private var th: KbTheme = Themes.get(0)
    private val handler = Handler(Looper.getMainLooper())
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val rects = Array(N) { RectF() }
    private val tagR = RectF()
    private val icons = HashMap<Int, Drawable>()
    private var bg: LinearGradient? = null
    private var selecting = false
    private var down = -1

    private val repeat = object : Runnable {
        override fun run() {
            if (down < 0) return
            fire(down)
            handler.postDelayed(this, 55)
        }
    }

    fun applyTheme(t: KbTheme) {
        th = t
        buildBg()
        invalidate()
    }

    fun open() {
        selecting = false
        down = -1
        invalidate()
    }

    fun close() {
        handler.removeCallbacksAndMessages(null)
        down = -1
        selecting = false
    }

    private fun buildBg() {
        bg = if (height > 0) LinearGradient(0f, 0f, 0f, height.toFloat(), th.bgTop, th.bgBottom, Shader.TileMode.CLAMP) else null
    }

    private fun withA(color: Int, a: Int): Int = (color and 0x00FFFFFF) or (a shl 24)

    private fun drawable(res: Int): Drawable = icons.getOrPut(res) { context.getDrawable(res)!!.mutate() }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        buildBg()
        layoutButtons(w.toFloat(), h.toFloat())
    }

    private fun layoutButtons(w: Float, h: Float) {
        val pad = dp(8f)
        val gap = dp(6f)
        val headH = (h * 0.17f).coerceIn(dp(30f), dp(46f))
        // header
        rects[BACK].set(pad, pad, pad + headH, pad + headH)
        val abcW = dp(64f)
        rects[ABC].set(w - pad - abcW, pad, w - pad, pad + headH)
        val top = pad + headH + gap
        val bottom = h - pad
        // right column: cut / copy / paste / select all
        val colW = w * 0.32f
        val colL = w - pad - colW
        val rowH = (bottom - top - 3 * gap) / 5f
        // bottom row: home / end / backspace (one row high)
        val botTop = bottom - rowH
        val upper = botTop - gap
        val ch = (upper - top - 3 * gap) / 4f
        for (i in 0 until 4) {
            val y = top + i * (ch + gap)
            rects[CUT + i].set(colL, y, colL + colW, y + ch)
        }
        // left side: plus-shaped pad
        val padW = colL - gap - pad
        val bw = (padW - 2 * gap) / 3.2f
        val centreW = padW - 2 * bw - 2 * gap
        val bh = (upper - top - 2 * gap) / 3f
        val cx = pad + bw + gap
        rects[UP].set(cx, top, cx + centreW, top + bh)
        rects[SELECT].set(cx, top + bh + gap, cx + centreW, top + 2 * bh + gap)
        rects[DOWN].set(cx, top + 2 * (bh + gap), cx + centreW, top + 3 * bh + 2 * gap)
        rects[LEFT].set(pad, top + bh + gap, pad + bw, top + 2 * bh + gap)
        rects[RIGHT].set(cx + centreW + gap, top + bh + gap, cx + centreW + gap + bw, top + 2 * bh + gap)
        // bottom row
        val third = (w - 2 * pad - 2 * gap) / 3f
        rects[HOME].set(pad, botTop, pad + third, bottom)
        rects[END].set(pad + third + gap, botTop, pad + 2 * third + gap, bottom)
        rects[DEL].set(pad + 2 * (third + gap), botTop, w - pad, bottom)
    }

    private fun baseline(cy: Float): Float = cy - (tp.descent() + tp.ascent()) / 2f

    override fun onDraw(c: Canvas) {
        val t = th
        val w = width.toFloat()
        val h = height.toFloat()
        p.style = Paint.Style.FILL
        p.shader = bg
        c.drawRect(0f, 0f, w, h, p)
        p.shader = null
        val rad = dp(14f)

        // header
        drawKey(c, BACK, rad, false, false)
        icon(c, R.drawable.ic_back, rects[BACK], dp(20f), t.text)
        tp.textAlign = Paint.Align.LEFT
        tp.textSize = dp(17f)
        tp.color = t.text
        c.drawText("Text Editor", rects[BACK].right + dp(12f), baseline(rects[BACK].centerY()), tp)
        tp.textAlign = Paint.Align.CENTER
        // CURSOR MODE tag (the mode this panel is) and ABC (back to the keys)
        val tag = "CURSOR MODE"
        tp.textSize = dp(11.5f)
        val tagW = tp.measureText(tag) + dp(26f)
        tagR.set(rects[ABC].left - dp(8f) - tagW, rects[ABC].top + dp(3f), rects[ABC].left - dp(8f), rects[ABC].bottom - dp(3f))
        p.color = withA(t.text, 28)
        c.drawRoundRect(tagR, tagR.height() / 2f, tagR.height() / 2f, p)
        tp.color = withA(t.text, 170)
        c.drawText(tag, tagR.centerX(), baseline(tagR.centerY()), tp)
        drawKey(c, ABC, rects[ABC].height() / 2f, true, down == ABC)
        label(c, ABC, "ABC", t.accentText, 14f)

        // arrows + select
        arrow(c, UP)
        arrow(c, DOWN)
        arrow(c, LEFT)
        arrow(c, RIGHT)
        drawKey(c, SELECT, rects[SELECT].height() / 2f, selecting, down == SELECT)
        label(c, SELECT, "SELECT", if (selecting) t.accentText else t.text, 13.5f)

        // actions
        drawKey(c, CUT, rad, false, down == CUT); label(c, CUT, "CUT", t.text, 14f)
        drawKey(c, COPY, rad, false, down == COPY); label(c, COPY, "COPY", t.text, 14f)
        drawKey(c, PASTE, rad, false, down == PASTE); label(c, PASTE, "PASTE", t.text, 14f)
        drawKey(c, ALL, rad, false, down == ALL); label(c, ALL, "SELECT ALL", t.text, 13f)

        // home / end / backspace
        drawKey(c, HOME, rad, false, down == HOME); label(c, HOME, "|\u25C0", t.text, 17f)
        drawKey(c, END, rad, false, down == END); label(c, END, "\u25B6|", t.text, 17f)
        val red = Color.parseColor("#FF6B81")
        p.style = Paint.Style.FILL
        p.color = withA(red, if (down == DEL) 80 else 34)
        c.drawRoundRect(rects[DEL], rad, rad, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = dp(1.5f)
        p.color = withA(red, 200)
        c.drawRoundRect(rects[DEL], rad, rad, p)
        p.style = Paint.Style.FILL
        icon(c, R.drawable.ic_backspace, rects[DEL], dp(26f), red)
    }

    private fun arrow(c: Canvas, id: Int) {
        drawKey(c, id, dp(14f), false, down == id)
        val glyph = when (id) {
            UP -> "\u25B2"
            DOWN -> "\u25BC"
            LEFT -> "\u25C0"
            else -> "\u25B6"
        }
        label(c, id, glyph, th.text, 18f)
    }

    private fun drawKey(c: Canvas, id: Int, rad: Float, accent: Boolean, pressed: Boolean) {
        val t = th
        val r = rects[id]
        p.style = Paint.Style.FILL
        p.color = if (accent) t.accent else t.key
        c.drawRoundRect(r, rad, rad, p)
        if (pressed) {
            p.color = withA(if (accent) Color.WHITE else t.accent, 70)
            c.drawRoundRect(r, rad, rad, p)
        }
        if (!accent) {
            p.style = Paint.Style.STROKE
            p.strokeWidth = dp(1.2f)
            p.color = withA(t.accent, 110)
            c.drawRoundRect(r, rad, rad, p)
            p.style = Paint.Style.FILL
        }
    }

    private fun label(c: Canvas, id: Int, s: String, color: Int, size: Float) {
        tp.textAlign = Paint.Align.CENTER
        tp.textSize = dp(size)
        tp.color = color
        val r = rects[id]
        c.drawText(s, r.centerX(), baseline(r.centerY()), tp)
    }

    private fun icon(c: Canvas, res: Int, r: RectF, size: Float, color: Int) {
        val d = drawable(res)
        d.setTint(color)
        val l = (r.centerX() - size / 2f).toInt()
        val tt = (r.centerY() - size / 2f).toInt()
        d.setBounds(l, tt, l + size.toInt(), tt + size.toInt())
        d.draw(c)
    }

    private fun hit(x: Float, y: Float): Int {
        for (i in 0 until N) if (rects[i].contains(x, y)) return i
        return -1
    }

    /** Runs the button's action once. */
    private fun fire(id: Int) {
        when (id) {
            UP -> cb.onEditorKey(KeyEvent.KEYCODE_DPAD_UP, selecting)
            DOWN -> cb.onEditorKey(KeyEvent.KEYCODE_DPAD_DOWN, selecting)
            LEFT -> cb.onEditorKey(KeyEvent.KEYCODE_DPAD_LEFT, selecting)
            RIGHT -> cb.onEditorKey(KeyEvent.KEYCODE_DPAD_RIGHT, selecting)
            HOME -> cb.onEditorKey(KeyEvent.KEYCODE_MOVE_HOME, selecting)
            END -> cb.onEditorKey(KeyEvent.KEYCODE_MOVE_END, selecting)
            CUT -> cb.onEditorAction(android.R.id.cut)
            COPY -> cb.onEditorAction(android.R.id.copy)
            PASTE -> cb.onEditorAction(android.R.id.paste)
            ALL -> cb.onEditorAction(android.R.id.selectAll)
            DEL -> cb.onEditorDelete()
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val id = hit(e.x, e.y)
                if (id < 0) return true
                down = id
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                when (id) {
                    SELECT -> {
                        selecting = !selecting
                    }
                    BACK, ABC -> {
                        // closes on release
                    }
                    else -> {
                        fire(id)
                        if (id == UP || id == DOWN || id == LEFT || id == RIGHT || id == DEL) {
                            handler.removeCallbacks(repeat)
                            handler.postDelayed(repeat, 380)
                        }
                    }
                }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (down >= 0 && !rects[down].contains(e.x, e.y)) {
                    handler.removeCallbacks(repeat)
                    down = -1
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                val id = down
                handler.removeCallbacks(repeat)
                down = -1
                invalidate()
                if ((id == BACK || id == ABC) && rects[id].contains(e.x, e.y)) cb.onEditorClose()
            }
            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(repeat)
                down = -1
                invalidate()
            }
        }
        return true
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacksAndMessages(null)
        super.onDetachedFromWindow()
    }
}
