package com.zeta.board

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView

/** ZETA TRANSLATE panel: type or paste text, pick languages, translate, insert into the focused field. */
class TranslatePanel(ctx: Context, private val cb: Callback) : FrameLayout(ctx) {

    interface Callback {
        /** Inserts text into the focused field. False when there is no field or it is private. */
        fun onTranslateInsert(text: String): Boolean
        fun onTranslateClose()
        fun onTranslateTyping(active: Boolean)
    }

    private enum class Mode { MAIN, PICK_FROM, PICK_TO, HISTORY }

    private val d = ctx.resources.displayMetrics.density
    private fun dp(v: Int): Int = (v * d).toInt()
    private val M = ViewGroup.LayoutParams.MATCH_PARENT
    private val W = ViewGroup.LayoutParams.WRAP_CONTENT
    private val handler = Handler(Looper.getMainLooper())
    private var th: KbTheme = Themes.get(0)
    private val purple = Color.parseColor("#8B5CF6")
    private val cyan = Color.parseColor("#22D3EE")

    private val title = TextView(ctx)
    private val scroll = ScrollView(ctx)
    private val body = LinearLayout(ctx)
    private val toast = TextView(ctx)
    private lateinit var backBtn: ImageView
    private lateinit var histBtn: ImageView

    private var mode = Mode.MAIN
    private var typing = false
    private var cursorOn = true
    private var blocked = false
    private var loading = false
    private var from = "auto"
    private var to = "en"
    private var resultText = ""
    private var detected: String? = null
    private var error: String? = null
    private var inserted = false
    private val buffer = StringBuilder()
    private var inputView: TextView? = null

    private val blink = object : Runnable {
        override fun run() {
            cursorOn = !cursorOn
            refreshInput()
            handler.postDelayed(this, 520)
        }
    }

    init {
        val main = LinearLayout(ctx)
        main.orientation = LinearLayout.VERTICAL
        val header = LinearLayout(ctx)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        header.setPadding(dp(6), 0, dp(6), 0)
        backBtn = iconBtn(R.drawable.ic_keyboard) { onBack() }
        histBtn = iconBtn(R.drawable.ic_info) { if (mode == Mode.HISTORY) show(Mode.MAIN) else show(Mode.HISTORY) }
        title.textSize = 16f
        title.typeface = Typeface.DEFAULT_BOLD
        header.addView(backBtn, LinearLayout.LayoutParams(dp(40), dp(40)))
        val tlp = LinearLayout.LayoutParams(0, W, 1f)
        tlp.leftMargin = dp(6)
        header.addView(title, tlp)
        header.addView(histBtn, LinearLayout.LayoutParams(dp(40), dp(40)))
        main.addView(header, LinearLayout.LayoutParams(M, dp(44)))

        scroll.isVerticalScrollBarEnabled = false
        scroll.overScrollMode = View.OVER_SCROLL_NEVER
        body.orientation = LinearLayout.VERTICAL
        body.setPadding(dp(10), dp(2), dp(10), dp(10))
        scroll.addView(body, ViewGroup.LayoutParams(M, W))
        main.addView(scroll, LinearLayout.LayoutParams(M, 0, 1f))
        addView(main, LayoutParams(M, M))

        toast.textSize = 12f
        toast.typeface = Typeface.DEFAULT_BOLD
        toast.setPadding(dp(16), dp(8), dp(16), dp(8))
        toast.alpha = 0f
        val tl = LayoutParams(W, W, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        tl.bottomMargin = dp(14)
        addView(toast, tl)
        applyTheme(th)
    }

    // ---------- lifecycle ----------

    fun open(isBlocked: Boolean) {
        blocked = isBlocked
        val langs = Translator.languages(context)
        from = Prefs.getS(context, "tr_from", "auto").let { if (it == "auto" || langs.any { l -> l.code == it }) it else "auto" }
        to = Prefs.getS(context, "tr_to", "en").let { if (langs.any { l -> l.code == it }) it else "en" }
        if (blocked) { buffer.setLength(0); resultText = ""; error = null; detected = null }
        loading = false
        show(Mode.MAIN)
    }

    fun close() {
        Translator.cancel()
        handler.removeCallbacks(blink)
        toast.animate().cancel()
        toast.alpha = 0f
        typing = false
        cursorOn = false
        loading = false
    }

    private fun onBack() {
        if (mode != Mode.MAIN) show(Mode.MAIN) else cb.onTranslateClose()
    }

    private fun show(m: Mode) {
        mode = m
        if (m != Mode.MAIN) setTyping(false)
        render()
    }

    // ---------- keyboard input routed from the host ----------

    fun insert(s: String) {
        if (!typing || mode != Mode.MAIN) return
        if (buffer.length + s.length > 1000) { flash("Text limit reached"); return }
        buffer.append(s)
        inserted = false
        refreshInput()
    }

    fun backspace() {
        if (!typing || buffer.isEmpty()) return
        val n = Emojis.lastClusterLength(buffer).coerceIn(1, buffer.length)
        buffer.setLength(buffer.length - n)
        inserted = false
        refreshInput()
    }

    fun enter() {
        if (typing) { setTyping(false); doTranslate() }
    }

    private fun setTyping(v: Boolean) {
        if (typing == v) { refreshInput(); return }
        typing = v
        handler.removeCallbacks(blink)
        cursorOn = v
        if (v) handler.postDelayed(blink, 520)
        refreshInput()
        cb.onTranslateTyping(v)
    }

    // ---------- actions ----------

    private fun doTranslate() {
        setTyping(false)
        if (blocked) return
        error = null
        if (buffer.isBlank()) { resultText = ""; error = "Type or paste some text first."; render(); return }
        loading = true
        inserted = false
        render()
        val src = buffer.toString()
        val f = from
        val t = to
        Translator.run(context, src, f, t) { res, err ->
            loading = false
            if (err != null) {
                resultText = ""
                error = err.message
            } else if (res != null) {
                resultText = res.text
                detected = res.detected
                error = null
                rememberPair(f, t)
                TrHistory.add(context, THistItem(System.currentTimeMillis(), src, res.text, f, t))
            }
            if (mode == Mode.MAIN) render()
        }
    }

    private fun rememberPair(f: String, t: String) {
        Prefs.setS(context, "tr_from", f)
        Prefs.setS(context, "tr_to", t)
        for (c in listOf(f, t)) {
            if (c == "auto") continue
            val l = Prefs.getS(context, "tr_recent", "").split(",").filter { it.isNotEmpty() && it != c }.toMutableList()
            l.add(0, c)
            Prefs.setS(context, "tr_recent", l.take(4).joinToString(","))
        }
    }

    private fun swap() {
        if (from == "auto") {
            val dl = detected
            if (dl == null || Langs.byCode(dl.lowercase().take(2)) == null) { flash("Pick a source language to swap"); return }
            from = dl.lowercase().take(2)
        }
        val t = from; from = to; to = t
        if (resultText.isNotEmpty()) { buffer.setLength(0); buffer.append(resultText) }
        resultText = ""
        error = null
        Translator.cancel()
        loading = false
        Prefs.setS(context, "tr_from", from)
        Prefs.setS(context, "tr_to", to)
        render()
    }

    private fun copy(text: String) {
        try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Zeta Translate", text))
            flash("Copied")
        } catch (e: Exception) {
            flash("Couldn't copy")
        }
    }

    private fun paste() {
        try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val t = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
            if (t.isNullOrEmpty()) { flash("Clipboard is empty"); return }
            if (t.length > 1000) { flash("Text too long (max 1000)"); return }
            buffer.setLength(0)
            buffer.append(t)
            resultText = ""
            error = null
            render()
        } catch (e: Exception) {
            flash("Couldn't read clipboard")
        }
    }

    private fun doInsert() {
        if (resultText.isEmpty() || inserted) return
        if (cb.onTranslateInsert(resultText)) {
            inserted = true
            cb.onTranslateClose()
        } else flash("Can't insert here")
    }

    // ---------- rendering ----------

    private fun render() {
        body.removeAllViews()
        inputView = null
        title.text = when (mode) {
            Mode.MAIN -> "ZETA TRANSLATE"
            Mode.PICK_FROM -> "Translate from"
            Mode.PICK_TO -> "Translate to"
            Mode.HISTORY -> "History"
        }
        scroll.scrollTo(0, 0)
        if (blocked) {
            body.addView(emptyState("Translate is off here", "Text in password or private fields is never translated."))
            return
        }
        when (mode) {
            Mode.MAIN -> renderMain()
            Mode.PICK_FROM -> renderPicker(true)
            Mode.PICK_TO -> renderPicker(false)
            Mode.HISTORY -> renderHistory()
        }
    }

    private fun langName(c: String): String = Langs.byCode(c)?.name ?: c

    private fun renderMain() {
        val row = LinearLayout(context)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.addView(langChip(langName(from), true) { show(Mode.PICK_FROM) }, LinearLayout.LayoutParams(0, dp(40), 1f))
        val sw = iconBtn(R.drawable.ic_back) { swap() }
        sw.rotation = 0f
        sw.setColorFilter(cyan)
        row.addView(sw, LinearLayout.LayoutParams(dp(40), dp(40)))
        row.addView(langChip(langName(to), false) { show(Mode.PICK_TO) }, LinearLayout.LayoutParams(0, dp(40), 1f))
        body.addView(row, LinearLayout.LayoutParams(M, W))
        if (from == "auto" && detected != null && resultText.isNotEmpty())
            body.addView(small("Detected: ${detected!!}"))

        // input card
        val card = card()
        val inp = TextView(context)
        inp.textSize = 15f
        inp.maxLines = 5
        inp.ellipsize = TextUtils.TruncateAt.START
        inp.minHeight = dp(52)
        inp.setOnClickListener { setTyping(true) }
        card.addView(inp, LinearLayout.LayoutParams(M, W))
        inputView = inp
        val acts = LinearLayout(context)
        acts.orientation = LinearLayout.HORIZONTAL
        acts.gravity = Gravity.END
        acts.addView(textBtn("Paste", false) { paste() })
        acts.addView(textBtn("Clear", false) {
            buffer.setLength(0); resultText = ""; error = null; detected = null; Translator.cancel(); loading = false; render()
        })
        card.addView(acts, LinearLayout.LayoutParams(M, W))
        body.addView(card, cardLp())
        refreshInput()

        // translate button
        val go = textBtn(if (loading) "Translating\u2026" else "Translate", true) { if (!loading) doTranslate() }
        val glp = LinearLayout.LayoutParams(M, dp(44))
        glp.topMargin = dp(8)
        body.addView(go, glp)

        // result area
        val rc = card()
        if (loading) {
            val pb = ProgressBar(context)
            pb.isIndeterminate = true
            pb.indeterminateTintList = ColorStateList.valueOf(cyan)
            rc.addView(pb, LinearLayout.LayoutParams(dp(28), dp(28)).also { it.gravity = Gravity.CENTER_HORIZONTAL; it.topMargin = dp(10); it.bottomMargin = dp(10) })
        } else if (error != null) {
            val e = TextView(context)
            e.text = error
            e.textSize = 13.5f
            e.setTextColor(Color.parseColor("#FB7185"))
            e.setPadding(0, dp(6), 0, dp(6))
            rc.addView(e)
        } else if (resultText.isNotEmpty()) {
            val r = TextView(context)
            r.text = resultText
            r.textSize = 15.5f
            r.setTextColor(th.text)
            r.setTextIsSelectable(false)
            r.setPadding(0, dp(4), 0, dp(4))
            rc.addView(r)
            val acts2 = LinearLayout(context)
            acts2.orientation = LinearLayout.HORIZONTAL
            acts2.gravity = Gravity.END
            acts2.addView(textBtn("Copy", false) { copy(resultText) })
            acts2.addView(textBtn("Insert translation", true) { doInsert() })
            rc.addView(acts2, LinearLayout.LayoutParams(M, W))
        } else {
            rc.addView(small("Your translation will appear here."))
        }
        val rlp = cardLp()
        body.addView(rc, rlp)
    }

    private fun refreshInput() {
        val v = inputView ?: return
        val cur = if (typing && cursorOn) "\u258F" else ""
        if (buffer.isEmpty()) {
            v.text = cur + " Tap to type, or paste"
            v.setTextColor(withAlpha(th.text, 120))
        } else {
            v.text = buffer.toString() + cur
            v.setTextColor(th.text)
        }
        (v.parent as? View)?.let { (it.background as? GradientDrawable)?.setStroke(dp(1), if (typing) cyan else withAlpha(th.text, 40)) }
    }

    private fun renderPicker(isFrom: Boolean) {
        val all = Translator.languages(context)
        val cur = if (isFrom) from else to
        val recent = Prefs.getS(context, "tr_recent", "").split(",").mapNotNull { c -> all.firstOrNull { it.code == c } }
        val pick = { l: Lang ->
            if (isFrom) from = l.code else to = l.code
            if (from != "auto" && from == to) {
                if (isFrom) {
                    to = if (from == "en") "si" else "en"
                } else {
                    from = "auto"
                }
            }
            Prefs.setS(context, "tr_from", from)
            Prefs.setS(context, "tr_to", to)
            resultText = ""
            error = null
            show(Mode.MAIN)
        }
        if (isFrom) body.addView(langRow(Langs.AUTO, cur == "auto", pick))
        if (recent.isNotEmpty()) {
            body.addView(section("Recent"))
            for (l in recent) body.addView(langRow(l, cur == l.code, pick))
        }
        body.addView(section("All languages"))
        for (l in all) body.addView(langRow(l, cur == l.code, pick))
    }

    private fun renderHistory() {
        if (!TrHistory.enabled(context)) {
            body.addView(emptyState("History is off", "Turn it on in Zeta Board \u2192 Translate settings."))
            return
        }
        val list = TrHistory.load(context)
        if (list.isEmpty()) { body.addView(emptyState("No translations yet", "Your translations are saved here, on this phone only.")); return }
        body.addView(textBtn("Clear all history", false) { TrHistory.clear(context); render() })
        for (h in list) {
            val c = card()
            c.addView(small("${langName(h.from)} \u2192 ${langName(h.to)}"))
            val a = TextView(context)
            a.text = h.src
            a.textSize = 13f
            a.maxLines = 2
            a.ellipsize = TextUtils.TruncateAt.END
            a.setTextColor(withAlpha(th.text, 170))
            c.addView(a)
            val b = TextView(context)
            b.text = h.dst
            b.textSize = 14.5f
            b.maxLines = 3
            b.ellipsize = TextUtils.TruncateAt.END
            b.setTextColor(th.text)
            b.setPadding(0, dp(4), 0, 0)
            c.addView(b)
            val acts = LinearLayout(context)
            acts.orientation = LinearLayout.HORIZONTAL
            acts.gravity = Gravity.END
            acts.addView(textBtn("Copy", false) { copy(h.dst) })
            acts.addView(textBtn("Reuse", false) {
                buffer.setLength(0); buffer.append(h.src); resultText = h.dst; error = null
                from = h.from; to = h.to; inserted = false
                show(Mode.MAIN)
            })
            acts.addView(textBtn("Delete", false) { TrHistory.delete(context, h.id); render() })
            c.addView(acts, LinearLayout.LayoutParams(M, W))
            body.addView(c, cardLp())
        }
    }

    // ---------- small view helpers ----------

    private fun card(): LinearLayout {
        val c = LinearLayout(context)
        c.orientation = LinearLayout.VERTICAL
        c.setPadding(dp(14), dp(10), dp(14), dp(8))
        val g = GradientDrawable()
        g.cornerRadius = dp(16).toFloat()
        g.setColor(th.key)
        g.setStroke(dp(1), withAlpha(th.text, 40))
        c.background = g
        return c
    }

    private fun cardLp(): LinearLayout.LayoutParams {
        val lp = LinearLayout.LayoutParams(M, W)
        lp.topMargin = dp(8)
        return lp
    }

    private fun small(t: String): TextView {
        val v = TextView(context)
        v.text = t
        v.textSize = 12f
        v.setTextColor(withAlpha(th.text, 150))
        v.setPadding(dp(2), dp(4), dp(2), dp(4))
        return v
    }

    private fun section(t: String): TextView {
        val v = small(t.uppercase())
        v.typeface = Typeface.DEFAULT_BOLD
        v.setPadding(dp(2), dp(10), dp(2), dp(2))
        return v
    }

    private fun langChip(t: String, left: Boolean, click: () -> Unit): View {
        val v = TextView(context)
        v.text = t
        v.textSize = 14f
        v.typeface = Typeface.DEFAULT_BOLD
        v.gravity = Gravity.CENTER
        v.maxLines = 1
        v.setTextColor(th.text)
        val g = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
            if (left) intArrayOf(withAlpha(cyan, 60), withAlpha(cyan, 25)) else intArrayOf(withAlpha(purple, 25), withAlpha(purple, 70)))
        g.cornerRadius = dp(20).toFloat()
        g.setStroke(dp(1), withAlpha(if (left) cyan else purple, 150))
        v.background = RippleDrawable(ColorStateList.valueOf(withAlpha(th.accent, 70)), g, null)
        v.setOnClickListener { click() }
        return v
    }

    private fun langRow(l: Lang, selected: Boolean, click: (Lang) -> Unit): View {
        val r = LinearLayout(context)
        r.orientation = LinearLayout.HORIZONTAL
        r.gravity = Gravity.CENTER_VERTICAL
        r.setPadding(dp(14), 0, dp(14), 0)
        val g = GradientDrawable()
        g.cornerRadius = dp(14).toFloat()
        g.setColor(if (selected) withAlpha(cyan, 45) else th.key)
        r.background = RippleDrawable(ColorStateList.valueOf(withAlpha(th.accent, 70)), g, null)
        val n = TextView(context)
        n.text = l.name
        n.textSize = 14.5f
        n.setTextColor(th.text)
        r.addView(n, LinearLayout.LayoutParams(0, W, 1f))
        val nn = TextView(context)
        nn.text = l.native
        nn.textSize = 13f
        nn.setTextColor(withAlpha(th.text, 150))
        r.addView(nn)
        r.setOnClickListener { click(l) }
        val lp = LinearLayout.LayoutParams(M, dp(42))
        lp.topMargin = dp(4)
        r.layoutParams = lp
        return r
    }

    private fun textBtn(t: String, primary: Boolean, click: () -> Unit): TextView {
        val v = TextView(context)
        v.text = t
        v.textSize = 13.5f
        v.typeface = Typeface.DEFAULT_BOLD
        v.gravity = Gravity.CENTER
        v.setPadding(dp(14), dp(8), dp(14), dp(8))
        if (primary) {
            v.setTextColor(Color.WHITE)
            val g = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(purple, cyan))
            g.cornerRadius = dp(24).toFloat()
            v.background = RippleDrawable(ColorStateList.valueOf(withAlpha(Color.WHITE, 90)), g, null)
        } else {
            v.setTextColor(cyan)
            v.background = RippleDrawable(ColorStateList.valueOf(withAlpha(cyan, 70)), null, null)
        }
        v.setOnClickListener { click() }
        return v
    }

    private fun emptyState(t: String, sub: String): View {
        val box = LinearLayout(context)
        box.orientation = LinearLayout.VERTICAL
        box.gravity = Gravity.CENTER
        box.setPadding(dp(24), dp(24), dp(24), dp(8))
        val t1 = TextView(context)
        t1.text = t
        t1.textSize = 15f
        t1.typeface = Typeface.DEFAULT_BOLD
        t1.setTextColor(th.text)
        t1.gravity = Gravity.CENTER
        val t2 = TextView(context)
        t2.text = sub
        t2.textSize = 12f
        t2.gravity = Gravity.CENTER
        t2.setTextColor(withAlpha(th.text, 150))
        box.addView(t1)
        box.addView(t2)
        return box
    }

    private fun flash(msg: String) {
        toast.text = msg
        toast.animate().cancel()
        toast.alpha = 0f
        toast.translationY = dp(8).toFloat()
        toast.animate().alpha(1f).translationY(0f).setStartDelay(0).setDuration(160).withEndAction {
            toast.animate().alpha(0f).setStartDelay(1100).setDuration(260).start()
        }.start()
    }

    private fun styleIcon(v: ImageView, tint: Int) {
        v.setColorFilter(tint)
        val mask = GradientDrawable()
        mask.shape = GradientDrawable.OVAL
        mask.setColor(Color.WHITE)
        v.background = RippleDrawable(ColorStateList.valueOf(withAlpha(th.accent, 90)), null, mask)
    }

    private fun iconBtn(res: Int, onClick: () -> Unit): ImageView {
        val v = ImageView(context)
        v.setImageResource(res)
        val pad = dp(40) * 27 / 100
        v.setPadding(pad, pad, pad, pad)
        styleIcon(v, th.text)
        v.setOnClickListener { onClick() }
        return v
    }

    fun applyTheme(t: KbTheme) {
        th = t
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(t.bgTop, t.bgBottom))
        title.setTextColor(t.text)
        styleIcon(backBtn, t.text)
        styleIcon(histBtn, t.text)
        val tb = GradientDrawable()
        tb.cornerRadius = dp(20).toFloat()
        tb.setColor(mix(t.bgTop, t.accent, 0.5f))
        toast.background = tb
        toast.setTextColor(t.accentText)
        render()
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacksAndMessages(null)
        Translator.cancel()
        super.onDetachedFromWindow()
    }
}
