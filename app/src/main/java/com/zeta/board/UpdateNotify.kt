package com.zeta.board

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Handler
import android.os.Looper

object UpdateScheduler {
    private const val ID = 4108
    private const val OLD_ID = 4107 // the old 6-hour job, removed on upgrade
    const val INTERVAL_MIN = 45L
    private const val INTERVAL_MS = INTERVAL_MIN * 60L * 1000L

    /** Checks for a new version every 45 minutes (needs network, survives reboot). */
    fun schedule(ctx: Context) {
        try {
            val js = ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            js.cancel(OLD_ID)
            val existing = js.getPendingJob(ID)
            if (existing != null && existing.isPeriodic && existing.intervalMillis == INTERVAL_MS) return
            val info = JobInfo.Builder(ID, ComponentName(ctx, UpdateJobService::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(INTERVAL_MS)
                .setPersisted(true)
                .build()
            js.schedule(info)
        } catch (e: Exception) {
        }
    }
}

/** A second 45-minute timer that runs while the keyboard (or the app) process is alive, so checks are not missed. */
object UpdateWatcher {
    const val INTERVAL_MS = 45L * 60L * 1000L
    private const val MIN_GAP_MS = 40L * 60L * 1000L

    private val handler = Handler(Looper.getMainLooper())
    private var appCtx: Context? = null
    private var started = false

    private val tick = object : Runnable {
        override fun run() {
            val c = appCtx ?: return
            checkAndNotify(c, false) {}
            handler.postDelayed(this, INTERVAL_MS)
        }
    }

    fun start(ctx: Context) {
        appCtx = ctx.applicationContext
        if (started) return
        started = true
        handler.postDelayed(tick, INTERVAL_MS)
    }

    /** Looks for a new version and shows a notification once per version. [done] is always called on the main thread. */
    fun checkAndNotify(ctx: Context, force: Boolean, done: () -> Unit) {
        val app = ctx.applicationContext
        val now = System.currentTimeMillis()
        val gap = now - Prefs.getL(app, "last_bg_check", 0L)
        if (!force && gap >= 0L && gap < MIN_GAP_MS) {
            done()
            return
        }
        Updater.check(app) { info, err ->
            if (err == null) Prefs.setL(app, "last_bg_check", System.currentTimeMillis())
            if (info != null && !Updater.dialogOpen && info.code > Prefs.getI(app, "notified_code", 0).toLong()) {
                Prefs.setI(app, "notified_code", info.code.toInt())
                UpdateNotifier.show(app, info)
            }
            done()
        }
    }
}

class UpdateJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        UpdateWatcher.checkAndNotify(this, true) { jobFinished(params, false) }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true
}

object UpdateNotifier {
    private const val CHANNEL = "updates"

    fun show(ctx: Context, info: Updater.Info) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!nm.areNotificationsEnabled()) return
        if (nm.getNotificationChannel(CHANNEL) == null) {
            val ch = NotificationChannel(CHANNEL, "App updates", NotificationManager.IMPORTANCE_HIGH)
            ch.description = "Tells you when a new Zeta Board version is ready"
            ch.enableLights(true)
            ch.lightColor = Pal.PURPLE
            nm.createNotificationChannel(ch)
        }
        val open = Intent(ctx, MainActivity::class.java)
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        open.putExtra("from_update", true)
        val pi = PendingIntent.getActivity(ctx, 1, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notes = info.notes.ifBlank { "Improvements and bug fixes" }
        val action = Notification.Action.Builder(Icon.createWithResource(ctx, R.drawable.ic_notif), "Update now", pi).build()
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setLargeIcon(Logo.square(ctx, 192))
            .setColor(Pal.PURPLE)
            .setContentTitle("Zeta Board v" + info.name + " is ready")
            .setContentText("A new update is available. Tap to install.")
            .setSubText("Update available")
            .setStyle(
                Notification.BigTextStyle()
                    .setBigContentTitle("Zeta Board v" + info.name)
                    .bigText("What's new:\n" + notes)
            )
            .addAction(action)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify(7001, n)
    }
}
