import Foundation
import PDFKit
import SwiftUI

enum BookFormat: String, Codable { case txt, pdf }

struct NativeBook: Identifiable, Codable, Equatable {
    let id: UUID
    var title: String
    let fileName: String
    let format: BookFormat
    let importedAt: Date
    var progress: Double = 0
    var textOffset: Int = 0
    var pdfPage: Int = 0
}

enum LibraryError: LocalizedError {
    case unsupported, tooLarge, invalidText, invalidPDF
    var errorDescription: String? {
        switch self {
        case .unsupported: return "This starter supports TXT and PDF files."
        case .tooLarge: return "Choose a file smaller than 64 MB."
        case .invalidText: return "TXT files must use UTF-8 or BOM-marked UTF-16 encoding."
        case .invalidPDF: return "The PDF is empty, damaged, or password protected."
        }
    }
}

@MainActor
final class LibraryStore: ObservableObject {
    @Published private(set) var books: [NativeBook] = []
    @Published var error: String?
    let directory: URL
    private var saveTask: Task<Void, Never>?
    private var writableIndex = true
    private var indexURL: URL { directory.appendingPathComponent("library.json") }

    init(directory: URL? = nil) {
        self.directory = directory ?? FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("NativeLibrary", isDirectory: true)
        do {
            try FileManager.default.createDirectory(at: self.directory, withIntermediateDirectories: true)
            if FileManager.default.fileExists(atPath: indexURL.path) {
                books = try JSONDecoder().decode([NativeBook].self, from: Data(contentsOf: indexURL))
                // A corrupt or foreign index must not escape the app's library directory.
                guard books.allSatisfy({ $0.fileName == "\($0.id.uuidString).\($0.format.rawValue)" }) else {
                    books = []
                    throw CocoaError(.fileReadCorruptFile)
                }
            }
        } catch { writableIndex = false; self.error = "Could not load the library: \(error.localizedDescription)" }
    }

    func fileURL(for book: NativeBook) -> URL { directory.appendingPathComponent(book.fileName) }

    @discardableResult
    func importFile(_ source: URL) throws -> NativeBook {
        let scoped = source.startAccessingSecurityScopedResource()
        defer { if scoped { source.stopAccessingSecurityScopedResource() } }
        guard let format = BookFormat(rawValue: source.pathExtension.lowercased()) else { throw LibraryError.unsupported }
        let size = try source.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
        guard size <= 64 * 1024 * 1024 else { throw LibraryError.tooLarge }
        let data = try Data(contentsOf: source, options: .mappedIfSafe)
        guard data.count <= 64 * 1024 * 1024 else { throw LibraryError.tooLarge }
        let id = UUID()
        let book = NativeBook(id: id, title: source.deletingPathExtension().lastPathComponent,
            fileName: "\(id.uuidString).\(format.rawValue)", format: format, importedAt: Date())
        let imported: Data
        if format == .txt {
            let encoding: String.Encoding = data.starts(with: [0xFF, 0xFE]) || data.starts(with: [0xFE, 0xFF]) ? .utf16 : .utf8
            guard let text = String(data: data, encoding: encoding), !text.isEmpty, !text.contains("\0") else {
                throw LibraryError.invalidText
            }
            imported = Data(text.utf8)
        } else {
            guard let document = PDFDocument(data: data), !document.isLocked, document.pageCount > 0 else { throw LibraryError.invalidPDF }
            imported = data
        }
        let target = fileURL(for: book)
        try imported.write(to: target, options: .atomic)
        do { try persist([book] + books) }
        catch { try? FileManager.default.removeItem(at: target); throw error }
        books.insert(book, at: 0)
        return book
    }

    func updatePosition(id: UUID, progress: Double, textOffset: Int? = nil, pdfPage: Int? = nil) {
        guard progress.isFinite, let index = books.firstIndex(where: { $0.id == id }) else { return }
        books[index].progress = min(1, max(0, progress))
        if let textOffset { books[index].textOffset = max(0, textOffset) }
        if let pdfPage { books[index].pdfPage = max(0, pdfPage) }
        saveTask?.cancel()
        saveTask = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(500))
            guard !Task.isCancelled else { return }
            self?.flush()
        }
    }

    func remove(_ book: NativeBook) {
        do {
            let remaining = books.filter { $0.id != book.id }
            try persist(remaining)
            books = remaining
            try? FileManager.default.removeItem(at: fileURL(for: book))
        } catch { self.error = error.localizedDescription }
    }

    func flush() {
        saveTask?.cancel()
        do { try persist(books) } catch { self.error = error.localizedDescription }
    }

    private func persist(_ entries: [NativeBook]) throws {
        guard writableIndex else { throw CocoaError(.fileReadCorruptFile) }
        try JSONEncoder().encode(entries).write(to: indexURL, options: .atomic)
    }
}
