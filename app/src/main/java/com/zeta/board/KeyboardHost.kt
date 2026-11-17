package com.zeta.board

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.LinearLayout

/**
 * Container used by the IME and by the in-app preview: the normal keyboard plus the clipboard panel.
 * All text goes through [Output], so both places behave the same.
 */
class KeyboardHost(ctx: Context, private val out: Output) : LinearLayout(ctx), KeyboardView.Listener, ClipPanel.Callback {

    interface Output {
        fun commit(s: String)
        fun del()
        fun enter()
        fun cursor(dir: Int)
        fun settings()
    }

    val kv = KeyboardView(ctx)
    private val vm = ClipViewModel()
    private val panel = ClipPanel(ctx, vm, this)
    private var panelOpen = false
    private var typing = false
    private val d = ctx.resources.displayMetrics.density

    init {
        orientation = VERTICAL
        kv.listener = this
        kv.onTheme = { panel.applyTheme(it) }
        panel.applyTheme(kv.theme)
        addView(panel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(kv, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        panel.visibility = GONE
    }

    private fun applyHeights() {
        val lp = panel.layoutParams as LayoutParams
        lp.height = if (typing) (196 * d).toInt() else kv.preferredHeight()
        panel.layoutParams = lp
        kv.visibility = if (typing) VISIBLE else GONE
    }

    fun openClipboard() {
        if (panelOpen) return
        panelOpen = true
        typing = false
        applyHeights()
        panel.visibility = VISIBLE
        panel.alpha = 0f
        panel.translationY = 24f * d
        panel.animate().alpha(1f).translationY(0f).setStartDelay(0).setDuration(200).start()
        kv.visibility = GONE
        panel.open()
    }

    fun closeClipboard() {
        if (!panelOpen) return
        panelOpen = false
        typing = false
        panel.animate().cancel()
        panel.close()
        panel.visibility = GONE
        kv.visibility = VISIBLE
    }

    private fun routed(): Boolean = panelOpen && typing

    // ---- KeyboardView.Listener ----

    override fun onText(s: String) {
        if (routed()) {
            panel.insert(s)
            return
        }
        val st = Prefs.getI(context, "textstyle", 0)
        out.commit(if (st != 0) TextStyles.convert(st, s) else s)
    }

    override fun onAction(a: Int) {
        when (a) {
            KeyboardView.DEL -> if (routed()) panel.backspace() else out.del()
            KeyboardView.ENTER -> if (routed()) panel.enter() else out.enter()
            KeyboardView.LEFT -> if (!routed()) out.cursor(-1)
            KeyboardView.RIGHT -> if (!routed()) out.cursor(1)
            KeyboardView.CLIPBOARD -> openClipboard()
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
        closeClipboard()
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
