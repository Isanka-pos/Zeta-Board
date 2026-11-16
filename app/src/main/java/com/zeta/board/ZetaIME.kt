package com.zeta.board

import android.content.ClipboardManager
import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo

class ZetaIME : InputMethodService(), KeyboardView.Listener {

    private var kv: KeyboardView? = null

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onCreateInputView(): View {
        val v = KeyboardView(this)
        v.listener = this
        v.theme = Themes.get(Prefs.getI(this, "theme", 0))
        kv = v
        return v
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        val v = kv ?: return
        v.theme = Themes.get(Prefs.getI(this, "theme", 0))
        v.reload()
        val t = info?.inputType ?: 0
        val cls = t and InputType.TYPE_MASK_CLASS
        val layer = if (cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE) 1 else 0
        val cap = cls == InputType.TYPE_CLASS_TEXT && (t and InputType.TYPE_TEXT_FLAG_CAP_SENTENCES) != 0
        v.startInput(layer, if (cap) 1 else 0)
    }

    override fun onText(s: String) {
        currentInputConnection?.commitText(s, 1)
    }

    override fun onAction(a: Int) {
        val ic = currentInputConnection ?: return
        when (a) {
            KeyboardView.DEL -> {
                val sel = ic.getSelectedText(0)
                if (sel.isNullOrEmpty()) ic.deleteSurroundingTextInCodePoints(1, 0) else ic.commitText("", 1)
            }
            KeyboardView.ENTER -> {
                val ei = currentInputEditorInfo
                val action = (ei?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
                val noAction = ((ei?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
                if (ei == null || noAction || action == EditorInfo.IME_ACTION_NONE || action == EditorInfo.IME_ACTION_UNSPECIFIED) {
                    sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
                } else {
                    ic.performEditorAction(action)
                }
            }
            KeyboardView.LEFT -> sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_LEFT)
            KeyboardView.RIGHT -> sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_RIGHT)
            KeyboardView.PASTE -> {
                val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                val item = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
                val txt = item?.coerceToText(this)?.toString()
                if (!txt.isNullOrEmpty()) ic.commitText(txt, 1)
            }
            KeyboardView.SETTINGS -> {
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }
}
