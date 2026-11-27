package com.zeta.board

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MenuEntry(val id: String, val title: String, val sub: String, val icon: Int)

/**
 * The slide-in category menu that opens from the three-line (hamburger) button.
 * It only draws the menu; [onPick] tells the screen which category was tapped.
 */
class SideMenu(
    ctx: Context,
    private val entries: List<MenuEntry>,
    private val onPick: (String) -> Unit
) : FrameLayout(ctx) {

    private val d = ctx.resources.displayMetrics.density
    private fun dp(v: Int): Int = (v * d).toInt()
    private val M = ViewGroup.LayoutParams.MATCH_PARENT
    private val W = ViewGroup.LayoutParams.WRAP_CONTENT

    private val scrim = View(ctx)
    private val panel = LinearLayout(ctx)
    private val versionText = TextView(ctx)
    private val rows = LinkedHashMap<String, Row>()
    private var current: String = entries.firstOrNull()?.id ?: ""

    var isOpen = false
        private set

    init {
        visibility = View.GONE
        scrim.setBackgroundColor(Color.argb(160, 0, 0, 0))
        scrim.setOnClickListener { close() }
        addView(scrim, LayoutParams(M, M))
        buildPanel()
        addView(panel, LayoutParams(panelWidth(), M, Gravity.START))
    }

    private fun panelWidth(): Int {
        val screen = resources.displayMetrics.widthPixels
        return minOf(dp(310), (screen * 0.84f).toInt())
    }

    private fun label(s: String, size: Float, color: Int, bold: Boolean): TextView {
        val t = TextView(context)
        t.text = s
        t.textSize = size
        t.setTextColor(color)
        if (bold) t.typeface = Typeface.DEFAULT_BOLD
        return t
    }

    private fun buildPanel() {
        panel.orientation = LinearLayout.VERTICAL
        panel.isClickable = true
        panel.elevation = dp(16).toFloat()
        val bg = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Pal.BG2, Pal.BG))
        val r = dp(28).toFloat()
        bg.cornerRadii = floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
        panel.background = bg
        panel.setPadding(dp(14), dp(30), dp(14), dp(16))

        val head = LinearLayout(context)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.setPadding(dp(6), 0, dp(2), 0)
        head.addView(LogoBadge(context), LinearLayout.LayoutParams(dp(50), dp(50)))
        val names = LinearLayout(context)
        names.orientation = LinearLayout.VERTICAL
        names.setPadding(dp(12), 0, 0, 0)
        names.addView(label("Zeta Board", 19f, Color.WHITE, true))
        versionText.textSize = 12f
        versionText.setTextColor(Pal.MUTED)
        names.addView(versionText)
        head.addView(names, LinearLayout.LayoutParams(0, W, 1f))
        val x = ImageView(context)
        x.setImageResource(R.drawable.ic_close)
        x.setColorFilter(Pal.MUTED)
        x.setPadding(dp(10), dp(10), dp(10), dp(10))
        x.setOnClickListener { close() }
        head.addView(x, LinearLayout.LayoutParams(dp(40), dp(40)))
        panel.addView(head, LinearLayout.LayoutParams(M, W))

        val line = View(context)
        line.setBackgroundColor(Pal.STROKE)
        val lineLp = LinearLayout.LayoutParams(M, dp(1))
        lineLp.topMargin = dp(18)
        lineLp.bottomMargin = dp(14)
        panel.addView(line, lineLp)

        val cap = label("CATEGORIES", 11f, Pal.MUTED, true)
        cap.letterSpacing = 0.14f
        cap.setPadding(dp(8), 0, 0, dp(8))
        panel.addView(cap, LinearLayout.LayoutParams(M, W))

        val list = LinearLayout(context)
        list.orientation = LinearLayout.VERTICAL
        for (e in entries) {
            val row = Row(e)
            rows[e.id] = row
            row.paint(e.id == current)
            val lp = LinearLayout.LayoutParams(M, W)
            lp.bottomMargin = dp(4)
            list.addView(row.view, lp)
        }
        val sv = ScrollView(context)
        sv.isVerticalScrollBarEnabled = false
        sv.addView(list)
        panel.addView(sv, LinearLayout.LayoutParams(M, 0, 1f))

        val foot = label("Made by Isanka Dev", 11f, Pal.MUTED, false)
        foot.gravity = Gravity.CENTER
        foot.setPadding(0, dp(10), 0, 0)
        panel.addView(foot, LinearLayout.LayoutParams(M, W))
    }

    fun setVersion(text: String) {
        versionText.text = text
    }

    /** Highlights the category that is showing. */
    fun select(id: String) {
        if (id == current && painted) return
        val old = current
        current = id
        if (!painted) {
            // first time: draw every row once
            for ((k, row) in rows) row.paint(k == current)
            painted = true
            return
        }
        // afterwards only the two rows that changed are redrawn
        rows[old]?.paint(false)
        rows[id]?.paint(true)
    }

    private var painted = false

    /** Shows or hides the red dot on a category (used for "update available"). */
    fun badge(id: String, on: Boolean) {
        rows[id]?.dot?.visibility = if (on) View.VISIBLE else View.GONE
    }

    fun open() {
        if (isOpen) return
        isOpen = true
        visibility = View.VISIBLE
        val w = panelWidth()
        val lp = panel.layoutParams as LayoutParams
        lp.width = w
        panel.layoutParams = lp
        panel.translationX = -w.toFloat()
        scrim.alpha = 0f
        panel.animate().translationX(0f).setDuration(200).setInterpolator(DecelerateInterpolator()).start()
        scrim.animate().alpha(1f).setDuration(200).start()
    }

    fun close() {
        if (!isOpen) return
        isOpen = false
        val w = panel.width.toFloat()
        panel.animate().translationX(-w).setDuration(130).setInterpolator(android.view.animation.AccelerateInterpolator()).start()
        scrim.animate().alpha(0f).setDuration(130).withEndAction {
            if (!isOpen) visibility = View.GONE
        }.start()
    }

    private inner class Row(val e: MenuEntry) {
        val view = LinearLayout(this@SideMenu.context)
        val tile = FrameLayout(this@SideMenu.context)
        val icon = ImageView(this@SideMenu.context)
        val title = TextView(this@SideMenu.context)
        val sub = TextView(this@SideMenu.context)
        val dot = View(this@SideMenu.context)

        init {
            view.orientation = LinearLayout.HORIZONTAL
            view.gravity = Gravity.CENTER_VERTICAL
            view.setPadding(dp(10), dp(9), dp(12), dp(9))

            icon.setImageResource(e.icon)
            icon.setPadding(dp(9), dp(9), dp(9), dp(9))
            tile.addView(icon, FrameLayout.LayoutParams(dp(40), dp(40)))
            view.addView(tile, LinearLayout.LayoutParams(dp(40), dp(40)))

            val texts = LinearLayout(this@SideMenu.context)
            texts.orientation = LinearLayout.VERTICAL
            texts.setPadding(dp(12), 0, dp(8), 0)
            title.text = e.title
            title.textSize = 15f
            title.typeface = Typeface.DEFAULT_BOLD
            sub.text = e.sub
            sub.textSize = 11.5f
            sub.setTextColor(Pal.MUTED)
            sub.maxLines = 1
            texts.addView(title)
            texts.addView(sub)
            view.addView(texts, LinearLayout.LayoutParams(0, W, 1f))

            val dotBg = GradientDrawable()
            dotBg.shape = GradientDrawable.OVAL
            dotBg.setColor(Color.parseColor("#EF4444"))
            dot.background = dotBg
            dot.visibility = View.GONE
            view.addView(dot, LinearLayout.LayoutParams(dp(10), dp(10)))

            view.setOnClickListener { onPick(e.id) }
        }

        fun paint(sel: Boolean) {
            val face = GradientDrawable()
            face.cornerRadius = dp(16).toFloat()
            if (sel) {
                face.setColor(Color.argb(44, 168, 85, 247))
                face.setStroke(dp(1), Pal.PURPLE)
            } else {
                face.setColor(Color.TRANSPARENT)
            }
            val mask = GradientDrawable()
            mask.cornerRadius = dp(16).toFloat()
            mask.setColor(Color.WHITE)
            view.background = RippleDrawable(ColorStateList.valueOf(Color.argb(50, 255, 255, 255)), face, mask)

            val tileColors = if (sel) intArrayOf(Pal.CYAN, Pal.PURPLE) else intArrayOf(Pal.TILE, Pal.TILE)
            val tileBg = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, tileColors)
            tileBg.cornerRadius = dp(12).toFloat()
            tile.background = tileBg
            icon.setColorFilter(if (sel) Color.WHITE else Pal.CYAN)
            title.setTextColor(if (sel) Color.WHITE else Color.parseColor("#E6E1F7"))
        }
    }
}
