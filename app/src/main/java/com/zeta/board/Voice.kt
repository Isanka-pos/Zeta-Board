package com.zeta.board

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Voice typing. Uses the phone's own speech recognizer (the Google speech service) and keeps listening after each
 * pause until it is stopped. Every finished phrase is handed to [Callback.onVoiceText].
 */
class Voice(private val ctx: Context, private val cb: Callback) {

    interface Callback {
        /** What is being heard right now ("" while nothing has been said yet). */
        fun onVoiceHeard(partial: String)

        /** A finished phrase to type into the field. */
        fun onVoiceText(text: String)

        /** Voice typing is over. [problem] says why when it did not end normally; [needMic] means the microphone permission is missing. */
        fun onVoiceEnded(problem: String?, needMic: Boolean)
    }

    companion object {
        const val AUTO = "auto"
        const val SI = "si"
        const val EN = "en"

        fun mode(c: Context): String = Prefs.getS(c, "voice_lang", AUTO).let { if (it == SI || it == EN) it else AUTO }

        fun available(c: Context): Boolean = try {
            SpeechRecognizer.isRecognitionAvailable(c)
        } catch (e: Exception) {
            false
        }

        fun hasMic(c: Context): Boolean = c.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    private val main = Handler(Looper.getMainLooper())
    private val reaper = Handler(Looper.getMainLooper())
    private var rec: SpeechRecognizer? = null
    private var gen = 0
    private var stopping = false
    private var listening = false
    private var heard = false
    private var noMatch = 0
    private var busyRetries = 0

    /** True from [start] until voice typing ends. */
    var active = false
        private set

    fun start() {
        if (active) return
        active = true
        stopping = false
        listening = false
        heard = false
        noMatch = 0
        busyRetries = 0
        gen++
        listen()
    }

    /** Finishes the phrase being spoken (it is still typed), then ends. */
    fun stop() {
        if (!active || stopping) return
        stopping = true
        if (!listening) {
            end(null)
            return
        }
        val my = gen
        try {
            rec?.stopListening()
        } catch (e: Exception) {
            end(null)
            return
        }
        main.postDelayed({ if (active && my == gen) end(null) }, 3500)
    }

    /** Ends at once and throws away anything not typed yet. Reports nothing. */
    fun cancel() {
        if (!active) return
        active = false
        stopping = false
        listening = false
        gen++
        main.removeCallbacksAndMessages(null)
        destroyRec()
    }

    // ---------- internals ----------

    private fun intent(): Intent {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1800L)
        when (mode(ctx)) {
            EN -> i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            SI -> i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "si-LK")
            else -> {
                // Auto: Sinhala first, English as the second language the speech service may switch to
                i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "si-LK")
                i.putStringArrayListExtra(RecognizerIntent.EXTRA_ADDITIONAL_LANGUAGES, arrayListOf("en-US"))
                if (Build.VERSION.SDK_INT >= 33) {
                    i.putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true)
                    i.putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES, arrayListOf("si-LK", "en-US"))
                }
            }
        }
        return i
    }

    private fun listen() {
        var r = rec
        if (r == null) {
            r = try {
                SpeechRecognizer.createSpeechRecognizer(ctx)
            } catch (e: Exception) {
                null
            }
            if (r == null) {
                end("Voice typing is not available on this phone")
                return
            }
            r.setRecognitionListener(Listener(gen))
            rec = r
        }
        try {
            listening = true
            r.startListening(intent())
        } catch (e: Exception) {
            listening = false
            end("Could not start voice typing")
        }
    }

    private fun again(ms: Long) {
        val my = gen
        main.postDelayed({ if (active && my == gen && !stopping) listen() }, ms)
    }

    private fun end(problem: String?, needMic: Boolean = false) {
        if (!active) return
        active = false
        stopping = false
        listening = false
        gen++
        main.removeCallbacksAndMessages(null)
        destroyRec()
        cb.onVoiceEnded(problem, needMic)
    }

    private fun destroyRec() {
        val r = rec ?: return
        rec = null
        reaper.post {
            try {
                r.cancel()
            } catch (e: Exception) {
            }
            try {
                r.destroy()
            } catch (e: Exception) {
            }
        }
    }

    private fun first(b: Bundle?): String =
        b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()

    private fun fail(code: Int) {
        listening = false
        when (code) {
            SpeechRecognizer.ERROR_NO_MATCH -> {
                noMatch++
                if (stopping) end(null)
                else if (noMatch >= 3) end(if (heard) null else "I couldn't understand that. Try again")
                else again(200)
            }
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                end(if (heard || stopping) null else "I didn't hear anything. Tap the mic and speak")
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                if (stopping) {
                    end(null)
                } else if (busyRetries++ < 2) {
                    destroyRec()
                    again(700)
                } else {
                    end("Voice typing is busy. Try again in a moment")
                }
            }
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> end("Allow the microphone, then tap the mic again", true)
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER, 11 ->
                end("Voice typing needs an internet connection")
            10 -> end("Too many requests. Wait a moment and try again")
            SpeechRecognizer.ERROR_AUDIO -> end("Microphone problem. Close other apps that use it")
            SpeechRecognizer.ERROR_CLIENT ->
                end(if (heard || stopping) null else "Voice typing stopped. Tap the mic again")
            12, 13 -> end("This voice language is not available. Update or download it in the Google app")
            else -> end("Voice typing failed ($code)")
        }
    }

    private inner class Listener(private val my: Int) : RecognitionListener {
        private fun ok() = active && my == gen

        override fun onReadyForSpeech(params: Bundle?) {
            if (ok()) cb.onVoiceHeard("")
        }

        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            if (!ok() || stopping) return
            val t = first(partialResults)
            if (t.isNotEmpty()) cb.onVoiceHeard(t)
        }

        override fun onResults(results: Bundle?) {
            if (!ok()) return
            listening = false
            val t = first(results)
            if (t.isNotEmpty()) {
                heard = true
                noMatch = 0
                cb.onVoiceText(t)
            }
            if (!ok()) return
            if (stopping || !Prefs.getB(ctx, "voice_cont", true)) end(null) else again(150)
        }

        override fun onError(error: Int) {
            if (ok()) fail(error)
        }
    }
}

/** Invisible screen that asks for the microphone permission (a keyboard cannot show that dialog itself). */
class MicPermissionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Voice.hasMic(this)) {
            finish()
            return
        }
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 41)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        finish()
    }
}
