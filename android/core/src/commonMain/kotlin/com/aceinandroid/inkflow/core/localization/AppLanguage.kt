package com.aceinandroid.inkflow.core.localization

enum class AppLanguage(val tag: String, val nativeName: String) {
    ENGLISH("en", "English"),
    SIMPLIFIED_CHINESE("zh-Hans", "简体中文"),
    JAPANESE("ja", "日本語"),
    KOREAN("ko", "한국어"),
}

/** Platform code supplies these values; resolution never reads platform state. */
data class LanguageEnvironment(
    val regionCode: String? = null,
    val zoneId: String? = null,
    val systemLanguageTags: List<String> = emptyList(),
)

enum class LanguageResolutionSource {
    MANUAL,
    REGION,
    TIME_ZONE,
    SYSTEM_LANGUAGE,
    FALLBACK,
}

data class LanguageResolution(
    val language: AppLanguage,
    val source: LanguageResolutionSource,
)
