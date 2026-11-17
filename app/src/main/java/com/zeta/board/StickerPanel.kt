package com.zeta.board

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.util.concurrent.Executors

/**
 * Sticker maker panel: pick a photo (or use the copied image), the sticker is created online and
 * lands in a history grid. Tap a sticker to send it, hold to delete it.
 */
class StickerPanel(ctx: Context, private val cb: Callback) : FrameLayout(ctx) {

    interface Callback {
        fun onPickPhoto()
        fun onUseClipboard()
        fun onSend(f: File)
        fun onClose()
    }

    private val d = ctx.resources.displayMetrics.density
    private fun dp(v: Int): Int = (v * d).toInt()
    private val M = ViewGroup.LayoutParams.MATCH_PARENT
    private val W = ViewGroup.LayoutParams.WRAP_CONTENT
    private val cols = 4
    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var th: KbTheme = Themes.get(0)
    private var gen = 0
    private var highlight: File? = null

    private val main = LinearLayout(ctx)
    private val header = LinearLayout(ctx)
    private val titleView = TextView(ctx)
    private val actions = LinearLayout(ctx)
    private val statusRow = LinearLayout(ctx)
    private val statusText = TextView(ctx)
    private val spinner = ProgressBar(ctx, null, android.R.attr.progressBarStyleSmall)
    private val hint = TextView(ctx)
    private val scroll = ScrollView(ctx)
    private val list = LinearLayout(ctx)
    private val toast = TextView(ctx)
    private lateinit var backBtn: ImageView

    init {
        main.orientation = LinearLayout.VERTICAL

        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        header.setPadding(dp(6), 0, dp(6), 0)
        backBtn = iconBtn(R.drawable.ic_keyboard, 40) { cb.onClose() }
        titleView.text = "Sticker Maker"
        titleView.textSize = 16f
        titleView.typeface = Typeface.DEFAULT_BOLD
        header.addView(backBtn, LinearLayout.LayoutParams(dp(40), dp(40)))
        val tlp = LinearLayout.LayoutParams(0, W, 1f)
        tlp.leftMargin = dp(6)
        header.addView(titleView, tlp)
        main.addView(header, LinearLayout.LayoutParams(M, dp(44)))

        actions.orientation = LinearLayout.HORIZONTAL
        actions.setPadding(dp(10), dp(2), dp(10), dp(6))
        main.addView(actions, LinearLayout.LayoutParams(M, W))

        statusRow.orientation = LinearLayout.HORIZONTAL
        statusRow.gravity = Gravity.CENTER_VERTICAL
        statusRow.setPadding(dp(14), dp(8), dp(14), dp(8))
        statusRow.visibility = View.GONE
        statusText.textSize = 12.5f
        statusText.typeface = Typeface.DEFAULT_BOLD
        statusRow.addView(spinner, LinearLayout.LayoutParams(dp(18), dp(18)))
        val slp = LinearLayout.LayoutParams(0, W, 1f)
        slp.leftMargin = dp(10)
        statusRow.addView(statusText, slp)
        val srLp = LinearLayout.LayoutParams(M, W)
        srLp.setMargins(dp(10), 0, dp(10), dp(6))
        main.addView(statusRow, srLp)

        hint.textSize = 11f
        hint.text = "Tap to send · Hold to delete"
        hint.setPadding(dp(16), 0, dp(16), dp(2))
        main.addView(hint, LinearLayout.LayoutParams(M, W))

        scroll.isVerticalScrollBarEnabled = false
        scroll.overScrollMode = View.OVER_SCROLL_NEVER
        scroll.isFillViewport = true
        list.orientation = LinearLayout.VERTICAL
        list.setPadding(dp(6), 0, dp(6), dp(10))
        scroll.addView(list, ViewGroup.LayoutParams(M, W))
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

    fun open() {
        highlight = null
        setStatus(if (StickerHub.busy) StickerHub.status else null)
        refresh()
    }

    fun close() {
        gen++
        setStatus(null)
    }

    fun setStatus(text: String?) {
        if (text == null) {
            statusRow.visibility = View.GONE
        } else {
            statusText.text = text
            statusRow.visibility = View.VISIBLE
        }
    }

    /** A new sticker has just been made: show it first, highlighted. */
    fun onNew(f: File, note: String?) {
        highlight = f
        setStatus(null)
        refresh()
        scroll.post { scroll.scrollTo(0, 0) }
        flash(if (note != null) "Sticker ready • $note" else "Sticker ready — tap to send")
    }

    fun flash(msg: String) {
        toast.text = msg
        toast.animate().cancel()
        toast.alpha = 0f
        toast.translationY = dp(8).toFloat()
        toast.animate().alpha(1f).translationY(0f).setStartDelay(0).setDuration(160).withEndAction {
            toast.animate().alpha(0f).setStartDelay(1600).setDuration(260).start()
        }.start()
    }

    // ---------- grid ----------

    private fun refresh() {
        val files = StickerRepo.list(context)
        list.removeAllViews()
        hint.visibility = if (files.isEmpty()) View.GONE else View.VISIBLE
        if (files.isEmpty()) {
            val e = TextView(context)
            e.text = "No stickers yet\n\nTap Photo to choose a picture, or copy an image and tap Clipboard."
            e.gravity = Gravity.CENTER
            e.textSize = 13f
            e.setTextColor(withAlpha(th.text, 150))
            e.setPadding(dp(24), dp(24), dp(24), dp(24))
            list.addView(e, LinearLayout.LayoutParams(M, dp(150)))
            return
        }
        val cells = ArrayList<ImageView>()
        var row: LinearLayout? = null
        files.forEachIndexed { i, f ->
            if (i % cols == 0) {
                val r = LinearLayout(context)
                r.orientation = LinearLayout.HORIZONTAL
                list.addView(r, LinearLayout.LayoutParams(M, dp(86)))
                row = r
            }
            val iv = cell(f)
            row!!.addView(iv, cellLp())
            cells.add(iv)
        }
        val rem = files.size % cols
        if (rem != 0) repeat(cols - rem) { row!!.addView(View(context), cellLp()) }

        val g = ++gen
        io.execute {
            val bmps = files.map { decodeThumb(it) }
            handler.post {
                if (g == gen) cells.forEachIndexed { i, iv -> iv.setImageBitmap(bmps[i]) }
            }
        }
    }

    private fun cellLp(): LinearLayout.LayoutParams {
        val lp = LinearLayout.LayoutParams(0, M, 1f)
        lp.setMargins(dp(4), dp(4), dp(4), dp(4))
        return lp
    }

    private fun cell(f: File): ImageView {
        val iv = ImageView(context)
        iv.scaleType = ImageView.ScaleType.FIT_CENTER
        iv.setPadding(dp(6), dp(6), dp(6), dp(6))
        val mark = f == highlight
        iv.background = roundBg(withAlpha(th.text, 16), 14, if (mark) th.accent else 0, if (mark) 2 else 0)
        iv.setOnClickListener { cb.onSend(f) }
        iv.setOnLongClickListener {
            StickerRepo.remove(f)
            if (highlight == f) highlight = null
            refresh()
            flash("Sticker removed")
            true
        }
        return iv
    }

    private fun decodeThumb(f: File): Bitmap? = try {
        val o = BitmapFactory.Options()
        o.inSampleSize = 4
        BitmapFactory.decodeFile(f.path, o)
    } catch (e: Throwable) {
        null
    }

    // ---------- styling ----------

    private fun roundBg(color: Int, r: Int, strokeColor: Int, stroke: Int): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = dp(r).toFloat()
        if (stroke > 0) g.setStroke(dp(stroke), strokeColor)
        return g
    }

    private fun iconBtn(res: Int, size: Int, onClick: () -> Unit): ImageView {
        val v = ImageView(context)
        v.setImageResource(res)
        val pad = dp(size) * 27 / 100
        v.setPadding(pad, pad, pad, pad)
        v.setOnClickListener { onClick() }
        return v
    }

    private fun styleIcon(v: ImageView, tint: Int) {
        v.setColorFilter(tint)
        val mask = GradientDrawable()
        mask.shape = GradientDrawable.OVAL
        mask.setColor(Color.WHITE)
        v.background = RippleDrawable(ColorStateList.valueOf(withAlpha(tint, 50)), null, mask)
    }

    private fun pill(text: String, icon: Int, primary: Boolean, onClick: () -> Unit): TextView {
        val t = TextView(context)
        t.text = text
        t.textSize = 13.5f
        t.typeface = Typeface.DEFAULT_BOLD
        t.gravity = Gravity.CENTER
        t.compoundDrawablePadding = dp(8)
        t.setPadding(dp(14), dp(11), dp(14), dp(11))
        val fg = if (primary) th.accentText else th.text
        context.getDrawable(icon)?.mutate()?.let {
            it.setTint(fg)
            it.setBounds(0, 0, dp(18), dp(18))
            t.setCompoundDrawables(it, null, null, null)
        }
        if (primary) {
            val g = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(th.accent, mix(th.accent, Color.WHITE, 0.25f)))
            g.cornerRadius = dp(16).toFloat()
            t.background = g
        } else {
            t.background = roundBg(withAlpha(th.text, 20), 16, withAlpha(th.text, 50), 1)
        }
        t.setTextColor(fg)
        t.setOnClickListener { onClick() }
        return t
    }

    private fun buildActions() {
        actions.removeAllViews()
        val a = pill("Photo", R.drawable.ic_image, true) { cb.onPickPhoto() }
        val b = pill("Clipboard", R.drawable.ic_paste, false) { cb.onUseClipboard() }
        val l1 = LinearLayout.LayoutParams(0, W, 1f)
        l1.rightMargin = dp(8)
        actions.addView(a, l1)
        actions.addView(b, LinearLayout.LayoutParams(0, W, 1f))
    }

    fun applyTheme(t: KbTheme) {
        th = t
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(t.bgTop, t.bgBottom))
        titleView.setTextColor(t.text)
        styleIcon(backBtn, t.text)
        buildActions()
        statusRow.background = roundBg(withAlpha(t.accent, 70), 14, withAlpha(t.accent, 140), 1)
        statusText.setTextColor(t.text)
        spinner.indeterminateTintList = ColorStateList.valueOf(t.accent)
        hint.setTextColor(withAlpha(t.text, 110))
        toast.background = roundBg(mix(t.bgTop, t.accent, 0.5f), 20, 0, 0)
        toast.setTextColor(t.accentText)
        if (isAttachedToWindow) refresh()
    }
}
