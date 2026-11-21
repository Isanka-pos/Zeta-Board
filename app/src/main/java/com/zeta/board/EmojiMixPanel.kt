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
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException
import java.util.concurrent.Executors

/** What the Emoji Mix API returned for one pair of emoji. [combos] are (label, text) pairs ready to insert. */
class MixResult(
    val chemistry: Int,
    val verdict: String,
    val nameFusion: String,
    val keywords: List<String>,
    val combos: List<Pair<String, String>>,
    val related: List<String>
)

/** Talks to the Emoji Mix API (GET /api/emoji/mix?a=..&b=.., key in the x-api-key header). */
object EmojiMixApi {

    private class MixError(msg: String) : Exception(msg)

    private val pool = Executors.newSingleThreadExecutor { r -> Thread(r, "zeta-emojimix").also { it.isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    private val cache = HashMap<String, MixResult>()

    private val labels = listOf(
        "side_by_side" to "Side by side",
        "reversed" to "Reversed",
        "with_plus" to "With plus",
        "with_heart" to "With heart",
        "zwj_attempt" to "Joined",
        "sparkle" to "Sparkle",
        "stacked" to "Stacked"
    )

    /** False when the app was built without an API key. */
    fun ready(): Boolean = BuildConfig.EMOJIMIX_KEY.isNotBlank()

    /** Mixes [a] and [b]. [done] runs on the main thread with the result, or with a short message when it failed. */
    fun get(a: String, b: String, done: (MixResult?, String?) -> Unit) {
        val key = "$a|$b"
        val hit = synchronized(cache) { cache[key] }
        if (hit != null) {
            done(hit, null)
            return
        }
        pool.execute {
            val out: Pair<MixResult?, String?> = try {
                Pair(fetch(a, b), null)
            } catch (e: MixError) {
                Pair(null, e.message ?: "Could not mix these emoji")
            } catch (e: Exception) {
                Pair(null, mapIo(e))
            }
            val r = out.first
            if (r != null) {
                synchronized(cache) {
                    if (cache.size > 40) cache.clear()
                    cache[key] = r
                }
            }
            main.post { done(out.first, out.second) }
        }
    }

    private fun mapIo(e: Exception): String = when (e) {
        is SocketTimeoutException -> "The server took too long. Try again."
        is UnknownHostException -> "No internet connection."
        is IOException -> "Network problem. Check your connection."
        else -> "Unexpected answer from the Emoji Mix server."
    }

    private fun fetch(a: String, b: String): MixResult {
        val base = RemoteCfg.emojimixUrl()
        val url = base + "/api/emoji/mix?a=" + URLEncoder.encode(a, "UTF-8") + "&b=" + URLEncoder.encode(b, "UTF-8")
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 8000
            c.readTimeout = 15000
            c.requestMethod = "GET"
            c.setRequestProperty("Accept", "application/json")
            c.setRequestProperty("x-api-key", BuildConfig.EMOJIMIX_KEY)
            val code = c.responseCode
            val ok = code in 200..299
            val stream = if (ok) c.inputStream else c.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (!ok) {
                if (code == 401 || code == 403) throw MixError("The Emoji Mix key is wrong or missing.")
                if (code == 429) throw MixError("Too many tries. Wait a moment.")
                var msg = ""
                try {
                    val j = JSONObject(body)
                    msg = j.optString("error", j.optString("message", ""))
                } catch (e: Exception) {
                }
                if (msg == "null") msg = ""
                throw MixError(if (msg.isNotBlank()) msg.take(80) else "Emoji Mix server error ($code).")
            }
            return parse(JSONObject(body))
        } finally {
            c.disconnect()
        }
    }

    private fun str(o: JSONObject, k: String): String {
        val v = o.optString(k, "")
        return if (v == "null") "" else v
    }

    private fun items(arr: JSONArray?, max: Int): List<String> {
        val out = ArrayList<String>()
        if (arr == null) return out
        for (i in 0 until minOf(arr.length(), max)) {
            val o = arr.opt(i)
            val s = when (o) {
                is String -> o
                is JSONObject -> str(o, "emoji")
                else -> ""
            }
            if (s.isNotBlank() && s != "null") out.add(s)
        }
        return out
    }

    private fun parse(j: JSONObject): MixResult {
        val err = str(j, "error")
        if (err.isNotBlank()) throw MixError(err.take(80))
        val combos = ArrayList<Pair<String, String>>()
        val mix = j.optJSONObject("mix")
        if (mix != null) {
            for ((k, label) in labels) {
                val v = str(mix, k)
                if (v.isNotBlank() && combos.none { it.second == v }) combos.add(Pair(label, v))
            }
        }
        if (combos.isEmpty()) throw MixError("No mix came back for these emoji.")
        return MixResult(
            chemistry = j.optInt("chemistry", -1),
            verdict = str(j, "verdict"),
            nameFusion = str(j, "name_fusion"),
            keywords = items(j.optJSONArray("shared_keywords"), 6),
            combos = combos,
            related = items(j.optJSONArray("related"), 12)
        )
    }
}

/**
 * Emoji Mix panel: pick two emoji, the Emoji Mix API combines them (chemistry score, verdict, fused name and
 * ready-made mixes). Tap a mix to insert it into the focused field.
 */
class EmojiMixPanel(ctx: Context, private val cb: Callback) : FrameLayout(ctx) {

    interface Callback {
        /** Inserts the text into the focused field. Returns false when that is not possible. */
        fun onMixPick(text: String): Boolean
        fun onMixClose()
    }

    private companion object {
        const val PICK = 0
        const val LOADING = 1
        const val RESULT = 2
        const val FAILED = 3
        const val COLS = 8
        const val MAX_CELLS = 160
    }

    private val d = ctx.resources.displayMetrics.density
    private fun dp(v: Int): Int = (v * d).toInt()
    private val M = ViewGroup.LayoutParams.MATCH_PARENT
    private val W = ViewGroup.LayoutParams.WRAP_CONTENT
    private var th: KbTheme = Themes.get(0)

    private val main = LinearLayout(ctx)
    private val titleView = TextView(ctx)
    private val slotA = TextView(ctx)
    private val slotB = TextView(ctx)
    private val plus = TextView(ctx)
    private val hint = TextView(ctx)
    private val catScroll = HorizontalScrollView(ctx)
    private val catRow = LinearLayout(ctx)
    private val pickScroll = ScrollView(ctx)
    private val grid = LinearLayout(ctx)
    private val resultScroll = ScrollView(ctx)
    private val resultBox = LinearLayout(ctx)
    private val toast = TextView(ctx)
    private lateinit var backBtn: ImageView

    private var a = ""
    private var b = ""
    private var active = 0
    private var mode = PICK
    private var cat = 1
    private var result: MixResult? = null
    private var failMsg = ""
    private var req = 0

    init {
        main.orientation = LinearLayout.VERTICAL

        val header = LinearLayout(ctx)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        header.setPadding(dp(6), 0, dp(6), 0)
        backBtn = iconBtn(R.drawable.ic_keyboard, 40) { cb.onMixClose() }
        titleView.text = "Emoji Mix"
        titleView.textSize = 16f
        titleView.typeface = Typeface.DEFAULT_BOLD
        header.addView(backBtn, LinearLayout.LayoutParams(dp(40), dp(40)))
        val tlp = LinearLayout.LayoutParams(0, W, 1f)
        tlp.leftMargin = dp(6)
        header.addView(titleView, tlp)
        main.addView(header, LinearLayout.LayoutParams(M, dp(44)))

        val slots = LinearLayout(ctx)
        slots.orientation = LinearLayout.HORIZONTAL
        slots.gravity = Gravity.CENTER_VERTICAL
        slots.setPadding(dp(12), dp(2), dp(12), dp(6))
        slotA.gravity = Gravity.CENTER
        slotA.textSize = 26f
        slotB.gravity = Gravity.CENTER
        slotB.textSize = 26f
        slotA.setOnClickListener { selectSlot(0) }
        slotB.setOnClickListener { selectSlot(1) }
        plus.text = "+"
        plus.textSize = 20f
        plus.typeface = Typeface.DEFAULT_BOLD
        plus.gravity = Gravity.CENTER
        hint.textSize = 12.5f
        slots.addView(slotA, LinearLayout.LayoutParams(dp(54), dp(54)))
        slots.addView(plus, LinearLayout.LayoutParams(dp(28), W))
        slots.addView(slotB, LinearLayout.LayoutParams(dp(54), dp(54)))
        val hlp = LinearLayout.LayoutParams(0, W, 1f)
        hlp.leftMargin = dp(12)
        slots.addView(hint, hlp)
        main.addView(slots, LinearLayout.LayoutParams(M, W))

        catScroll.isHorizontalScrollBarEnabled = false
        catScroll.overScrollMode = View.OVER_SCROLL_NEVER
        catRow.orientation = LinearLayout.HORIZONTAL
        catRow.setPadding(dp(6), 0, dp(6), 0)
        catScroll.addView(catRow, ViewGroup.LayoutParams(W, M))
        main.addView(catScroll, LinearLayout.LayoutParams(M, dp(40)))

        pickScroll.isVerticalScrollBarEnabled = false
        pickScroll.overScrollMode = View.OVER_SCROLL_NEVER
        grid.orientation = LinearLayout.VERTICAL
        grid.setPadding(dp(6), dp(2), dp(6), dp(8))
        pickScroll.addView(grid, ViewGroup.LayoutParams(M, W))
        main.addView(pickScroll, LinearLayout.LayoutParams(M, 0, 1f))

        resultScroll.isVerticalScrollBarEnabled = false
        resultScroll.overScrollMode = View.OVER_SCROLL_NEVER
        resultScroll.isFillViewport = true
        resultBox.orientation = LinearLayout.VERTICAL
        resultBox.setPadding(dp(8), dp(2), dp(8), dp(10))
        resultScroll.addView(resultBox, ViewGroup.LayoutParams(M, W))
        main.addView(resultScroll, LinearLayout.LayoutParams(M, 0, 1f))
        resultScroll.visibility = View.GONE

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

    /** Called when the panel opens: starts with two empty slots. */
    fun open() {
        req++
        a = ""
        b = ""
        active = 0
        mode = PICK
        result = null
        failMsg = ""
        cat = if (Emojis.recents(context).isNotEmpty()) Emojis.RECENT else 1
        renderAll()
        pickScroll.scrollTo(0, 0)
    }

    fun close() {
        req++
        toast.animate().cancel()
        toast.alpha = 0f
    }

    // ---------- logic ----------

    private fun selectSlot(i: Int) {
        req++
        active = i
        mode = PICK
        renderAll()
    }

    private fun pickEmoji(e: String) {
        if (active == 0) a = e else b = e
        if (a.isNotEmpty() && b.isNotEmpty()) {
            mixNow()
        } else {
            active = if (a.isEmpty()) 0 else 1
            renderAll()
        }
    }

    private fun mixNow() {
        if (!EmojiMixApi.ready()) {
            mode = FAILED
            failMsg = "Emoji Mix is not set up yet. The key must be added when the app is built."
            renderAll()
            return
        }
        val my = ++req
        mode = LOADING
        renderAll()
        EmojiMixApi.get(a, b) { res, err ->
            if (my == req) {
                if (res != null) {
                    result = res
                    mode = RESULT
                } else {
                    failMsg = err ?: "Could not mix these emoji."
                    mode = FAILED
                }
                renderAll()
            }
        }
    }

    // ---------- rendering ----------

    private fun catList(i: Int): List<String> =
        if (i == Emojis.RECENT) Emojis.recents(context) else Emojis.cats[i].all

    private fun renderAll() {
        val picking = mode == PICK
        styleSlot(slotA, a, picking && active == 0)
        styleSlot(slotB, b, picking && active == 1)
        hint.text = when (mode) {
            PICK -> if (active == 0) "Pick the first emoji" else "Pick the second emoji"
            LOADING -> "Mixing\u2026"
            else -> "Tap a slot to change"
        }
        catScroll.visibility = if (picking) View.VISIBLE else View.GONE
        pickScroll.visibility = if (picking) View.VISIBLE else View.GONE
        resultScroll.visibility = if (picking) View.GONE else View.VISIBLE
        if (picking) {
            renderCats()
            renderGrid()
        } else {
            renderResult()
        }
    }

    private fun styleSlot(v: TextView, e: String, on: Boolean) {
        v.text = if (e.isEmpty()) "?" else e
        v.setTextColor(if (e.isEmpty()) withAlpha(th.text, 110) else th.text)
        val g = GradientDrawable()
        g.cornerRadius = dp(16).toFloat()
        g.setColor(th.key)
        g.setStroke(dp(2), if (on) th.accent else withAlpha(th.text, 30))
        v.background = RippleDrawable(ColorStateList.valueOf(withAlpha(th.accent, 70)), g, null)
    }

    private fun renderCats() {
        catRow.removeAllViews()
        if (cat !in Emojis.cats.indices || catList(cat).isEmpty()) cat = 1
        for (i in Emojis.cats.indices) {
            if (catList(i).isEmpty()) continue
            val c = TextView(context)
            c.text = Emojis.cats[i].icon
            c.textSize = 18f
            c.gravity = Gravity.CENTER
            val g = GradientDrawable()
            g.cornerRadius = dp(18).toFloat()
            g.setColor(if (i == cat) withAlpha(th.accent, 80) else Color.TRANSPARENT)
            c.background = RippleDrawable(ColorStateList.valueOf(withAlpha(th.accent, 70)), g, null)
            c.setOnClickListener {
                cat = i
                renderCats()
                renderGrid()
                pickScroll.scrollTo(0, 0)
            }
            val lp = LinearLayout.LayoutParams(dp(44), dp(36))
            lp.setMargins(dp(3), dp(2), dp(3), dp(2))
            catRow.addView(c, lp)
        }
    }

    private fun renderGrid() {
        grid.removeAllViews()
        val list = catList(cat).take(MAX_CELLS)
        var i = 0
        while (i < list.size) {
            val row = LinearLayout(context)
            row.orientation = LinearLayout.HORIZONTAL
            for (j in 0 until COLS) {
                val idx = i + j
                val cell = TextView(context)
                if (idx < list.size) {
                    val e = list[idx]
                    cell.text = e
                    cell.textSize = 24f
                    cell.gravity = Gravity.CENTER
                    val mask = GradientDrawable()
                    mask.cornerRadius = dp(12).toFloat()
                    mask.setColor(Color.WHITE)
                    cell.background = RippleDrawable(ColorStateList.valueOf(withAlpha(th.accent, 90)), null, mask)
                    cell.setOnClickListener { pickEmoji(e) }
                }
                row.addView(cell, LinearLayout.LayoutParams(0, dp(44), 1f))
            }
            grid.addView(row, LinearLayout.LayoutParams(M, W))
            i += COLS
        }
    }

    private fun renderResult() {
        resultBox.removeAllViews()
        if (mode == LOADING) {
            resultBox.addView(message("Mixing\u2026", "Asking the Emoji Mix server", false), LinearLayout.LayoutParams(M, M))
            return
        }
        val r = result
        if (mode == FAILED || r == null) {
            resultBox.addView(message("Could not mix", failMsg.ifEmpty { "Try again." }, true), LinearLayout.LayoutParams(M, M))
            return
        }

        // score + verdict
        val top = LinearLayout(context)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        top.setPadding(dp(6), dp(4), dp(6), dp(2))
        if (r.chemistry >= 0) {
            val sc = TextView(context)
            sc.text = r.chemistry.toString() + "%"
            sc.textSize = 24f
            sc.typeface = Typeface.DEFAULT_BOLD
            sc.setTextColor(th.accent)
            top.addView(sc, LinearLayout.LayoutParams(W, W))
        }
        if (r.verdict.isNotBlank()) {
            val vd = TextView(context)
            vd.text = r.verdict
            vd.textSize = 13.5f
            vd.typeface = Typeface.DEFAULT_BOLD
            vd.maxLines = 2
            vd.ellipsize = TextUtils.TruncateAt.END
            vd.setTextColor(th.text)
            val vlp = LinearLayout.LayoutParams(0, W, 1f)
            vlp.leftMargin = dp(10)
            top.addView(vd, vlp)
        }
        resultBox.addView(top, LinearLayout.LayoutParams(M, W))

        if (r.chemistry in 0..100) {
            val bar = LinearLayout(context)
            bar.orientation = LinearLayout.HORIZONTAL
            val track = GradientDrawable()
            track.cornerRadius = dp(4).toFloat()
            track.setColor(withAlpha(th.text, 30))
            bar.background = track
            val fill = View(context)
            val fg = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(th.accent, mix(th.accent, Color.WHITE, 0.3f)))
            fg.cornerRadius = dp(4).toFloat()
            fill.background = fg
            if (r.chemistry > 0) bar.addView(fill, LinearLayout.LayoutParams(0, M, r.chemistry.toFloat()))
            if (r.chemistry < 100) bar.addView(View(context), LinearLayout.LayoutParams(0, M, (100 - r.chemistry).toFloat()))
            val blp = LinearLayout.LayoutParams(M, dp(8))
            blp.setMargins(dp(6), dp(4), dp(6), dp(6))
            resultBox.addView(bar, blp)
        }

        if (r.nameFusion.isNotBlank() || r.keywords.isNotEmpty()) {
            val info = TextView(context)
            val parts = ArrayList<String>()
            if (r.nameFusion.isNotBlank()) parts.add("Name: " + r.nameFusion)
            if (r.keywords.isNotEmpty()) parts.add("Shared: " + r.keywords.joinToString(", "))
            info.text = parts.joinToString("   \u00B7   ")
            info.textSize = 12f
            info.maxLines = 2
            info.ellipsize = TextUtils.TruncateAt.END
            info.setTextColor(withAlpha(th.text, 170))
            info.setPadding(dp(6), 0, dp(6), dp(6))
            resultBox.addView(info, LinearLayout.LayoutParams(M, W))
        }

        val tap = TextView(context)
        tap.text = "Tap a mix to insert it"
        tap.textSize = 11.5f
        tap.setTextColor(withAlpha(th.text, 130))
        tap.setPadding(dp(6), dp(2), dp(6), dp(2))
        resultBox.addView(tap, LinearLayout.LayoutParams(M, W))

        var k = 0
        while (k < r.combos.size) {
            val row = LinearLayout(context)
            row.orientation = LinearLayout.HORIZONTAL
            for (j in 0..1) {
                val item = r.combos.getOrNull(k + j)
                val lp = LinearLayout.LayoutParams(0, W, 1f)
                lp.setMargins(dp(3), dp(3), dp(3), dp(3))
                if (item != null) row.addView(comboCard(item.first, item.second), lp) else row.addView(View(context), lp)
            }
            resultBox.addView(row, LinearLayout.LayoutParams(M, W))
            k += 2
        }

        if (r.related.isNotEmpty()) {
            val rl = TextView(context)
            rl.text = "Related"
            rl.textSize = 11.5f
            rl.setTextColor(withAlpha(th.text, 130))
            rl.setPadding(dp(6), dp(8), dp(6), dp(2))
            resultBox.addView(rl, LinearLayout.LayoutParams(M, W))
            val hs = HorizontalScrollView(context)
            hs.isHorizontalScrollBarEnabled = false
            val rr = LinearLayout(context)
            rr.orientation = LinearLayout.HORIZONTAL
            for (e in r.related) {
                val t = TextView(context)
                t.text = e
                t.textSize = 24f
                t.gravity = Gravity.CENTER
                val mask = GradientDrawable()
                mask.cornerRadius = dp(12).toFloat()
                mask.setColor(Color.WHITE)
                t.background = RippleDrawable(ColorStateList.valueOf(withAlpha(th.accent, 90)), null, mask)
                t.setOnClickListener { if (!cb.onMixPick(e)) flash("Can't insert here") }
                rr.addView(t, LinearLayout.LayoutParams(dp(46), dp(44)))
            }
            hs.addView(rr, ViewGroup.LayoutParams(W, W))
            resultBox.addView(hs, LinearLayout.LayoutParams(M, W))
        }
    }

    private fun comboCard(label: String, text: String): View {
        val c = LinearLayout(context)
        c.orientation = LinearLayout.VERTICAL
        c.gravity = Gravity.CENTER
        c.setPadding(dp(8), dp(8), dp(8), dp(8))
        val shape = GradientDrawable()
        shape.cornerRadius = dp(16).toFloat()
        shape.setColor(th.key)
        shape.setStroke(dp(1), withAlpha(th.text, 28))
        c.background = RippleDrawable(ColorStateList.valueOf(withAlpha(th.accent, 70)), shape, null)
        c.isClickable = true
        c.setOnClickListener { if (!cb.onMixPick(text)) flash("Can't insert here") }
        val t = TextView(context)
        t.text = text
        t.textSize = 22f
        t.gravity = Gravity.CENTER
        t.maxLines = 2
        t.ellipsize = TextUtils.TruncateAt.END
        t.setTextColor(th.text)
        val l = TextView(context)
        l.text = label
        l.textSize = 11f
        l.gravity = Gravity.CENTER
        l.setTextColor(withAlpha(th.text, 150))
        l.setPadding(0, dp(2), 0, 0)
        c.addView(t, LinearLayout.LayoutParams(M, W))
        c.addView(l, LinearLayout.LayoutParams(M, W))
        return c
    }

    private fun message(title: String, sub: String, retry: Boolean): View {
        val box = LinearLayout(context)
        box.orientation = LinearLayout.VERTICAL
        box.gravity = Gravity.CENTER
        box.setPadding(dp(24), dp(8), dp(24), dp(8))
        val t1 = TextView(context)
        t1.text = title
        t1.textSize = 15f
        t1.typeface = Typeface.DEFAULT_BOLD
        t1.setTextColor(th.text)
        t1.gravity = Gravity.CENTER
        t1.setPadding(0, 0, 0, dp(3))
        val t2 = TextView(context)
        t2.text = sub
        t2.textSize = 12f
        t2.gravity = Gravity.CENTER
        t2.setTextColor(withAlpha(th.text, 150))
        box.addView(t1)
        box.addView(t2)
        if (retry && a.isNotEmpty() && b.isNotEmpty() && EmojiMixApi.ready()) {
            val btn = TextView(context)
            btn.text = "Try again"
            btn.textSize = 13f
            btn.typeface = Typeface.DEFAULT_BOLD
            btn.gravity = Gravity.CENTER
            btn.setTextColor(th.accentText)
            btn.setPadding(dp(18), dp(9), dp(18), dp(9))
            val g = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(th.accent, mix(th.accent, Color.WHITE, 0.25f)))
            g.cornerRadius = dp(30).toFloat()
            btn.background = g
            btn.setOnClickListener { mixNow() }
            val lp = LinearLayout.LayoutParams(W, W)
            lp.topMargin = dp(12)
            box.addView(btn, lp)
        }
        return box
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
        mask.setColor(Color.WHITE)
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
        plus.setTextColor(withAlpha(t.text, 150))
        hint.setTextColor(withAlpha(t.text, 170))
        styleIcon(backBtn, t.text)
        val tb = GradientDrawable()
        tb.cornerRadius = dp(20).toFloat()
        tb.setColor(mix(t.bgTop, t.accent, 0.5f))
        toast.background = tb
        toast.setTextColor(t.accentText)
        renderAll()
    }

    override fun onDetachedFromWindow() {
        req++
        super.onDetachedFromWindow()
    }
}
