package com.zeta.board

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Theme Store: browse every theme with a live preview, take (install) the ones you like and apply them.
 * Everything is built into the app, so it works offline. Taken themes appear in the Appearance card and
 * in the keyboard's Theme button.
 * The Wallpapers tab lists pictures from the Zeta wallpaper server; Apply makes one the keyboard background.
 */
class ThemeStoreActivity : Activity() {

    private lateinit var preview: ThemePreview
    private lateinit var nameTv: TextView
    private lateinit var subTv: TextView
    private lateinit var statsTv: TextView
    private lateinit var primary: TextView
    private lateinit var remove: TextView
    private lateinit var grid: LinearLayout
    private lateinit var gridScroll: ScrollView
    private val tabs = ArrayList<Pair<String, TextView>>()
    private val tiles = ArrayList<ThemeTile>()

    private var sel = 0
    private var filter = "All"

    // wallpapers tab
    private val wpTab = "Wallpapers"
    private val wpTiles = ArrayList<WallpaperTile>()
    private var wallpapers: List<Wallpaper> = WallpaperStore.builtin()
    private var wpSelId = ""
    private var wpLoading = false
    private var wpError: String? = null
    private var wpFetchedAt = 0L
    private var wpBusy = false
    private var alive = true

    private val dens: Float by lazy { resources.displayMetrics.density }
    private fun dp(v: Int): Int = (v * dens).toInt()
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sel = Prefs.getI(this, "theme", 0).coerceIn(0, Themes.all.size - 1)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Pal.BG)
        root.setPadding(dp(14), dp(10), dp(14), 0)
        root.addView(topBar(), LinearLayout.LayoutParams(MATCH, WRAP))

        preview = ThemePreview(this)
        val plp = LinearLayout.LayoutParams(MATCH, dp(176))
        plp.topMargin = dp(6)
        root.addView(preview, plp)
        root.addView(actionRow(), LinearLayout.LayoutParams(MATCH, WRAP))
        root.addView(tabRow(), LinearLayout.LayoutParams(MATCH, WRAP))

        gridScroll = ScrollView(this)
        gridScroll.isVerticalScrollBarEnabled = false
        grid = LinearLayout(this)
        grid.orientation = LinearLayout.VERTICAL
        grid.setPadding(0, dp(4), 0, dp(28))
        gridScroll.addView(grid, ViewGroup.LayoutParams(MATCH, WRAP))
        root.addView(gridScroll, LinearLayout.LayoutParams(MATCH, 0, 1f))
        setContentView(root)

        preview.set(Themes.get(sel))
        buildGrid()
        refreshAll()
    }

    override fun onDestroy() {
        alive = false
        super.onDestroy()
    }

    // ---------- header / controls ----------

    private fun topBar(): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        val back = ImageView(this)
        back.setImageResource(R.drawable.ic_close)
        back.setColorFilter(Color.WHITE)
        back.setPadding(dp(10), dp(10), dp(10), dp(10))
        back.setOnClickListener { finish() }
        row.addView(back, LinearLayout.LayoutParams(dp(44), dp(44)))
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(8), 0, 0, 0)
        col.addView(tv("Theme Store", 20f, Color.WHITE, true))
        statsTv = tv("", 12f, Pal.MUTED, false)
        col.addView(statsTv)
        row.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
        row.addView(LogoBadge(this), LinearLayout.LayoutParams(dp(42), dp(42)))
        return row
    }

    private fun actionRow(): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(2), dp(12), dp(2), dp(4))

        val info = LinearLayout(this)
        info.orientation = LinearLayout.VERTICAL
        nameTv = tv("", 17f, Color.WHITE, true)
        nameTv.maxLines = 1
        subTv = tv("", 12f, Pal.MUTED, false)
        subTv.maxLines = 1
        info.addView(nameTv)
        info.addView(subTv)
        row.addView(info, LinearLayout.LayoutParams(0, WRAP, 1f))

        remove = chipText("Remove", false)
        remove.setTextColor(Color.parseColor("#FF6B7A"))
        remove.setOnClickListener { onRemove() }
        val rlp = LinearLayout.LayoutParams(WRAP, WRAP)
        rlp.rightMargin = dp(8)
        row.addView(remove, rlp)

        primary = chipText("Apply", true)
        primary.setOnClickListener { onPrimary() }
        row.addView(primary, LinearLayout.LayoutParams(WRAP, WRAP))
        return row
    }

    private fun tabRow(): View {
        val hs = HorizontalScrollView(this)
        hs.isHorizontalScrollBarEnabled = false
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.setPadding(0, dp(8), 0, dp(8))
        val names = ArrayList<String>()
        names.add("All")
        names.add("Installed")
        names.add(wpTab)
        names.addAll(Themes.categories)
        for (n in names) {
            val t = chipText(n, n == filter)
            t.setOnClickListener {
                if (filter != n) {
                    filter = n
                    paintTabs()
                    buildGrid()
                    refreshAll()
                    gridScroll.scrollTo(0, 0)
                    if (n == wpTab && System.currentTimeMillis() - wpFetchedAt > 60000L) loadWallpapers()
                }
            }
            tabs.add(Pair(n, t))
            val lp = LinearLayout.LayoutParams(WRAP, WRAP)
            lp.rightMargin = dp(8)
            row.addView(t, lp)
        }
        hs.addView(row)
        return hs
    }

    private fun paintTabs() {
        for ((n, t) in tabs) paintChip(t, n == filter)
    }

    // ---------- actions ----------

    private fun onPrimary() {
        if (filter == wpTab) {
            onApplyWallpaper()
            return
        }
        val i = sel
        val cur = Prefs.getI(this, "theme", 0)
        if (i == cur) return
        val th = Themes.get(i)
        if (!Themes.isInstalled(this, i)) {
            Themes.install(this, i)
            Prefs.setI(this, "theme", i)
            Toast.makeText(this, th.name + " installed and applied", Toast.LENGTH_SHORT).show()
        } else {
            Prefs.setI(this, "theme", i)
            Toast.makeText(this, th.name + " applied", Toast.LENGTH_SHORT).show()
        }
        refreshAll()
    }

    private fun onRemove() {
        if (filter == wpTab) {
            onResetWallpaper()
            return
        }
        val i = sel
        if (Themes.isBuiltin(i) || !Themes.isInstalled(this, i)) return
        val wasActive = Prefs.getI(this, "theme", 0) == i
        val th = Themes.get(i)
        Themes.uninstall(this, i)
        val msg = if (wasActive) th.name + " removed, back to " + Themes.get(0).name else th.name + " removed"
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        if (filter == "Installed") buildGrid()
        refreshAll()
    }

    private fun pick(i: Int) {
        sel = i
        preview.set(Themes.get(i))
        refreshAll()
    }

    // ---------- grid ----------

    private fun visibleList(): List<Int> {
        return when (filter) {
            "All" -> Themes.all.indices.toList()
            "Installed" -> Themes.installed(this)
            else -> Themes.all.indices.filter { Themes.category(it) == filter }
        }
    }

    private fun buildGrid() {
        grid.removeAllViews()
        tiles.clear()
        wpTiles.clear()
        if (filter == wpTab) {
            buildWallpaperGrid()
            return
        }
        val list = visibleList()
        if (list.isEmpty()) {
            val e = tv("Nothing here yet", 14f, Pal.MUTED, false)
            e.gravity = Gravity.CENTER
            e.setPadding(0, dp(40), 0, 0)
            grid.addView(e, LinearLayout.LayoutParams(MATCH, WRAP))
            return
        }
        var k = 0
        while (k < list.size) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            for (j in 0 until 3) {
                val lp = LinearLayout.LayoutParams(0, dp(122), 1f)
                lp.bottomMargin = dp(10)
                if (j < 2) lp.rightMargin = dp(10)
                if (k + j < list.size) {
                    val idx = list[k + j]
                    val tile = ThemeTile(this, idx, Themes.get(idx))
                    tile.setOnClickListener { pick(idx) }
                    tiles.add(tile)
                    row.addView(tile, lp)
                } else {
                    val sp = View(this)
                    sp.visibility = View.INVISIBLE
                    row.addView(sp, lp)
                }
            }
            grid.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
            k += 3
        }
    }

    /** Repaints everything that depends on what is selected, owned and in use. */
    private fun refreshAll() {
        if (filter == wpTab) {
            refreshWallpaperUi()
            return
        }
        preview.setBg(null)
        preview.set(Themes.get(sel))
        remove.text = "Remove"
        val cur = Prefs.getI(this, "theme", 0)
        for (t in tiles) {
            t.active = t.idx == cur
            t.owned = Themes.isInstalled(this, t.idx)
            t.chosen = t.idx == sel
            t.invalidate()
        }
        val th = Themes.get(sel)
        val active = sel == cur
        val owned = Themes.isInstalled(this, sel)
        nameTv.text = th.name
        subTv.text = Themes.category(sel) + "  \u2022  " + (if (active) "In use" else if (owned) "Installed" else "Not installed")
        primary.text = if (active) "Applied" else if (owned) "Apply" else "Get & Apply"
        primary.alpha = if (active) 0.5f else 1f
        remove.visibility = if (owned && !Themes.isBuiltin(sel)) View.VISIBLE else View.GONE
        statsTv.text = Themes.all.size.toString() + " themes  \u2022  " + Themes.installed(this).size + " installed"
        paintTabs()
    }

    // ---------- wallpapers ----------

    private fun selectedWallpaper(): Wallpaper? {
        var w = wallpapers.firstOrNull { it.id == wpSelId }
        if (w == null) {
            w = wallpapers.firstOrNull { WallpaperStore.isActive(this, it) } ?: wallpapers.firstOrNull()
            wpSelId = w?.id ?: ""
        }
        return w
    }

    private fun loadWallpapers() {
        if (wpLoading || !WallpaperStore.configured()) return
        wpLoading = true
        wpError = null
        if (filter == wpTab) {
            buildGrid()
            refreshAll()
        }
        WallpaperStore.fetchList(this) { items, _, err ->
            wpLoading = false
            if (alive) {
                wallpapers = items
                wpError = err
                if (err == null) wpFetchedAt = System.currentTimeMillis()
                if (filter == wpTab) {
                    buildGrid()
                    refreshAll()
                }
            }
        }
    }

    private fun message(text: String, retry: Boolean) {
        val e = tv(text, 14f, Pal.MUTED, false)
        e.gravity = Gravity.CENTER
        e.setPadding(dp(20), dp(40), dp(20), dp(16))
        grid.addView(e, LinearLayout.LayoutParams(MATCH, WRAP))
        if (retry) {
            val b = chipText("Try again", true)
            b.setOnClickListener { loadWallpapers() }
            val lp = LinearLayout.LayoutParams(WRAP, WRAP)
            lp.gravity = Gravity.CENTER_HORIZONTAL
            grid.addView(b, lp)
        }
    }

    private fun buildWallpaperGrid() {
        if (wallpapers.isEmpty()) {
            when {
                wpLoading -> message("Loading wallpapers\u2026", false)
                wpError != null -> message("Could not load wallpapers.\n" + wpError, true)
                wpFetchedAt == 0L -> message("Loading wallpapers\u2026", false)
                else -> message("No wallpapers yet. Check back soon.", false)
            }
            return
        }
        if (wpError != null) {
            val note = tv("Offline: online wallpapers could not be loaded", 12f, Pal.MUTED, false)
            note.setPadding(dp(2), dp(4), 0, dp(8))
            grid.addView(note, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        val cats = wallpapers.map { it.category }.distinct().sortedBy { it.lowercase() }
        for (cat in cats) {
            val head = tv(cat, 13f, Pal.MUTED, true)
            head.setPadding(dp(2), dp(6), 0, dp(8))
            grid.addView(head, LinearLayout.LayoutParams(MATCH, WRAP))
            val list = wallpapers.filter { it.category == cat }
            var k = 0
            while (k < list.size) {
                val row = LinearLayout(this)
                row.orientation = LinearLayout.HORIZONTAL
                for (j in 0 until 3) {
                    val lp = LinearLayout.LayoutParams(0, dp(150), 1f)
                    lp.bottomMargin = dp(10)
                    if (j < 2) lp.rightMargin = dp(10)
                    if (k + j < list.size) {
                        val w = list[k + j]
                        val tile = WallpaperTile(this, w)
                        tile.setOnClickListener {
                            wpSelId = w.id
                            refreshAll()
                        }
                        wpTiles.add(tile)
                        row.addView(tile, lp)
                        WallpaperStore.loadThumb(this, w) { bmp ->
                            if (alive && bmp != null) {
                                tile.bmp = bmp
                                tile.invalidate()
                                if (filter == wpTab && wpSelId == w.id) refreshAll()
                            }
                        }
                    } else {
                        val sp = View(this)
                        sp.visibility = View.INVISIBLE
                        row.addView(sp, lp)
                    }
                }
                grid.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
                k += 3
            }
        }
        grid.addView(View(this), LinearLayout.LayoutParams(MATCH, dp(8)))
    }

    private fun refreshWallpaperUi() {
        paintTabs()
        for (t in wpTiles) {
            t.applied = WallpaperStore.isActive(this, t.wp)
            t.chosen = t.wp.id == wpSelId
            t.invalidate()
        }
        preview.set(Themes.get(Prefs.getI(this, "theme", 0)))
        remove.text = "Reset"
        remove.visibility = if (KbBg.has(this) && !wpBusy) View.VISIBLE else View.GONE
        statsTv.text = wallpapers.size.toString() + " wallpapers"
        val w = selectedWallpaper()
        if (w == null) {
            preview.setBg(null)
            nameTv.text = "Wallpapers"
            subTv.text = if (!WallpaperStore.configured()) "Not set up" else if (wpLoading) "Loading\u2026" else "Nothing to show yet"
            primary.text = "Apply"
            primary.alpha = 0.5f
            return
        }
        preview.setBg(WallpaperStore.peekThumb(w.id))
        val active = WallpaperStore.isActive(this, w)
        nameTv.text = w.name
        subTv.text = w.category + "  \u2022  " + (if (active) "In use" else "Not applied")
        primary.text = if (wpBusy) "Applying\u2026" else if (active) "Applied" else "Apply"
        primary.alpha = if (active || wpBusy) 0.5f else 1f
    }

    private fun onApplyWallpaper() {
        val w = selectedWallpaper() ?: return
        if (wpBusy || WallpaperStore.isActive(this, w)) return
        wpBusy = true
        refreshAll()
        WallpaperStore.applyToKeyboard(this, w) { err ->
            wpBusy = false
            if (alive) {
                if (err == null) {
                    Toast.makeText(this, w.name + " applied", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Could not apply: " + err, Toast.LENGTH_LONG).show()
                }
                refreshAll()
            }
        }
    }

    private fun onResetWallpaper() {
        if (wpBusy || !KbBg.has(this)) return
        WallpaperStore.reset(this)
        Toast.makeText(this, "Keyboard wallpaper removed", Toast.LENGTH_SHORT).show()
        refreshAll()
    }

    // ---------- small helpers ----------

    private fun tv(s: String, size: Float, color: Int, bold: Boolean): TextView {
        val t = TextView(this)
        t.text = s
        t.textSize = size
        t.setTextColor(color)
        if (bold) t.typeface = Typeface.DEFAULT_BOLD
        return t
    }

    private fun shape(color: Int, radius: Int, strokeColor: Int, stroke: Int): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = dp(radius).toFloat()
        if (stroke > 0) g.setStroke(dp(stroke), strokeColor)
        return g
    }

    /** A rounded label used for the buttons and the category tabs. */
    private fun chipText(text: String, on: Boolean): TextView {
        val t = tv(text, 13f, Color.WHITE, true)
        t.gravity = Gravity.CENTER
        t.setPadding(dp(16), dp(9), dp(16), dp(9))
        paintChip(t, on)
        return t
    }

    private fun paintChip(t: TextView, on: Boolean) {
        if (on) {
            val g = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Pal.CYAN, Pal.PURPLE))
            g.cornerRadius = dp(30).toFloat()
            t.background = g
        } else {
            t.background = shape(Pal.CARD, 30, Pal.STROKE, 1)
        }
    }
}

/** Big mini-keyboard showing the highlighted theme. */
private class ThemePreview(ctx: Context) : View(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rf = RectF()
    private val clip = Path()
    private val shiftIc: Drawable = ctx.getDrawable(R.drawable.ic_shift)!!.mutate()
    private val backIc: Drawable = ctx.getDrawable(R.drawable.ic_backspace)!!.mutate()
    private var th: KbTheme = Themes.get(0)
    private var bg: Bitmap? = null
    private var dim = 35
    private val bgPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val srcR = Rect()

    init {
        tp.typeface = Typeface.DEFAULT_BOLD
        tp.textAlign = Paint.Align.CENTER
    }

    fun set(t: KbTheme) {
        th = t
        invalidate()
    }

    /** A wallpaper drawn behind the keys (null = none), dimmed the same way the real keyboard dims it. */
    fun setBg(b: Bitmap?) {
        if (bg === b) return
        bg = b
        dim = Prefs.getI(context, "bgdim", 35)
        invalidate()
    }

    private fun label(c: Canvas, s: String, cx: Float, cy: Float, size: Float, color: Int) {
        tp.textSize = size
        tp.color = color
        c.drawText(s, cx, cy - (tp.descent() + tp.ascent()) / 2f, tp)
    }

    private fun key(c: Canvas, l: Float, t: Float, r: Float, b: Float, fill: Int, text: String, textColor: Int, size: Float) {
        p.shader = null
        p.style = Paint.Style.FILL
        p.color = fill
        c.drawRoundRect(l, t, r, b, d * 6f, d * 6f, p)
        label(c, text, (l + r) / 2f, (t + b) / 2f, size, textColor)
    }

    private fun iconKey(c: Canvas, l: Float, t: Float, r: Float, b: Float, fill: Int, ic: Drawable, color: Int) {
        p.shader = null
        p.style = Paint.Style.FILL
        p.color = fill
        c.drawRoundRect(l, t, r, b, d * 6f, d * 6f, p)
        val s = (b - t) * 0.5f
        val cx = (l + r) / 2f
        val cy = (t + b) / 2f
        ic.setTint(color)
        ic.setBounds((cx - s / 2f).toInt(), (cy - s / 2f).toInt(), (cx + s / 2f).toInt(), (cy + s / 2f).toInt())
        ic.draw(c)
    }

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(getDefaultSize(0, w), (176 * d).toInt())
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        rf.set(0f, 0f, w, h)
        clip.reset()
        clip.addRoundRect(rf, d * 20f, d * 20f, Path.Direction.CW)
        c.save()
        c.clipPath(clip)

        p.style = Paint.Style.FILL
        p.shader = LinearGradient(0f, 0f, 0f, h, th.bgTop, th.bgBottom, Shader.TileMode.CLAMP)
        c.drawRect(rf, p)
        p.shader = null

        val wb = bg
        if (wb != null) {
            val sc = maxOf(w / wb.width, h / wb.height)
            val sw = w / sc
            val sh = h / sc
            srcR.set(((wb.width - sw) / 2f).toInt(), ((wb.height - sh) / 2f).toInt(), ((wb.width + sw) / 2f).toInt(), ((wb.height + sh) / 2f).toInt())
            c.drawBitmap(wb, srcR, rf, bgPaint)
            p.style = Paint.Style.FILL
            p.color = Color.argb(dim * 255 / 100, 0, 0, 0)
            c.drawRect(rf, p)
        }

        if (th.fx != 0) drawBinaryPreview(c, 0f, 0f, w, h, th.accent, d)

        val pad = d * 10f
        val g = d * 5f
        // toolbar strip with the theme name
        val tb = d * 24f
        p.color = th.accent
        p.alpha = 70
        c.drawRoundRect(pad, pad, w - pad, pad + tb, d * 10f, d * 10f, p)
        p.alpha = 255
        label(c, th.name, w / 2f, pad + tb / 2f, d * 12f, th.text)

        val top = pad + tb + d * 8f
        val avail = h - top - pad
        val kh = (avail - 3f * g) / 4f
        val full = w - 2f * pad
        val kw = (full - 9f * g) / 10f
        val ts = kh * 0.42f

        // row 1: Q..P
        val r1 = "QWERTYUIOP"
        for (i in 0 until 10) {
            val l = pad + i * (kw + g)
            key(c, l, top, l + kw, top + kh, th.key, r1[i].toString(), th.text, ts)
        }
        // row 2: A..L, centred
        val r2 = "ASDFGHJKL"
        val y2 = top + kh + g
        val off2 = pad + (kw + g) / 2f
        for (i in 0 until 9) {
            val l = off2 + i * (kw + g)
            key(c, l, y2, l + kw, y2 + kh, th.key, r2[i].toString(), th.text, ts)
        }
        // row 3: shift, Z..M, backspace
        val r3 = "ZXCVBNM"
        val y3 = y2 + kh + g
        val sw = (full - 7f * kw - 8f * g) / 2f
        iconKey(c, pad, y3, pad + sw, y3 + kh, th.special, shiftIc, th.text)
        for (i in 0 until 7) {
            val l = pad + sw + g + i * (kw + g)
            key(c, l, y3, l + kw, y3 + kh, th.key, r3[i].toString(), th.text, ts)
        }
        val bl = w - pad - sw
        iconKey(c, bl, y3, bl + sw, y3 + kh, th.special, backIc, th.text)
        // row 4: 123, space, enter
        val y4 = y3 + kh + g
        key(c, pad, y4, pad + sw, y4 + kh, th.special, "123", th.text, ts * 0.8f)
        val spaceL = pad + sw + g
        val spaceR = w - pad - sw - g
        key(c, spaceL, y4, spaceR, y4 + kh, th.key, "Zeta Board", th.text, ts * 0.8f)
        key(c, spaceR + g, y4, w - pad, y4 + kh, th.accent, "Go", th.accentText, ts * 0.8f)

        c.restore()
        p.style = Paint.Style.STROKE
        p.strokeWidth = d
        p.color = Color.argb(50, 255, 255, 255)
        c.drawRoundRect(d * 0.5f, d * 0.5f, w - d * 0.5f, h - d * 0.5f, d * 20f, d * 20f, p)
        p.style = Paint.Style.FILL
    }
}

/** One theme in the grid: a small keyboard, its name, and a badge showing whether it is in use or still to be taken. */
private class ThemeTile(ctx: Context, val idx: Int, val th: KbTheme) : View(ctx) {
    var chosen = false
    var active = false
    var owned = true

    private val d = ctx.resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rf = RectF()
    private val check = ctx.getDrawable(R.drawable.ic_check)!!.mutate()
    private val dl = ctx.getDrawable(R.drawable.ic_download)!!.mutate()

    init {
        tp.typeface = Typeface.DEFAULT_BOLD
        tp.textAlign = Paint.Align.CENTER
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val r = d * 16f
        rf.set(d * 2f, d * 2f, w - d * 2f, h - d * 2f)
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(0f, 0f, 0f, h, th.bgTop, th.bgBottom, Shader.TileMode.CLAMP)
        c.drawRoundRect(rf, r, r, p)
        p.shader = null
        if (th.fx != 0) drawBinaryPreview(c, rf.left + d * 4f, rf.top + d * 4f, rf.right - d * 4f, rf.bottom - d * 4f, th.accent, d)

        val gap = d * 3f
        val kw = (w - d * 20f - gap * 5f) / 6f
        val kh = d * 12f
        for (row in 0..2) {
            for (col in 0..5) {
                val x = d * 10f + col * (kw + gap)
                val y = d * 14f + row * (kh + d * 5f)
                p.color = if (row == 2 && col == 5) th.accent else if (row == 2 && col == 0) th.special else th.key
                c.drawRoundRect(x, y, x + kw, y + kh, d * 3f, d * 3f, p)
            }
        }
        tp.color = th.text
        tp.textSize = d * 11f
        var name = th.name
        val maxW = w - d * 12f
        while (name.length > 3 && tp.measureText(name) > maxW) name = name.substring(0, name.length - 2)
        if (name != th.name) name += "\u2026"
        c.drawText(name, w / 2f, h - d * 15f, tp)

        // outline: selected = bright ring, otherwise a faint edge
        p.style = Paint.Style.STROKE
        if (chosen) {
            p.strokeWidth = d * 2.5f
            p.color = Pal.CYAN
        } else {
            p.strokeWidth = d
            p.color = Color.argb(50, 255, 255, 255)
        }
        c.drawRoundRect(rf, r, r, p)
        p.style = Paint.Style.FILL

        // badge: check = in use, download arrow = not taken yet
        val cx = w - d * 17f
        val cy = d * 17f
        if (active) {
            p.color = th.accent
            c.drawCircle(cx, cy, d * 10f, p)
            check.setTint(th.accentText)
            check.setBounds((cx - d * 6f).toInt(), (cy - d * 6f).toInt(), (cx + d * 6f).toInt(), (cy + d * 6f).toInt())
            check.draw(c)
        } else if (!owned) {
            p.color = Color.argb(150, 0, 0, 0)
            c.drawCircle(cx, cy, d * 10f, p)
            dl.setTint(Color.WHITE)
            dl.setBounds((cx - d * 6f).toInt(), (cy - d * 6f).toInt(), (cx + d * 6f).toInt(), (cy + d * 6f).toInt())
            dl.draw(c)
        }
    }
}

/** One wallpaper in the grid: its thumbnail (centre-cropped like the keyboard does), a name strip and a tick when it is in use. */
private class WallpaperTile(ctx: Context, val wp: Wallpaper) : View(ctx) {
    var bmp: Bitmap? = null
    var chosen = false
    var applied = false

    private val d = ctx.resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bp = Paint(Paint.FILTER_BITMAP_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rf = RectF()
    private val clip = Path()
    private val src = Rect()
    private val check: Drawable = ctx.getDrawable(R.drawable.ic_check)!!.mutate()

    init {
        tp.typeface = Typeface.DEFAULT_BOLD
        tp.textAlign = Paint.Align.CENTER
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val r = d * 16f
        rf.set(d * 2f, d * 2f, w - d * 2f, h - d * 2f)
        clip.reset()
        clip.addRoundRect(rf, r, r, Path.Direction.CW)
        c.save()
        c.clipPath(clip)

        p.style = Paint.Style.FILL
        p.shader = null
        p.color = Pal.TILE
        c.drawRect(rf, p)
        val b = bmp
        if (b != null) {
            val sc = maxOf(rf.width() / b.width, rf.height() / b.height)
            val sw = rf.width() / sc
            val sh = rf.height() / sc
            src.set(((b.width - sw) / 2f).toInt(), ((b.height - sh) / 2f).toInt(), ((b.width + sw) / 2f).toInt(), ((b.height + sh) / 2f).toInt())
            c.drawBitmap(b, src, rf, bp)
        }

        // name strip
        val band = d * 24f
        p.color = Color.argb(140, 0, 0, 0)
        c.drawRect(rf.left, rf.bottom - band, rf.right, rf.bottom, p)
        tp.color = Color.WHITE
        tp.textSize = d * 11f
        var name = wp.name
        val maxW = w - d * 14f
        while (name.length > 3 && tp.measureText(name) > maxW) name = name.substring(0, name.length - 2)
        if (name != wp.name) name += "\u2026"
        c.drawText(name, w / 2f, rf.bottom - band / 2f - (tp.descent() + tp.ascent()) / 2f, tp)
        c.restore()

        // outline: selected = bright ring, otherwise a faint edge
        p.style = Paint.Style.STROKE
        if (chosen) {
            p.strokeWidth = d * 2.5f
            p.color = Pal.CYAN
        } else {
            p.strokeWidth = d
            p.color = Color.argb(50, 255, 255, 255)
        }
        c.drawRoundRect(rf, r, r, p)
        p.style = Paint.Style.FILL

        // tick = this wallpaper is the keyboard background now
        if (applied) {
            val cx = w - d * 17f
            val cy = d * 17f
            p.color = Pal.CYAN
            c.drawCircle(cx, cy, d * 10f, p)
            check.setTint(Color.parseColor("#04222A"))
            check.setBounds((cx - d * 6f).toInt(), (cy - d * 6f).toInt(), (cx + d * 6f).toInt(), (cy + d * 6f).toInt())
            check.draw(c)
        }
    }
}
