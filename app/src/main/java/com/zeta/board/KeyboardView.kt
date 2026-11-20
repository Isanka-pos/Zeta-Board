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
        const val CLIPBOARD = 4
        const val LEFT = 5
        const val RIGHT = 6
        const val STICKER = 7
        const val QUICK = 8
        const val TRANSLATE = 9
        /** The Quick Text suggestion on the toolbar was tapped. */
        const val SUGGEST = 10
        /** The tool list button (last icon on the toolbar) was tapped. */
        const val TOOLS = 11
        /** The status message on the toolbar (translating, error) was tapped. */
        const val STATUS = 12
        /** The mic (voice typing) was tapped. */
        const val VOICE = 13
        /** The Status Saver tool was used. */
        const val STATUSES = 14
        /** The Singlish typing tool was used (switches Singlish -> Sinhala typing on or off). */
        const val SINGLISH = 15
        const val STATUS_BUSY = 0
        const val STATUS_INFO = 1
        const val STATUS_ERROR = 2
        private const val TOOL_SUG = 99

        private const val K_CHAR = 0
        private const val K_SHIFT = 1
        private const val K_DEL = 2
        private const val K_LAYER = 3
        private const val K_SPACE = 4
        private const val K_ENTER = 5
        private const val K_CAT = 6
        private const val K_PAD = 7
        private const val K_STYLE = 8
        private const val K_FONTKEY = 9
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
        var altStr: List<String>? = null
    }

    var listener: Listener? = null
    var onTheme: ((KbTheme) -> Unit)? = null
    var theme: KbTheme = Themes.get(0)
        set(v) {
            field = v
            invalidate()
            onTheme?.invoke(v)
        }

    private val dm = ctx.resources.displayMetrics.density
    private fun dp(v: Float) = v * dm

    // layers: 0 abc, 1 symbols, 2 more symbols, 3 sinhala, 4 emoji, 5 text style
    private var layer = 0
    private var shift = 0
    private var emojiCat = 1
    private var emojiEmpty = false
    private var swiping = false
    private var downInGrid = false
    private val emojiCache = HashMap<Int, List<String>>()
    private var textStyle = 0
    private var styleList: List<Int> = listOf(0)
    private val brandTf: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private var keyFontSel = -1
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
    // emoji are drawn with their own paint so a custom key font never changes how they look
    private val ep = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = AppleEmoji.typeface(ctx) ?: Typeface.DEFAULT
    }
    // phone's own emoji font: used for any emoji the Apple font cannot draw as one glyph (never broken pieces)
    private val epSys = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT
    }
    private val pickCache = HashMap<String, Paint?>()
    private var fontReq = false
    private val rect = RectF()
    private val altRect = RectF()
    private val toolRects = Array(Tools.MAX_PINS + 1) { RectF() }
    // the tools pinned to the toolbar (the user changes them in the tool list); the tool list button always comes after them
    private var pins: List<Int> = Tools.DEFAULT
    private val cache = HashMap<Int, Drawable>()

    private var pressed: Key? = null
    private var pressedTool = -1

    // Quick Text suggestion: while a typed shortcut matches, this strip replaces the logo, title and icons
    private var suggestion: String? = null
    private var sugFlat = ""
    // status message (Zeta Translate): shown on the same strip and wins over the suggestion
    private var statusMsg: String? = null
    private var statusKind = STATUS_INFO
    private var statusIcon = R.drawable.ic_translate
    private val sugRect = RectF()

    // tool list: the icons that do not fit on the toolbar open from the last (menu) icon
    private var menuOpen = false
    private var pressedMenu = -1
    private val menuIcons = intArrayOf(R.drawable.ic_font, R.drawable.ic_tune)
    private val menuLabels = arrayOf("Text style", "Settings")
    private val menuRects = Array(2) { RectF() }
    private val menuBox = RectF()
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

    // ---------- emoji helpers ----------

    /** Switches the emoji paint to the Apple font (or back to the system one) and rebuilds the emoji lists. */
    private fun applyEmojiFont() {
        val want = AppleEmoji.typeface(context)
        val cur = ep.typeface
        if (want != null && cur !== want) {
            ep.typeface = want
            pickCache.clear(); emojiCache.clear()
        } else if (want == null && cur !== Typeface.DEFAULT) {
            ep.typeface = Typeface.DEFAULT
            pickCache.clear(); emojiCache.clear()
        }
        if (AppleEmoji.enabled(context) && !AppleEmoji.ready && !fontReq) {
            fontReq = true
            AppleEmoji.load(context) {
                fontReq = false
                applyEmojiFont()
                if (layer == 4) rebuild() // emoji panel is open: rebuild its pages with the new font
                invalidate()
            }
        }
    }

    /** True when [p] draws [e] as a single emoji (a broken sequence would come out several glyphs wide). */
    private fun oneGlyph(p: Paint, e: String): Boolean {
        val old = p.textSize
        p.textSize = 100f
        val w = p.measureText(e)
        p.textSize = old
        return w in 1f..135f
    }

    /** Which paint draws [e] cleanly: Apple font first, phone font as fallback, null when nothing can. */
    private fun pick(e: String): Paint? {
        if (pickCache.containsKey(e)) return pickCache[e]
        val r: Paint? = when {
            ep.hasGlyph(e) && oneGlyph(ep, e) -> ep
            epSys.hasGlyph(e) -> epSys
            else -> null
        }
        pickCache[e] = r
        return r
    }

    private fun supported(e: String): Boolean = pick(e) != null

    private fun emojiList(i: Int): List<String> {
        if (i == Emojis.RECENT) return Emojis.recents(context).filter { supported(it) }
        return emojiCache.getOrPut(i) { Emojis.cats[i].all.filter { supported(it) } }
    }

    private fun perPage(): Int = (if (numRow) 4 else 3) * 8

    private fun pagesOf(cat: Int): Int = ((emojiList(cat).size + perPage() - 1) / perPage()).coerceAtLeast(1)

    private fun emojiSwipe(dir: Int) {
        var cat = emojiCat
        var p = emojiPage + dir
        if (p >= emojiPages) {
            if (cat < Emojis.cats.size - 1) {
                cat++
                p = 0
            } else {
                p = emojiPages - 1
            }
        } else if (p < 0) {
            val pc = cat - 1
            if (pc >= 0 && emojiList(pc).isNotEmpty()) {
                cat = pc
                p = pagesOf(pc) - 1
            } else {
                p = 0
            }
        }
        emojiCat = cat
        emojiPage = p
        rebuild()
    }

    private val longPress = Runnable {
        val k = pressed
        val tone = k?.altStr
        if (k != null && tone != null && tone.isNotEmpty()) {
            altList = tone
            altSel = altSelAt(k, curX)
            invalidate()
        } else if (k != null && k.alts.isNotEmpty()) {
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
        applyEmojiFont()
        reload()
    }

    // ---------- public API ----------

    fun reload() {
        val c = context
        applyEmojiFont()
        numRow = Prefs.getB(c, "numrow", true)
        heightPct = Prefs.getI(c, "height", 100)
        haptic = Prefs.getB(c, "haptic", true)
        sound = Prefs.getB(c, "sound", false)
        preview = Prefs.getB(c, "preview", true)
        pins = Tools.pins(c)
        shape = Prefs.getI(c, "shape", 0)
        rgb = Prefs.getI(c, "rgb", 0)
        rgbSpeed = Prefs.getI(c, "rgbspeed", 5)
        bgDim = Prefs.getI(c, "bgdim", 35)
        val ksel = KbFonts.selected(c)
        if (ksel != keyFontSel) {
            keyFontSel = ksel
            tp.typeface = KbFonts.preset(ksel)
        }
        textStyle = Prefs.getI(c, "textstyle", 0)
        // only offer styles this phone can really draw (no empty boxes)
        styleList = TextStyles.names.indices.filter { TextStyles.supported(it, tp) }
        if (textStyle !in styleList) textStyle = 0
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

    /** The pinned tools changed (tool list): redraws the toolbar with the new set. */
    fun reloadTools() {
        pins = Tools.pins(context)
        pressedTool = -1
        invalidate()
    }

    /** Runs one tool, the same as tapping its icon on the toolbar. */
    fun runTool(id: Int) {
        when (id) {
            Tools.STICKER -> listener?.onAction(STICKER)
            Tools.CLIPBOARD -> listener?.onAction(CLIPBOARD)
            Tools.QUICK -> listener?.onAction(QUICK)
            Tools.TRANSLATE -> listener?.onAction(TRANSLATE)
            Tools.THEME -> {
                val n = (Prefs.getI(context, "theme", 0) + 1) % Themes.all.size
                Prefs.setI(context, "theme", n)
                theme = Themes.get(n)
            }
            Tools.STYLE -> {
                menuOpen = false
                layer = 5
                rebuild()
            }
            Tools.EMOJI -> {
                menuOpen = false
                layer = 4
                emojiPage = 0
                emojiCat = if (emojiList(Emojis.RECENT).isNotEmpty()) Emojis.RECENT else 1
                rebuild()
            }
            Tools.SETTINGS -> listener?.onAction(SETTINGS)
            Tools.VOICE -> listener?.onAction(VOICE)
            Tools.STATUS -> listener?.onAction(STATUSES)
            Tools.SINGLISH -> listener?.onAction(SINGLISH)
        }
        invalidate()
    }

    /** Shows [msg] on the toolbar in place of the icons, or brings the icons back when [msg] is null. */
    fun setSuggestion(msg: String?) {
        if (msg == suggestion) return
        suggestion = msg
        sugFlat = msg?.replace(Regex("\\s+"), " ")?.trim() ?: ""
        if (msg != null && pressedTool in toolRects.indices) pressedTool = -1
        if (!stripOn() && pressedTool == TOOL_SUG) pressedTool = -1
        if (msg != null) menuOpen = false
        invalidate()
    }

    private fun stripOn(): Boolean = statusMsg != null || suggestion != null

    /** Shows a short status on the toolbar in place of the icons ([STATUS_BUSY], [STATUS_INFO] or [STATUS_ERROR]); null clears it. */
    fun setStatus(msg: String?, kind: Int = STATUS_INFO, icon: Int = R.drawable.ic_translate) {
        if (msg == statusMsg && kind == statusKind && icon == statusIcon) return
        statusMsg = msg
        statusKind = kind
        statusIcon = icon
        if (msg != null && pressedTool in toolRects.indices) pressedTool = -1
        if (!stripOn() && pressedTool == TOOL_SUG) pressedTool = -1
        if (msg != null) menuOpen = false
        invalidate()
    }

    private fun singlishOn(): Boolean = Prefs.getB(context, "singlish", false)

    /** Singlish typing was switched on or off: capital letters are meaningful in Singlish, so no automatic capital. */
    fun onSinglishChanged() {
        if (singlishOn() && shift == 1) shift = 0
        rebuild()
    }

    fun startInput(l: Int, s: Int) {
        menuOpen = false
        layer = l
        shift = if (l == 0 && singlishOn()) 0 else s
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
            5 -> {
                for (chunk in styleList.chunked(4)) {
                    val r = ArrayList<Key>()
                    for (i in chunk) r.add(Key(TextStyles.sample(i), K_STYLE, 1f, arg = i))
                    while (r.size < 4) r.add(Key("", K_PAD))
                    out.add(r)
                }
                val bot = ArrayList<Key>()
                bot.add(Key("ABC", K_LAYER, 1.2f, arg = 0))
                bot.add(Key("", K_FONTKEY, 2.6f))
                bot.add(Key("", K_DEL, 1.2f, icon = R.drawable.ic_backspace))
                out.add(bot)
            }
            else -> {
                val eRows = if (numRow) 4 else 3
                val per = eRows * 8
                val list = emojiList(emojiCat)
                emojiEmpty = list.isEmpty()
                emojiPages = ((list.size + per - 1) / per).coerceAtLeast(1)
                emojiPage = emojiPage.coerceIn(0, emojiPages - 1)
                val slice = list.drop(emojiPage * per).take(per)
                for (r in 0 until eRows) {
                    val row = ArrayList<Key>()
                    for (col in 0 until 8) {
                        val e = slice.getOrNull(r * 8 + col)
                        row.add(if (e != null) Key(e, K_CHAR) else Key("", K_PAD))
                    }
                    out.add(row)
                }
                val bot = ArrayList<Key>()
                bot.add(Key("ABC", K_LAYER, 1.4f, arg = 0))
                Emojis.cats.forEachIndexed { i, cat -> bot.add(Key(cat.icon, K_CAT, 1f, arg = i)) }
                bot.add(Key("", K_DEL, 1.2f, icon = R.drawable.ic_backspace))
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
        val vgap = dp(5f)
        val top = toolH
        val rh = (height - top - dp(6f)) / rows.size
        rows.forEachIndexed { i, row ->
            val gap = if (layer == 4 && i == rows.size - 1) dp(1.5f) else dp(5f)
            var sum = 0f
            for (k in row) sum += k.w
            val avail = w - 2 * padX - gap * (row.size - 1)
            var x = padX
            for (k in row) {
                val kw = avail * k.w / sum
                k.r.set(x, top + i * rh + vgap / 2f, x + kw, top + (i + 1) * rh - vgap / 2f)
                x += kw + gap
            }
        }
    }

    fun preferredHeight(): Int {
        val base = dp(46f) * heightPct / 100f
        val n = if (numRow) 5 else 4
        return (toolH + base * n + dp(8f)).toInt()
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), preferredHeight())
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

    private fun baselineE(cy: Float, pp: Paint = ep): Float = cy - (pp.descent() + pp.ascent()) / 2f

    override fun onDraw(c: Canvas) {
        val t = theme
        val bs = bgScaled
        p.style = Paint.Style.FILL
        // The shared paint keeps the alpha of whatever was drawn last frame (shadows, popups, pressed
        // states). A shader is multiplied by that alpha, which made the whole keyboard see-through.
        p.color = Color.BLACK
        p.alpha = 255
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
        if (layer == 4 && emojiEmpty && rows.size > 1) {
            val ty = (rows.first().first().r.top + rows[rows.size - 2].first().r.bottom) / 2f
            tp.textSize = dp(13f)
            tp.color = t.text
            tp.alpha = 150
            c.drawText("No recent emoji yet", width / 2f, baseline(ty), tp)
        }
        drawPopups(c)
        if (menuOpen) drawMenu(c)
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

    private fun drawStrip(c: Canvas, text: String, tint: Int, iconRes: Int) {
        val t = theme
        val h = toolH
        val padX = dp(8f)
        sugRect.set(padX, dp(5f), width - padX, h - dp(4f))
        val down = pressedTool == TOOL_SUG
        p.shader = null
        p.style = Paint.Style.FILL
        p.color = tint
        p.alpha = if (down) 120 else 60
        c.drawRoundRect(sugRect, dp(12f), dp(12f), p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = dp(1f)
        p.color = tint
        p.alpha = 160
        c.drawRoundRect(sugRect, dp(12f), dp(12f), p)
        p.style = Paint.Style.FILL
        p.alpha = 255
        rect.set(sugRect.left + dp(6f), 0f, sugRect.left + dp(30f), h)
        icon(c, iconRes, rect, t.text, dp(18f))

        val savedTf = tp.typeface
        tp.typeface = brandTf
        tp.textAlign = Paint.Align.LEFT
        tp.letterSpacing = 0f
        tp.textSize = dp(13f)
        tp.color = t.text
        tp.alpha = 255
        val tx = sugRect.left + dp(36f)
        val maxW = (sugRect.right - dp(12f) - tx).coerceAtLeast(1f)
        var shown = text
        val n = tp.breakText(shown, true, maxW, null)
        if (n < shown.length) {
            var cut = (n - 1).coerceAtLeast(0)
            if (cut > 0 && Character.isHighSurrogate(shown[cut - 1])) cut--
            shown = shown.substring(0, cut) + "\u2026"
        }
        c.drawText(shown, tx, baseline(h / 2f), tp)
        tp.textAlign = Paint.Align.CENTER
        tp.typeface = savedTf
    }

    private fun drawToolbar(c: Canvas) {
        val st = statusMsg
        if (st != null) {
            drawStrip(c, st, if (statusKind == STATUS_ERROR) 0xFFE5484D.toInt() else theme.accent, statusIcon)
            return
        }
        if (suggestion != null) {
            drawStrip(c, sugFlat, theme.accent, R.drawable.ic_quick)
            return
        }
        val t = theme
        val h = toolH
        val s = dp(28f)
        val lx = dp(12f)
        val ly = (h - s) / 2f
        rect.set(lx, ly, lx + s, ly + s)
        p.color = Color.argb(70, 0, 0, 0)
        c.drawRoundRect(rect, dp(8f), dp(8f), p)
        Logo.draw(c, context, lx, ly, lx + s, ly + s, dp(8f))

        val tw = dp(40f)
        val cnt = pins.size + 1
        for (i in 0 until cnt) {
            val right = width - dp(6f) - (cnt - 1 - i) * tw
            toolRects[i].set(right - tw, 0f, right, h)
        }
        val savedTf = tp.typeface
        tp.typeface = brandTf
        tp.textAlign = Paint.Align.LEFT
        tp.letterSpacing = 0.1f
        tp.color = t.text
        tp.alpha = 200
        val title = when (layer) {
            4 -> Emojis.cats[emojiCat].name
            5 -> "TEXT STYLE"
            else -> "ZETA BOARD"
        }
        val pageW = if (layer == 4 && emojiPages > 1) dp(34f) else 0f
        val tx = lx + s + dp(8f)
        val maxW = toolRects[0].left - dp(6f) - pageW - tx
        var ts = dp(12f)
        tp.textSize = ts
        while (tp.measureText(title) > maxW && ts > dp(8f)) {
            ts -= dp(0.5f)
            tp.textSize = ts
        }
        val n = tp.breakText(title, true, maxW.coerceAtLeast(1f), null)
        c.drawText(title, 0, n.coerceAtLeast(0), tx, baseline(h / 2f), tp)
        tp.letterSpacing = 0f
        tp.typeface = savedTf

        for (i in 0 until cnt) {
            if (pressedTool == i) {
                p.color = t.accent
                p.alpha = 90
                c.drawCircle(toolRects[i].centerX(), h / 2f, dp(18f), p)
            }
            val lit = i < pins.size && pins[i] == Tools.SINGLISH && singlishOn()
            icon(c, if (i < pins.size) Tools.icon(pins[i]) else R.drawable.ic_menu, toolRects[i], if (lit) t.accent else t.text, dp(20f))
        }
        if (layer == 4 && emojiPages > 1) {
            tp.textAlign = Paint.Align.RIGHT
            tp.textSize = dp(11f)
            tp.color = t.text
            tp.alpha = 150
            c.drawText("${emojiPage + 1}/$emojiPages", toolRects[0].left - dp(6f), baseline(h / 2f), tp)
        }
        tp.textAlign = Paint.Align.CENTER
    }

    private fun drawMenu(c: Canvas) {
        val t = theme
        val rowH = dp(46f)
        val w = dp(176f)
        val right = width - dp(8f)
        val top = toolH
        menuBox.set(right - w, top, right, top + rowH * menuRects.size + dp(8f))
        p.style = Paint.Style.FILL
        p.shader = null
        p.color = Color.argb(90, 0, 0, 0)
        rect.set(menuBox.left, menuBox.top + dp(3f), menuBox.right, menuBox.bottom + dp(3f))
        c.drawRoundRect(rect, dp(16f), dp(16f), p)
        p.color = blend(t.bgTop, t.key, 0.7f)
        p.alpha = 255
        c.drawRoundRect(menuBox, dp(16f), dp(16f), p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = dp(1f)
        p.color = withA(t.text, 50)
        c.drawRoundRect(menuBox, dp(16f), dp(16f), p)
        p.style = Paint.Style.FILL
        val saved = tp.textAlign
        tp.textAlign = Paint.Align.LEFT
        tp.textSize = dp(14f)
        for (i in menuRects.indices) {
            val y = menuBox.top + dp(4f) + i * rowH
            menuRects[i].set(menuBox.left, y, menuBox.right, y + rowH)
            if (pressedMenu == i) {
                p.color = t.accent
                p.alpha = 80
                rect.set(menuBox.left + dp(4f), y + dp(2f), menuBox.right - dp(4f), y + rowH - dp(2f))
                c.drawRoundRect(rect, dp(12f), dp(12f), p)
                p.alpha = 255
            }
            rect.set(menuBox.left + dp(8f), y, menuBox.left + dp(48f), y + rowH)
            icon(c, menuIcons[i], rect, t.text, dp(20f))
            tp.color = t.text
            tp.alpha = 255
            c.drawText(menuLabels[i], menuBox.left + dp(52f), baseline(y + rowH / 2f), tp)
        }
        tp.textAlign = saved
    }

    private fun menuTap(i: Int) {
        when (i) {
            0 -> {
                layer = 5
                rebuild()
            }
            1 -> listener?.onAction(SETTINGS)
        }
    }

    private fun drawKey(c: Canvas, k: Key) {
        if (k.kind == K_PAD) return
        val t = theme
        val isP = k === pressed && altList.isEmpty() && !swiping
        val catSel = k.kind == K_CAT && k.arg == emojiCat
        val flat = layer == 4 && (k.kind == K_CHAR || (k.kind == K_CAT && !catSel))
        val shiftOn = k.kind == K_SHIFT && shift > 0
        val bg = when {
            isP -> blend(t.key, t.accent, 0.55f)
            k.kind == K_STYLE && k.arg == textStyle -> t.accent
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
        val styleSel = k.kind == K_STYLE && k.arg == textStyle
        val fg = if (k.kind == K_ENTER || catSel || shiftOn || styleSel) t.accentText else t.text
        if (k.icon != 0) {
            icon(c, k.icon, k.r, fg, dp(22f))
            if (k.kind == K_SHIFT && shift == 2) {
                p.color = fg
                c.drawRoundRect(k.r.centerX() - dp(7f), k.r.bottom - dp(8f), k.r.centerX() + dp(7f), k.r.bottom - dp(6f), dp(1f), dp(1f), p)
            }
            return
        }
        val rh = k.r.height()
        if (layer == 4 && (k.kind == K_CHAR || k.kind == K_CAT)) {
            val pe = pick(k.label) ?: ep
            pe.textSize = if (k.kind == K_CAT) minOf(rh * 0.5f, k.r.width() * 0.72f) else minOf(rh * 0.62f, k.r.width() * 0.64f)
            pe.alpha = if (k.kind == K_CAT && !catSel) 175 else 255
            c.drawText(k.label, k.r.centerX(), baselineE(k.r.centerY(), pe), pe)
            return
        }
        if (k.kind == K_STYLE) {
            tp.color = fg
            tp.alpha = 255
            tp.textSize = rh * 0.38f
            c.drawText(k.label, k.r.centerX(), baseline(k.r.top + rh * 0.42f), tp)
            tp.textSize = rh * 0.17f
            tp.alpha = 170
            c.drawText(TextStyles.names[k.arg], k.r.centerX(), baseline(k.r.top + rh * 0.8f), tp)
            return
        }
        if (k.kind == K_FONTKEY) {
            tp.color = fg
            tp.alpha = 255
            tp.textSize = rh * 0.27f
            c.drawText("Key font: " + KbFonts.names[keyFontSel.coerceAtLeast(0)] + "  \u25B6", k.r.centerX(), baseline(k.r.centerY()), tp)
            return
        }
        var label = k.label
        tp.color = fg
        when {
            k.kind == K_SPACE -> {
                label = if (layer == 3 || (layer == 0 && singlishOn())) "\u0DC3\u0DD2\u0D82\u0DC4\u0DBD" else TextStyles.convert(textStyle, "ZETA BOARD")
                tp.textSize = rh * 0.24f
                tp.alpha = 140
            }
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
                if (layer == 4) {
                    val pe = pick(s) ?: ep
                    pe.textSize = dp(24f)
                    pe.alpha = 255
                    c.drawText(s, l + i * iw + iw / 2f, baselineE(top + ih / 2f, pe), pe)
                } else {
                    tp.color = if (i == altSel) t.accentText else t.text
                    tp.textSize = dp(22f)
                    c.drawText(s, l + i * iw + iw / 2f, baseline(top + ih / 2f), tp)
                }
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
        swiping = false
        downInGrid = layer == 4 && y >= toolH && rows.size > 1 && y < rows[rows.size - 1].first().r.top
        if (menuOpen) {
            pressed = null
            pressedTool = -1
            pressedMenu = menuRects.indexOfFirst { it.contains(x, y) }
            if (pressedMenu >= 0) feedback() else menuOpen = false
            invalidate()
            return
        }
        if (y < toolH) {
            pressed = null
            pressedTool = if (stripOn()) TOOL_SUG else toolAt(x, y)
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
        } else if (layer == 4 && k.kind == K_CHAR) {
            if (k.altStr == null) k.altStr = Emojis.toneVariants(k.label) { supported(it) }
            if (k.altStr!!.isNotEmpty()) handler.postDelayed(longPress, 380)
        }
        invalidate()
    }

    private fun move(x: Float, y: Float) {
        curX = x
        if (pressedTool >= 0 || pressedMenu >= 0) return
        if (layer == 4 && altList.isEmpty() && downInGrid) {
            val pk = pressed
            if ((pk == null || pk.kind == K_CHAR) && abs(x - downX) > dp(14f)) {
                swiping = true
                handler.removeCallbacks(longPress)
                invalidate()
                return
            }
        }
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
        val pm = pressedMenu
        pressedMenu = -1
        val wasSwipe = swiping
        pressed = null
        pressedTool = -1
        activeId = -1
        swiping = false
        if (doCommit) {
            if (pm >= 0) {
                menuOpen = false
                menuTap(pm)
            } else if (tool >= 0) {
                toolTap(tool)
            } else if (altList.isNotEmpty()) {
                val s = altList.getOrNull(altSel)
                if (s != null) {
                    listener?.onText(s)
                    if (layer == 4) Emojis.addRecent(context, s)
                    afterSentence = false
                    if (shift == 1) {
                        shift = 0
                        rebuild()
                    }
                }
            } else if (layer == 4 && wasSwipe) {
                if (abs(curX - downX) > dp(50f)) emojiSwipe(if (curX < downX) 1 else -1)
            } else if (k != null) {
                if (cursorMoved || k.kind == K_DEL) {
                    // nothing to commit
                } else {
                    commitKey(k)
                }
            }
        }
        altList = emptyList()
        altSel = -1
        invalidate()
    }

    private fun toolAt(x: Float, y: Float): Int {
        for (i in 0..pins.size) if (toolRects[i].contains(x, y)) return i
        return -1
    }

    private fun toolTap(i: Int) {
        if (i == TOOL_SUG) {
            if (statusMsg != null) listener?.onAction(STATUS) else if (suggestion != null) listener?.onAction(SUGGEST)
            invalidate()
            return
        }
        if (stripOn()) return // the icons are hidden, a press that started on one must not fire
        if (i == pins.size) listener?.onAction(TOOLS) else if (i in pins.indices) runTool(pins[i])
        invalidate()
    }

    private fun commitKey(k: Key) {
        when (k.kind) {
            K_CHAR -> {
                val s = if (layer == 0 && shift > 0) k.out.uppercase() else k.out
                listener?.onText(s)
                if (layer == 4) Emojis.addRecent(context, s)
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
                if (auto && layer == 0 && shift == 0 && !singlishOn()) {
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
                if (layer == 4) {
                    emojiPage = 0
                    emojiCat = if (emojiList(Emojis.RECENT).isNotEmpty()) Emojis.RECENT else 1
                }
                rebuild()
            }
            K_STYLE -> {
                textStyle = k.arg
                Prefs.setI(context, "textstyle", textStyle)
                invalidate()
            }
            K_FONTKEY -> {
                val n = (KbFonts.selected(context) + 1) % KbFonts.names.size
                Prefs.setI(context, "keyfont", n)
                reload()
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
