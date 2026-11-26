package com.aceinandroid.inkflow

import android.content.Context
import java.util.UUID

internal object NativeProgressIdentity {
    private const val PREFERENCES = "native_progress_sync"
    private const val DEVICE_ID = "device_id"

    fun getOrCreate(context: Context): String = synchronized(this) {
        val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        preferences.getString(DEVICE_ID, null)?.takeIf(String::isNotBlank) ?: UUID.randomUUID().toString().also {
            check(preferences.edit().putString(DEVICE_ID, it).commit()) { "Cannot save device identity" }
        }
    }
}
