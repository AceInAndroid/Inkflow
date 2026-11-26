package com.aceinandroid.inkflow.core.localization

/** A null manual selection means auto: region, named time zone, system list, then English. */
object AppLanguageResolver {
    fun resolve(
        environment: LanguageEnvironment,
        manualLanguage: AppLanguage? = null,
    ): LanguageResolution {
        if (manualLanguage != null) {
            return LanguageResolution(manualLanguage, LanguageResolutionSource.MANUAL)
        }
        regionLanguage(environment.regionCode)?.let {
            return LanguageResolution(it, LanguageResolutionSource.REGION)
        }
        zoneLanguage(environment.zoneId)?.let {
            return LanguageResolution(it, LanguageResolutionSource.TIME_ZONE)
        }
        for (tag in environment.systemLanguageTags) {
            systemLanguage(tag)?.let {
                return LanguageResolution(it, LanguageResolutionSource.SYSTEM_LANGUAGE)
            }
        }
        return LanguageResolution(AppLanguage.ENGLISH, LanguageResolutionSource.FALLBACK)
    }

    private fun regionLanguage(regionCode: String?): AppLanguage? = when (regionCode?.trim()?.uppercase()) {
        "CN", "TW", "HK", "MO" -> AppLanguage.SIMPLIFIED_CHINESE
        "JP" -> AppLanguage.JAPANESE
        "KR" -> AppLanguage.KOREAN
        "US", "GB", "IE", "AU", "NZ", "CA" -> AppLanguage.ENGLISH
        else -> null
    }

    private fun systemLanguage(tag: String): AppLanguage? =
        when (tag.trim().replace('_', '-').substringBefore('-').lowercase()) {
            "en" -> AppLanguage.ENGLISH
            // There is one Chinese UI: all Chinese regions and scripts use Simplified Chinese.
            "zh" -> AppLanguage.SIMPLIFIED_CHINESE
            "ja" -> AppLanguage.JAPANESE
            "ko" -> AppLanguage.KOREAN
            else -> null
        }

    private fun zoneLanguage(zoneId: String?): AppLanguage? = when (zoneId?.trim()?.lowercase()) {
        in chineseZones -> AppLanguage.SIMPLIFIED_CHINESE
        "asia/tokyo", "japan" -> AppLanguage.JAPANESE
        "asia/seoul", "rok" -> AppLanguage.KOREAN
        in englishZones -> AppLanguage.ENGLISH
        else -> null
    }

    // Explicit IANA identifiers and legacy names; never infer a country from an offset or prefix.
    private val chineseZones = setOf(
        "asia/shanghai", "asia/urumqi", "asia/taipei", "asia/hong_kong", "asia/macau",
        "asia/chongqing", "asia/chungking", "asia/harbin", "asia/kashgar", "asia/macao",
        "prc", "roc", "hongkong",
    )

    private val englishZones = setOf(
        // United States, including historical identifiers.
        "america/new_york", "america/detroit", "america/kentucky/louisville",
        "america/kentucky/monticello", "america/indiana/indianapolis",
        "america/indiana/vincennes", "america/indiana/winamac", "america/indiana/marengo",
        "america/indiana/petersburg", "america/indiana/vevay", "america/chicago",
        "america/indiana/tell_city", "america/indiana/knox", "america/menominee",
        "america/north_dakota/center", "america/north_dakota/new_salem",
        "america/north_dakota/beulah", "america/denver", "america/boise", "america/phoenix",
        "america/los_angeles", "america/anchorage", "america/juneau", "america/sitka",
        "america/metlakatla", "america/yakutat", "america/nome", "america/adak",
        "pacific/honolulu", "america/atka", "america/fort_wayne", "america/indianapolis",
        "america/knox_in", "america/louisville", "america/shiprock", "navajo",
        "us/alaska", "us/aleutian", "us/arizona", "us/central", "us/east-indiana",
        "us/eastern", "us/hawaii", "us/indiana-starke", "us/michigan", "us/mountain",
        "us/pacific", "us/pacific-new",
        // United Kingdom and Ireland. GMT/UTC alone carry no geographic evidence.
        "europe/london", "europe/belfast", "europe/dublin", "gb", "gb-eire", "eire",
        // Australia.
        "antarctica/macquarie", "australia/lord_howe", "australia/hobart",
        "australia/melbourne", "australia/sydney", "australia/broken_hill",
        "australia/brisbane", "australia/lindeman", "australia/adelaide", "australia/darwin",
        "australia/perth", "australia/eucla", "australia/act", "australia/canberra",
        "australia/lhi", "australia/nsw", "australia/north", "australia/queensland",
        "australia/south", "australia/tasmania", "australia/victoria", "australia/west",
        "australia/yancowinna", "australia/currie",
        // New Zealand.
        "pacific/auckland", "pacific/chatham", "nz", "nz-chat",
        // Canada, including historical identifiers.
        "america/st_johns", "america/halifax", "america/glace_bay", "america/moncton",
        "america/goose_bay", "america/blanc-sablon", "america/toronto", "america/iqaluit",
        "america/atikokan", "america/winnipeg", "america/resolute", "america/rankin_inlet",
        "america/regina", "america/swift_current", "america/edmonton", "america/cambridge_bay",
        "america/inuvik", "america/vancouver", "america/creston", "america/dawson_creek",
        "america/fort_nelson", "america/whitehorse", "america/dawson", "america/montreal",
        "america/nipigon", "america/thunder_bay", "america/pangnirtung", "america/rainy_river",
        "america/yellowknife", "america/coral_harbour", "canada/atlantic", "canada/central",
        "canada/eastern", "canada/mountain", "canada/newfoundland", "canada/pacific",
        "canada/saskatchewan", "canada/east-saskatchewan", "canada/yukon",
    )
}
