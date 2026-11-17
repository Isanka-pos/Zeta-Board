package com.zeta.board

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
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal fun withAlpha(c: Int, a: Int): Int = (c and 0x00FFFFFF) or (a.coerceIn(0, 255) shl 24)

internal fun mix(a: Int, b: Int, f: Float): Int = Color.rgb(
    (Color.red(a) * (1 - f) + Color.red(b) * f).toInt(),
    (Color.green(a) * (1 - f) + Color.green(b) * f).toInt(),
    (Color.blue(a) * (1 - f) + Color.blue(b) * f).toInt()
)

class MaxHeightScroll(ctx: Context, private val maxH: Int) : ScrollView(ctx) {
    override fun onMeasure(w: Int, h: Int) {
        super.onMeasure(w, MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST))
    }
}

/** Clipboard manager UI: PINNED + RECENT CLIPS, search, edit, copy, pin, delete, clear with confirmation. */
class ClipPanel(ctx: Context, private val vm: ClipViewModel, private val cb: Callback) : FrameLayout(ctx) {

    interface Callback {
        fun onPaste(text: String)
        fun onCopy(text: String)
        fun onClose()
        fun onTyping(active: Boolean)
    }

    private val d = ctx.resources.displayMetrics.density
    private fun dp(v: Int): Int = (v * d).toInt()
    private val M = ViewGroup.LayoutParams.MATCH_PARENT
    private val W = ViewGroup.LayoutParams.WRAP_CONTENT
    private val handler = Handler(Looper.getMainLooper())
    private var th: KbTheme = Themes.get(0)

    private val main = LinearLayout(ctx)
    private val header = LinearLayout(ctx)
    private val titleView = TextView(ctx)
    private val fieldRow = LinearLayout(ctx)
    private val fieldScroll = MaxHeightScroll(ctx, (88 * d).toInt())
    private val field = TextView(ctx)
    private val fieldBg = GradientDrawable()
    private val editBar = LinearLayout(ctx)
    private val scroll = ScrollView(ctx)
    private val list = LinearLayout(ctx)
    private val toast = TextView(ctx)
    private val confirm = FrameLayout(ctx)
    private lateinit var backBtn: ImageView
    private lateinit var searchBtn: ImageView
    private lateinit var clearBtn: ImageView

    private var mode = 0 // 0 list, 1 search, 2 edit
    private var typing = false
    private var cursorOn = true
    private var animateNext = true
    private var editing: ClipItem? = null
    private val buffer = StringBuilder()
    private val expanded = HashSet<Long>()

    private val blink = object : Runnable {
        override fun run() {
            cursorOn = !cursorOn
            refreshField()
            handler.postDelayed(this, 520)
        }
    }

    init {
        ClipRepo.init(ctx)

        main.orientation = LinearLayout.VERTICAL
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        header.setPadding(dp(6), 0, dp(6), 0)
        backBtn = iconBtn(R.drawable.ic_keyboard, th.text, 40) { cb.onClose() }
        searchBtn = iconBtn(R.drawable.ic_search, th.text, 40) { toggleSearch() }
        clearBtn = iconBtn(R.drawable.ic_delete, th.text, 40) { askClear() }
        titleView.text = "Clipboard"
        titleView.textSize = 16f
        titleView.typeface = Typeface.DEFAULT_BOLD
        header.addView(backBtn, LinearLayout.LayoutParams(dp(40), dp(40)))
        val tlp = LinearLayout.LayoutParams(0, W, 1f)
        tlp.leftMargin = dp(6)
        header.addView(titleView, tlp)
        header.addView(searchBtn, LinearLayout.LayoutParams(dp(40), dp(40)))
        header.addView(clearBtn, LinearLayout.LayoutParams(dp(40), dp(40)))
        main.addView(header, LinearLayout.LayoutParams(M, dp(44)))

        fieldRow.orientation = LinearLayout.VERTICAL
        fieldRow.setPadding(dp(10), dp(2), dp(10), dp(6))
        fieldRow.visibility = View.GONE
        field.textSize = 14f
        field.setPadding(dp(14), dp(10), dp(14), dp(10))
        fieldBg.cornerRadius = dp(14).toFloat()
        field.background = fieldBg
        field.setOnClickListener { if (mode != 0) setTyping(true) }
        fieldScroll.isVerticalScrollBarEnabled = false
        fieldScroll.addView(field, ViewGroup.LayoutParams(M, W))
        fieldRow.addView(fieldScroll, LinearLayout.LayoutParams(M, W))
        main.addView(fieldRow, LinearLayout.LayoutParams(M, W))

        editBar.orientation = LinearLayout.HORIZONTAL
        editBar.setPadding(dp(10), 0, dp(10), dp(6))
        editBar.visibility = View.GONE
        main.addView(editBar, LinearLayout.LayoutParams(M, W))

        scroll.isVerticalScrollBarEnabled = false
        scroll.overScrollMode = View.OVER_SCROLL_NEVER
        list.orientation = LinearLayout.VERTICAL
        list.setPadding(0, 0, 0, dp(10))
        scroll.addView(list, ViewGroup.LayoutParams(M, W))
        main.addView(scroll, LinearLayout.LayoutParams(M, 0, 1f))
        addView(main, FrameLayout.LayoutParams(M, M))

        toast.textSize = 12f
        toast.typeface = Typeface.DEFAULT_BOLD
        toast.setPadding(dp(16), dp(8), dp(16), dp(8))
        toast.alpha = 0f
        val toastLp = FrameLayout.LayoutParams(W, W, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        toastLp.bottomMargin = dp(14)
        addView(toast, toastLp)

        confirm.visibility = View.GONE
        confirm.isClickable = true
        confirm.setBackgroundColor(Color.argb(160, 0, 0, 0))
        addView(confirm, FrameLayout.LayoutParams(M, M))

        applyTheme(th)
    }

    // ---------- lifecycle ----------

    fun open() {
        mode = 0
        buffer.setLength(0)
        editing = null
        typing = false
        fieldRow.visibility = View.GONE
        editBar.visibility = View.GONE
        scroll.visibility = View.VISIBLE
        confirm.visibility = View.GONE
        titleView.text = "Clipboard"
        animateNext = true
        vm.onChanged = { render() }
        if (vm.query.isNotEmpty()) vm.search("")
        vm.start()
        render()
    }

    fun close() {
        handler.removeCallbacks(blink)
        typing = false
        cursorOn = false
        mode = 0
        editing = null
        buffer.setLength(0)
        fieldRow.visibility = View.GONE
        editBar.visibility = View.GONE
        scroll.visibility = View.VISIBLE
        confirm.visibility = View.GONE
        vm.stop()
        vm.onChanged = null
    }

    // ---------- keyboard input routed from the host ----------

    fun insert(s: String) {
        if (!typing) return
        buffer.append(s)
        onBufferChanged()
    }

    fun backspace() {
        if (!typing || buffer.isEmpty()) return
        val n = if (buffer.length >= 2 && Character.isLowSurrogate(buffer[buffer.length - 1]) &&
            Character.isHighSurrogate(buffer[buffer.length - 2])) 2 else 1
        buffer.setLength(buffer.length - n)
        onBufferChanged()
    }

    fun enter() {
        if (mode == 1) setTyping(false) else if (mode == 2) insert("\n")
    }

    private fun onBufferChanged() {
        if (buffer.length > ClipRepo.MAX_TEXT) buffer.setLength(ClipRepo.MAX_TEXT)
        refreshField()
        fieldScroll.post { fieldScroll.fullScroll(View.FOCUS_DOWN) }
        if (mode == 1) vm.search(buffer.toString())
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
        cb.onTyping(v)
    }

    private fun refreshField() {
        val t = buffer.toString()
        val cur = if (typing && cursorOn) "\u258F" else ""
        if (t.isEmpty()) {
            field.text = cur + (if (mode == 1) " Search clipboard" else "")
            field.setTextColor(withAlpha(th.text, 120))
        } else {
            field.text = t + cur
            field.setTextColor(th.text)
        }
        fieldBg.setStroke(dp(1), if (typing) th.accent else withAlpha(th.text, 40))
    }

    // ---------- search / edit modes ----------

    private fun toggleSearch() {
        if (confirm.visibility == View.VISIBLE) return
        if (mode == 1) {
            mode = 0
            fieldRow.visibility = View.GONE
            setTyping(false)
            buffer.setLength(0)
            if (vm.query.isNotEmpty()) vm.search("")
        } else if (mode == 0) {
            mode = 1
            buffer.setLength(0)
            buffer.append(vm.query)
            fieldRow.visibility = View.VISIBLE
            setTyping(true)
        }
    }

    private fun startEdit(item: ClipItem) {
        if (vm.query.isNotEmpty()) vm.search("")
        mode = 2
        editing = item
        buffer.setLength(0)
        buffer.append(item.text)
        titleView.text = "Edit clip"
        fieldRow.visibility = View.VISIBLE
        editBar.visibility = View.VISIBLE
        scroll.visibility = View.GONE
        styleEditBar()
        setTyping(true)
        refreshField()
    }

    private fun finishEdit(save: Boolean) {
        val item = editing
        if (save && item != null && buffer.isNotBlank()) {
            vm.edit(item, buffer.toString())
            flash("Saved")
        }
        editing = null
        mode = 0
        buffer.setLength(0)
        titleView.text = "Clipboard"
        fieldRow.visibility = View.GONE
        editBar.visibility = View.GONE
        scroll.visibility = View.VISIBLE
        setTyping(false)
        render()
    }

    // ---------- clear confirmation ----------

    private fun askClear() {
        if (confirm.visibility == View.VISIBLE || mode == 2) return
        confirm.removeAllViews()
        val box = LinearLayout(context)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(20), dp(18), dp(20), dp(16))
        val bg = GradientDrawable()
        bg.cornerRadius = dp(22).toFloat()
        bg.setColor(mix(th.bgTop, th.key, 0.5f))
        bg.setStroke(dp(1), withAlpha(th.text, 40))
        box.background = bg
        val t1 = TextView(context)
        t1.text = "Clear recent clips?"
        t1.textSize = 16f
        t1.typeface = Typeface.DEFAULT_BOLD
        t1.setTextColor(th.text)
        val t2 = TextView(context)
        t2.text = "All unpinned clipboard history will be deleted. Your pinned items stay safe."
        t2.textSize = 13f
        t2.setTextColor(withAlpha(th.text, 170))
        t2.setPadding(0, dp(6), 0, dp(14))
        val row = LinearLayout(context)
        row.orientation = LinearLayout.HORIZONTAL
        val cancel = pillBtn("Cancel", false) { hideConfirm() }
        val del = pillBtn("Delete", true) {
            hideConfirm()
            vm.clearRecent()
            flash("Recent history cleared")
        }
        del.background = roundBg(Color.parseColor("#EF4444"), 16, 0, 0)
        del.setTextColor(Color.WHITE)
        val l1 = LinearLayout.LayoutParams(0, W, 1f)
        l1.rightMargin = dp(10)
        row.addView(cancel, l1)
        row.addView(del, LinearLayout.LayoutParams(0, W, 1f))
        box.addView(t1)
        box.addView(t2)
        box.addView(row)
        val lp = FrameLayout.LayoutParams(M, W, Gravity.CENTER)
        lp.leftMargin = dp(28)
        lp.rightMargin = dp(28)
        confirm.addView(box, lp)
        confirm.alpha = 0f
        confirm.visibility = View.VISIBLE
        confirm.animate().alpha(1f).setDuration(150).start()
        box.scaleX = 0.92f
        box.scaleY = 0.92f
        box.animate().scaleX(1f).scaleY(1f).setDuration(180).start()
    }

    private fun hideConfirm() {
        confirm.animate().alpha(0f).setDuration(120).withEndAction { confirm.visibility = View.GONE }.start()
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

    // ---------- rendering ----------

    private fun render() {
        if (mode == 2) return
        list.removeAllViews()
        if (!vm.loaded) return
        val pinned = vm.pinned
        val recent = vm.recent
        if (pinned.isEmpty() && recent.isEmpty()) {
            list.addView(emptyState())
            animateNext = false
            return
        }
        var i = 0
        if (pinned.isNotEmpty()) {
            list.addView(sectionHeader("PINNED", pinned.size, true))
            for (item in pinned) list.addView(card(item, i++))
        }
        if (recent.isNotEmpty()) {
            list.addView(sectionHeader("RECENT CLIPS", recent.size, false))
            for (item in recent) list.addView(card(item, i++))
        }
        animateNext = false
    }

    private fun sectionHeader(title: String, count: Int, pinned: Boolean): View {
        val t = TextView(context)
        t.text = "$title  \u00B7  $count"
        t.textSize = 11f
        t.typeface = Typeface.DEFAULT_BOLD
        t.letterSpacing = 0.12f
        t.setTextColor(if (pinned) th.accent else withAlpha(th.text, 150))
        t.setPadding(dp(18), dp(10), dp(18), dp(2))
        return t
    }

    private fun emptyState(): View {
        val box = LinearLayout(context)
        box.orientation = LinearLayout.VERTICAL
        box.gravity = Gravity.CENTER_HORIZONTAL
        box.setPadding(dp(24), dp(24), dp(24), dp(12))
        val circle = FrameLayout(context)
        val cb2 = GradientDrawable()
        cb2.shape = GradientDrawable.OVAL
        cb2.setColor(withAlpha(th.accent, 45))
        circle.background = cb2
        val ic = ImageView(context)
        ic.setImageResource(R.drawable.ic_paste)
        ic.setColorFilter(th.accent)
        ic.setPadding(dp(16), dp(16), dp(16), dp(16))
        circle.addView(ic, FrameLayout.LayoutParams(M, M))
        box.addView(circle, LinearLayout.LayoutParams(dp(64), dp(64)))
        val searching = vm.query.isNotEmpty()
        val t1 = TextView(context)
        t1.text = if (searching) "No matches" else "Clipboard is empty"
        t1.textSize = 16f
        t1.typeface = Typeface.DEFAULT_BOLD
        t1.setTextColor(th.text)
        t1.setPadding(0, dp(12), 0, dp(4))
        val t2 = TextView(context)
        t2.text = if (searching) "Try a different search." else "Copy any text and it will appear here.\nPin favourites to keep them forever."
        t2.textSize = 12f
        t2.gravity = Gravity.CENTER
        t2.setTextColor(withAlpha(th.text, 150))
        box.addView(t1)
        box.addView(t2)
        box.alpha = 0f
        box.translationY = dp(10).toFloat()
        box.animate().alpha(1f).translationY(0f).setDuration(260).start()
        return box
    }

    private fun card(item: ClipItem, idx: Int): View {
        val c = LinearLayout(context)
        c.orientation = LinearLayout.VERTICAL
        c.setPadding(dp(14), dp(10), dp(8), dp(4))
        val shape = GradientDrawable()
        shape.cornerRadius = dp(16).toFloat()
        shape.setColor(if (item.pinned) mix(th.key, th.accent, 0.16f) else th.key)
        shape.setStroke(dp(1), if (item.pinned) withAlpha(th.accent, 160) else withAlpha(th.text, 28))
        c.background = RippleDrawable(ColorStateList.valueOf(withAlpha(th.accent, 70)), shape, null)
        c.isClickable = true
        c.setOnClickListener { cb.onPaste(item.text) }
        val lp = LinearLayout.LayoutParams(M, W)
        lp.setMargins(dp(10), dp(4), dp(10), dp(4))
        c.layoutParams = lp

        val meta = LinearLayout(context)
        meta.orientation = LinearLayout.HORIZONTAL
        meta.gravity = Gravity.CENTER_VERTICAL
        if (item.pinned) {
            val pi = ImageView(context)
            pi.setImageResource(R.drawable.ic_pin)
            pi.setColorFilter(th.accent)
            val plp = LinearLayout.LayoutParams(dp(13), dp(13))
            plp.rightMargin = dp(5)
            meta.addView(pi, plp)
        }
        val tm = TextView(context)
        tm.text = timeAgo(item.ts) + "  \u2022  " + item.text.length + " chars"
        tm.textSize = 10.5f
        tm.setTextColor(withAlpha(th.text, 150))
        meta.addView(tm)
        c.addView(meta)

        val tx = TextView(context)
        // very long clips are cut for display only (keeps the keyboard smooth); pasting always uses the full text
        tx.text = if (expanded.contains(item.id) || item.text.length <= 600) item.text else item.text.take(600) + "\u2026"
        tx.textSize = 14f
        tx.setTextColor(th.text)
        tx.setLineSpacing(0f, 1.1f)
        tx.setPadding(0, dp(4), dp(6), 0)
        val long = item.text.length > 140 || item.text.count { it == '\n' } >= 4
        val open = expanded.contains(item.id)
        if (!open) {
            tx.maxLines = 4
            tx.ellipsize = TextUtils.TruncateAt.END
        }
        c.addView(tx)

        val actions = LinearLayout(context)
        actions.orientation = LinearLayout.HORIZONTAL
        actions.gravity = Gravity.CENTER_VERTICAL
        if (long) {
            val more = TextView(context)
            more.text = if (open) "Show less" else "Show more"
            more.textSize = 11.5f
            more.typeface = Typeface.DEFAULT_BOLD
            more.setTextColor(th.accent)
            more.setPadding(0, dp(6), dp(10), dp(6))
            more.setOnClickListener {
                if (open) expanded.remove(item.id) else expanded.add(item.id)
                render()
            }
            actions.addView(more, LinearLayout.LayoutParams(0, W, 1f))
        } else {
            actions.addView(View(context), LinearLayout.LayoutParams(0, dp(1), 1f))
        }
        val soft = withAlpha(th.text, 190)
        actions.addView(iconBtn(R.drawable.ic_copy, soft, 34) {
            cb.onCopy(item.text)
            flash("Copied")
        }, LinearLayout.LayoutParams(dp(34), dp(34)))
        actions.addView(iconBtn(R.drawable.ic_pin, if (item.pinned) th.accent else soft, 34) {
            vm.togglePin(item)
            flash(if (item.pinned) "Unpinned" else "Pinned")
        }, LinearLayout.LayoutParams(dp(34), dp(34)))
        actions.addView(iconBtn(R.drawable.ic_edit, soft, 34) { startEdit(item) }, LinearLayout.LayoutParams(dp(34), dp(34)))
        actions.addView(iconBtn(R.drawable.ic_delete, soft, 34) {
            c.animate().alpha(0f).scaleX(0.92f).scaleY(0.92f).setStartDelay(0).setDuration(160).withEndAction { vm.delete(item) }.start()
        }, LinearLayout.LayoutParams(dp(34), dp(34)))
        c.addView(actions)

        if (animateNext && idx < 10) {
            c.alpha = 0f
            c.translationY = dp(14).toFloat()
            c.animate().alpha(1f).translationY(0f).setStartDelay(idx * 30L).setDuration(240).start()
        }
        return c
    }

    private fun timeAgo(ts: Long): String {
        val min = (System.currentTimeMillis() - ts) / 60000L
        return when {
            min < 1L -> "Just now"
            min < 60L -> "$min min ago"
            min < 1440L -> (min / 60L).toString() + " h ago"
            min < 2880L -> "Yesterday"
            else -> SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(ts))
        }
    }

    // ---------- styling helpers ----------

    private fun roundBg(color: Int, r: Int, strokeColor: Int, stroke: Int): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = dp(r).toFloat()
        if (stroke > 0) g.setStroke(dp(stroke), strokeColor)
        return g
    }

    private fun styleIcon(v: ImageView, tint: Int) {
        v.setColorFilter(tint)
        val mask = GradientDrawable()
        mask.shape = GradientDrawable.OVAL
        mask.setColor(Color.WHITE)
        v.background = RippleDrawable(ColorStateList.valueOf(withAlpha(th.accent, 90)), null, mask)
    }

    private fun iconBtn(res: Int, tint: Int, size: Int, onClick: () -> Unit): ImageView {
        val v = ImageView(context)
        v.setImageResource(res)
        val pad = dp(size) * 27 / 100
        v.setPadding(pad, pad, pad, pad)
        styleIcon(v, tint)
        v.setOnClickListener { onClick() }
        return v
    }

    private fun pillBtn(text: String, primary: Boolean, onClick: () -> Unit): TextView {
        val t = TextView(context)
        t.text = text
        t.textSize = 13f
        t.typeface = Typeface.DEFAULT_BOLD
        t.gravity = Gravity.CENTER
        t.setPadding(dp(14), dp(10), dp(14), dp(10))
        if (primary) {
            val g = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(th.accent, mix(th.accent, Color.WHITE, 0.25f)))
            g.cornerRadius = dp(16).toFloat()
            t.background = g
            t.setTextColor(th.accentText)
        } else {
            t.background = roundBg(withAlpha(th.text, 20), 16, withAlpha(th.text, 50), 1)
            t.setTextColor(th.text)
        }
        t.setOnClickListener { onClick() }
        return t
    }

    private fun styleEditBar() {
        editBar.removeAllViews()
        val cancel = pillBtn("Cancel", false) { finishEdit(false) }
        val save = pillBtn("Save", true) { finishEdit(true) }
        val l1 = LinearLayout.LayoutParams(0, W, 1f)
        l1.rightMargin = dp(10)
        editBar.addView(cancel, l1)
        editBar.addView(save, LinearLayout.LayoutParams(0, W, 1f))
    }

    fun applyTheme(t: KbTheme) {
        th = t
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(t.bgTop, t.bgBottom))
        titleView.setTextColor(t.text)
        styleIcon(backBtn, t.text)
        styleIcon(searchBtn, t.text)
        styleIcon(clearBtn, t.text)
        fieldBg.setColor(withAlpha(t.text, 18))
        toast.background = roundBg(mix(t.bgTop, t.accent, 0.5f), 20, 0, 0)
        toast.setTextColor(t.accentText)
        refreshField()
        if (mode == 2) styleEditBar()
        if (vm.loaded && mode != 2) render()
    }
}
