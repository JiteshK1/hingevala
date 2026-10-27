package com.hingevala

import android.app.Application
import org.lsposed.hiddenapibypass.HiddenApiBypass

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // libadb's Wireless debugging pairing uses TLS calls Android keeps hidden from apps.
        HiddenApiBypass.addHiddenApiExemptions("L")
        BotLog.init(this)
    }
}
