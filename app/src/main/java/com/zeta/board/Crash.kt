package com.zeta.board

import android.content.Context
import android.os.Build
import com.google.firebase.crashlytics.FirebaseCrashlytics

/**
 * Crash reports (Firebase Crashlytics). Safe to call any time: does nothing when the build has no google-services.json.
 * PRIVACY: only crash stack traces, device model / Android version and the app version are sent.
 * Never put typed text, clipboard text, Quick Text content or anything the user wrote into a log line or key.
 */
object Crash {
    const val PREF = "crash_reports"

    private fun cl(): FirebaseCrashlytics? = try {
        FirebaseCrashlytics.getInstance()
    } catch (e: Throwable) {
        null
    }

    /** Applies the user's on/off choice (default on) and tags the reports with the app state. Call from Application.onCreate. */
    fun init(ctx: Context) {
        val c = cl() ?: return
        try {
            c.setCrashlyticsCollectionEnabled(Prefs.getB(ctx, PREF, true))
            c.setCustomKey("sdk", Build.VERSION.SDK_INT)
            c.setCustomKey("theme", Prefs.getI(ctx, "theme", 0))
            c.setCustomKey("apple_emoji", Prefs.getB(ctx, "apple_emoji", true))
            c.setCustomKey("singlish", Prefs.getB(ctx, "singlish", false))
        } catch (e: Throwable) {
        }
    }

    /** The user switched crash reports on or off in the app. */
    fun setEnabled(ctx: Context, on: Boolean) {
        Prefs.setB(ctx, PREF, on)
        try {
            cl()?.setCrashlyticsCollectionEnabled(on)
        } catch (e: Throwable) {
        }
    }

    /** Records a handled error (shows in the console as a non-fatal) so silent failures can be found too. [where] = a short label like "sendSticker". */
    fun log(where: String, e: Throwable) {
        try {
            val c = cl() ?: return
            c.log(where)
            c.recordException(e)
        } catch (t: Throwable) {
        }
    }

    /** A short breadcrumb that is attached to the next report. Labels only, never user text. */
    fun note(msg: String) {
        try {
            cl()?.log(msg)
        } catch (t: Throwable) {
        }
    }

    /** Deliberate crash to check that reports arrive (hidden: long-press the version line in About). Reopen the app afterwards to upload it. */
    fun testCrash() {
        throw RuntimeException("Zeta Board test crash")
    }
}
