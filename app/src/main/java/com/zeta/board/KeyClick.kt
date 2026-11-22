package com.zeta.board

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The key click sound. It plays its own tiny click, so the "Key sound" switch works even when the phone's
 * "touch sounds" setting is off (the system click used before stayed silent then). It follows the phone's system volume
 * and stays quiet in silent / vibrate mode.
 */
object KeyClick {
    private var pool: SoundPool? = null
    private var soundId = 0
    @Volatile private var ready = false

    fun play(c: Context) {
        try {
            val am = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (am.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
            val p = pool ?: create(c.applicationContext)
            if (ready) p.play(soundId, 0.6f, 0.6f, 1, 0, 1f)
        } catch (e: Exception) {
        }
    }

    fun release() {
        try {
            pool?.release()
        } catch (e: Exception) {
        }
        pool = null
        ready = false
    }

    private fun create(c: Context): SoundPool {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val p = SoundPool.Builder().setMaxStreams(3).setAudioAttributes(attrs).build()
        p.setOnLoadCompleteListener { _, _, status -> ready = status == 0 }
        val f = File(c.cacheDir, "zeta_click.wav")
        if (!f.exists() || f.length() < 100L) f.writeBytes(wav())
        soundId = p.load(f.absolutePath, 1)
        pool = p
        return p
    }

    /** A 28 ms soft "tick": a short burst of noise and a 1.8 kHz tone that fades out fast. */
    private fun wav(): ByteArray {
        val rate = 22050
        val n = rate * 28 / 1000
        val data = ByteArray(n * 2)
        var seed = 12345
        for (i in 0 until n) {
            val t = i.toFloat() / n
            seed = seed * 1103515245 + 12345
            val noise = ((seed shr 16) and 0x7fff) / 16384f - 1f
            val tone = Math.sin(2.0 * Math.PI * 1800.0 * i / rate).toFloat()
            val env = (1f - t) * (1f - t) * (1f - t)
            val v = ((noise * 0.5f + tone * 0.5f) * env * 0.8f * 32767f).toInt()
            data[2 * i] = (v and 0xff).toByte()
            data[2 * i + 1] = ((v shr 8) and 0xff).toByte()
        }
        val b = ByteBuffer.allocate(44 + data.size).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + data.size).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        b.put("data".toByteArray()).putInt(data.size).put(data)
        return b.array()
    }
}
