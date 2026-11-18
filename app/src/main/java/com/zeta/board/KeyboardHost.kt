package com.zeta.board

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.view.View
import java.io.File
import android.widget.LinearLayout

/**
 * Container used by the IME and by the in-app preview: the normal keyboard plus the clipboard panel.
 * All text goes through [Output], so both places behave the same.
 */
class KeyboardHost(ctx: Context, private val out: Output) : LinearLayout(ctx), KeyboardView.Listener, ClipPanel.Callback, StickerPanel.Callback, QuickPanel.Callback {

    interface Output {
        fun commit(s: String)
        fun del()
        fun enter()
        fun cursor(dir: Int)
        fun settings()

        /** Sends a sticker into the current app. Returns false when that app does not accept stickers. */
        fun sendSticker(f: File): Boolean = false

        /** Fallback: let the user share the sticker with another app. */
        fun shareSticker(f: File) {}

        /** Opens the photo picker used by the sticker maker. */
        fun pickPhoto() {}

        /** Quick Text: replaces a just-typed shortcut before the cursor with its message. True when it did. */
        fun expand(delim: String): Boolean = false

        /** Quick Text: inserts a saved message into the focused field. False when there is no field or it is private. */
        fun insertQuick(text: String): Boolean = false

        /** True when Quick Text may be used in the current field (false for password / private fields). */
        fun quickAllowed(): Boolean = true

        /** Opens the Quick Text Manager screen. */
        fun openQuickManager() {}
    }

    val kv = KeyboardView(ctx)
    private val vm = ClipViewModel()
    private val panel = ClipPanel(ctx, vm, this)
    private val stickers = StickerPanel(ctx, this)
    private val quick = QuickPanel(ctx, this)
    private var panelOpen = false
    private var stickersOpen = false
    private var quickOpen = false
    private var typing = false
    /** Tells the keyboard service when a private panel (clipboard / stickers) is open, so its window can block screenshots. */
    var onSecure: ((Boolean) -> Unit)? = null
    private fun syncSecure() { onSecure?.invoke(panelOpen || stickersOpen || quickOpen) }
    private val d = ctx.resources.displayMetrics.density

    init {
        orientation = VERTICAL
        kv.listener = this
        kv.onTheme = {
            panel.applyTheme(it)
            stickers.applyTheme(it)
            quick.applyTheme(it)
        }
        panel.applyTheme(kv.theme)
        stickers.applyTheme(kv.theme)
        quick.applyTheme(kv.theme)
        addView(panel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(stickers, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        stickers.visibility = GONE
        addView(quick, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        quick.visibility = GONE
        addView(kv, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        panel.visibility = GONE
    }

    private fun applyHeights() {
        val target: View = if (quickOpen) quick else panel
        val lp = target.layoutParams as LayoutParams
        lp.height = if (typing) (196 * d).toInt() else kv.preferredHeight()
        target.layoutParams = lp
        kv.visibility = if (typing) VISIBLE else GONE
    }

    fun openClipboard() {
        if (panelOpen) return
        closeQuick()
        panelOpen = true
        typing = false
        applyHeights()
        panel.visibility = VISIBLE
        panel.alpha = 0f
        panel.translationY = 24f * d
        panel.animate().alpha(1f).translationY(0f).setStartDelay(0).setDuration(200).start()
        kv.visibility = GONE
        panel.open()
        syncSecure()
    }

    fun closeClipboard() {
        if (!panelOpen) return
        panelOpen = false
        typing = false
        panel.animate().cancel()
        panel.close()
        panel.visibility = GONE
        kv.visibility = VISIBLE
        syncSecure()
    }

    // ---- quick text ----

    fun openQuick() {
        if (quickOpen) return
        closeClipboard()
        closeStickers()
        quickOpen = true
        typing = false
        applyHeights()
        quick.visibility = VISIBLE
        quick.alpha = 0f
        quick.translationY = 24f * d
        quick.animate().alpha(1f).translationY(0f).setStartDelay(0).setDuration(200).start()
        kv.visibility = GONE
        quick.open(!out.quickAllowed())
        syncSecure()
    }

    fun closeQuick() {
        if (!quickOpen) return
        quickOpen = false
        typing = false
        quick.animate().cancel()
        quick.close()
        quick.visibility = GONE
        kv.visibility = VISIBLE
        syncSecure()
    }

    override fun onQuickPick(text: String): Boolean {
        if (!out.insertQuick(text)) return false
        closeQuick()
        return true
    }

    override fun onQuickClose() {
        closeQuick()
    }

    override fun onQuickManage() {
        out.openQuickManager()
        closeQuick()
    }

    override fun onQuickTyping(active: Boolean) {
        typing = active
        if (active) kv.startInput(0, 0)
        applyHeights()
    }

    // ---- sticker maker ----

    private val hubListener = object : StickerHub.Listener {
        override fun onStatus(text: String) {
            stickers.setStatus(text)
        }

        override fun onDone(f: File, note: String?) {
            // if the keyboard is on screen the user sees the sticker right now, otherwise show it next time
            StickerHub.pendingShow = !(isAttachedToWindow && windowVisibility == View.VISIBLE)
            if (!stickersOpen) openStickers()
            stickers.onNew(f, note)
        }

        override fun onFail(msg: String) {
            stickers.setStatus(null)
            stickers.flash(msg)
        }

        override fun onIdle() {
            stickers.setStatus(null)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        StickerHub.listener = hubListener
    }

    override fun onDetachedFromWindow() {
        if (StickerHub.listener === hubListener) StickerHub.listener = null
        super.onDetachedFromWindow()
    }

    fun openStickers() {
        if (stickersOpen) return
        closeClipboard()
        closeQuick()
        StickerHub.pendingShow = false
        stickersOpen = true
        typing = false
        val lp = stickers.layoutParams as LayoutParams
        lp.height = kv.preferredHeight()
        stickers.layoutParams = lp
        stickers.visibility = VISIBLE
        stickers.alpha = 0f
        stickers.translationY = 24f * d
        stickers.animate().alpha(1f).translationY(0f).setStartDelay(0).setDuration(200).start()
        kv.visibility = GONE
        stickers.open()
        syncSecure()
    }

    fun closeStickers() {
        if (!stickersOpen) return
        stickersOpen = false
        stickers.animate().cancel()
        stickers.close()
        stickers.visibility = GONE
        kv.visibility = VISIBLE
        syncSecure()
    }

    /** Called every time the keyboard shows up: opens the sticker panel if a photo/sticker is waiting for it. */
    fun onStartInput() {
        if (StickerHub.pendingShow) openStickers()
    }

    override fun onPickPhoto() {
        StickerHub.pendingShow = true
        out.pickPhoto()
    }

    override fun onUseClipboard() {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        var uri: Uri? = null
        try {
            val clip = cm.primaryClip
            if (clip != null) {
                for (i in 0 until clip.itemCount) {
                    val u = clip.getItemAt(i).uri ?: continue
                    val type = context.contentResolver.getType(u) ?: ""
                    if (type.startsWith("image/")) {
                        uri = u
                        break
                    }
                }
            }
        } catch (e: Exception) {
        }
        if (uri == null) {
            stickers.flash("No copied image found \u2014 copy a photo first")
            return
        }
        StickerHub.submitUri(context, uri)
    }

    override fun onSend(f: File) {
        if (out.sendSticker(f)) {
            stickers.flash("Sticker sent")
        } else {
            stickers.flash("This app can't take stickers directly \u2014 choose where to send")
            out.shareSticker(f)
        }
    }

    private fun routed(): Boolean = (panelOpen || quickOpen) && typing

    // ---- KeyboardView.Listener ----

    override fun onText(s: String) {
        if (routed()) {
            if (quickOpen) quick.insert(s) else panel.insert(s)
            return
        }
        // Quick Text: a typed delimiter right after a shortcut expands it (the IME decides if it is allowed)
        if (s.length == 1 && QuickText.isDelimiter(s[0]) && out.expand(s)) return
        val st = Prefs.getI(context, "textstyle", 0)
        out.commit(if (st != 0) TextStyles.convert(st, s) else s)
    }

    override fun onAction(a: Int) {
        when (a) {
            KeyboardView.DEL -> if (routed()) (if (quickOpen) quick.backspace() else panel.backspace()) else out.del()
            KeyboardView.ENTER -> if (routed()) (if (quickOpen) quick.enter() else panel.enter()) else out.enter()
            KeyboardView.LEFT -> if (!routed()) out.cursor(-1)
            KeyboardView.RIGHT -> if (!routed()) out.cursor(1)
            KeyboardView.CLIPBOARD -> openClipboard()
            KeyboardView.STICKER -> openStickers()
            KeyboardView.QUICK -> openQuick()
            KeyboardView.SETTINGS -> out.settings()
        }
    }

    // ---- ClipPanel.Callback ----

    override fun onPaste(text: String) {
        out.commit(text)
        closeClipboard()
    }

    override fun onCopy(text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        try {
            cm.setPrimaryClip(ClipData.newPlainText("Zeta Board", text))
        } catch (e: Exception) {
        }
        ClipRepo.add(text, Prefs.getI(context, "clip_max", 100))
    }

    override fun onClose() {
        if (stickersOpen) closeStickers() else closeClipboard()
    }

    override fun onTyping(active: Boolean) {
        typing = active
        if (active) kv.startInput(0, 0)
        applyHeights()
    }
}

/** Keeps obviously sensitive copies (OTP codes, card numbers) out of the history. */
object ClipGuard {
    private val otp = Regex("^\\s*\\d{4,8}\\s*$")
    private val otpSplit = Regex("^\\s*\\d{3}[ -]\\d{3}\\s*$")

    fun looksSensitive(t: String): Boolean {
        if (otp.matches(t) || otpSplit.matches(t)) return true
        val digits = t.replace(" ", "").replace("-", "")
        if (digits.length in 13..19 && digits.all { it.isDigit() } && luhn(digits)) return true
        return false
    }

    private fun luhn(s: String): Boolean {
        var sum = 0
        var alt = false
        for (i in s.length - 1 downTo 0) {
            var n = s[i] - '0'
            if (alt) {
                n *= 2
                if (n > 9) n -= 9
            }
            sum += n
            alt = !alt
        }
        return sum % 10 == 0
    }
}
