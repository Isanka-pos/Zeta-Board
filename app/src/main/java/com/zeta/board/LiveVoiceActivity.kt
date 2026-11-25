package com.zeta.board

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Live Voice Changer: the microphone is played back through the speaker / headphones with an effect.
 * Use headphones, otherwise the speaker sound goes back into the microphone (echo).
 */
class LiveVoiceActivity : Activity() {

    private class Effect(val id: Int, val name: String, val hint: String)

    private val effects = listOf(
        Effect(0, "Normal", "Your own voice"),
        Effect(1, "Deep", "Low, heavy voice"),
        Effect(2, "Chipmunk", "High, squeaky voice"),
        Effect(3, "Robot", "Metal robot voice"),
        Effect(4, "Echo", "Voice with echo"),
        Effect(5, "Alien", "Wobbling alien voice"),
        Effect(6, "Giant", "Very deep and slow")
    )

    @Volatile private var effect = 0
    @Volatile private var running = false
    private var worker: Thread? = null

    private lateinit var status: TextView
    private lateinit var toggle: TextView
    private val chips = ArrayList<TextView>()

    private val bg = Color.parseColor("#0F0B1E")
    private val card = Color.parseColor("#1A1433")
    private val purple = Color.parseColor("#8B5CF6")
    private val cyan = Color.parseColor("#22D3EE")
    private val muted = Color.parseColor("#9A93BD")

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun round(color: Int, radius: Int, stroke: Int = 0, strokeColor: Int = 0): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = dp(radius).toFloat()
        if (stroke > 0) g.setStroke(dp(stroke), strokeColor)
        return g
    }

    private fun text(s: String, size: Float, color: Int, bold: Boolean): TextView {
        val t = TextView(this)
        t.text = s
        t.textSize = size
        t.setTextColor(color)
        if (bold) t.setTypeface(t.typeface, android.graphics.Typeface.BOLD)
        return t
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg

        val scroll = ScrollView(this)
        scroll.setBackgroundColor(bg)
        scroll.isVerticalScrollBarEnabled = false

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(20), dp(28), dp(20), dp(28))
        scroll.addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val title = text("Live Voice Changer", 24f, Color.WHITE, true)
        root.addView(title)
        val sub = text("Change your voice in real time. Use headphones to avoid echo.", 13f, muted, false)
        sub.setPadding(0, dp(6), 0, dp(18))
        root.addView(sub)

        // effects
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(14), dp(14), dp(14), dp(14))
        box.background = round(card, 20, 1, Color.parseColor("#2B2350"))
        for (e in effects) {
            val chip = LinearLayout(this)
            chip.orientation = LinearLayout.VERTICAL
            chip.setPadding(dp(14), dp(10), dp(14), dp(10))
            val n = text(e.name, 15f, Color.WHITE, true)
            val h = text(e.hint, 12f, muted, false)
            chip.addView(n)
            chip.addView(h)
            chip.tag = e.id
            chip.setOnClickListener { pick(e.id) }
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = dp(8)
            box.addView(chip, lp)
            chips.add(n)
        }
        root.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        status = text("Stopped", 13f, muted, false)
        status.gravity = Gravity.CENTER
        status.setPadding(0, dp(18), 0, dp(8))
        root.addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        toggle = text("Start", 16f, Color.WHITE, true)
        toggle.gravity = Gravity.CENTER
        toggle.setPadding(dp(20), dp(14), dp(20), dp(14))
        toggle.background = round(purple, 18)
        toggle.setOnClickListener { if (running) stopEngine() else startWithPermission() }
        root.addView(toggle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        setContentView(scroll)
        pick(0)
    }

    private fun pick(id: Int) {
        effect = id
        for (i in 0 until chips.size) {
            val parent = chips[i].parent as View
            val on = effects[i].id == id
            parent.background = round(if (on) Color.parseColor("#2A2050") else Color.TRANSPARENT, 14, 1, if (on) cyan else Color.parseColor("#2B2350"))
            chips[i].setTextColor(if (on) cyan else Color.WHITE)
        }
    }

    private fun startWithPermission() {
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 77)
            return
        }
        startEngine()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 77) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) startEngine()
            else Toast.makeText(this, "Microphone permission is needed", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startEngine() {
        if (running) return
        running = true
        toggle.text = "Stop"
        toggle.background = round(Color.parseColor("#EF4444"), 18)
        status.text = "Live: speak now"
        status.setTextColor(cyan)
        val t = Thread { runEngine() }
        t.isDaemon = true
        worker = t
        t.start()
    }

    private fun stopEngine() {
        running = false
        try {
            worker?.join(800)
        } catch (e: InterruptedException) {
        }
        worker = null
        toggle.text = "Start"
        toggle.background = round(purple, 18)
        status.text = "Stopped"
        status.setTextColor(muted)
    }

    private fun failed(msg: String) {
        runOnUiThread {
            running = false
            toggle.text = "Start"
            toggle.background = round(purple, 18)
            status.text = msg
            status.setTextColor(Color.parseColor("#F87171"))
        }
    }

    private fun runEngine() {
        val rate = 16000
        val minIn = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val minOut = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minIn <= 0 || minOut <= 0) {
            failed("Audio is not available on this phone")
            return
        }
        var rec: AudioRecord? = null
        var track: AudioTrack? = null
        try {
            rec = AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minIn * 2)
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                failed("Could not open the microphone")
                return
            }
            track = AudioTrack(
                AudioManager.STREAM_MUSIC, rate, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT, minOut * 2, AudioTrack.MODE_STREAM
            )
            val frame = 320
            val inBuf = ShortArray(frame)
            val outBuf = ShortArray(frame)
            val fx = Dsp(rate)
            rec.startRecording()
            track.play()
            while (running) {
                val n = rec.read(inBuf, 0, frame)
                if (n <= 0) continue
                fx.process(effect, inBuf, outBuf, n)
                track.write(outBuf, 0, n)
            }
        } catch (e: Exception) {
            failed("Voice changer stopped: " + (e.message ?: "error"))
        } finally {
            try {
                rec?.stop()
            } catch (e: Exception) {
            }
            try {
                rec?.release()
            } catch (e: Exception) {
            }
            try {
                track?.stop()
            } catch (e: Exception) {
            }
            try {
                track?.release()
            } catch (e: Exception) {
            }
        }
    }

    override fun onPause() {
        super.onPause()
        if (running) stopEngine()
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    /** Small real-time effects: a delay-line pitch shifter, ring modulation and echo. */
    private class Dsp(private val rate: Int) {
        private val size = 4096
        private val ring = FloatArray(size)
        private var w = 0
        private var d1 = 0f
        private var d2 = 0f
        private val win = 1024f
        private var phase = 0.0
        private val echo = FloatArray(rate / 3)
        private var ep = 0
        private var lfo = 0.0

        private fun read(pos: Float): Float {
            var p = w - pos
            while (p < 0) p += size
            val i = p.toInt() % size
            val f = p - p.toInt()
            val a = ring[i]
            val b = ring[(i + 1) % size]
            return a + (b - a) * f
        }

        private fun shift(ratio: Float, x: Float): Float {
            ring[w] = x
            // two read heads moving at (1 - ratio) samples per sample, cross-faded so there is no click
            d1 += 1f - ratio
            if (d1 >= win) d1 -= win
            if (d1 < 0f) d1 += win
            d2 = (d1 + win / 2f) % win
            val g1 = sin(PI.toFloat() * d1 / win)
            val g2 = sin(PI.toFloat() * d2 / win)
            val y = read(d1 + 2f) * g1 * g1 + read(d2 + 2f) * g2 * g2
            w = (w + 1) % size
            return y
        }

        fun process(effect: Int, input: ShortArray, output: ShortArray, n: Int) {
            for (i in 0 until n) {
                val x = input[i] / 32768f
                var y: Float
                when (effect) {
                    1 -> y = shift(0.72f, x) * 1.2f
                    2 -> y = shift(1.55f, x)
                    3 -> {
                        phase += 2.0 * PI * 55.0 / rate
                        if (phase > 2.0 * PI) phase -= 2.0 * PI
                        y = x * sin(phase).toFloat() * 1.8f
                    }
                    4 -> {
                        val delayed = echo[ep]
                        echo[ep] = x + delayed * 0.45f
                        ep = (ep + 1) % echo.size
                        y = x + delayed * 0.6f
                    }
                    5 -> {
                        lfo += 2.0 * PI * 6.0 / rate
                        if (lfo > 2.0 * PI) lfo -= 2.0 * PI
                        val r = 1.25f + 0.25f * sin(lfo).toFloat()
                        y = shift(r, x)
                    }
                    6 -> y = shift(0.55f, x) * 1.3f
                    else -> y = x
                }
                if (abs(y) > 0.98f) y = if (y > 0) 0.98f else -0.98f
                output[i] = (y * 32767f).toInt().toShort()
            }
        }
    }
}
