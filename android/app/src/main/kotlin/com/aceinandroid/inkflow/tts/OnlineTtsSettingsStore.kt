package com.aceinandroid.inkflow.tts

import android.content.Context
import com.aceinandroid.inkflow.ai.NativeAiSecrets
import java.io.IOException

/** Device-local settings; the existing backup only exports Flutter preferences. */
internal class OnlineTtsSettingsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("native_online_tts", Context.MODE_PRIVATE)

    fun load(): OnlineTtsConfig {
        val encrypted = preferences.getString("key_encrypted", "").orEmpty()
        return OnlineTtsConfig(preferences.getBoolean("enabled", false),
            preferences.getString("endpoint", "").orEmpty(),
            if (encrypted.isEmpty()) "" else NativeAiSecrets.decrypt(encrypted),
            preferences.getString("model", "").orEmpty(), preferences.getString("voice", "alloy").orEmpty(),
            // Older configurations are OpenAI-compatible; an unknown value must never silently send a key elsewhere.
            OnlineTtsProvider.valueOf(preferences.getString("provider", OnlineTtsProvider.OPENAI.name).orEmpty()),
            preferences.getString("language", "en-US").orEmpty())
    }

    fun save(config: OnlineTtsConfig) {
        if (config.enabled) config.validate()
        val encrypted = if (config.apiKey.isBlank()) "" else NativeAiSecrets.encrypt(config.apiKey)
        if (!preferences.edit().putBoolean("enabled", config.enabled).putString("endpoint", config.endpoint.trim())
                .putString("key_encrypted", encrypted).putString("model", config.model.trim())
                .putString("voice", config.voice.trim()).putString("provider", config.provider.name)
                .putString("language", config.language.trim()).commit()) throw IOException("Cannot save speech settings")
    }
}
