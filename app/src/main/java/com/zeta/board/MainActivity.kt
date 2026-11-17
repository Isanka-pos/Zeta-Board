package com.zeta.board

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
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
    private lateinit var et: EditText
    private lateinit var col: LinearLayout
    private val steps = ArrayList<StepRow>()
    private val chips = ArrayList<ThemeChip>()

    private val dens: Float by lazy { resources.displayMetrics.density }
    private fun dp(v: Int): Int = (v * dens).toInt()
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this)
        root.setBackgroundColor(Pal.BG)
        val home = buildHome()
        root.addView(home, FrameLayout.LayoutParams(MATCH, MATCH))
        val splash = SplashView(this)
        root.addView(splash, FrameLayout.LayoutParams(MATCH, MATCH))
        setContentView(root)

        for (i in 0 until col.childCount) {
            val v = col.getChildAt(i)
            v.alpha = 0f
            v.translationY = dp(30).toFloat()
        }
        splash.onDone = {
            checkUpdate(false)
            splash.animate().alpha(0f).setDuration(400).withEndAction { root.removeView(splash) }.start()
            for (i in 0 until col.childCount) {
                col.getChildAt(i).animate()
                    .alpha(1f).translationY(0f)
                    .setStartDelay(150L + i * 90L).setDuration(450)
                    .setInterpolator(DecelerateInterpolator()).start()
            }
        }
    }

    override fun onResume() {
        super.onResume()
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

    private fun checkUpdate(manual: Boolean) {
        Updater.check(this) { info, failed ->
            if (isFinishing) return@check
            if (info != null) UpdateDialog.show(this, info)
            else if (manual) Toast.makeText(this, if (failed) "Could not check for updates" else "You are on the latest version", Toast.LENGTH_SHORT).show()
        }
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
        col.addView(header())
        col.addView(setupCard())
        val themes = themeCard()
        val preview = previewCard()
        val settings = settingsCard()
        col.addView(themes)
        col.addView(preview)
        col.addView(settings)
        val foot = tv("Zeta Board v1.0  \u2022  Made by Isanka Malith", 12f, Pal.MUTED)
        foot.gravity = Gravity.CENTER
        foot.setPadding(0, dp(24), 0, 0)
        col.addView(foot, LinearLayout.LayoutParams(MATCH, WRAP))
        val sv = ScrollView(this)
        sv.setBackgroundColor(Pal.BG)
        sv.isVerticalScrollBarEnabled = false
        sv.addView(col)
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
        c.addView(tv("Premium keyboard", 13f, Pal.MUTED))
        row.addView(c)
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

        kv = KeyboardView(this)
        kv.theme = Themes.get(Prefs.getI(this, "theme", 0))
        val radius = dp(18).toFloat()
        kv.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }
        kv.clipToOutline = true
        kv.listener = object : KeyboardView.Listener {
            override fun onText(s: String) {
                val pos = et.selectionStart.coerceAtLeast(0)
                et.text.insert(pos, s)
            }

            override fun onAction(a: Int) {
                val t = et.text
                val pos = et.selectionStart.coerceAtLeast(0)
                when (a) {
                    KeyboardView.DEL -> if (pos > 0) {
                        val n = if (pos >= 2 && Character.isLowSurrogate(t[pos - 1]) && Character.isHighSurrogate(t[pos - 2])) 2 else 1
                        t.delete(pos - n, pos)
                    }
                    KeyboardView.ENTER -> t.insert(pos, "\n")
                    KeyboardView.LEFT -> if (pos > 0) et.setSelection(pos - 1)
                    KeyboardView.RIGHT -> if (pos < t.length) et.setSelection(pos + 1)
                }
            }
        }
        val lp2 = LinearLayout.LayoutParams(MATCH, WRAP)
        lp2.topMargin = dp(12)
        c.addView(kv, lp2)
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

    private fun settingsCard(): View {
        val c = card("Typing", "Make it feel right")
        c.addView(switchRow("Vibration", "Haptic tap on every key", "haptic", true))
        c.addView(switchRow("Key sound", "Click sound while typing", "sound", false))
        c.addView(switchRow("Key preview", "Pop-up bubble above pressed key", "preview", true))
        c.addView(switchRow("Number row", "Always show 1-0 on top", "numrow", true))

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

        val vrow = LinearLayout(this)
        vrow.orientation = LinearLayout.HORIZONTAL
        vrow.gravity = Gravity.CENTER_VERTICAL
        vrow.setPadding(0, dp(18), 0, 0)
        val vcol = LinearLayout(this)
        vcol.orientation = LinearLayout.VERTICAL
        vcol.addView(tv("Updates", 15f, Color.WHITE))
        vcol.addView(tv("Version " + Updater.currentName(this), 12f, Pal.MUTED))
        vrow.addView(vcol, LinearLayout.LayoutParams(0, WRAP, 1f))
        vrow.addView(pill("Check") { checkUpdate(true) })
        c.addView(vrow)
        return c
    }
}
