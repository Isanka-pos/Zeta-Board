package com.zeta.board

import android.content.Context
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.FirebaseRemoteConfigSettings

/**
 * Firebase Remote Config: change things from the Firebase console without releasing a new APK.
 * Every value has a safe default in the code, so the app works the same with no internet or without google-services.json.
 * Keys are listed in REMOTE_CONFIG.md.
 */
object RemoteCfg {
    private const val FETCH_SECONDS = 900L // same rhythm as the update check; the SDK serves its cache in between

    const val MIN_VERSION = "min_version_code"
    const val OFF_MESSAGE = "tool_off_message"
    const val EMOJIMIX_URL = "emojimix_url"
    const val WALLPAPER_URL = "wallpaper_url"
    const val BANNER_LINES = "banner_lines"
    const val PERF_ON = "perf_on"

    /** tool id -> remote key (true = tool works). Settings, Theme, Text style and Emoji can never be switched off. */
    private val toolKeys = mapOf(
        Tools.STICKER to "tool_sticker_on",
        Tools.CLIPBOARD to "tool_clipboard_on",
        Tools.QUICK to "tool_quicktext_on",
        Tools.TRANSLATE to "tool_translate_on",
        Tools.VOICE to "tool_voice_on",
        Tools.STATUS to "tool_status_on",
        Tools.SINGLISH to "tool_singlish_on",
        Tools.MIX to "tool_emojimix_on"
    )

    private val defaults: Map<String, Any> = HashMap<String, Any>().apply {
        put(MIN_VERSION, 0L)
        put(OFF_MESSAGE, "This tool is paused for now. Please try again later.")
        put(EMOJIMIX_URL, "")
        put(WALLPAPER_URL, "")
        put(BANNER_LINES, "")
        put(PERF_ON, true)
        for (k in toolKeys.values) put(k, true)
    }

    private fun rc(): FirebaseRemoteConfig? = try {
        FirebaseRemoteConfig.getInstance()
    } catch (e: Throwable) {
        null
    }

    /** Call from Application.onCreate: sets the defaults, applies the last downloaded values and asks for fresh ones. */
    fun init(ctx: Context) {
        val r = rc() ?: return
        try {
            r.setConfigSettingsAsync(FirebaseRemoteConfigSettings.Builder().setMinimumFetchIntervalInSeconds(FETCH_SECONDS).build())
            r.setDefaultsAsync(defaults)
            applyBanner()
        } catch (e: Throwable) {
        }
        refresh {}
    }

    /** Downloads new values (a no-op within 15 minutes of the last download) and activates them. [done] runs on the main thread. */
    fun refresh(done: () -> Unit) {
        val r = rc()
        if (r == null) {
            done()
            return
        }
        try {
            r.fetchAndActivate().addOnCompleteListener {
                try {
                    applyBanner()
                    Perf.setEnabled(bool(PERF_ON))
                } catch (e: Throwable) {
                }
                done()
            }
        } catch (e: Throwable) {
            done()
        }
    }

    // ---------- readers (always safe, fall back to the defaults) ----------

    private fun str(key: String): String = try {
        rc()?.getString(key) ?: (defaults[key] as? String ?: "")
    } catch (e: Throwable) {
        defaults[key] as? String ?: ""
    }

    private fun bool(key: String): Boolean = try {
        rc()?.getBoolean(key) ?: true
    } catch (e: Throwable) {
        true
    }

    /** False when the owner switched this tool off in the console. */
    fun toolOn(id: Int): Boolean {
        val k = toolKeys[id] ?: return true
        return bool(k)
    }

    fun offMessage(): String = str(OFF_MESSAGE).trim().ifEmpty { defaults[OFF_MESSAGE] as String }.take(80)

    /** True when this installed version is older than the minimum the owner set. */
    fun mustUpdate(ctx: Context): Boolean = try {
        val min = rc()?.getLong(MIN_VERSION) ?: 0L
        min > 0L && Updater.currentCode(ctx) < min
    } catch (e: Throwable) {
        false
    }

    private fun httpsOrNull(raw: String): String? {
        val u = raw.trim().trimEnd('/')
        if (!u.startsWith("https://") || u.length < 12) return null
        if (u.any { it.isWhitespace() || it == '"' || it == '\'' || it == '<' || it == '>' }) return null
        return u
    }

    /** Emoji Mix server address: the console value when it is a valid https address, otherwise the one built into the app. */
    fun emojimixUrl(): String = httpsOrNull(str(EMOJIMIX_URL)) ?: BuildConfig.EMOJIMIX_URL.trimEnd('/')

    /** Wallpaper server address from the console, or "" to keep the built-in behaviour. */
    fun wallpaperUrl(): String = httpsOrNull(str(WALLPAPER_URL)) ?: BuildConfig.WALLPAPER_URL

    /** The lines the Hacker-theme typing banner shows. Comma separated in the console; each is cut to 11 safe characters. */
    private fun applyBanner() {
        val out = ArrayList<String>()
        for (part in str(BANNER_LINES).split(",")) {
            val l = part.trim().filter { it.isLetterOrDigit() || it == '_' || it == '@' || it == '.' || it == '-' }.take(11)
            if (l.isNotEmpty() && out.size < 12) out.add(l)
        }
        TypeBanner.custom = if (out.isEmpty()) null else out.toTypedArray()
    }
}
