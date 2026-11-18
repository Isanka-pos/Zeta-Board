package com.zeta.board

import android.app.Activity
import android.Manifest
import android.content.Intent
import java.io.File
import android.content.pm.PackageManager
import android.os.Build
import android.widget.ImageView.ScaleType
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.format.DateUtils
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var kv: KeyboardView
    private lateinit var host: KeyboardHost
    private var clearArmed = false
    private lateinit var et: EditText
    private lateinit var col: LinearLayout
    private val steps = ArrayList<StepRow>()
    private val chips = ArrayList<ThemeChip>()
    private val shapeChips = ArrayList<ShapeChip>()
    private lateinit var thumb: ImageView
    private var splashDone = false
    private var lastCheck = 0L
    private lateinit var thumbHint: TextView
    private lateinit var clipStat: TextView
    private lateinit var sideMenu: SideMenu
    private lateinit var scrollRoot: ScrollView
    private lateinit var headerSub: TextView
    private lateinit var updStatus: TextView
    private lateinit var updateBanner: View
    private lateinit var updateBannerText: TextView
    private lateinit var menuDot: View
    private val sections = ArrayList<Pair<View, Set<String>>>()
    private var curGroup = "home"

    // re-checks for updates every 15 minutes while the app is on screen
    private val ticker = Handler(Looper.getMainLooper())
    private val tickRun = object : Runnable {
        override fun run() {
            if (splashDone && !isFinishing && !isDestroyed && !Updater.dialogOpen) checkUpdate(false)
            ticker.postDelayed(this, UpdateWatcher.INTERVAL_MS)
        }
    }

    private val dens: Float by lazy { resources.displayMetrics.density }
    private fun dp(v: Int): Int = (v * dens).toInt()
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UpdateScheduler.schedule(this)
        ClipRepo.init(this)
        val root = FrameLayout(this)
        root.setBackgroundColor(Pal.BG)
        val home = buildHome()
        root.addView(home, FrameLayout.LayoutParams(MATCH, MATCH))
        sideMenu = SideMenu(this, menuEntries()) { id -> onMenuPick(id) }
        sideMenu.setVersion("Version " + Updater.currentName(this))
        root.addView(sideMenu, FrameLayout.LayoutParams(MATCH, MATCH))
        val splash = SplashView(this)
        root.addView(splash, FrameLayout.LayoutParams(MATCH, MATCH))
        setContentView(root)

        for (i in 0 until col.childCount) {
            val v = col.getChildAt(i)
            v.alpha = 0f
            v.translationY = dp(30).toFloat()
        }
        splash.onDone = {
            splashDone = true
            afterSplash()
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                col.postDelayed({
                    if (!isFinishing) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 9)
                }, 1200L)
            }
            splash.animate().alpha(0f).scaleX(1.06f).scaleY(1.06f).setDuration(480).withEndAction { root.removeView(splash) }.start()
            for (i in 0 until col.childCount) {
                col.getChildAt(i).animate()
                    .alpha(1f).translationY(0f)
                    .setStartDelay(150L + i * 90L).setDuration(450)
                    .setInterpolator(DecelerateInterpolator()).start()
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (::sideMenu.isInitialized && sideMenu.isOpen) {
            sideMenu.close()
            return
        }
        if (curGroup != "home") {
            showGroup("home")
            return
        }
        super.onBackPressed()
    }

    /** Runs once the splash is gone: shows "what's new" after an update, otherwise looks for a new version. */
    private fun afterSplash() {
        PostUpdate.detect(this)
        if (PostUpdate.shouldShow(this)) {
            val name = Updater.currentName(this)
            val saved = PostUpdate.savedNotes(this)
            PostUpdate.markShown(this)
            if (saved.isNotBlank()) {
                showWhatsNew(name, saved)
            } else {
                // the notes were never saved (first update with this feature): read them from the release
                Updater.latest(this) { l ->
                    if (isFinishing || isDestroyed) return@latest
                    val n = if (l != null && l.code == Updater.currentCode(this)) l.notes else ""
                    showWhatsNew(name, n.ifBlank { "Improvements and bug fixes" })
                }
            }
        } else {
            checkUpdate(false)
        }
        refreshUpdateUi()
    }

    private fun showWhatsNew(name: String, notes: String) {
        if (Updater.dialogOpen) return
        WhatsNewDialog.show(this, name, notes) {
            if (!isFinishing && !isDestroyed) checkUpdate(false)
        }
    }

    override fun onPause() {
        ticker.removeCallbacks(tickRun)
        super.onPause()
    }

    override fun onDestroy() {
        ticker.removeCallbacksAndMessages(null)
        Updater.dialog?.dismiss()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        ticker.removeCallbacks(tickRun)
        ticker.postDelayed(tickRun, UpdateWatcher.INTERVAL_MS)
        refreshClipStat()
        refreshUpdateUi()
        fontPaint?.invoke()
        textPaint?.invoke()
        if (splashDone && !Updater.dialogOpen && System.currentTimeMillis() - lastCheck > 60000L) checkUpdate(false)
        val pend = Updater.pending
        if (pend != null && packageManager.canRequestPackageInstalls()) {
            Updater.pending = null
            Updater.install(this, pend)
        }
        if (steps.size == 2) {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            val enabled = imm.enabledInputMethodList.any { it.packageName == packageName }
            val sel = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
                ?.startsWith(packageName) == true
            steps[0].setDone(enabled)
            steps[1].setDone(sel)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 77 && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            Thread {
                val ok = KbBg.save(this, uri)
                runOnUiThread {
                    if (ok) {
                        kv.reload()
                        refreshThumb()
                    } else {
                        Toast.makeText(this, "Could not load that image", Toast.LENGTH_SHORT).show()
                    }
                }
            }.start()
        }
    }

    private fun refreshThumb() {
        val b = if (KbBg.has(this)) KbBg.load(this, 4) else null
        thumb.setImageBitmap(b)
        thumbHint.visibility = if (b == null) View.VISIBLE else View.GONE
    }

    private fun gradientPill(): GradientDrawable {
        val g = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Pal.CYAN, Pal.PURPLE))
        g.cornerRadius = dp(14).toFloat()
        return g
    }

    private fun segment(labels: List<String>, cur: Int, onPick: (Int) -> Unit): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        val views = ArrayList<TextView>()
        fun paint(sel: Int) {
            views.forEachIndexed { i, v ->
                v.background = if (i == sel) gradientPill() else roundBg(Pal.BG, 14, Pal.STROKE, 1)
                v.setTextColor(if (i == sel) Color.WHITE else Pal.MUTED)
            }
        }
        labels.forEachIndexed { i, s ->
            val t = tv(s, 12f, Pal.MUTED, true)
            t.gravity = Gravity.CENTER
            t.setPadding(dp(4), dp(11), dp(4), dp(11))
            val lp = LinearLayout.LayoutParams(0, WRAP, 1f)
            if (i > 0) lp.leftMargin = dp(6)
            row.addView(t, lp)
            views.add(t)
            t.setOnClickListener {
                paint(i)
                onPick(i)
            }
        }
        paint(cur)
        return row
    }

    private fun slider(label: String, unit: String, min: Int, max: Int, key: String, def: Int): View {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(0, dp(16), 0, 0)
        val cur = Prefs.getI(this, key, def)
        val lab = tv(label + "  " + cur + unit, 14f, Color.WHITE)
        box.addView(lab)
        val sb = SeekBar(this)
        sb.max = max - min
        sb.progress = cur - min
        sb.progressTintList = ColorStateList.valueOf(Pal.PURPLE)
        sb.thumbTintList = ColorStateList.valueOf(Pal.CYAN)
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                val v = min + p
                lab.text = label + "  " + v + unit
                if (fromUser) {
                    Prefs.setI(this@MainActivity, key, v)
                    kv.reload()
                }
            }

            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        box.addView(sb, LinearLayout.LayoutParams(MATCH, WRAP))
        return box
    }

    private fun shapeCard(): View {
        val c = card("Key shape", "Choose how every key looks")
        val hs = HorizontalScrollView(this)
        hs.isHorizontalScrollBarEnabled = false
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        val cur = Prefs.getI(this, "shape", 0)
        for (i in KeyShape.names.indices) {
            val ch = ShapeChip(this, i, i == cur)
            shapeChips.add(ch)
            val lp = LinearLayout.LayoutParams(dp(96), dp(88))
            lp.rightMargin = dp(10)
            row.addView(ch, lp)
            ch.setOnClickListener {
                Prefs.setI(this, "shape", i)
                kv.reload()
                shapeChips.forEachIndexed { j, x -> x.select(j == i) }
            }
        }
        hs.addView(row)
        val lp = LinearLayout.LayoutParams(MATCH, WRAP)
        lp.topMargin = dp(14)
        c.addView(hs, lp)
        return c
    }

    private fun rgbCard(): View {
        val c = card("RGB lighting", "Animated rainbow glow on your keyboard")
        val lp = LinearLayout.LayoutParams(MATCH, WRAP)
        lp.topMargin = dp(14)
        c.addView(segment(listOf("Off", "Wave", "Border", "Both"), Prefs.getI(this, "rgb", 0)) {
            Prefs.setI(this, "rgb", it)
            kv.reload()
        }, lp)
        c.addView(slider("Speed", "", 1, 10, "rgbspeed", 5))
        return c
    }

    private fun photoCard(): View {
        val c = card("Photo background", "Use any picture from your gallery")
        val frame = FrameLayout(this)
        frame.background = roundBg(Pal.BG, 16, Pal.STROKE, 1)
        val radius = dp(16).toFloat()
        frame.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }
        frame.clipToOutline = true
        thumbHint = tv("No photo selected", 13f, Pal.MUTED)
        thumbHint.gravity = Gravity.CENTER
        frame.addView(thumbHint, FrameLayout.LayoutParams(MATCH, MATCH))
        thumb = ImageView(this)
        thumb.scaleType = ScaleType.CENTER_CROP
        frame.addView(thumb, FrameLayout.LayoutParams(MATCH, MATCH))
        val flp = LinearLayout.LayoutParams(MATCH, dp(130))
        flp.topMargin = dp(14)
        c.addView(frame, flp)
        refreshThumb()

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        val choose = pill("Choose photo") {
            val i = Intent(Intent.ACTION_GET_CONTENT)
            i.type = "image/*"
            i.addCategory(Intent.CATEGORY_OPENABLE)
            startActivityForResult(Intent.createChooser(i, "Select photo"), 77)
        }
        val remove = pill("Remove") {
            KbBg.clear(this)
            kv.reload()
            refreshThumb()
        }
        remove.background = roundBg(Pal.BG, 30, Pal.STROKE, 1)
        val l1 = LinearLayout.LayoutParams(0, WRAP, 1f)
        l1.rightMargin = dp(10)
        row.addView(choose, l1)
        row.addView(remove, LinearLayout.LayoutParams(0, WRAP, 1f))
        val rlp = LinearLayout.LayoutParams(MATCH, WRAP)
        rlp.topMargin = dp(14)
        c.addView(row, rlp)
        c.addView(slider("Photo dim", "%", 0, 80, "bgdim", 35))
        return c
    }

    private fun textStyleCard(): View {
        val c = card("Text style", "Change how the words you type look. Also on the keyboard: tap the A icon.")
        val hs = HorizontalScrollView(this)
        hs.isHorizontalScrollBarEnabled = false
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        val chipsT = ArrayList<Pair<Int, TextView>>()
        fun paint() {
            val sel = Prefs.getI(this, "textstyle", 0)
            for ((i, t) in chipsT) {
                t.background = if (i == sel) gradientPill() else roundBg(Pal.BG, 14, Pal.STROKE, 1)
                t.setTextColor(if (i == sel) Color.WHITE else Pal.MUTED)
            }
        }
        val probe = android.graphics.Paint()
        for (i in TextStyles.names.indices) {
            if (!TextStyles.supported(i, probe)) continue
            val t = tv(TextStyles.sample(i) + "\n" + TextStyles.names[i], 15f, Pal.MUTED)
            t.gravity = Gravity.CENTER
            t.setPadding(dp(12), dp(12), dp(12), dp(12))
            val lp = LinearLayout.LayoutParams(dp(104), WRAP)
            lp.rightMargin = dp(10)
            row.addView(t, lp)
            chipsT.add(Pair(i, t))
            t.setOnClickListener {
                Prefs.setI(this, "textstyle", i)
                kv.reload()
                paint()
            }
        }
        paint()
        hs.addView(row)
        val lp = LinearLayout.LayoutParams(MATCH, WRAP)
        lp.topMargin = dp(14)
        c.addView(hs, lp)
        textPaint = { paint() }
        return c
    }

    private var textPaint: (() -> Unit)? = null
    private var fontPaint: (() -> Unit)? = null

    private fun keyFontCard(): View {
        val c = card("Key font", "How the keys look. You can also switch it from the A icon on the keyboard.")
        val hs = HorizontalScrollView(this)
        hs.isHorizontalScrollBarEnabled = false
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        val chipsF = ArrayList<TextView>()
        fun paint() {
            val sel = KbFonts.selected(this)
            chipsF.forEachIndexed { i, t ->
                t.background = if (i == sel) gradientPill() else roundBg(Pal.BG, 14, Pal.STROKE, 1)
                t.setTextColor(if (i == sel) Color.WHITE else Pal.MUTED)
            }
        }
        fontPaint = { paint() }
        KbFonts.names.forEachIndexed { i, name ->
            val t = tv("Aa Zeta\n" + name, 14f, Pal.MUTED)
            t.typeface = KbFonts.preset(i)
            t.gravity = Gravity.CENTER
            t.setPadding(dp(14), dp(12), dp(14), dp(12))
            val lp = LinearLayout.LayoutParams(dp(104), WRAP)
            lp.rightMargin = dp(10)
            row.addView(t, lp)
            chipsF.add(t)
            t.setOnClickListener {
                Prefs.setI(this, "keyfont", i)
                kv.reload()
                paint()
            }
        }
        paint()
        hs.addView(row)
        val lp = LinearLayout.LayoutParams(MATCH, WRAP)
        lp.topMargin = dp(14)
        c.addView(hs, lp)
        return c
    }

    private fun aboutCard(): View {
        val c = card("About", "Zeta Board  \u2022  v" + Updater.currentName(this))
        c.gravity = Gravity.CENTER_HORIZONTAL
        for (i in 0 until c.childCount) (c.getChildAt(i) as? TextView)?.gravity = Gravity.CENTER
        val d = tv("A premium keyboard with themes, a clipboard manager and Sinhala typing.", 13f, Pal.MUTED)
        d.gravity = Gravity.CENTER
        d.setPadding(0, dp(10), 0, 0)
        c.addView(d)
        val owner = tv("Owner: Isanka Dev", 13f, Color.WHITE, true)
        owner.gravity = Gravity.CENTER
        owner.setPadding(0, dp(8), 0, 0)
        c.addView(owner)

        val btn = LinearLayout(this)
        btn.orientation = LinearLayout.HORIZONTAL
        btn.gravity = Gravity.CENTER
        btn.setPadding(dp(22), dp(12), dp(22), dp(12))
        btn.background = roundBg(Color.parseColor("#25D366"), 30)
        val ic = ImageView(this)
        ic.setImageResource(R.drawable.ic_whatsapp)
        btn.addView(ic, LinearLayout.LayoutParams(dp(22), dp(22)))
        val label = tv("Contact owner", 14f, Color.WHITE, true)
        label.setPadding(dp(10), 0, 0, 0)
        btn.addView(label)
        btn.setOnClickListener { v ->
            v.animate().scaleX(0.94f).scaleY(0.94f).setDuration(70).withEndAction {
                v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://wa.me/94765480861")))
                } catch (e: Exception) {
                    Toast.makeText(this, "Could not open WhatsApp", Toast.LENGTH_SHORT).show()
                }
            }.start()
        }
        val lp = LinearLayout.LayoutParams(WRAP, WRAP)
        lp.gravity = Gravity.CENTER_HORIZONTAL
        lp.topMargin = dp(16)
        c.addView(btn, lp)
        return c
    }

    private fun clipManagerCard(): View {
        val c = card("Clipboard Manager", "Everything you copy, saved on this phone only")
        clipStat = tv("Loading...", 13f, Pal.MUTED)
        clipStat.setPadding(0, dp(12), 0, 0)
        c.addView(clipStat)
        val open = pill("Open Clipboard Manager") {
            startActivity(Intent(this, ClipManagerActivity::class.java))
        }
        val lp = LinearLayout.LayoutParams(MATCH, WRAP)
        lp.topMargin = dp(14)
        c.addView(open, lp)
        return c
    }

    private fun refreshClipStat() {
        ClipRepo.load("") { pinned, recent ->
            if (!isFinishing && !isDestroyed) {
                val total = pinned.size + recent.size
                if (total == 0) {
                    clipStat.text = "No clips yet. Copy some text while Zeta Board is your keyboard."
                } else {
                    clipStat.text = total.toString() + " saved  \u2022  " + pinned.size + " pinned"
                }
            }
        }
    }

    private fun checkUpdate(manual: Boolean) {
        lastCheck = System.currentTimeMillis()
        if (manual) Toast.makeText(this, "Checking for updates...", Toast.LENGTH_SHORT).show()
        Updater.check(this) { info, err ->
            if (isFinishing || isDestroyed) return@check
            if (err == null) Prefs.setL(this, "last_ok_check", System.currentTimeMillis())
            if (info != null) {
                UpdateStore.save(this, info)
                refreshUpdateUi()
                if (!Updater.dialogOpen) UpdateDialog.show(this, info)
            } else {
                if (err == null) UpdateStore.dropRetracted(this)
                refreshUpdateUi()
                if (manual) {
                    val msg = if (err != null) "Update check failed: $err" else "You are on the latest version"
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // ---------- category menu ----------

    private fun menuEntries(): List<MenuEntry> = listOf(
        MenuEntry("home", "Home", "Everything in one place", R.drawable.ic_home),
        MenuEntry("setup", "Setup", "Enable and select the keyboard", R.drawable.ic_keyboard),
        MenuEntry("look", "Appearance", "Themes, shapes, RGB, photo", R.drawable.ic_palette),
        MenuEntry("typing", "Typing & Fonts", "Text style, key font, feel", R.drawable.ic_font),
        MenuEntry("clipboard", "Clipboard", "History and manager", R.drawable.ic_paste),
        MenuEntry("sticker", "Sticker Maker", "Photo to WhatsApp sticker", R.drawable.ic_sticker),
        MenuEntry("updates", "Updates", "Version and what's new", R.drawable.ic_download),
        MenuEntry("about", "About", "Owner and contact", R.drawable.ic_info)
    )

    private fun onMenuPick(id: String) {
        sideMenu.close()
        if (id == "sticker") {
            startActivity(Intent(this, StickerMakerActivity::class.java))
            return
        }
        showGroup(id)
    }

    /** Shows only the cards that belong to the chosen category ("home" shows everything). */
    private fun showGroup(key: String) {
        val changed = key != curGroup
        curGroup = key
        sideMenu.select(key)
        headerSub.text = if (key == "home") "Premium keyboard" else menuEntries().first { it.id == key }.title
        var n = 0
        for ((v, g) in sections) {
            if (v === updateBanner) continue
            val show = key == "home" || g.contains("*") || g.contains(key)
            if (!show) {
                v.visibility = View.GONE
                continue
            }
            v.visibility = View.VISIBLE
            if (changed && !g.contains("*")) {
                v.alpha = 0f
                v.translationY = dp(18).toFloat()
                v.animate().alpha(1f).translationY(0f)
                    .setStartDelay(40L * n).setDuration(300)
                    .setInterpolator(DecelerateInterpolator()).start()
                n++
            }
        }
        scrollRoot.scrollTo(0, 0)
    }

    private fun put(v: View, vararg groups: String) {
        col.addView(v)
        sections.add(Pair(v, groups.toSet()))
    }

    private fun menuButton(): View {
        val box = FrameLayout(this)
        val b = ImageView(this)
        b.setImageResource(R.drawable.ic_menu)
        b.setColorFilter(Color.WHITE)
        b.setPadding(dp(11), dp(11), dp(11), dp(11))
        b.background = RippleDrawable(ColorStateList.valueOf(Color.argb(60, 168, 85, 247)), roundBg(Pal.CARD, 23, Pal.STROKE, 1), null)
        b.contentDescription = "Menu"
        b.setOnClickListener { sideMenu.open() }
        box.addView(b, FrameLayout.LayoutParams(dp(46), dp(46)))
        val dot = View(this)
        val dotBg = GradientDrawable()
        dotBg.shape = GradientDrawable.OVAL
        dotBg.setColor(Color.parseColor("#EF4444"))
        dotBg.setStroke(dp(2), Pal.BG)
        dot.background = dotBg
        dot.visibility = View.GONE
        menuDot = dot
        val dlp = FrameLayout.LayoutParams(dp(14), dp(14), Gravity.TOP or Gravity.END)
        dlp.topMargin = dp(1)
        dlp.rightMargin = dp(1)
        box.addView(dot, dlp)
        return box
    }

    // ---------- update warning ----------

    private fun updateBannerView(): View {
        val amber = Color.parseColor("#F59E0B")
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(14), dp(12), dp(12), dp(12))
        row.background = roundBg(Color.argb(34, 245, 158, 11), 20, amber, 1)
        val ic = ImageView(this)
        ic.setImageResource(R.drawable.ic_warning)
        ic.setColorFilter(amber)
        row.addView(ic, LinearLayout.LayoutParams(dp(26), dp(26)))
        val t = LinearLayout(this)
        t.orientation = LinearLayout.VERTICAL
        t.setPadding(dp(12), 0, dp(8), 0)
        t.addView(tv("Update available", 15f, Color.WHITE, true))
        updateBannerText = tv("", 12f, Color.parseColor("#E9D5A6"))
        t.addView(updateBannerText)
        row.addView(t, LinearLayout.LayoutParams(0, WRAP, 1f))
        row.addView(pill("Update") { showPendingUpdate() })
        row.setOnClickListener { showPendingUpdate() }
        val lp = LinearLayout.LayoutParams(MATCH, WRAP)
        lp.topMargin = dp(16)
        row.layoutParams = lp
        row.visibility = View.GONE
        updateBanner = row
        return row
    }

    private fun showPendingUpdate() {
        val info = UpdateStore.pending(this)
        if (info == null) {
            refreshUpdateUi()
            return
        }
        if (!Updater.dialogOpen) UpdateDialog.show(this, info)
    }

    /** Keeps the warning banner, the red dots and the Updates card in line with what is known about new versions. */
    private fun refreshUpdateUi() {
        val info = UpdateStore.pending(this)
        if (info != null) {
            updateBanner.visibility = View.VISIBLE
            updateBannerText.text = "Version " + info.name + " is ready. Update now for the latest fixes."
            updStatus.text = "Update available: v" + info.name
            updStatus.setTextColor(Color.parseColor("#F59E0B"))
        } else {
            updateBanner.visibility = View.GONE
            val ts = Prefs.getL(this, "last_ok_check", 0L)
            val ago = if (ts == 0L) "Not checked yet" else "Last checked " + DateUtils.getRelativeTimeSpanString(ts, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
            updStatus.text = "You are on the latest version  \u2022  " + ago
            updStatus.setTextColor(Pal.GREEN)
        }
        menuDot.visibility = if (info != null) View.VISIBLE else View.GONE
        sideMenu.badge("updates", info != null)
    }

    private fun updatesCard(): View {
        val c = card("Updates", "Version " + Updater.currentName(this))
        updStatus = tv("", 13f, Pal.MUTED)
        updStatus.setPadding(0, dp(12), 0, 0)
        c.addView(updStatus)
        val note = tv("Zeta Board checks for new versions every " + UpdateScheduler.INTERVAL_MIN + " minutes and tells you with a notification.", 12f, Pal.MUTED)
        note.setPadding(0, dp(8), 0, 0)
        c.addView(note)
        val lp = LinearLayout.LayoutParams(MATCH, WRAP)
        lp.topMargin = dp(14)
        c.addView(pill("Check for updates") { checkUpdate(true) }, lp)
        return c
    }

    // ---------- helpers ----------

    private fun tv(s: String, size: Float, color: Int, bold: Boolean = false): TextView {
        val t = TextView(this)
        t.text = s
        t.textSize = size
        t.setTextColor(color)
        if (bold) t.typeface = Typeface.DEFAULT_BOLD
        return t
    }

    private fun roundBg(color: Int, radius: Int, strokeColor: Int = 0, stroke: Int = 0): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = dp(radius).toFloat()
        if (stroke > 0) g.setStroke(dp(stroke), strokeColor)
        return g
    }

    private fun card(title: String, sub: String): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.setPadding(dp(18), dp(18), dp(18), dp(18))
        c.background = roundBg(Pal.CARD, 24, Pal.STROKE, 1)
        val lp = LinearLayout.LayoutParams(MATCH, WRAP)
        lp.topMargin = dp(16)
        c.layoutParams = lp
        c.addView(tv(title, 17f, Color.WHITE, true))
        c.addView(tv(sub, 12f, Pal.MUTED))
        return c
    }

    private fun pill(text: String, onClick: () -> Unit): TextView {
        val t = tv(text, 13f, Color.WHITE, true)
        t.gravity = Gravity.CENTER
        t.setPadding(dp(18), dp(10), dp(18), dp(10))
        val g = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Pal.CYAN, Pal.PURPLE))
        g.cornerRadius = dp(30).toFloat()
        t.background = g
        t.setOnClickListener { v ->
            v.animate().scaleX(0.94f).scaleY(0.94f).setDuration(70).withEndAction {
                v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                onClick()
            }.start()
        }
        return t
    }

    private inner class StepRow(
        number: Int, title: String, desc: String, private val btnText: String, onClick: () -> Unit
    ) : LinearLayout(this@MainActivity) {
        private val badge = TextView(this@MainActivity)
        private val check = ImageView(this@MainActivity)
        private val button: TextView

        init {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(14), 0, dp(2))
            val f = FrameLayout(this@MainActivity)
            badge.text = number.toString()
            badge.setTextColor(Color.WHITE)
            badge.typeface = Typeface.DEFAULT_BOLD
            badge.gravity = Gravity.CENTER
            val bg = GradientDrawable()
            bg.shape = GradientDrawable.OVAL
            bg.setColor(Pal.STROKE)
            badge.background = bg
            val cg = GradientDrawable()
            cg.shape = GradientDrawable.OVAL
            cg.setColor(Pal.GREEN)
            check.background = cg
            check.setImageResource(R.drawable.ic_check)
            check.setColorFilter(Color.WHITE)
            check.setPadding(dp(8), dp(8), dp(8), dp(8))
            check.visibility = View.GONE
            f.addView(badge, FrameLayout.LayoutParams(dp(34), dp(34)))
            f.addView(check, FrameLayout.LayoutParams(dp(34), dp(34)))
            addView(f)
            val c = LinearLayout(this@MainActivity)
            c.orientation = LinearLayout.VERTICAL
            c.setPadding(dp(12), 0, dp(8), 0)
            c.addView(tv(title, 15f, Color.WHITE, true))
            c.addView(tv(desc, 12f, Pal.MUTED))
            addView(c, LinearLayout.LayoutParams(0, WRAP, 1f))
            button = pill(btnText, onClick)
            addView(button)
        }

        fun setDone(done: Boolean) {
            check.visibility = if (done) View.VISIBLE else View.GONE
            badge.visibility = if (done) View.GONE else View.VISIBLE
            button.text = if (done) "Done" else btnText
            button.alpha = if (done) 0.5f else 1f
        }
    }

    // ---------- sections ----------

    private fun buildHome(): ScrollView {
        col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(18), dp(32), dp(18), dp(40))
        put(header(), "*")
        put(updateBannerView(), "*")
        put(setupCard(), "setup")
        put(previewCard(), "setup", "look", "typing")
        put(themeCard(), "look")
        put(shapeCard(), "look")
        put(rgbCard(), "look")
        put(photoCard(), "look")
        put(textStyleCard(), "typing")
        put(keyFontCard(), "typing")
        put(typingCard(), "typing")
        put(clipManagerCard(), "clipboard")
        put(clipSettingsCard(), "clipboard")
        put(updatesCard(), "updates")
        put(aboutCard(), "about")
        val foot = tv("Zeta Board v" + Updater.currentName(this) + "  \u2022  Made by Isanka Dev", 12f, Pal.MUTED)
        foot.gravity = Gravity.CENTER
        foot.setPadding(0, dp(24), 0, 0)
        col.addView(foot, LinearLayout.LayoutParams(MATCH, WRAP))
        sections.add(Pair(foot, setOf("*")))
        val sv = ScrollView(this)
        sv.setBackgroundColor(Pal.BG)
        sv.isVerticalScrollBarEnabled = false
        sv.addView(col)
        scrollRoot = sv
        return sv
    }

    private fun header(): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.addView(LogoBadge(this), LinearLayout.LayoutParams(dp(60), dp(60)))
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.setPadding(dp(14), 0, 0, 0)
        c.addView(tv("Zeta Board", 26f, Color.WHITE, true))
        headerSub = tv("Premium keyboard", 13f, Pal.MUTED)
        c.addView(headerSub)
        row.addView(c, LinearLayout.LayoutParams(0, WRAP, 1f))
        row.addView(menuButton(), LinearLayout.LayoutParams(dp(46), dp(46)))
        return row
    }

    private fun setupCard(): View {
        val c = card("Setup", "Two quick steps to start typing")
        steps.add(StepRow(1, "Enable Zeta Board", "Turn it on in keyboard settings", "Enable") {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        })
        steps.add(StepRow(2, "Switch keyboard", "Pick Zeta Board as active", "Select") {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        })
        for (s in steps) c.addView(s)
        return c
    }

    private fun themeCard(): View {
        val c = card("Themes", "Tap a theme to apply it live")
        val hs = HorizontalScrollView(this)
        hs.isHorizontalScrollBarEnabled = false
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        val cur = Prefs.getI(this, "theme", 0)
        Themes.all.forEachIndexed { i, t ->
            val ch = ThemeChip(this, t, i == cur)
            chips.add(ch)
            val lp = LinearLayout.LayoutParams(dp(104), dp(128))
            lp.rightMargin = dp(10)
            row.addView(ch, lp)
            ch.setOnClickListener {
                Prefs.setI(this, "theme", i)
                kv.theme = Themes.get(i)
                chips.forEachIndexed { j, x -> x.select(j == i) }
            }
        }
        hs.addView(row)
        val lp = LinearLayout.LayoutParams(MATCH, WRAP)
        lp.topMargin = dp(14)
        c.addView(hs, lp)
        return c
    }

    private fun previewCard(): View {
        val c = card("Live preview", "Try the keyboard right here")
        et = EditText(this)
        et.hint = "Type something..."
        et.setTextColor(Color.WHITE)
        et.setHintTextColor(Pal.MUTED)
        et.textSize = 15f
        et.minHeight = dp(56)
        et.gravity = Gravity.TOP or Gravity.START
        et.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        et.showSoftInputOnFocus = false
        et.setPadding(dp(14), dp(12), dp(14), dp(12))
        et.background = roundBg(Pal.BG, 16, Pal.STROKE, 1)
        val lp1 = LinearLayout.LayoutParams(MATCH, WRAP)
        lp1.topMargin = dp(14)
        c.addView(et, lp1)

        host = KeyboardHost(this, object : KeyboardHost.Output {
            override fun commit(s: String) {
                val pos = et.selectionStart.coerceAtLeast(0)
                et.text.insert(pos, s)
            }

            override fun del() {
                val t = et.text
                val pos = et.selectionStart.coerceAtLeast(0)
                if (pos > 0) {
                    val n = if (pos >= 2 && Character.isLowSurrogate(t[pos - 1]) && Character.isHighSurrogate(t[pos - 2])) 2 else 1
                    t.delete(pos - n, pos)
                }
            }

            override fun enter() {
                et.text.insert(et.selectionStart.coerceAtLeast(0), "\n")
            }

            override fun cursor(dir: Int) {
                val pos = et.selectionStart.coerceAtLeast(0)
                if (dir < 0 && pos > 0) et.setSelection(pos - 1)
                if (dir > 0 && pos < et.text.length) et.setSelection(pos + 1)
            }

            override fun settings() {}

            override fun pickPhoto() {
                startActivity(Intent(this@MainActivity, StickerActivity::class.java))
            }

            override fun shareSticker(f: File) {
                try {
                    val send = Intent(Intent.ACTION_SEND)
                        .setType("image/webp")
                        .putExtra(Intent.EXTRA_STREAM, StickerSender.uriFor(this@MainActivity, f))
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    startActivity(Intent.createChooser(send, "Send sticker"))
                } catch (e: Exception) {
                }
            }
        })
        kv = host.kv
        kv.theme = Themes.get(Prefs.getI(this, "theme", 0))
        val radius = dp(18).toFloat()
        host.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }
        host.clipToOutline = true
        val lp2 = LinearLayout.LayoutParams(MATCH, WRAP)
        lp2.topMargin = dp(12)
        c.addView(host, lp2)
        return c
    }

    private fun switchRow(title: String, desc: String, key: String, def: Boolean): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, dp(10), 0, dp(10))
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.addView(tv(title, 15f, Color.WHITE))
        c.addView(tv(desc, 12f, Pal.MUTED))
        row.addView(c, LinearLayout.LayoutParams(0, WRAP, 1f))
        val sw = Switch(this)
        sw.isChecked = Prefs.getB(this, key, def)
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        sw.trackTintList = ColorStateList(states, intArrayOf(Pal.PURPLE, Color.argb(60, 255, 255, 255)))
        sw.thumbTintList = ColorStateList.valueOf(Color.WHITE)
        sw.setOnCheckedChangeListener { _, v ->
            Prefs.setB(this, key, v)
            kv.reload()
        }
        row.addView(sw)
        return row
    }

    private fun typingCard(): View {
        val c = card("Typing", "Make it feel right")
        c.addView(switchRow("Vibration", "Haptic tap on every key", "haptic", true))
        c.addView(switchRow("Key sound", "Click sound while typing", "sound", false))
        c.addView(switchRow("Key preview", "Pop-up bubble above pressed key", "preview", true))
        c.addView(switchRow("Number row", "Always show 1-0 on top", "numrow", true))
        c.addView(switchRow("Apple emoji style", "iPhone-style emoji in the keyboard emoji panel", "apple_emoji", true))

        val label = tv("Keyboard height  " + Prefs.getI(this, "height", 100) + "%", 15f, Color.WHITE)
        val lp = LinearLayout.LayoutParams(MATCH, WRAP)
        lp.topMargin = dp(8)
        c.addView(label, lp)
        val sb = SeekBar(this)
        sb.max = 50
        sb.progress = Prefs.getI(this, "height", 100) - 80
        sb.progressTintList = ColorStateList.valueOf(Pal.PURPLE)
        sb.thumbTintList = ColorStateList.valueOf(Pal.CYAN)
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                val v = 80 + p
                label.text = "Keyboard height  $v%"
                if (fromUser) {
                    Prefs.setI(this@MainActivity, "height", v)
                    kv.reload()
                }
            }

            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        c.addView(sb, LinearLayout.LayoutParams(MATCH, WRAP))
        return c
    }

    private fun clipSettingsCard(): View {
        val c = card("Clipboard settings", "Control what Zeta Board remembers")
        c.addView(switchRow("Clipboard history", "Save copied text on this phone only", "clip_on", true))
        val maxVals = intArrayOf(30, 60, 100, 200)
        val maxIdx = maxVals.indexOf(Prefs.getI(this, "clip_max", 100)).coerceAtLeast(0)
        c.addView(tv("History size", 15f, Color.WHITE))
        val seg = segment(listOf("30", "60", "100", "200"), maxIdx) { Prefs.setI(this, "clip_max", maxVals[it]) }
        val slp = LinearLayout.LayoutParams(MATCH, WRAP)
        slp.topMargin = dp(8)
        c.addView(seg, slp)
        val wipe = pill("Delete all clipboard data") {
            if (!clearArmed) {
                clearArmed = true
                Toast.makeText(this, "Tap again to delete everything, including pinned items", Toast.LENGTH_SHORT).show()
                col.postDelayed({ clearArmed = false }, 3000L)
            } else {
                clearArmed = false
                ClipRepo.clearAll()
                Toast.makeText(this, "Clipboard data deleted", Toast.LENGTH_SHORT).show()
            }
        }
        wipe.background = roundBg(Pal.BG, 30, Pal.STROKE, 1)
        val wlp = LinearLayout.LayoutParams(MATCH, WRAP)
        wlp.topMargin = dp(14)
        c.addView(wipe, wlp)

        return c
    }
}
