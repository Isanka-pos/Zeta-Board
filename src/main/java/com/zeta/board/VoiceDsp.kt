package com.zeta.board

import android.graphics.Color
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * One voice effect shown on a card. [clip] effects (Slow / Fast) cannot run in real time, so they record a short clip
 * in memory and play the result back. [presetSemis] is the pitch shift the effect always applies.
 */
class VoiceEffect(
    val id: Int,
    val name: String,
    val desc: String,
    val icon: String,
    val c1: Int,
    val c2: Int,
    val clip: Boolean,
    val presetSemis: Float,
    val defaultSpeed: Float
)

object VoiceEffects {
    const val ROBOT = 0
    const val DEEP = 1
    const val MONSTER = 2
    const val BABY = 3
    const val CHIPMUNK = 4
    const val ALIEN = 5
    const val ECHO = 6
    const val REVERB = 7
    const val SLOW = 8
    const val FAST = 9

    /** The list index of an effect is its id. */
    val ALL: List<VoiceEffect> = listOf(
        VoiceEffect(ROBOT, "Robot Voice", "Ring modulation + metallic comb", "\uD83E\uDD16", Color.parseColor("#6366F1"), Color.parseColor("#22D3EE"), false, 0f, 1f),
        VoiceEffect(DEEP, "Deep Voice", "Pitch down 5 semitones", "\uD83C\uDF0A", Color.parseColor("#4F46E5"), Color.parseColor("#7C3AED"), false, -5f, 1f),
        VoiceEffect(MONSTER, "Monster Voice", "Pitch down 8, growl + drive", "\uD83D\uDC79", Color.parseColor("#9333EA"), Color.parseColor("#BE185D"), false, -8f, 1f),
        VoiceEffect(BABY, "Baby Voice", "Pitch up 6 semitones", "\uD83D\uDC76", Color.parseColor("#A855F7"), Color.parseColor("#EC4899"), false, 6f, 1f),
        VoiceEffect(CHIPMUNK, "Chipmunk Voice", "Pitch up 10 semitones", "\uD83D\uDC3F", Color.parseColor("#8B5CF6"), Color.parseColor("#3B82F6"), false, 10f, 1f),
        VoiceEffect(ALIEN, "Alien Voice", "Wobbling pitch + ring mod", "\uD83D\uDC7D", Color.parseColor("#14B8A6"), Color.parseColor("#6366F1"), false, 3f, 1f),
        VoiceEffect(ECHO, "Echo", "330 ms repeating delay", "\uD83D\uDD0A", Color.parseColor("#3B82F6"), Color.parseColor("#6366F1"), false, 0f, 1f),
        VoiceEffect(REVERB, "Reverb", "Room reverb (4 comb + 2 allpass)", "\uD83C\uDFDB", Color.parseColor("#7C3AED"), Color.parseColor("#3B82F6"), false, 0f, 1f),
        VoiceEffect(SLOW, "Slow Voice", "Record, then play slower (same pitch)", "\uD83D\uDC22", Color.parseColor("#0EA5E9"), Color.parseColor("#6366F1"), true, 0f, 0.65f),
        VoiceEffect(FAST, "Fast Voice", "Record, then play faster (same pitch)", "\u26A1", Color.parseColor("#F59E0B"), Color.parseColor("#A855F7"), true, 0f, 1.6f)
    )
}

/**
 * Real-time pitch shifter: a delay line with two read heads that sweep through it at a different speed than the write
 * head, cross-faded with a Hann window. The duration of the sound does not change (one sample in, one sample out), so
 * it can run live. It is a simple granular shifter: fine for voice effects, with the slight "grainy" colour that
 * this technique always has.
 */
class PitchShifter(sampleRate: Int) {
    private val win = max(64, (sampleRate * 0.035f).toInt())
    private val size: Int
    private val mask: Int
    private val buf: FloatArray
    private var w = 0
    private var phase = 0f

    init {
        var s = 1
        while (s < win * 2) s = s shl 1
        size = s
        mask = s - 1
        buf = FloatArray(s)
    }

    fun reset() {
        java.util.Arrays.fill(buf, 0f)
        w = 0
        phase = 0f
    }

    private fun tap(d: Float): Float {
        var p = w - d
        if (p < 0f) p += size
        val i0 = p.toInt() and mask
        val fr = p - p.toInt()
        val i1 = (i0 + 1) and mask
        return buf[i0] * (1f - fr) + buf[i1] * fr
    }

    /** Shifts [x] (first [n] samples) in place. [ratio] > 1 raises the pitch, < 1 lowers it. */
    fun process(x: FloatArray, n: Int, ratio: Float) {
        val winF = win.toFloat()
        val inc = (1f - ratio) / winF
        val twoPi = (2.0 * PI).toFloat()
        for (i in 0 until n) {
            buf[w] = x[i]
            val pa = phase
            var pb = pa + 0.5f
            if (pb >= 1f) pb -= 1f
            val ga = 0.5f - 0.5f * cos(twoPi * pa)
            x[i] = tap(pa * winF) * ga + tap(pb * winF) * (1f - ga)
            phase += inc
            if (phase >= 1f) phase -= 1f else if (phase < 0f) phase += 1f
            w = (w + 1) and mask
        }
    }
}

/**
 * The whole live effect: pitch shifter plus the effect's own colouring (ring modulation, delay, reverb, ...).
 * Every buffer is created once in the constructor, so [process] never allocates. Use from ONE thread only.
 */
class EffectChain(private val sr: Int) {
    private val shifter = PitchShifter(sr)
    private var effect = -1
    private var userSemis = 0f
    private var shifterOn = false
    private val fadeLen = max(1, sr / 100)
    private var fadeLeft = 0

    private var ringPh = 0.0
    private var lfoPh = 0.0
    private var lp = 0f

    private val echoBuf = FloatArray(max(1, (sr * 0.33f).toInt()))
    private var echoPos = 0

    private val combBuf = FloatArray(max(2, (sr * 0.006f).toInt()))
    private var combPos = 0

    private val scale = sr / 44100f
    private val rvCombLens = intArrayOf(1116, 1188, 1277, 1356)
    private val rvComb = Array(4) { FloatArray(max(1, (rvCombLens[it] * scale).toInt())) }
    private val rvCombPos = IntArray(4)
    private val rvFilt = FloatArray(4)
    private val rvApLens = intArrayOf(556, 441)
    private val rvAp = Array(2) { FloatArray(max(1, (rvApLens[it] * scale).toInt())) }
    private val rvApPos = IntArray(2)

    /** Chooses the effect and the user's extra pitch (semitones). Changing the effect clears all old audio state. */
    fun configure(e: Int, semis: Float) {
        userSemis = semis
        if (e != effect) {
            effect = e
            resetState()
        }
    }

    private fun resetState() {
        shifter.reset()
        shifterOn = false
        java.util.Arrays.fill(echoBuf, 0f)
        java.util.Arrays.fill(combBuf, 0f)
        for (b in rvComb) java.util.Arrays.fill(b, 0f)
        for (b in rvAp) java.util.Arrays.fill(b, 0f)
        java.util.Arrays.fill(rvCombPos, 0)
        java.util.Arrays.fill(rvApPos, 0)
        java.util.Arrays.fill(rvFilt, 0f)
        echoPos = 0
        combPos = 0
        lp = 0f
        fadeLeft = fadeLen
    }

    fun process(b: FloatArray, n: Int) {
        val e = effect
        if (e < 0 || e >= VoiceEffects.ALL.size) return
        var semis = VoiceEffects.ALL[e].presetSemis + userSemis
        if (e == VoiceEffects.ALIEN) {
            semis += 1.5f * sin(lfoPh).toFloat()
            lfoPh += 2.0 * PI * 5.5 * n / sr
            if (lfoPh > 2.0 * PI) lfoPh -= 2.0 * PI
        }
        semis = semis.coerceIn(-24f, 24f)
        val on = abs(semis) > 0.05f
        if (on != shifterOn) {
            shifterOn = on
            shifter.reset()
            fadeLeft = fadeLen
        }
        if (on) shifter.process(b, n, 2f.pow(semis / 12f))

        when (e) {
            VoiceEffects.ROBOT -> robot(b, n)
            VoiceEffects.DEEP -> lowpass(b, n, 0.45f)
            VoiceEffects.MONSTER -> monster(b, n)
            VoiceEffects.ALIEN -> ring(b, n, 180.0, 0.45f)
            VoiceEffects.ECHO -> echo(b, n)
            VoiceEffects.REVERB -> reverb(b, n)
        }

        if (fadeLeft > 0) {
            for (i in 0 until n) {
                if (fadeLeft <= 0) break
                b[i] *= 1f - fadeLeft.toFloat() / fadeLen
                fadeLeft--
            }
        }
    }

    private fun ring(b: FloatArray, n: Int, freq: Double, mix: Float) {
        val inc = 2.0 * PI * freq / sr
        for (i in 0 until n) {
            ringPh += inc
            if (ringPh > 2.0 * PI) ringPh -= 2.0 * PI
            val m = sin(ringPh).toFloat()
            b[i] = b[i] * (1f - mix) + b[i] * m * mix
        }
    }

    private fun lowpass(b: FloatArray, n: Int, a: Float) {
        var s = lp
        for (i in 0 until n) {
            s += a * (b[i] - s)
            b[i] = s
        }
        lp = s
    }

    private fun robot(b: FloatArray, n: Int) {
        ring(b, n, 50.0, 1f)
        var p = combPos
        val len = combBuf.size
        for (i in 0 until n) {
            val v = b[i] + 0.55f * combBuf[p]
            combBuf[p] = v
            p++
            if (p >= len) p = 0
            b[i] = v * 0.7f
        }
        combPos = p
    }

    private fun monster(b: FloatArray, n: Int) {
        ring(b, n, 24.0, 0.35f)
        lowpass(b, n, 0.5f)
        for (i in 0 until n) b[i] = tanh(b[i] * 3f) * 0.6f
    }

    private fun echo(b: FloatArray, n: Int) {
        var p = echoPos
        val len = echoBuf.size
        for (i in 0 until n) {
            val x = b[i]
            val d = echoBuf[p]
            echoBuf[p] = x + d * 0.45f
            b[i] = x + d * 0.6f
            p++
            if (p >= len) p = 0
        }
        echoPos = p
    }

    private fun reverb(b: FloatArray, n: Int) {
        for (i in 0 until n) {
            val x = b[i]
            val inp = x * 0.1f
            var sum = 0f
            for (k in 0 until 4) {
                val cb = rvComb[k]
                val p = rvCombPos[k]
                val o = cb[p]
                rvFilt[k] = o * 0.7f + rvFilt[k] * 0.3f
                cb[p] = inp + rvFilt[k] * 0.82f
                rvCombPos[k] = if (p + 1 >= cb.size) 0 else p + 1
                sum += o
            }
            var y = sum
            for (k in 0 until 2) {
                val ab = rvAp[k]
                val p = rvApPos[k]
                val bo = ab[p]
                val out = -y + bo
                ab[p] = y + bo * 0.5f
                rvApPos[k] = if (p + 1 >= ab.size) 0 else p + 1
                y = out
            }
            b[i] = x * 0.75f + y
        }
    }
}

object Dsp {
    /**
     * Applies the volume, soft-limits so the sound never hard-clips, and converts [n] samples to 16-bit.
     * Returns the peak level (0..1, before limiting).
     */
    fun finish(b: FloatArray, n: Int, vol: Float, out: ShortArray, outOff: Int = 0): Float {
        var peak = 0f
        for (i in 0 until n) {
            var v = b[i] * vol
            if (v != v) v = 0f
            val a = abs(v)
            if (a > peak) peak = a
            if (a > 0.85f) {
                val s = 0.85f + 0.15f * tanh((a - 0.85f) / 0.15f)
                v = if (v < 0f) -s else s
            }
            out[outOff + i] = (v * 32767f).toInt().toShort()
        }
        return min(peak, 1f)
    }
}

/**
 * Pitch-preserving time stretch (WSOLA: waveform-similarity overlap-add) for the Slow / Fast clip effects.
 * [speed] 0.5 = twice as long, 2.0 = half as long. Works on a finished clip, so it is allowed to allocate.
 */
object TimeStretch {
    private const val N = 1024
    private const val H = 512
    private const val D = 256

    fun stretch(x: FloatArray, n: Int, speed: Float): FloatArray {
        val sp = speed.coerceIn(0.25f, 4f)
        val outLen = max(1, (n / sp).toInt())
        val inp = FloatArray(n + 2 * N + 2 * D)
        System.arraycopy(x, 0, inp, D, n)
        val win = FloatArray(N) { (0.5 - 0.5 * cos(2.0 * PI * it / N)).toFloat() }
        val out = FloatArray(outLen + N)

        for (k in 0 until N) out[k] += inp[D + k] * win[k]
        var prev = D
        var m = 1
        while (true) {
            val outPos = m * H
            if (outPos >= outLen) break
            val ideal = D + (m * H * sp).toInt()
            if (ideal - D >= n) break
            val ref = prev + H
            var best = ideal
            var bestScore = -Float.MAX_VALUE
            var c = ideal - D
            while (c <= ideal + D) {
                var corr = 0f
                var en = 1e-6f
                var k = 0
                while (k < H) {
                    val bb = inp[c + k]
                    corr += inp[ref + k] * bb
                    en += bb * bb
                    k += 4
                }
                val score = corr / sqrt(en)
                if (score > bestScore) {
                    bestScore = score
                    best = c
                }
                c += 2
            }
            for (k in 0 until N) out[outPos + k] += inp[best + k] * win[k]
            prev = best
            m++
        }
        return out.copyOf(outLen)
    }
}
