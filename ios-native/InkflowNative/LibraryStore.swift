import Foundation
import PDFKit
import SwiftUI
import CryptoKit

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
    var lastReadAt: Date? = nil
    var readerFontSize: Double? = nil
    var contentDigest: String? = nil
}

enum LibraryError: LocalizedError {
    case unsupported, tooLarge, invalidText, invalidPDF, damagedIndex, missingBook
    var errorDescription: String? { message(.en) }
    func message(_ language: InterfaceLanguage) -> String {
        let key: InterfaceText
        switch self {
        case .unsupported: key = .unsupported
        case .tooLarge: key = .tooLarge
        case .invalidText: key = .invalidText
        case .invalidPDF: key = .invalidPDF
        case .damagedIndex: key = .damagedIndex
        case .missingBook: key = .missingBook
        }
        return key.text(language)
    }
}

/// Resolve app-owned errors at display time so changing UI language never leaves stale English.
struct LibraryFailure {
    let operation: InterfaceText
    let cause: Error
    func message(_ language: InterfaceLanguage) -> String {
        (cause as? LibraryError)?.message(language) ?? cause.localizedDescription
    }
}

struct LibraryImportEntry: Identifiable {
    let id = UUID()
    let fileName: String
    let book: NativeBook?
    let status: InterfaceText
    let failure: LibraryFailure?
}

@MainActor
final class LibraryStore: ObservableObject {
    @Published private(set) var books: [NativeBook] = []
    @Published var error: LibraryFailure?
    let directory: URL
    private let maximumSize = 64 * 1024 * 1024
    private var saveTask: Task<Void, Never>?
    private var writableIndex = true
    private var indexURL: URL { directory.appendingPathComponent("library.json") }

    init(directory: URL? = nil) {
        self.directory = directory ?? FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("NativeLibrary", isDirectory: true)
        do {
            try FileManager.default.createDirectory(at: self.directory, withIntermediateDirectories: true)
            if FileManager.default.fileExists(atPath: indexURL.path) {
                var decoded = try JSONDecoder().decode([NativeBook].self, from: Data(contentsOf: indexURL))
                guard Set(decoded.map(\.id)).count == decoded.count,
                      decoded.allSatisfy({ $0.fileName == "\($0.id.uuidString).\($0.format.rawValue)" }) else {
                    throw LibraryError.damagedIndex
                }
                for index in decoded.indices {
                    decoded[index].progress = min(1, max(0, decoded[index].progress))
                    decoded[index].textOffset = max(0, decoded[index].textOffset)
                    decoded[index].pdfPage = max(0, decoded[index].pdfPage)
                    if let size = decoded[index].readerFontSize { decoded[index].readerFontSize = ReadingLocation.fontSize(size) }
                }
                books = decoded
            }
        } catch {
            writableIndex = false
            self.error = LibraryFailure(operation: .loadLibraryError, cause: LibraryError.damagedIndex)
        }
    }

    // Derive paths from typed identity, never from caller-supplied fileName/path components.
    func fileURL(for book: NativeBook) -> URL { directory.appendingPathComponent("\(book.id.uuidString).\(book.format.rawValue)") }

    func loadText(for book: NativeBook) throws -> String {
        let owned = try ownedBook(book.id)
        guard owned.format == .txt else { throw LibraryError.unsupported }
        let data = try boundedData(fileURL(for: owned))
        guard let text = String(data: data, encoding: .utf8), !text.isEmpty, !text.contains("\0") else { throw LibraryError.invalidText }
        return text
    }

    func loadPDF(for book: NativeBook) throws -> PDFDocument {
        let owned = try ownedBook(book.id)
        guard owned.format == .pdf else { throw LibraryError.unsupported }
        let data = try boundedData(fileURL(for: owned))
        guard let document = PDFDocument(data: data), !document.isLocked, document.pageCount > 0 else { throw LibraryError.invalidPDF }
        return document
    }

    @discardableResult
    func importFile(_ source: URL) throws -> NativeBook {
        guard let book = try importEntry(source).book else { throw LibraryError.missingBook }
        return book
    }

    /// One failure never hides earlier successes or prevents processing later files.
    func importFiles(_ sources: [URL]) -> [LibraryImportEntry] {
        sources.map { source in
            do { return try importEntry(source) }
            catch {
                return LibraryImportEntry(fileName: source.lastPathComponent, book: nil, status: .importFailed,
                    failure: LibraryFailure(operation: .importFailed, cause: error))
            }
        }
    }

    private func importEntry(_ source: URL) throws -> LibraryImportEntry {
        guard writableIndex else { throw LibraryError.damagedIndex }
        let scoped = source.startAccessingSecurityScopedResource()
        defer { if scoped { source.stopAccessingSecurityScopedResource() } }
        guard let format = BookFormat(rawValue: source.pathExtension.lowercased()) else { throw LibraryError.unsupported }
        let data = try boundedData(source)
        let imported: Data
        if format == .txt {
            let encoding: String.Encoding = data.starts(with: [0xFF, 0xFE]) || data.starts(with: [0xFE, 0xFF]) ? .utf16 : .utf8
            guard var text = String(data: data, encoding: encoding) else { throw LibraryError.invalidText }
            if data.starts(with: [0xEF, 0xBB, 0xBF]), text.first == "\u{FEFF}" { text.removeFirst() }
            guard !text.isEmpty, !text.contains("\0") else { throw LibraryError.invalidText }
            imported = Data(text.utf8)
        } else {
            guard let document = PDFDocument(data: data), !document.isLocked, document.pageCount > 0 else { throw LibraryError.invalidPDF }
            imported = data
        }
        guard imported.count <= maximumSize else { throw LibraryError.tooLarge }
        let digest = Self.digest(imported)
        for (index, existing) in books.enumerated() where existing.format == format {
            let target = fileURL(for: existing)
            if !FileManager.default.fileExists(atPath: target.path), existing.contentDigest == digest {
                // Explicit reimport can repair a missing managed copy without discarding its position.
                try imported.write(to: target, options: .atomic)
                return LibraryImportEntry(fileName: source.lastPathComponent, book: existing, status: .restoredFile, failure: nil)
            }
            if let known = existing.contentDigest, known != digest { continue }
            guard let stored = try? boundedData(target), Self.digest(stored) == digest else { continue }
            var duplicate = existing
            if duplicate.contentDigest == nil {
                duplicate.contentDigest = digest
                var updated = books; updated[index] = duplicate
                try persist(updated); books = updated
            }
            return LibraryImportEntry(fileName: source.lastPathComponent, book: duplicate, status: .alreadyImported, failure: nil)
        }
        let id = UUID()
        var book = NativeBook(id: id, title: source.deletingPathExtension().lastPathComponent,
            fileName: "\(id.uuidString).\(format.rawValue)", format: format, importedAt: Date())
        book.contentDigest = digest
        let target = fileURL(for: book)
        try imported.write(to: target, options: .atomic)
        do { try persist([book] + books) }
        catch { try? FileManager.default.removeItem(at: target); throw error }
        books.insert(book, at: 0)
        return LibraryImportEntry(fileName: source.lastPathComponent, book: book, status: .imported, failure: nil)
    }

    var home: LibraryHomeSnapshot { LibraryHomeSnapshot(books: books) }

    func markOpened(id: UUID, at date: Date = Date()) {
        guard let index = books.firstIndex(where: { $0.id == id }) else { return }
        books[index].lastReadAt = date
        flush()
    }

    func updateFontSize(id: UUID, value: Double) {
        guard value.isFinite, let index = books.firstIndex(where: { $0.id == id }) else { return }
        books[index].readerFontSize = ReadingLocation.fontSize(value)
        flush()
    }

    func updatePosition(id: UUID, progress: Double, textOffset: Int? = nil, pdfPage: Int? = nil) {
        guard progress.isFinite, let index = books.firstIndex(where: { $0.id == id }) else { return }
        books[index].progress = min(1, max(0, progress))
        books[index].lastReadAt = Date()
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
        guard let owned = books.first(where: { $0.id == book.id }) else { return }
        do {
            let remaining = books.filter { $0.id != owned.id }
            try persist(remaining)
            books = remaining
            try? FileManager.default.removeItem(at: fileURL(for: owned))
        } catch { self.error = LibraryFailure(operation: .delete, cause: error) }
    }

    func flush() {
        saveTask?.cancel()
        do { try persist(books) }
        catch { self.error = LibraryFailure(operation: .saveLibraryError, cause: error) }
    }

    private func persist(_ entries: [NativeBook]) throws {
        guard writableIndex else { throw LibraryError.damagedIndex }
        try JSONEncoder().encode(entries).write(to: indexURL, options: .atomic)
    }

    private func ownedBook(_ id: UUID) throws -> NativeBook {
        guard let book = books.first(where: { $0.id == id }) else { throw LibraryError.missingBook }
        return book
    }

    private func boundedData(_ url: URL) throws -> Data {
        guard FileManager.default.fileExists(atPath: url.path) else { throw LibraryError.missingBook }
        guard (try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0) <= maximumSize else { throw LibraryError.tooLarge }
        let data = try Data(contentsOf: url, options: .mappedIfSafe)
        guard data.count <= maximumSize else { throw LibraryError.tooLarge }
        return data
    }

    private static func digest(_ data: Data) -> String { SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined() }
}

/// All Home styles consume this index-only projection; no document reads or invented durations.
struct LibraryHomeSnapshot {
    let books: [NativeBook]
    var recent: [NativeBook] {
        books.filter { $0.lastReadAt != nil || $0.progress > 0 || $0.textOffset > 0 || $0.pdfPage > 0 }
            .sorted {
                let left = $0.lastReadAt ?? .distantPast, right = $1.lastReadAt ?? .distantPast
                if left != right { return left > right }
                if $0.importedAt != $1.importedAt { return $0.importedAt > $1.importedAt }
                return $0.id.uuidString < $1.id.uuidString
            }
    }
    var featured: NativeBook? { recent.first ?? books.first }
    var openedCount: Int { recent.count }
    var completedCount: Int { books.filter { $0.progress >= 1 }.count }
}
