package com.zeta.board

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

/** One announcement sent from the admin panel on the zeta-board-server project. */
class AppMessage(val id: String, val title: String, val body: String, val type: String)

/**
 * Pulls announcements from the server's /api/messages and shows a notification for any the
 * user hasn't seen yet. Same server as Updater.kt - same SERVER_BASE, just a different endpoint.
 * Piggybacks on UpdateWatcher's 15-minute cycle, so there's no extra battery/network cost.
 */
object Messages {

    // Keep in sync with Updater.SERVER_BASE.
    private const val SERVER_BASE = "https://zeta-board.vercel.app"

    private val main = Handler(Looper.getMainLooper())

    private fun parse(text: String): List<AppMessage> {
        val arr = JSONArray(text)
        val out = ArrayList<AppMessage>()
        for (i in 0 until arr.length()) {
            val j = arr.getJSONObject(i)
            out.add(AppMessage(j.getString("_id"), j.getString("title"), j.getString("body"), j.optString("type", "info")))
        }
        return out
    }

    /** onResult runs on the main thread with the newest unseen message, or null if there isn't one. */
    fun checkUnseen(c: Context, onResult: (AppMessage?) -> Unit) {
        if (SERVER_BASE.isEmpty()) {
            onResult(null)
            return
        }
        val app = c.applicationContext
        Thread {
            var found: AppMessage? = null
            try {
                val conn = URL("$SERVER_BASE/api/messages").openConnection() as HttpURLConnection
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                conn.setRequestProperty("User-Agent", "ZetaBoard")
                if (conn.responseCode == 200) {
                    val text = conn.inputStream.bufferedReader().use { it.readText() }
                    val messages = parse(text)
                    val lastSeen = Prefs.getS(app, "last_seen_msg_id", "")
                    // messages are newest-first; the first one not equal to lastSeen is the newest unseen one
                    if (messages.isNotEmpty() && messages[0].id != lastSeen) found = messages[0]
                }
                conn.disconnect()
            } catch (e: Exception) {
            }
            main.post { onResult(found) }
        }.start()
    }

    fun markSeen(c: Context, id: String) = Prefs.setS(c.applicationContext, "last_seen_msg_id", id)
}

object MessageNotifier {
    private const val CHANNEL = "app_messages"
    const val ID = 7003

    private fun manager(ctx: Context): NotificationManager? {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!nm.areNotificationsEnabled()) return null
        if (nm.getNotificationChannel(CHANNEL) == null) {
            val ch = NotificationChannel(CHANNEL, "Announcements", NotificationManager.IMPORTANCE_DEFAULT)
            ch.description = "News and announcements from Zeta Board"
            ch.enableLights(true)
            ch.lightColor = Pal.PURPLE
            nm.createNotificationChannel(ch)
        }
        return nm
    }

    fun show(ctx: Context, msg: AppMessage) {
        try {
            val nm = manager(ctx) ?: return
            val open = Intent(ctx, MainActivity::class.java)
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val pi = PendingIntent.getActivity(ctx, 3, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val color = when (msg.type) {
                "warning" -> Pal.GREEN // swap for a warning color if Pal defines one
                else -> Pal.PURPLE
            }
            val n = Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_notif)
                .setLargeIcon(Logo.square(ctx, 192))
                .setColor(color)
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setContentTitle(msg.title)
                .setContentText(msg.body)
                .setStyle(Notification.BigTextStyle().bigText(msg.body))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            nm.notify(ID, n)
        } catch (e: Exception) {
        }
    }
}
