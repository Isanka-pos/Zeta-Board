package com.zeta.board

import android.animation.ValueAnimator
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.Window
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * Premium, minimal progress bar: a slim rounded track and a cyan-to-purple fill.
 * [setProgress] takes 0..1 (the fill glides to it) or a negative value when the total size is unknown
 * (a short soft segment slides along the track; no made-up percentage is ever shown).
 * Its only animator runs while the view is on screen and only in the unknown-size mode.
 */
class SmoothBar(ctx: Context) : View(ctx) {

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rf = RectF()
    private var shown = 0f
    private var target = 0f
    private var unknown = false
    private var glide: ValueAnimator? = null
    private var slide: ValueAnimator? = null
    private var slideT = 0f
    private var fillShader: LinearGradient? = null
    private var shaderW = -1f

    /** Colour of the fill's far end; turns green when the download is complete. */
    private var endColor = Pal.PURPLE
    private var startColor = Pal.CYAN

    fun setProgress(v: Float) {
        if (v < 0f) {
            if (!unknown) {
                unknown = true
                startSlide()
                invalidate()
            }
            return
        }
        stopSlide()
        unknown = false
        val t = v.coerceIn(0f, 1f)
        if (t == target && glide != null) return
        target = t
        glide?.cancel()
        val g = ValueAnimator.ofFloat(shown, t)
        g.duration = 280
        g.interpolator = DecelerateInterpolator()
        g.addUpdateListener {
            shown = it.animatedValue as Float
            invalidate()
        }
        g.start()
        glide = g
    }

    /** Fills the bar and switches it to the green "done" colour. */
    fun complete() {
        stopSlide()
        unknown = false
        startColor = Pal.GREEN
        endColor = Pal.GREEN
        shaderW = -1f
        setProgress(1f)
    }

    private fun startSlide() {
        if (slide != null || !isAttachedToWindow) return
        val a = ValueAnimator.ofFloat(0f, 1f)
        a.duration = 1400
        a.repeatCount = ValueAnimator.INFINITE
        a.interpolator = LinearInterpolator()
        a.addUpdateListener {
            slideT = it.animatedValue as Float
            invalidate()
        }
        a.start()
        slide = a
    }

    private fun stopSlide() {
        slide?.cancel()
        slide = null
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (unknown) startSlide()
    }

    override fun onDetachedFromWindow() {
        glide?.cancel()
        glide = null
        stopSlide()
        super.onDetachedFromWindow()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val r = h / 2f
        p.shader = null
        p.color = Color.parseColor("#26204A")
        rf.set(0f, 0f, w, h)
        c.drawRoundRect(rf, r, r, p)

        val l: Float
        val rt: Float
        if (unknown) {
            val seg = w * 0.34f
            val pos = abs(slideT * 2f - 1f)
            l = (w - seg) * (1f - pos)
            rt = l + seg
        } else {
            l = 0f
            rt = (w * shown).coerceAtLeast(if (shown > 0f) h else 0f)
            if (rt <= 0f) return
        }
        if (shaderW != w) {
            fillShader = LinearGradient(0f, 0f, w, 0f, startColor, endColor, Shader.TileMode.CLAMP)
            shaderW = w
        }
        p.shader = fillShader
        rf.set(l, 0f, rt, h)
        c.drawRoundRect(rf, r, r, p)
        p.shader = null
    }
}

/**
 * A small download arrow with one calm movement: it drifts down a few dp and fades back up, in a loop,
 * above a fixed base line. [setActive] false stops the animator (it is also stopped when the view leaves the screen).
 */
class DownloadArrowView(ctx: Context) : View(ctx) {

    private val d = ctx.resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arrow = Path()
    private var t = 0f
    private var anim: ValueAnimator? = null
    private var active = true

    init {
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        p.strokeJoin = Paint.Join.ROUND
        p.strokeWidth = 2.4f * d
        p.color = Pal.CYAN
    }

    fun setActive(on: Boolean) {
        active = on
        if (on) start() else stop()
    }

    private fun start() {
        if (anim != null || !active || !isAttachedToWindow) return
        val a = ValueAnimator.ofFloat(0f, 1f)
        a.duration = 1500
        a.repeatCount = ValueAnimator.INFINITE
        a.interpolator = LinearInterpolator()
        a.addUpdateListener {
            t = it.animatedValue as Float
            invalidate()
        }
        a.start()
        anim = a
    }

    private fun stop() {
        anim?.cancel()
        anim = null
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        start()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val cx = w / 2f
        val s = minOf(w, h)
        // base line
        p.alpha = 150
        c.drawLine(cx - s * 0.30f, h * 0.86f, cx + s * 0.30f, h * 0.86f, p)
        // arrow drifts down and fades in at the top again
        val e = if (active) t else 0.4f
        val dy = (e - 0.5f) * s * 0.16f
        p.alpha = (255 * (1f - abs(e * 2f - 1f) * 0.55f)).toInt().coerceIn(80, 255)
        arrow.reset()
        arrow.moveTo(cx, h * 0.14f + dy)
        arrow.lineTo(cx, h * 0.60f + dy)
        arrow.moveTo(cx - s * 0.20f, h * 0.42f + dy)
        arrow.lineTo(cx, h * 0.62f + dy)
        arrow.lineTo(cx + s * 0.20f, h * 0.42f + dy)
        c.drawPath(arrow, p)
    }
}

/**
 * A circle that draws its tick (or, for [error], a cross) once, with a short stroke animation.
 * Draws with plain paths, so it costs next to nothing.
 */
class StatusMarkView(ctx: Context) : View(ctx) {

    private val d = ctx.resources.displayMetrics.density
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mark = Paint(Paint.ANTI_ALIAS_FLAG)
    private val full = Path()
    private val part = Path()
    private val rf = RectF()
    private var anim: ValueAnimator? = null
    private var f = 0f
    private var color = Pal.GREEN
    private var error = false

    init {
        ring.style = Paint.Style.STROKE
        ring.strokeWidth = 2.4f * d
        mark.style = Paint.Style.STROKE
        mark.strokeWidth = 3.2f * d
        mark.strokeCap = Paint.Cap.ROUND
        mark.strokeJoin = Paint.Join.ROUND
    }

    /** Plays the tick (or cross) animation from the start. */
    fun play(isError: Boolean) {
        error = isError
        color = if (isError) Color.parseColor("#F87171") else Pal.GREEN
        anim?.cancel()
        f = 0f
        val a = ValueAnimator.ofFloat(0f, 1f)
        a.duration = 520
        a.interpolator = DecelerateInterpolator()
        a.addUpdateListener {
            f = it.animatedValue as Float
            invalidate()
        }
        a.start()
        anim = a
    }

    override fun onDetachedFromWindow() {
        anim?.cancel()
        anim = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val s = minOf(w, h)
        val cx = w / 2f
        val cy = h / 2f
        val rad = s / 2f - ring.strokeWidth
        // ring first (first 60% of the animation), mark afterwards
        val ringF = (f / 0.6f).coerceIn(0f, 1f)
        val markF = ((f - 0.4f) / 0.6f).coerceIn(0f, 1f)
        ring.color = color
        ring.alpha = 255
        rf.set(cx - rad, cy - rad, cx + rad, cy + rad)
        c.drawArc(rf, -90f, 360f * ringF, false, ring)
        if (markF <= 0f) return
        full.reset()
        if (error) {
            full.moveTo(cx - rad * 0.34f, cy - rad * 0.34f)
            full.lineTo(cx + rad * 0.34f, cy + rad * 0.34f)
            full.moveTo(cx + rad * 0.34f, cy - rad * 0.34f)
            full.lineTo(cx - rad * 0.34f, cy + rad * 0.34f)
        } else {
            full.moveTo(cx - rad * 0.42f, cy + rad * 0.02f)
            full.lineTo(cx - rad * 0.10f, cy + rad * 0.34f)
            full.lineTo(cx + rad * 0.44f, cy - rad * 0.30f)
        }
        mark.color = color
        if (markF >= 1f) {
            c.drawPath(full, mark)
        } else {
            // draw only the first part of the path: the tick "writes" itself
            val pm = PathMeasure(full, false)
            part.reset()
            var left = pm.length * markF
            do {
                val len = pm.length
                if (left <= 0f) break
                pm.getSegment(0f, minOf(left, len), part, true)
                left -= len
            } while (pm.nextContour())
            c.drawPath(part, mark)
        }
    }
}

/**
 * The window shown while a live wallpaper is downloaded and applied: one dark rounded card with the wallpaper's
 * picture and name and one of these stages:
 *   download  - real percent, MB done / total, a smooth bar, Cancel
 *   complete  - tick + "DOWNLOAD COMPLETE" (only after the file passed the checks)
 *   applying  - "APPLYING LIVE WALLPAPER"
 *   applied   - tick + "LIVE WALLPAPER APPLIED"
 *   error     - friendly message with Retry and Close (the old wallpaper stays untouched)
 * Every method must be called on the main thread. Nothing here touches the network.
 */
internal class ApplyWindow(
    private val act: Activity,
    private val wp: Wallpaper,
    private val onCancel: () -> Unit,
    private val onRetry: () -> Unit,
    private val onClose: () -> Unit
) {
    private enum class Stage { DOWNLOAD, COMPLETE, APPLYING, APPLIED, ERROR }

    private val dialog = Dialog(act)
    private val card: LinearLayout
    private val thumb = ImageView(act)
    private val heading: TextView
    private val nameTv: TextView
    private val iconBox: FrameLayout
    private val arrow: DownloadArrowView
    private val mark: StatusMarkView
    private val pct: TextView
    private val bar: SmoothBar
    private val status: TextView
    private val btnRow: LinearLayout
    private val primaryBtn: TextView
    private val secondaryBtn: TextView

    private var stage = Stage.DOWNLOAD
    private var shownPct = -1
    private var closed = false
    private val ui = android.os.Handler(android.os.Looper.getMainLooper())
    private val autoClose = Runnable { finish() }

    private fun dp(v: Int): Int = (v * act.resources.displayMetrics.density + 0.5f).toInt()

    private fun label(s: String, size: Float, color: Int, bold: Boolean): TextView {
        val t = TextView(act)
        t.text = s
        t.textSize = size
        t.setTextColor(color)
        if (bold) t.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        t.includeFontPadding = false
        return t
    }

    private fun pill(text: String, filled: Boolean): TextView {
        val t = label(text, 14f, Color.WHITE, true)
        t.gravity = Gravity.CENTER
        t.setPadding(dp(22), dp(12), dp(22), dp(12))
        val g = if (filled) {
            GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Pal.CYAN, Pal.PURPLE))
        } else {
            GradientDrawable().also { it.setColor(Color.parseColor("#221C45")) }
        }
        g.cornerRadius = dp(14).toFloat()
        t.background = g
        if (filled) t.setTextColor(Color.parseColor("#0C0919"))
        return t
    }

    init {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)
        // Back while downloading = Cancel; Back at any other moment is ignored (the apply step is very short)
        dialog.setOnKeyListener { _, code, ev ->
            if (code == KeyEvent.KEYCODE_BACK && ev.action == KeyEvent.ACTION_UP) {
                if (stage == Stage.DOWNLOAD) cancelPressed() else if (stage == Stage.ERROR) finish()
                true
            } else false
        }

        card = LinearLayout(act)
        card.orientation = LinearLayout.VERTICAL
        card.gravity = Gravity.CENTER_HORIZONTAL
        card.setPadding(dp(24), dp(26), dp(24), dp(22))
        val bg = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Pal.BG2, Pal.BG))
        bg.cornerRadius = dp(28).toFloat()
        bg.setStroke(dp(1), Pal.STROKE)
        card.background = bg

        heading = label("Downloading Live Wallpaper", 13f, Pal.MUTED, true)
        heading.gravity = Gravity.CENTER
        heading.letterSpacing = 0.04f
        card.addView(heading, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // wallpaper picture (the one the store already has in memory; no extra decoding)
        thumb.scaleType = ImageView.ScaleType.CENTER_CROP
        thumb.setBackgroundColor(Pal.TILE)
        thumb.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) {
                o.setRoundRect(0, 0, v.width, v.height, dp(18).toFloat())
            }
        }
        thumb.clipToOutline = true
        val tlp = LinearLayout.LayoutParams(dp(112), dp(112))
        tlp.topMargin = dp(18)
        card.addView(thumb, tlp)

        nameTv = label(wp.name.uppercase(), 19f, Color.WHITE, true)
        nameTv.gravity = Gravity.CENTER
        nameTv.letterSpacing = 0.05f
        nameTv.setSingleLine(true)
        nameTv.ellipsize = TextUtils.TruncateAt.END
        val nlp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        nlp.topMargin = dp(16)
        card.addView(nameTv, nlp)

        // icon slot: arrow while downloading, tick / cross afterwards
        iconBox = FrameLayout(act)
        arrow = DownloadArrowView(act)
        mark = StatusMarkView(act)
        mark.visibility = View.GONE
        iconBox.addView(arrow, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER))
        iconBox.addView(mark, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.CENTER))
        val ilp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48))
        ilp.topMargin = dp(10)
        card.addView(iconBox, ilp)

        pct = label("", 34f, Pal.CYAN, true)
        pct.gravity = Gravity.CENTER
        val plp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        plp.topMargin = dp(6)
        card.addView(pct, plp)

        bar = SmoothBar(act)
        val blp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8))
        blp.topMargin = dp(14)
        card.addView(bar, blp)

        status = label("Starting...", 13f, Pal.MUTED, false)
        status.gravity = Gravity.CENTER
        val slp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        slp.topMargin = dp(14)
        card.addView(status, slp)

        btnRow = LinearLayout(act)
        btnRow.orientation = LinearLayout.HORIZONTAL
        btnRow.gravity = Gravity.CENTER
        secondaryBtn = pill("Cancel", false)
        primaryBtn = pill("Retry", true)
        val b1 = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        b1.rightMargin = dp(6)
        val b2 = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        b2.leftMargin = dp(6)
        btnRow.addView(secondaryBtn, b1)
        btnRow.addView(primaryBtn, b2)
        primaryBtn.visibility = View.GONE
        secondaryBtn.setOnClickListener {
            when (stage) {
                Stage.DOWNLOAD -> cancelPressed()
                Stage.ERROR -> finish()
                else -> {}
            }
        }
        primaryBtn.setOnClickListener {
            if (stage == Stage.ERROR) {
                onRetry()
            }
        }
        val rlp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        rlp.topMargin = dp(20)
        card.addView(btnRow, rlp)

        dialog.setContentView(card)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setDimAmount(0.6f)
        val w = (act.resources.displayMetrics.widthPixels * 0.88f).toInt().coerceAtMost(dp(360))
        dialog.window?.setLayout(w, ViewGroup.LayoutParams.WRAP_CONTENT)

        val have = WallpaperStore.peekThumb(wp.id)
        if (have != null) {
            thumb.setImageBitmap(have)
        } else {
            WallpaperStore.loadThumb(act, wp) { b: Bitmap? ->
                if (b != null && !closed) thumb.setImageBitmap(b)
            }
        }
        enterDownload(true)
    }

    private fun alive(): Boolean = !act.isFinishing && !act.isDestroyed

    private fun cancelPressed() {
        secondaryBtn.isEnabled = false
        secondaryBtn.alpha = 0.5f
        status.text = "Cancelling..."
        onCancel()
    }

    fun show() {
        if (!alive()) return
        try {
            dialog.show()
            // subtle fade + scale in
            card.alpha = 0f
            card.scaleX = 0.94f
            card.scaleY = 0.94f
            card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220)
                .setInterpolator(DecelerateInterpolator()).start()
        } catch (e: Exception) {
        }
    }

    // ------------------------------------------------------------ stages

    private fun swapText(tv: TextView, s: String) {
        if (tv.text.toString() == s) return
        tv.text = s
    }

    private fun enterDownload(first: Boolean) {
        stage = Stage.DOWNLOAD
        shownPct = -1
        heading.text = "Downloading Live Wallpaper"
        heading.setTextColor(Pal.MUTED)
        mark.visibility = View.GONE
        arrow.visibility = View.VISIBLE
        arrow.setActive(true)
        pct.visibility = View.VISIBLE
        pct.setTextColor(Pal.CYAN)
        pct.text = ""
        bar.visibility = View.VISIBLE
        bar.setProgress(-1f)
        status.text = if (first) "Connecting..." else "Trying again..."
        status.setTextColor(Pal.MUTED)
        btnRow.visibility = View.VISIBLE
        primaryBtn.visibility = View.GONE
        secondaryBtn.text = "Cancel"
        secondaryBtn.isEnabled = true
        secondaryBtn.alpha = 1f
    }

    /** A new attempt after an error (Retry). */
    fun restart() {
        if (closed) return
        enterDownload(false)
    }

    /** Real download progress. [total] <= 0 means the server did not tell the size. */
    fun progress(done: Long, total: Long) {
        if (closed || stage != Stage.DOWNLOAD) return
        if (total > 0L) {
            val p = (done * 1000L / total).toInt().coerceIn(0, 1000)
            bar.setProgress(p / 1000f)
            val whole = p / 10
            if (whole != shownPct) {
                shownPct = whole
                pct.text = "$whole%"
            }
            swapText(status, "Downloading... " + mb(done) + " / " + mb(total))
        } else {
            pct.text = ""
            bar.setProgress(-1f)
            swapText(status, "Downloading... " + mb(done))
        }
    }

    private fun mb(b: Long): String = String.format("%.1f MB", b / 1048576.0)

    /** The file is fully downloaded and passed the checks. */
    fun downloaded() {
        if (closed) return
        stage = Stage.COMPLETE
        arrow.setActive(false)
        arrow.visibility = View.GONE
        pct.visibility = View.GONE
        bar.complete()
        heading.text = "Download complete"
        heading.setTextColor(Pal.GREEN)
        status.text = "Your wallpaper is ready"
        btnRow.visibility = View.GONE
        mark.visibility = View.VISIBLE
        mark.play(false)
    }

    fun applying() {
        if (closed) return
        stage = Stage.APPLYING
        arrow.setActive(false)
        arrow.visibility = View.GONE
        pct.visibility = View.GONE
        btnRow.visibility = View.GONE
        heading.text = "APPLYING LIVE WALLPAPER"
        heading.setTextColor(Pal.CYAN)
        status.text = "Setting up your keyboard..."
        mark.visibility = View.GONE
        bar.visibility = View.VISIBLE
        bar.setProgress(-1f)
    }

    fun applied() {
        if (closed) return
        stage = Stage.APPLIED
        bar.complete()
        heading.text = "LIVE WALLPAPER APPLIED"
        heading.setTextColor(Pal.GREEN)
        status.text = wp.name
        mark.visibility = View.VISIBLE
        mark.play(false)
        // close by itself after a short moment, then hand control back
        ui.postDelayed(autoClose, 1100)
    }

    fun error(message: String, canRetry: Boolean) {
        if (closed) return
        stage = Stage.ERROR
        arrow.setActive(false)
        arrow.visibility = View.GONE
        pct.visibility = View.GONE
        bar.visibility = View.GONE
        heading.text = "Something went wrong"
        heading.setTextColor(Color.parseColor("#F87171"))
        status.text = message
        status.setTextColor(Color.parseColor("#D8D2F0"))
        mark.visibility = View.VISIBLE
        mark.play(true)
        btnRow.visibility = View.VISIBLE
        secondaryBtn.text = "Close"
        secondaryBtn.isEnabled = true
        secondaryBtn.alpha = 1f
        primaryBtn.visibility = if (canRetry) View.VISIBLE else View.GONE
    }

    /** Dismisses the window and tells the caller it is over. Safe to call twice. */
    private fun finish() {
        if (closed) return
        dismiss()
        onClose()
    }

    /** Dismisses the window only (used when the screen is closed). */
    fun dismiss() {
        if (closed) return
        closed = true
        ui.removeCallbacksAndMessages(null)
        arrow.setActive(false)
        try {
            if (dialog.isShowing) dialog.dismiss()
        } catch (e: Exception) {
        }
    }
}
