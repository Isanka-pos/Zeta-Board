package com.zeta.board

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

private class NotesScroll(ctx: Context, private val maxH: Int) : ScrollView(ctx) {
    override fun onMeasure(w: Int, h: Int) {
        super.onMeasure(w, MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST))
    }
}

/** Shown once after the app has been updated: "Update installed" + the release notes. */
object WhatsNewDialog {

    fun show(a: Activity, name: String, notes: String, onClose: () -> Unit) {
        val d = a.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

        fun tv(s: String, size: Float, color: Int, bold: Boolean): TextView {
            val t = TextView(a)
            t.text = s
            t.textSize = size
            t.setTextColor(color)
            if (bold) t.typeface = Typeface.DEFAULT_BOLD
            return t
        }

        fun shape(color: Int, r: Int, strokeColor: Int = 0, stroke: Int = 0): GradientDrawable {
            val g = GradientDrawable()
            g.setColor(color)
            g.cornerRadius = dp(r).toFloat()
            if (stroke > 0) g.setStroke(dp(stroke), strokeColor)
            return g
        }

        fun gradient(c1: Int, c2: Int, r: Int): GradientDrawable {
            val g = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(c1, c2))
            g.cornerRadius = dp(r).toFloat()
            return g
        }

        val dialog = Dialog(a)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setCancelable(true)

        val root = LinearLayout(a)
        root.orientation = LinearLayout.VERTICAL
        root.gravity = Gravity.CENTER_HORIZONTAL
        root.setPadding(dp(20), dp(24), dp(20), dp(20))
        root.background = shape(Pal.CARD, 28, Pal.GREEN, 1)

        root.addView(LogoBadge(a), LinearLayout.LayoutParams(dp(72), dp(72)))

        val pill = tv("UPDATE INSTALLED", 11f, Color.WHITE, true)
        pill.letterSpacing = 0.08f
        pill.setPadding(dp(14), dp(6), dp(14), dp(6))
        pill.background = gradient(Pal.GREEN, Pal.CYAN, 20)
        val pillLp = LinearLayout.LayoutParams(WRAP, WRAP)
        pillLp.topMargin = dp(16)
        root.addView(pill, pillLp)

        val title = tv("Zeta Board is up to date!", 21f, Color.WHITE, true)
        title.gravity = Gravity.CENTER
        val titleLp = LinearLayout.LayoutParams(MATCH, WRAP)
        titleLp.topMargin = dp(12)
        root.addView(title, titleLp)

        val chip = tv("You are now on v$name", 14f, Pal.CYAN, true)
        chip.setPadding(dp(18), dp(8), dp(18), dp(8))
        chip.background = shape(Color.TRANSPARENT, 20, Pal.CYAN, 1)
        val chipLp = LinearLayout.LayoutParams(WRAP, WRAP)
        chipLp.topMargin = dp(12)
        root.addView(chip, chipLp)

        val box = LinearLayout(a)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(16), dp(14), dp(16), dp(14))
        box.addView(tv("What's New:", 15f, Color.WHITE, true))
        val body = tv(notes.ifBlank { "Improvements and bug fixes" }, 13f, Color.parseColor("#C9C2E8"), false)
        body.setLineSpacing(0f, 1.2f)
        body.setPadding(0, dp(8), 0, 0)
        box.addView(body)
        val scroll = NotesScroll(a, dp(220))
        scroll.addView(box)
        scroll.background = shape(Pal.BG, 16, Pal.STROKE, 1)
        val scrollLp = LinearLayout.LayoutParams(MATCH, WRAP)
        scrollLp.topMargin = dp(16)
        root.addView(scroll, scrollLp)

        val ok = tv("Awesome", 15f, Color.WHITE, true)
        ok.gravity = Gravity.CENTER
        ok.background = gradient(Pal.CYAN, Pal.PURPLE, 18)
        ok.setOnClickListener { dialog.dismiss() }
        val okLp = LinearLayout.LayoutParams(MATCH, dp(48))
        okLp.topMargin = dp(18)
        root.addView(ok, okLp)

        dialog.setContentView(root)
        dialog.setOnDismissListener {
            Updater.dialogOpen = false
            Updater.dialog = null
            onClose()
        }
        Updater.dialogOpen = true
        Updater.dialog = dialog
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
        dialog.window?.setLayout((a.resources.displayMetrics.widthPixels * 0.92f).toInt(), WRAP)
    }
}
