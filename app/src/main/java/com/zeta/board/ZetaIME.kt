package com.zeta.board

import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.net.Uri
import android.inputmethodservice.InputMethodService
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import java.io.File

class ZetaIME : InputMethodService() {

    private var host: KeyboardHost? = null
    private var video: VideoBgView? = null
    private var secure = false
    private var expanding = false
    private var cm: ClipboardManager? = null
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        captureClip(true)
        checkReveal(true)
    }

    /** Hash of the protected message that was last shown or typed in, so it does not pop up again on every new text box. */
    private var revealSeen = 0

    /** True while the keyboard is on the screen for a text box (between start and finish of the input view). */
    private var inputActive = false

    private val output = object : KeyboardHost.Output {
        override fun revealUsed() {
            revealSeen = currentClipText()?.hashCode() ?: revealSeen
        }

        override fun commit(s: String) {
            currentInputConnection?.commitText(s, 1)
        }

        override fun del() {
            val ic = currentInputConnection ?: return
            // the selection is already known from onUpdateSelection: skip one round trip to the app per delete
            if (selStart != selEnd) {
                val sel = ic.getSelectedText(0)
                if (!sel.isNullOrEmpty()) {
                    ic.commitText("", 1)
                    return
                }
            }
            // delete a whole emoji (flag, family, skin tone, heart + selector...) in one tap
            val before = ic.getTextBeforeCursor(32, 0)
            val n = if (before.isNullOrEmpty()) 0 else Emojis.lastClusterLength(before)
            if (n > 0) ic.deleteSurroundingText(n, 0) else ic.deleteSurroundingTextInCodePoints(1, 0)
        }

        override fun enter() {
            val ic = currentInputConnection ?: return
            val ei = currentInputEditorInfo
            val opts = ei?.imeOptions ?: 0
            val action = opts and EditorInfo.IME_MASK_ACTION
            val noAction = (opts and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
            if (ei == null || noAction || action == EditorInfo.IME_ACTION_NONE || action == EditorInfo.IME_ACTION_UNSPECIFIED) {
                sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
            } else {
                ic.performEditorAction(action)
            }
        }

        override fun editorKey(code: Int, select: Boolean) {
            val ic = currentInputConnection ?: return
            // |< and >| go to the very start / end of the whole text (the Home / End key only reaches the start of the line)
            if (code == KeyEvent.KEYCODE_MOVE_HOME || code == KeyEvent.KEYCODE_MOVE_END) {
                try {
                    var a = selStart
                    var b = selEnd
                    val ex = ic.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)
                    if (ex != null && ex.selectionStart >= 0) {
                        a = ex.startOffset + ex.selectionStart
                        b = ex.startOffset + ex.selectionEnd
                    }
                    val lo = minOf(a, b)
                    val hi = maxOf(a, b)
                    if (code == KeyEvent.KEYCODE_MOVE_HOME) {
                        if (select) ic.setSelection(0, hi) else ic.setSelection(0, 0)
                    } else {
                        val after = ic.getTextAfterCursor(1000000, 0)?.length ?: 0
                        val total = hi + after
                        if (select) ic.setSelection(lo, total) else ic.setSelection(total, total)
                    }
                    return
                } catch (e: Exception) {
                    Crash.log("editorKeyHomeEnd", e)
                }
            }
            try {
                val now = android.os.SystemClock.uptimeMillis()
                val meta = if (select) KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON else 0
                if (select) ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_LEFT, 0, 0))
                ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, meta))
                ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0, meta))
                if (select) ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SHIFT_LEFT, 0, 0))
            } catch (e: Exception) {
                Crash.log("editorKey", e)
            }
        }

        override fun editorAction(id: Int) {
            try {
                currentInputConnection?.performContextMenuAction(id)
            } catch (e: Exception) {
                Crash.log("editorAction", e)
            }
        }

        override fun cursor(dir: Int) {
            sendDownUpKeyEvents(if (dir < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT)
        }

        override fun settings() {
            startActivity(Intent(this@ZetaIME, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }

        override fun sendSticker(f: File): Boolean {
            val ic = currentInputConnection ?: return false
            val ei = currentInputEditorInfo ?: return false
            return try {
                StickerSender.send(this@ZetaIME, ic, ei, f)
            } catch (e: Exception) {
                Crash.log("sendSticker", e)
                false
            }
        }

        override fun shareSticker(f: File) {
            try {
                val uri: Uri = StickerSender.uriFor(this@ZetaIME, f)
                val send = Intent(Intent.ACTION_SEND)
                    .setType("image/webp")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val chooser = Intent.createChooser(send, "Send sticker")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                startActivity(chooser)
            } catch (e: Exception) {
            }
        }

        override fun expand(delim: String): Boolean {
            if (secure || expanding) return false
            if (!Prefs.getB(this@ZetaIME, "quick_auto", true)) return false
            if (!QuickRepo.hasEnabled()) return false
            val ic = currentInputConnection ?: return false
            expanding = true
            try {
                if (!ic.getSelectedText(0).isNullOrEmpty()) return false
                val before = ic.getTextBeforeCursor(QuickText.MAX_SC + 2, 0) ?: return false
                val tok = QuickText.tokenAtEnd(before) ?: return false
                val msg = QuickRepo.lookup(tok) ?: return false
                ic.beginBatchEdit()
                try {
                    if (!ic.deleteSurroundingText(tok.length, 0)) return false
                    ic.commitText(msg, 1)
                    ic.commitText(delim, 1)
                } finally {
                    ic.endBatchEdit()
                }
                return true
            } catch (e: Exception) {
                Crash.log("expand", e)
                return false
            } finally {
                expanding = false
            }
        }

        override fun quickSuggestion(): String? {
            if (secure || !Prefs.getB(this@ZetaIME, "quick_suggest", true)) return null
            if (!QuickRepo.hasEnabled()) return null
            val ic = currentInputConnection ?: return null
            return try {
                // the selection is already known from onUpdateSelection: no extra round trip to the app while nothing is selected
                if (selStart != selEnd && !ic.getSelectedText(0).isNullOrEmpty()) return null
                val before = ic.getTextBeforeCursor(QuickText.MAX_SC + 2, 0) ?: return null
                val tok = QuickText.tokenAtEnd(before) ?: return null
                QuickRepo.lookup(tok)
            } catch (e: Exception) {
                null
            }
        }

        override fun applyQuick(): Boolean {
            if (secure || expanding) return false
            val ic = currentInputConnection ?: return false
            expanding = true
            try {
                if (!ic.getSelectedText(0).isNullOrEmpty()) return false
                val before = ic.getTextBeforeCursor(QuickText.MAX_SC + 2, 0) ?: return false
                val tok = QuickText.tokenAtEnd(before) ?: return false
                val msg = QuickRepo.lookup(tok) ?: return false
                ic.beginBatchEdit()
                try {
                    if (!ic.deleteSurroundingText(tok.length, 0)) return false
                    ic.commitText(msg, 1)
                } finally {
                    ic.endBatchEdit()
                }
                return true
            } catch (e: Exception) {
                return false
            } finally {
                expanding = false
            }
        }

        override fun insertQuick(text: String): Boolean {
            if (secure) return false
            val ic = currentInputConnection ?: return false
            return try {
                ic.commitText(text, 1)
            } catch (e: Exception) {
                false
            }
        }

        override fun quickAllowed(): Boolean = !secure

        override fun voiceAllowed(): Boolean = !secure

        override fun charBeforeCursor(): Char? = try {
            currentInputConnection?.getTextBeforeCursor(1, 0)?.firstOrNull()
        } catch (e: Exception) {
            null
        }

        override fun requestMic() {
            try {
                startActivity(Intent(this@ZetaIME, MicPermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
            }
        }

        override fun setComposing(text: String) {
            try {
                currentInputConnection?.setComposingText(text, 1)
            } catch (e: Exception) {
            }
        }

        override fun finishComposing() {
            try {
                currentInputConnection?.finishComposingText()
            } catch (e: Exception) {
            }
        }

        override fun replaceBefore(n: Int, text: String): Boolean {
            val ic = currentInputConnection ?: return false
            if (selStart != selEnd) return false
            return try {
                ic.beginBatchEdit()
                ic.deleteSurroundingText(n, 0)
                ic.commitText(text, 1)
                ic.endBatchEdit()
                true
            } catch (e: Exception) {
                false
            }
        }

        override fun textBefore(n: Int): CharSequence? = try {
            currentInputConnection?.getTextBeforeCursor(n, 0)
        } catch (e: Exception) {
            null
        }

        override fun translateAllowed(): Boolean = !secure

        override fun translateSource(): TrSource? {
            val ic = currentInputConnection ?: return null
            return try {
                val sel = ic.getSelectedText(0)?.toString()
                if (!sel.isNullOrEmpty()) {
                    if (sel.isBlank()) TrSource("", true, "Select some text first") else TrSource(sel, true)
                } else if (selStart != selEnd) {
                    TrSource("", true, "This app does not let the keyboard read the selection")
                } else {
                    val before = ic.getTextBeforeCursor(Translator.MAX_CHARS + 1, 0)?.toString() ?: ""
                    val after = ic.getTextAfterCursor(Translator.MAX_CHARS + 1, 0)?.toString() ?: ""
                    val all = before + after
                    if (all.isBlank()) TrSource("", false, "Type a message first") else TrSource(all, false)
                }
            } catch (e: Exception) {
                null
            }
        }

        override fun applyTranslation(src: TrSource, translated: String): Boolean {
            if (secure || translated.isEmpty()) return false
            val ic = currentInputConnection ?: return false
            return try {
                if (src.selection) {
                    if (ic.getSelectedText(0)?.toString() != src.text) return false
                    ic.commitText(translated, 1)
                } else {
                    val before = ic.getTextBeforeCursor(Translator.MAX_CHARS + 1, 0)?.toString() ?: return false
                    val after = ic.getTextAfterCursor(Translator.MAX_CHARS + 1, 0)?.toString() ?: return false
                    if (before + after != src.text) return false
                    ic.beginBatchEdit()
                    try {
                        if (!ic.deleteSurroundingText(before.length, after.length)) return false
                        ic.commitText(translated, 1)
                    } finally {
                        ic.endBatchEdit()
                    }
                }
            } catch (e: Exception) {
                Crash.log("applyTranslation", e)
                false
            }
        }

        override fun openQuickManager() {
            try {
                startActivity(Intent(this@ZetaIME, QuickTextActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
            }
        }

        override fun openStatusSetup(kind: String) {
            try {
                startActivity(
                    Intent(this@ZetaIME, StatusSetupActivity::class.java).putExtra("kind", kind)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                )
            } catch (e: Exception) {
            }
        }

        override fun requestStoragePermission() = openStatusSetup("perm")

        override fun sendMedia(f: File, mime: String): Boolean {
            val ic = currentInputConnection ?: return false
            val ei = currentInputEditorInfo ?: return false
            return try {
                StickerSender.sendMedia(this@ZetaIME, ic, ei, f, mime)
            } catch (e: Exception) {
                Crash.log("sendMedia", e)
                false
            }
        }

        override fun shareMedia(files: List<File>, mimes: List<String>) {
            try {
                if (files.isEmpty()) return
                val uris = ArrayList<Uri>()
                for (f in files) uris.add(StickerSender.uriFor(this@ZetaIME, f))
                val majors = mimes.map { it.substringBefore('/') }.toSet()
                val type = if (uris.size == 1) mimes[0] else if (majors.size == 1) majors.first() + "/*" else "*/*"
                val send = if (uris.size == 1) {
                    Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
                } else {
                    Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                }
                send.setType(type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                startActivity(
                    Intent.createChooser(send, "Send status")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                )
            } catch (e: Exception) {
            }
        }

        override fun pickPhoto() {
            startActivity(
                Intent(this@ZetaIME, StickerActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            )
        }
    }

    override fun onCreate() {
        super.onCreate()
        ClipRepo.init(this)
        ClipRepo.warm()
        KeyClick.warm(this)
        DeleteFx.warm()
        EnSuggest.preload(this)
        QuickRepo.init(this)
        val m = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm = m
        m.addPrimaryClipChangedListener(clipListener)
        UpdateScheduler.schedule(this)
        UpdateWatcher.start(this)
    }

    override fun onDestroy() {
        DeleteFx.release(this)
        KeyClick.release()
        cm?.removePrimaryClipChangedListener(clipListener)
        host?.closeClipboard()
        host?.closeStickers()
        host?.closeStatus()
        host?.closeQuick()
        host?.closeMix()
        host?.closeTools()
        host?.closeEditor()
        host?.cancelWork()
        super.onDestroy()
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    /** Where the cursor / selection is in the focused field (as the app last reported it). */
    private var selStart = 0
    private var selEnd = 0

    /** The text or cursor changed (typing, the app clearing the field after Send, the user moving the cursor): update the Quick Text suggestion. */
    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        selStart = newSelStart
        selEnd = newSelEnd
        host?.onSelectionChanged(candidatesStart, candidatesEnd, newSelStart, newSelEnd)
        host?.scheduleSuggestion()
    }

    override fun onCreateInputView(): View {
        val tr = Perf.begin("keyboard_view_create")
        try {
            return buildInputView()
        } finally {
            Perf.end(tr)
        }
    }

    /** Shown instead of the real keyboard when this install's signature does not match the project's: no keys, nothing typed. */
    private fun tamperView(): View {
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.gravity = Gravity.CENTER
        root.setBackgroundColor(Color.parseColor("#0C0919"))
        root.setPadding(dp(24), dp(20), dp(24), dp(20))
        val title = TextView(this)
        title.text = "This copy has been changed"
        title.textSize = 15f
        title.setTextColor(Color.WHITE)
        title.typeface = Typeface.DEFAULT_BOLD
        title.gravity = Gravity.CENTER
        root.addView(title)
        val body = TextView(this)
        body.text = "This install was edited after it left the project, so the keyboard will not work. Get the official app from its GitHub releases."
        body.textSize = 12f
        body.setTextColor(Color.parseColor("#A59BC9"))
        body.gravity = Gravity.CENTER
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(8)
        root.addView(body, lp)
        return root
    }

    private fun buildInputView(): View {
        AntiTamper.check(this)
        if (!AntiTamper.ok) return tamperView()
        EnSuggest.preload(this)
        val h = KeyboardHost(this, output)
        h.kv.theme = Themes.get(Prefs.getI(this, "theme", 0))
        h.onSecure = { on -> ScreenGuard.setSecure(window?.window, on && ScreenGuard.blocking(this)) }
        host = h
        // video background: a player view sits behind the keyboard; the frame shows the theme colours until the first video frame
        val v = VideoBgView(this)
        video = v
        // The keyboard decides the height. A plain FrameLayout would count the video view (match_parent) as well and
        // grow to the whole screen, so the frame measures the keyboard first and then sizes the video to it.
        val frame = object : FrameLayout(this) {
            override fun onMeasure(widthSpec: Int, heightSpec: Int) {
                val kb = getChildAt(1)
                val vid = getChildAt(0)
                if (kb == null || vid == null) {
                    super.onMeasure(widthSpec, heightSpec)
                    return
                }
                measureChild(kb, widthSpec, heightSpec)
                val w = resolveSize(kb.measuredWidth, widthSpec)
                val hgt = resolveSize(kb.measuredHeight, heightSpec)
                setMeasuredDimension(w, hgt)
                vid.measure(
                    View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(hgt, View.MeasureSpec.EXACTLY)
                )
                // anything on top of the keyboard (the flower effect) is as big as the keyboard
                for (i in 2 until childCount) {
                    val ex = getChildAt(i)
                    if (ex.visibility != View.GONE) {
                        ex.measure(
                            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(hgt, View.MeasureSpec.EXACTLY)
                        )
                    }
                }
            }
        }
        fun paintFrame(t: KbTheme) {
            frame.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(t.bgTop, t.bgBottom))
        }
        paintFrame(h.kv.theme)
        val prev = h.kv.onTheme
        h.kv.onTheme = {
            prev?.invoke(it)
            paintFrame(it)
        }
        frame.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        frame.addView(h, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        // the backspace flower effect for phones that have not allowed the full-screen version: plays over the keyboard
        val fx = DelFxView(this)
        fx.visibility = View.GONE
        frame.addView(fx, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        DeleteFx.attachInside(fx)
        return frame
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        val tr = Perf.begin("keyboard_start_input")
        try {
            startInput(info, restarting)
        } finally {
            Perf.end(tr)
        }
    }

    private fun startInput(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        inputActive = true
        val h = host ?: return
        val t = info?.inputType ?: 0
        val cls = t and InputType.TYPE_MASK_CLASS
        val v = t and InputType.TYPE_MASK_VARIATION
        secure = (cls == InputType.TYPE_CLASS_TEXT &&
            (v == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                v == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                v == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)) ||
            (cls == InputType.TYPE_CLASS_NUMBER && v == InputType.TYPE_NUMBER_VARIATION_PASSWORD) ||
            ((info?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0
        h.resetSinglish()
        h.closeClipboard()
        h.closeStickers()
        h.closeStatus()
        h.closeQuick()
        h.closeMix()
        if (!restarting) h.cancelWork()
        h.closeTools()
        h.closeEditor()
        // setting the theme repaints every panel: only do it when it really changed
        val nt = Themes.get(Prefs.getI(this, "theme", 0))
        if (nt !== h.kv.theme) h.kv.theme = nt
        h.kv.latinOnly = secure
        h.kv.reload()
        video?.sync()
        val layer = if (cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE) 1 else 0
        val cap = cls == InputType.TYPE_CLASS_TEXT && (t and InputType.TYPE_TEXT_FLAG_CAP_SENTENCES) != 0
        h.kv.startInput(layer, if (cap) 1 else 0)
        if (!secure) h.onStartInput()
        selStart = info?.initialSelStart ?: 0
        selEnd = info?.initialSelEnd ?: 0
        h.refreshSuggestion(true)
        if (secure) h.showReveal(null) else checkReveal(false)
        // background chores wait until the keyboard is already on the screen
        bgHandler.post {
            captureClip(false)
            UpdateWatcher.checkSoon(this)
            val now = System.currentTimeMillis()
            if (now - lastCfgRefresh > 5 * 60 * 1000L) {
                lastCfgRefresh = now
                RemoteCfg.refresh {}
            }
        }
    }

    private val bgHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var lastCfgRefresh = 0L

    override fun onFinishInputView(finishingInput: Boolean) {
        inputActive = false
        host?.showReveal(null)
        DeleteFx.stop()
        Perf.flushDraw()
        host?.flushSinglish()
        host?.closeClipboard()
        host?.closeStickers()
        host?.closeStatus()
        host?.closeQuick()
        host?.closeMix()
        host?.closeTools()
        host?.closeEditor()
        host?.cancelWork()
        video?.pause()
        super.onFinishInputView(finishingInput)
    }

    private fun currentClipText(): String? = try {
        val clip = cm?.primaryClip
        if (clip == null || clip.itemCount == 0 || !clip.description.hasMimeType("text/*")) null
        else clip.getItemAt(0).coerceToText(this)?.toString()
    } catch (e: Exception) {
        null
    }

    /**
     * If the copied text is a protected message, shows what it says on the toolbar (tap it to type it in). [fromCopy] is true
     * when the user just copied it; when the keyboard only opens, a message that was already shown or typed in stays hidden.
     * Anything else that is copied takes the message off the toolbar again.
     */
    private fun checkReveal(fromCopy: Boolean) {
        val h = host ?: return
        // a copy made while the keyboard is not showing is picked up when the keyboard opens
        if (fromCopy && !inputActive) return
        try {
            if (secure) {
                h.showReveal(null)
                return
            }
            val text = currentClipText()
            val msg = if (Protect.has(text)) Protect.decode(text) else null
            if (msg == null) {
                h.showReveal(null)
                return
            }
            val hash = text.hashCode()
            if (!fromCopy && hash == revealSeen) return
            revealSeen = hash
            h.showReveal(msg)
            Stats.event(this, "protect", "reveal")
        } catch (e: Exception) {
            h.showReveal(null)
        }
    }

    /**
     * Stores the current clip locally. Skips sensitive clips, password fields and incognito fields.
     * [fromCopy] is true when the user just copied something; when the keyboard only opens, a clip that was
     * already handled (for example one the user deleted from history) is not added back.
     */
    private fun captureClip(fromCopy: Boolean) {
        if (secure || !Prefs.getB(this, "clip_on", true)) return
        try {
            val clip = cm?.primaryClip ?: return
            if (clip.itemCount == 0) return
            val desc = clip.description
            if (!desc.hasMimeType("text/*")) return
            if (desc.extras?.getBoolean("android.content.extra.IS_SENSITIVE", false) == true) return
            val text = clip.getItemAt(0).coerceToText(this)?.toString() ?: return
            if (Protect.has(text)) return // a protected message looks empty, so it is not worth keeping in the history
            if (ClipGuard.looksSensitive(text)) return
            val h = text.hashCode()
            if (!fromCopy && Prefs.getI(this, "clip_hash", 0) == h) return
            Prefs.setI(this, "clip_hash", h)
            ClipRepo.add(text, Prefs.getI(this, "clip_max", 100))
        } catch (e: Exception) {
        }
    }
}
