import SwiftUI

@main
struct InkflowNativeApp: App {
    @StateObject private var library = LibraryStore()
    @StateObject private var preferences = AppPreferences()
    @Environment(\.scenePhase) private var scenePhase
    var body: some Scene {
        WindowGroup {
            NativeRootView()
                .environmentObject(library)
                .environmentObject(preferences)
                .environment(\.interfaceLanguage, preferences.resolvedLanguage())
                .environment(\.locale, Locale(identifier: preferences.resolvedLanguage().rawValue))
                .preferredColorScheme(preferences.theme == .system ? nil : preferences.theme == .dark ? .dark : .light)
                .onChange(of: scenePhase) { _, phase in
                    if phase != .active { library.flush() }
                    else { preferences.refreshSystemEnvironment() }
                }
                .onReceive(NotificationCenter.default.publisher(for: NSLocale.currentLocaleDidChangeNotification)) { _ in
                    preferences.refreshSystemEnvironment()
                }
                .onReceive(NotificationCenter.default.publisher(for: .NSSystemTimeZoneDidChange)) { _ in
                    preferences.refreshSystemEnvironment()
                }
        }
    }
}

private struct InterfaceLanguageKey: EnvironmentKey {
    static let defaultValue = InterfaceLanguage.en
}
extension EnvironmentValues {
    var interfaceLanguage: InterfaceLanguage {
        get { self[InterfaceLanguageKey.self] }
        set { self[InterfaceLanguageKey.self] = newValue }
    }
}
