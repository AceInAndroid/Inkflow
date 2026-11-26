package com.aceinandroid.inkflow.core.localization

import kotlin.test.Test
import kotlin.test.assertEquals

class AppLanguageResolverTest {
    @Test
    fun supportedLanguagesHaveCanonicalTagsAndNativeNames() {
        assertEquals(
            listOf("en" to "English", "zh-Hans" to "简体中文", "ja" to "日本語", "ko" to "한국어"),
            AppLanguage.entries.map { it.tag to it.nativeName },
        )
    }

    @Test
    fun everyManualLanguageOverridesAllAutomaticSignals() {
        val environment = LanguageEnvironment("CN", "Asia/Tokyo", listOf("ko", "en"))
        for (language in AppLanguage.entries) {
            assertEquals(
                LanguageResolution(language, LanguageResolutionSource.MANUAL),
                AppLanguageResolver.resolve(environment, manualLanguage = language),
            )
        }
    }

    @Test
    fun nullManualLanguageSelectsAuto() {
        val environment = LanguageEnvironment("JP", "Asia/Seoul", listOf("en"))
        assertEquals(
            LanguageResolution(AppLanguage.JAPANESE, LanguageResolutionSource.REGION),
            AppLanguageResolver.resolve(environment, manualLanguage = null),
        )
    }

    @Test
    fun allSupportedRegionsOverrideConflictingTimeZoneAndSystemLanguages() {
        val regions = mapOf(
            AppLanguage.SIMPLIFIED_CHINESE to listOf("CN", "TW", "HK", "MO"),
            AppLanguage.JAPANESE to listOf("JP"),
            AppLanguage.KOREAN to listOf("KR"),
            AppLanguage.ENGLISH to listOf("US", "GB", "IE", "AU", "NZ", "CA"),
        )
        for ((language, codes) in regions) {
            val conflict = if (language == AppLanguage.SIMPLIFIED_CHINESE) "ja" else "zh-Hans"
            val zone = if (language == AppLanguage.JAPANESE) "Asia/Shanghai" else "Asia/Tokyo"
            for (code in codes) {
                assertResolution(language, LanguageResolutionSource.REGION, code, zone, listOf(conflict))
            }
        }
    }

    @Test
    fun recognizedTimeZonesOverrideSystemLanguagesForUnmappedRegions() {
        val zones = mapOf(
            AppLanguage.SIMPLIFIED_CHINESE to listOf(
                "Asia/Shanghai", "Asia/Urumqi", "Asia/Taipei", "Asia/Hong_Kong", "Asia/Macau",
            ),
            AppLanguage.JAPANESE to listOf("Asia/Tokyo"),
            AppLanguage.KOREAN to listOf("Asia/Seoul"),
            AppLanguage.ENGLISH to listOf(
                "America/New_York", "America/Los_Angeles", "America/Anchorage", "Pacific/Honolulu",
                "America/Indiana/Indianapolis", "America/North_Dakota/Beulah", "Europe/London",
                "Europe/Dublin", "Australia/Sydney", "Australia/Eucla", "Australia/Lord_Howe",
                "Antarctica/Macquarie", "Pacific/Auckland", "Pacific/Chatham", "America/Toronto",
                "America/St_Johns", "America/Blanc-Sablon", "America/Atikokan", "America/Inuvik",
            ),
        )
        for ((language, ids) in zones) {
            val conflict = if (language == AppLanguage.JAPANESE) "ko" else "ja"
            for (id in ids) {
                assertResolution(language, LanguageResolutionSource.TIME_ZONE, "DE", id, listOf(conflict))
            }
        }
    }

    @Test
    fun chineseJapaneseAndKoreanTimeZoneAliasesAreRecognized() {
        for (zone in listOf(
            "PRC", "ROC", "Hongkong", "Asia/Chongqing", "Asia/Chungking", "Asia/Harbin",
            "Asia/Kashgar", "Asia/Macao",
        )) {
            assertResolution(AppLanguage.SIMPLIFIED_CHINESE, LanguageResolutionSource.TIME_ZONE, zone = zone)
        }
        assertResolution(AppLanguage.JAPANESE, LanguageResolutionSource.TIME_ZONE, zone = "Japan")
        assertResolution(AppLanguage.KOREAN, LanguageResolutionSource.TIME_ZONE, zone = "ROK")
    }

    @Test
    fun commonEnglishRegionTimeZoneAliasesAreRecognized() {
        for (zone in listOf(
            "US/Eastern", "US/Pacific", "US/Alaska", "US/Hawaii", "US/Arizona", "US/Central",
            "US/East-Indiana", "US/Indiana-Starke", "US/Michigan", "US/Mountain", "US/Aleutian",
            "America/Indianapolis", "America/Louisville", "America/Knox_IN", "Navajo",
            "GB", "GB-Eire", "Europe/Belfast", "Eire", "NZ", "NZ-CHAT",
            "Australia/ACT", "Australia/Canberra", "Australia/NSW", "Australia/LHI",
            "Australia/Queensland", "Australia/North", "Australia/South", "Australia/Tasmania",
            "Australia/Victoria", "Australia/West", "Australia/Yancowinna", "Australia/Currie",
            "Canada/Eastern", "Canada/Pacific", "Canada/Atlantic", "Canada/Central",
            "Canada/Mountain", "Canada/Newfoundland", "Canada/Saskatchewan", "Canada/Yukon",
            "America/Montreal", "America/Yellowknife", "America/Coral_Harbour",
        )) {
            assertResolution(
                AppLanguage.ENGLISH, LanguageResolutionSource.TIME_ZONE,
                zone = zone, tags = listOf("ko"),
            )
        }
    }

    @Test
    fun identifiersAreCaseInsensitiveAndTolerateSurroundingWhitespace() {
        assertResolution(AppLanguage.KOREAN, LanguageResolutionSource.REGION, region = " kR ")
        assertResolution(AppLanguage.ENGLISH, LanguageResolutionSource.REGION, region = " iE ")
        assertResolution(AppLanguage.SIMPLIFIED_CHINESE, LanguageResolutionSource.TIME_ZONE, zone = " aSiA/sHaNgHaI ")
        assertResolution(AppLanguage.JAPANESE, LanguageResolutionSource.TIME_ZONE, zone = " jApAn ")
        assertResolution(AppLanguage.ENGLISH, LanguageResolutionSource.TIME_ZONE, zone = " cAnAdA/eAsTeRn ")
        assertResolution(AppLanguage.KOREAN, LanguageResolutionSource.SYSTEM_LANGUAGE, tags = listOf(" Ko-kR "))
    }

    @Test
    fun unknownRegionAndTimeZoneUseFirstSupportedSystemLanguageInOriginalOrder() {
        assertResolution(
            AppLanguage.KOREAN, LanguageResolutionSource.SYSTEM_LANGUAGE,
            "FR", "Europe/Paris", listOf("fr-FR", "de-DE", "ko-KR", "ja-JP", "en-US"),
        )
        assertResolution(
            AppLanguage.JAPANESE, LanguageResolutionSource.SYSTEM_LANGUAGE,
            "ZZ", "Unknown/Zone", listOf("fr", "ja", "ko"),
        )
        assertResolution(
            AppLanguage.ENGLISH, LanguageResolutionSource.SYSTEM_LANGUAGE,
            tags = listOf("en-CA", "zh-Hans", "ja"),
        )
        assertResolution(
            AppLanguage.SIMPLIFIED_CHINESE, LanguageResolutionSource.SYSTEM_LANGUAGE,
            tags = listOf("zh", "en", "ja"),
        )
    }

    @Test
    fun chineseScriptAndRegionVariantsShareTheSimplifiedChineseUi() {
        for (tag in listOf(
            "zh", "zh-Hans", "zh-Hant", "zh-CN", "zh-TW", "zh-HK", "zh-MO", "zh-SG",
            "zh-Hans-CN", "zh-Hant-TW", "ZH-hAnT-hK", "zh_Hant_TW",
        )) {
            assertResolution(
                AppLanguage.SIMPLIFIED_CHINESE, LanguageResolutionSource.SYSTEM_LANGUAGE,
                tags = listOf("fr-FR", tag, "en"),
            )
        }
    }

    @Test
    fun supportedBaseLanguagesAllowRegionalTagsAndPlatformUnderscores() {
        for ((tag, language) in mapOf(
            "en-GB" to AppLanguage.ENGLISH,
            "en_AU" to AppLanguage.ENGLISH,
            "ja-JP" to AppLanguage.JAPANESE,
            "ja_JP" to AppLanguage.JAPANESE,
            "ko-KR" to AppLanguage.KOREAN,
            "ko_KR" to AppLanguage.KOREAN,
        )) {
            assertResolution(language, LanguageResolutionSource.SYSTEM_LANGUAGE, tags = listOf(tag))
        }
    }

    @Test
    fun offsetLikeZonesAndUtcDoNotIdentifyAGeographicLanguage() {
        for (zone in listOf(
            "UTC", "GMT", "Etc/UTC", "Etc/GMT", "Etc/GMT-8", "Etc/GMT-9", "Etc/GMT+5",
            "UTC+08:00", "GMT+09:00", "+08:00", "-05:00", "CST", "JST", "KST", "EST", "PST",
        )) {
            assertResolution(
                AppLanguage.KOREAN, LanguageResolutionSource.SYSTEM_LANGUAGE,
                zone = zone, tags = listOf("ko", "en"),
            )
        }
    }

    @Test
    fun unsupportedGeographicZonesAndInventedPrefixesDoNotMatch() {
        for (zone in listOf(
            "Asia/Singapore", "Asia/Kuala_Lumpur", "Asia/Manila", "Asia/Pyongyang",
            "Europe/Paris", "Europe/Lisbon", "Africa/Accra", "America/Mexico_City",
            "America/Panama", "America/Puerto_Rico", "Pacific/Fiji", "Asia/Shanghai/Unknown",
            "US/Unknown", "Canada/Unknown", "Australia/Unknown", "Asia/Beijing",
        )) {
            assertResolution(
                AppLanguage.JAPANESE, LanguageResolutionSource.SYSTEM_LANGUAGE,
                zone = zone, tags = listOf("ja"),
            )
        }
    }

    @Test
    fun missingBlankAndUnsupportedSignalsFallBackToEnglishWithDistinctSource() {
        for (environment in listOf(
            LanguageEnvironment(),
            LanguageEnvironment("", "", emptyList()),
            LanguageEnvironment("  ", "  ", listOf("", " ")),
            LanguageEnvironment("ZZ", "Unknown/Zone", listOf("fr-FR", "und", "xx", "zho", "eng")),
            LanguageEnvironment("DE", "Europe/Berlin", listOf("english", "japanese", "korean", "zhong")),
        )) {
            assertEquals(
                LanguageResolution(AppLanguage.ENGLISH, LanguageResolutionSource.FALLBACK),
                AppLanguageResolver.resolve(environment),
                environment.toString(),
            )
        }
    }

    @Test
    fun sourceDistinguishesIdenticalEnglishResultsAtEveryPriority() {
        assertResolution(AppLanguage.ENGLISH, LanguageResolutionSource.REGION, region = "US")
        assertResolution(AppLanguage.ENGLISH, LanguageResolutionSource.TIME_ZONE, zone = "Europe/London")
        assertResolution(AppLanguage.ENGLISH, LanguageResolutionSource.SYSTEM_LANGUAGE, tags = listOf("en"))
        assertResolution(AppLanguage.ENGLISH, LanguageResolutionSource.FALLBACK)
    }

    @Test
    fun repeatedResolutionsReflectOnlyCurrentInputs() {
        assertResolution(AppLanguage.JAPANESE, LanguageResolutionSource.REGION, region = "JP")
        assertResolution(AppLanguage.ENGLISH, LanguageResolutionSource.FALLBACK)
        assertResolution(AppLanguage.KOREAN, LanguageResolutionSource.TIME_ZONE, zone = "Asia/Seoul")
        assertResolution(AppLanguage.ENGLISH, LanguageResolutionSource.FALLBACK)
    }

    private fun assertResolution(
        language: AppLanguage,
        source: LanguageResolutionSource,
        region: String? = null,
        zone: String? = null,
        tags: List<String> = emptyList(),
    ) {
        val environment = LanguageEnvironment(region, zone, tags)
        assertEquals(
            LanguageResolution(language, source),
            AppLanguageResolver.resolve(environment),
            environment.toString(),
        )
    }
}
