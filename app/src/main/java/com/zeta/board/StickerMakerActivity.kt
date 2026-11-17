package com.zeta.board

import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.concurrent.Executors

/**
 * The Sticker Maker inside the app (the same engine as the sticker panel on the keyboard).
 * Make a sticker from a photo or from a copied image, see all your stickers, send, share or delete them.
 */
class StickerMakerActivity : Activity(), StickerHub.Listener {

    private val dens: Float by lazy { resources.displayMetrics.density }
    private fun dp(v: Int): Int = (v * dens).toInt()
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    private val COLS = 3

    private val ui = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var gen = 0
    private var highlight: File? = null
    private var prevListener: StickerHub.Listener? = null

    private lateinit var statusRow: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var countText: TextView
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Pal.BG)
        root.addView(topBar(), LinearLayout.LayoutParams(MATCH, dp(64)))

        val body = LinearLayout(this)
        body.orientation = LinearLayout.VERTICAL
        body.setPadding(dp(16), dp(4), dp(16), dp(28))
        body.addView(hero())
        body.addView(statusBox())

        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.setPadding(dp(4), 0, dp(4), 0)
        head.addView(tv("My stickers", 16f, Color.WHITE, true), LinearLayout.LayoutParams(0, WRAP, 1f))
        countText = tv("0", 13f, Pal.MUTED, true)
        head.addView(countText)
        val headLp = LinearLayout.LayoutParams(MATCH, WRAP)
        headLp.topMargin = dp(22)
        headLp.bottomMargin = dp(4)
        body.addView(head, headLp)
        body.addView(tv("Tap a sticker to send or share it", 12f, Pal.MUTED), LinearLayout.LayoutParams(MATCH, WRAP))

        list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        val listLp = LinearLayout.LayoutParams(MATCH, WRAP)
        listLp.topMargin = dp(8)
        body.addView(list, listLp)

        val sv = ScrollView(this)
        sv.isVerticalScrollBarEnabled = false
        sv.addView(body)
        root.addView(sv, LinearLayout.LayoutParams(MATCH, 0, 1f))
        setContentView(root)

        // Stay registered while the photo picker is on top, so the result always lands here.
        prevListener = StickerHub.listener
        StickerHub.listener = this
    }

    override fun onResume() {
        super.onResume()
        if (StickerHub.listener !== this) StickerHub.listener = this
        showStatus(if (StickerHub.busy) StickerHub.status else null)
        render()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        render()
    }

    override fun onDestroy() {
        gen++
        if (StickerHub.listener === this) StickerHub.listener = prevListener
        prevListener = null
        io.shutdown()
        super.onDestroy()
    }

    // ---------- StickerHub.Listener ----------

    override fun onStatus(text: String) {
        showStatus(text)
    }

    override fun onDone(f: File, note: String?) {
        StickerHub.pendingShow = false
        showStatus(null)
        highlight = f
        render()
        Toast.makeText(this, if (note != null) "Sticker ready \u2022 $note" else "Sticker ready \u2014 tap it to send", Toast.LENGTH_LONG).show()
    }

    override fun onFail(msg: String) {
        showStatus(null)
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    override fun onIdle() {
        showStatus(null)
    }

    // ---------- actions ----------

    private fun pickPhoto() {
        if (StickerHub.busy) {
            Toast.makeText(this, "A sticker is being made, please wait", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(Intent(this, StickerActivity::class.java))
        } catch (e: Exception) {
            Toast.makeText(this, "Could not open the photo picker", Toast.LENGTH_SHORT).show()
        }
    }

    private fun useClipboard() {
        if (StickerHub.busy) {
            Toast.makeText(this, "A sticker is being made, please wait", Toast.LENGTH_SHORT).show()
            return
        }
        var uri: Uri? = null
        try {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            val clip = cm.primaryClip
            if (clip != null) {
                for (i in 0 until clip.itemCount) {
                    val u = clip.getItemAt(i).uri ?: continue
                    val type = contentResolver.getType(u) ?: ""
                    if (type.startsWith("image/")) {
                        uri = u
                        break
                    }
                }
            }
        } catch (e: Exception) {
        }
        if (uri == null) {
            Toast.makeText(this, "No copied image found \u2014 copy a photo first", Toast.LENGTH_SHORT).show()
            return
        }
        StickerHub.submitUri(this, uri)
    }

    private fun sendTo(f: File, pkg: String?) {
        try {
            val uri = StickerSender.uriFor(this, f)
            val send = Intent(Intent.ACTION_SEND)
            send.type = "image/webp"
            send.putExtra(Intent.EXTRA_STREAM, uri)
            send.clipData = ClipData.newRawUri("Sticker", uri)
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (pkg != null) {
                send.setPackage(pkg)
                startActivity(send)
            } else {
                startActivity(Intent.createChooser(send, "Send sticker"))
            }
        } catch (e: Exception) {
            Toast.makeText(this, if (pkg != null) "WhatsApp is not installed" else "Could not share the sticker", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showStatus(text: String?) {
        if (text == null) {
            statusRow.visibility = View.GONE
        } else {
            statusText.text = text
            statusRow.visibility = View.VISIBLE
        }
    }

    // ---------- screen parts ----------

    private fun topBar(): View {
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setPadding(dp(12), 0, dp(18), 0)

        val back = ImageView(this)
        back.setImageResource(R.drawable.ic_back)
        back.setColorFilter(Color.WHITE)
        back.setPadding(dp(11), dp(11), dp(11), dp(11))
        back.background = RippleDrawable(ColorStateList.valueOf(Color.argb(60, 168, 85, 247)), roundBg(Pal.CARD, 23, Pal.STROKE, 1), null)
        back.contentDescription = "Back"
        back.setOnClickListener { finish() }
        bar.addView(back, LinearLayout.LayoutParams(dp(46), dp(46)))

        val t = LinearLayout(this)
        t.orientation = LinearLayout.VERTICAL
        t.setPadding(dp(14), 0, 0, 0)
        t.addView(tv("Sticker Maker", 20f, Color.WHITE, true))
        t.addView(tv("Photo to WhatsApp sticker", 12f, Pal.MUTED))
        bar.addView(t, LinearLayout.LayoutParams(0, WRAP, 1f))
        return bar
    }

    private fun hero(): View {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.setPadding(dp(18), dp(18), dp(18), dp(18))
        c.background = roundBg(Pal.CARD, 24, Pal.STROKE, 1)
        c.addView(tv("Create a sticker", 17f, Color.WHITE, true))
        val d = tv("Pick a photo or use an image you copied. It becomes a 512x512 WebP sticker that works in WhatsApp.", 12.5f, Pal.MUTED)
        d.setPadding(0, dp(4), 0, 0)
        c.addView(d)

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        val photo = actionPill("Photo", R.drawable.ic_image, true) { pickPhoto() }
        val clip = actionPill("Clipboard", R.drawable.ic_paste, false) { useClipboard() }
        val l1 = LinearLayout.LayoutParams(0, WRAP, 1f)
        l1.rightMargin = dp(10)
        row.addView(photo, l1)
        row.addView(clip, LinearLayout.LayoutParams(0, WRAP, 1f))
        val rlp = LinearLayout.LayoutParams(MATCH, WRAP)
        rlp.topMargin = dp(16)
        c.addView(row, rlp)

        val lp = LinearLayout.LayoutParams(MATCH, WRAP)
        c.layoutParams = lp
        return c
    }

    private fun statusBox(): View {
        statusRow = LinearLayout(this)
        statusRow.orientation = LinearLayout.HORIZONTAL
        statusRow.gravity = Gravity.CENTER_VERTICAL
        statusRow.setPadding(dp(16), dp(12), dp(16), dp(12))
        statusRow.background = roundBg(Color.argb(50, 168, 85, 247), 16, Pal.PURPLE, 1)
        statusRow.visibility = View.GONE
        val spin = ProgressBar(this, null, android.R.attr.progressBarStyleSmall)
        spin.indeterminateTintList = ColorStateList.valueOf(Pal.CYAN)
        statusRow.addView(spin, LinearLayout.LayoutParams(dp(20), dp(20)))
        statusText = tv("", 13f, Color.WHITE, true)
        statusText.setPadding(dp(12), 0, 0, 0)
        statusRow.addView(statusText, LinearLayout.LayoutParams(0, WRAP, 1f))
        val lp = LinearLayout.LayoutParams(MATCH, WRAP)
        lp.topMargin = dp(14)
        statusRow.layoutParams = lp
        return statusRow
    }

    // ---------- sticker grid ----------

    private fun render() {
        val files = StickerRepo.list(this)
        list.removeAllViews()
        countText.text = files.size.toString()
        if (files.isEmpty()) {
            val e = tv("No stickers yet\n\nTap Photo to choose a picture, or copy an image and tap Clipboard.", 13f, Pal.MUTED)
            e.gravity = Gravity.CENTER
            e.setPadding(dp(24), dp(24), dp(24), dp(24))
            list.addView(e, LinearLayout.LayoutParams(MATCH, dp(180)))
            return
        }
        val cellH = (resources.displayMetrics.widthPixels - dp(32)) / COLS
        val cells = ArrayList<ImageView>()
        var row: LinearLayout? = null
        files.forEachIndexed { i, f ->
            if (i % COLS == 0) {
                val r = LinearLayout(this)
                r.orientation = LinearLayout.HORIZONTAL
                list.addView(r, LinearLayout.LayoutParams(MATCH, cellH))
                row = r
            }
            val iv = cell(f)
            row!!.addView(iv, cellLp())
            cells.add(iv)
        }
        val rem = files.size % COLS
        if (rem != 0) repeat(COLS - rem) { row!!.addView(View(this), cellLp()) }

        val g = ++gen
        try {
            io.execute {
                val bmps = files.map { decodeThumb(it) }
                ui.post {
                    if (g == gen && !isFinishing && !isDestroyed) cells.forEachIndexed { i, iv -> iv.setImageBitmap(bmps[i]) }
                }
            }
        } catch (e: Exception) {
        }
    }

    private fun cellLp(): LinearLayout.LayoutParams {
        val lp = LinearLayout.LayoutParams(0, MATCH, 1f)
        lp.setMargins(dp(4), dp(4), dp(4), dp(4))
        return lp
    }

    private fun cell(f: File): ImageView {
        val iv = ImageView(this)
        iv.scaleType = ImageView.ScaleType.FIT_CENTER
        iv.setPadding(dp(8), dp(8), dp(8), dp(8))
        val mark = f == highlight
        iv.background = roundBg(Color.argb(18, 255, 255, 255), 16, if (mark) Pal.CYAN else Pal.STROKE, if (mark) 2 else 1)
        iv.setOnClickListener { showSticker(f) }
        return iv
    }

    private fun decodeThumb(f: File): Bitmap? = try {
        val o = BitmapFactory.Options()
        o.inSampleSize = 2
        BitmapFactory.decodeFile(f.path, o)
    } catch (e: Throwable) {
        null
    }

    // ---------- one sticker ----------

    private fun showSticker(f: File) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setCancelable(true)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.gravity = Gravity.CENTER_HORIZONTAL
        root.setPadding(dp(20), dp(22), dp(20), dp(20))
        root.background = roundBg(Pal.CARD, 28, Pal.PURPLE, 1)

        val img = ImageView(this)
        img.scaleType = ImageView.ScaleType.FIT_CENTER
        img.setPadding(dp(12), dp(12), dp(12), dp(12))
        img.background = roundBg(Pal.BG, 20, Pal.STROKE, 1)
        try {
            img.setImageBitmap(BitmapFactory.decodeFile(f.path))
        } catch (e: Throwable) {
        }
        root.addView(img, LinearLayout.LayoutParams(dp(220), dp(220)))

        val t = tv("Your sticker", 16f, Color.WHITE, true)
        t.gravity = Gravity.CENTER
        val tlp = LinearLayout.LayoutParams(MATCH, WRAP)
        tlp.topMargin = dp(14)
        root.addView(t, tlp)
        val kb = (f.length() / 1024L).toString()
        val info = tv("512 x 512 WebP \u2022 $kb KB", 12f, Pal.MUTED)
        info.gravity = Gravity.CENTER
        root.addView(info, LinearLayout.LayoutParams(MATCH, WRAP))

        val wa = pillRow("WhatsApp", R.drawable.ic_whatsapp, roundBg(Color.parseColor("#25D366"), 16), false)
        wa.setOnClickListener {
            sendTo(f, "com.whatsapp")
        }
        val share = pillRow("Share", R.drawable.ic_copy, gradient(16), true)
        share.setOnClickListener {
            sendTo(f, null)
        }
        val row1 = LinearLayout(this)
        row1.orientation = LinearLayout.HORIZONTAL
        val a1 = LinearLayout.LayoutParams(0, dp(46), 1f)
        a1.rightMargin = dp(10)
        row1.addView(wa, a1)
        row1.addView(share, LinearLayout.LayoutParams(0, dp(46), 1f))
        val r1lp = LinearLayout.LayoutParams(MATCH, WRAP)
        r1lp.topMargin = dp(16)
        root.addView(row1, r1lp)

        val del = pillRow("Delete", R.drawable.ic_delete, roundBg(Pal.BG, 16, Pal.STROKE, 1), true)
        val delLabel = del.getChildAt(1) as TextView
        var armed = false
        del.setOnClickListener {
            if (!armed) {
                armed = true
                delLabel.text = "Tap again"
                delLabel.setTextColor(Color.parseColor("#F87171"))
                ui.postDelayed({
                    armed = false
                    delLabel.text = "Delete"
                    delLabel.setTextColor(Color.WHITE)
                }, 2500L)
            } else {
                StickerRepo.remove(f)
                if (highlight == f) highlight = null
                dialog.dismiss()
                render()
                Toast.makeText(this, "Sticker deleted", Toast.LENGTH_SHORT).show()
            }
        }
        val close = pillRow("Close", R.drawable.ic_close, roundBg(Pal.BG, 16, Pal.STROKE, 1), true)
        close.setOnClickListener { dialog.dismiss() }
        val row2 = LinearLayout(this)
        row2.orientation = LinearLayout.HORIZONTAL
        val a2 = LinearLayout.LayoutParams(0, dp(46), 1f)
        a2.rightMargin = dp(10)
        row2.addView(del, a2)
        row2.addView(close, LinearLayout.LayoutParams(0, dp(46), 1f))
        val r2lp = LinearLayout.LayoutParams(MATCH, WRAP)
        r2lp.topMargin = dp(10)
        root.addView(row2, r2lp)

        dialog.setContentView(root)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.86f).toInt(), WRAP)
    }

    // ---------- small helpers ----------

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

    private fun gradient(radius: Int): GradientDrawable {
        val g = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Pal.CYAN, Pal.PURPLE))
        g.cornerRadius = dp(radius).toFloat()
        return g
    }

    /** A rounded button with an icon and a label, both centered. Child 0 is the icon, child 1 the label. */
    private fun pillRow(text: String, icon: Int, bg: GradientDrawable, tint: Boolean): LinearLayout {
        val r = LinearLayout(this)
        r.orientation = LinearLayout.HORIZONTAL
        r.gravity = Gravity.CENTER
        r.background = bg
        val ic = ImageView(this)
        ic.setImageResource(icon)
        if (tint) ic.setColorFilter(Color.WHITE)
        r.addView(ic, LinearLayout.LayoutParams(dp(18), dp(18)))
        val label = tv(text, 14f, Color.WHITE, true)
        label.setPadding(dp(8), 0, 0, 0)
        r.addView(label)
        return r
    }

    private fun actionPill(text: String, icon: Int, primary: Boolean, onClick: () -> Unit): LinearLayout {
        val r = pillRow(text, icon, if (primary) gradient(16) else roundBg(Pal.BG, 16, Pal.STROKE, 1), true)
        r.setPadding(dp(14), dp(13), dp(14), dp(13))
        r.setOnClickListener { onClick() }
        return r
    }
}
