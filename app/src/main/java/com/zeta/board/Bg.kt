package com.zeta.board

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

object KbBg {
    fun file(c: Context): File = File(c.filesDir, "kbbg.jpg")

    fun has(c: Context): Boolean = file(c).exists()

    /** Changes whenever the background picture changes (the keyboard uses the same value to notice it). */
    fun stamp(c: Context): Long {
        val f = file(c)
        return if (f.exists()) f.lastModified() + f.length() else 0L
    }

    fun clear(c: Context) {
        file(c).delete()
    }

    fun load(c: Context, sample: Int): Bitmap? {
        val f = file(c)
        if (!f.exists()) return null
        val o = BitmapFactory.Options()
        o.inSampleSize = sample
        return BitmapFactory.decodeFile(f.path, o)
    }

    /** Copies a gallery image into app storage (max 1280px, EXIF rotation applied). Run off the main thread. */
    fun save(c: Context, uri: Uri): Boolean {
        try {
            val cr = c.contentResolver
            val bo = BitmapFactory.Options()
            bo.inJustDecodeBounds = true
            cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bo) }
            if (bo.outWidth <= 0 || bo.outHeight <= 0) return false
            var ss = 1
            while (max(bo.outWidth, bo.outHeight) / ss > 2000) ss *= 2
            val o = BitmapFactory.Options()
            o.inSampleSize = ss
            var bmp: Bitmap = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) } ?: return false

            var ori = 1
            try {
                ori = cr.openInputStream(uri)?.use { s ->
                    ExifInterface(s).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)
                } ?: 1
            } catch (e: Exception) {
                ori = 1
            }
            val deg = when (ori) {
                6 -> 90
                3 -> 180
                8 -> 270
                else -> 0
            }
            val longest = max(bmp.width, bmp.height)
            val scale = if (longest > 1280) 1280f / longest else 1f
            if (deg != 0 || scale != 1f) {
                val m = Matrix()
                m.postScale(scale, scale)
                m.postRotate(deg.toFloat())
                bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            }
            // write to a temp file first, so the keyboard never sees a half-written picture
            val tmp = File(c.filesDir, "kbbg.tmp")
            val written = FileOutputStream(tmp).use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            if (!written || !tmp.renameTo(file(c))) {
                tmp.delete()
                return false
            }
            KbVideo.clear(c)
            return true
        } catch (e: Exception) {
            return false
        }
    }
}
