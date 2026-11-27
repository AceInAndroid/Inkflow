package com.aceinandroid.inkflow.ui.theme

import android.content.Context

/** App chrome only. ReaderAppearanceStore remains the authority for reading paper and ink. */
enum class AppAppearance { SYSTEM, LIGHT, DARK }

class AppAppearanceStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("inkflow_app_appearance", Context.MODE_PRIVATE)

    fun load(): AppAppearance = AppAppearance.entries.firstOrNull {
        it.name == preferences.getString("mode", null)
    } ?: AppAppearance.SYSTEM

    fun save(mode: AppAppearance) {
        preferences.edit().putString("mode", mode.name).apply()
    }
}
