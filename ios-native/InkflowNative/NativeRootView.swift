import SwiftUI
import UniformTypeIdentifiers

struct NativeRootView: View {
    @EnvironmentObject private var library: LibraryStore
    @Environment(\.interfaceLanguage) private var language
    @Environment(\.horizontalSizeClass) private var sizeClass
    @EnvironmentObject private var preferences: AppPreferences
    @State private var importing = false
    @State private var reader: NativeBook?
    @State private var importResults: [LibraryImportEntry] = []

    var body: some View {
        Group {
            if sizeClass == .regular {
                NavigationSplitView {
                    List(NativeTab.allCases, selection: Binding<NativeTab?>(
                        get: { preferences.selectedTab }, set: { if let value = $0 { preferences.selectedTab = value } })) { item in
                        Label(item.title.text(language), systemImage: item.icon).tag(item)
                    }.navigationTitle("Inkflow")
                } detail: { content(preferences.selectedTab) }
            } else {
                TabView(selection: $preferences.selectedTab) {
                    ForEach(NativeTab.allCases) { item in
                        content(item).tabItem { Label(item.title.text(language), systemImage: item.icon) }
                            .tag(item)
                    }
                }
            }
        }
        .fileImporter(isPresented: $importing, allowedContentTypes: [.plainText, .pdf], allowsMultipleSelection: true) { result in
            do {
                importResults = library.importFiles(try result.get())
                preferences.librarySearch = ""
                preferences.libraryAnchor = importResults.last(where: { $0.book != nil })?.book?.id
                preferences.selectedTab = .library
            } catch {
                if (error as NSError).code != NSUserCancelledError {
                    library.error = LibraryFailure(operation: .importFailed, cause: error)
                }
            }
        }
        .fullScreenCover(item: $reader) { book in
            NavigationStack {
                ReaderView(book: book)
                    .toolbar { ToolbarItem(placement: .cancellationAction) {
                        Button(InterfaceText.close.text(language), systemImage: "xmark") { reader = nil }
                            .labelStyle(.iconOnly)
                    } }
            }
        }
        .onAppear { preferences.reconcileLibrary(Set(library.books.map(\.id))) }
        .onChange(of: library.books.map(\.id)) { _, ids in preferences.reconcileLibrary(Set(ids)) }
        .alert((library.error?.operation ?? .library).text(language), isPresented: Binding(
            get: { library.error != nil }, set: { if !$0 { library.error = nil } })) {
                Button(InterfaceText.ok.text(language)) { library.error = nil }
            } message: { Text(library.error?.message(language) ?? "") }
    }

    @ViewBuilder private func content(_ item: NativeTab) -> some View {
        switch item {
        case .home:
            NavigationStack(path: $preferences.homePath) {
                HomeView(isVisible: preferences.selectedTab == .home, openBook: { reader = $0 }, importBooks: { importing = true })
                    .navigationDestination(for: NativeDestination.self) { _ in HomeSettingsView() }
            }
        case .library:
            NavigationStack {
                BookshelfView(importResults: $importResults, openBook: { reader = $0 }, importBooks: { importing = true })
            }
        case .me:
            NavigationStack(path: $preferences.mePath) {
                MeView().navigationDestination(for: NativeDestination.self) { _ in HomeSettingsView() }
            }
        }
    }
}

struct BookshelfView: View {
    @Binding var importResults: [LibraryImportEntry]
    let openBook: (NativeBook) -> Void
    let importBooks: () -> Void
    @EnvironmentObject private var library: LibraryStore
    @Environment(\.interfaceLanguage) private var language
    @Environment(\.dynamicTypeSize) private var typeSize
    @EnvironmentObject private var preferences: AppPreferences
    @State private var deleting: NativeBook?
    private var books: [NativeBook] {
        library.books.filter { preferences.librarySearch.isEmpty || $0.title.localizedStandardContains(preferences.librarySearch) }
    }

    var body: some View {
        ScrollView {
            LazyVGrid(columns: [GridItem(.adaptive(minimum: typeSize.isAccessibilitySize ? 220 : 110, maximum: typeSize.isAccessibilitySize ? 320 : 180), spacing: 18)], spacing: 24) {
                ForEach(books) { book in
                    Button { openBook(book) } label: {
                        VStack(alignment: .leading, spacing: 8) {
                            Image(systemName: book.format == .pdf ? "doc.richtext" : "text.book.closed")
                                .font(.largeTitle).frame(maxWidth: .infinity).frame(height: 150)
                                .background(.quaternary, in: RoundedRectangle(cornerRadius: 10))
                            Text(book.title).font(.headline).lineLimit(2, reservesSpace: true)
                            Text(book.format.rawValue.uppercased()).font(.caption).foregroundStyle(.secondary)
                            ProgressView(value: book.progress)
                            Text(book.progress, format: .percent.precision(.fractionLength(0))).font(.caption)
                        }.contentShape(Rectangle())
                    }.buttonStyle(.plain).id(book.id)
                        .contextMenu { Button(InterfaceText.delete.text(language), role: .destructive) { deleting = book } }
                        .accessibilityAction(named: InterfaceText.delete.text(language)) { deleting = book }
                }
                Button(action: importBooks) {
                    Label(InterfaceText.importBook.text(language), systemImage: "plus").frame(maxWidth: .infinity, minHeight: 150)
                        .background(.quaternary, in: RoundedRectangle(cornerRadius: 10))
                }.buttonStyle(.plain)
            }.scrollTargetLayout().padding()
        }
        .scrollPosition(id: Binding<UUID?>(get: { preferences.libraryAnchor }, set: {
            if let target = $0 { preferences.libraryAnchor = target }
        }))
        .onChange(of: preferences.librarySearch) { _, _ in preferences.libraryAnchor = nil }
        .safeAreaInset(edge: .top) {
            if !importResults.isEmpty {
                ScrollView { resultsPanel }.frame(maxHeight: 240).background(.bar)
            }
        }
        .overlay {
            if library.books.isEmpty && importResults.isEmpty {
                ContentUnavailableView {
                    Label(InterfaceText.emptyTitle.text(language), systemImage: "books.vertical")
                } description: { Text(InterfaceText.emptyDetail.text(language)) }
                actions: { Button(InterfaceText.importBook.text(language), action: importBooks).buttonStyle(.borderedProminent) }
            } else if books.isEmpty && importResults.isEmpty {
                ContentUnavailableView(InterfaceText.search.text(language), systemImage: "magnifyingglass", description: Text(preferences.librarySearch))
            }
        }
        .searchable(text: $preferences.librarySearch, prompt: InterfaceText.search.text(language))
        .navigationTitle(InterfaceText.library.text(language))
        .toolbar { ToolbarItem(placement: .primaryAction) {
            Button(InterfaceText.importBook.text(language), systemImage: "plus", action: importBooks)
        } }
        .confirmationDialog(InterfaceText.delete.text(language), isPresented: Binding(
            get: { deleting != nil }, set: { if !$0 { deleting = nil } })) {
                Button(InterfaceText.delete.text(language), role: .destructive) {
                    if let deleting { library.remove(deleting) }; deleting = nil
                }
                Button(InterfaceText.cancel.text(language), role: .cancel) { deleting = nil }
            } message: { Text(deleting?.title ?? "") }
    }

    private var resultsPanel: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text(InterfaceText.importResults.text(language)).font(.headline)
            ForEach(importResults) { entry in
                VStack(alignment: .leading, spacing: 4) {
                    Text(entry.fileName).font(.headline)
                    Text(entry.status.text(language)).font(.subheadline)
                    if let failure = entry.failure { Text(failure.message(language)).font(.caption) }
                    if let book = entry.book, library.books.contains(where: { $0.id == book.id }) {
                        Button(InterfaceText.unread.text(language)) {
                            if let current = library.books.first(where: { $0.id == book.id }) { openBook(current) }
                        }.buttonStyle(.bordered)
                    }
                }
            }
            Button(InterfaceText.dismissResults.text(language)) { importResults = [] }
        }.frame(maxWidth: .infinity, alignment: .leading).padding()
    }

}

struct MeView: View {
    @EnvironmentObject private var preferences: AppPreferences
    @Environment(\.interfaceLanguage) private var language
    var body: some View {
        Form {
            Section(InterfaceText.appearance.text(language)) {
                NavigationLink(value: NativeDestination.homeSettings) {
                    Label(InterfaceText.homeSettings.text(language), systemImage: "house")
                }
                Picker(InterfaceText.theme.text(language), selection: $preferences.theme) {
                    ForEach(AppTheme.allCases, id: \.self) { Text($0.title.text(language)).tag($0) }
                }
            }
            Section(InterfaceText.language.text(language)) {
                Picker(InterfaceText.language.text(language), selection: $preferences.languageChoice) {
                    ForEach(LanguageChoice.allCases, id: \.self) { Text($0.title(language)).tag($0) }
                }
            }
            Section(InterfaceText.about.text(language)) {
                Text(InterfaceText.foundation.text(language))
                Label(InterfaceText.localOnly.text(language), systemImage: "iphone")
            }
        }.navigationTitle(InterfaceText.me.text(language))
    }
}
