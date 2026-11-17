package com.zeta.board

import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.inputmethodservice.InputMethodService
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import java.io.File

class ZetaIME : InputMethodService() {

    private var host: KeyboardHost? = null
    private var secure = false
    private var cm: ClipboardManager? = null
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { captureClip(true) }

    private val output = object : KeyboardHost.Output {
        override fun commit(s: String) {
            currentInputConnection?.commitText(s, 1)
        }

        override fun del() {
            val ic = currentInputConnection ?: return
            val sel = ic.getSelectedText(0)
            if (!sel.isNullOrEmpty()) {
                ic.commitText("", 1)
                return
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
        QuickTextRepo.init(this)
        val m = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm = m
        m.addPrimaryClipChangedListener(clipListener)
        UpdateScheduler.schedule(this)
        UpdateWatcher.start(this)
    }

    override fun onDestroy() {
        cm?.removePrimaryClipChangedListener(clipListener)
        host?.closeClipboard()
        host?.closeStickers()
        host?.closeQuickText()
        super.onDestroy()
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onCreateInputView(): View {
        val h = KeyboardHost(this, output)
        h.kv.theme = Themes.get(Prefs.getI(this, "theme", 0))
        h.onSecure = { on -> ScreenGuard.setSecure(window?.window, on && ScreenGuard.blocking(this)) }
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
        h.closeStickers()
        h.closeQuickText()
        h.quickTextAllowed = !secure
        h.kv.theme = Themes.get(Prefs.getI(this, "theme", 0))
        h.kv.reload()
        val layer = if (cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE) 1 else 0
        val cap = cls == InputType.TYPE_CLASS_TEXT && (t and InputType.TYPE_TEXT_FLAG_CAP_SENTENCES) != 0
        h.kv.startInput(layer, if (cap) 1 else 0)
        if (!secure) h.onStartInput()
        captureClip(false)
        UpdateWatcher.checkSoon(this)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        host?.closeClipboard()
        host?.closeStickers()
        host?.closeQuickText()
        super.onFinishInputView(finishingInput)
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
            if (ClipGuard.looksSensitive(text)) return
            val h = text.hashCode()
            if (!fromCopy && Prefs.getI(this, "clip_hash", 0) == h) return
            Prefs.setI(this, "clip_hash", h)
            ClipRepo.add(text, Prefs.getI(this, "clip_max", 100))
        } catch (e: Exception) {
        }
    }
}
