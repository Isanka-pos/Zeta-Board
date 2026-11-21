package com.zeta.board

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/**
 * Quick Text Manager: create, edit, delete, search, copy and enable/disable shortcuts.
 * Everything is stored on this phone only.
 */
class QuickTextActivity : Activity() {

    private lateinit var list: LinearLayout
    private lateinit var stats: TextView
    private lateinit var search: EditText
    private var req = 0
    private var query = ""
    private var data = QuickList(emptyList(), 0, 0)
    private var loaded = false

    private val dens: Float by lazy { resources.displayMetrics.density }
    private fun dp(v: Int): Int = (v * dens).toInt()
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private val obs: () -> Unit = { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        QuickRepo.init(this)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Pal.BG)
        root.setPadding(dp(14), dp(10), dp(14), 0)
        root.addView(topBar(), LinearLayout.LayoutParams(MATCH, WRAP))
        root.addView(searchBox())
        root.addView(actionRow())
        root.addView(autoRow())

        val sv = ScrollView(this)
        sv.isVerticalScrollBarEnabled = false
        sv.isFillViewport = true
        list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        list.setPadding(0, dp(4), 0, dp(24))
        sv.addView(list, ViewGroup.LayoutParams(MATCH, MATCH))
        root.addView(sv, LinearLayout.LayoutParams(MATCH, 0, 1f))
        setContentView(root)
    }

    override fun onStart() {
        super.onStart()
        QuickRepo.observe(obs)
        refresh()
    }

    override fun onStop() {
        QuickRepo.unobserve(obs)
        super.onStop()
    }

    private fun refresh() {
        val my = ++req
        QuickRepo.load(query, false, 2000) { r ->
            if (my == req && !isFinishing && !isDestroyed) {
                data = r
                loaded = true
                render()
            }
        }
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
        col.addView(tv("Quick Text", 20f, Color.WHITE, true))
        stats = tv("", 12f, Pal.MUTED, false)
        col.addView(stats)
        row.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
        row.addView(LogoBadge(this), LinearLayout.LayoutParams(dp(42), dp(42)))
        return row
    }

    private fun searchBox(): View {
        search = EditText(this)
        search.hint = "Search shortcuts or messages"
        search.setHintTextColor(Pal.MUTED)
        search.setTextColor(Color.WHITE)
        search.textSize = 15f
        search.inputType = InputType.TYPE_CLASS_TEXT
        search.setSingleLine(true)
        search.setPadding(dp(16), dp(11), dp(16), dp(11))
        search.background = shape(Pal.CARD, 16, Pal.STROKE, 1)
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString() ?: ""
                refresh()
            }
        })
        val lp = LinearLayout.LayoutParams(MATCH, WRAP)
        lp.topMargin = dp(12)
        search.layoutParams = lp
        return search
    }

    private fun actionRow(): View {
        val add = pill("+ New shortcut", true) { openEditor(null) }
        val lp = LinearLayout.LayoutParams(MATCH, WRAP)
        lp.topMargin = dp(12)
        add.layoutParams = lp
        return add
    }

    private fun autoRow(): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(4), dp(10), dp(4), dp(4))
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.addView(tv("Auto-expand while typing", 14f, Color.WHITE, false))
        c.addView(tv("Type a shortcut, then space, to swap it for its message. Off in password fields.", 11.5f, Pal.MUTED, false))
        row.addView(c, LinearLayout.LayoutParams(0, WRAP, 1f))
        row.addView(switchView(Prefs.getB(this, "quick_auto", true)) { Prefs.setB(this, "quick_auto", it) })
        return row
    }

    private fun switchView(on: Boolean, onChange: (Boolean) -> Unit): Switch {
        val sw = Switch(this)
        sw.isChecked = on
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        sw.trackTintList = ColorStateList(states, intArrayOf(Pal.PURPLE, Color.argb(60, 255, 255, 255)))
        sw.thumbTintList = ColorStateList.valueOf(Color.WHITE)
        sw.setOnCheckedChangeListener { _, v -> onChange(v) }
        return sw
    }

    // ---------- list ----------

    private fun render() {
        if (isFinishing || isDestroyed) return
        list.removeAllViews()
        if (!loaded) return
        stats.text = if (data.total == 0) "No shortcuts yet" else data.total.toString() + " saved  \u2022  " + data.enabled + " on"
        if (data.items.isEmpty()) {
            list.addView(emptyView(), LinearLayout.LayoutParams(MATCH, MATCH))
            return
        }
        for (item in data.items) list.addView(card(item))
    }

    private fun emptyView(): View {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.gravity = Gravity.CENTER
        box.setPadding(dp(24), dp(24), dp(24), dp(48))
        val searching = query.trim().isNotEmpty()
        val ic = ImageView(this)
        ic.setImageResource(R.drawable.ic_quick)
        ic.setColorFilter(Pal.CYAN)
        ic.setPadding(dp(16), dp(16), dp(16), dp(16))
        val cbg = GradientDrawable()
        cbg.shape = GradientDrawable.OVAL
        cbg.setColor(Color.argb(40, 34, 211, 238))
        ic.background = cbg
        box.addView(ic, LinearLayout.LayoutParams(dp(64), dp(64)))
        val t1 = tv(if (searching) "No matches" else "No shortcuts yet", 17f, Color.WHITE, true)
        t1.gravity = Gravity.CENTER
        t1.setPadding(0, dp(12), 0, 0)
        val t2 = tv(
            if (searching) "Try a different search." else "Save a message once, like .welcome, then insert it anywhere from the Quick Text button on the keyboard.\nTap \"+ New shortcut\" to start.",
            13f, Pal.MUTED, false
        )
        t2.gravity = Gravity.CENTER
        t2.setPadding(0, dp(8), 0, 0)
        box.addView(t1, LinearLayout.LayoutParams(MATCH, WRAP))
        box.addView(t2, LinearLayout.LayoutParams(MATCH, WRAP))
        return box
    }

    private fun card(item: QuickItem): View {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.setPadding(dp(14), dp(12), dp(8), dp(6))
        val bg = GradientDrawable()
        bg.cornerRadius = dp(18).toFloat()
        bg.setColor(Pal.CARD)
        bg.setStroke(dp(1), if (item.enabled) Pal.STROKE else Color.argb(60, 255, 255, 255))
        c.background = RippleDrawable(ColorStateList.valueOf(Color.argb(70, 168, 85, 247)), bg, null)
        c.isClickable = true
        c.setOnClickListener { openEditor(item) }
        val lp = LinearLayout.LayoutParams(MATCH, WRAP)
        lp.setMargins(0, dp(5), 0, dp(5))
        c.layoutParams = lp

        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        val chip = tv(item.sc, 13f, if (item.enabled) Pal.CYAN else Pal.MUTED, true)
        chip.maxLines = 1
        chip.setPadding(dp(10), dp(3), dp(10), dp(3))
        chip.background = shape(if (item.enabled) Color.argb(40, 34, 211, 238) else Color.argb(30, 255, 255, 255), 20, 0, 0)
        head.addView(chip, LinearLayout.LayoutParams(WRAP, WRAP))
        val spacer = View(this)
        head.addView(spacer, LinearLayout.LayoutParams(0, dp(1), 1f))
        head.addView(switchView(item.enabled) { on ->
            QuickRepo.setEnabled(item.id, on)
            toast(if (on) "Turned on" else "Turned off")
        })
        c.addView(head)

        if (item.desc.isNotEmpty()) {
            val ds = tv(item.desc, 14.5f, Color.WHITE, true)
            ds.maxLines = 1
            ds.ellipsize = TextUtils.TruncateAt.END
            ds.setPadding(0, dp(6), dp(6), 0)
            c.addView(ds)
        }
        val shown = if (item.msg.length > 300) item.msg.take(300) + "\u2026" else item.msg
        val tx = tv(shown, 13.5f, if (item.enabled) Color.WHITE else Pal.MUTED, false)
        tx.alpha = if (item.enabled) 0.85f else 0.7f
        tx.maxLines = 3
        tx.ellipsize = TextUtils.TruncateAt.END
        tx.setLineSpacing(0f, 1.1f)
        tx.setPadding(0, dp(4), dp(6), 0)
        c.addView(tx)

        val actions = LinearLayout(this)
        actions.orientation = LinearLayout.HORIZONTAL
        actions.gravity = Gravity.CENTER_VERTICAL or Gravity.END
        actions.addView(iconBtn(R.drawable.ic_copy, Pal.MUTED) { copyToSystem(item.msg) }, LinearLayout.LayoutParams(dp(40), dp(40)))
        actions.addView(iconBtn(R.drawable.ic_edit, Pal.MUTED) { openEditor(item) }, LinearLayout.LayoutParams(dp(40), dp(40)))
        actions.addView(iconBtn(R.drawable.ic_delete, Pal.MUTED) { askDelete(item) }, LinearLayout.LayoutParams(dp(40), dp(40)))
        c.addView(actions, LinearLayout.LayoutParams(MATCH, WRAP))
        return c
    }

    // ---------- actions ----------

    private fun copyToSystem(text: String) {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Zeta Board", text))
            toast("Copied")
        } catch (e: Exception) {
            toast("Could not copy")
        }
    }

    private fun askDelete(item: QuickItem) {
        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle("Delete " + item.sc + "?")
            .setMessage("This shortcut and its message will be removed. This cannot be undone.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                QuickRepo.delete(item.id)
                toast("Deleted")
            }
            .create().also { ScreenGuard.secure(it.window) }.show()
    }

    /** Add (item == null) or edit. The dialog stays open until the input is valid. */
    private fun openEditor(item: QuickItem?) {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(20), dp(8), dp(20), 0)

        fun field(hint: String, value: String, multi: Boolean, max: Int): EditText {
            val e = EditText(this)
            e.hint = hint
            e.filters = arrayOf<InputFilter>(InputFilter.LengthFilter(max))
            e.inputType = if (multi) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL
            e.setText(value)
            if (multi) {
                e.minLines = 4
                e.maxLines = 8
                e.gravity = Gravity.TOP or Gravity.START
            } else {
                e.setSingleLine(true)
            }
            return e
        }

        val scIn = field("Shortcut, e.g. .welcome", item?.sc ?: ".", false, QuickText.MAX_SC)
        val descIn = field("Description, e.g. Welcome Message", item?.desc ?: "", false, QuickText.MAX_DESC)
        val msgIn = field("Message", item?.msg ?: "", true, QuickText.MAX_MSG)
        scIn.setSelection(scIn.text.length)
        val err = TextView(this)
        err.setTextColor(Color.parseColor("#F87171"))
        err.textSize = 12.5f
        err.visibility = View.GONE
        err.setPadding(0, dp(8), 0, 0)
        box.addView(scIn)
        box.addView(descIn)
        box.addView(msgIn)
        box.addView(err)
        val scroll = ScrollView(this)
        scroll.addView(box)

        val dialog = AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle(if (item == null) "New shortcut" else "Edit shortcut")
            .setView(scroll)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save", null)
            .create()
        ScreenGuard.secure(dialog.window)
        dialog.show()
        val save = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        save.setOnClickListener {
            save.isEnabled = false
            QuickRepo.save(item?.id ?: 0L, scIn.text.toString(), descIn.text.toString(), msgIn.text.toString()) { e ->
                if (!isFinishing && !isDestroyed) {
                    if (e == null) {
                        dialog.dismiss()
                        toast("Saved")
                    } else {
                        save.isEnabled = true
                        err.text = e
                        err.visibility = View.VISIBLE
                    }
                }
            }
        }
    }

    // ---------- helpers ----------

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

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

    private fun pill(text: String, primary: Boolean, onClick: () -> Unit): TextView {
        val t = tv(text, 13f, Color.WHITE, true)
        t.gravity = Gravity.CENTER
        t.setPadding(dp(8), dp(11), dp(8), dp(11))
        if (primary) {
            val g = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Pal.CYAN, Pal.PURPLE))
            g.cornerRadius = dp(30).toFloat()
            t.background = g
        } else {
            t.background = shape(Pal.CARD, 30, Pal.STROKE, 1)
        }
        t.setOnClickListener { onClick() }
        return t
    }

    private fun iconBtn(res: Int, tint: Int, onClick: () -> Unit): ImageView {
        val v = ImageView(this)
        v.setImageResource(res)
        v.setColorFilter(tint)
        v.setPadding(dp(10), dp(10), dp(10), dp(10))
        v.setOnClickListener { onClick() }
        return v
    }
}
