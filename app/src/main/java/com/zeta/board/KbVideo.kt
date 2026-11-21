package com.zeta.board

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.view.Surface
import android.view.TextureView
import java.io.File
import java.io.FileOutputStream

/** The video the user picked as keyboard background. One video at a time; it replaces the photo background. */
object KbVideo {
    /** Biggest video accepted. Short loops (10 to 30 seconds, 720p) are best for battery and smoothness. */
    const val MAX_BYTES = 60L * 1024 * 1024

    fun file(c: Context): File = File(c.filesDir, "kbvideo.mp4")

    fun has(c: Context): Boolean = file(c).exists()

    /** Changes whenever the video changes (the player uses it to notice a new video). */
    fun stamp(c: Context): Long {
        val f = file(c)
        return if (f.exists()) f.lastModified() + f.length() else 0L
    }

    fun clear(c: Context) {
        file(c).delete()
    }

    /** A still picture from the first second of the video, for the preview in the app. Null if it cannot be read. */
    fun thumb(c: Context): Bitmap? {
        if (!has(c)) return null
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(file(c).path)
            r.getFrameAtTime(500_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } catch (e: Exception) {
            null
        } finally {
            try {
                r.release()
            } catch (e: Exception) {
            }
        }
    }

    /**
     * Copies a gallery video into app storage. Run off the main thread.
     * Returns null when it worked, or a short message for the user when it did not.
     * A video background replaces the photo background.
     */
    fun save(c: Context, uri: Uri): String? {
        val tmp = File(c.filesDir, "kbvideo.tmp")
        try {
            var total = 0L
            val input = c.contentResolver.openInputStream(uri) ?: return "Could not open that video"
            input.use { i ->
                FileOutputStream(tmp).use { o ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = i.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_BYTES) {
                            tmp.delete()
                            return "Video is too big (max 60 MB). Pick a shorter one"
                        }
                        o.write(buf, 0, n)
                    }
                }
            }
            // make sure it really is a playable video before replacing anything
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(tmp.path)
                if (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) != "yes") {
                    tmp.delete()
                    return "That file has no video"
                }
            } finally {
                try {
                    r.release()
                } catch (e: Exception) {
                }
            }
            if (!tmp.renameTo(file(c))) {
                tmp.delete()
                return "Could not save the video"
            }
            KbBg.clear(c)
            return null
        } catch (e: Exception) {
            tmp.delete()
            return "Could not read that video"
        }
    }
}

/**
 * Plays the background video, muted and looping, behind the keys. It only plays while the keyboard is on screen,
 * so it does not use battery when the keyboard is closed. The video fills the keyboard (centre crop).
 */
class VideoBgView(ctx: Context) : TextureView(ctx), TextureView.SurfaceTextureListener {

    private var mp: MediaPlayer? = null
    private var surface: Surface? = null
    private var prepared = false
    private var vw = 0
    private var vh = 0
    private var loadedStamp = -1L
    private val m = Matrix()

    init {
        surfaceTextureListener = this
        isOpaque = false
        visibility = GONE
    }

    /** Called every time the keyboard opens: picks up a new, changed or removed video. */
    fun sync() {
        val c = context
        if (!KbVideo.has(c)) {
            release()
            loadedStamp = -1L
            visibility = GONE
            return
        }
        val st = KbVideo.stamp(c)
        if (st != loadedStamp) {
            release()
            loadedStamp = st
        }
        visibility = VISIBLE
        play()
    }

    fun pause() {
        try {
            mp?.takeIf { prepared && it.isPlaying }?.pause()
        } catch (e: Exception) {
        }
    }

    private fun play() {
        if (!KbVideo.has(context)) return
        if (surfaceTexture == null) return // onSurfaceTextureAvailable will call this again
        val p = mp
        if (p == null) {
            create()
        } else if (prepared) {
            try {
                if (!p.isPlaying) p.start()
            } catch (e: Exception) {
                release()
            }
        }
    }

    private fun create() {
        val st = surfaceTexture ?: return
        try {
            val p = MediaPlayer()
            val s = Surface(st)
            surface = s
            p.setDataSource(KbVideo.file(context).path)
            p.setSurface(s)
            p.isLooping = true
            p.setVolume(0f, 0f)
            p.setOnVideoSizeChangedListener { _, w, h ->
                vw = w
                vh = h
                fit()
            }
            p.setOnPreparedListener {
                prepared = true
                if (windowVisibility == VISIBLE) {
                    try {
                        it.start()
                    } catch (e: Exception) {
                        release()
                    }
                }
            }
            p.setOnErrorListener { _, _, _ ->
                release()
                true
            }
            mp = p
            p.prepareAsync()
        } catch (e: Exception) {
            release()
        }
    }

    private fun release() {
        prepared = false
        try {
            mp?.release()
        } catch (e: Exception) {
        }
        mp = null
        try {
            surface?.release()
        } catch (e: Exception) {
        }
        surface = null
    }

    /** TextureView stretches the picture to its size; this scales it back to its own shape and crops the overflow. */
    private fun fit() {
        if (vw <= 0 || vh <= 0 || width <= 0 || height <= 0) return
        val vr = vw.toFloat() / vh
        val r = width.toFloat() / height
        m.reset()
        if (vr > r) m.setScale(vr / r, 1f, width / 2f, height / 2f) else m.setScale(1f, r / vr, width / 2f, height / 2f)
        setTransform(m)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        fit()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) play() else pause()
    }

    override fun onDetachedFromWindow() {
        release()
        super.onDetachedFromWindow()
    }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
        play()
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
        fit()
    }

    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        release()
        return true
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
}
