package com.zeta.board

import android.app.Application

class ZetaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ScreenGuard.install(this)
        Push.subscribe(this)
        // the old translate panel kept a history and optional server settings; none of that exists any more, so wipe it
        Prefs.removeAll(this, listOf("tr_history", "tr_hist", "tr_key", "tr_url", "tr_email", "tr_provider", "tr_from"))
        if (AppleEmoji.enabled(this)) AppleEmoji.load(this) {}
    }
}
