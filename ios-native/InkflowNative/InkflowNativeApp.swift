import SwiftUI
import UniformTypeIdentifiers

@main
struct InkflowNativeApp: App {
    @StateObject private var library = LibraryStore()
    @Environment(\.scenePhase) private var scenePhase
    var body: some Scene {
        WindowGroup {
            BookshelfView().environmentObject(library)
                .onChange(of: scenePhase) { _, phase in if phase != .active { library.flush() } }
        }
    }
}

struct BookshelfView: View {
    @EnvironmentObject private var library: LibraryStore
    @State private var importing = false
    @State private var selection: UUID?

    var body: some View {
        NavigationSplitView {
            List(selection: $selection) {
                Section {
                    ForEach(library.books) { book in
                        NavigationLink(value: book.id) {
                            HStack(spacing: 14) {
                                Image(systemName: book.format == .pdf ? "doc.richtext" : "text.book.closed")
                                    .font(.title2).foregroundStyle(.tint).frame(width: 36, height: 54)
                                VStack(alignment: .leading, spacing: 6) {
                                    Text(book.title).font(.headline).lineLimit(2)
                                    Text("\(book.format.rawValue.uppercased()) · \(Int(book.progress * 100))% read")
                                        .font(.caption).foregroundStyle(.secondary)
                                    ProgressView(value: book.progress).tint(.accentColor)
                                }
                            }.padding(.vertical, 5)
                        }
                        .swipeActions { Button("Delete", role: .destructive) {
                            if selection == book.id { selection = nil }
                            library.remove(book)
                        } }
                    }
                } footer: {
                    Text("Native starter · TXT and PDF\nStored on this device. EPUB, sync, and AI provider sending are not included yet.")
                }
            }
            .overlay { if library.books.isEmpty { ContentUnavailableView("Your reading starts here",
                systemImage: "books.vertical", description: Text("Import a TXT or PDF from Files.")) } }
            .navigationTitle("Inkflow")
            .toolbar { ToolbarItem(placement: .primaryAction) {
                Button("Import", systemImage: "plus") { importing = true }
            } }
        } detail: {
            if let book = library.books.first(where: { $0.id == selection }) {
                ReaderView(book: book).id(book.id)
            } else { ContentUnavailableView("Choose a book", systemImage: "book", description: Text("Read locally, at your own pace.")) }
        }
        .fileImporter(isPresented: $importing, allowedContentTypes: [.plainText, .pdf], allowsMultipleSelection: true) { result in
            do {
                for url in try result.get() { selection = try library.importFile(url).id }
            } catch { library.error = error.localizedDescription }
        }
        .alert("Library", isPresented: Binding(get: { library.error != nil }, set: { if !$0 { library.error = nil } })) {
            Button("OK") { library.error = nil }
        } message: { Text(library.error ?? "") }
    }
}
