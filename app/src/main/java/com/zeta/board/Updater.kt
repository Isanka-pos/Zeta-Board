package com.zeta.board

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

object Updater {

    // Your own server (see the zeta-board-server project). Set this once you've deployed it
    // to Vercel, e.g. "https://zeta-board-server.vercel.app". Leave blank to skip it and use
    // GitHub releases only.
    private const val SERVER_BASE = ""

    class Info(val code: Long, val name: String, val notes: String, val apkUrl: String, val mandatory: Boolean = false)

    class Task {
        @Volatile
        var cancelled = false
    }

    // APK waiting for the "install unknown apps" permission
    var pending: File? = null

    private val main = Handler(Looper.getMainLooper())

    // PackageInfoCompat works on Android 8+ (longVersionCode alone crashes on API 26-27)
    fun currentCode(c: Context): Long = PackageInfoCompat.getLongVersionCode(c.packageManager.getPackageInfo(c.packageName, 0))

    fun currentName(c: Context): String = c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: "1.0"

    var dialogOpen = false

    // the update dialog currently on screen (dismissed when the activity is destroyed)
    var dialog: Dialog? = null

    private fun get(url: String, accept: String?): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10000
        conn.readTimeout = 10000
        conn.useCaches = false
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("Cache-Control", "no-cache")
        conn.setRequestProperty("User-Agent", "ZetaBoard")
        if (accept != null) conn.setRequestProperty("Accept", accept)
        val code = conn.responseCode
        if (code != 200) {
            conn.disconnect()
            throw IllegalStateException("HTTP $code")
        }
        val text = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()
        return text
    }

    private fun fromJson(text: String): Info {
        val j = JSONObject(text)
        return Info(
            j.getLong("versionCode"), j.optString("versionName", ""), j.optString("notes", ""),
            j.getString("apkUrl"), j.optBoolean("mandatory", false)
        )
    }

    private fun fromApi(text: String): Info {
        val j = JSONObject(text)
        val tag = j.getString("tag_name")
        val code = tag.removePrefix("v").toLongOrNull() ?: throw IllegalStateException("bad tag $tag")
        val assets = j.getJSONArray("assets")
        var apk = ""
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.getString("name").endsWith(".apk")) apk = a.getString("browser_download_url")
        }
        if (apk.isEmpty()) throw IllegalStateException("no apk in release")
        return Info(code, "1.0.$code", j.optString("body", ""), apk)
    }

    /** Reads the newest published release (own server first, then update.json, then the GitHub API). Throws when none works. */
    private fun fetchLatest(): Info {
        if (SERVER_BASE.isNotEmpty()) {
            try {
                return fromJson(get("$SERVER_BASE/api/update", "application/json"))
            } catch (e: Exception) {
                // server down/unreachable - fall through to GitHub below
            }
        }
        val repo = BuildConfig.REPO
        if (repo.isEmpty()) throw IllegalStateException("this build has no repo set - build it with GitHub Actions")
        var latest: Info? = null
        var e1 = ""
        try {
            latest = fromJson(get("https://github.com/$repo/releases/latest/download/update.json", null))
        } catch (e: Exception) {
            e1 = "update.json: " + (e.message ?: e.javaClass.simpleName)
        }
        if (latest == null) {
            try {
                latest = fromApi(get("https://api.github.com/repos/$repo/releases/latest", "application/vnd.github+json"))
            } catch (e: Exception) {
                throw IllegalStateException(e1 + " / api: " + (e.message ?: e.javaClass.simpleName))
            }
        }
        return latest!!
    }

    /** onResult(info, error): info != null when a newer version exists; error != null when the check failed. */
    fun check(c: Context, onResult: (Info?, String?) -> Unit) {
        val app = c.applicationContext
        Thread {
            var info: Info? = null
            var err: String? = null
            try {
                val l = fetchLatest()
                if (l.code > currentCode(app)) info = Info(l.code, l.name.ifBlank { "1.0." + l.code }, l.notes, l.apkUrl, l.mandatory)
            } catch (e: Exception) {
                err = e.message ?: e.javaClass.simpleName
            }
            main.post { onResult(info, err) }
        }.start()
    }

    /** The newest published release, newer than the installed one or not (null if it cannot be read). Used for the "what's new" notes. */
    fun latest(c: Context, onResult: (Info?) -> Unit) {
        Thread {
            var info: Info? = null
            try {
                info = fetchLatest()
            } catch (e: Exception) {
            }
            main.post { onResult(info) }
        }.start()
    }

    /** The downloaded file must really be a Zeta Board APK, otherwise the installer would just fail. */
    private fun isValidApk(c: Context, f: File): Boolean {
        if (!f.exists() || f.length() < 100000L) return false
        val pi = c.packageManager.getPackageArchiveInfo(f.path, 0) ?: return false
        return pi.packageName == c.packageName
    }

    /** onProgress(percent, downloadedBytes, totalBytes); onDone(file or null on failure/cancel). Both run on the main thread. */
    fun download(
        c: Context,
        url: String,
        onProgress: (Int, Long, Long) -> Unit,
        onDone: (File?) -> Unit
    ): Task {
        val task = Task()
        val app = c.applicationContext
        val dir = File(app.cacheDir, "updates")
        Thread {
            val out = File(dir, "ZetaBoard.apk")
            try {
                dir.mkdirs()
                out.delete()
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 15000
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", "ZetaBoard")
                val http = conn.responseCode
                if (http != 200) {
                    conn.disconnect()
                    throw IllegalStateException("HTTP $http")
                }
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
                if (total > 0 && got != total) throw IllegalStateException("incomplete download")
                if (!isValidApk(app, out)) throw IllegalStateException("invalid apk")
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
