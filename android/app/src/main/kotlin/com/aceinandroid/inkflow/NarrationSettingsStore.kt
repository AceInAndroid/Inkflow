package com.aceinandroid.inkflow

import android.content.Context

data class NarrationSettings(
    val rate: Float = 1f,
    val pitch: Float = 1f,
    val voiceName: String? = null,
    val continuous: Boolean = false,
)

class NarrationSettingsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("native_narration", Context.MODE_PRIVATE)

    fun load(): NarrationSettings = NarrationSettings(
        rate = preferences.getFloat("rate", 1f).validRate(),
        pitch = preferences.getFloat("pitch", 1f).validPitch(),
        voiceName = preferences.getString("voice", null),
        continuous = preferences.getBoolean("continuous", false),
    )

    fun save(value: NarrationSettings) {
        preferences.edit()
            .putFloat("rate", value.rate.validRate())
            .putFloat("pitch", value.pitch.validPitch())
            .putString("voice", value.voiceName)
            .putBoolean("continuous", value.continuous)
            .apply()
    }

    private fun Float.validRate(): Float = if (isFinite()) coerceIn(0.1f, 4f) else 1f
    private fun Float.validPitch(): Float = if (isFinite()) coerceIn(0.5f, 2f) else 1f
}
