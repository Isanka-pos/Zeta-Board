package com.zeta.board

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * ZETA LIVE VOICE CHANGER: speak into the microphone and hear the changed voice through headphones, inside Zeta Board.
 * (Android does not let a keyboard app replace the microphone of other apps, so this does not change calls or chats.)
 */
class LiveVoiceActivity : Activity(), VoiceEngine.Listener {

    private val dens: Float by lazy { resources.displayMetrics.density }
    private fun dp(v: Int): Int = (v * dens).toInt()
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    private val BLUE = Color.parseColor("#3B82F6")
    private val RED = Color.parseColor("#EF4444")
    private val AMBER = Color.parseColor("#F59E0B")
    private val REQ_MIC = 77

    private lateinit var engine: VoiceEngine
    private lateinit var audio: AudioManager
    private val ui = Handler(Looper.getMainLooper())

    private var selected = 0
    private var engState = VoiceEngine.IDLE
    private var permBlocked = false
    private var lastMsg: String? = null

    private lateinit var content: LinearLayout
    private lateinit var gridHost: LinearLayout
    private lateinit var micView: MicIndicatorView
    private lateinit var waveView: WaveformView
    private lateinit var liveBanner: TextView
    private lateinit var statusText: TextView
    private lateinit var msgText: TextView
    private lateinit var currentFx: TextView
    private lateinit var startBtn: TextView
    private lateinit var stopBtn: TextView
    private lateinit var micStatus: TextView
    private lateinit var micAllow: TextView
    private lateinit var hpStatus: TextView
    private lateinit var speakerSwitch: Switch
    private lateinit var pitchLabel: TextView
    private lateinit var speedLabel: TextView
    private lateinit var volLabel: TextView
    private lateinit var pitchBar: SeekBar
    private lateinit var speedBar: SeekBar
    private lateinit var volBar: SeekBar
    private val cardViews = ArrayList<LinearLayout>()

    private val deviceCb = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = devicesChanged()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = devicesChanged()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        engine = VoiceEngine(this)
        engine.listener = this

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Pal.BG)
        root.addView(topBar(), LinearLayout.LayoutParams(MATCH, dp(64)))

        content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        content.setPadding(dp(16), dp(4), dp(16), dp(32))
        content.addView(heroCard())
        content.addView(statusCard())
        content.addView(sectionTitle("Effects"))
        gridHost = LinearLayout(this)
        gridHost.orientation = LinearLayout.VERTICAL
        content.addView(gridHost, LinearLayout.LayoutParams(MATCH, WRAP))
        content.addView(controlsCard())
        content.addView(infoCard())

        val holder = FrameLayout(this)
        holder.addView(content, FrameLayout.LayoutParams(contentWidth(), WRAP, Gravity.CENTER_HORIZONTAL))
        val sv = ScrollView(this)
        sv.isVerticalScrollBarEnabled = false
        sv.addView(holder, ViewGroup.LayoutParams(MATCH, WRAP))
        root.addView(sv, LinearLayout.LayoutParams(MATCH, 0, 1f))
        setContentView(root)

        buildGrid()
        applyEffectDefaults(false)
        syncEngine()
        try {
            audio.registerAudioDeviceCallback(deviceCb, ui)
        } catch (e: Exception) {
        }
        refreshUi()
    }

    override fun onResume() {
        super.onResume()
        if (hasMic()) permBlocked = false
        refreshUi()
    }

    override fun onStop() {
        // never keep listening in the background
        engine.abort()
        super.onStop()
    }

    override fun onDestroy() {
        engine.listener = null
        engine.abort()
        try {
            audio.unregisterAudioDeviceCallback(deviceCb)
        } catch (e: Exception) {
        }
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val lp = content.layoutParams as FrameLayout.LayoutParams
        lp.width = contentWidth()
        content.layoutParams = lp
        buildGrid()
    }

    private fun contentWidth(): Int = min(resources.displayMetrics.widthPixels, dp(640))

    // ---------- engine callbacks ----------

    override fun onEngineState(state: Int, message: String?) {
        engState = state
        lastMsg = if (state == VoiceEngine.IDLE) message else null
        if (state == VoiceEngine.IDLE) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        refreshUi()
    }

    private fun devicesChanged() {
        if (isFinishing || isDestroyed) return
        if (engState == VoiceEngine.LIVE && !headphones() && !speakerSwitch.isChecked) {
            engine.abort("Headphones were disconnected, so live monitoring stopped to avoid feedback.")
        }
        refreshUi()
    }

    // ---------- permissions and devices ----------

    private fun hasMic(): Boolean = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun requestMic() {
        if (permBlocked) {
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            } catch (e: Exception) {
            }
        } else {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC) {
            val ok = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            if (!ok) permBlocked = !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
            refreshUi()
        }
    }

    private fun headphones(): Boolean {
        return try {
            for (d in audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                when (d.type) {
                    AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET,
                    AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_HEARING_AID,
                    AudioDeviceInfo.TYPE_BLE_HEADSET -> return true
                }
            }
            false
        } catch (e: Exception) {
            false
        }
    }

    // ---------- start / stop ----------

    private fun onStart2() {
        val fx = VoiceEffects.ALL[selected]
        if (!hasMic()) {
            requestMic()
            return
        }
        if (!fx.clip && !headphones() && !speakerSwitch.isChecked) {
            lastMsg = "Connect headphones to monitor your voice live. Without them the microphone would hear the speaker and howl."
            refreshUi()
            return
        }
        syncEngine()
        lastMsg = null
        if (engine.start(fx.clip)) {
            engState = VoiceEngine.STARTING
        } else {
            lastMsg = "Still shutting down. Try again in a moment."
        }
        refreshUi()
    }

    private fun syncEngine() {
        engine.effectId = selected
        engine.pitchSemis = pitchBar.progress - 12
        engine.speed = 0.5f + speedBar.progress / 100f
        engine.volume = volBar.progress / 100f
    }

    // ---------- UI building ----------

    private fun roundBg(color: Int, r: Int, strokeColor: Int = 0, stroke: Int = 0): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = dp(r).toFloat()
        if (stroke > 0) g.setStroke(dp(stroke), strokeColor)
        return g
    }

    private fun grad(c1: Int, c2: Int, r: Int): GradientDrawable {
        val g = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(c1, c2))
        g.cornerRadius = dp(r).toFloat()
        return g
    }

    private fun label(text: String, size: Float, color: Int, bold: Boolean = false): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = size
        t.setTextColor(color)
        if (bold) t.typeface = Typeface.DEFAULT_BOLD
        return t
    }

    private fun sectionTitle(s: String): View {
        val t = label(s, 16f, Color.WHITE, true)
        t.setPadding(dp(4), dp(22), 0, dp(10))
        return t
    }

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
        t.addView(label("Live Voice Changer", 20f, Color.WHITE, true))
        t.addView(label("Real-time effects, processed on your phone", 12f, Pal.MUTED))
        bar.addView(t, LinearLayout.LayoutParams(0, WRAP, 1f))
        return bar
    }

    private fun bigButton(text: String, c1: Int, c2: Int, onClick: () -> Unit): TextView {
        val t = label(text, 16f, Color.WHITE, true)
        t.gravity = Gravity.CENTER
        t.setPadding(dp(12), dp(15), dp(12), dp(15))
        t.background = RippleDrawable(ColorStateList.valueOf(Color.argb(70, 255, 255, 255)), grad(c1, c2, 18), null)
        t.setOnClickListener { onClick() }
        return t
    }

    private fun heroCard(): View {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.gravity = Gravity.CENTER_HORIZONTAL
        c.setPadding(dp(18), dp(18), dp(18), dp(18))
        val bg = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.parseColor("#2A1759"), Color.parseColor("#12204D")))
        bg.cornerRadius = dp(26).toFloat()
        bg.setStroke(dp(1), Pal.STROKE)
        c.background = bg

        liveBanner = label("\u25CF  MICROPHONE ACTIVE", 12f, Color.WHITE, true)
        liveBanner.gravity = Gravity.CENTER
        liveBanner.setPadding(dp(14), dp(5), dp(14), dp(5))
        liveBanner.background = roundBg(RED, 20)
        c.addView(liveBanner, LinearLayout.LayoutParams(WRAP, WRAP))

        micView = MicIndicatorView(this, engine)
        val mlp = LinearLayout.LayoutParams(dp(150), dp(150))
        mlp.topMargin = dp(6)
        c.addView(micView, mlp)

        currentFx = label("", 18f, Color.WHITE, true)
        currentFx.gravity = Gravity.CENTER
        c.addView(currentFx, LinearLayout.LayoutParams(MATCH, WRAP))
        statusText = label("", 13f, Pal.MUTED)
        statusText.gravity = Gravity.CENTER
        statusText.setPadding(0, dp(4), 0, 0)
        c.addView(statusText, LinearLayout.LayoutParams(MATCH, WRAP))
        msgText = label("", 12.5f, AMBER)
        msgText.gravity = Gravity.CENTER
        msgText.setPadding(0, dp(6), 0, 0)
        c.addView(msgText, LinearLayout.LayoutParams(MATCH, WRAP))

        waveView = WaveformView(this, engine)
        val wlp = LinearLayout.LayoutParams(MATCH, dp(72))
        wlp.topMargin = dp(12)
        c.addView(waveView, wlp)

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        startBtn = bigButton("Start", Pal.PURPLE, BLUE) { onStart2() }
        stopBtn = bigButton("Stop", Color.parseColor("#F43F5E"), Color.parseColor("#B91C1C")) { engine.stop() }
        val l1 = LinearLayout.LayoutParams(0, WRAP, 1f)
        l1.rightMargin = dp(10)
        row.addView(startBtn, l1)
        row.addView(stopBtn, LinearLayout.LayoutParams(0, WRAP, 1f))
        val rlp = LinearLayout.LayoutParams(MATCH, WRAP)
        rlp.topMargin = dp(16)
        c.addView(row, rlp)

        val lp = LinearLayout.LayoutParams(MATCH, WRAP)
        lp.topMargin = dp(8)
        c.layoutParams = lp
        return c
    }

    private fun statusCard(): View {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.setPadding(dp(18), dp(14), dp(18), dp(14))
        c.background = roundBg(Pal.CARD, 22, Pal.STROKE, 1)
        val lp = LinearLayout.LayoutParams(MATCH, WRAP)
        lp.topMargin = dp(14)
        c.layoutParams = lp

        val r1 = LinearLayout(this)
        r1.orientation = LinearLayout.HORIZONTAL
        r1.gravity = Gravity.CENTER_VERTICAL
        micStatus = label("", 13.5f, Color.WHITE)
        r1.addView(micStatus, LinearLayout.LayoutParams(0, WRAP, 1f))
        micAllow = label("Allow", 12.5f, Color.WHITE, true)
        micAllow.gravity = Gravity.CENTER
        micAllow.setPadding(dp(16), dp(8), dp(16), dp(8))
        micAllow.background = grad(Pal.PURPLE, BLUE, 20)
        micAllow.setOnClickListener { requestMic() }
        r1.addView(micAllow, LinearLayout.LayoutParams(WRAP, WRAP))
        c.addView(r1, LinearLayout.LayoutParams(MATCH, WRAP))

        hpStatus = label("", 13.5f, Color.WHITE)
        hpStatus.setPadding(0, dp(10), 0, 0)
        c.addView(hpStatus, LinearLayout.LayoutParams(MATCH, WRAP))

        val r2 = LinearLayout(this)
        r2.orientation = LinearLayout.HORIZONTAL
        r2.gravity = Gravity.CENTER_VERTICAL
        r2.setPadding(0, dp(10), 0, 0)
        val txt = LinearLayout(this)
        txt.orientation = LinearLayout.VERTICAL
        txt.addView(label("Allow phone speaker", 13.5f, Color.WHITE))
        txt.addView(label("Off by default. Causes loud feedback if the volume is high.", 11.5f, Pal.MUTED))
        r2.addView(txt, LinearLayout.LayoutParams(0, WRAP, 1f))
        speakerSwitch = Switch(this)
        speakerSwitch.isChecked = false
        speakerSwitch.setOnCheckedChangeListener { _, on ->
            if (!on && engState == VoiceEngine.LIVE && !headphones()) {
                engine.abort("Live monitoring stopped: the phone speaker is switched off and no headphones are connected.")
            }
            refreshUi()
        }
        r2.addView(speakerSwitch, LinearLayout.LayoutParams(WRAP, WRAP))
        c.addView(r2, LinearLayout.LayoutParams(MATCH, WRAP))
        return c
    }

    private fun contentInnerWidth(): Int = contentWidth() - dp(32)

    private fun buildGrid() {
        gridHost.removeAllViews()
        cardViews.clear()
        val cols = if (contentInnerWidth() >= dp(480)) 3 else 2
        var i = 0
        val all = VoiceEffects.ALL
        while (i < all.size) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            for (k in 0 until cols) {
                val idx = i + k
                val lp = LinearLayout.LayoutParams(0, WRAP, 1f)
                lp.setMargins(dp(4), dp(4), dp(4), dp(4))
                if (idx < all.size) {
                    val cv = effectCard(all[idx])
                    cardViews.add(cv)
                    row.addView(cv, lp)
                } else {
                    row.addView(View(this), lp)
                }
            }
            gridHost.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
            i += cols
        }
        styleCards()
    }

    private fun effectCard(fx: VoiceEffect): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.setPadding(dp(14), dp(14), dp(14), dp(14))
        c.minimumHeight = dp(112)
        c.tag = fx.id
        val ic = label(fx.icon, 26f, Color.WHITE)
        c.addView(ic, LinearLayout.LayoutParams(WRAP, WRAP))
        val n = label(fx.name, 14.5f, Color.WHITE, true)
        n.setPadding(0, dp(6), 0, 0)
        c.addView(n, LinearLayout.LayoutParams(MATCH, WRAP))
        val d = label(fx.desc, 11f, Color.argb(200, 220, 214, 245))
        d.setPadding(0, dp(2), 0, 0)
        c.addView(d, LinearLayout.LayoutParams(MATCH, WRAP))
        if (fx.clip) {
            val tag = label("RECORD + PLAY", 9.5f, Color.WHITE, true)
            tag.setPadding(dp(8), dp(2), dp(8), dp(2))
            tag.background = roundBg(Color.argb(90, 0, 0, 0), 8)
            val tl = LinearLayout.LayoutParams(WRAP, WRAP)
            tl.topMargin = dp(6)
            c.addView(tag, tl)
        }
        c.setOnClickListener { selectEffect(fx.id) }
        return c
    }

    private fun styleCards() {
        for (cv in cardViews) {
            val id = cv.tag as Int
            val fx = VoiceEffects.ALL[id]
            if (id == selected) {
                val g = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(fx.c1, fx.c2))
                g.cornerRadius = dp(20).toFloat()
                g.setStroke(dp(2), Color.WHITE)
                cv.background = g
            } else {
                cv.background = RippleDrawable(
                    ColorStateList.valueOf(Color.argb(60, 168, 85, 247)),
                    roundBg(Pal.TILE, 20, Pal.STROKE, 1), null
                )
            }
        }
    }

    private fun selectEffect(id: Int) {
        if (id == selected) return
        val oldClip = VoiceEffects.ALL[selected].clip
        selected = id
        applyEffectDefaults(true)
        syncEngine()
        styleCards()
        // live <-> clip needs a different engine setup: stop and let the user press Start again
        if (engState != VoiceEngine.IDLE && VoiceEffects.ALL[id].clip != oldClip) {
            engine.abort("Effect changed. Press Start to use it.")
        }
        refreshUi()
    }

    /** Clip effects start at their own speed; live effects keep the speed slider off (1.0x). */
    private fun applyEffectDefaults(fromUser: Boolean) {
        val fx = VoiceEffects.ALL[selected]
        if (fx.clip) {
            speedBar.progress = ((fx.defaultSpeed - 0.5f) * 100f + 0.5f).toInt()
        } else {
            speedBar.progress = 50
        }
        speedBar.isEnabled = fx.clip
        speedBar.alpha = if (fx.clip) 1f else 0.4f
        speedLabel.alpha = if (fx.clip) 1f else 0.5f
        updateLabels()
    }

    private fun seek(max: Int, progress: Int, onChange: (Int) -> Unit): SeekBar {
        val s = SeekBar(this)
        s.max = max
        s.progress = progress
        s.progressTintList = ColorStateList.valueOf(Pal.PURPLE)
        s.thumbTintList = ColorStateList.valueOf(Pal.CYAN)
        s.progressBackgroundTintList = ColorStateList.valueOf(Pal.STROKE)
        s.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                onChange(p)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        return s
    }

    private fun controlsCard(): View {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.setPadding(dp(18), dp(16), dp(18), dp(16))
        c.background = roundBg(Pal.CARD, 22, Pal.STROKE, 1)
        val lp = LinearLayout.LayoutParams(MATCH, WRAP)
        lp.topMargin = dp(16)
        c.layoutParams = lp

        pitchLabel = label("", 13.5f, Color.WHITE, true)
        c.addView(pitchLabel)
        pitchBar = seek(24, 12) { syncEngine(); updateLabels() }
        c.addView(pitchBar, LinearLayout.LayoutParams(MATCH, WRAP))

        speedLabel = label("", 13.5f, Color.WHITE, true)
        speedLabel.setPadding(0, dp(12), 0, 0)
        c.addView(speedLabel)
        speedBar = seek(150, 50) { syncEngine(); updateLabels() }
        c.addView(speedBar, LinearLayout.LayoutParams(MATCH, WRAP))
        val sn = label("Speed only applies to Slow Voice and Fast Voice (they keep the pitch).", 11f, Pal.MUTED)
        c.addView(sn)

        volLabel = label("", 13.5f, Color.WHITE, true)
        volLabel.setPadding(0, dp(12), 0, 0)
        c.addView(volLabel)
        volBar = seek(200, 100) { syncEngine(); updateLabels() }
        c.addView(volBar, LinearLayout.LayoutParams(MATCH, WRAP))
        return c
    }

    private fun updateLabels() {
        val st = pitchBar.progress - 12
        pitchLabel.text = "Pitch   " + (if (st > 0) "+$st" else "$st") + " semitones"
        val sp = 0.5f + speedBar.progress / 100f
        speedLabel.text = "Speed   " + String.format(java.util.Locale.US, "%.2f", sp) + "x"
        volLabel.text = "Volume   " + volBar.progress + "%"
    }

    private fun infoCard(): View {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.setPadding(dp(18), dp(16), dp(18), dp(16))
        c.background = roundBg(Pal.CARD, 22, Pal.STROKE, 1)
        val lp = LinearLayout.LayoutParams(MATCH, WRAP)
        lp.topMargin = dp(16)
        c.layoutParams = lp
        c.addView(label("Good to know", 15f, Color.WHITE, true))
        val t = label(
            "\u2022 Use headphones. The changed voice is played live; on the phone speaker the microphone would pick it up again and howl (feedback).\n" +
                "\u2022 Wired headphones give the lowest delay. Bluetooth adds a clear delay (often 150-300 ms) and may lower the microphone quality.\n" +
                "\u2022 Live delay is usually 30-100 ms, depending on the phone.\n" +
                "\u2022 Slow and Fast cannot run live (the sound would drift away from your speech). They record up to 12 seconds in memory, then play the result. Pitch stays natural.\n" +
                "\u2022 Pitch effects use a real-time granular pitch shifter: great for fun voices, with a slightly grainy sound.\n" +
                "\u2022 This changes your voice inside Zeta Board only. Android does not let a keyboard app replace the microphone of WhatsApp, phone calls or other apps.\n" +
                "\u2022 Audio is processed on this phone only. Nothing is saved or uploaded.",
            12.5f, Pal.MUTED
        )
        t.setLineSpacing(0f, 1.15f)
        t.setPadding(0, dp(8), 0, 0)
        c.addView(t)
        return c
    }

    // ---------- state -> UI ----------

    private fun setBtn(b: TextView, on: Boolean) {
        b.isEnabled = on
        b.alpha = if (on) 1f else 0.38f
    }

    private fun refreshUi() {
        if (isFinishing || isDestroyed) return
        val fx = VoiceEffects.ALL[selected]
        val mic = hasMic()
        val hp = headphones()
        val running = engState != VoiceEngine.IDLE
        val micOn = engState == VoiceEngine.LIVE || engState == VoiceEngine.CAPTURING

        micStatus.text = when {
            mic -> "\u2713  Microphone permission: allowed"
            permBlocked -> "\u2717  Microphone permission: blocked"
            else -> "\u2717  Microphone permission: not allowed yet"
        }
        micStatus.setTextColor(if (mic) Pal.GREEN else AMBER)
        micAllow.visibility = if (mic) View.GONE else View.VISIBLE
        micAllow.text = if (permBlocked) "Open settings" else "Allow"

        hpStatus.text = if (hp) "\u2713  Headphones: connected" else "\u2717  Headphones: not connected"
        hpStatus.setTextColor(if (hp) Pal.GREEN else AMBER)

        currentFx.text = fx.icon + "  " + fx.name
        statusText.text = when (engState) {
            VoiceEngine.STARTING -> "Starting..."
            VoiceEngine.LIVE -> "LIVE: you are hearing your changed voice"
            VoiceEngine.CAPTURING -> "Recording... tap \"Finish & play\" (max 12 s)"
            VoiceEngine.PLAYING -> "Playing the changed clip"
            else -> if (fx.clip) "Ready. Records a short clip, then plays it back." else "Ready. Put on headphones and press Start."
        }
        statusText.setTextColor(if (micOn) Color.WHITE else Pal.MUTED)
        val m = lastMsg
        msgText.text = m ?: ""
        msgText.visibility = if (m.isNullOrEmpty()) View.GONE else View.VISIBLE

        liveBanner.visibility = if (micOn || engState == VoiceEngine.STARTING) View.VISIBLE else View.GONE
        micView.active = micOn
        waveView.active = micOn || engState == VoiceEngine.PLAYING

        setBtn(startBtn, !running)
        setBtn(stopBtn, running && engState != VoiceEngine.STARTING)
        stopBtn.text = if (engState == VoiceEngine.CAPTURING) "Finish & play" else "Stop"
    }
}

/** Pulsing microphone badge: rings and size follow the live input level while the microphone is on. */
class MicIndicatorView(ctx: Context, private val engine: VoiceEngine) : View(ctx) {

    var active = false
        set(v) {
            field = v
            invalidate()
        }

    private val d = ctx.resources.displayMetrics.density
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val icon: Drawable? = ctx.getDrawable(R.drawable.ic_mic)?.mutate()
    private var shaderOn: Shader? = null
    private var shaderOff: Shader? = null
    private val t0 = System.nanoTime()

    init {
        icon?.setTint(Color.WHITE)
        ringPaint.style = Paint.Style.STROKE
        ringPaint.strokeWidth = 2.5f * d
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        shaderOn = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), Color.parseColor("#A855F7"), Color.parseColor("#3B82F6"), Shader.TileMode.CLAMP)
        shaderOff = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), Color.parseColor("#3B3563"), Color.parseColor("#2A2350"), Shader.TileMode.CLAMP)
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val base = min(width, height) * 0.26f
        val t = (System.nanoTime() - t0) / 1e9f
        val lvl = if (active) min(1f, sqrt(engine.level)) else 0f
        if (active) {
            for (i in 0..2) {
                val ph = (t * 0.8f + i / 3f) % 1f
                val r = base * (1.15f + ph * 0.9f) + base * lvl * 0.35f
                ringPaint.color = Color.argb(((1f - ph) * 130f).toInt(), 129, 140, 248)
                c.drawCircle(cx, cy, r, ringPaint)
            }
        }
        corePaint.shader = if (active) shaderOn else shaderOff
        c.drawCircle(cx, cy, base * (1f + lvl * 0.14f), corePaint)
        val s = (base * 1.0f).toInt()
        icon?.setBounds((cx - s / 2f).toInt(), (cy - s / 2f).toInt(), (cx + s / 2f).toInt(), (cy + s / 2f).toInt())
        icon?.draw(c)
        if (active) postInvalidateOnAnimation()
    }
}

/** Scrolling bars of the recent audio level (the newest on the right). */
class WaveformView(ctx: Context, private val engine: VoiceEngine) : View(ctx) {

    var active = false
        set(v) {
            field = v
            invalidate()
        }

    private val d = ctx.resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        paint.shader = LinearGradient(0f, 0f, w.toFloat(), 0f, Color.parseColor("#A855F7"), Color.parseColor("#22D3EE"), Shader.TileMode.CLAMP)
    }

    override fun onDraw(c: Canvas) {
        val n = VoiceEngine.WAVE_N
        val w = width.toFloat()
        val h = height.toFloat()
        val slot = w / n
        val barW = max(1.5f * d, slot * 0.55f)
        val head = engine.waveHead
        val mid = h / 2f
        for (i in 0 until n) {
            val v = if (active) engine.wave[((head - n + i) % n + n) % n] else 0f
            val bh = max(3f * d, sqrt(min(1f, v * 1.6f)) * h)
            val x = slot * i + slot / 2f
            rect.set(x - barW / 2f, mid - bh / 2f, x + barW / 2f, mid + bh / 2f)
            c.drawRoundRect(rect, barW / 2f, barW / 2f, paint)
        }
        if (active) postInvalidateOnAnimation()
    }
}
