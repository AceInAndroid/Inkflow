import Foundation
import Combine

enum NativeTab: String, CaseIterable, Identifiable {
    case home, library, me
    var id: Self { self }
    var title: InterfaceText { switch self { case .home: return .home; case .library: return .library; case .me: return .me } }
    var icon: String { switch self { case .home: return "house"; case .library: return "books.vertical"; case .me: return "person.crop.circle" } }
}
enum NativeDestination: String, Hashable { case homeSettings }

enum HomeStyle: String, CaseIterable { case classic, wallpaper, minimal, eink }
enum AppTheme: String, CaseIterable { case system, light, dark }
enum InterfaceLanguage: String, CaseIterable { case en, zhHans = "zh-Hans", ja, ko }
enum LanguageChoice: String, CaseIterable {
    case automatic, en, zhHans = "zh-Hans", ja, ko
    var explicitLanguage: InterfaceLanguage? { InterfaceLanguage(rawValue: rawValue) }
}

/// Uses raw OS preferences, never Bundle's already-negotiated application language.
enum InterfaceLanguageResolver {
    static func resolve(choice: LanguageChoice, region: String?, timeZone: String,
                        preferredLanguages: [String]) -> InterfaceLanguage {
        if let explicit = choice.explicitLanguage { return explicit }
        switch region?.uppercased() {
        case "CN", "TW", "HK", "MO", "SG": return .zhHans
        case "JP": return .ja
        case "KR": return .ko
        case "US", "GB", "AU", "CA", "NZ", "IE": return .en
        default: break
        }
        // Deliberately enumerated IANA IDs; offsets and broad Asia/* guesses are unsafe.
        switch timeZone {
        case "Asia/Shanghai", "Asia/Chongqing", "Asia/Chungking", "Asia/Harbin",
             "Asia/Urumqi", "Asia/Taipei", "Asia/Hong_Kong", "Asia/Macau", "Asia/Macao",
             "Asia/Singapore", "Singapore", "PRC", "ROC", "Hongkong": return .zhHans
        case "Asia/Tokyo", "Japan": return .ja
        case "Asia/Seoul", "ROK": return .ko
        case "America/New_York", "America/Detroit", "America/Chicago", "America/Denver",
             "America/Phoenix", "America/Los_Angeles", "America/Anchorage", "Pacific/Honolulu",
             "America/Toronto", "America/Vancouver", "Europe/London", "Europe/Dublin",
             "Australia/Sydney", "Australia/Melbourne", "Australia/Brisbane", "Australia/Perth",
             "Australia/Adelaide", "Australia/Darwin", "Australia/Hobart", "Pacific/Auckland": return .en
        default: break
        }
        for raw in preferredLanguages {
            switch raw.replacingOccurrences(of: "_", with: "-").lowercased().split(separator: "-").first {
            case "zh": return .zhHans
            case "ja": return .ja
            case "ko": return .ko
            case "en": return .en
            default: continue
            }
        }
        return .en
    }
}

/// UserDefaults.standard is device-local; these keys never enter a library/export payload.
@MainActor
final class AppPreferences: ObservableObject {
    private let defaults: UserDefaults
    private static let prefix = "inkflow.native."
    @Published var homeStyle: HomeStyle { didSet { save("homeStyle", homeStyle.rawValue); if oldValue != homeStyle { homeAnchor = nil } } }
    @Published var theme: AppTheme { didSet { save("theme", theme.rawValue) } }
    @Published var languageChoice: LanguageChoice { didSet { save("language", languageChoice.rawValue) } }
    @Published var motto: String { didSet { save("motto", motto) } }
    @Published var showClock: Bool { didSet { save("showClock", showClock) } }
    @Published var showMotto: Bool { didSet { save("showMotto", showMotto) } }
    @Published var showRecent: Bool { didSet {
        save("showRecent", showRecent)
        if !showRecent && homeAnchor == "recent" { homeAnchor = nil }
    } }
    @Published var showHistory: Bool { didSet {
        save("showHistory", showHistory)
        if !showHistory && homeAnchor == "history" { homeAnchor = nil }
    } }
    @Published var wallpaperFile: String { didSet { save("wallpaperFile", wallpaperFile) } }

    @Published var selectedTab: NativeTab { didSet { save("selectedTab", selectedTab.rawValue) } }
    @Published var librarySearch: String { didSet { save("librarySearch", librarySearch) } }
    @Published var libraryAnchor: UUID? { didSet { save("libraryAnchor", libraryAnchor?.uuidString) } }
    @Published var homeAnchor: String? { didSet { save("homeAnchor", homeAnchor) } }
    @Published var homePath: [NativeDestination] { didSet { save("homePath", homePath.map(\.rawValue)) } }
    @Published var mePath: [NativeDestination] { didSet { save("mePath", mePath.map(\.rawValue)) } }

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        func value(_ key: String) -> String { defaults.string(forKey: Self.prefix + key) ?? "" }
        func flag(_ key: String) -> Bool { defaults.object(forKey: Self.prefix + key) as? Bool ?? true }
        homeStyle = HomeStyle(rawValue: value("homeStyle")) ?? .classic
        theme = AppTheme(rawValue: value("theme")) ?? .system
        languageChoice = LanguageChoice(rawValue: value("language")) ?? .automatic
        motto = value("motto")
        showClock = flag("showClock"); showMotto = flag("showMotto")
        showRecent = flag("showRecent"); showHistory = flag("showHistory")
        wallpaperFile = value("wallpaperFile")
        selectedTab = NativeTab(rawValue: value("selectedTab")) ?? .home
        librarySearch = value("librarySearch")
        libraryAnchor = UUID(uuidString: value("libraryAnchor"))
        let anchor = value("homeAnchor")
        let minimal = HomeStyle(rawValue: value("homeStyle")) == .minimal
        let validAnchors = ["heading", "featured"] + (flag("showRecent") && !minimal ? ["recent"] : []) + (flag("showHistory") && !minimal ? ["history"] : [])
        homeAnchor = validAnchors.contains(anchor) ? anchor : nil
        func path(_ key: String) -> [NativeDestination] {
            let values = defaults.stringArray(forKey: Self.prefix + key) ?? []
            return values == [NativeDestination.homeSettings.rawValue] ? [.homeSettings] : []
        }
        homePath = path("homePath"); mePath = path("mePath")
    }

    func resolvedLanguage(region: String? = Locale.current.region?.identifier,
                          timeZone: String = TimeZone.current.identifier,
                          preferredLanguages: [String] = Locale.preferredLanguages) -> InterfaceLanguage {
        InterfaceLanguageResolver.resolve(choice: languageChoice, region: region,
            timeZone: timeZone, preferredLanguages: preferredLanguages)
    }

    func refreshSystemEnvironment() { objectWillChange.send() }

    func resetHome() {
        homeStyle = .classic; motto = ""; wallpaperFile = ""; homeAnchor = nil
        showClock = true; showMotto = true; showRecent = true; showHistory = true
    }

    func reconcileLibrary(_ bookIDs: Set<UUID>) {
        if let libraryAnchor, !bookIDs.contains(libraryAnchor) { self.libraryAnchor = nil }
    }

    private func save(_ key: String, _ value: Any?) { defaults.set(value, forKey: Self.prefix + key) }
}
