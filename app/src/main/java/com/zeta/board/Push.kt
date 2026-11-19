package com.zeta.board

import android.content.Context
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
