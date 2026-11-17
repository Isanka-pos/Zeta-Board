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
        /** Text just before the cursor (used by Zeta Bold). */
        fun before(): CharSequence? = null
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

    // ---- Zeta Bold: every word you type is wrapped like *word* ----

    private var wordOpen = false

    private fun zetaBold(): Boolean = KbFonts.selected(context) == KbFonts.ZETA_BOLD

    private fun isWordCh(s: String): Boolean {
        if (s.isEmpty()) return false
        val cp = s.codePointAt(0)
        if (Character.isLetterOrDigit(cp)) return true
        val t = Character.getType(cp)
        return t == Character.NON_SPACING_MARK.toInt() || t == Character.COMBINING_SPACING_MARK.toInt()
    }

    private fun boldCommit(s: String) {
        if (isWordCh(s)) {
            if (!wordOpen) {
                wordOpen = true
                out.commit("*" + s)
            } else {
                out.commit(s)
            }
        } else if (wordOpen) {
            wordOpen = false
            out.commit("*" + s)
        } else {
            out.commit(s)
        }
    }

    /** Call when the text field changes or the cursor jumps, so a half-finished word is not tracked any more. */
    fun resetWord() {
        wordOpen = false
    }

    override fun onText(s: String) {
        if (routed()) panel.insert(s) else if (zetaBold()) boldCommit(s) else out.commit(s)
    }

    override fun onAction(a: Int) {
        when (a) {
            KeyboardView.DEL -> if (routed()) panel.backspace() else {
                out.del()
                if (wordOpen) {
                    val b = out.before()
                    if (b == null || b.isEmpty()) {
                        wordOpen = false
                    } else if (b[b.length - 1] == '*') {
                        out.del() // the opening star of a word that is now empty
                        wordOpen = false
                    }
                }
            }
            KeyboardView.ENTER -> if (routed()) panel.enter() else {
                if (wordOpen) {
                    wordOpen = false
                    out.commit("*")
                }
                out.enter()
            }
            KeyboardView.LEFT -> if (!routed()) {
                wordOpen = false
                out.cursor(-1)
            }
            KeyboardView.RIGHT -> if (!routed()) {
                wordOpen = false
                out.cursor(1)
            }
            KeyboardView.CLIPBOARD -> openClipboard()
            KeyboardView.SETTINGS -> out.settings()
            KeyboardView.FONT -> wordOpen = false
        }
    }

    // ---- ClipPanel.Callback ----

    override fun onPaste(text: String) {
        wordOpen = false
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
