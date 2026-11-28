import SwiftUI
import UniformTypeIdentifiers

struct HomeView: View {
    let isVisible: Bool
    let openBook: (NativeBook) -> Void
    let importBooks: () -> Void
    @EnvironmentObject private var library: LibraryStore
    @EnvironmentObject private var preferences: AppPreferences
    @Environment(\.interfaceLanguage) private var language
    @Environment(\.scenePhase) private var scenePhase
    @State private var wallpaper: UIImage?
    @State private var isOnScreen = false
    private var snapshot: LibraryHomeSnapshot { library.home }
    private var isInk: Bool { preferences.homeStyle == .eink }

    var body: some View {
        ScrollView {
            Group {
                switch preferences.homeStyle {
                case .classic:
                    VStack(alignment: .leading, spacing: 24) { heading.id("heading"); featured.id("featured"); recent.id("recent"); history.id("history") }.scrollTargetLayout()
                case .wallpaper:
                    VStack(alignment: .leading, spacing: 32) {
                        heading.id("heading")
                        Spacer(minLength: 120)
                        featured.id("featured")
                        recent.id("recent")
                        history.id("history")
                    }.scrollTargetLayout().padding().background(.regularMaterial, in: RoundedRectangle(cornerRadius: 24))
                case .minimal:
                    VStack(spacing: 48) { heading.id("heading"); featured.id("featured") }.scrollTargetLayout().frame(maxWidth: 480).padding(.vertical, 60)
                case .eink:
                    VStack(alignment: .leading, spacing: 28) {
                        heading.id("heading")
                        Divider().overlay(.black)
                        featured.id("featured")
                        recent.id("recent")
                        history.id("history")
                    }.scrollTargetLayout()
                }
            }.frame(maxWidth: 760).frame(maxWidth: .infinity).padding(24)
        }
        .scrollPosition(id: Binding<String?>(get: { preferences.homeAnchor }, set: {
            if let target = $0 { preferences.homeAnchor = target }
        }))
        .background {
            if isInk { Color.white.ignoresSafeArea() }
            else if preferences.homeStyle == .wallpaper, let wallpaper {
                GeometryReader { geometry in
                    Image(uiImage: wallpaper).resizable().scaledToFill()
                        .frame(width: geometry.size.width, height: geometry.size.height).clipped()
                }.ignoresSafeArea().accessibilityHidden(true)
            } else { Color(uiColor: .systemGroupedBackground).ignoresSafeArea() }
        }
        .foregroundStyle(isInk ? Color.black : Color.primary)
        .tint(isInk ? Color.black : Color.accentColor)
        .navigationTitle(InterfaceText.home.text(language))
        .toolbar { ToolbarItem(placement: .primaryAction) {
            NavigationLink(value: NativeDestination.homeSettings) {
                Label(InterfaceText.homeSettings.text(language), systemImage: "slider.horizontal.3")
            }
        } }
        .onAppear { isOnScreen = true }
        .onDisappear { isOnScreen = false }
        .task(id: preferences.wallpaperFile) { wallpaper = NativeWallpaper.load(preferences.wallpaperFile) }
    }

    private var heading: some View {
        VStack(alignment: .leading, spacing: 12) {
            if preferences.showClock {
                if isVisible && isOnScreen && scenePhase == .active {
                    TimelineView(.everyMinute) { context in clock(context.date) }
                } else { clock(Date()) }
            }
            if preferences.showMotto {
                Text(preferences.motto.isEmpty ? InterfaceText.defaultMotto.text(language) : preferences.motto)
                    .font(isInk ? .title3 : .title3.italic()).fixedSize(horizontal: false, vertical: true)
            }
        }.frame(maxWidth: .infinity, alignment: preferences.homeStyle == .minimal ? .center : .leading)
    }

    private func clock(_ date: Date) -> some View {
        VStack(alignment: .leading) {
            Text(date, format: .dateTime.hour().minute()).font(.system(.largeTitle, design: .rounded).weight(.light))
            Text(date, format: .dateTime.weekday(.wide).month().day()).font(.subheadline)
        }
    }

    @ViewBuilder private var featured: some View {
        if let book = snapshot.featured {
            VStack(alignment: .leading, spacing: 12) {
                Text((snapshot.recent.isEmpty ? InterfaceText.unread : .continueReading).text(language)).font(.headline)
                bookButton(book)
            }
        } else {
            ContentUnavailableView {
                Label(InterfaceText.emptyTitle.text(language), systemImage: "book")
            } description: { Text(InterfaceText.emptyDetail.text(language)) }
            actions: { Button(InterfaceText.importBook.text(language), action: importBooks).buttonStyle(.bordered) }
        }
    }

    private func bookButton(_ book: NativeBook) -> some View {
        Button { openBook(book) } label: {
            HStack(spacing: 16) {
                Image(systemName: book.format == .pdf ? "doc.richtext" : "book.closed")
                    .font(.largeTitle).frame(width: 54, height: 78)
                    .overlay(Rectangle().strokeBorder(lineWidth: isInk ? 2 : 0))
                VStack(alignment: .leading, spacing: 8) {
                    Text(book.title).font(.title3).multilineTextAlignment(.leading)
                    Text(book.format.rawValue.uppercased()).font(.caption)
                    ProgressView(value: book.progress)
                    Text(book.progress, format: .percent.precision(.fractionLength(0))).font(.caption)
                }
                Spacer(minLength: 0)
                Image(systemName: "chevron.right").accessibilityHidden(true)
            }.padding(16).frame(maxWidth: .infinity, minHeight: 100)
                .background(isInk ? Color.white : Color(uiColor: .secondarySystemGroupedBackground),
                            in: RoundedRectangle(cornerRadius: isInk ? 0 : 18))
                .overlay(RoundedRectangle(cornerRadius: isInk ? 0 : 18).strokeBorder(isInk ? Color.black : .clear))
                .contentShape(Rectangle())
        }.buttonStyle(.plain)
    }

    @ViewBuilder private var recent: some View {
        if preferences.showRecent {
            VStack(alignment: .leading, spacing: 12) {
                Text(InterfaceText.recentReading.text(language)).font(.headline)
                if snapshot.recent.isEmpty { Text(InterfaceText.noHistory.text(language)).font(.callout) }
                ForEach(Array(snapshot.recent.prefix(4))) { book in bookButton(book) }
            }
        }
    }

    @ViewBuilder private var history: some View {
        if preferences.showHistory {
            VStack(alignment: .leading, spacing: 12) {
                Text(InterfaceText.history.text(language)).font(.headline)
                ViewThatFits(in: .horizontal) {
                    HStack(spacing: 24) { historyCounts }
                    VStack(alignment: .leading, spacing: 12) { historyCounts }
                }
            }
        }
    }

    @ViewBuilder private var historyCounts: some View {
        count(snapshot.books.count, .books)
        count(snapshot.openedCount, .started)
        count(snapshot.completedCount, .completed)
    }

    private func count(_ number: Int, _ key: InterfaceText) -> some View {
        VStack(alignment: .leading) { Text(number, format: .number).font(.title2); Text(key.text(language)).font(.caption) }
    }
}

struct HomeSettingsView: View {
    @EnvironmentObject private var preferences: AppPreferences
    @Environment(\.interfaceLanguage) private var language
    @State private var choosingWallpaper = false
    @State private var wallpaperError = false
    var body: some View {
        Form {
            Section(InterfaceText.style.text(language)) {
                Picker(InterfaceText.style.text(language), selection: $preferences.homeStyle) {
                    ForEach(HomeStyle.allCases, id: \.self) { Text($0.title.text(language)).tag($0) }
                }
            }
            Section(InterfaceText.components.text(language)) {
                Toggle(InterfaceText.clock.text(language), isOn: $preferences.showClock)
                Toggle(InterfaceText.showMotto.text(language), isOn: $preferences.showMotto)
                Toggle(InterfaceText.showRecent.text(language), isOn: $preferences.showRecent)
                Toggle(InterfaceText.showHistory.text(language), isOn: $preferences.showHistory)
            }
            Section(InterfaceText.motto.text(language)) {
                TextField(InterfaceText.defaultMotto.text(language), text: $preferences.motto, axis: .vertical)
                Button(InterfaceText.resetMotto.text(language)) { preferences.motto = "" }
            }
            Section {
                Button(InterfaceText.chooseWallpaper.text(language)) { choosingWallpaper = true }
                if !preferences.wallpaperFile.isEmpty {
                    Button(InterfaceText.removeWallpaper.text(language)) { removeWallpaper() }
                }
            } header: { Text(InterfaceText.wallpaper.text(language)) }
            footer: { Text(InterfaceText.wallpaperHelp.text(language)) }
            Section {
                Button(InterfaceText.resetHome.text(language)) { removeWallpaper(); preferences.resetHome() }
            }
        }
        .navigationTitle(InterfaceText.homeSettings.text(language))
        .fileImporter(isPresented: $choosingWallpaper, allowedContentTypes: [.image]) { result in
            do {
                let url = try result.get()
                let file = try NativeWallpaper.importImage(url)
                removeWallpaper()
                preferences.wallpaperFile = file
            } catch {
                if (error as NSError).code != NSUserCancelledError {
                    wallpaperError = true
                }
            }
        }
        .alert(InterfaceText.wallpaper.text(language), isPresented: $wallpaperError) {
            Button(InterfaceText.ok.text(language)) { wallpaperError = false }
        } message: { Text(InterfaceText.invalidWallpaper.text(language)) }
    }
    private func removeWallpaper() {
        NativeWallpaper.remove(preferences.wallpaperFile)
        preferences.wallpaperFile = ""
    }
}

private enum NativeWallpaper {
    private static var directory: URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("NativeAppearance", isDirectory: true)
    }
    private static func url(_ file: String) -> URL? {
        guard !file.isEmpty, file == (file as NSString).lastPathComponent, file.hasSuffix(".jpg") else { return nil }
        return directory.appendingPathComponent(file)
    }
    static func load(_ file: String) -> UIImage? { url(file).flatMap { UIImage(contentsOfFile: $0.path) } }
    static func remove(_ file: String) { if let url = url(file) { try? FileManager.default.removeItem(at: url) } }
    static func importImage(_ source: URL) throws -> String {
        let scoped = source.startAccessingSecurityScopedResource()
        defer { if scoped { source.stopAccessingSecurityScopedResource() } }
        guard (try source.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0) <= 20 * 1024 * 1024 else {
            throw CocoaError(.fileReadTooLarge)
        }
        let data = try Data(contentsOf: source)
        guard data.count <= 20 * 1024 * 1024, let image = UIImage(data: data), image.size.width > 0, image.size.height > 0 else {
            throw CocoaError(.fileReadCorruptFile)
        }
        let scale = min(1, 2048 / max(image.size.width, image.size.height))
        let size = CGSize(width: image.size.width * scale, height: image.size.height * scale)
        let format = UIGraphicsImageRendererFormat(); format.scale = 1
        let normalized = UIGraphicsImageRenderer(size: size, format: format).image { _ in image.draw(in: CGRect(origin: .zero, size: size)) }
        guard let jpeg = normalized.jpegData(compressionQuality: 0.85) else { throw CocoaError(.fileReadCorruptFile) }
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let file = UUID().uuidString + ".jpg"
        try jpeg.write(to: directory.appendingPathComponent(file), options: .atomic)
        return file
    }
}
