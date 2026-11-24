package com.zeta.board

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.Process
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

/**
 * Microphone -> effects -> headphones, all on one dedicated audio thread that owns every audio resource.
 *
 * Live effects: AudioRecord is read in 512-sample blocks, run through [EffectChain] and written straight to a
 * low-latency AudioTrack. Clip effects (Slow / Fast): up to [MAX_CLIP_SEC] seconds are recorded into memory, then the
 * stretched result is played. Nothing is ever written to disk or sent anywhere.
 *
 * The UI talks to the engine only through the @Volatile settings and [start] / [stop] / [abort]; results come back to
 * the main thread through [Listener].
 */
class VoiceEngine(context: Context) {

    interface Listener {
        /** Always called on the main thread. [message] explains why the engine ended abnormally (or null). */
        fun onEngineState(state: Int, message: String?)
    }

    private class EngineException(msg: String) : Exception(msg)

    companion object {
        const val IDLE = 0
        const val LIVE = 1
        const val CAPTURING = 2
        const val PLAYING = 3
        const val STARTING = 4

        const val SAMPLE_RATE = 44100
        const val BLOCK = 512
        const val WAVE_N = 64
        const val MAX_CLIP_SEC = 12
    }

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val audio = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    @Volatile var listener: Listener? = null

    // settings, written by the UI thread and read by the audio thread once per block
    @Volatile var effectId = 0
    @Volatile var pitchSemis = 0
    @Volatile var speed = 1f
    @Volatile var volume = 1f

    // meters for the UI (benign races: only drawn)
    @Volatile var level = 0f
    val wave = FloatArray(WAVE_N)
    @Volatile var waveHead = 0

    @Volatile var state = IDLE
        private set
    private val busy = AtomicBoolean(false)
    @Volatile private var abortFlag = false
    @Volatile private var finishFlag = false
    @Volatile private var abortReason: String? = null
    @Volatile private var focusReq: AudioFocusRequest? = null

    // owned by the audio thread
    private var rec: AudioRecord? = null
    private var track: AudioTrack? = null

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            abort("Stopped: another app took the audio")
        }
    }

    val isBusy: Boolean get() = busy.get()

    /** Starts the engine. Returns false if it is still running / shutting down. Needs the microphone permission. */
    fun start(clipMode: Boolean): Boolean {
        if (!busy.compareAndSet(false, true)) return false
        abortFlag = false
        finishFlag = false
        abortReason = null
        level = 0f
        java.util.Arrays.fill(wave, 0f)
        state = STARTING
        requestFocus()
        val t = Thread({ runEngine(clipMode) }, "ZetaVoiceEngine")
        try {
            t.start()
        } catch (e: Throwable) {
            abandonFocus()
            state = IDLE
            busy.set(false)
            return false
        }
        return true
    }

    /** User pressed Stop. While a clip is being recorded this means "finish and play it". */
    fun stop() {
        if (state == CAPTURING) finishFlag = true else abortFlag = true
    }

    /** Ends everything now (leaving the screen, headphones removed, interruption, ...). Safe from any thread. */
    fun abort(reason: String? = null) {
        if (reason != null && abortReason == null) abortReason = reason
        abortFlag = true
    }

    private fun emit(s: Int, msg: String? = null) {
        state = s
        main.post { listener?.onEngineState(s, msg) }
    }

    private fun requestFocus() {
        try {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val r = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener(focusListener, main)
                .build()
            focusReq = r
            audio.requestAudioFocus(r)
        } catch (e: Exception) {
            focusReq = null
        }
    }

    private fun abandonFocus() {
        val r = focusReq ?: return
        focusReq = null
        try {
            audio.abandonAudioFocusRequest(r)
        } catch (e: Exception) {
        }
    }

    private fun createRecord(): AudioRecord? {
        return try {
            val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (min <= 0) return null
            val r = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build()
                )
                .setBufferSizeInBytes(max(min, BLOCK * 2 * 4))
                .build()
            if (r.state != AudioRecord.STATE_INITIALIZED) {
                r.release()
                null
            } else r
        } catch (e: Exception) {
            null
        }
    }

    private fun createTrack(): AudioTrack? {
        return try {
            val min = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (min <= 0) return null
            val t = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(max(min, BLOCK * 2 * 2))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
            if (t.state != AudioTrack.STATE_INITIALIZED) {
                t.release()
                null
            } else t
        } catch (e: Exception) {
            null
        }
    }

    private fun runEngine(clip: Boolean) {
        var error: String? = null
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val r = createRecord() ?: throw EngineException("Could not open the microphone. Is another app using it?")
            rec = r
            if (!clip) {
                track = createTrack() ?: throw EngineException("Could not open audio playback on this device")
            }
            if (abortFlag) return
            r.startRecording()
            if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw EngineException("The microphone is busy. Close other apps that use it and try again.")
            }
            if (clip) runClip(r) else runLive(r, track!!)
        } catch (e: EngineException) {
            error = e.message
        } catch (e: SecurityException) {
            error = "Microphone permission was denied"
        } catch (e: Exception) {
            error = "Audio error (" + e.javaClass.simpleName + ")"
        } finally {
            val r = rec
            rec = null
            if (r != null) {
                try { r.stop() } catch (e: Exception) { }
                try { r.release() } catch (e: Exception) { }
            }
            val t = track
            track = null
            if (t != null) {
                try { t.pause() } catch (e: Exception) { }
                try { t.flush() } catch (e: Exception) { }
                try { t.release() } catch (e: Exception) { }
            }
            abandonFocus()
            level = 0f
            val msg = error ?: abortReason
            state = IDLE
            busy.set(false)
            main.post { listener?.onEngineState(IDLE, msg) }
        }
    }

    private fun publish(peak: Float) {
        level = max(peak, level * 0.8f)
        val h = waveHead
        wave[h and (WAVE_N - 1)] = peak
        waveHead = h + 1
    }

    private fun runLive(r: AudioRecord, t: AudioTrack) {
        val inS = ShortArray(BLOCK)
        val outS = ShortArray(BLOCK)
        val f = FloatArray(BLOCK)
        val chain = EffectChain(SAMPLE_RATE)
        t.play()
        emit(LIVE)
        var empty = 0
        while (!abortFlag) {
            val n = r.read(inS, 0, BLOCK)
            if (n < 0) throw EngineException("The microphone stopped working (it may have been disconnected)")
            if (n == 0) {
                if (++empty > 200) throw EngineException("The microphone is not delivering audio")
                continue
            }
            empty = 0
            for (i in 0 until n) f[i] = inS[i] * (1f / 32768f)
            chain.configure(effectId, pitchSemis.toFloat())
            chain.process(f, n)
            publish(Dsp.finish(f, n, volume, outS))
            writeAll(t, outS, n)
        }
    }

    private fun writeAll(t: AudioTrack, s: ShortArray, n: Int) {
        var off = 0
        var stall = 0
        while (off < n && !abortFlag) {
            val w = t.write(s, off, n - off)
            if (w < 0) throw EngineException("Audio playback failed (the output device may have changed)")
            if (w == 0) {
                if (++stall > 100) throw EngineException("Audio playback stalled")
            } else {
                stall = 0
                off += w
            }
        }
    }

    private fun runClip(r: AudioRecord) {
        val cap = SAMPLE_RATE * MAX_CLIP_SEC
        val raw = FloatArray(cap)
        val tmp = ShortArray(BLOCK)
        var len = 0
        emit(CAPTURING)
        var empty = 0
        while (!abortFlag && !finishFlag && len < cap) {
            val n = r.read(tmp, 0, min(BLOCK, cap - len))
            if (n < 0) throw EngineException("The microphone stopped working (it may have been disconnected)")
            if (n == 0) {
                if (++empty > 200) throw EngineException("The microphone is not delivering audio")
                continue
            }
            empty = 0
            var peak = 0f
            for (i in 0 until n) {
                val v = tmp[i] * (1f / 32768f)
                raw[len + i] = v
                val a = if (v < 0f) -v else v
                if (a > peak) peak = a
            }
            len += n
            publish(peak)
        }
        if (abortFlag) return
        try { r.stop() } catch (e: Exception) { }   // the microphone is off from here on
        if (len < SAMPLE_RATE / 4) throw EngineException("Too short. Speak for at least a second, then tap Finish.")

        // processing (still on the audio thread)
        val fx = effectId
        val chain = EffectChain(SAMPLE_RATE)
        chain.configure(fx, pitchSemis.toFloat())
        val blk = FloatArray(BLOCK)
        var pos = 0
        while (pos < len) {
            val n = min(BLOCK, len - pos)
            System.arraycopy(raw, pos, blk, 0, n)
            chain.process(blk, n)
            System.arraycopy(blk, 0, raw, pos, n)
            pos += n
        }
        val sp = speed
        val result = if (sp > 0.98f && sp < 1.02f) raw else TimeStretch.stretch(raw, len, sp)
        val total = if (result === raw) len else result.size
        if (abortFlag) return

        val t = createTrack() ?: throw EngineException("Could not open audio playback on this device")
        track = t
        t.play()
        emit(PLAYING)
        val outS = ShortArray(BLOCK)
        pos = 0
        while (pos < total && !abortFlag) {
            val n = min(BLOCK, total - pos)
            System.arraycopy(result, pos, blk, 0, n)
            publish(Dsp.finish(blk, n, volume, outS))
            writeAll(t, outS, n)
            pos += n
        }
        if (abortFlag) return
        // let the queued audio finish
        try { t.stop() } catch (e: Exception) { }
        val deadline = System.currentTimeMillis() + 3000L
        while (!abortFlag && t.playbackHeadPosition < total && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(20) } catch (e: InterruptedException) { break }
        }
    }
}
