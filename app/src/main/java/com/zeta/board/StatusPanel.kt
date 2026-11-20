package com.zeta.board

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.util.concurrent.Executors

/**
 * Status Saver: shows the photos and videos from the WhatsApp status folders the user added.
 * Tap items to select them, then Save (to the gallery), Send (into the chat box) or Share.
 */
class StatusPanel(ctx: Context, private val cb: Callback) : FrameLayout(ctx) {

    interface Callback {
        /** kind: "wa", "w4b" or "any" */
        fun onStatusSetup(kind: String)
        fun onStatusSend(f: File, mime: String)
        fun onStatusShare(files: List<File>, mimes: List<String>)
        fun onStatusNeedPermission()
        fun onStatusClose()
    }

    private class Cell(val item: StatusItem, val sel: View, val check: ImageView, val saved: View)

    private val d = ctx.resources.displayMetrics.density
    private fun dp(v: Int): Int = (v * d).toInt()
    private val M = ViewGroup.LayoutParams.MATCH_PARENT
    private val W = ViewGroup.LayoutParams.WRAP_CONTENT
    private val handler = Handler(Looper.getMainLooper())
    private val work = Executors.newSingleThreadExecutor()
    private val thumbIo = Executors.newSingleThreadExecutor()
    private var th: KbTheme = Themes.get(0)

    // state
    private var listing: StatusRepo.Listing? = null
    private var savedSet: Set<String> = emptySet()
    private var filter = 0 // 0 all, 1 photos, 2 videos
    private var showSetup = false
    private var busy = false
    private var loadGen = 0
    private var renderGen = 0
    private val selected = LinkedHashSet<String>()
    private val cells = HashMap<String, Cell>()
    private var shown: List<StatusItem> = emptyList()

    // views
    private val main = LinearLayout(ctx)
    private val titleView = TextView(ctx)
    private val chips = LinearLayout(ctx)
    private val statusRow = LinearLayout(ctx)
    private val statusText = TextView(ctx)
    private val spinner = ProgressBar(ctx, null, android.R.attr.progressBarStyleSmall)
    private val scroll = ScrollView(ctx)
    private val body = LinearLayout(ctx)
    private val bar = LinearLayout(ctx)
    private val toast = TextView(ctx)
    private lateinit var backBtn: ImageView
    private lateinit var setupBtn: ImageView

    init {
        main.orientation = LinearLayout.VERTICAL

        val header = LinearLayout(ctx)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        header.setPadding(dp(6), 0, dp(6), 0)
        backBtn = iconBtn(R.drawable.ic_keyboard) { cb.onStatusClose() }
        setupBtn = iconBtn(R.drawable.ic_tune) {
            showSetup = !showSetup
            selected.clear()
            render()
        }
        titleView.text = "Status Saver"
        titleView.textSize = 16f
        titleView.typeface = Typeface.DEFAULT_BOLD
        header.addView(backBtn, LinearLayout.LayoutParams(dp(40), dp(40)))
        val tlp = LinearLayout.LayoutParams(0, W, 1f)
        tlp.leftMargin = dp(6)
        header.addView(titleView, tlp)
        header.addView(setupBtn, LinearLayout.LayoutParams(dp(40), dp(40)))
        main.addView(header, LinearLayout.LayoutParams(M, dp(44)))

        chips.orientation = LinearLayout.HORIZONTAL
        chips.gravity = Gravity.CENTER_VERTICAL
        chips.setPadding(dp(10), dp(2), dp(10), dp(6))
        main.addView(chips, LinearLayout.LayoutParams(M, W))

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

        scroll.isVerticalScrollBarEnabled = false
        scroll.overScrollMode = View.OVER_SCROLL_NEVER
        scroll.isFillViewport = true
        body.orientation = LinearLayout.VERTICAL
        body.setPadding(dp(6), 0, dp(6), dp(10))
        scroll.addView(body, ViewGroup.LayoutParams(M, W))
        main.addView(scroll, LinearLayout.LayoutParams(M, 0, 1f))

        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setPadding(dp(10), dp(6), dp(10), dp(8))
        bar.visibility = View.GONE
        main.addView(bar, LinearLayout.LayoutParams(M, W))
        addView(main, LayoutParams(M, M))

        toast.textSize = 12f
        toast.typeface = Typeface.DEFAULT_BOLD
        toast.setPadding(dp(16), dp(8), dp(16), dp(8))
        toast.alpha = 0f
        val toastLp = LayoutParams(W, W, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        toastLp.bottomMargin = dp(64)
        addView(toast, toastLp)

        applyTheme(th)
    }

    // ---------- lifecycle ----------

    fun open() {
        selected.clear()
        showSetup = false
        setBusy(null)
        load()
    }

    /** The keyboard came back (for example after picking a folder): read the folders again. */
    fun reload() {
        load()
    }

    fun close() {
        loadGen++
        renderGen++
        selected.clear()
        setBusy(null)
    }

    private fun load() {
        val g = ++loadGen
        if (listing == null) setStatus("Loading\u2026", true)
        work.execute {
            val l = StatusRepo.list(context)
            val s = StatusRepo.savedNames(context)
            handler.post {
                if (g != loadGen) return@post
                listing = l
                savedSet = s
                if (!busy) setStatus(null, false)
                selected.retainAll(l.items.map { it.key }.toSet())
                render()
            }
        }
    }

    fun flash(msg: String) {
        toast.text = msg
        toast.animate().cancel()
        toast.alpha = 0f
        toast.translationY = dp(8).toFloat()
        toast.animate().alpha(1f).translationY(0f).setStartDelay(0).setDuration(160).withEndAction {
            toast.animate().alpha(0f).setStartDelay(2200).setDuration(260).start()
        }.start()
    }

    private fun setStatus(text: String?, spin: Boolean) {
        if (text == null) {
            statusRow.visibility = View.GONE
        } else {
            statusText.text = text
            spinner.visibility = if (spin) View.VISIBLE else View.GONE
            statusRow.visibility = View.VISIBLE
        }
    }

    private fun setBusy(msg: String?) {
        busy = msg != null
        setStatus(msg, true)
    }

    // ---------- drawing the content ----------

    private fun render() {
        val g = ++renderGen
        body.removeAllViews()
        cells.clear()
        val l = listing
        if (l == null) {
            chips.visibility = View.GONE
            bar.visibility = View.GONE
            return
        }
        if (showSetup || l.folders == 0 || (l.broken == l.folders && l.items.isEmpty())) {
            chips.visibility = View.GONE
            bar.visibility = View.GONE
            buildSetup(l)
            return
        }

        chips.visibility = View.VISIBLE
        buildChips()
        shown = when (filter) {
            1 -> l.items.filter { !it.video }
            2 -> l.items.filter { it.video }
            else -> l.items
        }
        if (shown.isEmpty()) {
            val msg = if (l.items.isEmpty()) {
                "No statuses yet\n\nOpen WhatsApp \u2192 Status and view a few. They show up here after you have seen them."
            } else {
                "Nothing in this filter"
            }
            body.addView(message(msg), LinearLayout.LayoutParams(M, dp(170)))
            updateBar()
            return
        }

        val wDp = resources.displayMetrics.widthPixels / d
        val cols = (wDp / 110f).toInt().coerceIn(3, 6)
        val size = (resources.displayMetrics.widthPixels - dp(12)) / cols - dp(8)
        val toLoad = ArrayList<Pair<StatusItem, ImageView>>()
        var row: LinearLayout? = null
        shown.forEachIndexed { i, item ->
            if (i % cols == 0) {
                val r = LinearLayout(context)
                r.orientation = LinearLayout.HORIZONTAL
                body.addView(r, LinearLayout.LayoutParams(M, size + dp(8)))
                row = r
            }
            val iv = ImageView(context)
            val cell = buildCell(item, iv)
            val lp = LinearLayout.LayoutParams(size, size)
            lp.setMargins(dp(4), dp(4), dp(4), dp(4))
            row!!.addView(cell, lp)
            val cached = StatusIO.cachedThumb(item)
            if (cached != null) iv.setImageBitmap(cached) else toLoad.add(Pair(item, iv))
        }
        updateBar()

        if (toLoad.isNotEmpty()) {
            val px = size.coerceAtLeast(96)
            thumbIo.execute {
                for ((item, iv) in toLoad) {
                    if (g != renderGen) break
                    val b = StatusIO.thumb(context, item, px) ?: continue
                    handler.post { if (g == renderGen) iv.setImageBitmap(b) }
                }
            }
        }
    }

    private fun buildCell(item: StatusItem, iv: ImageView): FrameLayout {
        val fl = FrameLayout(context)
        iv.scaleType = ImageView.ScaleType.CENTER_CROP
        iv.outlineProvider = ViewOutlineProvider.BACKGROUND
        iv.background = roundBg(withAlpha(th.text, 22), 12, 0, 0)
        iv.clipToOutline = true
        fl.addView(iv, FrameLayout.LayoutParams(M, M))

        if (item.video) {
            val badge = TextView(context)
            badge.text = "\u25B6"
            badge.textSize = 11f
            badge.setTextColor(Color.WHITE)
            badge.gravity = Gravity.CENTER
            badge.background = roundBg(Color.argb(150, 0, 0, 0), 10, 0, 0)
            val lp = LayoutParams(dp(22), dp(22), Gravity.BOTTOM or Gravity.START)
            lp.setMargins(dp(6), 0, 0, dp(6))
            fl.addView(badge, lp)
        }

        val saved = TextView(context)
        saved.text = "\u2713 Saved"
        saved.textSize = 10f
        saved.typeface = Typeface.DEFAULT_BOLD
        saved.setTextColor(Color.WHITE)
        saved.setPadding(dp(6), dp(2), dp(6), dp(2))
        saved.background = roundBg(Color.argb(170, 20, 140, 70), 8, 0, 0)
        val slp = LayoutParams(W, W, Gravity.BOTTOM or Gravity.END)
        slp.setMargins(0, 0, dp(6), dp(6))
        fl.addView(saved, slp)

        val sel = View(context)
        sel.background = roundBg(withAlpha(th.accent, 70), 12, th.accent, 3)
        fl.addView(sel, FrameLayout.LayoutParams(M, M))

        val check = ImageView(context)
        check.setImageResource(R.drawable.ic_check)
        check.setColorFilter(th.accentText)
        check.setPadding(dp(3), dp(3), dp(3), dp(3))
        val oval = GradientDrawable()
        oval.shape = GradientDrawable.OVAL
        oval.setColor(th.accent)
        check.background = oval
        val clp = LayoutParams(dp(22), dp(22), Gravity.TOP or Gravity.END)
        clp.setMargins(0, dp(6), dp(6), 0)
        fl.addView(check, clp)

        val cell = Cell(item, sel, check, saved)
        cells[item.key] = cell
        updateCell(cell)
        fl.setOnClickListener { toggle(item) }
        return fl
    }

    private fun updateCell(c: Cell) {
        val on = selected.contains(c.item.key)
        c.sel.visibility = if (on) View.VISIBLE else View.GONE
        c.check.visibility = if (on) View.VISIBLE else View.GONE
        c.saved.visibility = if (!on && savedSet.contains(c.item.name)) View.VISIBLE else View.GONE
    }

    private fun toggle(item: StatusItem) {
        if (busy) return
        if (!selected.remove(item.key)) selected.add(item.key)
        cells[item.key]?.let { updateCell(it) }
        updateBar()
    }

    private fun selectedItems(): List<StatusItem> {
        val all = listing?.items ?: return emptyList()
        return all.filter { selected.contains(it.key) }
    }

    // ---------- chips and the action bar ----------

    private fun buildChips() {
        chips.removeAllViews()
        val names = arrayOf("All", "Photos", "Videos")
        names.forEachIndexed { i, n ->
            val c = chip(n, filter == i) {
                if (filter != i) {
                    filter = i
                    render()
                }
            }
            val lp = LinearLayout.LayoutParams(W, W)
            lp.rightMargin = dp(6)
            chips.addView(c, lp)
        }
        val spacer = View(context)
        chips.addView(spacer, LinearLayout.LayoutParams(0, 1, 1f))
        chips.addView(chip("Select all", false) {
            if (!busy) {
                shown.forEach { selected.add(it.key) }
                cells.values.forEach { updateCell(it) }
                updateBar()
            }
        })
    }

    private fun updateBar() {
        bar.removeAllViews()
        val n = selected.size
        if (n == 0 || showSetup) {
            bar.visibility = View.GONE
            return
        }
        bar.visibility = View.VISIBLE
        val clear = iconBtn(R.drawable.ic_close) {
            if (!busy) {
                selected.clear()
                cells.values.forEach { updateCell(it) }
                updateBar()
            }
        }
        styleIcon(clear, th.text)
        bar.addView(clear, LinearLayout.LayoutParams(dp(36), dp(36)))
        val count = TextView(context)
        count.text = "$n selected"
        count.textSize = 12.5f
        count.maxLines = 1
        count.typeface = Typeface.DEFAULT_BOLD
        count.setTextColor(th.text)
        val cl = LinearLayout.LayoutParams(0, W, 1f)
        cl.leftMargin = dp(4)
        bar.addView(count, cl)

        val gap = LinearLayout.LayoutParams(W, W)
        gap.leftMargin = dp(6)
        bar.addView(pill("Share", null, false, 10) { doShare() }, gap)
        if (n == 1) bar.addView(pill("Send", null, false, 10) { doSend() }, LinearLayout.LayoutParams(W, W).also { it.leftMargin = dp(6) })
        bar.addView(pill("Save", R.drawable.ic_download, true, 12) { doSave() }, LinearLayout.LayoutParams(W, W).also { it.leftMargin = dp(6) })
    }

    // ---------- actions ----------

    private fun doSave() {
        val picks = selectedItems()
        if (picks.isEmpty() || busy) return
        setBusy("Saving\u2026")
        work.execute {
            var ok = 0
            var had = 0
            var fail = 0
            var perm = false
            for (x in picks) {
                when (StatusIO.save(context, x)) {
                    StatusIO.Saved.OK -> {
                        ok++
                        StatusRepo.markSaved(context, x.name)
                    }
                    StatusIO.Saved.EXISTS -> {
                        had++
                        StatusRepo.markSaved(context, x.name)
                    }
                    StatusIO.Saved.NEED_PERMISSION -> perm = true
                    StatusIO.Saved.FAIL -> fail++
                }
            }
            val s = StatusRepo.savedNames(context)
            handler.post {
                setBusy(null)
                if (perm) {
                    flash("Allow storage access, then save again")
                    cb.onStatusNeedPermission()
                    return@post
                }
                savedSet = s
                selected.clear()
                cells.values.forEach { updateCell(it) }
                updateBar()
                flash(
                    when {
                        ok > 0 -> "Saved $ok to your gallery (Zeta Board)" + (if (had > 0) " \u2022 $had already saved" else "") + (if (fail > 0) " \u2022 $fail failed" else "")
                        had > 0 && fail == 0 -> if (had == 1) "Already saved" else "All $had already saved"
                        else -> "Couldn't save. The status may have expired."
                    }
                )
            }
        }
    }

    private fun doSend() {
        val x = selectedItems().singleOrNull() ?: return
        if (busy) return
        setBusy("Preparing\u2026")
        work.execute {
            val f = StatusIO.toCache(context, x)
            handler.post {
                setBusy(null)
                if (f == null) flash("Couldn't read this status") else cb.onStatusSend(f, x.mime)
            }
        }
    }

    private fun doShare() {
        val picks = selectedItems().take(20)
        if (picks.isEmpty() || busy) return
        setBusy("Preparing\u2026")
        work.execute {
            val files = ArrayList<File>()
            val mimes = ArrayList<String>()
            for (x in picks) {
                val f = StatusIO.toCache(context, x) ?: continue
                files.add(f)
                mimes.add(x.mime)
            }
            handler.post {
                setBusy(null)
                if (files.isEmpty()) flash("Couldn't read these statuses") else cb.onStatusShare(files, mimes)
            }
        }
    }

    // ---------- folder setup ----------

    private fun buildSetup(l: StatusRepo.Listing) {
        val broken = l.folders > 0 && l.broken > 0
        val title = TextView(context)
        title.text = when {
            l.folders == 0 -> "Add a status folder"
            broken -> "Folder access was lost"
            else -> "Status folders"
        }
        title.textSize = 15f
        title.typeface = Typeface.DEFAULT_BOLD
        title.setTextColor(th.text)
        title.setPadding(dp(10), dp(6), dp(10), dp(2))
        body.addView(title, LinearLayout.LayoutParams(M, W))

        val info = TextView(context)
        info.text = "Pick the WhatsApp status folder once. Android asks you to confirm: tap \u201CUse this folder\u201D, then \u201CAllow\u201D. " +
            "Only statuses you have already viewed in WhatsApp appear here."
        info.textSize = 12f
        info.setTextColor(withAlpha(th.text, 160))
        info.setPadding(dp(10), 0, dp(10), dp(8))
        body.addView(info, LinearLayout.LayoutParams(M, W))

        val a = pill("WhatsApp", R.drawable.ic_whatsapp, true) { cb.onStatusSetup("wa") }
        val b = pill("WhatsApp Business", R.drawable.ic_whatsapp, false) { cb.onStatusSetup("w4b") }
        val c = pill("Choose another folder", R.drawable.ic_image, false) { cb.onStatusSetup("any") }
        for (v in listOf(a, b, c)) {
            val lp = LinearLayout.LayoutParams(M, W)
            lp.setMargins(dp(10), dp(3), dp(10), dp(3))
            body.addView(v, lp)
        }

        val fs = StatusRepo.folders(context)
        if (fs.isNotEmpty()) {
            val head = TextView(context)
            head.text = "Added folders"
            head.textSize = 12f
            head.typeface = Typeface.DEFAULT_BOLD
            head.setTextColor(withAlpha(th.text, 160))
            head.setPadding(dp(10), dp(12), dp(10), dp(2))
            body.addView(head, LinearLayout.LayoutParams(M, W))
            for (f in fs) {
                val row = LinearLayout(context)
                row.orientation = LinearLayout.HORIZONTAL
                row.gravity = Gravity.CENTER_VERTICAL
                row.setPadding(dp(10), dp(2), dp(4), dp(2))
                val t = TextView(context)
                t.text = f.docId.substringAfter(':')
                t.textSize = 11.5f
                t.maxLines = 2
                t.setTextColor(th.text)
                row.addView(t, LinearLayout.LayoutParams(0, W, 1f))
                val rm = TextView(context)
                rm.text = "Remove"
                rm.textSize = 12f
                rm.typeface = Typeface.DEFAULT_BOLD
                rm.setTextColor(th.accent)
                rm.setPadding(dp(10), dp(8), dp(10), dp(8))
                rm.setOnClickListener {
                    StatusRepo.remove(context, f)
                    load()
                }
                row.addView(rm, LinearLayout.LayoutParams(W, W))
                body.addView(row, LinearLayout.LayoutParams(M, W))
            }
            if (showSetup) {
                val done = pill("Back to statuses", null, false) {
                    showSetup = false
                    render()
                }
                val lp = LinearLayout.LayoutParams(M, W)
                lp.setMargins(dp(10), dp(10), dp(10), dp(4))
                body.addView(done, lp)
            }
        }
    }

    private fun message(text: String): TextView {
        val e = TextView(context)
        e.text = text
        e.gravity = Gravity.CENTER
        e.textSize = 13f
        e.setTextColor(withAlpha(th.text, 150))
        e.setPadding(dp(24), dp(24), dp(24), dp(24))
        return e
    }

    // ---------- styling ----------

    private fun roundBg(color: Int, r: Int, strokeColor: Int, stroke: Int): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = dp(r).toFloat()
        if (stroke > 0) g.setStroke(dp(stroke), strokeColor)
        return g
    }

    private fun iconBtn(res: Int, onClick: () -> Unit): ImageView {
        val v = ImageView(context)
        v.setImageResource(res)
        val pad = dp(40) * 27 / 100
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

    private fun chip(text: String, active: Boolean, onClick: () -> Unit): TextView {
        val t = TextView(context)
        t.text = text
        t.textSize = 12.5f
        t.typeface = Typeface.DEFAULT_BOLD
        t.gravity = Gravity.CENTER
        t.setPadding(dp(12), dp(6), dp(12), dp(6))
        if (active) {
            t.background = roundBg(th.accent, 16, 0, 0)
            t.setTextColor(th.accentText)
        } else {
            t.background = roundBg(withAlpha(th.text, 20), 16, withAlpha(th.text, 40), 1)
            t.setTextColor(th.text)
        }
        t.setOnClickListener { onClick() }
        return t
    }

    private fun pill(text: String, icon: Int?, primary: Boolean, padH: Int = 14, onClick: () -> Unit): TextView {
        val t = TextView(context)
        t.text = text
        t.textSize = 13.5f
        t.typeface = Typeface.DEFAULT_BOLD
        t.gravity = Gravity.CENTER
        t.compoundDrawablePadding = dp(8)
        t.setPadding(dp(padH), dp(10), dp(padH), dp(10))
        val fg = if (primary) th.accentText else th.text
        if (icon != null) {
            context.getDrawable(icon)?.mutate()?.let {
                it.setTint(fg)
                it.setBounds(0, 0, dp(18), dp(18))
                t.setCompoundDrawables(it, null, null, null)
            }
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

    fun applyTheme(t: KbTheme) {
        th = t
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(t.bgTop, t.bgBottom))
        titleView.setTextColor(t.text)
        styleIcon(backBtn, t.text)
        styleIcon(setupBtn, t.text)
        statusRow.background = roundBg(withAlpha(t.accent, 70), 14, withAlpha(t.accent, 140), 1)
        statusText.setTextColor(t.text)
        spinner.indeterminateTintList = ColorStateList.valueOf(t.accent)
        toast.background = roundBg(mix(t.bgTop, t.accent, 0.5f), 20, 0, 0)
        toast.setTextColor(t.accentText)
        if (isAttachedToWindow && listing != null) render()
    }
}
