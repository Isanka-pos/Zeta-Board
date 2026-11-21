package com.zeta.board

import android.app.Activity
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.RequiresApi

/**
 * Screenshot privacy. The mode is fixed and cannot be changed by the user:
 *
 *  - Android 14+: screenshots are detected. The moment one is taken, a warning banner slides in from the top
 *    of the screen and a heads-up notification is shown.
 *  - Android 13 and older: Android has no screenshot detection, so every screen and dialog uses FLAG_SECURE
 *    instead and screenshots / screen recordings are blocked.
 *
 * (Android never reports a screenshot of a FLAG_SECURE window, so blocking and warning cannot run together.)
 */
object ScreenGuard {
    private const val CHANNEL = "privacy"
    private const val NOTIF_ID = 7003
    private const val TAG = "zeta_shot_banner"
    private const val BANNER_MS = 3800L
    private const val DEBOUNCE_MS = 1500L
    private val AMBER = Color.parseColor("#F59E0B")

    private val main = Handler(Looper.getMainLooper())
    private val callbacks = HashMap<Activity, Any>()
    private val live = ArrayList<Activity>() // started (visible) activities
    private var lastWarn = 0L

    /** Screenshot detection exists only from Android 14. */
    fun canWarn(): Boolean = Build.VERSION.SDK_INT >= 34

    /** True when windows are blocked with FLAG_SECURE: only on Android 13 and older, where warning is impossible. */
    fun blocking(@Suppress("UNUSED_PARAMETER") ctx: Context): Boolean = !canWarn()

    /** Call once from Application.onCreate(). Covers every Activity of the app automatically. */
    fun install(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(a: Activity, b: Bundle?) {
                if (a is StickerActivity) return // invisible helper screen
                applyMode(a)
            }

            override fun onActivityStarted(a: Activity) {
                if (a is StickerActivity) return
                if (!live.contains(a)) live.add(a)
                applyMode(a)
            }

            override fun onActivityStopped(a: Activity) {
                live.remove(a)
                if (Build.VERSION.SDK_INT >= 34) unlisten(a)
            }

            override fun onActivityDestroyed(a: Activity) {
                live.remove(a)
                callbacks.remove(a)
            }

            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
        })
    }

    /** Android 14+: listen for screenshots. Older Android: FLAG_SECURE. */
    private fun applyMode(a: Activity) {
        val block = blocking(a)
        setSecure(a.window, block)
        if (Build.VERSION.SDK_INT >= 34) {
            if (block) unlisten(a) else if (live.contains(a)) listen(a)
        }
    }

    /** Blocks screenshots for this window (dialogs, keyboard panels) on Android 13 and older. */
    fun secure(w: Window?) {
        val c = w?.context ?: return
        setSecure(w, blocking(c))
    }

    fun setSecure(w: Window?, on: Boolean) {
        try {
            if (on) w?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            else w?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } catch (e: Exception) {
        }
    }

    // ---- screenshot attempt detection (Android 14+) ----

    @RequiresApi(34)
    private fun listen(a: Activity) {
        if (callbacks.containsKey(a)) return
        try {
            val cb = Activity.ScreenCaptureCallback { warn(a) }
            a.registerScreenCaptureCallback(a.mainExecutor, cb)
            callbacks[a] = cb
        } catch (e: Exception) {
        }
    }

    @RequiresApi(34)
    private fun unlisten(a: Activity) {
        val cb = callbacks.remove(a) as? Activity.ScreenCaptureCallback ?: return
        try {
            a.unregisterScreenCaptureCallback(cb)
        } catch (e: Exception) {
        }
    }

    // ---- the warning ----

    /** Shows the top banner on [a] and a heads-up notification. */
    fun warn(a: Activity) {
        val now = System.currentTimeMillis()
        if (now - lastWarn < DEBOUNCE_MS) return
        lastWarn = now
        main.post {
            if (!a.isFinishing && !a.isDestroyed) showBanner(a)
            notifyHeadsUp(a.applicationContext)
        }
    }

    private fun showBanner(a: Activity) {
        val decor = a.window?.decorView as? ViewGroup ?: return
        decor.findViewWithTag<View>(TAG)?.let { decor.removeView(it) }
        val d = a.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val row = LinearLayout(a)
        row.tag = TAG
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(14), dp(12), dp(14), dp(12))
        row.elevation = dp(24).toFloat()
        row.background = GradientDrawable().apply {
            setColor(Color.parseColor("#2A1B05"))
            cornerRadius = dp(16).toFloat()
            setStroke(dp(2), AMBER)
        }

        val icon = ImageView(a)
        icon.setImageResource(R.drawable.ic_warning)
        icon.setColorFilter(AMBER)
        row.addView(icon, LinearLayout.LayoutParams(dp(26), dp(26)).apply { rightMargin = dp(12) })

        val col = LinearLayout(a)
        col.orientation = LinearLayout.VERTICAL
        val title = TextView(a)
        title.text = "Screenshot detected"
        title.setTextColor(Color.WHITE)
        title.textSize = 15f
        title.setTypeface(title.typeface, android.graphics.Typeface.BOLD)
        val sub = TextView(a)
        sub.text = "A screenshot of Zeta Board was just taken. Keep private content private."
        sub.setTextColor(Color.parseColor("#E9D5A6"))
        sub.textSize = 12f
        col.addView(title)
        col.addView(sub)
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val sbId = a.resources.getIdentifier("status_bar_height", "dimen", "android")
        val sb = if (sbId > 0) a.resources.getDimensionPixelSize(sbId) else dp(24)
        val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP)
        lp.setMargins(dp(12), sb + dp(8), dp(12), 0)
        decor.addView(row, lp)

        row.translationY = -dp(160).toFloat()
        row.alpha = 0f
        row.animate().translationY(0f).alpha(1f).setDuration(260).start()
        row.postDelayed({
            row.animate().translationY(-dp(160).toFloat()).alpha(0f).setDuration(240)
                .withEndAction { (row.parent as? ViewGroup)?.removeView(row) }.start()
        }, BANNER_MS)
    }

    private fun notifyHeadsUp(ctx: Context) {
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (!nm.areNotificationsEnabled()) return
            if (nm.getNotificationChannel(CHANNEL) == null) {
                val ch = NotificationChannel(CHANNEL, "Privacy alerts", NotificationManager.IMPORTANCE_HIGH)
                ch.description = "Warns you when a screenshot is taken inside Zeta Board"
                nm.createNotificationChannel(ch)
            }
            val n = Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_warning)
                .setColor(AMBER)
                .setCategory(Notification.CATEGORY_STATUS)
                .setContentTitle("\u26A0 Screenshot detected")
                .setContentText("A screenshot of Zeta Board was just taken.")
                .setTimeoutAfter(6000L)
                .setAutoCancel(true)
                .build()
            nm.notify(NOTIF_ID, n)
        } catch (e: Exception) {
        }
    }
}
