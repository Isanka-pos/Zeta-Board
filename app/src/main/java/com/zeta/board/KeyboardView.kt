package com.zeta.board

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

class KeyboardView(ctx: Context) : View(ctx) {

    interface Listener {
        fun onText(s: String)
        fun onAction(a: Int)
    }

    companion object {
        const val DEL = 1
        const val ENTER = 2
        const val SETTINGS = 3
        const val PASTE = 4
        const val LEFT = 5
        const val RIGHT = 6

        private const val K_CHAR = 0
        private const val K_SHIFT = 1
        private const val K_DEL = 2
        private const val K_LAYER = 3
        private const val K_SPACE = 4
        private const val K_ENTER = 5
        private const val K_CAT = 6
        private const val K_PAD = 7
    }

    private class Key(
        val label: String,
        val kind: Int,
        val w: Float = 1f,
        val alts: String = "",
        val arg: Int = 0,
        val icon: Int = 0,
        val out: String = label
    ) {
        val r = RectF()
    }

    var listener: Listener? = null
    var theme: KbTheme = Themes.get(0)
        set(v) {
            field = v
            invalidate()
        }

    private val dm = ctx.resources.displayMetrics.density
    private fun dp(v: Float) = v * dm

    // layers: 0 abc, 1 symbols, 2 more symbols, 3 sinhala, 4 emoji
    private var layer = 0
    private var shift = 0
    private var emojiCat = 0
    private var emojiPage = 0
    private var emojiPages = 1
    private var rows: List<List<Key>> = emptyList()

    private var numRow = true
    private var heightPct = 100
    private var haptic = true
    private var sound = false
    private var preview = true
    private var shape = 0
    private var rgb = 0
    private var rgbSpeed = 5
    private var bgDim = 35
    private var bgRaw: Bitmap? = null
    private var bgScaled: Bitmap? = null
    private var bgStamp = 0L
    private val path = Path()
    private val hsv = FloatArray(3)
    private val sweepM = Matrix()
    private var sweep: SweepGradient? = null
    private val srcR = Rect()
    private val dstR = Rect()
    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val rainbow = IntArray(7) { Color.HSVToColor(floatArrayOf(it * 60f, 0.9f, 1f)) }

    private val toolH: Float get() = dp(44f)
    private val handler = Handler(Looper.getMainLooper())
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val rect = RectF()
    private val altRect = RectF()
    private val toolRects = Array(3) { RectF() }
    private val toolIcons = intArrayOf(R.drawable.ic_paste, R.drawable.ic_palette, R.drawable.ic_tune)
    private val cache = HashMap<Int, Drawable>()

    private var pressed: Key? = null
    private var pressedTool = -1
    private var activeId = -1
    private var downX = 0f
    private var curX = 0f
    private var lastCurX = 0f
    private var cursorMoved = false
    private var altList: List<String> = emptyList()
    private var altSel = -1
    private var lastShiftTap = 0L
    private var afterSentence = false

    private val accents = mapOf(
        'a' to "àáâäæãåā", 'e' to "èéêëēėę", 'i' to "îïíīįì", 'o' to "ôöòóœøōõ",
        'u' to "ûüùúū", 's' to "ß", 'c' to "çćč", 'n' to "ñń", 'y' to "ÿý", 'z' to "žźż"
    )

    private fun cps(r: Iterable<Int>): List<String> = r.map { String(Character.toChars(it)) }

    private val emojiCats: List<Pair<String, List<String>>> by lazy {
        listOf(
            "Face" to cps(0x1F600..0x1F64F),
            "Love" to cps((0x1F493..0x1F49F) + listOf(0x1F48B, 0x1F339, 0x1F525, 0x2728, 0x1F31F, 0x1F389)),
            "Hand" to cps((0x1F446..0x1F450) + listOf(0x1F64C, 0x1F64F, 0x1F4AA, 0x1F91D, 0x1F91E)),
            "Pets" to cps(0x1F400..0x1F43C),
            "Food" to cps(0x1F345..0x1F37A),
            "Fun" to cps((0x1F3A0..0x1F3C4) + (0x1F680..0x1F68A))
        )
    }

    private val longPress = Runnable {
        val k = pressed
        if (k != null && k.alts.isNotEmpty()) {
            val up = layer == 0 && shift > 0
            altList = k.alts.map { ch -> if (up) ch.toString().uppercase() else ch.toString() }
            altSel = altSelAt(k, curX)
            invalidate()
        }
    }

    private val repeat = object : Runnable {
        override fun run() {
            listener?.onAction(DEL)
            handler.postDelayed(this, 45)
        }
    }

    init {
        reload()
    }

    // ---------- public API ----------

    fun reload() {
        val c = context
        numRow = Prefs.getB(c, "numrow", true)
        heightPct = Prefs.getI(c, "height", 100)
        haptic = Prefs.getB(c, "haptic", true)
        sound = Prefs.getB(c, "sound", false)
        preview = Prefs.getB(c, "preview", true)
        shape = Prefs.getI(c, "shape", 0)
        rgb = Prefs.getI(c, "rgb", 0)
        rgbSpeed = Prefs.getI(c, "rgbspeed", 5)
        bgDim = Prefs.getI(c, "bgdim", 35)
        val bf = KbBg.file(c)
        val stamp = if (bf.exists()) bf.lastModified() + bf.length() else 0L
        if (stamp != bgStamp) {
            bgStamp = stamp
            bgRaw = if (stamp != 0L) KbBg.load(c, 1) else null
            buildBg()
        }
        rows = build()
        requestLayout()
        layoutKeys()
        invalidate()
    }

    fun startInput(l: Int, s: Int) {
        layer = l
        shift = s
        afterSentence = false
        rebuild()
    }

    // ---------- layouts ----------

    private fun chars(s: String, dig: String = ""): List<Key> = s.mapIndexed { i, ch ->
        val d = if (i < dig.length) dig[i].toString() else ""
        Key(ch.toString(), K_CHAR, alts = d + (accents[ch] ?: ""))
    }

    private fun bottom(mode: Int): List<Key> {
        val l = ArrayList<Key>()
        when (mode) {
            1 -> l.add(Key("ABC", K_LAYER, 1.5f, arg = 0))
            else -> l.add(Key("?123", K_LAYER, 1.4f, arg = 1))
        }
        if (mode != 1) {
            l.add(Key("", K_LAYER, 1f, arg = 4, icon = R.drawable.ic_emoji))
            if (mode == 2) l.add(Key("EN", K_LAYER, 1f, arg = 0)) else l.add(Key("\u0DC3\u0DD2", K_LAYER, 1f, arg = 3))
        }
        l.add(Key(",", K_CHAR))
        l.add(Key("", K_SPACE, if (mode == 1) 4.6f else 3.4f))
        l.add(Key(".", K_CHAR, alts = "?!:;'\"-@"))
        l.add(Key("", K_ENTER, 1.4f, icon = R.drawable.ic_enter))
        return l
    }

    private fun build(): List<List<Key>> {
        val out = ArrayList<List<Key>>()
        val delKey = Key("", K_DEL, 1.5f, icon = R.drawable.ic_backspace)
        when (layer) {
            0 -> {
                if (numRow) out.add(chars("1234567890"))
                out.add(chars("qwertyuiop", if (numRow) "" else "1234567890"))
                out.add(listOf(Key("", K_PAD, 0.5f)) + chars("asdfghjkl") + Key("", K_PAD, 0.5f))
                out.add(listOf(Key("", K_SHIFT, 1.5f, icon = R.drawable.ic_shift)) + chars("zxcvbnm") + delKey)
                out.add(bottom(0))
            }
            1 -> {
                out.add(chars("1234567890"))
                out.add(chars("@#\$_&-+()/"))
                out.add(listOf(Key("=\\<", K_LAYER, 1.5f, arg = 2)) + chars("*\"':;!?") + delKey)
                out.add(bottom(1))
            }
            2 -> {
                out.add(chars("~`|\u2022\u221A\u03C0\u00F7\u00D7\u00B6\u2206"))
                out.add(chars("\u00A3\u00A2\u20AC\u00A5^\u00B0={}\\"))
                out.add(listOf(Key("123", K_LAYER, 1.5f, arg = 1)) + chars("%\u00A9\u00AE\u2122[]<>") + delKey)
                out.add(bottom(1))
            }
            3 -> {
                out.add(chars("\u0D85\u0D86\u0D87\u0D88\u0D89\u0D8A\u0D8B\u0D8C\u0D91\u0D92"))
                out.add(chars("\u0D9A\u0D9C\u0DA0\u0DA2\u0DA7\u0DA9\u0DAD\u0DAF\u0DB4\u0DB6"))
                out.add(chars("\u0DB8\u0DBA\u0DBB\u0DBD\u0DC0\u0DC3\u0DC4\u0DC5\u0DB1\u0D82"))
                val marks = listOf("\u0DCF", "\u0DD0", "\u0DD1", "\u0DD2", "\u0DD3", "\u0DD4", "\u0DD6", "\u0DD9", "\u0DDC", "\u0DCA")
                out.add(marks.map { m -> Key("\u25CC" + m, K_CHAR, out = m) } + Key("", K_DEL, 1.3f, icon = R.drawable.ic_backspace))
                out.add(bottom(2))
            }
            else -> {
                val list = emojiCats[emojiCat].second
                emojiPages = (list.size + 23) / 24
                emojiPage = emojiPage.coerceIn(0, emojiPages - 1)
                val slice = list.drop(emojiPage * 24).take(24)
                for (chunk in slice.chunked(8)) {
                    val r = ArrayList<Key>()
                    for (e in chunk) r.add(Key(e, K_CHAR))
                    while (r.size < 8) r.add(Key("", K_PAD))
                    out.add(r)
                }
                val bot = ArrayList<Key>()
                bot.add(Key("ABC", K_LAYER, 1.5f, arg = 0))
                emojiCats.forEachIndexed { i, cat -> bot.add(Key(cat.first, K_CAT, 1.2f, arg = i)) }
                bot.add(Key("", K_DEL, 1.3f, icon = R.drawable.ic_backspace))
                out.add(bot)
            }
        }
        return out
    }

    private fun rebuild() {
        rows = build()
        layoutKeys()
        invalidate()
    }

    private fun layoutKeys() {
        val w = width.toFloat()
        if (w <= 0f || rows.isEmpty()) return
        val padX = dp(4f)
        val gap = dp(5f)
        val top = toolH
        val rh = (height - top - dp(6f)) / rows.size
        rows.forEachIndexed { i, row ->
            var sum = 0f
            for (k in row) sum += k.w
            val avail = w - 2 * padX - gap * (row.size - 1)
            var x = padX
            for (k in row) {
                val kw = avail * k.w / sum
                k.r.set(x, top + i * rh + gap / 2f, x + kw, top + (i + 1) * rh - gap / 2f)
                x += kw + gap
            }
        }
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val base = dp(46f) * heightPct / 100f
        val n = if (numRow) 5 else 4
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), (toolH + base * n + dp(8f)).toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        layoutKeys()
        buildBg()
        sweep = SweepGradient(w / 2f, h / 2f, rainbow, null)
    }

    // ---------- drawing ----------

    private fun blend(a: Int, b: Int, f: Float): Int = Color.rgb(
        (Color.red(a) * (1 - f) + Color.red(b) * f).toInt(),
        (Color.green(a) * (1 - f) + Color.green(b) * f).toInt(),
        (Color.blue(a) * (1 - f) + Color.blue(b) * f).toInt()
    )

    private fun drawable(res: Int): Drawable = cache.getOrPut(res) { context.getDrawable(res)!!.mutate() }

    private fun icon(c: Canvas, res: Int, r: RectF, color: Int, size: Float) {
        val d = drawable(res)
        d.setTint(color)
        val l = (r.centerX() - size / 2f).toInt()
        val t = (r.centerY() - size / 2f).toInt()
        d.setBounds(l, t, l + size.toInt(), t + size.toInt())
        d.draw(c)
    }

    private fun baseline(cy: Float): Float = cy - (tp.descent() + tp.ascent()) / 2f

    override fun onDraw(c: Canvas) {
        val t = theme
        val bs = bgScaled
        p.style = Paint.Style.FILL
        if (bs != null) {
            p.shader = null
            c.drawBitmap(bs, 0f, 0f, null)
            p.color = Color.argb(bgDim * 255 / 100, 0, 0, 0)
            c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p)
        } else {
            p.shader = LinearGradient(0f, 0f, 0f, height.toFloat(), t.bgTop, t.bgBottom, Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p)
            p.shader = null
        }
        drawToolbar(c)
        for (row in rows) for (k in row) drawKey(c, k)
        drawPopups(c)
        if ((rgb and 2) != 0) drawBorder(c)
        if (rgb != 0 && isShown) postInvalidateOnAnimation()
    }

    private fun withA(color: Int, a: Int): Int = (color and 0x00FFFFFF) or (a shl 24)

    private fun drawShape(c: Canvas, r: RectF, dy: Float, paint: Paint) {
        KeyShape.draw(c, r.left, r.top + dy, r.right, r.bottom + dy, shape, dm, paint, path)
    }

    private fun phase(): Float = SystemClock.uptimeMillis() * 0.001f * rgbSpeed * 18f

    private fun buildBg() {
        bgScaled?.recycle()
        bgScaled = null
        val raw = bgRaw ?: return
        if (width <= 0 || height <= 0) return
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val sc = maxOf(width.toFloat() / raw.width, height.toFloat() / raw.height)
        val sw = width / sc
        val sh = height / sc
        srcR.set(((raw.width - sw) / 2f).toInt(), ((raw.height - sh) / 2f).toInt(), ((raw.width + sw) / 2f).toInt(), ((raw.height + sh) / 2f).toInt())
        dstR.set(0, 0, width, height)
        cv.drawBitmap(raw, srcR, dstR, bmpPaint)
        bgScaled = bmp
    }

    private fun drawBorder(c: Canvas) {
        val sh = sweep ?: return
        sweepM.setRotate(phase(), width / 2f, height / 2f)
        sh.setLocalMatrix(sweepM)
        p.shader = sh
        p.style = Paint.Style.STROKE
        rect.set(dp(1.5f), dp(1.5f), width - dp(1.5f), height - dp(1.5f))
        p.strokeWidth = dp(7f)
        p.alpha = 50
        c.drawRoundRect(rect, dp(14f), dp(14f), p)
        p.strokeWidth = dp(2.5f)
        p.alpha = 255
        c.drawRoundRect(rect, dp(14f), dp(14f), p)
        p.shader = null
        p.style = Paint.Style.FILL
    }

    private fun drawRgbKey(c: Canvas, k: Key, isP: Boolean) {
        hsv[0] = ((k.r.centerX() / width) * 300f + (k.r.centerY() / height) * 90f + phase()) % 360f
        hsv[1] = 0.85f
        hsv[2] = 1f
        val col = Color.HSVToColor(hsv)
        p.style = Paint.Style.STROKE
        if (isP) {
            p.strokeWidth = dp(6f)
            p.color = withA(col, 90)
            drawShape(c, k.r, 0f, p)
        }
        p.strokeWidth = dp(1.8f)
        p.color = col
        drawShape(c, k.r, 0f, p)
        p.style = Paint.Style.FILL
    }

    private fun drawToolbar(c: Canvas) {
        val t = theme
        val h = toolH
        val s = dp(28f)
        val lx = dp(12f)
        val ly = (h - s) / 2f
        rect.set(lx, ly, lx + s, ly + s)
        p.color = Color.argb(70, 0, 0, 0)
        c.drawRoundRect(rect, dp(8f), dp(8f), p)
        val lg = drawable(R.drawable.ic_logo)
        lg.setTintList(null)
        lg.setBounds((lx - s * 0.2f).toInt(), (ly - s * 0.2f).toInt(), (lx + s * 1.2f).toInt(), (ly + s * 1.2f).toInt())
        lg.draw(c)

        tp.textAlign = Paint.Align.LEFT
        tp.letterSpacing = 0.12f
        tp.textSize = dp(12f)
        tp.color = t.text
        tp.alpha = 200
        c.drawText("ZETA BOARD", lx + s + dp(8f), baseline(h / 2f), tp)
        tp.letterSpacing = 0f

        for (i in 0..2) {
            val right = width - dp(6f) - (2 - i) * dp(44f)
            toolRects[i].set(right - dp(44f), 0f, right, h)
            if (pressedTool == i) {
                p.color = t.accent
                p.alpha = 90
                c.drawCircle(toolRects[i].centerX(), h / 2f, dp(18f), p)
            }
            icon(c, toolIcons[i], toolRects[i], t.text, dp(20f))
        }
        if (layer == 4) {
            tp.textAlign = Paint.Align.RIGHT
            tp.textSize = dp(11f)
            tp.color = t.text
            tp.alpha = 150
            c.drawText("${emojiPage + 1}/$emojiPages", toolRects[0].left - dp(6f), baseline(h / 2f), tp)
        }
        tp.textAlign = Paint.Align.CENTER
    }

    private fun drawKey(c: Canvas, k: Key) {
        if (k.kind == K_PAD) return
        val t = theme
        val isP = k === pressed && altList.isEmpty()
        val flat = layer == 4 && k.kind == K_CHAR
        val catSel = k.kind == K_CAT && k.arg == emojiCat
        val shiftOn = k.kind == K_SHIFT && shift > 0
        val bg = when {
            isP -> blend(t.key, t.accent, 0.55f)
            k.kind == K_ENTER || catSel || shiftOn -> t.accent
            k.kind == K_CHAR || k.kind == K_SPACE -> t.key
            else -> t.special
        }
        if (!flat || isP) {
            val photo = bgScaled != null
            if (shape == 4) {
                p.color = withA(bg, if (isP) 200 else 70)
                drawShape(c, k.r, 0f, p)
                p.style = Paint.Style.STROKE
                p.strokeWidth = dp(1.6f)
                p.color = withA(if (k.kind == K_CHAR || k.kind == K_SPACE) t.accent else t.text, 190)
                drawShape(c, k.r, 0f, p)
                p.style = Paint.Style.FILL
            } else {
                if (!photo) {
                    p.color = Color.argb(55, 0, 0, 0)
                    drawShape(c, k.r, dp(1.6f), p)
                }
                p.color = if (photo) withA(bg, 185) else bg
                drawShape(c, k.r, 0f, p)
            }
            if ((rgb and 1) != 0) drawRgbKey(c, k, isP)
        }
        val fg = if (k.kind == K_ENTER || catSel || shiftOn) t.accentText else t.text
        if (k.icon != 0) {
            icon(c, k.icon, k.r, fg, dp(22f))
            if (k.kind == K_SHIFT && shift == 2) {
                p.color = fg
                c.drawRoundRect(k.r.centerX() - dp(7f), k.r.bottom - dp(8f), k.r.centerX() + dp(7f), k.r.bottom - dp(6f), dp(1f), dp(1f), p)
            }
            return
        }
        var label = k.label
        tp.color = fg
        val rh = k.r.height()
        when {
            k.kind == K_SPACE -> {
                label = if (layer == 3) "\u0DC3\u0DD2\u0D82\u0DC4\u0DBD" else "ZETA BOARD"
                tp.textSize = rh * 0.24f
                tp.alpha = 140
            }
            layer == 4 && k.kind == K_CHAR -> tp.textSize = rh * 0.55f
            k.kind == K_CHAR -> {
                if (layer == 0 && shift > 0) label = label.uppercase()
                tp.textSize = rh * 0.42f
            }
            else -> tp.textSize = rh * 0.28f
        }
        c.drawText(label, k.r.centerX(), baseline(k.r.centerY()), tp)
    }

    private fun altLeft(k: Key): Float {
        val total = dp(40f) * altList.size
        val maxL = (width - total - dp(4f)).coerceAtLeast(dp(4f))
        return (k.r.centerX() - total / 2f).coerceIn(dp(4f), maxL)
    }

    private fun altSelAt(k: Key, x: Float): Int =
        ((x - altLeft(k)) / dp(40f)).toInt().coerceIn(0, (altList.size - 1).coerceAtLeast(0))

    private fun drawPopups(c: Canvas) {
        val k = pressed ?: return
        val t = theme
        if (altList.isNotEmpty()) {
            val iw = dp(40f)
            val ih = dp(52f)
            val l = altLeft(k)
            val top = (k.r.top - ih - dp(6f)).coerceAtLeast(0f)
            altRect.set(l, top, l + iw * altList.size, top + ih)
            p.color = Color.argb(80, 0, 0, 0)
            c.drawRoundRect(altRect.left, altRect.top + dp(2f), altRect.right, altRect.bottom + dp(2f), dp(12f), dp(12f), p)
            p.color = t.special
            c.drawRoundRect(altRect, dp(12f), dp(12f), p)
            altList.forEachIndexed { i, s ->
                if (i == altSel) {
                    rect.set(l + i * iw + dp(3f), top + dp(3f), l + (i + 1) * iw - dp(3f), top + ih - dp(3f))
                    p.color = t.accent
                    c.drawRoundRect(rect, dp(9f), dp(9f), p)
                }
                tp.color = if (i == altSel) t.accentText else t.text
                tp.textSize = dp(22f)
                c.drawText(s, l + i * iw + iw / 2f, baseline(top + ih / 2f), tp)
            }
        } else if (preview && k.kind == K_CHAR && layer != 4) {
            val bw = k.r.width() * 1.4f
            val bh = k.r.height() * 1.25f
            val bl = (k.r.centerX() - bw / 2f).coerceIn(dp(2f), (width - bw - dp(2f)).coerceAtLeast(dp(2f)))
            val bt = (k.r.top - bh - dp(4f)).coerceAtLeast(0f)
            rect.set(bl, bt, bl + bw, bt + bh)
            p.color = Color.argb(80, 0, 0, 0)
            c.drawRoundRect(bl, bt + dp(2f), bl + bw, bt + bh + dp(2f), dp(12f), dp(12f), p)
            p.color = t.special
            c.drawRoundRect(rect, dp(12f), dp(12f), p)
            var s = k.out
            if (layer == 0 && shift > 0) s = s.uppercase()
            tp.color = t.text
            tp.textSize = dp(28f)
            c.drawText(s, rect.centerX(), baseline(rect.centerY()), tp)
        }
    }

    // ---------- touch ----------

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> down(e.getPointerId(0), e.x, e.y)
            MotionEvent.ACTION_POINTER_DOWN -> {
                finish(true)
                val i = e.actionIndex
                down(e.getPointerId(i), e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_MOVE -> {
                val i = e.findPointerIndex(activeId)
                if (i >= 0) move(e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_UP -> finish(true)
            MotionEvent.ACTION_POINTER_UP -> if (e.getPointerId(e.actionIndex) == activeId) finish(true)
            MotionEvent.ACTION_CANCEL -> finish(false)
        }
        return true
    }

    private fun findKey(x: Float, y: Float): Key? {
        val g = dp(3f)
        for (row in rows) for (k in row) {
            if (x >= k.r.left - g && x <= k.r.right + g && y >= k.r.top - g && y <= k.r.bottom + g) return k
        }
        return null
    }

    private fun feedback() {
        if (haptic) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        if (sound) {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.playSoundEffect(AudioManager.FX_KEYPRESS_STANDARD, 0.5f)
        }
    }

    private fun down(id: Int, x: Float, y: Float) {
        handler.removeCallbacks(longPress)
        handler.removeCallbacks(repeat)
        activeId = id
        downX = x
        curX = x
        lastCurX = x
        cursorMoved = false
        altList = emptyList()
        altSel = -1
        if (y < toolH) {
            pressed = null
            pressedTool = toolRects.indexOfFirst { it.contains(x, y) }
            if (pressedTool >= 0) feedback()
            invalidate()
            return
        }
        pressedTool = -1
        val k = findKey(x, y)
        if (k == null || k.kind == K_PAD) {
            pressed = null
            invalidate()
            return
        }
        pressed = k
        feedback()
        if (k.kind == K_DEL) {
            listener?.onAction(DEL)
            handler.postDelayed(repeat, 400)
        } else if (k.alts.isNotEmpty()) {
            handler.postDelayed(longPress, 380)
        }
        invalidate()
    }

    private fun move(x: Float, y: Float) {
        curX = x
        if (pressedTool >= 0) return
        val k = pressed ?: return
        if (altList.isNotEmpty()) {
            altSel = altSelAt(k, x)
            invalidate()
            return
        }
        if (k.kind == K_SPACE) {
            if (abs(x - lastCurX) > dp(14f)) {
                listener?.onAction(if (x > lastCurX) RIGHT else LEFT)
                lastCurX = x
                cursorMoved = true
            }
            return
        }
        if (layer == 4 || k.kind == K_DEL) return
        val nk = findKey(x, y)
        if (nk != null && nk !== k && nk.kind != K_PAD && nk.kind != K_DEL) {
            handler.removeCallbacks(longPress)
            pressed = nk
            if (nk.alts.isNotEmpty()) handler.postDelayed(longPress, 380)
            invalidate()
        }
    }

    private fun finish(doCommit: Boolean) {
        handler.removeCallbacks(longPress)
        handler.removeCallbacks(repeat)
        val k = pressed
        val tool = pressedTool
        pressed = null
        pressedTool = -1
        activeId = -1
        if (doCommit) {
            if (tool >= 0) {
                toolTap(tool)
            } else if (k != null) {
                if (altList.isNotEmpty()) {
                    val s = altList.getOrNull(altSel)
                    if (s != null) {
                        listener?.onText(s)
                        afterSentence = false
                        if (shift == 1) {
                            shift = 0
                            rebuild()
                        }
                    }
                } else if (cursorMoved || k.kind == K_DEL) {
                    // nothing to commit
                } else if (layer == 4 && k.kind == K_CHAR && abs(curX - downX) > dp(50f)) {
                    emojiPage += if (curX < downX) 1 else -1
                    rebuild()
                } else {
                    commitKey(k)
                }
            }
        }
        altList = emptyList()
        altSel = -1
        invalidate()
    }

    private fun toolTap(i: Int) {
        when (i) {
            0 -> listener?.onAction(PASTE)
            1 -> {
                val n = (Prefs.getI(context, "theme", 0) + 1) % Themes.all.size
                Prefs.setI(context, "theme", n)
                theme = Themes.get(n)
            }
            2 -> listener?.onAction(SETTINGS)
        }
    }

    private fun commitKey(k: Key) {
        when (k.kind) {
            K_CHAR -> {
                val s = if (layer == 0 && shift > 0) k.out.uppercase() else k.out
                listener?.onText(s)
                afterSentence = s == "." || s == "!" || s == "?"
                if (shift == 1) {
                    shift = 0
                    rebuild()
                }
            }
            K_SPACE -> {
                listener?.onText(" ")
                val auto = afterSentence
                afterSentence = false
                if (auto && layer == 0 && shift == 0) {
                    shift = 1
                    rebuild()
                }
            }
            K_ENTER -> {
                listener?.onAction(ENTER)
                afterSentence = false
            }
            K_SHIFT -> {
                val now = SystemClock.uptimeMillis()
                shift = if (shift == 1 && now - lastShiftTap < 350) 2 else if (shift == 0) 1 else 0
                lastShiftTap = now
                rebuild()
            }
            K_LAYER -> {
                layer = k.arg
                rebuild()
            }
            K_CAT -> {
                emojiCat = k.arg
                emojiPage = 0
                rebuild()
            }
        }
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacksAndMessages(null)
        super.onDetachedFromWindow()
    }
}
