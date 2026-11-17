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
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Clipboard Manager screen: every saved clip with search, copy, pin, edit, delete,
 * manual add, clear recent and delete all. All data stays on this phone.
 */
class ClipManagerActivity : Activity() {

    private val vm = ClipViewModel()
    private lateinit var list: LinearLayout
    private lateinit var stats: TextView
    private lateinit var search: EditText
    private val expanded = HashSet<Long>()

    private val dens: Float by lazy { resources.displayMetrics.density }
    private fun dp(v: Int): Int = (v * dens).toInt()
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ClipRepo.init(this)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Pal.BG)
        root.setPadding(dp(14), dp(10), dp(14), 0)
        root.addView(topBar(), LinearLayout.LayoutParams(MATCH, WRAP))
        root.addView(searchBox())
        root.addView(actionRow())

        val hint = tv("Tap a clip to copy it. Use the icons to pin, edit or delete.", 12f, Pal.MUTED, false)
        hint.setPadding(dp(4), dp(10), dp(4), dp(2))
        root.addView(hint, LinearLayout.LayoutParams(MATCH, WRAP))

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
        vm.onChanged = { render() }
        vm.start()
    }

    override fun onStop() {
        vm.stop()
        vm.onChanged = null
        super.onStop()
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
        col.addView(tv("Clipboard Manager", 20f, Color.WHITE, true))
        stats = tv("", 12f, Pal.MUTED, false)
        col.addView(stats)
        row.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))

        row.addView(LogoBadge(this), LinearLayout.LayoutParams(dp(42), dp(42)))
        return row
    }

    private fun searchBox(): View {
        search = EditText(this)
        search.hint = "Search clips"
        search.setHintTextColor(Pal.MUTED)
        search.setTextColor(Color.WHITE)
        search.textSize = 15f
        search.inputType = InputType.TYPE_CLASS_TEXT
        search.setPadding(dp(16), dp(11), dp(16), dp(11))
        search.background = shape(Pal.CARD, 16, Pal.STROKE, 1)
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                vm.search(s?.toString() ?: "")
            }
        })
        val lp = LinearLayout.LayoutParams(MATCH, WRAP)
        lp.topMargin = dp(12)
        search.layoutParams = lp
        return search
    }

    private fun actionRow(): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        val add = pill("+ Add clip", true) {
            openEditor("Add clip", "") { t -> ClipRepo.add(t, Prefs.getI(this, "clip_max", 100)) }
        }
        val clear = pill("Clear recent", false) {
            confirm("Clear recent clips?", "All unpinned clips will be deleted. Pinned clips stay safe.", "Clear") {
                vm.clearRecent()
                toast("Recent clips cleared")
            }
        }
        val wipe = pill("Delete all", false) {
            confirm("Delete everything?", "This removes ALL clips, including pinned ones. It cannot be undone.", "Delete all") {
                ClipRepo.clearAll()
                toast("All clips deleted")
            }
        }
        wipe.setTextColor(Color.parseColor("#F87171"))
        val l1 = LinearLayout.LayoutParams(0, WRAP, 1f)
        l1.rightMargin = dp(8)
        val l2 = LinearLayout.LayoutParams(0, WRAP, 1f)
        l2.rightMargin = dp(8)
        row.addView(add, l1)
        row.addView(clear, l2)
        row.addView(wipe, LinearLayout.LayoutParams(0, WRAP, 1f))
        val lp = LinearLayout.LayoutParams(MATCH, WRAP)
        lp.topMargin = dp(12)
        row.layoutParams = lp
        return row
    }

    // ---------- list ----------

    private fun render() {
        if (isFinishing || isDestroyed) return
        list.removeAllViews()
        if (!vm.loaded) return
        val pinned = vm.pinned
        val recent = vm.recent
        val total = pinned.size + recent.size
        if (vm.query.isNotEmpty()) {
            stats.text = total.toString() + " matches"
        } else {
            stats.text = total.toString() + " clips  \u2022  " + pinned.size + " pinned"
        }
        if (total == 0) {
            list.addView(emptyView(), LinearLayout.LayoutParams(MATCH, MATCH))
            return
        }
        if (pinned.isNotEmpty()) {
            list.addView(sectionLabel("PINNED  \u00B7  " + pinned.size, true))
            for (item in pinned) list.addView(card(item))
        }
        if (recent.isNotEmpty()) {
            list.addView(sectionLabel("RECENT CLIPS  \u00B7  " + recent.size, false))
            for (item in recent) list.addView(card(item))
        }
    }

    private fun sectionLabel(text: String, pinned: Boolean): View {
        val t = tv(text, 11f, if (pinned) Pal.PURPLE else Pal.MUTED, true)
        t.letterSpacing = 0.12f
        t.setPadding(dp(4), dp(14), dp(4), dp(2))
        return t
    }

    private fun emptyView(): View {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.gravity = Gravity.CENTER
        box.setPadding(dp(24), dp(12), dp(24), dp(48))
        val searching = vm.query.isNotEmpty()
        val t1 = tv(if (searching) "No matches" else "Clipboard is empty", 17f, Color.WHITE, true)
        t1.gravity = Gravity.CENTER
        val emptyMsg = if (searching) {
            "Try a different search."
        } else {
            "Copy any text while Zeta Board is your keyboard and it will appear here.\nYou can also tap \"+ Add clip\"."
        }
        val t2 = tv(emptyMsg, 13f, Pal.MUTED, false)
        t2.gravity = Gravity.CENTER
        t2.setPadding(0, dp(8), 0, 0)
        box.addView(t1, LinearLayout.LayoutParams(MATCH, WRAP))
        box.addView(t2, LinearLayout.LayoutParams(MATCH, WRAP))
        return box
    }

    private fun card(item: ClipItem): View {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.setPadding(dp(14), dp(12), dp(8), dp(6))
        val bg = GradientDrawable()
        bg.cornerRadius = dp(18).toFloat()
        bg.setColor(if (item.pinned) mix(Pal.CARD, Pal.PURPLE, 0.14f) else Pal.CARD)
        bg.setStroke(dp(1), if (item.pinned) withAlpha(Pal.PURPLE, 170) else Pal.STROKE)
        c.background = RippleDrawable(ColorStateList.valueOf(withAlpha(Pal.PURPLE, 70)), bg, null)
        c.isClickable = true
        c.setOnClickListener { copyToSystem(item.text) }
        val lp = LinearLayout.LayoutParams(MATCH, WRAP)
        lp.setMargins(0, dp(5), 0, dp(5))
        c.layoutParams = lp

        val meta = LinearLayout(this)
        meta.orientation = LinearLayout.HORIZONTAL
        meta.gravity = Gravity.CENTER_VERTICAL
        if (item.pinned) {
            val pi = ImageView(this)
            pi.setImageResource(R.drawable.ic_pin)
            pi.setColorFilter(Pal.PURPLE)
            val plp = LinearLayout.LayoutParams(dp(13), dp(13))
            plp.rightMargin = dp(5)
            meta.addView(pi, plp)
        }
        meta.addView(tv(timeAgo(item.ts) + "  \u2022  " + item.text.length + " chars", 11f, Pal.MUTED, false))
        c.addView(meta)

        val open = expanded.contains(item.id)
        val isLong = item.text.length > 140 || item.text.count { ch -> ch == '\n' } >= 4
        // very long clips are cut for display only; copying always uses the full text
        val shown = if (open || item.text.length <= 600) item.text else item.text.take(600) + "\u2026"
        val tx = tv(shown, 14.5f, Color.WHITE, false)
        tx.setLineSpacing(0f, 1.1f)
        tx.setPadding(0, dp(6), dp(6), 0)
        if (!open) {
            tx.maxLines = 5
            tx.ellipsize = TextUtils.TruncateAt.END
        }
        c.addView(tx)

        val actions = LinearLayout(this)
        actions.orientation = LinearLayout.HORIZONTAL
        actions.gravity = Gravity.CENTER_VERTICAL
        if (isLong) {
            val more = tv(if (open) "Show less" else "Show more", 12f, Pal.CYAN, true)
            more.setPadding(0, dp(8), dp(10), dp(8))
            more.setOnClickListener {
                if (open) expanded.remove(item.id) else expanded.add(item.id)
                render()
            }
            actions.addView(more, LinearLayout.LayoutParams(0, WRAP, 1f))
        } else {
            actions.addView(View(this), LinearLayout.LayoutParams(0, dp(1), 1f))
        }
        actions.addView(iconBtn(R.drawable.ic_copy, Pal.MUTED) { copyToSystem(item.text) }, LinearLayout.LayoutParams(dp(40), dp(40)))
        actions.addView(
            iconBtn(R.drawable.ic_pin, if (item.pinned) Pal.PURPLE else Pal.MUTED) {
                vm.togglePin(item)
                toast(if (item.pinned) "Unpinned" else "Pinned")
            },
            LinearLayout.LayoutParams(dp(40), dp(40))
        )
        actions.addView(
            iconBtn(R.drawable.ic_edit, Pal.MUTED) {
                openEditor("Edit clip", item.text) { t -> vm.edit(item, t) }
            },
            LinearLayout.LayoutParams(dp(40), dp(40))
        )
        actions.addView(iconBtn(R.drawable.ic_delete, Pal.MUTED) { deleteItem(item) }, LinearLayout.LayoutParams(dp(40), dp(40)))
        c.addView(actions)
        return c
    }

    // ---------- actions ----------

    private fun copyToSystem(text: String) {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Zeta Board", text))
            ClipRepo.add(text, Prefs.getI(this, "clip_max", 100))
            toast("Copied")
        } catch (e: Exception) {
            toast("Could not copy")
        }
    }

    private fun deleteItem(item: ClipItem) {
        expanded.remove(item.id)
        if (item.pinned) {
            confirm("Delete pinned clip?", "This clip is pinned. Delete it anyway?", "Delete") {
                vm.delete(item)
                toast("Deleted")
            }
        } else {
            vm.delete(item)
            toast("Deleted")
        }
    }

    private fun openEditor(title: String, initial: String, onSave: (String) -> Unit) {
        val input = EditText(this)
        input.filters = arrayOf<InputFilter>(InputFilter.LengthFilter(ClipRepo.MAX_TEXT))
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        input.setText(initial)
        input.setSelection(input.text.length)
        input.minLines = 3
        input.maxLines = 8
        input.gravity = Gravity.TOP or Gravity.START
        input.hint = "Type or paste text"
        val box = FrameLayout(this)
        box.setPadding(dp(20), dp(8), dp(20), 0)
        box.addView(input, FrameLayout.LayoutParams(MATCH, WRAP))
        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle(title)
            .setView(box)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val t = input.text.toString()
                if (t.isBlank()) {
                    toast("Text can't be empty")
                } else {
                    onSave(t)
                }
            }
            .show()
    }

    private fun confirm(title: String, msg: String, ok: String, onOk: () -> Unit) {
        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle(title)
            .setMessage(msg)
            .setNegativeButton("Cancel", null)
            .setPositiveButton(ok) { _, _ -> onOk() }
            .show()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    // ---------- helpers ----------

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
