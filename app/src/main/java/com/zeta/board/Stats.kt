package com.zeta.board

import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Anonymous usage statistics for the owner's dashboard (the zeta-stats project on Vercel + MongoDB).
 * Sends only: a random install id, app version, Android version, phone brand/model, system language and which theme, wallpaper
 * or tool was used. Never keystrokes, text, contacts or anything personal. Nothing is sent until the owner sets the
 * Remote Config key stats_url, and it can be stopped again by clearing that key. Every failure is swallowed: stats can never crash the keyboard.
 */
object Stats {
    private const val KEY_ID = "stats_id"
    private const val KEY_DAY = "stats_day"
    private const val KEY_OFF = "stats_off"

    private val pool = Executors.newSingleThreadExecutor { r -> Thread(r, "zeta-stats").apply { isDaemon = true } }

    private fun enabled(app: Context): Boolean = !Prefs.getB(app, KEY_OFF, false) && RemoteCfg.statsUrl().isNotEmpty()

    private fun installId(app: Context): String {
        val saved = Prefs.getS(app, KEY_ID, "")
        if (saved.length == 36) return saved
        val id = UUID.randomUUID().toString()
        Prefs.setS(app, KEY_ID, id)
        return id
    }

    /** Once per calendar day: counts this phone as active today. */
    fun open(ctx: Context) {
        try {
            val app = ctx.applicationContext
            if (!enabled(app)) return
            val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            if (Prefs.getS(app, KEY_DAY, "") == day) return
            send(app, "open", "") { ok -> if (ok) Prefs.setS(app, KEY_DAY, day) }
        } catch (e: Throwable) {
        }
    }

    /** name: theme_apply, wallpaper_apply, tool, language, update_install. prop: what was used (a theme name, a tool number). */
    fun event(ctx: Context, name: String, prop: String = "") {
        try {
            val app = ctx.applicationContext
            if (!enabled(app)) return
            send(app, name, prop) {}
        } catch (e: Throwable) {
        }
    }

    private fun send(app: Context, name: String, prop: String, done: (Boolean) -> Unit) {
        val base = RemoteCfg.statsUrl()
        val key = RemoteCfg.statsKey()
        val body = JSONObject().apply {
            put("id", installId(app))
            put("e", name)
            put("p", prop.take(40))
            put("v", Updater.currentCode(app))
            put("vn", BuildConfig.VERSION_NAME)
            put("sdk", Build.VERSION.SDK_INT)
            put("br", (Build.MANUFACTURER ?: "").lowercase(Locale.US).take(24))
            put("md", (Build.MODEL ?: "").take(40))
            put("lg", Locale.getDefault().language.take(8))
        }.toString()
        try {
            pool.execute {
                var ok = false
                var c: HttpURLConnection? = null
                try {
                    c = URL("$base/api/track").openConnection() as HttpURLConnection
                    c.requestMethod = "POST"
                    c.connectTimeout = 8000
                    c.readTimeout = 8000
                    c.doOutput = true
                    c.setRequestProperty("Content-Type", "application/json")
                    if (key.isNotEmpty()) c.setRequestProperty("x-app-key", key)
                    c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                    ok = c.responseCode in 200..299
                } catch (e: Throwable) {
                } finally {
                    try {
                        c?.disconnect()
                    } catch (e: Throwable) {
                    }
                }
                done(ok)
            }
        } catch (e: Throwable) {
            done(false)
        }
    }
}
