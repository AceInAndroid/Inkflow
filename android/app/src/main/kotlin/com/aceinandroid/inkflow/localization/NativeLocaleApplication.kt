package com.aceinandroid.inkflow.localization

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.Build

class NativeLocaleApplication : Application() {
    private val changes = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            NativeLocaleController.refresh()
        }
    }
    override fun onCreate() {
        super.onCreate()
        NativeLocaleController.initialize(this)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_LOCALE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(changes, filter, RECEIVER_EXPORTED)
        else registerReceiver(changes, filter)
    }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        NativeLocaleController.refresh()
    }
}
