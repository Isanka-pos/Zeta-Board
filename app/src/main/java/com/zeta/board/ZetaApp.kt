package com.zeta.board

import android.app.Application

class ZetaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ScreenGuard.install(this)
    }
}
