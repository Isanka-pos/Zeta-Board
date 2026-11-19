package com.zeta.board

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Instant update alerts through Firebase Cloud Messaging.
 * Every install subscribes to the "updates" topic; the GitHub Actions build sends one message to that topic
 * right after a release is published. The 15-minute check stays as the fallback (e.g. when push is blocked).
 */
object Push {
    const val TOPIC = "updates"

    /** Safe to call any time: does nothing when the build has no google-services.json. */
    fun subscribe(ctx: Context) {
        try {
            FirebaseMessaging.getInstance().subscribeToTopic(TOPIC)
        } catch (e: Exception) {
        }
    }

    /** data = the key/values sent by .github/scripts/send_push.py */
    fun handle(ctx: Context, data: Map<String, String>) {
        val app = ctx.applicationContext
        if (data["type"] == "announce") {
            Announcer.show(app, data["id"].orEmpty(), data["title"].orEmpty(), data["body"].orEmpty(), data["url"].orEmpty())
            return
        }
        if (data["type"] != "update") return
        val code = data["versionCode"]?.toLongOrNull() ?: return
        val apk = data["apkUrl"].orEmpty()
        if (apk.isEmpty()) return
        // already on this version (or newer): nothing to announce
        if (code <= Updater.currentCode(app)) return

        val info = Updater.Info(code, data["versionName"].orEmpty().ifBlank { "1.0.$code" }, data["notes"].orEmpty(), apk)
        UpdateStore.save(app, info)
        if (!Updater.dialogOpen && code > Prefs.getI(app, "notified_code", 0).toLong()) {
            Prefs.setI(app, "notified_code", code.toInt())
            UpdateNotifier.show(app, info)
        }
        // the push carries shortened notes; fetch the full release info so the "what's new" screen is complete
        UpdateWatcher.checkAndNotify(app, true) {}
    }
}

class ZetaMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        Push.subscribe(this)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        try {
            Push.handle(this, message.data)
        } catch (e: Exception) {
        }
    }
}

/** Custom messages from the owner ("new feature coming", news...). Separate, calmer channel than the update alerts. */
object Announcer {
    private const val CHANNEL = "announcements"

    fun show(ctx: Context, id: String, title: String, body: String, url: String) {
        try {
            if (title.isBlank() || body.isBlank()) return
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (!nm.areNotificationsEnabled()) return
            if (nm.getNotificationChannel(CHANNEL) == null) {
                val ch = NotificationChannel(CHANNEL, "News & announcements", NotificationManager.IMPORTANCE_DEFAULT)
                ch.description = "News about new Zeta Board features"
                nm.createNotificationChannel(ch)
            }
            val nid = 8000 + (id.hashCode() and 0xFFFF)
            // only web links are opened; anything else just opens the app
            val link = url.trim()
            val intent = if (link.startsWith("https://") || link.startsWith("http://")) {
                Intent(Intent.ACTION_VIEW, Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            } else {
                Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            val pi = PendingIntent.getActivity(ctx, nid, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val n = Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_notif)
                .setLargeIcon(Logo.square(ctx, 192))
                .setColor(Pal.PURPLE)
                .setCategory(Notification.CATEGORY_RECOMMENDATION)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(Notification.BigTextStyle().bigText(body))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            nm.notify(nid, n)
        } catch (e: Exception) {
        }
    }
}
