package com.pocketlm.app

import android.app.Application
import com.pocketlm.app.data.AppLog

class PocketApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLog.install(this)
    }
}
