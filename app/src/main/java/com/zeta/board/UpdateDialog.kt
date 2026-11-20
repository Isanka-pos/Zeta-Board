package com.zeta.board

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.content.res.ColorStateList
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.Locale

private class MaxScroll(ctx: Context, private val maxH: Int) : ScrollView(ctx) {
    override fun onMeasure(w: Int, h: Int) {
        super.onMeasure(w, MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST))
    }
}

object UpdateDialog {

    fun show(a: Activity, info: Updater.Info) {
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

        fun gradient(r: Int): GradientDrawable {
            val g = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Pal.CYAN, Pal.PURPLE))
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
        root.background = shape(Pal.CARD, 28, Pal.PURPLE, 1)

        root.addView(LogoBadge(a), LinearLayout.LayoutParams(dp(72), dp(72)))

        val pill = tv("NEW UPDATE AVAILABLE", 11f, Color.WHITE, true)
        pill.letterSpacing = 0.08f
        pill.setPadding(dp(14), dp(6), dp(14), dp(6))
        pill.background = gradient(20)
        val pillLp = LinearLayout.LayoutParams(WRAP, WRAP)
        pillLp.topMargin = dp(16)
        root.addView(pill, pillLp)

        val title = tv("Zeta Board Update Ready!", 21f, Color.WHITE, true)
        title.gravity = Gravity.CENTER
        val titleLp = LinearLayout.LayoutParams(MATCH, WRAP)
        titleLp.topMargin = dp(12)
        root.addView(title, titleLp)

        val chip = tv("v" + Updater.currentName(a) + "  \u2192  v" + info.name, 14f, Pal.CYAN, true)
        chip.setPadding(dp(18), dp(8), dp(18), dp(8))
        chip.background = shape(Color.TRANSPARENT, 20, Pal.CYAN, 1)
        val chipLp = LinearLayout.LayoutParams(WRAP, WRAP)
        chipLp.topMargin = dp(12)
        root.addView(chip, chipLp)

        val warn = tv("\u26A0  Please update soon: older versions may have bugs that are already fixed.", 12f, Color.parseColor("#F59E0B"), false)
        warn.gravity = Gravity.CENTER
        val warnLp = LinearLayout.LayoutParams(MATCH, WRAP)
        warnLp.topMargin = dp(12)
        root.addView(warn, warnLp)

        // heads-up about the Google Play Protect screen that Android shows for apps that are not from the Play Store
        val pp = tv("\u2139  Android may show \"Play Protect: App scan recommended\". This is normal for apps outside the Play Store. Tap \"Scan app\" (or More details \u2192 Install without scanning), then Install.", 12f, Pal.CYAN, false)
        pp.gravity = Gravity.CENTER
        pp.setLineSpacing(0f, 1.15f)
        pp.setPadding(dp(12), dp(8), dp(12), dp(8))
        pp.background = shape(Color.TRANSPARENT, 12, Pal.CYAN, 1)
        val ppLp = LinearLayout.LayoutParams(MATCH, WRAP)
        ppLp.topMargin = dp(10)
        root.addView(pp, ppLp)

        val notes = LinearLayout(a)
        notes.orientation = LinearLayout.VERTICAL
        notes.setPadding(dp(16), dp(14), dp(16), dp(14))
        notes.addView(tv("What's New in v" + info.name + ":", 15f, Color.WHITE, true))
        val body = tv(info.notes.ifBlank { "Improvements and bug fixes" }, 13f, Color.parseColor("#C9C2E8"), false)
        body.setLineSpacing(0f, 1.2f)
        body.setPadding(0, dp(8), 0, 0)
        notes.addView(body)
        val scroll = MaxScroll(a, dp(200))
        scroll.addView(notes)
        scroll.background = shape(Pal.BG, 16, Pal.STROKE, 1)
        val scrollLp = LinearLayout.LayoutParams(MATCH, WRAP)
        scrollLp.topMargin = dp(16)
        root.addView(scroll, scrollLp)

        // progress card (hidden until download starts)
        val prog = LinearLayout(a)
        prog.orientation = LinearLayout.VERTICAL
        prog.setPadding(dp(16), dp(12), dp(16), dp(12))
        prog.background = shape(Pal.BG, 16, Pal.PURPLE, 1)
        prog.visibility = View.GONE
        val progTop = LinearLayout(a)
        progTop.orientation = LinearLayout.HORIZONTAL
        val progLabel = tv("Downloading update...", 14f, Color.WHITE, true)
        val progPct = tv("0%", 14f, Pal.PURPLE, true)
        progTop.addView(progLabel, LinearLayout.LayoutParams(0, WRAP, 1f))
        progTop.addView(progPct)
        prog.addView(progTop)
        val bar = ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal)
        bar.max = 100
        bar.progressTintList = ColorStateList.valueOf(Pal.CYAN)
        bar.progressBackgroundTintList = ColorStateList.valueOf(Pal.STROKE)
        val barLp = LinearLayout.LayoutParams(MATCH, dp(8))
        barLp.topMargin = dp(10)
        prog.addView(bar, barLp)
        val progMb = tv("0.0 MB", 12f, Pal.MUTED, false)
        progMb.setPadding(0, dp(8), 0, 0)
        prog.addView(progMb)
        val progLp = LinearLayout.LayoutParams(MATCH, WRAP)
        progLp.topMargin = dp(14)
        root.addView(prog, progLp)

        // buttons
        val later = tv("Later", 15f, Color.WHITE, true)
        later.gravity = Gravity.CENTER
        later.background = shape(Pal.BG, 18, Pal.STROKE, 1)
        val update = tv("Update Now", 15f, Color.WHITE, true)
        update.gravity = Gravity.CENTER
        update.background = gradient(18)
        val btns = LinearLayout(a)
        btns.orientation = LinearLayout.HORIZONTAL
        val laterLp = LinearLayout.LayoutParams(0, dp(48), 1f)
        laterLp.rightMargin = dp(10)
        btns.addView(later, laterLp)
        btns.addView(update, LinearLayout.LayoutParams(0, dp(48), 1.4f))
        val btnsLp = LinearLayout.LayoutParams(MATCH, WRAP)
        btnsLp.topMargin = dp(18)
        root.addView(btns, btnsLp)

        var task: Updater.Task? = null
        var apk: File? = null

        fun mb(b: Long) = String.format(Locale.US, "%.1f", b / 1048576.0)

        later.setOnClickListener {
            task?.cancelled = true
            dialog.dismiss()
        }

        update.setOnClickListener {
            val ready = apk
            if (ready != null) {
                Updater.install(a, ready)
                return@setOnClickListener
            }
            if (task != null) return@setOnClickListener
            prog.visibility = View.VISIBLE
            progLabel.text = "Downloading update..."
            bar.progress = 0
            progPct.text = "0%"
            progMb.text = "0.0 MB"
            later.text = "Cancel"
            update.text = "Downloading..."
            update.alpha = 0.6f
            dialog.setCancelable(false)
            task = Updater.download(a, info.apkUrl, { pct, got, total ->
                if (dialog.isShowing) {
                    bar.progress = pct
                    progPct.text = "$pct%"
                    progMb.text = if (total > 0) mb(got) + " / " + mb(total) + " MB" else mb(got) + " MB"
                }
            }, { f ->
                if (dialog.isShowing) {
                    task = null
                    update.alpha = 1f
                    dialog.setCancelable(true)
                    later.text = "Later"
                    if (f == null) {
                        progLabel.text = "Download failed"
                        update.text = "Retry"
                        Toast.makeText(a, "Download failed. Check your internet.", Toast.LENGTH_SHORT).show()
                    } else {
                        apk = f
                        progLabel.text = "Download complete"
                        update.text = "Install"
                        Updater.install(a, f)
                    }
                }
            })
        }

        dialog.setContentView(root)
        ScreenGuard.secure(dialog.window)
        dialog.setOnDismissListener {
            task?.cancelled = true
            Updater.dialogOpen = false
            Updater.dialog = null
        }
        Updater.dialogOpen = true
        Updater.dialog = dialog
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
        dialog.window?.setLayout((a.resources.displayMetrics.widthPixels * 0.92f).toInt(), WRAP)
    }
}
