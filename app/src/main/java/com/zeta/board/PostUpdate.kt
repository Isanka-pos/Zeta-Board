package com.zeta.board

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Notices that the app has just been updated, so we can tell the user what changed. */
object PostUpdate {

    /** Call on every start. Remembers the running version and flags "what's new" when it is higher than the last one. */
    fun detect(ctx: Context) {
        val app = ctx.applicationContext
        val cur = Updater.currentCode(app)
        val last = Prefs.getL(app, "last_code", 0L)
        if (last == cur) return
        var updated = last > 0L && last < cur
        if (last == 0L) {
            // The build before this feature never saved its version: tell an update from a first install by the install times.
            updated = try {
                val pi = app.packageManager.getPackageInfo(app.packageName, 0)
                pi.lastUpdateTime - pi.firstInstallTime > 10_000L
            } catch (e: Exception) {
                false
            }
        }
        Prefs.setL(app, "last_code", cur)
        if (updated) Prefs.setL(app, "whatsnew_code", cur)
    }

    /** True while the "what's new" screen for the running version has not been shown yet. */
    fun shouldShow(ctx: Context): Boolean {
        val app = ctx.applicationContext
        return Prefs.getL(app, "whatsnew_code", 0L) == Updater.currentCode(app)
    }

    fun markShown(ctx: Context) {
        val app = ctx.applicationContext
        Prefs.setL(app, "whatsnew_code", 0L)
        UpdateNotifier.cancelInstalled(app)
        UpdateNotifier.cancelAvailable(app)
    }

    /** Release notes of the running version that were saved when the update was announced (empty if unknown). */
    fun savedNotes(ctx: Context): String {
        val app = ctx.applicationContext
        return if (Prefs.getL(app, "avail_code", 0L) == Updater.currentCode(app)) Prefs.getS(app, "avail_notes", "") else ""
    }

    fun notes(ctx: Context): String = savedNotes(ctx).ifBlank { "Improvements and bug fixes" }
}

/**
 * Runs right after Zeta Board has been updated (even if the app is not opened) and after a reboot.
 * It posts the "Zeta Board updated" notification and makes sure the 45-minute update check is scheduled.
 */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try {
            UpdateScheduler.schedule(context)
            if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
                PostUpdate.detect(context)
                UpdateNotifier.cancelAvailable(context)
                if (PostUpdate.shouldShow(context)) {
                    UpdateNotifier.showInstalled(context, Updater.currentName(context), PostUpdate.notes(context))
                }
            }
        } catch (e: Exception) {
        }
    }
}
