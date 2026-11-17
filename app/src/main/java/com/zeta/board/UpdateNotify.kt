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

object UpdateScheduler {
    private const val ID = 4107

    /** Checks for a new version every ~6 hours (needs network, survives reboot). */
    fun schedule(ctx: Context) {
        try {
            val js = ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            if (js.getPendingJob(ID) != null) return
            val info = JobInfo.Builder(ID, ComponentName(ctx, UpdateJobService::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(6L * 60L * 60L * 1000L)
                .setPersisted(true)
                .build()
            js.schedule(info)
        } catch (e: Exception) {
        }
    }
}

class UpdateJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Updater.check(this) { info, _ ->
            if (info != null && info.code > Prefs.getI(this, "notified_code", 0).toLong()) {
                Prefs.setI(this, "notified_code", info.code.toInt())
                UpdateNotifier.show(this, info)
            }
            jobFinished(params, false)
        }
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
