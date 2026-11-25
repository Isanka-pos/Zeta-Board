package com.zeta.board

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Random

/**
 * Style Name Generator: type a name and get many stylish versions (fancy letters, decorations, random mixes).
 * Tap a result to copy it, or use Share. Nothing is stored or sent anywhere.
 */
class StyleNameActivity : Activity() {

    private lateinit var input: EditText
    private lateinit var list: LinearLayout
    private lateinit var hint: TextView
    private var tab = 0
    private var seed = System.nanoTime()
    private val tabViews = ArrayList<TextView>()
    private val probe = Paint()

    private val dens: Float by lazy { resources.displayMetrics.density }
    private fun dp(v: Int): Int = (v * dens).toInt()
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Pal.BG)
        root.setPadding(dp(14), dp(10), dp(14), 0)
        root.addView(topBar(), LinearLayout.LayoutParams(MATCH, WRAP))
        root.addView(inputBox())
        root.addView(tabBar())

        hint = tv("", 12f, Pal.MUTED, false)
        hint.setPadding(dp(4), dp(10), dp(4), dp(4))
        root.addView(hint, LinearLayout.LayoutParams(MATCH, WRAP))

        list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        list.setPadding(0, dp(4), 0, dp(30))
        val sv = ScrollView(this)
        sv.isVerticalScrollBarEnabled = false
        sv.addView(list, ViewGroup.LayoutParams(MATCH, WRAP))
        root.addView(sv, LinearLayout.LayoutParams(MATCH, 0, 1f))
        setContentView(root)
        refresh()
    }

    // ---------------------------------------------------------------- UI

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
        col.addView(tv("Style Name Generator", 20f, Color.WHITE, true))
        col.addView(tv("Type a name, tap a style to copy it", 12f, Pal.MUTED, false))
        row.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
        row.addView(LogoBadge(this), LinearLayout.LayoutParams(dp(42), dp(42)))
        return row
    }

    private fun inputBox(): View {
        input = EditText(this)
        input.hint = "Your name, e.g. Zeta"
        input.setHintTextColor(Pal.MUTED)
        input.setTextColor(Color.WHITE)
        input.textSize = 17f
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        input.setSingleLine(true)
        input.setText("Zeta")
        input.setSelection(input.text.length)
        input.setPadding(dp(16), dp(13), dp(16), dp(13))
        input.background = shape(Pal.CARD, 16, Pal.CYAN, 1)
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                refresh()
            }
        })
        val lp = LinearLayout.LayoutParams(MATCH, WRAP)
        lp.topMargin = dp(12)
        input.layoutParams = lp
        return input
    }

    private fun tabBar(): View {
        val hs = HorizontalScrollView(this)
        hs.isHorizontalScrollBarEnabled = false
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        val labels = listOf("Fancy letters", "Decorated", "Random mix")
        labels.forEachIndexed { i, s ->
            val t = tv(s, 13f, Pal.MUTED, true)
            t.gravity = Gravity.CENTER
            t.setPadding(dp(16), dp(10), dp(16), dp(10))
            val lp = LinearLayout.LayoutParams(WRAP, WRAP)
            lp.rightMargin = dp(8)
            row.addView(t, lp)
            tabViews.add(t)
            t.setOnClickListener {
                tab = i
                seed = System.nanoTime()
                refresh()
            }
        }
        hs.addView(row)
        val lp = LinearLayout.LayoutParams(MATCH, WRAP)
        lp.topMargin = dp(12)
        hs.layoutParams = lp
        return hs
    }

    private fun paintTabs() {
        tabViews.forEachIndexed { i, v ->
            if (i == tab) {
                val g = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Pal.CYAN, Pal.PURPLE))
                g.cornerRadius = dp(30).toFloat()
                v.background = g
                v.setTextColor(Color.WHITE)
            } else {
                v.background = shape(Pal.CARD, 30, Pal.STROKE, 1)
                v.setTextColor(Pal.MUTED)
            }
        }
    }

    private fun refresh() {
        paintTabs()
        list.removeAllViews()
        val name = input.text.toString().trim()
        if (name.isEmpty()) {
            hint.text = "Type a name above to see the styles."
            return
        }
        val rows: List<Pair<String, String>> = when (tab) {
            0 -> StyleNames.fancy(name).filter { usable(it.second) }
            1 -> StyleNames.decorated(name).filter { usable(it.second) }
            else -> StyleNames.mix(name, java.util.Random(seed)).filter { usable(it.second) }
        }
        hint.text = if (tab == 2) "Tap Shuffle for new combinations. Tap any style to copy." else "Tap a style to copy it."
        if (tab == 2) {
            val sh = tv("Shuffle", 14f, Color.WHITE, true)
            sh.gravity = Gravity.CENTER
            sh.setPadding(dp(20), dp(12), dp(20), dp(12))
            val g = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Pal.CYAN, Pal.PURPLE))
            g.cornerRadius = dp(30).toFloat()
            sh.background = g
            sh.setOnClickListener {
                seed = System.nanoTime()
                refresh()
            }
            val lp = LinearLayout.LayoutParams(MATCH, WRAP)
            lp.bottomMargin = dp(8)
            list.addView(sh, lp)
        }
        for ((label, text) in rows) list.addView(row(label, text))
    }

    /** Hides a result when the phone cannot draw its letters (it would show as empty boxes). */
    private fun usable(s: String): Boolean {
        val plain = s.filter { !it.isWhitespace() }
        if (plain.isEmpty()) return false
        var i = 0
        while (i < plain.length) {
            val cp = plain.codePointAt(i)
            i += Character.charCount(cp)
            if (cp < 0x80 || cp in 0x300..0x36F) continue
            if (!probe.hasGlyph(String(Character.toChars(cp)))) return false
        }
        return true
    }

    private fun row(label: String, text: String): View {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.HORIZONTAL
        c.gravity = Gravity.CENTER_VERTICAL
        c.setPadding(dp(16), dp(12), dp(10), dp(12))
        c.background = shape(Pal.CARD, 18, Pal.STROKE, 1)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.addView(tv(label, 11f, Pal.MUTED, false))
        val t = tv(text, 19f, Color.WHITE, false)
        t.setPadding(0, dp(3), 0, 0)
        col.addView(t)
        c.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))

        val share = tv("Share", 12f, Pal.CYAN, true)
        share.setPadding(dp(12), dp(10), dp(12), dp(10))
        share.setOnClickListener {
            val i = Intent(Intent.ACTION_SEND)
            i.type = "text/plain"
            i.putExtra(Intent.EXTRA_TEXT, text)
            try {
                startActivity(Intent.createChooser(i, "Share name"))
            } catch (e: Exception) {
            }
        }
        c.addView(share, LinearLayout.LayoutParams(WRAP, WRAP))

        val copy = tv("Copy", 12f, Color.WHITE, true)
        copy.setPadding(dp(14), dp(10), dp(14), dp(10))
        val g = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Pal.CYAN, Pal.PURPLE))
        g.cornerRadius = dp(20).toFloat()
        copy.background = g
        copy.setOnClickListener { copyText(text) }
        c.addView(copy, LinearLayout.LayoutParams(WRAP, WRAP))
        c.setOnClickListener { copyText(text) }

        val lp = LinearLayout.LayoutParams(MATCH, WRAP)
        lp.topMargin = dp(8)
        c.layoutParams = lp
        return c
    }

    private fun copyText(text: String) {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Zeta Board name", text))
            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Could not copy", Toast.LENGTH_SHORT).show()
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
}

/** The text tricks behind the generator. Letters A-Z are converted; every other character (digits, Sinhala, spaces) is kept. */
object StyleNames {

    private class Font(val label: String, val map: (Char) -> String)

    private fun cp(base: Int, i: Int): String = String(Character.toChars(base + i))

    private fun alpha(upBase: Int, loBase: Int, holes: Map<Char, String> = emptyMap()): (Char) -> String = { ch ->
        when {
            holes.containsKey(ch) -> holes.getValue(ch)
            ch in 'A'..'Z' -> cp(upBase, ch - 'A')
            ch in 'a'..'z' -> cp(loBase, ch - 'a')
            else -> ch.toString()
        }
    }

    private const val SMALL_CAPS = "ᴀʙᴄᴅᴇꜰɢʜɪᴊᴋʟᴍɴᴏᴘǫʀsᴛᴜᴠᴡxʏᴢ"
    private const val SUPER_LO = "ᵃᵇᶜᵈᵉᶠᵍʰⁱʲᵏˡᵐⁿᵒᵖʠʳˢᵗᵘᵛʷˣʸᶻ"
    private const val FLIP_LO = "ɐqɔpǝɟƃɥᴉɾʞןɯuodbɹsʇnʌʍxʎz"

    private val doubleHoles = mapOf(
        'C' to "ℂ", 'H' to "ℍ", 'N' to "ℕ", 'P' to "ℙ",
        'Q' to "ℚ", 'R' to "ℝ", 'Z' to "ℤ"
    )
    private val italicHoles = mapOf('h' to "ℎ")

    private val fonts: List<Font> = listOf(
        Font("Bold", alpha(0x1D400, 0x1D41A)),
        Font("Italic", alpha(0x1D434, 0x1D44E, italicHoles)),
        Font("Bold Italic", alpha(0x1D468, 0x1D482)),
        Font("Script", alpha(0x1D4D0, 0x1D4EA)),
        Font("Gothic", alpha(0x1D56C, 0x1D586)),
        Font("Double Struck", alpha(0x1D538, 0x1D552, doubleHoles)),
        Font("Sans", alpha(0x1D5A0, 0x1D5BA)),
        Font("Sans Bold", alpha(0x1D5D4, 0x1D5EE)),
        Font("Sans Italic", alpha(0x1D608, 0x1D622)),
        Font("Sans Bold Italic", alpha(0x1D63C, 0x1D656)),
        Font("Monospace", alpha(0x1D670, 0x1D68A)),
        Font("Circled", alpha(0x24B6, 0x24D0)),
        Font("Black Circles", { ch -> if (ch.isLetter() && ch.code < 128) cp(0x1F150, ch.uppercaseChar() - 'A') else ch.toString() }),
        Font("Squares", { ch -> if (ch.isLetter() && ch.code < 128) cp(0x1F130, ch.uppercaseChar() - 'A') else ch.toString() }),
        Font("Black Squares", { ch -> if (ch.isLetter() && ch.code < 128) cp(0x1F170, ch.uppercaseChar() - 'A') else ch.toString() }),
        Font("Wide", { ch ->
            when {
                ch in 'A'..'Z' -> cp(0xFF21, ch - 'A')
                ch in 'a'..'z' -> cp(0xFF41, ch - 'a')
                ch in '0'..'9' -> cp(0xFF10, ch - '0')
                else -> ch.toString()
            }
        }),
        Font("Small Caps", { ch -> if (ch in 'a'..'z') SMALL_CAPS[ch - 'a'].toString() else ch.toString() }),
        Font("Tiny", { ch ->
            when {
                ch in 'a'..'z' -> SUPER_LO[ch - 'a'].toString()
                ch in 'A'..'Z' -> SUPER_LO[ch - 'A'].toString()
                else -> ch.toString()
            }
        })
    )

    private fun flip(s: String): String {
        val sb = StringBuilder()
        for (ch in s.reversed()) {
            sb.append(
                when {
                    ch in 'a'..'z' -> FLIP_LO[ch - 'a'].toString()
                    ch in 'A'..'Z' -> FLIP_LO[ch - 'A'].toString()
                    else -> ch.toString()
                }
            )
        }
        return sb.toString()
    }

    private fun combine(s: String, mark: Char): String {
        val sb = StringBuilder()
        for (ch in s) {
            sb.append(ch)
            if (!ch.isWhitespace()) sb.append(mark)
        }
        return sb.toString()
    }

    private fun spaced(s: String): String = s.map { it.toString() }.joinToString(" ")

    private fun conv(f: Font, s: String): String {
        val sb = StringBuilder(s.length * 2)
        for (ch in s) sb.append(f.map(ch))
        return sb.toString()
    }

    private val decorations = listOf(
        "꧁%s꧂", "★彡 %s 彡★", "【%s】", "『%s』", "༺ %s ༻",
        "✧･ﾟ: *✧ %s ✧*:･ﾟ✧", "•°¯`•• %s ••´¯°•",
        "ღ %s ღ", "♛ %s ♛", "⚡ %s ⚡", "☠ %s ☠", "❖ %s ❖",
        "★ %s ★", "♡ %s ♡", "✦ %s ✦", "❣ %s ❣", "» %s «",
        "••• %s •••", "╔═ %s ═╗", "░▒▓ %s ▓▒░",
        "〖%s〗", "❀ %s ❀", "❂ %s ❂", "👑 %s 👑", "🔥 %s 🔥",
        "💀 %s 💀", "✨ %s ✨", "💎 %s 💎"
    )

    /** Every letter style, as (label, text). */
    fun fancy(name: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        for (f in fonts) out.add(Pair(f.label, conv(f, name)))
        out.add(Pair("Upside Down", flip(name)))
        out.add(Pair("Underline", combine(name, '̲')))
        out.add(Pair("Strikethrough", combine(name, '̶')))
        out.add(Pair("Slash", combine(name, '̸')))
        out.add(Pair("Spaced", spaced(name)))
        out.add(Pair("Dotted", combine(name, '̇')))
        return out.filter { it.second != name || it.first == "Spaced" }
    }

    /** The name with a decoration around it (the bold style keeps the name readable). */
    fun decorated(name: String): List<Pair<String, String>> {
        val bold = fonts[7]
        val base = conv(bold, name)
        return decorations.map { d -> Pair(d.replace("%s", "…").trim(), d.replace("%s", base)) }
    }

    /** Random font + random decoration combinations. */
    fun mix(name: String, rnd: Random): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        val seen = HashSet<String>()
        var guard = 0
        while (out.size < 14 && guard < 200) {
            guard++
            val f = fonts[rnd.nextInt(fonts.size)]
            val d = decorations[rnd.nextInt(decorations.size)]
            val text = d.replace("%s", conv(f, name))
            if (seen.add(text)) out.add(Pair(f.label + " + decoration", text))
        }
        return out
    }
}
