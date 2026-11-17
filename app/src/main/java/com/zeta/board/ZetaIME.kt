package com.zeta.board

import android.content.ClipboardManager
import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo

class ZetaIME : InputMethodService() {

    private var host: KeyboardHost? = null
    private var secure = false
    private var cm: ClipboardManager? = null
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { captureClip() }

    private val output = object : KeyboardHost.Output {
        override fun commit(s: String) {
            currentInputConnection?.commitText(s, 1)
        }

        override fun del() {
            val ic = currentInputConnection ?: return
            val sel = ic.getSelectedText(0)
            if (sel.isNullOrEmpty()) ic.deleteSurroundingTextInCodePoints(1, 0) else ic.commitText("", 1)
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

        override fun cursor(dir: Int) {
            sendDownUpKeyEvents(if (dir < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT)
        }

        override fun settings() {
            startActivity(Intent(this@ZetaIME, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    override fun onCreate() {
        super.onCreate()
        ClipRepo.init(this)
        val m = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm = m
        m.addPrimaryClipChangedListener(clipListener)
        UpdateScheduler.schedule(this)
    }

    override fun onDestroy() {
        cm?.removePrimaryClipChangedListener(clipListener)
        super.onDestroy()
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onCreateInputView(): View {
        val h = KeyboardHost(this, output)
        h.kv.theme = Themes.get(Prefs.getI(this, "theme", 0))
        host = h
        return h
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
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
        h.closeClipboard()
        h.kv.theme = Themes.get(Prefs.getI(this, "theme", 0))
        h.kv.reload()
        val layer = if (cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE) 1 else 0
        val cap = cls == InputType.TYPE_CLASS_TEXT && (t and InputType.TYPE_TEXT_FLAG_CAP_SENTENCES) != 0
        h.kv.startInput(layer, if (cap) 1 else 0)
        captureClip()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        host?.closeClipboard()
        super.onFinishInputView(finishingInput)
    }

    /** Stores the current clip locally. Skips sensitive clips, password fields and incognito fields. */
    private fun captureClip() {
        if (secure || !Prefs.getB(this, "clip_on", true)) return
        try {
            val clip = cm?.primaryClip ?: return
            if (clip.itemCount == 0) return
            val desc = clip.description
            if (!desc.hasMimeType("text/*")) return
            if (desc.extras?.getBoolean("android.content.extra.IS_SENSITIVE", false) == true) return
            val text = clip.getItemAt(0).coerceToText(this)?.toString() ?: return
            if (ClipGuard.looksSensitive(text)) return
            ClipRepo.add(text, Prefs.getI(this, "clip_max", 100))
        } catch (e: Exception) {
        }
    }
}
