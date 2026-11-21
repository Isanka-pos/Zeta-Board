package com.zeta.board

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min

/** The app logo (res/drawable-nodpi/app_logo.jpg) drawn as a rounded square. */
object Logo {
    private var bmp: Bitmap? = null
    private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val src = Rect()
    private val dst = RectF()
    private val path = Path()

    fun bitmap(ctx: Context): Bitmap {
        val cached = bmp
        if (cached != null) return cached
        val bo = BitmapFactory.Options()
        bo.inJustDecodeBounds = true
        BitmapFactory.decodeResource(ctx.resources, R.drawable.app_logo, bo)
        var ss = 1
        while (max(bo.outWidth, bo.outHeight) / ss > 512) ss *= 2
        val o = BitmapFactory.Options()
        o.inSampleSize = ss
        val b = BitmapFactory.decodeResource(ctx.resources, R.drawable.app_logo, o)
            ?: Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        bmp = b
        return b
    }

    fun draw(c: Canvas, ctx: Context, l: Float, t: Float, r: Float, b: Float, radius: Float) {
        val bm = bitmap(ctx)
        val side = min(bm.width, bm.height)
        src.set((bm.width - side) / 2, (bm.height - side) / 2, (bm.width + side) / 2, (bm.height + side) / 2)
        dst.set(l, t, r, b)
        c.save()
        path.reset()
        path.addRoundRect(dst, radius, radius, Path.Direction.CW)
        c.clipPath(path)
        c.drawBitmap(bm, src, dst, p)
        c.restore()
    }

    /** Rounded square bitmap, used as the notification large icon. */
    fun square(ctx: Context, px: Int): Bitmap {
        val out = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        draw(Canvas(out), ctx, 0f, 0f, px.toFloat(), px.toFloat(), px * 0.22f)
        return out
    }
}
