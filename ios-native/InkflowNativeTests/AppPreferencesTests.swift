import XCTest
@testable import InkflowNative

final class InterfaceLanguageTests: XCTestCase {
    private func resolve(_ region: String?, _ zone: String, _ languages: [String],
                         choice: LanguageChoice = .automatic) -> InterfaceLanguage {
        InterfaceLanguageResolver.resolve(choice: choice, region: region, timeZone: zone, preferredLanguages: languages)
    }

    func testManualChoicesOverrideEverySystemSignal() {
        for choice in LanguageChoice.allCases where choice != .automatic {
            XCTAssertEqual(resolve("JP", "Asia/Shanghai", ["ko-KR"], choice: choice), choice.explicitLanguage)
        }
    }

    func testRegionPrecedesTimeZoneAndRawLanguage() {
        for region in ["CN", "TW", "HK", "MO", "SG", "cn"] {
            XCTAssertEqual(resolve(region, "Asia/Tokyo", ["ko-KR"]), .zhHans)
        }
        XCTAssertEqual(resolve("JP", "Asia/Shanghai", ["en-US"]), .ja)
        XCTAssertEqual(resolve("KR", "Asia/Tokyo", ["en-US"]), .ko)
        for region in ["US", "GB", "AU", "CA", "NZ", "IE"] {
            XCTAssertEqual(resolve(region, "Asia/Seoul", ["zh-Hant"]), .en)
        }
    }

    func testExplicitTimeZonePrecedesLanguagesWithoutGuessingOffsetsOrPrefixes() {
        for zone in ["Asia/Shanghai", "Asia/Urumqi", "Asia/Taipei", "Asia/Hong_Kong", "Asia/Macau", "Asia/Singapore", "PRC"] {
            XCTAssertEqual(resolve("DE", zone, ["ja-JP"]), .zhHans)
        }
        XCTAssertEqual(resolve(nil, "Asia/Tokyo", ["en-US"]), .ja)
        XCTAssertEqual(resolve(nil, "Asia/Seoul", ["en-US"]), .ko)
        XCTAssertEqual(resolve(nil, "Europe/London", ["ja-JP"]), .en)
        for unknown in ["Asia/Bangkok", "Asia/Tokyo_Fake", "GMT+09:00", "UTC", ""] {
            XCTAssertEqual(resolve("DE", unknown, ["ko-KR"]), .ko)
        }
    }

    func testRawPreferredLanguagesUseFirstSupportedBaseAndEnglishFallback() {
        XCTAssertEqual(resolve(nil, "UTC", ["fr-FR", "ja_JP", "ko-KR"]), .ja)
        XCTAssertEqual(resolve(nil, "UTC", ["zh-Hant-TW", "en-US"]), .zhHans)
        XCTAssertEqual(resolve(nil, "UTC", ["KO-kr"]), .ko)
        XCTAssertEqual(resolve(nil, "UTC", ["en-GB", "zh-Hans"]), .en)
        XCTAssertEqual(resolve(nil, "UTC", ["fr", "de", "english"]), .en)
        XCTAssertEqual(resolve(nil, "UTC", []), .en)
    }

    func testEveryInterfaceKeyHasFourNonemptyTranslations() {
        for key in InterfaceText.allCases {
            for language in InterfaceLanguage.allCases {
                XCTAssertFalse(key.text(language).trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, "\(key) / \(language)")
            }
        }
        XCTAssertEqual(InterfaceText.home.text(.zhHans), "首页")
        XCTAssertEqual(InterfaceText.library.text(.ja), "ライブラリ")
        XCTAssertEqual(InterfaceText.me.text(.ko), "내 정보")
    }
}

@MainActor
final class AppPreferencesTests: XCTestCase {
    private var suite: String!
    private var defaults: UserDefaults!
    override func setUp() {
        super.setUp()
        suite = "InkflowPreferencesTests.\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suite)!
    }
    override func tearDown() {
        defaults.removePersistentDomain(forName: suite)
        super.tearDown()
    }

    func testDefaultsAndAllHomeStylesRoundTrip() {
        let preferences = AppPreferences(defaults: defaults)
        XCTAssertEqual(preferences.homeStyle, .classic)
        XCTAssertEqual(preferences.theme, .system)
        XCTAssertEqual(preferences.languageChoice, .automatic)
        XCTAssertEqual(preferences.motto, "")
        XCTAssertTrue(preferences.showClock && preferences.showMotto && preferences.showRecent && preferences.showHistory)
        for style in HomeStyle.allCases {
            preferences.homeStyle = style
            XCTAssertEqual(AppPreferences(defaults: defaults).homeStyle, style)
        }
    }

    func testSettingsPersistWithoutTranslatingUserMotto() {
        let preferences = AppPreferences(defaults: defaults)
        let custom = "原文 🦉 日本語 한국어\nKeep my words."
        preferences.motto = custom
        preferences.theme = .dark
        preferences.wallpaperFile = "local-image.jpg"
        preferences.showClock = false; preferences.showMotto = false
        preferences.showRecent = false; preferences.showHistory = false
        for choice in LanguageChoice.allCases {
            preferences.languageChoice = choice
            let restored = AppPreferences(defaults: defaults)
            XCTAssertEqual(restored.languageChoice, choice)
            XCTAssertEqual(restored.motto, custom)
            XCTAssertEqual(restored.theme, .dark)
            XCTAssertEqual(restored.wallpaperFile, "local-image.jpg")
            XCTAssertFalse(restored.showClock || restored.showMotto || restored.showRecent || restored.showHistory)
        }
    }

    func testAutomaticReevaluatesSystemInputsAndManualChoiceRemainsFixed() {
        let preferences = AppPreferences(defaults: defaults)
        XCTAssertEqual(preferences.resolvedLanguage(region: "JP", timeZone: "UTC", preferredLanguages: []), .ja)
        XCTAssertEqual(preferences.resolvedLanguage(region: "KR", timeZone: "UTC", preferredLanguages: []), .ko)
        preferences.languageChoice = .zhHans
        XCTAssertEqual(AppPreferences(defaults: defaults).resolvedLanguage(region: "US", timeZone: "UTC", preferredLanguages: ["en"]), .zhHans)
    }

    func testUnknownPreferencesFallBackAndHomeResetKeepsLanguageAndTheme() {
        defaults.set("future-style", forKey: "inkflow.native.homeStyle")
        defaults.set("future-theme", forKey: "inkflow.native.theme")
        defaults.set("future-language", forKey: "inkflow.native.language")
        defaults.set("bad-boolean", forKey: "inkflow.native.showClock")
        let preferences = AppPreferences(defaults: defaults)
        XCTAssertEqual(preferences.homeStyle, .classic)
        XCTAssertEqual(preferences.theme, .system)
        XCTAssertEqual(preferences.languageChoice, .automatic)
        XCTAssertTrue(preferences.showClock)
        preferences.languageChoice = .ko; preferences.theme = .light
        preferences.motto = "Mine"; preferences.homeStyle = .minimal
        preferences.showRecent = false; preferences.wallpaperFile = "image.jpg"
        preferences.resetHome()
        let restored = AppPreferences(defaults: defaults)
        XCTAssertEqual(restored.homeStyle, .classic)
        XCTAssertTrue(restored.showRecent)
        XCTAssertEqual(restored.motto, "")
        XCTAssertEqual(restored.wallpaperFile, "")
        XCTAssertEqual(restored.languageChoice, .ko)
        XCTAssertEqual(restored.theme, .light)
    }

    func testPreferencesAreIsolatedBetweenDeviceStores() {
        let secondSuite = "InkflowPreferencesTests.\(UUID().uuidString)"
        let second = UserDefaults(suiteName: secondSuite)!
        defer { second.removePersistentDomain(forName: secondSuite) }
        AppPreferences(defaults: defaults).languageChoice = .ja
        AppPreferences(defaults: defaults).motto = "Private device motto"
        XCTAssertEqual(AppPreferences(defaults: second).languageChoice, .automatic)
        XCTAssertEqual(AppPreferences(defaults: second).motto, "")
    }
    func testNavigationSearchAndScrollTargetsSurvivePreferenceReload() {
        let preferences = AppPreferences(defaults: defaults)
        let bookID = UUID()
        preferences.selectedTab = .me
        preferences.librarySearch = "原文 한국어"
        preferences.libraryAnchor = bookID
        preferences.homeAnchor = "recent"
        preferences.homePath = [.homeSettings]
        preferences.mePath = [.homeSettings]
        preferences.languageChoice = .ja
        let restored = AppPreferences(defaults: defaults)
        XCTAssertEqual(restored.selectedTab, .me)
        XCTAssertEqual(restored.librarySearch, "原文 한국어")
        XCTAssertEqual(restored.libraryAnchor, bookID)
        XCTAssertEqual(restored.homeAnchor, "recent")
        XCTAssertEqual(restored.homePath, [.homeSettings])
        XCTAssertEqual(restored.mePath, [.homeSettings])
        restored.reconcileLibrary([bookID])
        XCTAssertEqual(restored.libraryAnchor, bookID)
        restored.reconcileLibrary([])
        XCTAssertNil(AppPreferences(defaults: defaults).libraryAnchor)
        restored.homeStyle = .minimal
        XCTAssertNil(AppPreferences(defaults: defaults).homeAnchor)
        XCTAssertEqual(restored.homePath, [.homeSettings])
    }

    func testUnknownNavigationStateCannotRestoreAnInvalidDestination() {
        defaults.set("future-tab", forKey: "inkflow.native.selectedTab")
        defaults.set(["homeSettings", "future-route"], forKey: "inkflow.native.homePath")
        defaults.set(["future-route"], forKey: "inkflow.native.mePath")
        defaults.set("not-a-uuid", forKey: "inkflow.native.libraryAnchor")
        defaults.set("future-section", forKey: "inkflow.native.homeAnchor")
        let restored = AppPreferences(defaults: defaults)
        XCTAssertEqual(restored.selectedTab, .home)
        XCTAssertTrue(restored.homePath.isEmpty && restored.mePath.isEmpty)
        XCTAssertNil(restored.libraryAnchor)
        XCTAssertNil(restored.homeAnchor)
    }

    func testHiddenHomeSectionsCannotLeaveDanglingScrollTargets() {
        let preferences = AppPreferences(defaults: defaults)
        preferences.homeAnchor = "recent"
        preferences.showRecent = false
        XCTAssertNil(AppPreferences(defaults: defaults).homeAnchor)
        preferences.homeAnchor = "history"
        preferences.showHistory = false
        XCTAssertNil(AppPreferences(defaults: defaults).homeAnchor)
        defaults.set("recent", forKey: "inkflow.native.homeAnchor")
        defaults.set(HomeStyle.minimal.rawValue, forKey: "inkflow.native.homeStyle")
        XCTAssertNil(AppPreferences(defaults: defaults).homeAnchor)
    }

}
