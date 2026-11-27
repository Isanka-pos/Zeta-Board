package com.zeta.board

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.view.VelocityTracker
import android.widget.OverScroller
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.min

/** Every tool the keyboard offers, and which of them are pinned to the keyboard's top bar (the user can change that). */
object Tools {
    const val STICKER = 0
    const val CLIPBOARD = 1
    const val QUICK = 2
    const val TRANSLATE = 3
    const val THEME = 4
    const val STYLE = 5
    const val EMOJI = 6
    const val SETTINGS = 7
    const val VOICE = 8
    const val STATUS = 9
    const val SINGLISH = 10
    const val MIX = 11
    const val SPEED = 12
    /** Sinhala typing layout: English -> phonetic -> Wijesekara. */
    const val LAYOUT = 13
    /** Text Editor: arrow pad, select, cut / copy / paste, home / end, backspace. */
    const val EDITOR = 14
    /** Protect: hides the message in the box so it looks blank to everyone without Zeta Board. */
    const val PROTECT = 15
    const val COUNT = 16

    /** How many tools fit on the top bar (the tool list button always sits after them). */
    const val MAX_PINS = 5

    val DEFAULT: List<Int> = listOf(STICKER, CLIPBOARD, QUICK, TRANSLATE, VOICE)

    private val icons = intArrayOf(
        R.drawable.ic_sticker, R.drawable.ic_paste, R.drawable.ic_quick, R.drawable.ic_translate,
        R.drawable.ic_palette, R.drawable.ic_font, R.drawable.ic_emoji, R.drawable.ic_tune, R.drawable.ic_mic, R.drawable.ic_status, R.drawable.ic_singlish, R.drawable.ic_mix, R.drawable.ic_speed, R.drawable.ic_layout, R.drawable.ic_editor, R.drawable.ic_protect
    )
    private val names = arrayOf("Stickers", "Clipboard", "Quick Text", "Translate", "Theme", "Text style", "Emoji", "Settings", "Voice", "Status Saver", "Singlish", "Emoji Mix", "Typing speed", "Layout", "Text Editor", "Protect")

    fun icon(id: Int): Int = icons[id]

    /** The Layout tool shows a short word instead of a picture: sin (English keys), ph (phonetic) or wi (Wijesekara). */
    fun layoutLabel(c: Context): String = when (SinMode.current(c)) {
        SinMode.PHONETIC -> "ph"
        SinMode.WIJESEKARA -> "wi"
        else -> "sin"
    }

    fun name(id: Int): String = names[id]

    /** The pinned tools, in order. Anything unreadable falls back to the default set. */
    fun pins(c: Context): List<Int> {
        val raw = Prefs.getS(c, "tool_pins", "d")
        if (raw == "d") return DEFAULT
        val out = ArrayList<Int>()
        for (part in raw.split(",")) {
            val v = part.trim().toIntOrNull() ?: continue
            if (v in 0 until COUNT && !out.contains(v) && out.size < MAX_PINS) out.add(v)
        }
        if (out.isEmpty() && raw.isNotBlank()) return DEFAULT
        return out
    }

    fun savePins(c: Context, list: List<Int>) {
        Prefs.setS(c, "tool_pins", list.joinToString(","))
    }

    fun resetPins(c: Context) {
        Prefs.setS(c, "tool_pins", "d")
    }
}

/**
 * The tool list: every tool as a round button with its name. Tap one to use it.
 * Touch and hold a tool, then drag it onto the top bar to pin it there; hold a pinned tool and drag it
 * to another place to reorder it, or down into the list to take it off the top bar.
 */
class ToolsPanel(ctx: Context, private val cb: Callback) : View(ctx) {

    interface Callback {
        fun onToolRun(id: Int)
        fun onToolsClose()
        fun onPinsChanged()
    }

    private companion object {
        const val NONE = 0
        const val PIN = 1
        const val CLOSE = 2
        const val TILE = 3
        const val RESET = 4
        const val SLOTS = 6 // 5 pinned places + the close button
        const val COLS = 4 // tools per row in the list (big icons, the list scrolls when it does not fit)
    }

    private val dm = ctx.resources.displayMetrics.density
    private fun dp(v: Float) = v * dm

    private var th: KbTheme = Themes.get(0)
    private var pins: MutableList<Int> = Tools.pins(ctx).toMutableList()
    private val handler = Handler(Looper.getMainLooper())
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }
    private val icons = HashMap<Int, Drawable>()
    private val rect = RectF()
    private val dash = DashPathEffect(floatArrayOf(dp(4f), dp(4f)), 0f)
    private var bg: LinearGradient? = null

    // touch state
    private var tKind = NONE
    private var tIdx = -1
    private var pressed = false
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var dragId = -1
    private var dragPin = -1
    private var dragX = 0f
    private var dragY = 0f
    private var dropSlot = -1

    // the tool list scrolls when the big icons do not all fit
    private var scroll = 0f
    private var scrolling = false
    private var gridDown = false
    private var scrollStart = 0f
    private var vt: VelocityTracker? = null
    private val scroller = OverScroller(ctx)

    private val longPress = Runnable {
        if (pressed && (tKind == PIN || tKind == TILE)) {
            if (tKind == PIN && tIdx !in pins.indices) return@Runnable
            dragId = if (tKind == PIN) pins[tIdx] else tIdx
            dragPin = if (tKind == PIN) tIdx else -1
            dragging = true
            dragX = downX
            dragY = downY
            updateDrop()
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            invalidate()
        }
    }

    // ---------- public API ----------

    fun applyTheme(t: KbTheme) {
        th = t
        buildBg()
        invalidate()
    }

    /** Called when the panel opens: reads the pinned tools again. */
    fun open() {
        pins = Tools.pins(context).toMutableList()
        resetTouch()
        scroller.abortAnimation()
        scroll = 0f
        invalidate()
    }

    fun close() {
        resetTouch()
    }

    // ---------- geometry ----------

    private val barH: Float get() = dp(58f)
    private val hintH: Float get() = dp(26f)
    private val padX: Float get() = dp(10f)
    private val rows: Int get() = (Tools.COUNT + COLS - 1) / COLS

    private fun slotW(): Float = (width - 2 * padX) / SLOTS
    private fun slotCx(s: Int): Float = padX + slotW() * (s + 0.5f)
    private fun gridTop(): Float = barH + hintH
    private fun cellW(): Float = (width - 2 * padX) / COLS
    private fun circleR(): Float = min(dp(31f), cellW() * 0.36f)

    /** A big round icon with the name under it (up to two lines). */
    private fun cellH(): Float = circleR() * 2f + dp(50f)
    private fun tileCx(i: Int): Float = padX + cellW() * (i % COLS + 0.5f)
    private fun tileCy(i: Int): Float = gridTop() - scroll + (i / COLS) * cellH() + circleR() + dp(6f)

    /** How far the list can be scrolled up (0 when everything fits). */
    private fun maxScroll(): Float = (rows * cellH() + dp(10f) - (height - gridTop())).coerceAtLeast(0f)

    private fun customized(): Boolean = pins != Tools.DEFAULT

    private fun resetRect(): RectF {
        tp.textSize = dp(11.5f)
        val w = tp.measureText("Reset") + dp(18f)
        val cy = barH + hintH / 2f
        rect.set(width - padX - w, cy - dp(11f), width - padX, cy + dp(11f))
        return rect
    }

    private fun hit(x: Float, y: Float) {
        tKind = NONE
        tIdx = -1
        if (y < barH) {
            if (x < padX || x > width - padX) return
            val s = ((x - padX) / slotW()).toInt()
            if (s == SLOTS - 1) {
                tKind = CLOSE
            } else if (s in pins.indices) {
                tKind = PIN
                tIdx = s
            }
            return
        }
        if (y < gridTop()) {
            if (customized()) {
                val r = resetRect()
                if (x >= r.left - dp(8f) && x <= r.right + dp(4f)) tKind = RESET
            }
            return
        }
        if (x < padX || x > width - padX) return
        val col = ((x - padX) / cellW()).toInt()
        val row = ((y - gridTop() + scroll) / cellH()).toInt()
        val i = row * COLS + col
        if (col in 0 until COLS && row >= 0 && i in 0 until Tools.COUNT) {
            tKind = TILE
            tIdx = i
        }
    }

    // ---------- dragging ----------

    private fun updateDrop() {
        dropSlot = if (dragY < barH + dp(6f)) ((dragX - padX) / slotW()).toInt().coerceIn(0, Tools.MAX_PINS - 1) else -1
    }

    /** The top bar place the dragged tool would land in, or -1 when it is not over the top bar. */
    private fun targetIndex(): Int {
        if (!dragging || dropSlot < 0) return -1
        return when {
            dragPin >= 0 -> min(dropSlot, pins.size - 1)
            pins.contains(dragId) -> min(dropSlot, pins.size - 1)
            pins.size < Tools.MAX_PINS -> min(dropSlot, pins.size)
            else -> dropSlot
        }.coerceAtLeast(0)
    }

    private fun finishDrag() {
        val slot = targetIndex()
        val id = dragId
        val from = dragPin
        val before = ArrayList(pins)
        if (id >= 0) {
            if (slot < 0) {
                if (from in pins.indices) pins.removeAt(from)
            } else if (from in pins.indices) {
                pins.removeAt(from)
                pins.add(min(slot, pins.size), id)
            } else {
                val ex = pins.indexOf(id)
                if (ex >= 0) {
                    pins.removeAt(ex)
                    pins.add(min(slot, pins.size), id)
                } else if (pins.size < Tools.MAX_PINS) {
                    pins.add(min(slot, pins.size), id)
                } else {
                    pins[slot.coerceIn(0, pins.size - 1)] = id
                }
            }
        }
        dragging = false
        dragId = -1
        dragPin = -1
        dropSlot = -1
        if (pins != before) {
            Tools.savePins(context, pins)
            cb.onPinsChanged()
        }
    }

    private fun resetTouch() {
        handler.removeCallbacks(longPress)
        pressed = false
        dragging = false
        dragId = -1
        dragPin = -1
        dropSlot = -1
        scrolling = false
        tKind = NONE
        tIdx = -1
    }

    private fun tap() {
        val kind = tKind
        val idx = tIdx
        when (kind) {
            PIN -> if (idx in pins.indices) {
                tapFeedback()
                cb.onToolRun(pins[idx])
            }
            TILE -> {
                tapFeedback()
                cb.onToolRun(idx)
            }
            CLOSE -> {
                tapFeedback()
                cb.onToolsClose()
            }
            RESET -> {
                tapFeedback()
                Tools.resetPins(context)
                pins = Tools.DEFAULT.toMutableList()
                cb.onPinsChanged()
            }
        }
    }

    private fun tapFeedback() {
        if (Prefs.getB(context, "haptic", true)) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                resetTouch()
                val flinging = !scroller.isFinished
                scroller.abortAnimation()
                vt?.recycle()
                vt = VelocityTracker.obtain()
                vt?.addMovement(e)
                downX = e.x
                downY = e.y
                gridDown = e.y >= gridTop()
                hit(e.x, e.y)
                // a touch that only stops a fling must not press a tool
                if (flinging) {
                    tKind = NONE
                    tIdx = -1
                }
                if (tKind != NONE) {
                    pressed = true
                    if (tKind == PIN || tKind == TILE) handler.postDelayed(longPress, 380)
                }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging) {
                    dragX = e.x
                    dragY = e.y
                    updateDrop()
                    invalidate()
                } else if (scrolling) {
                    vt?.addMovement(e)
                    scroll = (scrollStart - (e.y - downY)).coerceIn(0f, maxScroll())
                    invalidate()
                } else {
                    vt?.addMovement(e)
                    val dx = abs(e.x - downX)
                    val dy = abs(e.y - downY)
                    if (gridDown && dy > dp(8f) && dy > dx && maxScroll() > 0f) {
                        handler.removeCallbacks(longPress)
                        pressed = false
                        scrolling = true
                        scrollStart = scroll
                        downY = e.y
                        invalidate()
                    } else if (pressed && (dx > dp(12f) || dy > dp(12f))) {
                        handler.removeCallbacks(longPress)
                        pressed = false
                        invalidate()
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longPress)
                if (dragging) {
                    dragX = e.x
                    dragY = e.y
                    updateDrop()
                    finishDrag()
                } else if (scrolling) {
                    val v = vt
                    if (v != null) {
                        v.addMovement(e)
                        v.computeCurrentVelocity(1000, dp(4000f))
                        val vy = v.yVelocity
                        if (abs(vy) > dp(120f) && maxScroll() > 0f) {
                            scroller.fling(0, scroll.toInt(), 0, (-vy).toInt(), 0, 0, 0, maxScroll().toInt())
                            postInvalidateOnAnimation()
                        }
                    }
                } else if (pressed) {
                    tap()
                }
                vt?.recycle()
                vt = null
                resetTouch()
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                vt?.recycle()
                vt = null
                resetTouch()
                invalidate()
            }
        }
        return true
    }

    // ---------- drawing ----------

    private fun buildBg() {
        bg = if (height > 0) LinearGradient(0f, 0f, 0f, height.toFloat(), th.bgTop, th.bgBottom, Shader.TileMode.CLAMP) else null
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        buildBg()
        scroll = scroll.coerceIn(0f, maxScroll())
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scroll = scroller.currY.toFloat().coerceIn(0f, maxScroll())
            postInvalidateOnAnimation()
        }
    }

    private fun withA(color: Int, a: Int): Int = (color and 0x00FFFFFF) or (a shl 24)

    private fun drawable(res: Int): Drawable = icons.getOrPut(res) { context.getDrawable(res)!!.mutate() }

    /** Draws a tool: the Layout tool is a word (sin / ph / wi), every other tool is its picture. */
    private fun toolIcon(c: Canvas, id: Int, cx: Float, cy: Float, size: Float, color: Int, alpha: Int = 255) {
        if (id != Tools.LAYOUT) {
            icon(c, Tools.icon(id), cx, cy, size, color, alpha)
            return
        }
        val saved = tp.textSize
        val savedA = tp.textAlign
        val savedC = tp.color
        val savedTf = tp.typeface
        tp.typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
        tp.textAlign = Paint.Align.CENTER
        tp.textSize = size * 0.78f
        tp.color = withA(color, alpha)
        c.drawText(Tools.layoutLabel(context), cx, baseline(cy), tp)
        tp.textSize = saved
        tp.textAlign = savedA
        tp.color = savedC
        tp.typeface = savedTf
    }

    private fun icon(c: Canvas, res: Int, cx: Float, cy: Float, size: Float, color: Int, alpha: Int = 255) {
        val d = drawable(res)
        d.setTint(color)
        d.setAlpha(alpha)
        val l = (cx - size / 2f).toInt()
        val t = (cy - size / 2f).toInt()
        d.setBounds(l, t, l + size.toInt(), t + size.toInt())
        d.draw(c)
        d.setAlpha(255)
    }

    private fun baseline(cy: Float): Float = cy - (tp.descent() + tp.ascent()) / 2f

    private fun circle(c: Canvas, cx: Float, cy: Float, r: Float, color: Int, alpha: Int) {
        p.style = Paint.Style.FILL
        p.pathEffect = null
        p.color = color
        p.alpha = alpha
        c.drawCircle(cx, cy, r, p)
        p.alpha = 255
    }

    override fun onDraw(c: Canvas) {
        val t = th
        p.style = Paint.Style.FILL
        p.pathEffect = null
        p.shader = bg
        p.color = t.bgTop
        p.alpha = 255
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p)
        p.shader = null

        drawBar(c)
        drawHint(c)
        c.save()
        c.clipRect(0f, gridTop(), width.toFloat(), height.toFloat())
        drawGrid(c)
        drawScrollbar(c)
        c.restore()
        drawGhost(c)
    }

    private fun drawBar(c: Canvas) {
        val t = th
        val cy = barH / 2f
        val r = dp(22f)
        for (s in 0 until SLOTS) {
            val cx = slotCx(s)
            if (s == SLOTS - 1) {
                circle(c, cx, cy, r, t.text, if (pressed && tKind == CLOSE) 110 else 70)
                icon(c, R.drawable.ic_close, cx, cy, dp(22f), t.text)
            } else if (s < pins.size) {
                val dim = dragging && dragPin == s
                circle(c, cx, cy, r, t.text, if (dim) 14 else 36)
                if (pressed && !dragging && tKind == PIN && tIdx == s) circle(c, cx, cy, r, t.accent, 90)
                toolIcon(c, pins[s], cx, cy, dp(26f), t.text, if (dim) 70 else 255)
            } else if (dragging) {
                p.style = Paint.Style.STROKE
                p.strokeWidth = dp(1.5f)
                p.color = withA(t.text, 110)
                p.pathEffect = dash
                c.drawCircle(cx, cy, r, p)
                p.pathEffect = null
                p.style = Paint.Style.FILL
            }
        }
        val ti = targetIndex()
        if (ti >= 0) {
            p.style = Paint.Style.STROKE
            p.strokeWidth = dp(2.5f)
            p.color = t.accent
            p.alpha = 255
            c.drawCircle(slotCx(ti), cy, r + dp(3f), p)
            p.style = Paint.Style.FILL
        }
    }

    private fun drawHint(c: Canvas) {
        val t = th
        val cy = barH + hintH / 2f
        tp.textAlign = Paint.Align.LEFT
        tp.textSize = dp(12f)
        tp.color = withA(t.text, 170)
        val maxW = width - 2 * padX - (if (customized()) dp(64f) else 0f)
        var s = "Hold and drag to customize top bar"
        val n = tp.breakText(s, true, maxW.coerceAtLeast(1f), null)
        if (n < s.length) s = s.substring(0, (n - 1).coerceAtLeast(0)) + "\u2026"
        c.drawText(s, padX + dp(4f), baseline(cy), tp)
        if (customized()) {
            val r = resetRect()
            p.style = Paint.Style.FILL
            p.color = t.accent
            p.alpha = if (pressed && tKind == RESET) 120 else 55
            c.drawRoundRect(r, dp(11f), dp(11f), p)
            p.alpha = 255
            tp.textAlign = Paint.Align.CENTER
            tp.textSize = dp(11.5f)
            tp.color = t.text
            c.drawText("Reset", r.centerX(), baseline(r.centerY()), tp)
        }
        tp.textAlign = Paint.Align.CENTER
    }

    /** Fits [s] in [maxW] pixels, ending with ... when it is too long. */
    private fun fitLine(s: String, maxW: Float): String {
        val n = tp.breakText(s, true, maxW.coerceAtLeast(1f), null)
        return if (n < s.length) s.substring(0, (n - 1).coerceAtLeast(0)) + "\u2026" else s
    }

    private fun drawGrid(c: Canvas) {
        val t = th
        val r = circleR()
        tp.textAlign = Paint.Align.CENTER
        tp.textSize = dp(12.5f)
        val maxW = cellW() - dp(6f)
        val top = gridTop()
        for (i in 0 until Tools.COUNT) {
            val cy = tileCy(i)
            // rows that are scrolled out of view are skipped
            if (cy + cellH() < top || cy - r > height) continue
            val cx = tileCx(i)
            val dim = dragging && dragPin < 0 && dragId == i
            circle(c, cx, cy, r, t.text, if (dim) 12 else 30)
            if (pressed && !dragging && tKind == TILE && tIdx == i) circle(c, cx, cy, r, t.accent, 90)
            toolIcon(c, i, cx, cy, r * 1.15f, t.text, if (dim) 70 else 255)
            if (pins.contains(i)) circle(c, cx + r * 0.72f, cy - r * 0.72f, dp(5f), t.accent, 255)
            tp.color = withA(t.text, if (dim) 90 else 235)
            // the name goes under the icon; a long name wraps onto a second line
            val label = Tools.name(i)
            var a = label
            var b = ""
            if (tp.measureText(label) > maxW) {
                val sp = label.lastIndexOf(' ')
                if (sp > 0) {
                    a = label.substring(0, sp)
                    b = label.substring(sp + 1)
                }
            }
            val y1 = cy + r + dp(18f)
            c.drawText(fitLine(a, maxW), cx, y1, tp)
            if (b.isNotEmpty()) c.drawText(fitLine(b, maxW), cx, y1 + dp(15f), tp)
        }
    }

    /** A thin handle on the right while the list can be scrolled. */
    private fun drawScrollbar(c: Canvas) {
        val m = maxScroll()
        if (m <= 0f) return
        val viewH = height - gridTop()
        val trackH = viewH - dp(8f)
        val barLen = (trackH * viewH / (viewH + m)).coerceAtLeast(dp(24f))
        val y = gridTop() + dp(4f) + (trackH - barLen) * (scroll / m)
        rect.set(width - dp(4f), y, width - dp(1.5f), y + barLen)
        p.style = Paint.Style.FILL
        p.color = th.text
        p.alpha = 90
        c.drawRoundRect(rect, dp(1.5f), dp(1.5f), p)
        p.alpha = 255
    }

    private fun drawGhost(c: Canvas) {
        if (!dragging || dragId < 0) return
        val t = th
        val r = circleR() * 1.1f
        val gx = dragX.coerceIn(r, (width - r).coerceAtLeast(r))
        val gy = (dragY - dp(36f)).coerceAtLeast(r)
        circle(c, gx + dp(2f), gy + dp(3f), r, Color.BLACK, 70)
        circle(c, gx, gy, r, t.bgBottom, 255)
        circle(c, gx, gy, r, t.accent, 150)
        toolIcon(c, dragId, gx, gy, r * 0.95f, t.text)
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacksAndMessages(null)
        super.onDetachedFromWindow()
    }
}
