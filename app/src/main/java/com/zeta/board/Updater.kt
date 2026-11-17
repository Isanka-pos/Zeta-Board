package com.zeta.board

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

object Updater {

    class Info(val code: Long, val name: String, val notes: String, val apkUrl: String)

    class Task {
        @Volatile
        var cancelled = false
    }

    // APK waiting for the "install unknown apps" permission
    var pending: File? = null

    private val main = Handler(Looper.getMainLooper())

    fun currentCode(c: Context): Long = c.packageManager.getPackageInfo(c.packageName, 0).longVersionCode

    fun currentName(c: Context): String = c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: "1.0"

    /** onResult(info, failed): info != null when a newer version exists. */
    fun check(c: Context, onResult: (Info?, Boolean) -> Unit) {
        val app = c.applicationContext
        Thread {
            var info: Info? = null
            var failed = false
            try {
                if (BuildConfig.UPDATE_URL.isEmpty()) throw IllegalStateException("no update url")
                val conn = URL(BuildConfig.UPDATE_URL).openConnection() as HttpURLConnection
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                conn.useCaches = false
                conn.setRequestProperty("Cache-Control", "no-cache")
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                val j = JSONObject(text)
                val code = j.getLong("versionCode")
                if (code > currentCode(app)) {
                    info = Info(code, j.optString("versionName", code.toString()), j.optString("notes", ""), j.getString("apkUrl"))
                }
            } catch (e: Exception) {
                failed = true
            }
            main.post { onResult(info, failed) }
        }.start()
    }

    /** onProgress(percent, downloadedBytes, totalBytes); onDone(file or null on failure/cancel). Both run on the main thread. */
    fun download(
        c: Context,
        url: String,
        onProgress: (Int, Long, Long) -> Unit,
        onDone: (File?) -> Unit
    ): Task {
        val task = Task()
        val dir = File(c.cacheDir, "updates")
        Thread {
            val out = File(dir, "ZetaBoard.apk")
            try {
                dir.mkdirs()
                out.delete()
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 15000
                conn.instanceFollowRedirects = true
                val total = conn.contentLengthLong
                var got = 0L
                var last = 0L
                conn.inputStream.use { input ->
                    FileOutputStream(out).use { fos ->
                        val buf = ByteArray(32 * 1024)
                        while (true) {
                            if (task.cancelled) throw InterruptedException()
                            val n = input.read(buf)
                            if (n < 0) break
                            fos.write(buf, 0, n)
                            got += n
                            val now = System.currentTimeMillis()
                            if (now - last > 120) {
                                last = now
                                val pct = if (total > 0) (got * 100 / total).toInt() else 0
                                val g = got
                                main.post { onProgress(pct, g, total) }
                            }
                        }
                    }
                }
                conn.disconnect()
                val g = got
                main.post {
                    onProgress(100, g, total)
                    onDone(out)
                }
            } catch (e: Exception) {
                out.delete()
                main.post { onDone(null) }
            }
        }.start()
        return task
    }

    fun install(a: Activity, f: File) {
        if (!a.packageManager.canRequestPackageInstalls()) {
            pending = f
            a.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.packageName)))
            return
        }
        val uri = FileProvider.getUriForFile(a, a.packageName + ".fileprovider", f)
        val i = Intent(Intent.ACTION_VIEW)
        i.setDataAndType(uri, "application/vnd.android.package-archive")
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        a.startActivity(i)
    }
}
