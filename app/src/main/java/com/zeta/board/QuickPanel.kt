package com.zeta.board

import android.content.Context
import android.content.res.ColorStateList
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
import android.widget.ScrollView
import android.widget.TextView

/** Quick Text panel on the keyboard: pick a saved shortcut and its full message is inserted into the focused field. */
class QuickPanel(ctx: Context, private val cb: Callback) : FrameLayout(ctx) {

    interface Callback {
        /** Inserts the text into the focused field. Returns false when that is not possible. */
        fun onQuickPick(text: String): Boolean
        fun onQuickClose()
        fun onQuickManage()
        fun onQuickTyping(active: Boolean)
    }

    private val d = ctx.resources.displayMetrics.density
    private fun dp(v: Int): Int = (v * d).toInt()
    private val M = ViewGroup.LayoutParams.MATCH_PARENT
    private val W = ViewGroup.LayoutParams.WRAP_CONTENT
    private val handler = Handler(Looper.getMainLooper())
    private var th: KbTheme = Themes.get(0)

    private val main = LinearLayout(ctx)
    private val titleView = TextView(ctx)
    private val fieldRow = LinearLayout(ctx)
    private val field = TextView(ctx)
    private val fieldBg = GradientDrawable()
    private val scroll = ScrollView(ctx)
    private val list = LinearLayout(ctx)
    private val toast = TextView(ctx)
    private lateinit var backBtn: ImageView
    private lateinit var searchBtn: ImageView
    private lateinit var manageBtn: ImageView

    private var searching = false
    private var typing = false
    private var cursorOn = true
    private var blocked = false
    private var loaded = false
    private var req = 0
    private var result = QuickList(emptyList(), 0, 0)
    private val buffer = StringBuilder()

    private val obs: () -> Unit = { refresh() }

    private val blink = object : Runnable {
        override fun run() {
            cursorOn = !cursorOn
            refreshField()
            handler.postDelayed(this, 520)
        }
    }

    init {
        QuickRepo.init(ctx)
        main.orientation = LinearLayout.VERTICAL
        val header = LinearLayout(ctx)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        header.setPadding(dp(6), 0, dp(6), 0)
        backBtn = iconBtn(R.drawable.ic_keyboard, 40) { cb.onQuickClose() }
        searchBtn = iconBtn(R.drawable.ic_search, 40) { toggleSearch() }
        manageBtn = iconBtn(R.drawable.ic_edit, 40) { cb.onQuickManage() }
        titleView.text = "Quick Text"
        titleView.textSize = 16f
        titleView.typeface = Typeface.DEFAULT_BOLD
        header.addView(backBtn, LinearLayout.LayoutParams(dp(40), dp(40)))
        val tlp = LinearLayout.LayoutParams(0, W, 1f)
        tlp.leftMargin = dp(6)
        header.addView(titleView, tlp)
        header.addView(searchBtn, LinearLayout.LayoutParams(dp(40), dp(40)))
        header.addView(manageBtn, LinearLayout.LayoutParams(dp(40), dp(40)))
        main.addView(header, LinearLayout.LayoutParams(M, dp(44)))

        fieldRow.orientation = LinearLayout.VERTICAL
        fieldRow.setPadding(dp(10), dp(2), dp(10), dp(6))
        fieldRow.visibility = View.GONE
        field.textSize = 14f
        field.maxLines = 1
        field.ellipsize = TextUtils.TruncateAt.START
        field.setPadding(dp(14), dp(10), dp(14), dp(10))
        fieldBg.cornerRadius = dp(14).toFloat()
        field.background = fieldBg
        field.setOnClickListener { if (searching) setTyping(true) }
        fieldRow.addView(field, LinearLayout.LayoutParams(M, W))
        main.addView(fieldRow, LinearLayout.LayoutParams(M, W))

        scroll.isVerticalScrollBarEnabled = false
        scroll.overScrollMode = View.OVER_SCROLL_NEVER
        scroll.isFillViewport = true
        list.orientation = LinearLayout.VERTICAL
        list.setPadding(0, 0, 0, dp(10))
        scroll.addView(list, ViewGroup.LayoutParams(M, M))
        main.addView(scroll, LinearLayout.LayoutParams(M, 0, 1f))
        addView(main, LayoutParams(M, M))

        toast.textSize = 12f
        toast.typeface = Typeface.DEFAULT_BOLD
        toast.setPadding(dp(16), dp(8), dp(16), dp(8))
        toast.alpha = 0f
        val toastLp = LayoutParams(W, W, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        toastLp.bottomMargin = dp(14)
        addView(toast, toastLp)

        applyTheme(th)
    }

    // ---------- lifecycle ----------

    /** [isBlocked] is true in password / private fields: nothing is listed or inserted there. */
    fun open(isBlocked: Boolean) {
        blocked = isBlocked
        searching = false
        typing = false
        loaded = false
        buffer.setLength(0)
        fieldRow.visibility = View.GONE
        QuickRepo.observe(obs)
        render()
        refresh()
    }

    fun close() {
        handler.removeCallbacks(blink)
        toast.animate().cancel()
        toast.alpha = 0f
        typing = false
        cursorOn = false
        searching = false
        buffer.setLength(0)
        fieldRow.visibility = View.GONE
        QuickRepo.unobserve(obs)
        req++
    }

    private fun refresh() {
        if (blocked) {
            render()
            return
        }
        val my = ++req
        QuickRepo.load(buffer.toString(), true, 300) { r ->
            if (my == req) {
                result = r
                loaded = true
                render()
            }
        }
    }

    // ---------- keyboard input routed from the host ----------

    fun insert(s: String) {
        if (!typing) return
        buffer.append(s)
        if (buffer.length > 60) buffer.setLength(60)
        refreshField()
        refresh()
    }

    fun backspace() {
        if (!typing || buffer.isEmpty()) return
        val n = Emojis.lastClusterLength(buffer).coerceIn(1, buffer.length)
        buffer.setLength(buffer.length - n)
        refreshField()
        refresh()
    }

    fun enter() {
        if (searching) setTyping(false)
    }

    private fun setTyping(v: Boolean) {
        if (typing == v) {
            refreshField()
            return
        }
        typing = v
        handler.removeCallbacks(blink)
        if (v) {
            cursorOn = true
            handler.postDelayed(blink, 520)
        } else {
            cursorOn = false
        }
        refreshField()
        cb.onQuickTyping(v)
    }

    private fun toggleSearch() {
        if (blocked) return
        if (searching) {
            searching = false
            fieldRow.visibility = View.GONE
            val had = buffer.isNotEmpty()
            buffer.setLength(0)
            setTyping(false)
            if (had) refresh()
        } else {
            searching = true
            fieldRow.visibility = View.VISIBLE
            setTyping(true)
        }
    }

    private fun refreshField() {
        val t = buffer.toString()
        val cur = if (typing && cursorOn) "\u258F" else ""
        if (t.isEmpty()) {
            field.text = cur + " Search shortcuts"
            field.setTextColor(withAlpha(th.text, 120))
        } else {
            field.text = t + cur
            field.setTextColor(th.text)
        }
        fieldBg.setStroke(dp(1), if (typing) th.accent else withAlpha(th.text, 40))
    }

    // ---------- rendering ----------

    private fun render() {
        list.removeAllViews()
        if (blocked) {
            list.addView(emptyState("Quick Text is off here", "Shortcuts are never used in password or private fields.", false), LinearLayout.LayoutParams(M, M))
            return
        }
        if (!loaded) return
        if (result.items.isEmpty()) {
            val q = buffer.toString().trim()
            when {
                q.isNotEmpty() -> list.addView(emptyState("No matches", "Try a different search.", false), LinearLayout.LayoutParams(M, M))
                result.total == 0 -> list.addView(
                    emptyState("No shortcuts yet", "Save a message like .welcome once,\nthen insert it anywhere with one tap.", true),
                    LinearLayout.LayoutParams(M, M)
                )
                else -> list.addView(emptyState("All shortcuts are turned off", "Turn one on in Quick Text Manager.", true), LinearLayout.LayoutParams(M, M))
            }
            return
        }
        for (item in result.items) list.addView(card(item))
    }

    private fun emptyState(title: String, sub: String, manage: Boolean): View {
        val box = LinearLayout(context)
        box.orientation = LinearLayout.VERTICAL
        box.gravity = Gravity.CENTER
        box.setPadding(dp(24), dp(8), dp(24), dp(8))
        val circle = FrameLayout(context)
        val cb2 = GradientDrawable()
        cb2.shape = GradientDrawable.OVAL
        cb2.setColor(withAlpha(th.accent, 45))
        circle.background = cb2
        val ic = ImageView(context)
        ic.setImageResource(R.drawable.ic_quick)
        ic.setColorFilter(th.accent)
        ic.setPadding(dp(14), dp(14), dp(14), dp(14))
        circle.addView(ic, LayoutParams(M, M))
        box.addView(circle, LinearLayout.LayoutParams(dp(56), dp(56)))
        val t1 = TextView(context)
        t1.text = title
        t1.textSize = 15f
        t1.typeface = Typeface.DEFAULT_BOLD
        t1.setTextColor(th.text)
        t1.gravity = Gravity.CENTER
        t1.setPadding(0, dp(8), 0, dp(3))
        val t2 = TextView(context)
        t2.text = sub
        t2.textSize = 12f
        t2.gravity = Gravity.CENTER
        t2.setTextColor(withAlpha(th.text, 150))
        box.addView(t1)
        box.addView(t2)
        if (manage) {
            val b = TextView(context)
            b.text = "Open Quick Text Manager"
            b.textSize = 13f
            b.typeface = Typeface.DEFAULT_BOLD
            b.gravity = Gravity.CENTER
            b.setTextColor(th.accentText)
            b.setPadding(dp(18), dp(9), dp(18), dp(9))
            val g = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(th.accent, mix(th.accent, android.graphics.Color.WHITE, 0.25f)))
            g.cornerRadius = dp(30).toFloat()
            b.background = g
            b.setOnClickListener { cb.onQuickManage() }
            val lp = LinearLayout.LayoutParams(W, W)
            lp.topMargin = dp(12)
            box.addView(b, lp)
        }
        return box
    }

    private fun card(item: QuickItem): View {
        val c = LinearLayout(context)
        c.orientation = LinearLayout.VERTICAL
        c.setPadding(dp(14), dp(10), dp(14), dp(10))
        val shape = GradientDrawable()
        shape.cornerRadius = dp(16).toFloat()
        shape.setColor(th.key)
        shape.setStroke(dp(1), withAlpha(th.text, 28))
        c.background = RippleDrawable(ColorStateList.valueOf(withAlpha(th.accent, 70)), shape, null)
        c.isClickable = true
        c.setOnClickListener {
            if (!cb.onQuickPick(item.msg)) flash("Can't insert here")
        }
        val lp = LinearLayout.LayoutParams(M, W)
        lp.setMargins(dp(10), dp(4), dp(10), dp(4))
        c.layoutParams = lp

        val head = LinearLayout(context)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        val chip = TextView(context)
        chip.text = item.sc
        chip.textSize = 12.5f
        chip.typeface = Typeface.DEFAULT_BOLD
        chip.setTextColor(th.accent)
        chip.maxLines = 1
        chip.setPadding(dp(10), dp(3), dp(10), dp(3))
        val cbg = GradientDrawable()
        cbg.cornerRadius = dp(20).toFloat()
        cbg.setColor(withAlpha(th.accent, 38))
        chip.background = cbg
        head.addView(chip, LinearLayout.LayoutParams(W, W))
        if (item.desc.isNotEmpty()) {
            val ds = TextView(context)
            ds.text = item.desc
            ds.textSize = 12f
            ds.maxLines = 1
            ds.ellipsize = TextUtils.TruncateAt.END
            ds.setTextColor(withAlpha(th.text, 170))
            val dlp = LinearLayout.LayoutParams(0, W, 1f)
            dlp.leftMargin = dp(10)
            head.addView(ds, dlp)
        }
        c.addView(head)

        val tx = TextView(context)
        tx.text = if (item.msg.length > 240) item.msg.take(240) + "\u2026" else item.msg
        tx.textSize = 13.5f
        tx.maxLines = 2
        tx.ellipsize = TextUtils.TruncateAt.END
        tx.setTextColor(th.text)
        tx.setPadding(0, dp(6), 0, 0)
        c.addView(tx)
        return c
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

    // ---------- styling helpers ----------

    private fun styleIcon(v: ImageView, tint: Int) {
        v.setColorFilter(tint)
        val mask = GradientDrawable()
        mask.shape = GradientDrawable.OVAL
        mask.setColor(android.graphics.Color.WHITE)
        v.background = RippleDrawable(ColorStateList.valueOf(withAlpha(th.accent, 90)), null, mask)
    }

    private fun iconBtn(res: Int, size: Int, onClick: () -> Unit): ImageView {
        val v = ImageView(context)
        v.setImageResource(res)
        val pad = dp(size) * 27 / 100
        v.setPadding(pad, pad, pad, pad)
        styleIcon(v, th.text)
        v.setOnClickListener { onClick() }
        return v
    }

    fun applyTheme(t: KbTheme) {
        th = t
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(t.bgTop, t.bgBottom))
        titleView.setTextColor(t.text)
        styleIcon(backBtn, t.text)
        styleIcon(searchBtn, t.text)
        styleIcon(manageBtn, t.text)
        fieldBg.setColor(withAlpha(t.text, 18))
        val tb = GradientDrawable()
        tb.cornerRadius = dp(20).toFloat()
        tb.setColor(mix(t.bgTop, t.accent, 0.5f))
        toast.background = tb
        toast.setTextColor(t.accentText)
        refreshField()
        if (loaded || blocked) render()
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacksAndMessages(null)
        QuickRepo.unobserve(obs)
        super.onDetachedFromWindow()
    }
}
